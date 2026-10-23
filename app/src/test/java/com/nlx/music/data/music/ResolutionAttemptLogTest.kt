package com.nlx.music.data.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ResolutionAttemptLogTest {
    @Test
    fun summaryRedactsSignedUrlsAndSecrets() {
        val log = ResolutionAttemptLog()
        log.record(
            "probe client=VISIONOS http=403 url=https://rr1.googlevideo.com/videoplayback?expire=1&sig=abc&n=tok",
        )
        log.record("cookie=SID=secret authorization=Bearer abc sig=abc")
        val summary = log.summary()
        assertFalse(summary.contains("http://"))
        assertFalse(summary.contains("https://"))
        assertFalse(summary.contains("sig=abc"))
        assertFalse(summary.contains("SID=secret"))
        assertFalse(summary.contains("Bearer abc"))
        assertTrue(summary.contains("client=VISIONOS"))
        assertTrue(summary.contains("http=403"))
        assertTrue(summary.contains("[url]"))
        assertTrue(summary.contains("cookie=[redacted]"))
        assertTrue(summary.contains("authorization=[redacted]"))
        assertTrue(summary.contains("sig=[redacted]"))
    }

    @Test
    fun ioFailureIsExpectedAndCancellationIsNot() {
        val missed = IOException("Unable to resolve a playable audio stream for video")
        assertTrue(missed.isExpectedAudioResolutionFailure())
        assertFalse(missed.isCooperativeCancellation())

        val stopped = CancellationException("skipped")
        assertFalse(stopped.isExpectedAudioResolutionFailure())
        assertTrue(stopped.isCooperativeCancellation())
    }

    @Test
    fun timeoutIsExpectedAndNotCooperative() = runBlocking {
        val timeout = runCatching { withTimeout(1) { delay(10_000) } }.exceptionOrNull()!!
        assertTrue(timeout.isExpectedAudioResolutionFailure())
        assertFalse(timeout.isCooperativeCancellation())
        val detail = resolutionFailureDetail(timeout)
        assertTrue(detail.contains(timeout.javaClass.simpleName))
        assertFalse(detail.contains("https://"))
    }
}
