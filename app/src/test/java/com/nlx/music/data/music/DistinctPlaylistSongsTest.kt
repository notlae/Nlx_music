package com.nlx.music.data.music

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for [distinctPlaylistSongs] — the display boundary that
 * collapses a fetched playlist's repeated songs.
 *
 * Bug: with a YouTube account connected, every playlist opened from search
 * (Playlists filter) showed each song twice (identical title+artist). Every
 * parse path dedupes by videoId, so the second copy necessarily carries a
 * different videoId (a second upload of the same song, or the same song
 * returned under a second renderer by the authenticated browse).
 */
class DistinctPlaylistSongsTest {

    @Test
    fun sameSongUnderTwoVideoIdsCollapsesToOneRow() {
        val tracks = listOf(
            YouTubeMusicTrack("vid1", "Song", "Artist"),
            YouTubeMusicTrack("vid2", "Song", "Artist"),
            YouTubeMusicTrack("vid3", "Other Song", "Artist"),
        )

        val result = tracks.distinctPlaylistSongs()

        assertEquals(2, result.size)
        assertEquals("vid1", result[0].videoId)
        assertEquals("vid3", result[1].videoId)
    }

    @Test
    fun distinctSongsAreAllKeptInOrder() {
        val tracks = listOf(
            YouTubeMusicTrack("a", "One", "Artist"),
            YouTubeMusicTrack("b", "Two", "Artist"),
            YouTubeMusicTrack("c", "Three", "Artist"),
        )

        assertEquals(tracks, tracks.distinctPlaylistSongs())
    }

    @Test
    fun whitespaceAndCaseVariantsCollapse() {
        val tracks = listOf(
            YouTubeMusicTrack("a", "  Song ", "Artist"),
            YouTubeMusicTrack("b", "song", " artist "),
        )

        val result = tracks.distinctPlaylistSongs()

        assertEquals(1, result.size)
        assertEquals("a", result.single().videoId)
    }

    @Test
    fun sameTitleDifferentArtistsAreKept() {
        val tracks = listOf(
            YouTubeMusicTrack("a", "Song", "Artist One"),
            YouTubeMusicTrack("b", "Song", "Artist Two"),
        )

        val result = tracks.distinctPlaylistSongs()

        assertEquals(2, result.size)
    }

    @Test
    fun singleTrackAndEmptyListPassThrough() {
        val single = listOf(YouTubeMusicTrack("a", "Song", "Artist"))

        assertEquals(single, single.distinctPlaylistSongs())
        assertEquals(emptyList<YouTubeMusicTrack>(), emptyList<YouTubeMusicTrack>().distinctPlaylistSongs())
    }
}
