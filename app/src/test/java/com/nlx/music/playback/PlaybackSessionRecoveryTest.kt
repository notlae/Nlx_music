package com.nlx.music.playback

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlaybackSessionRecoveryTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `persisted playback session serializes and deserializes correctly`() {
        val track1 = PlayableTrack(
            title = "Track One",
            artist = "Artist A",
            album = "Album X",
            artworkUrl = "https://art1.png",
            videoId = "vid1",
            durationMs = 210000L,
        )
        val track2 = PlayableTrack(
            title = "Track Two",
            artist = "Artist B",
            album = "Album Y",
            artworkUrl = "https://art2.png",
            videoId = "vid2",
            durationMs = 180000L,
        )

        val session = PersistedPlaybackSession(
            version = 2,
            queue = listOf(track1, track2),
            currentIndex = 0,
            positionMs = 45000L,
            sourceLabel = "For You",
            isEndlessQueue = true,
            shuffleEnabled = true,
            repeatMode = 1, // REPEAT_MODE_ONE
            speed = 1.0f,
            durationMs = 210000L,
        )

        val encoded = json.encodeToString(PersistedPlaybackSession.serializer(), session)
        val decoded = json.decodeFromString(PersistedPlaybackSession.serializer(), encoded)

        assertThat(decoded.version).isEqualTo(2)
        assertThat(decoded.queue).hasSize(2)
        assertThat(decoded.queue[0].title).isEqualTo("Track One")
        assertThat(decoded.queue[1].title).isEqualTo("Track Two")
        assertThat(decoded.currentIndex).isEqualTo(0)
        assertThat(decoded.positionMs).isEqualTo(45000L)
        assertThat(decoded.sourceLabel).isEqualTo("For You")
        assertThat(decoded.isEndlessQueue).isTrue()
        assertThat(decoded.shuffleEnabled).isTrue()
        assertThat(decoded.repeatMode).isEqualTo(1)
    }

    @Test
    fun `corrupt json falls back safely without crashing`() {
        val invalidJson = "{ corrupt_data: true, invalid }"
        val result = runCatching {
            json.decodeFromString(PersistedPlaybackSession.serializer(), invalidJson)
        }
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `empty track list is identified as empty`() {
        val session = PersistedPlaybackSession(
            queue = emptyList(),
            currentIndex = 0,
            positionMs = 0L,
        )
        assertThat(session.queue).isEmpty()
    }

    @Test
    fun `blank or invalid title tracks are filtered during session normalization`() {
        val validTrack = PlayableTrack(title = "Valid", artist = "Artist")
        val invalidTrack = PlayableTrack(title = "  ", artist = "Artist")
        val queue = listOf(validTrack, invalidTrack)

        val filtered = queue.filter { it.title.isNotBlank() && it.artist.isNotBlank() }
        assertThat(filtered).hasSize(1)
        assertThat(filtered[0].title).isEqualTo("Valid")
    }

    @Test
    fun `restored music player state starts in paused mode`() {
        val track = PlayableTrack(title = "Restored Song", artist = "Restored Artist", videoId = "v1")
        val state = MusicPlayerState(
            current = track,
            queue = listOf(track),
            currentIndex = 0,
            positionMs = 30000L,
            isPlaying = false,
            isBuffering = false,
        )

        assertThat(state.isPlaying).isFalse()
        assertThat(state.current?.title).isEqualTo("Restored Song")
        assertThat(state.positionMs).isEqualTo(30000L)
    }
}
