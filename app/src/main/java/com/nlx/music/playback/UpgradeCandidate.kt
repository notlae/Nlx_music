package com.nlx.music.playback

internal const val UPGRADE_DURATION_TOLERANCE_SEC = 35
internal const val UPGRADE_DURATION_ATTEMPTS = 3

/** Both durations known and more than [toleranceSec] apart means a different edit. */
internal fun durationCompatible(
    expectedSec: Int?,
    candidateSec: Int?,
    toleranceSec: Int = UPGRADE_DURATION_TOLERANCE_SEC,
): Boolean {
    if (expectedSec == null || candidateSec == null || expectedSec <= 0 || candidateSec <= 0) return true
    return kotlin.math.abs(expectedSec - candidateSec) <= toleranceSec
}

/**
 * Asks [resolve] for the next stream, skipping URLs already rejected for
 * duration. Stops when a match is found, the resolver is exhausted, or
 * [maxAttempts] is reached. A blank or repeated URL ends the walk.
 */
internal suspend fun <T> selectDurationCompatibleUpgrade(
    expectedSec: Int?,
    maxAttempts: Int = UPGRADE_DURATION_ATTEMPTS,
    toleranceSec: Int = UPGRADE_DURATION_TOLERANCE_SEC,
    urlOf: (T) -> String,
    durationSecOf: (T) -> Int?,
    onRejected: (T) -> Unit = {},
    resolve: suspend (excludedUrls: Set<String>) -> T?,
): T? {
    val excluded = LinkedHashSet<String>()
    repeat(maxAttempts) {
        val candidate = resolve(excluded) ?: return null
        if (durationCompatible(expectedSec, durationSecOf(candidate), toleranceSec)) return candidate
        onRejected(candidate)
        val url = urlOf(candidate)
        if (url.isBlank() || !excluded.add(url)) return null
    }
    return null
}
