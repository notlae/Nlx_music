package com.nlx.music.playback.resolve

import android.util.Log
import com.nlx.music.data.music.YouTubeAudioStream
import com.nlx.music.data.music.containedAudioResolutionFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentHashMap

/**
 * One player extract per YouTube video id.
 * A second caller waits on the same job. Cancelling the caller does not
 * cancel the job, so a prefetch that the user skips still writes the URL.
 */
class StreamExtraction(
    parent: CoroutineScope,
    private val cache: PlaybackStreamCache,
    private val extract: suspend (youtubeVideoId: String) -> YouTubeAudioStream,
) {
    // Independent of the caller's job. Cancelling prefetch or playback
    // must not cancel an extract that is about to write the URL.
    // The scope ends when the player scope ends.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + containedAudioResolutionFailure(TAG),
    )

    init {
        parent.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion {
            scope.cancel()
        }
    }
    private val flights = ConcurrentHashMap<String, Deferred<YouTubeAudioStream>>()
    private val lock = Any()

    suspend fun await(youtubeVideoId: String): YouTubeAudioStream {
        val flight = synchronized(lock) {
            val current = flights[youtubeVideoId]
            if (current != null && current.isActive) {
                Log.i(TAG, "[EXTRACT_JOIN] youtubeVideoId=$youtubeVideoId")
                current
            } else {
                val created = scope.async { execute(youtubeVideoId) }
                flights[youtubeVideoId] = created
                created.invokeOnCompletion { flights.remove(youtubeVideoId, created) }
                created
            }
        }
        return flight.await()
    }

    private suspend fun execute(youtubeVideoId: String): YouTubeAudioStream {
        val started = System.nanoTime()
        Log.i(TAG, "[EXTRACT_START] youtubeVideoId=$youtubeVideoId")
        try {
            cache.get(TrackIdentity(youtubeVideoId, youtubeVideoId))?.let { hit ->
                Log.i(
                    TAG,
                    "[EXTRACT_END] youtubeVideoId=$youtubeVideoId elapsedMs=${elapsedSince(started)} cached=true",
                )
                return hit.toYouTubeAudioStream()
            }
            val stream = extract(youtubeVideoId)
            if (stream.videoId == youtubeVideoId && stream.url.isNotBlank()) {
                cache.putIfAbsent(stream.toStreamCache(TrackIdentity(youtubeVideoId, youtubeVideoId)))
            }
            Log.i(
                TAG,
                "[EXTRACT_END] youtubeVideoId=$youtubeVideoId elapsedMs=${elapsedSince(started)} " +
                    "cached=${stream.videoId == youtubeVideoId && stream.url.isNotBlank()}",
            )
            return stream
        } catch (error: Throwable) {
            Log.i(
                TAG,
                "[EXTRACT_END] youtubeVideoId=$youtubeVideoId elapsedMs=${elapsedSince(started)} " +
                    "cached=false error=${error::class.java.simpleName}",
            )
            throw error
        }
    }

    private companion object {
        const val TAG = "NlxMusicResolver"
    }
}
