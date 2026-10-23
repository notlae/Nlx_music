package com.nlx.music.data.plugin

import java.util.Locale

/**
 * Builds a minimal single-period DASH MPD from a segmented chunk descriptor.
 *
 * The provider module parses its own manifest format in JS and hands us a
 * [SegmentedStreamDescriptor] (baseUrl + per-chunk mediaRange list).
 * ExoPlayer's DashMediaSource needs MPD XML, so we re-emit exactly one
 * AdaptationSet/Representation with a SegmentList — chunk-by-chunk byte
 * ranges on the same CDN URL.
 *
 * Encrypted: emits Widevine ContentProtection + pssh so ExoPlayer routes
 * samples through MediaCrypto instead of trying clear playback.
 */
object SegmentedMpdBuilder {
    const val WIDEVINE_UUID = "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"

    fun build(descriptor: SegmentedStreamDescriptor): String =
        build(descriptor, descriptor.stream.baseUrl)

    /** [baseUrlOverride] points the MPD at a local file/content copy for offline play. */
    fun build(descriptor: SegmentedStreamDescriptor, baseUrlOverride: String? = null): String {
        val s = descriptor.stream
        val baseUrl = baseUrlOverride?.takeIf { it.isNotBlank() } ?: s.baseUrl
        require(baseUrl.isNotBlank()) { "Segmented descriptor missing baseUrl" }
        require(s.segments.isNotEmpty()) { "Segmented descriptor has no segments" }

        val mime = s.mimeType.ifBlank { "audio/mp4" }
        val codecs = when (s.codec.lowercase()) {
            "flac" -> "flac"
            "opus" -> "opus"
            "mp4a", "aac" -> "mp4a.40.2"
            "eac3", "ec-3", "ec3" -> "ec-3"
            "ac-4", "ac4" -> "ac-4"
            else -> s.codec.ifBlank { "flac" }
        }
        val bandwidth = s.bandwidth.takeIf { it > 0 } ?: 1_000_000
        val sampleRate = s.sampleRate.takeIf { it > 0 } ?: 44100
        val durationSec = descriptor.durationSec.takeIf { it > 0 } ?: 240
        // SegmentList/Duration form: total duration split evenly across chunks.
        // DASH duration in SegmentList requires integer timescale units; floating-point
        // durations fail Long.parseLong in DashManifestParser, causing seeks to reset to 0.
        val timescale = 1000L
        val durationTimescale = durationSec.toLong() * timescale
        val segCount = s.segments.size.coerceAtLeast(1)
        val segDurationTimescale = (durationTimescale / segCount).coerceAtLeast(1L)

        val psshBlock = descriptor.drm
            ?.pssh
            ?.takeIf { it.isNotBlank() }
            ?.let { pssh ->
                "<ContentProtection schemeIdUri=\"urn:uuid:$WIDEVINE_UUID\" value=\"Widevine\">" +
                    "<cenc:pssh xmlns:cenc=\"urn:mpeg:cenc:2013\">${pssh.trim()}</cenc:pssh>" +
                    "</ContentProtection>"
            }.orEmpty()

        val segUrls = s.segments.joinToString("") { seg ->
            "<SegmentURL mediaRange=\"${escapeXml(seg.range)}\"/>"
        }

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" " +
            "xmlns:cenc=\"urn:mpeg:cenc:2013\" " +
            "type=\"static\" mediaPresentationDuration=\"PT${durationSec}S\" " +
            "minBufferTime=\"PT1.5S\" profiles=\"urn:mpeg:dash:profile:isoff-live:2011\">" +
            "<Period duration=\"PT${durationSec}S\">" +
            "<AdaptationSet mimeType=\"${escapeXml(mime)}\" contentType=\"audio\">" +
            psshBlock +
            "<Representation id=\"${escapeXml(s.quality.ifBlank { "HD" })}\" " +
            "codecs=\"${escapeXml(codecs)}\" bandwidth=\"$bandwidth\" " +
            "audioSamplingRate=\"$sampleRate\">" +
            "<BaseURL>${escapeXml(baseUrl)}</BaseURL>" +
            "<SegmentList timescale=\"$timescale\" duration=\"$segDurationTimescale\">" +
            "<Initialization range=\"${escapeXml(s.initializationRange)}\"/>" +
            segUrls +
            "</SegmentList>" +
            "</Representation>" +
            "</AdaptationSet>" +
            "</Period>" +
            "</MPD>"
    }

    /**
     * Sanitizes raw DASH MPD XML so that any floating-point duration or timescale attributes
     * in <SegmentList> or <SegmentTemplate> are converted to integer units.
     * AndroidX Media3's DashManifestParser parses duration and timescale via Long.parseLong(), which
     * fails with NumberFormatException on decimals like "10.000", resetting seek position to 0.
     */
    fun sanitizeMpdXml(rawXml: String): String {
        if (!rawXml.contains("duration", ignoreCase = true) && !rawXml.contains("timescale", ignoreCase = true)) return rawXml

        // 1. Sanitize float timescale in <SegmentList> or <SegmentTemplate>
        val floatTimescaleRegex = Regex(
            """(<(?:SegmentList|SegmentTemplate)[^>]*?)\btimescale\s*=\s*['"](\d+)\.(\d+)['"]([^>]*?>)""",
            RegexOption.IGNORE_CASE,
        )
        var xml = floatTimescaleRegex.replace(rawXml) { match ->
            val prefix = match.groupValues[1]
            val floatVal = "${match.groupValues[2]}.${match.groupValues[3]}".toDoubleOrNull() ?: return@replace match.value
            val cleanScale = Math.round(floatVal).coerceAtLeast(1L)
            val suffix = match.groupValues[4]
            "${prefix}timescale=\"$cleanScale\"$suffix"
        }

        // 2. Sanitize float duration in <SegmentList> or <SegmentTemplate>
        val floatDurationRegex = Regex(
            """(<(?:SegmentList|SegmentTemplate)[^>]*?)\bduration\s*=\s*['"](\d+)\.(\d+)['"]([^>]*?>)""",
            RegexOption.IGNORE_CASE,
        )
        xml = floatDurationRegex.replace(xml) { match ->
            val prefix = match.groupValues[1]
            val intPart = match.groupValues[2]
            val fracPart = match.groupValues[3]
            val suffix = match.groupValues[4]
            val combinedAttrs = prefix + suffix
            val hasTimescale = combinedAttrs.contains("timescale", ignoreCase = true)
            val floatVal = "$intPart.$fracPart".toDoubleOrNull() ?: return@replace match.value
            if (hasTimescale) {
                val cleanDuration = Math.round(floatVal).coerceAtLeast(1L)
                "${prefix}duration=\"$cleanDuration\"$suffix"
            } else {
                val msDuration = Math.round(floatVal * 1000.0).coerceAtLeast(1L)
                "${prefix}timescale=\"1000\" duration=\"$msDuration\"$suffix"
            }
        }
        return xml
    }

    private fun escapeXml(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
