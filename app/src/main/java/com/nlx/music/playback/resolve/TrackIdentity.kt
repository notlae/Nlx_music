package com.nlx.music.playback.resolve

import com.nlx.music.playback.PlayableTrack

/**
 * Immutable playback identity. Title, artist, and album are display fields
 * and must never be used as a cache or resolver key.
 *
 * [cacheKey] is always `trackId+youtubeVideoId`.
 */
data class TrackIdentity(
    val trackId: String,
    val youtubeVideoId: String,
) {
    init {
        require(trackId.isNotBlank()) { "trackId is required" }
        require(youtubeVideoId.isNotBlank()) { "youtubeVideoId is required" }
    }

    val cacheKey: String get() = "$trackId+$youtubeVideoId"
}

/** YouTube video id when present, otherwise null. Never falls back to title. */
fun PlayableTrack.playbackIdentity(): TrackIdentity? {
    val videoId = videoId?.takeIf { it.isNotBlank() } ?: return null
    val trackId = internalTrackId?.takeIf { it.isNotBlank() } ?: videoId
    return TrackIdentity(trackId, videoId)
}
