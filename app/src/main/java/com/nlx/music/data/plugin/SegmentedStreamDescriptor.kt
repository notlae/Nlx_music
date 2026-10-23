package com.nlx.music.data.plugin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Provider-agnostic segmented DRM stream descriptor.
 *
 * Produced by an external provider module (.lwp `getPlayback()` call): one
 * [baseUrl] with N byte-range [segments] (chunk-by-chunk DASH) plus Widevine
 * [drm]. Decryption keys never leave the CDM — ExoPlayer + MediaDrm decrypts
 * chunk-by-chunk and feeds clear PCM to the existing audio sink.
 */
@Serializable
data class SegmentedStreamDescriptor(
    val provider: String = "external",
    val sessionId: String = "",
    @SerialName("trackId") val trackId: String = "",
    val title: String = "Unknown",
    val artist: String = "Unknown",
    val album: String = "",
    val durationSec: Int = 0,
    val stream: SegmentedStreamRef = SegmentedStreamRef(),
    val drm: SegmentedDrmRef? = null,
    val headers: Map<String, String> = emptyMap(),
    val expiresAt: Long = 0L,
)

@Serializable
data class SegmentedStreamRef(
    val type: String = "dash_segmented",
    val mimeType: String = "audio/mp4",
    val codec: String = "flac",
    /**
     * Source bit depth of the stream, or null when the module did not report
     * one. Deliberately NOT defaulted to 16: a module that simply omits the
     * field was asserting a depth it never measured, and at 44.1/48kHz that
     * fabricated 16 was believed by every badge downstream. Null means unknown,
     * and unknown renders rate-only.
     */
    val bitDepth: Int? = null,
    val sampleRate: Int = 44100,
    val bandwidth: Int = 0,
    val quality: String = "HD",
    val baseUrl: String = "",
    val initializationRange: String = "0-1459",
    val segmentCount: Int = 0,
    val segments: List<SegmentedChunkRef> = emptyList(),
)

@Serializable
data class SegmentedChunkRef(
    val index: Int = 0,
    val url: String = "",
    val range: String = "",
)

@Serializable
data class SegmentedDrmRef(
    val scheme: String = "widevine",
    val pssh: String = "",
    val licenseUrl: String = "",
    /** "module" = build/parse the license exchange via the provider module. */
    val envelope: String = "raw",
    /** Persisted offline key set (base64) for downloaded tracks; blank = streaming. */
    val keySetIdB64: String = "",
)

fun SegmentedStreamDescriptor.isExpired(nowMs: Long = System.currentTimeMillis()): Boolean {
    if (expiresAt <= 0L) return false
    return nowMs > (expiresAt - 120_000L)
}

/** Stable CacheDataSource key per track + quality (signed URLs rotate). */
fun SegmentedStreamDescriptor.stableCacheKey(): String =
    "segdrm:${trackId}:${stream.quality}"
