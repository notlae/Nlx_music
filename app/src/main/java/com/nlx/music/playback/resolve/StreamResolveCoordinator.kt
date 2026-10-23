package com.nlx.music.playback.resolve

import com.nlx.music.data.music.YouTubeAudioStream
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * Playback resolver always wins. Prefetch is cancelled when the user selects
 * a track and is limited to a single next-track job.
 */
class StreamResolveCoordinator(
    cacheDir: File,
    private val scope: CoroutineScope,
    private val extract: suspend (youtubeVideoId: String) -> YouTubeAudioStream,
    private val logger: ResolverLogger = AndroidResolverLogger,
) {
    val cache = PlaybackStreamCache(cacheDir)
    val playback = PlaybackResolver(cache, logger)
    val prefetch = PrefetchResolver(cache, logger)
    private val extraction = StreamExtraction(scope, cache, extract)

    fun cancelAll(reason: String) {
        val previous = playback.requested
        playback.invalidate()
        prefetch.cancel()
        logger.event(ResolveEvent.CANCELLED, previous, 0L, null, reason)
    }

    /**
     * Resolves [identity] for the user-selected track.
     * Joins an in-flight resolve of the same identity. Any other identity is stale.
     */
    suspend fun resolvePlayback(identity: TrackIdentity, durationMs: Long?): StreamCache {
        val active = playback.requested
        val token = playback.claim(identity)
        if (active == null) prefetch.cancel()
        return playback.resolve(token, identity, durationMs, extraction::await)
    }

    fun prefetchNext(
        identity: TrackIdentity,
        durationMs: Long?,
        onReady: suspend (StreamCache) -> Unit,
    ) {
        if (playback.requested?.youtubeVideoId == identity.youtubeVideoId) return
        prefetch.enqueue(
            scope = scope,
            identity = identity,
            durationMs = durationMs,
            activePlayback = playback::requested,
            extract = extraction::await,
            onReady = onReady,
        )
    }
}
