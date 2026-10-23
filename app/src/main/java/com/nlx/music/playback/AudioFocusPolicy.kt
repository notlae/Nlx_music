package com.nlx.music.playback

import android.media.AudioManager

/**
 * What the app must do about one platform audio-focus change.
 *
 * ExoPlayer's own focus handling answers a transient loss with a playback
 * *suppression*: it keeps `playWhenReady` true but stops rendering. The UI then
 * shows the pause icon on a frozen clock with no error, on a track that is
 * neither playing nor resumable by the play button, and it only moves again if
 * the app that took focus ever sends [AudioManager.AUDIOFOCUS_GAIN] — some
 * never do, so the song stays stuck "paused" mid-track. NLX_ MUSIC therefore owns
 * the decision itself, where every state has an explicit recovery path.
 */
enum class AudioFocusAction {
    /** The interruption is over: undo whatever the loss did. */
    Restore,

    /**
     * Another media app took playback over (the user started something else).
     * Pause and let the user resume explicitly — never steal the focus back.
     */
    PausePermanently,

    /**
     * A brief interruption (phone call, assistant). Pause, but remember the
     * user wanted music so a later [AudioManager.AUDIOFOCUS_GAIN] resumes it
     * automatically instead of parking on a frozen clock.
     */
    PauseTransiently,

    /** Keep playing, quietly. */
    Duck,

    /** Nothing this player must do. */
    KeepPlaying,
}

/** Platform focus events mapped to those decisions. */
object AudioFocusPolicy {
    /** Quiet level used while an [AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK] is held. */
    const val DUCK_VOLUME = 0.25f

    fun actionFor(focusChange: Int): AudioFocusAction = when (focusChange) {
        AudioManager.AUDIOFOCUS_GAIN -> AudioFocusAction.Restore
        AudioManager.AUDIOFOCUS_LOSS -> AudioFocusAction.PausePermanently
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AudioFocusAction.PauseTransiently
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> AudioFocusAction.Duck
        // AUDIOFOCUS_LOSS_TRANSIENT_EXCLUSIVE comes from recorders/assistants
        // that only borrow the output briefly: it has no public constant on
        // every SDK level, so it lands here and playback continues.
        else -> AudioFocusAction.KeepPlaying
    }
}
