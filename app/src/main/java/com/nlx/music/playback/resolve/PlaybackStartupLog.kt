package com.nlx.music.playback.resolve

import android.util.Log

/**
 * One playback request, from the tap until ExoPlayer reports ready.
 * Durations are wall time on that request. A new [begin] drops the previous one.
 */
object PlaybackStartupLog {
    private val lock = Any()
    private var tapElapsedMs = 0L
    private var cacheStartMs = 0L
    private var videoStartMs = 0L
    private var extractStartMs = 0L
    private var prepareStartMs = 0L
    private var cacheMs = 0L
    private var videoIdMs = 0L
    private var extractMs = 0L
    private var prepareMs = 0L
    private var cacheHit = false
    private var lookedUpVideoId = false
    private var extracted = false
    private var prepareEnded = false
    private var readyLogged = false
    private var trackId = ""
    private var youtubeVideoId = ""

    fun begin(trackId: String, youtubeVideoId: String?) {
        synchronized(lock) {
            tapElapsedMs = android.os.SystemClock.elapsedRealtime()
            cacheStartMs = 0L
            videoStartMs = 0L
            extractStartMs = 0L
            prepareStartMs = 0L
            cacheMs = 0L
            videoIdMs = 0L
            extractMs = 0L
            prepareMs = 0L
            cacheHit = false
            lookedUpVideoId = false
            extracted = false
            prepareEnded = false
            readyLogged = false
            this.trackId = trackId
            this.youtubeVideoId = youtubeVideoId.orEmpty()
        }
        event("TAP_TRACK")
    }

    fun cacheLookupStart() {
        synchronized(lock) { cacheStartMs = android.os.SystemClock.elapsedRealtime() }
        event("CACHE_LOOKUP_START")
    }

    fun cacheLookupEnd(hit: Boolean) {
        synchronized(lock) {
            cacheHit = hit
            cacheMs = elapsedSince(cacheStartMs)
        }
        event("CACHE_LOOKUP_END")
    }

    fun videoIdLookupStart() {
        synchronized(lock) {
            lookedUpVideoId = true
            videoStartMs = android.os.SystemClock.elapsedRealtime()
        }
        event("VIDEO_ID_LOOKUP_START")
    }

    fun videoIdLookupEnd() {
        synchronized(lock) { videoIdMs = elapsedSince(videoStartMs) }
        event("VIDEO_ID_LOOKUP_END")
    }

    fun extractStart() {
        synchronized(lock) {
            extracted = true
            extractStartMs = android.os.SystemClock.elapsedRealtime()
        }
        event("STREAM_EXTRACT_START")
    }

    fun extractEnd() {
        synchronized(lock) { extractMs = elapsedSince(extractStartMs) }
        event("STREAM_EXTRACT_END")
    }

    fun prepareStart() {
        synchronized(lock) { prepareStartMs = android.os.SystemClock.elapsedRealtime() }
        event("PLAYER_PREPARE_START")
    }

    fun prepareEnd() {
        val shouldLog = synchronized(lock) {
            if (prepareEnded || prepareStartMs == 0L) return
            prepareEnded = true
            prepareMs = elapsedSince(prepareStartMs)
            true
        }
        if (shouldLog) event("PLAYER_PREPARE_END")
    }

    fun ready() {
        val summary = synchronized(lock) {
            if (readyLogged || tapElapsedMs == 0L) return
            readyLogged = true
            if (!prepareEnded && prepareStartMs != 0L) {
                prepareEnded = true
                prepareMs = elapsedSince(prepareStartMs)
            }
            val total = elapsedSince(tapElapsedMs)
            "totalMs=$total cacheMs=$cacheMs videoIdMs=$videoIdMs extractMs=$extractMs " +
                "prepareMs=$prepareMs cacheHit=$cacheHit videoIdLookup=$lookedUpVideoId " +
                "extracted=$extracted trackId=$trackId youtubeVideoId=$youtubeVideoId"
        }
        event("PLAYER_READY")
        Log.i(TAG, "[STARTUP] $summary")
    }

    private fun event(name: String) {
        val elapsed = synchronized(lock) { elapsedSince(tapElapsedMs) }
        Log.i(
            TAG,
            "[$name] trackId=$trackId youtubeVideoId=$youtubeVideoId elapsedMs=$elapsed",
        )
    }

    private fun elapsedSince(startMs: Long): Long {
        if (startMs == 0L) return 0L
        return (android.os.SystemClock.elapsedRealtime() - startMs).coerceAtLeast(0L)
    }

    private const val TAG = "NlxMusicStartup"
}
