package com.nlx.music.data.playlist

import com.nlx.music.data.generate.GeneratedTrack
import com.nlx.music.data.local.db.SavedPlaylistDao
import com.nlx.music.data.local.db.SavedPlaylistEntity
import com.nlx.music.data.music.InnerTubeMusicApi
import com.nlx.music.util.FileExportHelper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the public-mirror fallback in
 * [PlaylistRepository.getAll] that served a stale Downloads JSON snapshot
 * whenever a Room read failed.
 *
 * Root cause: on ANY transient DAO failure the catch block fell through to
 * `loadPlaylistsFromPublicMirror()` (restoreIfDatabaseEmpty() returns 0 for a
 * non-empty database, which is the normal case), so callers that make
 * decisions from that list received a partial, lagging picture:
 *  - YtMusicSyncManager's orphan sweep deleted the user's YouTube mirrors for
 *    every playlist merely missing from the stale snapshot ("sometimes delete
 *    playlist"), then recreated them as duplicates;
 *  - ensureLikedSongs() created a second local Liked Songs row per stale read,
 *    each of which reconcile() then mirrored as its own remote playlist
 *    ("liking a song makes unlimited playlists");
 *  - save()/import dedupe saw "no existing playlist" and stacked siblings
 *    ("creating duplicate playlists");
 *  - merge baselines computed from the stale list pulled/pushed phantom songs
 *    ("it adds songs", "not syncing perfectly").
 *
 *  * A failed read must fail loudly (display callers already catch it) instead of
 * silently lying with lagging data.
 */
class PlaylistRepositoryReadFailureTest {

    private lateinit var scope: CoroutineScope
    private lateinit var dao: SavedPlaylistDao
    private lateinit var fileExportHelper: FileExportHelper
    private lateinit var publicMirror: PlaylistPublicMirror
    private lateinit var repository: PlaylistRepository

    private val playlistEntity = SavedPlaylistEntity(
        id = 1L,
        title = "Chill",
        subtitle = "Custom playlist",
        mode = "custom",
        tracksJson = "[]",
        createdAtMillis = 100L,
    )

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        dao = mockk(relaxed = true)
        fileExportHelper = mockk(relaxed = true)
        publicMirror = mockk(relaxed = true)
        repository = PlaylistRepository(
            dao = dao,
            fileExportHelper = fileExportHelper,
            exportEvents = mockk(relaxed = true),
            publicMirror = publicMirror,
            innerTube = mockk<InnerTubeMusicApi>(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** Transient failure (e.g. the locked database a sync write leaves behind)
     *  must surface as the real failure — never as a stale mirror snapshot. */
    @Test
    fun transientReadFailureThrowsInsteadOfServingStaleMirror() = runTest {
        coEvery { dao.getAll() } throws IllegalStateException("database locked")
        coEvery { fileExportHelper.readPublicPlaylistMirror() } returns
            Result.success(STALE_MIRROR_JSON)

        val failure = runCatching { repository.getAll() }.exceptionOrNull()

        assertTrue("expected the Room failure to surface", failure is IllegalStateException)
    }

    /** The live database is retried once before giving up on the read. */
    @Test
    fun transientReadFailureRetriesTheLiveReadOnce() = runTest {
        coEvery { dao.getAll() } throws IllegalStateException("database locked") andThen
            listOf(playlistEntity)

        val playlists = repository.getAll()

        assertEquals(1, playlists.size)
        assertEquals("Chill", playlists.single().title)
        coVerify(exactly = 2) { dao.getAll() }
    }

    /** save() must abort, not treat the failure as "no existing playlists"
     *  (which stacked a same-title sibling every time). */
    @Test
    fun saveAbortsWhenLocalReadFailsInsteadOfStackingDuplicate() = runTest {
        coEvery { dao.getAll() } throws IllegalStateException("database locked")
        val track = GeneratedTrack(
            name = "Song",
            artist = "Artist",
            url = "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
        )

        val failure = runCatching {
            repository.save("Road Trip", "Custom playlist", "custom", listOf(track))
        }.exceptionOrNull()

        assertTrue("save must not proceed without a trustworthy read", failure is IllegalStateException)
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    /** A failed re-read must not tear down the playlists flow (UI + the YT
     *  library projection collect it): keep serving the last good list. */
    @Test
    fun playlistsFlowKeepsLastGoodListWhenReReadFails() = runTest {
        coEvery { dao.getAll() } returns listOf(playlistEntity)
        coEvery { dao.getById(playlistEntity.id) } returns playlistEntity
        coEvery { publicMirror.writeFromDatabase() } returns Result.success(Unit)

        val emissions = mutableListOf<List<SavedPlaylist>>()
        val collector = scope.launch { repository.playlists.collect { emissions += it } }
        withTimeout(10_000) { while (emissions.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(1, emissions.size)
        assertEquals("Chill", emissions.single().single().title)

        // Next mutation emits a change; the collector's re-read now fails.
        coEvery { dao.getAll() } throws IllegalStateException("database locked")
        repository.setPinned(playlistEntity.id, pinned = true)
        kotlinx.coroutines.delay(100)

        assertTrue("collector must survive a failed re-read", collector.isActive)
        assertEquals("no partial/empty list may be emitted", 1, emissions.size)
        collector.cancel()
    }

    private companion object {
        /** A lagging-but-valid Downloads mirror snapshot. */
        const val STALE_MIRROR_JSON = """{"type":"nlxmusic-playlists","schemaVersion":4,"updatedAtMillis":1700000000000,"playlists":[{"id":42,"title":"Stale Snapshot","subtitle":"stale","mode":"custom","tracks":[],"createdAtMillis":1699999999999}]}"""
    }
}
