package com.nlx.music.data.artwork

import com.nlx.music.playback.resolve.MetadataLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Remote artwork lookups wait [settleMs] after a track change. A memory-cache
 * hit returns immediately and does not start a timer. A newer track cancels
 * the pending lookup before it can call [resolve].
 */
internal class DeferredArtworkLookup(
    private val scope: CoroutineScope,
    private val source: String,
    private val settleMs: Long = MetadataLog.SETTLE_MS,
    private val hasMemoryHit: (key: String) -> Boolean,
    private val resolve: suspend (name: String, artist: String) -> Unit,
    private val log: (event: String, key: String) -> Unit = { event, key ->
        when (event) {
            "delayed" -> MetadataLog.delayed(source, key)
            "started" -> MetadataLog.started(source, key)
            "skipped" -> MetadataLog.skipped(source, key)
        }
    },
) {
    private val lock = Any()
    private var job: Job? = null
    private var activeKey: String? = null

    fun request(name: String, artist: String) {
        val key = ArtworkNormalizer.cacheKey(name, artist)
        synchronized(lock) {
            if (hasMemoryHit(key)) {
                cancelLocked(logSkip = activeKey != null && activeKey != key)
                activeKey = key
                return
            }
            if (activeKey == key && job?.isActive == true) return
            cancelLocked(logSkip = job?.isActive == true)
            activeKey = key
            log("delayed", key)
            job = scope.launch {
                try {
                    delay(settleMs)
                    val stillCurrent = synchronized(lock) { activeKey == key }
                    if (!stillCurrent) {
                        log("skipped", key)
                        return@launch
                    }
                    log("started", key)
                    resolve(name, artist)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                }
            }
        }
    }

    fun cancel() {
        synchronized(lock) {
            cancelLocked(logSkip = job?.isActive == true)
            activeKey = null
        }
    }

    private fun cancelLocked(logSkip: Boolean) {
        val previous = activeKey
        job?.cancel()
        job = null
        if (logSkip && previous != null) log("skipped", previous)
    }
}
