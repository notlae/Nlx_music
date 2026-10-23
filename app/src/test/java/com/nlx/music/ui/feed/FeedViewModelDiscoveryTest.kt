package com.nlx.music.ui.feed

import com.google.common.truth.Truth.assertThat
import com.nlx.music.data.discover.DiscoverRepository
import com.nlx.music.data.feed.FeedData
import com.nlx.music.data.feed.FeedRepository
import com.nlx.music.data.generate.GeneratedTrack
import com.nlx.music.data.local.SessionData
import com.nlx.music.data.local.SessionPreferences
import com.nlx.music.data.local.SettingsPreferences
import com.nlx.music.data.music.InnerTubeMusicApi
import com.nlx.music.data.music.YouTubeMusicTrack
import com.nlx.music.data.repository.HomeRepository
import com.nlx.music.data.repository.RelatedGraphRepository
import com.nlx.music.data.ytmusic.YtConnection
import com.nlx.music.data.ytmusic.YtMusicAuthManager
import com.nlx.music.playback.MusicPlayer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class FeedViewModelDiscoveryTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val context: android.content.Context = mockk(relaxed = true)
    private val feedRepository: FeedRepository = mockk(relaxed = true)
    private val sessionPreferences: SessionPreferences = mockk(relaxed = true)
    private val settingsPreferences: SettingsPreferences = mockk(relaxed = true)
    private val musicPlayer: MusicPlayer = mockk(relaxed = true)
    private val innerTube: InnerTubeMusicApi = mockk(relaxed = true)
    private val relatedGraph: RelatedGraphRepository = mockk(relaxed = true)
    private val ytAuth: YtMusicAuthManager = mockk(relaxed = true)
    private val homeRepository: HomeRepository = mockk(relaxed = true)
    private val discoverRepository: DiscoverRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { ytAuth.connection } returns MutableStateFlow(YtConnection())
        coEvery { ytAuth.awaitLoadedConnection() } returns YtConnection()
        coEvery { sessionPreferences.session } returns MutableStateFlow(SessionData(username = "test_user"))
        coEvery { settingsPreferences.settings } returns MutableStateFlow(mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadDiscovery_populatesDiscoveryTracks() {
        val mockTrack = GeneratedTrack("Discovery Song", "Discovery Artist", "https://artwork.url")
        coEvery { discoverRepository.allowedCachedFeed() } returns listOf(mockTrack)

        val viewModel = FeedViewModel(
            context,
            feedRepository,
            sessionPreferences,
            settingsPreferences,
            musicPlayer,
            innerTube,
            relatedGraph,
            ytAuth,
            homeRepository,
            discoverRepository,
        )

        assertThat(viewModel.discoveryTracks.value).containsExactly(mockTrack)
    }

    @Test
    fun loadDiscovery_failureHandlesErrorGracefully() {
        coEvery { discoverRepository.allowedCachedFeed() } throws RuntimeException("Network error")
        coEvery { discoverRepository.nextBatch(any()) } throws RuntimeException("Network error")

        val viewModel = FeedViewModel(
            context,
            feedRepository,
            sessionPreferences,
            settingsPreferences,
            musicPlayer,
            innerTube,
            relatedGraph,
            ytAuth,
            homeRepository,
            discoverRepository,
        )

        assertThat(viewModel.discoveryTracks.value).isEmpty()
    }

    @Test
    fun quickPicks_combinesFamiliarAndNewDiscoveryTracks() {
        val familiarTrack = YouTubeMusicTrack("vid1", "Familiar Song", "Familiar Artist", "Album1", "https://art1")
        val newDiscoveryTrack = YouTubeMusicTrack("vid2", "New Song", "New Artist", "Album2", "https://art2")

        val feedData = FeedData(
            quickPicks = listOf(familiarTrack, newDiscoveryTrack),
        )

        assertThat(feedData.quickPicks).hasSize(2)
        assertThat(feedData.quickPicks).contains(familiarTrack)
        assertThat(feedData.quickPicks).contains(newDiscoveryTrack)
    }
}
