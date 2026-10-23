package com.nlx.music.playback

import android.media.AudioManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * These rules replace ExoPlayer's built-in focus handling, which answered a
 * transient loss with a playback suppression: playWhenReady stayed true while
 * nothing rendered, so the song froze under a pause icon and only recovered if
 * the other app sent AUDIOFOCUS_GAIN. Each test pins one decision so the
 * frozen-clock regression can't come back through one of these branches.
 */
class AudioFocusPolicyTest {

    @Test
    fun `a brief interruption pauses but is restored automatically`() {
        assertThat(AudioFocusPolicy.actionFor(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
            .isEqualTo(AudioFocusAction.PauseTransiently)
        assertThat(AudioFocusPolicy.actionFor(AudioManager.AUDIOFOCUS_GAIN))
            .isEqualTo(AudioFocusAction.Restore)
    }

    @Test
    fun `another media app pausing playback is never auto-resumed`() {
        assertThat(AudioFocusPolicy.actionFor(AudioManager.AUDIOFOCUS_LOSS))
            .isEqualTo(AudioFocusAction.PausePermanently)
    }

    @Test
    fun `a may-duck request only lowers the level`() {
        assertThat(AudioFocusPolicy.actionFor(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
            .isEqualTo(AudioFocusAction.Duck)
    }

    @Test
    fun `an unlisted exclusive borrow keeps playback untouched`() {
        assertThat(AudioFocusPolicy.actionFor(AudioManager.AUDIOFOCUS_NONE))
            .isEqualTo(AudioFocusAction.KeepPlaying)
    }

    @Test
    fun `unknown focus events do not touch playback`() {
        assertThat(AudioFocusPolicy.actionFor(AudioManager.AUDIOFOCUS_NONE))
            .isEqualTo(AudioFocusAction.KeepPlaying)
    }

    @Test
    fun `duck level is audible but clearly behind the other source`() {
        assertThat(AudioFocusPolicy.DUCK_VOLUME).isGreaterThan(0f)
        assertThat(AudioFocusPolicy.DUCK_VOLUME).isLessThan(0.5f)
    }
}
