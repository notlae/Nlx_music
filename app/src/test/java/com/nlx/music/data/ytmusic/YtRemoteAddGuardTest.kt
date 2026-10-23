package com.nlx.music.data.ytmusic

import android.content.Context
import com.nlx.music.data.generate.GeneratedTrack
import com.nlx.music.data.music.InnerTubeMusicApi
import com.nlx.music.data.music.YouTubeMusicTrack
import com.nlx.music.data.playlist.PlaylistImportManager
import com.nlx.music.data.playlist.PlaylistRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression tests for phantom/duplicate songs appearing in the user's real
 * YouTube playlists (and then pulled back into the app by sync).
 *
 * Root cause: [YtMusicLibraryManager.addTrack] resolved tracks without a
 * YouTube identity via fuzzy InnerTube search and pushed the GUESS straight
 * into the account playlist. A wrong guess landed as a song the user never
 * added; the next sync pass then imported it locally, duplicating/phantom
 * songs on both sides. The sync path already refuses such pushes
 * (push-guard in YtMusicSyncManager) — the direct-add path must too.
 */
class YtRemoteAddGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var innerTube: InnerTubeMusicApi
    private lateinit var manager: YtMusicLibraryManager

    private val localId = -987654321L
    private val remoteId = "PLTEST12345678901234567890123456"

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val context = mockk<Context>()
        every { context.cacheDir } returns tmp.root
        val auth = mockk<YtMusicAuthManager>()
        every { auth.connection } returns MutableStateFlow(YtConnection.DISCONNECTED)
        val preferences = mockk<YtMusicPreferences>(relaxed = true)
        every { preferences.connection } returns flowOf(YtConnection.DISCONNECTED)
        innerTube = mockk()
        coEvery { innerTube.findBestMatchOrNull(any(), any(), any()) } returns
            YouTubeMusicTrack(videoId = "GUESS1234567", title = "Guessed Song", artist = "Someone")
        coEvery { innerTube.addVideosToRemotePlaylist(any(), any()) } returns true

        manager = spyk(
            YtMusicLibraryManager(
                context = context,
                auth = auth,
                preferences = preferences,
                innerTube = innerTube,
                importManager = mockk<PlaylistImportManager>(relaxed = true),
                playlistRepository = mockk<PlaylistRepository>(relaxed = true),
                applicationScope = scope,
            ),
        )
        coEvery { manager.refresh() } returns Unit
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** Disk-cache seam: seeds the manager's remote-id lookup without network. */
    private suspend fun seedRemoteDetail() {
        withTimeout(10_000) { manager.libraryReady.first { it } }
        val dir = File(tmp.root, "yt_remote_playlists").apply { mkdirs() }
        File(dir, "$localId.json").writeText(
            """{"id":$localId,"title":"Road Trip","subtitle":"YouTube Music • Connected account","remotePlaylistId":"$remoteId","remoteTrackCount":0,"tracks":[],"cachedAtMillis":0,"playlistContentVersion":2}""",
        )
    }

    @Test
    fun urlLessTrackIsNeverPushedToYouTube() = runTest {
        seedRemoteDetail()
        // Qobuz / local / suggestion track: no watch URL, no videoId.
        val track = GeneratedTrack(name = "Never Added Song", artist = "Some Artist", url = "")

        assertFalse(manager.addTrack(localId, track))

        coVerify(exactly = 0) { innerTube.findBestMatchOrNull(any(), any(), any()) }
        coVerify(exactly = 0) { innerTube.addVideosToRemotePlaylist(any(), any()) }
    }

    @Test
    fun exactVideoIdTrackIsStillAdded() = runTest {
        seedRemoteDetail()
        val track = GeneratedTrack(name = "Known Song", artist = "Known Artist", url = "dQw4w9WgXcQ")

        assertTrue(manager.addTrack(localId, track))

        coVerify(exactly = 1) {
            innerTube.addVideosToRemotePlaylist(remoteId, listOf("dQw4w9WgXcQ"))
        }
    }

    @Test
    fun exactVideoIdIsNotDoubleAdded() = runTest {
        seedRemoteDetail()
        // Already present remotely: same videoId must be refused as duplicate.
        val dir = File(tmp.root, "yt_remote_playlists")
        File(dir, "$localId.json").writeText(
            """{"id":$localId,"title":"Road Trip","subtitle":"YouTube Music • Connected account","remotePlaylistId":"$remoteId","remoteTrackCount":1,"tracks":[{"name":"Known Song","artist":"Known Artist","url":"dQw4w9WgXcQ"}],"cachedAtMillis":0,"playlistContentVersion":2}""",
        )
        val track = GeneratedTrack(name = "Known Song", artist = "Known Artist", url = "dQw4w9WgXcQ")

        assertFalse(manager.addTrack(localId, track))
        coVerify(exactly = 0) { innerTube.addVideosToRemotePlaylist(any(), any()) }
    }
}
