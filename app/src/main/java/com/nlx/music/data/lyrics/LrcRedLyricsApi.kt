package com.nlx.music.data.lyrics

import com.nlx.music.data.artwork.awaitSuccessfulBodyOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lyrics source backed by lrc.red — an archive of millions of tracks
 * indexed directly by ISRC. Provides word-synced Apple TTML and enhanced LRC.
 */
@Singleton
class LrcRedLyricsApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val biniApi: BiniLyricsApi,
) {
    suspend fun fetchLyricsForIsrc(isrc: String): LyricsResult.Success? = withContext(Dispatchers.IO) {
        val cleanIsrc = isrc.trim().uppercase()
        if (cleanIsrc.length != 12) return@withContext null

        // 1. Try word-synced Apple TTML first
        val ttml = fetchUrl("https://lrc.red/s/$cleanIsrc.ttml")
        if (!ttml.isNullOrBlank()) {
            val lines = TtmlParser.parse(ttml).takeIf { it.isNotEmpty() }
            if (lines != null) {
                val hasWordTiming = lines.any { it.hasSyllables }
                return@withContext LyricsResult.Success(
                    lines = lines,
                    isSynced = true,
                    isWordSynced = hasWordTiming,
                    plainLyrics = lines.joinToString("\n") { it.text },
                    isInstrumental = false,
                    source = if (hasWordTiming) "lrc.red (Word-Sync)" else "lrc.red (Line-Sync)",
                )
            }
        }

        // 2. Fall back to LRC (may contain word or line timing)
        val lrc = fetchUrl("https://lrc.red/s/$cleanIsrc.lrc")
        if (!lrc.isNullOrBlank()) {
            val lines = LyricsRepository.parseLrc(lrc).takeIf { it.isNotEmpty() }
            if (lines != null) {
                val hasWordTiming = lines.any { it.hasSyllables }
                return@withContext LyricsResult.Success(
                    lines = lines,
                    isSynced = true,
                    isWordSynced = hasWordTiming,
                    plainLyrics = lines.joinToString("\n") { it.text },
                    isInstrumental = false,
                    source = if (hasWordTiming) "lrc.red (Word-Sync)" else "lrc.red (Line-Sync)",
                )
            }
        }

        null
    }

    suspend fun fetchLyricsWithIsrc(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int? = null,
        isrc: String? = null,
        hit: BiniHit? = null,
    ): Pair<String?, LyricsResult.Success>? = withContext(Dispatchers.IO) {
        // Direct ISRC
        if (!isrc.isNullOrBlank()) {
            val result = fetchLyricsForIsrc(isrc)
            if (result != null) return@withContext isrc to result
        }

        // From pre-resolved hit
        val hitIsrc = hit?.isrc?.takeIf { it.isNotBlank() }
        if (!hitIsrc.isNullOrBlank() && !hitIsrc.equals(isrc, ignoreCase = true)) {
            val result = fetchLyricsForIsrc(hitIsrc)
            if (result != null) return@withContext hitIsrc to result
        }

        // Resolve ISRC via Bini metadata if no ISRC is provided yet
        if (title.isNotBlank()) {
            val resolvedHit = runCatching {
                biniApi.identify(
                    title = title,
                    artist = artist,
                    durationSeconds = durationSeconds,
                    album = album,
                    isrc = null,
                )
            }.getOrNull()
            val resolvedIsrc = resolvedHit?.isrc?.takeIf { it.isNotBlank() }
            if (!resolvedIsrc.isNullOrBlank()) {
                val result = fetchLyricsForIsrc(resolvedIsrc)
                if (result != null) return@withContext resolvedIsrc to result
            }
        }

        null
    }

    private suspend fun fetchUrl(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "NLX_MUSIC-Android/1.0 (https://github.com/notlae/Nlx_music-)")
            .header("Accept", "*/*")
            .get()
            .build()
        return try {
            okHttpClient.newCall(request).awaitSuccessfulBodyOrNull()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }
}
