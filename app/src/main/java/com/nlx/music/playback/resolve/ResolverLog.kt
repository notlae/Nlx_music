package com.nlx.music.playback.resolve

import android.util.Log

enum class ResolveEvent {
    START,
    CANCELLED,
    CACHE_HIT,
    CACHE_MISS,
    SUCCESS,
    FAILED,
    IGNORED_STALE,
}

fun interface ResolverLogger {
    fun event(
        event: ResolveEvent,
        identity: TrackIdentity?,
        elapsedMs: Long,
        durationMs: Long?,
        detail: String,
    )
}

object AndroidResolverLogger : ResolverLogger {
    override fun event(
        event: ResolveEvent,
        identity: TrackIdentity?,
        elapsedMs: Long,
        durationMs: Long?,
        detail: String,
    ) {
        val tag = when (event) {
            ResolveEvent.START -> "RESOLVE_START"
            ResolveEvent.CANCELLED -> "RESOLVE_CANCELLED"
            ResolveEvent.CACHE_HIT -> "RESOLVE_CACHE_HIT"
            ResolveEvent.CACHE_MISS -> "RESOLVE_CACHE_MISS"
            ResolveEvent.SUCCESS -> "RESOLVE_SUCCESS"
            ResolveEvent.FAILED -> "RESOLVE_FAILED"
            ResolveEvent.IGNORED_STALE -> "RESOLVE_IGNORED_STALE"
        }
        Log.i(
            TAG,
            "[$tag] trackId=${identity?.trackId.orEmpty()} " +
                "youtubeVideoId=${identity?.youtubeVideoId.orEmpty()} " +
                "durationMs=${durationMs ?: -1} elapsedMs=$elapsedMs $detail",
        )
    }

    private const val TAG = "NlxMusicResolver"
}

internal fun elapsedSince(startedNanos: Long): Long =
    (System.nanoTime() - startedNanos).coerceAtLeast(0L) / 1_000_000L

internal object MetadataLog {
    const val SETTLE_MS = 3_000L

    fun delayed(source: String, videoId: String?) {
        android.util.Log.i(TAG, "[METADATA_DELAYED] source=$source videoId=${videoId.orEmpty()} priority=low")
    }

    fun started(source: String, videoId: String?) {
        android.util.Log.i(TAG, "[METADATA_STARTED] source=$source videoId=${videoId.orEmpty()} priority=low")
    }

    fun skipped(source: String, videoId: String?) {
        android.util.Log.i(TAG, "[METADATA_SKIPPED] source=$source videoId=${videoId.orEmpty()} priority=low")
    }

    private const val TAG = "NlxMusicMetadata"
}
