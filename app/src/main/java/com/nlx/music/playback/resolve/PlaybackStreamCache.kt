package com.nlx.music.playback.resolve

import android.util.Log
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Memory plus disk cache of signed stream URLs.
 * The map key is [StreamCache.youtubeVideoId]. Track id is stored on the
 * entry and is not required to reuse the URL.
 */
class PlaybackStreamCache(
    private val directory: File,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val maxEntries: Int = MAX_ENTRIES,
) {
    private val memory = ConcurrentHashMap<String, StreamCache>()
    private val order = ArrayDeque<String>()
    private val lock = Any()
    private val file = File(directory, "streams.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        load()
    }

    fun get(identity: TrackIdentity): StreamCache? {
        val stored = memory[identity.youtubeVideoId]
        if (stored == null) {
            log("CACHE_MISS", identity.youtubeVideoId, "trackId=${identity.trackId}")
            return null
        }
        if (stored.youtubeVideoId != identity.youtubeVideoId || stored.streamUrl.isBlank()) {
            log("CACHE_MISS", identity.youtubeVideoId, "trackId=${identity.trackId} emptyUrl=true")
            return null
        }
        if (stored.isExpired(nowMs())) {
            val remaining = stored.expiresAt - nowMs()
            log(
                "CACHE_EXPIRY",
                stored.youtubeVideoId,
                "trackId=${stored.trackId} expiresAt=${stored.expiresAt} remainingMs=$remaining",
            )
            invalidate(identity)
            return null
        }
        log(
            "CACHE_HIT",
            stored.youtubeVideoId,
            "trackId=${identity.trackId} expiresAt=${stored.expiresAt} remainingMs=${stored.expiresAt - nowMs()}",
        )
        return stored.copy(trackId = identity.trackId)
    }

    /** Playback owns the video id and may replace a prefetch entry. */
    fun putFromPlayback(entry: StreamCache) {
        store(entry, replace = true, source = "playback")
    }

    /**
     * Prefetch or a detached extract. Returns false when a fresh URL is
     * already stored so a background resolve cannot replace the playing URL.
     */
    fun putIfAbsent(entry: StreamCache): Boolean = store(entry, replace = false, source = "prefetch")

    fun invalidate(identity: TrackIdentity) {
        synchronized(lock) {
            val removed = memory.remove(identity.youtubeVideoId)
            if (removed == null && !order.contains(identity.youtubeVideoId)) return
            order.remove(identity.youtubeVideoId)
            if (removed != null) {
                log("CACHE_EVICT", identity.youtubeVideoId, "reason=invalidate trackId=${removed.trackId}")
            }
            persistLocked()
        }
    }

    fun clear() {
        synchronized(lock) {
            memory.clear()
            order.clear()
            persistLocked()
        }
    }

    private fun store(entry: StreamCache, replace: Boolean, source: String): Boolean {
        require(entry.trackId.isNotBlank() && entry.youtubeVideoId.isNotBlank())
        require(entry.streamUrl.isNotBlank()) { "streamUrl is required" }
        synchronized(lock) {
            val key = entry.youtubeVideoId
            val existing = memory[key]
            if (!replace && existing != null && existing.streamUrl.isNotBlank() && !existing.isExpired(nowMs())) {
                log("CACHE_HIT", key, "source=$source keptExisting=true expiresAt=${existing.expiresAt}")
                return false
            }
            memory[key] = entry
            order.remove(key)
            order.addLast(key)
            trimLocked()
            persistLocked()
            log(
                "CACHE_INSERT",
                key,
                "source=$source trackId=${entry.trackId} expiresAt=${entry.expiresAt} " +
                    "url=${entry.streamUrl.substringBefore('?').take(80)}",
            )
            return true
        }
    }

    private fun load() {
        val encoded = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull() ?: return
        val entries = runCatching {
            json.decodeFromString(ListSerializer(StreamCache.serializer()), encoded)
        }.getOrNull() ?: return
        synchronized(lock) {
            memory.clear()
            order.clear()
            entries.forEach { entry ->
                if (entry.trackId.isBlank() || entry.youtubeVideoId.isBlank() || entry.streamUrl.isBlank()) return@forEach
                if (entry.isExpired(nowMs())) {
                    log(
                        "CACHE_EXPIRY",
                        entry.youtubeVideoId,
                        "source=disk expiresAt=${entry.expiresAt}",
                    )
                    return@forEach
                }
                val previous = memory[entry.youtubeVideoId]
                if (previous != null && previous.expiresAt >= entry.expiresAt) return@forEach
                memory[entry.youtubeVideoId] = entry
            }
            memory.keys.forEach { order.addLast(it) }
            trimLocked()
        }
    }

    private fun trimLocked() {
        val now = nowMs()
        val expired = memory.filterValues { it.isExpired(now) }.keys.toList()
        expired.forEach { key ->
            val removed = memory.remove(key)
            order.remove(key)
            if (removed != null) {
                log("CACHE_EXPIRY", key, "source=trim expiresAt=${removed.expiresAt}")
            }
        }
        while (memory.size > maxEntries && order.isNotEmpty()) {
            val oldest = order.removeFirst()
            val removed = memory.remove(oldest)
            if (removed != null) {
                log("CACHE_EVICT", oldest, "reason=capacity trackId=${removed.trackId}")
            }
        }
    }

    private fun persistLocked() {
        runCatching {
            directory.mkdirs()
            val payload = json.encodeToString(
                ListSerializer(StreamCache.serializer()),
                order.mapNotNull { memory[it] },
            )
            val tmp = File(directory, "streams.json.tmp")
            tmp.writeText(payload)
            if (!tmp.renameTo(file)) {
                file.writeText(payload)
                tmp.delete()
            }
        }
    }

    private fun log(event: String, youtubeVideoId: String, detail: String) {
        Log.i(TAG, "[$event] youtubeVideoId=$youtubeVideoId $detail")
    }

    private companion object {
        const val MAX_ENTRIES = 128
        const val TAG = "NlxMusicResolver"
    }
}
