package com.nlx.music.playback.resolve

import android.util.Log
import com.nlx.music.data.music.YouTubeAudioStream
import com.nlx.music.data.music.isCooperativeCancellation
import com.nlx.music.data.music.isExpectedAudioResolutionFailure
import com.nlx.music.data.music.resolutionFailureDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Resolves at most the next track. It never claims the playback generation
 * and never replaces a cache entry playback has already stored.
 */
class PrefetchResolver(
    private val cache: PlaybackStreamCache,
    private val logger: ResolverLogger,
) {
    private var job: Job? = null
    private var activeVideoId: String? = null
    private val delivered = HashSet<String>()

    fun cancel() {
        job?.cancel()
        job = null
        activeVideoId = null
    }

    fun enqueue(
        scope: CoroutineScope,
        identity: TrackIdentity,
        durationMs: Long?,
        activePlayback: () -> TrackIdentity?,
        extract: suspend (youtubeVideoId: String) -> YouTubeAudioStream,
        onReady: suspend (StreamCache) -> Unit,
    ) {
        val videoId = identity.youtubeVideoId
        if (activePlayback()?.youtubeVideoId == videoId) return
        if (job?.isActive == true && activeVideoId == videoId) {
            Log.i(TAG, "[PREFETCH_SKIPPED_ALREADY_ACTIVE] youtubeVideoId=$videoId")
            return
        }
        if (videoId in delivered && cache.get(identity) != null) {
            Log.i(TAG, "[PREFETCH_SKIPPED_CACHED] youtubeVideoId=$videoId")
            return
        }
        cancel()
        activeVideoId = videoId
        Log.i(TAG, "[PREFETCH_SCHEDULED] youtubeVideoId=$videoId")
        job = scope.launch(Dispatchers.IO) {
            val started = System.nanoTime()
            try {
                logger.event(ResolveEvent.START, identity, 0L, durationMs, "prefetch")
                val hit = cache.get(identity)
                if (hit != null) {
                    logger.event(
                        ResolveEvent.CACHE_HIT,
                        identity,
                        elapsedSince(started),
                        hit.durationMs ?: durationMs,
                        "prefetch",
                    )
                    if (activePlayback()?.youtubeVideoId == identity.youtubeVideoId) return@launch
                    onReady(hit)
                    delivered += videoId
                    Log.i(
                        TAG,
                        "[PREFETCH_COMPLETED] youtubeVideoId=$videoId elapsedMs=${elapsedSince(started)}",
                    )
                    return@launch
                }
                logger.event(ResolveEvent.CACHE_MISS, identity, elapsedSince(started), durationMs, "prefetch")
                val extracted = extract(identity.youtubeVideoId)
                ensureActive()
                if (extracted.videoId != identity.youtubeVideoId) {
                    logger.event(
                        ResolveEvent.IGNORED_STALE,
                        identity,
                        elapsedSince(started),
                        durationMs,
                        "resolvedTrackId=${extracted.videoId}",
                    )
                    return@launch
                }
                if (activePlayback()?.youtubeVideoId == identity.youtubeVideoId) {
                    logger.event(
                        ResolveEvent.IGNORED_STALE,
                        identity,
                        elapsedSince(started),
                        durationMs,
                        "playback-owns",
                    )
                    return@launch
                }
                val entry = extracted.toStreamCache(identity)
                val stored = if (cache.putIfAbsent(entry)) entry else cache.get(identity) ?: return@launch
                logger.event(
                    ResolveEvent.SUCCESS,
                    identity,
                    elapsedSince(started),
                    stored.durationMs ?: durationMs,
                    "prefetch",
                )
                if (activePlayback()?.youtubeVideoId == identity.youtubeVideoId) return@launch
                onReady(stored)
                delivered += videoId
                Log.i(
                    TAG,
                    "[PREFETCH_COMPLETED] youtubeVideoId=$videoId elapsedMs=${elapsedSince(started)}",
                )
            } catch (error: Throwable) {
                if (error.isCooperativeCancellation()) {
                    if (activeVideoId == videoId) activeVideoId = null
                    logger.event(
                        ResolveEvent.CANCELLED,
                        identity,
                        elapsedSince(started),
                        durationMs,
                        "prefetch flight-continues",
                    )
                    throw error
                }
                if (activeVideoId == videoId) activeVideoId = null
                val detail = resolutionFailureDetail(error)
                logger.event(ResolveEvent.FAILED, identity, elapsedSince(started), durationMs, "prefetch $detail")
                Log.w(TAG, "[RESOLVE_FAILED] youtubeVideoId=$videoId $detail")
                if (!error.isExpectedAudioResolutionFailure()) throw error
            }
        }
    }

    private companion object {
        const val TAG = "NlxMusicResolver"
    }
}
