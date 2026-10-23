package com.nlx.music.data.lyrics

import com.nlx.music.BuildConfig
import com.nlx.music.data.artwork.awaitSuccessfulBodyOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Serializable
data class NlxMusicLyricsSearchItem(
    val id: String = "",
    val name: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val durationInMillis: Long? = null,
    val isrc: String? = null,
    val hasLyrics: Boolean? = null,
    val hasTimeSyncedLyrics: Boolean? = null,
)

@Serializable
private data class NlxMusicLyricsResponse(
    val type: String? = null,
    val content: String? = null,
    val track: String? = null,
    val source: String? = null,
)

/**
 * Syllable and line-synced Apple lyrics provided by NLX_ MUSIC backend.
 *
 * Configured via GitHub Actions secrets / BuildConfig:
 * - `NLXMUSIC_LYRICS_TOKEN`: Access token passed via `X-Lyrics-Token` header.
 * - `NLXMUSIC_LYRICS_URL`: Base backend URL.
 */
@Singleton
class NlxMusicLyricsApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val baseUrl: String
        get() = BuildConfig.NLXMUSIC_LYRICS_URL.trimEnd('/')

    private val token: String
        get() = BuildConfig.NLXMUSIC_LYRICS_TOKEN

    suspend fun fetchLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int? = null,
        isrc: String? = null,
    ): LyricsResult.Success? {
        return fetchLyricsWithIsrc(title, artist, album, durationSeconds, isrc)?.second
    }

    suspend fun fetchLyricsWithIsrc(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int? = null,
        isrc: String? = null,
    ): Pair<String?, LyricsResult.Success>? = withContext(Dispatchers.IO) {
        if (title.isBlank() || token.isBlank() || baseUrl.isBlank()) return@withContext null

        val item = searchBest(title, artist, durationSeconds, isrc)
            ?: run {
                val cleanedTitle = LrclibLyricsApi.cleanTrackTitle(title)
                val cleanedArtist = LrclibLyricsApi.cleanArtistName(artist)
                if (cleanedTitle != title || cleanedArtist != artist) {
                    searchBest(cleanedTitle, cleanedArtist, durationSeconds, isrc)
                } else null
            } ?: return@withContext null

        val result = fetchLyricsForId(
            id = item.id,
            isrc = item.isrc ?: isrc,
            title = title,
            artist = artist,
        ) ?: return@withContext null

        Pair(item.isrc, result)
    }

    suspend fun fetchLyricsForId(
        id: String,
        isrc: String? = null,
        title: String? = null,
        artist: String? = null,
    ): LyricsResult.Success? = withContext(Dispatchers.IO) {
        if (id.isBlank() || token.isBlank() || baseUrl.isBlank()) return@withContext null
        val urlBuilder = "$baseUrl/lyrics".toHttpUrlOrNull()?.newBuilder() ?: return@withContext null
        urlBuilder.addQueryParameter("id", id)
        if (!isrc.isNullOrBlank()) {
            urlBuilder.addQueryParameter("isrc", isrc)
        }
        if (!title.isNullOrBlank()) {
            urlBuilder.addQueryParameter("title", title)
        }
        if (!artist.isNullOrBlank()) {
            urlBuilder.addQueryParameter("artist", artist)
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("X-Lyrics-Token", token)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        val body = try {
            okHttpClient.newCall(request).awaitSuccessfulBodyOrNull() ?: return@withContext null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return@withContext null
        }

        val trimmed = body.trim()
        if (trimmed.isEmpty()) return@withContext null

        // 1. Direct or embedded TTML
        val ttml = extractTtml(trimmed) ?: trimmed.takeIf { "<p" in it.lowercase() }
        if (!ttml.isNullOrBlank() && "<p" in ttml.lowercase()) {
            val ttmlLines = TtmlParser.parse(ttml)
            if (ttmlLines.isNotEmpty()) {
                val hasWordTiming = ttmlLines.any { it.hasSyllables }
                return@withContext LyricsResult.Success(
                    lines = ttmlLines,
                    isSynced = true,
                    isWordSynced = hasWordTiming,
                    plainLyrics = ttmlLines.joinToString("\n") { it.text },
                    isInstrumental = false,
                    source = if (hasWordTiming) "NLX_ MUSIC (Word-Sync)" else "NLX_ MUSIC (Line-Sync)",
                )
            }
        }

        // 2. Parse JSON response envelope for LRC or plain text
        val response = try {
            json.decodeFromString<NlxMusicLyricsResponse>(trimmed)
        } catch (_: Exception) {
            null
        }

        val content = response?.content?.takeIf { it.isNotBlank() } ?: trimmed

        // Check if content is LRC
        val lrcLines = LyricsRepository.parseLrc(content)
        if (lrcLines.isNotEmpty()) {
            return@withContext LyricsResult.Success(
                lines = lrcLines,
                isSynced = true,
                isWordSynced = false,
                plainLyrics = lrcLines.joinToString("\n") { it.text },
                isInstrumental = false,
                source = "NLX_ MUSIC (Line-Sync)",
            )
        }

        // Plain text fallback
        if (content.isNotBlank() && !content.startsWith("{")) {
            return@withContext LyricsResult.Success(
                lines = emptyList(),
                isSynced = false,
                isWordSynced = false,
                plainLyrics = content.trim(),
                isInstrumental = false,
                source = "NLX_ MUSIC (Plain)",
            )
        }

        null
    }

    private suspend fun searchBest(
        title: String,
        artist: String,
        durationSeconds: Int?,
        isrc: String?,
    ): NlxMusicLyricsSearchItem? {
        val query = if (artist.isNotBlank()) "$title $artist" else title
        val items = performSearch(query).ifEmpty {
            if (artist.isNotBlank()) performSearch(title) else emptyList()
        }
        if (items.isEmpty()) return null

        val expectedMs = durationSeconds?.takeIf { it > 0 }?.times(1_000L)
        val candidates = items
            .filter { it.id.isNotBlank() }
            .filter { LrclibLyricsApi.sameVersion(title, it.name.orEmpty()) }
        if (candidates.isEmpty()) return null

        if (!isrc.isNullOrBlank()) {
            candidates.firstOrNull { it.isrc.equals(isrc.trim(), ignoreCase = true) }?.let {
                return it
            }
        }

        return candidates.maxWithOrNull(
            compareBy(
                { score(it, title, artist, expectedMs) },
                { -durationDistance(it, expectedMs) },
            ),
        )?.takeIf { isVerifiedMatch(it, title, artist, expectedMs) }
    }

    private suspend fun performSearch(query: String): List<NlxMusicLyricsSearchItem> {
        val url = "$baseUrl/search".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("q", query.trim())
            ?.build() ?: return emptyList()

        val request = Request.Builder()
            .url(url)
            .header("X-Lyrics-Token", token)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        val body = try {
            okHttpClient.newCall(request).awaitSuccessfulBodyOrNull() ?: return emptyList()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return emptyList()
        }

        return try {
            json.decodeFromString<List<NlxMusicLyricsSearchItem>>(body)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun durationDistance(song: NlxMusicLyricsSearchItem, expectedMs: Long?): Long {
        val songMs = song.durationInMillis ?: return Long.MAX_VALUE
        if (expectedMs == null || expectedMs <= 0 || songMs <= 0) return 0L
        return abs(songMs - expectedMs)
    }

    private fun isVerifiedMatch(song: NlxMusicLyricsSearchItem, title: String, artist: String, expectedMs: Long?): Boolean {
        if (!LrclibLyricsApi.sameVersion(title, song.name.orEmpty())) return false
        if (titleScore(song.name.orEmpty(), title) <= 0) return false
        if (artist.isBlank()) {
            if (titleScore(song.name.orEmpty(), title) < 3) return false
            return score(song, title, artist, expectedMs) >= MIN_MATCH_SCORE - 2
        }
        if (artistScore(song.artistName.orEmpty(), artist) <= 0) return false
        return score(song, title, artist, expectedMs) >= MIN_MATCH_SCORE
    }

    private fun titleScore(songTitle: String, title: String): Int {
        val cleanSong = LrclibLyricsApi.cleanTrackTitle(songTitle)
        val cleanReq = LrclibLyricsApi.cleanTrackTitle(title)
        if (cleanSong.equals(cleanReq, ignoreCase = true)) return 3
        if (LrclibLyricsApi.titlesMatch(cleanSong, cleanReq)) return 1
        return 0
    }

    private fun artistScore(songArtist: String, artist: String): Int {
        if (artist.isBlank() || songArtist.isBlank()) return 0
        val cleanSong = LrclibLyricsApi.cleanArtistName(songArtist)
        val cleanReq = LrclibLyricsApi.cleanArtistName(artist)
        return if (LrclibLyricsApi.artistMatches(cleanSong, cleanReq)) 2 else 0
    }

    private fun score(song: NlxMusicLyricsSearchItem, title: String, artist: String, expectedMs: Long?): Int {
        var score = titleScore(song.name.orEmpty(), title) + artistScore(song.artistName.orEmpty(), artist)
        val songMs = song.durationInMillis
        if (expectedMs != null && expectedMs > 0 && songMs != null && songMs > 0) {
            score += when (abs(songMs - expectedMs)) {
                in 0..3_000L -> 3
                in 3_001L..DURATION_TOLERANCE_MS -> 1
                else -> 0
            }
        }
        return score
    }

    private fun extractTtml(raw: String): String? {
        if (!raw.startsWith("{") && !raw.startsWith("[")) return raw
        return try {
            val element = json.parseToJsonElement(raw)
            findTtmlString(element)
        } catch (_: Exception) {
            null
        }
    }

    private fun findTtmlString(element: JsonElement): String? {
        return when (element) {
            is JsonPrimitive -> if (element.isString) {
                element.content.takeIf { "<p" in it.lowercase() || "<tt" in it.lowercase() }
            } else null
            is JsonArray -> element.firstNotNullOfOrNull { findTtmlString(it) }
            is JsonObject -> {
                val keys = listOf("content", "ttml", "ttmlContent", "lyrics", "lrc", "text", "data", "result")
                keys.firstNotNullOfOrNull { element[it]?.let { v -> findTtmlString(v) } }
                    ?: element.values.firstNotNullOfOrNull { findTtmlString(it) }
            }
        }
    }

    companion object {
        private const val USER_AGENT = "NLX_MUSIC-Android/1.0"
        private const val DURATION_TOLERANCE_MS = 6_000L
        private const val MIN_MATCH_SCORE = 3
    }
}
