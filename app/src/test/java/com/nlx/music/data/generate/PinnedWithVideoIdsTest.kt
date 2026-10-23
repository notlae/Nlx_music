package com.nlx.music.data.generate

import com.nlx.music.data.music.InnerTubeMusicApi
import com.nlx.music.data.music.YouTubeMusicTrack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedWithVideoIdsTest {
    private val api = mockk<InnerTubeMusicApi>()

    @Test
    fun keepsPinnedTracksWithoutNetwork() = runBlocking {
        // Bare id: youtubeVideoIdOrNull matches the 11-char regex directly
        // without touching android.net.Uri, which throws on plain JVM unit
        // tests (android.jar stubs). A full watch URL would need Robolectric.
        val pinned = GeneratedTrack(
            name = "Song",
            artist = "Artist",
            url = "abcdefghijk",
        )
        assertEquals(listOf(pinned), listOf(pinned).pinnedWithVideoIds(api))
        coVerify(exactly = 0) { api.findBestMatchOrNull(any(), any(), any()) }
    }

    @Test
    fun pinsResolvableAndDropsMisses() = runBlocking {
        coEvery { api.findBestMatchOrNull("Song", "Artist", any()) } returns
            YouTubeMusicTrack("12345678901", "Song", "Artist")
        coEvery { api.findBestMatchOrNull("Missing", "Artist", any()) } returns null
        val rows = listOf(
            GeneratedTrack(name = "Song", artist = "Artist"),
            GeneratedTrack(name = "Missing", artist = "Artist"),
        )
        val pinned = rows.pinnedWithVideoIds(api)
        assertEquals(1, pinned.size)
        assertEquals("https://music.youtube.com/watch?v=12345678901", pinned.single().url)
        assertEquals("Song", pinned.single().name)
    }

    @Test
    fun blankRowsDropWithoutNetwork() = runBlocking {
        assertEquals(emptyList<GeneratedTrack>(), listOf(GeneratedTrack(name = "", artist = "")).pinnedWithVideoIds(api))
        coVerify(exactly = 0) { api.findBestMatchOrNull(any(), any(), any()) }
    }
}
