package com.nlx.music.data.localmusic

import com.google.common.truth.Truth.assertThat
import com.nlx.music.data.generate.GeneratedTrack
import com.nlx.music.data.generate.LocalAudioFormats
import com.nlx.music.data.generate.isLocalFile
import com.nlx.music.playback.toPlayableTrack
import org.junit.Test

/** "Local songs" helpers (format filter, naming fallback, playback mapping). */
class LocalSongsTest {

    private val path = "/data/user/0/com.nlx.music/files/local_music/0123abcd.flac"

    @Test
    fun supportedFormatsCoverTheCommonOnes() {
        listOf("a.mp3", "b.FLAC", "c.wav", "d.ogg", "e.opus", "f.m4a", "g.aac").forEach {
            assertThat(LocalAudioFormats.isSupported(it)).isTrue()
        }
        listOf("a.aiff", "b.ape", "c.wma", "d.dsf", "cover.jpg", "lyrics.lrc", "noext").forEach {
            assertThat(LocalAudioFormats.isSupported(it)).isFalse()
        }
        assertThat(LocalAudioFormats.UNSUPPORTED_AUDIO).containsAtLeast("aiff", "ape", "wma", "dsf")
        assertThat(LocalAudioFormats.mimeFor("x.FLAC")).isEqualTo("audio/flac")
        assertThat(LocalAudioFormats.mimeFor("x.mp3")).isEqualTo("audio/mpeg")
    }

    @Test
    fun lrcSidecarSharesTheBaseName() {
        assertThat(LocalAudioFormats.lrcPathFor(path))
            .isEqualTo("/data/user/0/com.nlx.music/files/local_music/0123abcd.lrc")
    }

    @Test
    fun importedPathAcceptsPlainAndFileUriForms() {
        assertThat(LocalAudioFormats.importedPathOrNull(path)).isEqualTo(path)
        assertThat(LocalAudioFormats.importedPathOrNull("file://$path")).isEqualTo(path)
        // Downloads, YouTube urls and content URIs are not imports.
        assertThat(LocalAudioFormats.importedPathOrNull("/storage/emulated/0/Music/NLX_MUSIC/a.flac")).isNull()
        assertThat(LocalAudioFormats.importedPathOrNull("https://music.youtube.com/watch?v=abcdefghijk")).isNull()
        assertThat(LocalAudioFormats.importedPathOrNull("content://x/local_music/a.flac")).isNull()
        assertThat(LocalAudioFormats.importedPathOrNull(null)).isNull()
    }

    @Test
    fun localTrackMapsToDirectFilePlayback() {
        val track = GeneratedTrack(name = "Song", artist = "Band", album = "LP", artworkUrl = "file:///x/art/1.img", url = path)
        assertThat(track.isLocalFile()).isTrue()
        val playable = track.toPlayableTrack()
        assertThat(playable.playbackUrl).isEqualTo(path)
        assertThat(playable.playbackMimeType).isEqualTo("audio/flac")
        assertThat(playable.videoId).isNull()
        assertThat(playable.artworkUrl).isEqualTo("file:///x/art/1.img")
    }

    @Test
    fun streamingTrackIsNotLocal() {
        assertThat(GeneratedTrack(name = "a", artist = "b", url = "").isLocalFile()).isFalse()
        assertThat(GeneratedTrack(name = "a", artist = "b", url = "https://www.last.fm/x").isLocalFile()).isFalse()
    }

    @Test
    fun fileNameFallbackSplitsArtistAndTitle() {
        assertThat(LocalMusicNaming.fromFileName("01 - Daft Punk - One More Time.flac"))
            .isEqualTo(LocalMusicNaming.Names("One More Time", "Daft Punk"))
        assertThat(LocalMusicNaming.fromFileName("Air - La Femme d'Argent.mp3"))
            .isEqualTo(LocalMusicNaming.Names("La Femme d'Argent", "Air"))
        assertThat(LocalMusicNaming.fromFileName("07. Intro.wav"))
            .isEqualTo(LocalMusicNaming.Names("Intro", null))
        // A number that is part of the name stays.
        assertThat(LocalMusicNaming.fromFileName("2Pac - Changes.mp3"))
            .isEqualTo(LocalMusicNaming.Names("Changes", "2Pac"))
        assertThat(LocalMusicNaming.fromFileName("1999.flac"))
            .isEqualTo(LocalMusicNaming.Names("1999", null))
    }

    @Test
    fun stableIdIsDeterministicAndDistinct() {
        val a = LocalMusicNaming.stableId("tree|doc:1")
        assertThat(a).hasLength(24)
        assertThat(LocalMusicNaming.stableId("tree|doc:1")).isEqualTo(a)
        assertThat(LocalMusicNaming.stableId("tree|doc:2")).isNotEqualTo(a)
    }
}
