package com.nlx.music.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QualityBadgeTest {

    @Test
    fun losslessDepthAndRateIsFlacSlashForm() {
        assertThat(
            qualityBadgeLabel(
                MusicPlayerState(
                    isLossless = true,
                    bitDepth = 24,
                    samplingRateKHz = 44.1,
                    audioCodec = "LOSSLESS",
                    bitrateKbps = 2116,
                ),
            ),
        ).isEqualTo("24/44.1kHz")
    }

    @Test
    fun cdFlacIsSixteenFortyOne() {
        assertThat(
            qualityBadgeLabel(
                MusicPlayerState(
                    isLossless = true,
                    bitDepth = 16,
                    samplingRateKHz = 44.1,
                    audioCodec = "FLAC",
                    bitrateKbps = 1411,
                ),
            ),
        ).isEqualTo("16/44.1kHz")
    }

    @Test
    fun hiResNinetySix() {
        assertThat(
            qualityBadgeLabel(
                MusicPlayerState(
                    isLossless = true,
                    bitDepth = 24,
                    samplingRateKHz = 96.0,
                    audioCodec = "HI-RES FLAC",
                ),
            ),
        ).isEqualTo("24/96kHz")
    }

    @Test
    fun flacCodecWithoutLosslessFlagStillShowsDepthRate() {
        // Decoder path used to publish codec=FLAC + 1411 kbps with isLossless=false,
        // which rendered as the truncated "FLAC 1411 k…" pill.
        assertThat(
            qualityBadgeLabel(
                MusicPlayerState(
                    isLossless = false,
                    audioCodec = "FLAC",
                    bitrateKbps = 1411,
                    samplingRateKHz = 44.1,
                    bitDepth = 16,
                ),
            ),
        ).isEqualTo("16/44.1kHz")
    }

    @Test
    fun unknownDepthIsNeverGuessedFromBitrate() {
        // The old heuristic mapped "400..1150 kbps at 44.1/48kHz" to 16-bit and
        // ">= 1500 kbps" to 24-bit. A stereo 24-bit/44.1kHz FLAC lands anywhere
        // from ~500 to ~2500 kbps depending only on how well that recording
        // compresses, so those buckets relabelled real 24-bit tracks as 16-bit.
        // 941 and 926 kbps are taken straight from a failing device report.
        assertThat(
            qualityBadgeLabel(
                MusicPlayerState(
                    isLossless = true,
                    audioCodec = "FLAC",
                    bitrateKbps = 941,
                    samplingRateKHz = 44.1,
                ),
            ),
        ).isEqualTo("44.1kHz FLAC")
        assertThat(
            qualityBadgeLabel(
                MusicPlayerState(
                    isLossless = true,
                    audioCodec = "FLAC",
                    bitrateKbps = 926,
                    samplingRateKHz = 44.1,
                ),
            ),
        ).isEqualTo("44.1kHz FLAC")
        assertThat(
            qualityBadgeLabel(
                MusicPlayerState(
                    isLossless = true,
                    audioCodec = "FLAC",
                    bitrateKbps = 1411,
                    samplingRateKHz = 44.1,
                ),
            ),
        ).isEqualTo("44.1kHz FLAC")
        // The high-bitrate side of the deleted heuristic must not be resurrected.
        assertThat(
            qualityBadgeLabel(
                MusicPlayerState(
                    isLossless = true,
                    audioCodec = "FLAC",
                    bitrateKbps = 1695,
                    samplingRateKHz = 44.1,
                ),
            ),
        ).isEqualTo("44.1kHz FLAC")
    }

    @Test
    fun resolvedSourceDepthWinsOverBitrateRegardlessOfBitrate() {
        // The pill reads the song's own resolved depth, so a 24-bit track that
        // happens to compress below the old 16-bit threshold still reports 24.
        assertThat(
            qualityBadgeLabel(
                MusicPlayerState(
                    isLossless = true,
                    audioCodec = "24/44.1kHz",
                    bitDepth = 24,
                    bitrateKbps = 941,
                    samplingRateKHz = 44.1,
                ),
            ),
        ).isEqualTo("24/44.1kHz")
        // 24/192 exactly as the user described it.
        assertThat(
            qualityBadgeLabel(
                MusicPlayerState(
                    isLossless = true,
audioCodec = "24/192kHz",
                    bitDepth = 24,
                    bitrateKbps = 3411,
                    samplingRateKHz = 192.0,
                ),
            ),
        ).isEqualTo("24/192kHz")
    }

    @Test
    fun atmosNeverShowsFlacRate() {
        assertThat(
            qualityBadgeLabel(
                MusicPlayerState(
                    isLossless = true,
                    audioCodec = "DOLBY ATMOS",
                    bitDepth = 24,
                    samplingRateKHz = 48.0,
                    bitrateKbps = 768,
                ),
            ),
        ).isEqualTo("ATMOS")
        assertThat(spatialIndicatorLabel("DOLBY ATMOS")).isEqualTo("ATMOS")
        assertThat(spatialIndicatorLabel("SPATIAL AUDIO")).isEqualTo("SPATIAL")
    }

    @Test
    fun spatialAudioBadge() {
        assertThat(
            qualityBadgeLabel(
                MusicPlayerState(audioCodec = "SPATIAL AUDIO", isLossless = true, bitDepth = 24, samplingRateKHz = 48.0),
            ),
        ).isEqualTo("SPATIAL")
    }

    @Test
    fun opusKeepsKbpsForm() {
        assertThat(
            qualityBadgeLabel(
                MusicPlayerState(
                    audioCodec = "OPUS",
                    bitrateKbps = 160,
                    isLossless = false,
                ),
            ),
        ).isEqualTo("OPUS 160 kbps")
    }

    @Test
    fun lossy320And96Badges() {
        assertThat(
            qualityBadgeLabel(
                MusicPlayerState(
                    audioCodec = "320 kbps",
                    bitrateKbps = 320,
                    isLossless = false,
                ),
            ),
        ).isEqualTo("320 kbps")

        assertThat(
            qualityBadgeLabel(
                MusicPlayerState(
                    audioCodec = "96 kbps",
                    bitrateKbps = 96,
                    isLossless = false,
                ),
            ),
        ).isEqualTo("96 kbps")
    }

    @Test
    fun intermediateQualityBadgesForAllTiers() {
        // 16-bit 48 kHz
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, bitDepth = 16, samplingRateKHz = 48.0)),
        ).isEqualTo("16/48kHz")

        // 24-bit 44.1 kHz
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, bitDepth = 24, samplingRateKHz = 44.1)),
        ).isEqualTo("24/44.1kHz")

        // 24-bit 48 kHz
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, bitDepth = 24, samplingRateKHz = 48.0)),
        ).isEqualTo("24/48kHz")

        // 24-bit 88.2 kHz
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, bitDepth = 24, samplingRateKHz = 88.2)),
        ).isEqualTo("24/88.2kHz")

        // 24-bit 96 kHz
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, bitDepth = 24, samplingRateKHz = 96.0)),
        ).isEqualTo("24/96kHz")

        // 24-bit 176.4 kHz
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, bitDepth = 24, samplingRateKHz = 176.4)),
        ).isEqualTo("24/176.4kHz")

        // 24-bit 192 kHz
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, bitDepth = 24, samplingRateKHz = 192.0)),
        ).isEqualTo("24/192kHz")

        // 32-bit 384 kHz
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, bitDepth = 32, samplingRateKHz = 384.0)),
        ).isEqualTo("32/384kHz")
    }

    @Test
    fun unknownDepthAtCdRateShowsRateWithoutDepthClaim() {
        // At or below 48kHz an unknown depth must never fabricate "24-BIT" (or
        // "16-BIT"): 16-bit/44.1kHz and 24-bit/44.1kHz are both extremely
        // common, so the rate alone proves nothing. Hi-res rates are handled by
        // the opposite assumption — see unknownDepthAtHiResResolvesToTwentyFour.
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, samplingRateKHz = 44.1)),
        ).isEqualTo("44.1kHz FLAC")
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, samplingRateKHz = 48.0)),
        ).isEqualTo("48kHz FLAC")
    }

    @Test
    fun sixteenBitAtHiResIsCorrectedToTwentyFour() {
        // A rate above 48kHz is a 24-bit master in effectively every real
        // release; a genuine 16-bit stream at 88.2/96/176.4/192 is vanishingly
        // rare. So a reported 16 at those rates is corrected to 24 rather than
        // shown as 16 (which is what the old "discard the contradicted 16" rule
        // did, leaving a bare "96kHz FLAC" that hid half the format).
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, bitDepth = 16, samplingRateKHz = 96.0)),
        ).isEqualTo("24/96kHz")
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, bitDepth = 16, samplingRateKHz = 88.2)),
        ).isEqualTo("24/88.2kHz")
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, bitDepth = 16, samplingRateKHz = 176.4)),
        ).isEqualTo("24/176.4kHz")
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, bitDepth = 16, samplingRateKHz = 192.0)),
        ).isEqualTo("24/192kHz")
        // CD audio is genuinely 16-bit and keeps it.
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, bitDepth = 16, samplingRateKHz = 44.1)),
        ).isEqualTo("16/44.1kHz")
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, bitDepth = 16, samplingRateKHz = 48.0)),
        ).isEqualTo("16/48kHz")
    }

    @Test
    fun unknownDepthAtHiResResolvesToTwentyFour() {
        // The rate alone is strong evidence above 48kHz, so an unknown depth
        // there still gets a complete label instead of a half one.
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, samplingRateKHz = 96.0)),
        ).isEqualTo("24/96kHz")
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, samplingRateKHz = 192.0)),
        ).isEqualTo("24/192kHz")
        // Above 192kHz the hi-res class is 32-bit, not 24.
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, samplingRateKHz = 384.0)),
        ).isEqualTo("32/384kHz")
        // At or below 48kHz an unknown depth still claims nothing.
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, samplingRateKHz = 44.1)),
        ).isEqualTo("44.1kHz FLAC")
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, samplingRateKHz = 48.0)),
        ).isEqualTo("48kHz FLAC")
    }

    @Test
    fun signalPathSourceRowAgreesWithThePill() {
        // The Signal Path dialog and the pill read the same resolved state
        // through the same shared rule, so the source row can never disagree
        // with the badge. This mirrors MusicPlayer.updateSignalPath exactly.
        fun sourceRow(state: MusicPlayerState): PathCheck {
            val rateKHz = state.samplingRateKHz
            val rateHz = rateKHz?.times(1000.0)?.toInt()
            val report = evaluateSignalPath(
                SignalPathInput(
                    sourceLabel = state.audioCodec ?: "FLAC",
                    sourceRateHz = rateHz,
                    sourceBitDepth = resolveDepthForDisplay(state.bitDepth, rateKHz),
                    isLossless = state.isLossless,
                    appOutputRateHz = rateHz ?: 0,
                    platformMixerRateHz = 48000,
                    dspBypassEnabled = true,
                    crossfadeMixing = false,
                    speed = 1f,
                    appVolume = 1f,
                    systemVolume = 8,
                    systemVolumeMax = 15,
                    systemVolumeFixed = false,
                    dac = null,
                    routedToDac = false,
                    routeVerified = false,
                    driftPpm = null,
                    glitchCount = 0,
                    isPlaying = true,
                ),
            )
            return report.checks.first { it.labelRes == com.nlx.music.R.string.signal_label_source }
        }

        // A known depth is reported as [label, depth, rate].
        assertThat(sourceRow(MusicPlayerState(isLossless = true, bitDepth = 24, samplingRateKHz = 44.1)).detailArgs)
            .containsExactly("FLAC", 24, 44100).inOrder()

        // A 16 reported at a hi-res rate is corrected to 24, exactly as the pill.
        assertThat(sourceRow(MusicPlayerState(isLossless = true, bitDepth = 16, samplingRateKHz = 96.0)).detailArgs)
            .containsExactly("FLAC", 24, 96000).inOrder()

        // CD audio keeps its genuine 16.
        assertThat(sourceRow(MusicPlayerState(isLossless = true, bitDepth = 16, samplingRateKHz = 44.1)).detailArgs)
            .containsExactly("FLAC", 16, 44100).inOrder()

        // Unknown depth at a hi-res rate still resolves to 24 in the dialog.
        assertThat(sourceRow(MusicPlayerState(isLossless = true, samplingRateKHz = 192.0)).detailArgs)
            .containsExactly("FLAC", 24, 192000).inOrder()

        // Unknown depth at CD rate reports the rate alone: [label, rate], no depth.
        val unknown = sourceRow(MusicPlayerState(isLossless = true, samplingRateKHz = 44.1)).detailArgs
        assertThat(unknown).containsExactly("FLAC", 44100).inOrder()
        assertThat(unknown).doesNotContain(16)
        assertThat(unknown).doesNotContain(24)
    }

    @Test
    fun parsesQualityFromFormattedCodecString() {
        assertThat(
            qualityBadgeLabel(MusicPlayerState(audioCodec = "24-BIT / 96k", isLossless = true)),
        ).isEqualTo("24/96kHz")
        assertThat(
            qualityBadgeLabel(MusicPlayerState(audioCodec = "24-BIT / 48k", isLossless = true)),
        ).isEqualTo("24/48kHz")
        assertThat(
            qualityBadgeLabel(MusicPlayerState(audioCodec = "16-BIT / 48k", isLossless = true)),
        ).isEqualTo("16/48kHz")
        assertThat(
            qualityBadgeLabel(MusicPlayerState(audioCodec = "24-BIT / 44.1k", isLossless = true)),
        ).isEqualTo("24/44.1kHz")
    }

    @Test
    fun detailedQualityBadgeFormats() {
        assertThat(formatDetailedQualityBadge(24, 192.0)).isEqualTo("24-BIT / 192k")
        assertThat(formatDetailedQualityBadge(24, 176.4)).isEqualTo("24-BIT / 176.4k")
        assertThat(formatDetailedQualityBadge(24, 96.0)).isEqualTo("24-BIT / 96k")
        assertThat(formatDetailedQualityBadge(24, 88.2)).isEqualTo("24-BIT / 88.2k")
        assertThat(formatDetailedQualityBadge(24, 48.0)).isEqualTo("24-BIT / 48k")
        assertThat(formatDetailedQualityBadge(24, 44.1)).isEqualTo("24-BIT / 44.1k")
        assertThat(formatDetailedQualityBadge(16, 48.0)).isEqualTo("16-BIT / 48k")
        assertThat(formatDetailedQualityBadge(16, 44.1)).isEqualTo("16-BIT / 44.1k")
        assertThat(formatDetailedQualityBadge(null, 96.0)).isEqualTo("24-BIT / 96k")
        assertThat(formatDetailedQualityBadge(null, 44.1)).isEqualTo("44.1k FLAC")
        assertThat(formatDetailedQualityBadge(16, 96.0)).isEqualTo("24-BIT / 96k")
        assertThat(formatDetailedQualityBadge(16, 192.0)).isEqualTo("24-BIT / 192k")
        assertThat(formatDetailedQualityBadge(null, 384.0)).isEqualTo("32-BIT / 384k")
        assertThat(formatDetailedQualityBadge(null, null)).isEqualTo("FLAC")
        assertThat(formatDetailedQualityBadge(null, null, isAtmos = true)).isEqualTo("DOLBY ATMOS")
    }
}

