package com.nlx.music.presence

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nlx.music.data.artwork.ArtworkNormalizer
import com.nlx.music.data.artwork.ArtworkRepository
import com.nlx.music.data.artwork.DeferredArtworkLookup
import com.nlx.music.playback.MusicPlayer
import com.nlx.music.playback.MusicPlayerState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Publishes what is playing to Discord as a Rich Presence activity.
 *
 * Behaviour is ported from the desktop client
 * (`NlxMusic-Desktop/lib/features/presence/discord_presence_service.dart`):
 * push on track or play-state change, never on the position ticker; stay silent
 * when Discord is absent, refuses, or rate-limits; clear the card the moment
 * playback stops or the switch is turned off.
 *
 * Two throttles, both inherited from desktop and both load-bearing:
 * - [DiscordPresence.PUSH_INTERVAL_MS] on a signature of everything the card
 *   shows, so the ~16 Hz position stream costs at most one frame per interval.
 * - [DiscordPresence.RETRY_INTERVAL_MS] on connection attempts, because the
 *   common case is a user who simply does not have Discord installed and that
 *   must never be polled harder than the push cadence.
 *
 * A slow heartbeat re-evaluates on the same interval as the push throttle, so
 * presence can never go quietly stale (missed snapshot, Discord restarted while
 * paused, a push dropped by a dead transport).
 *
 * Playback is never touched, delayed or gated by anything in here: every
 * failure is swallowed and every frame is fire-and-forget.
 */
@Singleton
class DiscordPresenceManager @Inject constructor(
    private val musicPlayer: MusicPlayer,
    private val preferences: DiscordPresencePreferences,
    private val artworkRepository: ArtworkRepository,
    private val applicationScope: CoroutineScope,
    @ApplicationContext private val context: Context,
) {
    private val lock = Mutex()

    private var transport: DiscordTransport? = null
    private var started = false

    @Volatile private var enabled = true
    private var lastAttemptMs = 0L
    private var lastKey = ""
    private var lastPushMs = 0L
    private val deferredArtwork = DeferredArtworkLookup(
        scope = applicationScope,
        source = "discord",
        hasMemoryHit = { key ->
            artworkRepository.resolved.value[key]
                ?.startsWith("http://") == true ||
                artworkRepository.resolved.value[key]?.startsWith("https://") == true
        },
        resolve = { name, artist ->
            runCatching { artworkRepository.resolve(name, artist) }
        },
    )
    private var observer: Job? = null
    private var heartbeat: Job? = null

    @Synchronized
    fun start() {
        if (started) return
        started = true
        observer = applicationScope.launch(Dispatchers.Default) {
            try {
                combine(
                    musicPlayer.state,
                    preferences.enabled,
                    artworkRepository.resolved,
                ) { state, on, resolved ->
                    Triple(state, on, resolved)
                }.collect { (state, on, resolved) ->
                    enabled = on
                    evaluate(state, resolved)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Log.e(TAG, "Discord presence observer stopped", error)
            }
        }
        heartbeat = applicationScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(DiscordPresence.PUSH_INTERVAL_MS)
                val snapshot = musicPlayer.state.value
                evaluate(snapshot, artworkRepository.resolved.value)
            }
        }
    }

    /** Re-evaluates immediately (settings toggle, or an explicit refresh). */
    fun refresh() {
        applicationScope.launch(Dispatchers.Default) {
            evaluate(musicPlayer.state.value, artworkRepository.resolved.value)
        }
    }

    private suspend fun evaluate(state: MusicPlayerState, resolvedArt: Map<String, String>) {
        val now = SystemClock.elapsedRealtime()
        lock.withLock {
            // Disabled or unconfigured: hide anything still on the profile.
            if (!enabled || !DiscordPresence.isConfigured()) {
                deferredArtwork.cancel()
                clearQuiet()
                return
            }
            val track = state.current
            if (track == null || track.title.isBlank()) {
                deferredArtwork.cancel()
                clearQuiet()
                return
            }
            // The track's own http(s) art is exact; otherwise take whatever
            // the multi-provider pipeline (Apple -> Tidal -> Deezer ->
            // Spotify -> YouTube) has resolved for this title+artist. Either
            // way Discord gets a fetchable cover instead of the logo key.
            val ownArt = track.artworkUrl?.trim()
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            val pipelineArt = resolvedArt[ArtworkNormalizer.cacheKey(track.title, track.artist)]
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            if (ownArt == null && pipelineArt == null) {
                // Nothing cached yet. Wait before Deezer/iTunes/Tidal so a
                // skip does not search. The publish re-emits `resolved`,
                // which re-runs this evaluation with art.
                deferredArtwork.request(track.title, track.artist)
            }
            val effectiveState =
                if (pipelineArt != null && pipelineArt != track.artworkUrl) {
                    state.copy(current = track.copy(artworkUrl = pipelineArt))
                } else {
                    state
                }
            val key = DiscordPresence.signature(effectiveState)
            if (key == lastKey && now - lastPushMs < DiscordPresence.PUSH_INTERVAL_MS) {
                return // Position ticks must not become frames.
            }
            val active = ensureConnected(now)
            if (active == null) {
                // ensureConnected already logged why (throttled vs missing).
                return
            }
            val sent = runCatching {
                active.setActivity(DiscordPresence.buildActivity(effectiveState, System.currentTimeMillis()))
            }.onFailure {
                Log.w(TAG, "Discord presence push failed", it)
            }.getOrDefault(false)
            if (!sent) {
                // Transport died mid-push: drop it so the next evaluation
                // reconnects instead of writing into a dead connection.
                Log.w(TAG, "Discord push rejected; dropping connection")
                runCatching { active.close() }
                transport = null
                lastAttemptMs = SystemClock.elapsedRealtime()
                return
            }
            lastKey = key
            lastPushMs = now
            // One line per frame actually handed to Discord: a card that never
            // appears is then diagnosable as "no pushes at all" (observer /
            // heartbeat dead), "pushes but refused" (WARN above), or "pushes
            // fine, Discord drops it" (an app-side or rate-limit problem).
            Log.d(TAG, "Discord presence pushed: $key")
        }
    }

    /** Existing connection, or a new one if the retry throttle allows it. */
    private suspend fun ensureConnected(now: Long): DiscordTransport? {
        val existing = transport
        if (existing != null && existing.isOpen) return existing
        if (existing != null && !existing.isOpen) {
            Log.d(TAG, "Discord transport dead; reconnecting")
            runCatching { existing.close() }
            transport = null
        }
        if (now - lastAttemptMs < DiscordPresence.RETRY_INTERVAL_MS) return null
        runCatching { existing?.close() }
        transport = null
        lastAttemptMs = now
        val fresh = DiscordIpcTransport.connectOrNull(context)
        if (fresh == null) {
            Log.w(TAG, "Discord connect failed (app missing/signed-out/refusing?); retry in 15s")
        }
        return fresh?.also { transport = it }
    }

    /**
     * Hides the card without tearing the connection down, and forgets the last
     * signature so the next real track is never deduped away as "unchanged".
     */
    private fun clearQuiet() {
        lastKey = ""
        val active = transport ?: return
        runCatching { active.clearActivity() }
            .onFailure { Log.d(TAG, "Discord presence clear ignored", it) }
    }

    /** Best-effort clear + release. Never throws. */
    suspend fun shutdown() {
        heartbeat?.cancel()
        heartbeat = null
        observer?.cancel()
        observer = null
        lock.withLock {
            clearQuiet()
            runCatching { transport?.close() }
            transport = null
        }
        started = false
    }

    private companion object {
        const val TAG = "DiscordPresence"
    }
}
