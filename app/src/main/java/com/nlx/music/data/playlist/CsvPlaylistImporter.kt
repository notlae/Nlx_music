package com.nlx.music.data.playlist

import com.nlx.music.data.generate.GeneratedTrack
import com.nlx.music.data.generate.youtubeVideoIdOrNull
import com.nlx.music.data.music.InnerTubeMusicApi
import com.nlx.music.data.music.TextMatch
import com.nlx.music.data.music.YouTubeMusicTrack
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private const val TAG = "CsvImport"

data class CsvRawTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val videoId: String? = null,
)

data class CsvImportResult(
    val suggestedTitle: String,
    val totalRows: Int,
    val matchedCount: Int,
    val tracks: List<GeneratedTrack>,
)

@Singleton
class CsvPlaylistImporter @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
) {
    suspend fun parseAndMatchCsv(
        inputStream: InputStream,
        filename: String = "Imported Playlist",
    ): CsvImportResult = withContext(Dispatchers.IO) {
        val rawTracks = parseTracks(decodeText(inputStream), filename)
        // Exported playlists repeat rows often. Collapsing them up front keeps
        // the request budget spent on rows that can actually resolve.
        val rows = rawTracks.distinctBy(::rowIdentity)
        val limiter = Semaphore(SEARCH_CONCURRENCY)
        val matched = coroutineScope {
            rows.map { raw ->
                async {
                    limiter.withPermit {
                        try {
                            matchRow(raw)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Exception) {
                            android.util.Log.w(TAG, "match failed for \"${raw.title}\"", error)
                            null
                        }
                    }
                }
            }.awaitAll()
        }.filterNotNull().dedupeByVideoId()

        CsvImportResult(
            suggestedTitle = filename.substringBeforeLast('.').replace(Regex("[_-]+"), " ").trim().ifBlank { "Imported Playlist" },
            totalRows = rows.size,
            matchedCount = matched.size,
            tracks = matched,
        )
    }

    /**
     * Resolves one file row to a playable track, or null when the row cannot be
     * verified. A row is only ever accepted when the title and artist it
     * carries agree with the track that comes back, so a stale link or a fuzzy
     * near-miss drops the row instead of quietly importing the wrong song.
     */
    private suspend fun matchRow(raw: CsvRawTrack): GeneratedTrack? {
        // A link in the file is a claim, not proof. Check that it resolves to
        // the id we asked for and that it actually describes the row's song.
        val linked = raw.videoId?.let { fetchLinkedTrack(it) }
        if (linked != null && linkMatchesRow(raw, linked)) return trackFrom(raw, linked)
        if (linked != null) {
            android.util.Log.w(
                TAG,
                "link for \"${raw.title}\" points at \"${linked.title}\" by ${linked.artist}; resolving by search instead",
            )
        }
        if (raw.title.isBlank()) return null
        return searchMatch(raw)?.let { trackFrom(raw, it) }
    }

    /**
     * Resolves a pasted link, retrying like the search path does.
     * [InnerTubeMusicApi.fetchSongDetails] reports transport failures as null,
     * so one attempt cannot tell a dead link from a dropped connection.
     */
    private suspend fun fetchLinkedTrack(videoId: String): YouTubeMusicTrack? {
        repeat(LINK_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(SEARCH_RETRY_BACKOFF_MS * attempt)
            val details = runCatching { innerTube.fetchSongDetails(videoId) }.getOrNull()
            if (details != null && details.videoId == videoId) return details
        }
        return null
    }

    /**
     * A link is only checked where the row actually makes a claim: blank or
     * placeholder fields cannot falsify anything, so they are skipped. Album is
     * deliberately not enforced, because album names drift far more often
     * between a library export and a single upload than titles do.
     */
    internal fun linkMatchesRow(source: CsvRawTrack, target: YouTubeMusicTrack): Boolean {
        if (!VIDEO_ID.matches(target.videoId)) return false
        // Nothing was claimed beyond the link itself, so there is nothing to
        // contradict: this row asked for exactly this video.
        if (source.title.isBlank()) return true
        val targetArtist = target.artist.removeSuffix(" - Topic")
        val cleanTargetTitle = stripArtistPrefix(target.title, targetArtist)
        val titleMatches = sameText(source.title, target.title, allowSafeVideoLabel = true) ||
            sameText(source.title, cleanTargetTitle, allowSafeVideoLabel = true) ||
            sameText(cleanBaseTitle(source.title), cleanBaseTitle(cleanTargetTitle)) ||
            TextMatch.isSafeTitleMatch(target.title, source.title, source.artist)
        if (source.artist.isBlank() || source.artist.isUnknownArtistLabel()) {
            return titleMatches
        }
        if (!titleMatches) return false
        val normSourceArtist = normalize(source.artist).removePrefix("the ").trim()
        val normTargetArtist = normalize(targetArtist).removePrefix("the ").trim()
        return sameArtist(source.artist, targetArtist) ||
            normTargetArtist.contains(normSourceArtist) || normSourceArtist.contains(normTargetArtist) ||
            normalize(target.title).contains(normSourceArtist)
    }

    /**
     * Resolves a row by search. [InnerTubeMusicApi.searchSongs] folds transport
     * failures into an empty list, so an empty pass is indistinguishable from a
     * rate limit or a timeout: retry a bounded number of times with a short
     * backoff before giving the row up.
     */
    private suspend fun searchMatch(raw: CsvRawTrack): YouTubeMusicTrack? {
        val cleanArtist = raw.artist.takeUnless { it.isUnknownArtistLabel() }.orEmpty()
        repeat(SEARCH_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(SEARCH_RETRY_BACKOFF_MS * attempt)
            verifiedSearch(raw, cleanArtist)?.let { return it }
        }
        return null
    }

    /**
     * One search pass. Each query variant is walked in full and validated
     * before the next one is issued, so:
     *
     *  - a row that resolves on the first query still costs exactly one search,
     *    and
     *  - a song buried anywhere in the result list is still reachable, because
     *    every candidate is offered to [matchesRow] instead of just the top hit.
     */
    private suspend fun verifiedSearch(raw: CsvRawTrack, cleanArtist: String): YouTubeMusicTrack? {
        for (query in queryVariants(raw, cleanArtist)) {
            val candidates = innerTube.searchSongs(
                query = query,
                limit = SEARCH_RESULT_LIMIT,
                prefetchStreams = false,
            )
            val exactMatches = candidates.filter { matchesRow(raw, it) }
            if (exactMatches.isNotEmpty()) {
                return exactMatches.maxByOrNull { TextMatch.matchScore(it, raw.title, cleanArtist) }
            }

            val flexibleMatches = candidates.filter { flexibleMatchesRow(raw, it) }
            if (flexibleMatches.isNotEmpty()) {
                return flexibleMatches.maxByOrNull { TextMatch.matchScore(it, raw.title, cleanArtist) }
            }
        }
        return null
    }

    /**
     * Query shapes in order of precision. Prioritize clean artist and title
     * so search quickly finds the right song without bursting rate-limits.
     */
    private fun queryVariants(raw: CsvRawTrack, cleanArtist: String): List<String> {
        if (raw.title.isBlank()) return emptyList()
        val title = raw.title.trim()
        val baseTitle = cleanBaseTitle(title).ifBlank { title }
        val primaryArtist = splitArtists(cleanArtist).firstOrNull() ?: cleanArtist
        return buildList<String> {
            if (cleanArtist.isNotBlank()) {
                add("$title $primaryArtist")
                if (cleanArtist != primaryArtist) {
                    add("$title $cleanArtist")
                }
                if (baseTitle != title) {
                    add("$baseTitle $primaryArtist")
                }
                add("$cleanArtist $title")
            }
            add(title)
            if (baseTitle != title) {
                add(baseTitle)
            }
        }.map(String::trim).distinct()
    }

    private fun matchesRow(raw: CsvRawTrack, target: YouTubeMusicTrack): Boolean {
        if (!VIDEO_ID.matches(target.videoId)) return false
        if (raw.title.isBlank()) return false
        // A row that names an artist is held to title *and* artist. A
        // title-only row can only be held to its title.
        if (raw.artist.isBlank()) {
            val cleanTarget = stripArtistPrefix(target.title, target.artist)
            return sameText(raw.title, target.title, allowSafeVideoLabel = true) ||
                sameText(raw.title, cleanTarget, allowSafeVideoLabel = true)
        }
        return isExactMatch(raw.copy(album = null), target)
    }

    private fun flexibleMatchesRow(raw: CsvRawTrack, target: YouTubeMusicTrack): Boolean {
        if (!VIDEO_ID.matches(target.videoId)) return false
        if (raw.title.isBlank()) return false
        if (!allowsVersion(raw.title, target.title)) return false
        if (raw.artist.isNotBlank() && !sameArtist(raw.artist, target.artist)) return false

        val rawBase = cleanBaseTitle(raw.title)
        val cleanTarget = stripArtistPrefix(target.title, target.artist)
        val targetBase = cleanBaseTitle(cleanTarget)

        return (rawBase.isNotBlank() && sameText(rawBase, targetBase)) ||
            TextMatch.isSafeTitleMatch(target.title, raw.title, raw.artist)
    }

    private fun allowsVersion(sourceTitle: String, targetTitle: String): Boolean {
        val sNorm = normalize(sourceTitle)
        val tNorm = normalize(targetTitle)
        // Rendition markers share TextMatch.VARIANT_WORDS with the playback
        // matcher (multilingual since the language fix); 'tribute' stays
        // import-local — never a legitimate suffix, only a mismatch signal.
        for (word in TextMatch.VARIANT_WORDS + "tribute") {
            if (tNorm.contains(word) && !sNorm.contains(word)) return false
        }
        return true
    }

    private fun cleanBaseTitle(title: String): String = title
        .replace(SAFE_VIDEO_LABEL_REGEX, "")
        .replace(SAFE_BRACKET_LABEL_REGEX, "")
        .replace(FEATURE_REGEX, "")
        .replace(NOISE_SUFFIX, "")
        .replace(Regex("(?i)\\s*[\\[(]\\s*(?:\\d{4}\\s+)?(?:remaster(?:ed)?|remix|live|bonus|deluxe|edition|anniversary|version|mix|edit|audio|visualizer|explicit|clean|mono|stereo|original)\\b[^\\])]*[\\])]"), "")
        .replace(Regex("(?i)\\s*-\\s*(?:(?:\\d{4}\\s+)?remaster(?:ed)?|radio\\s*edit|single\\s*version|bonus\\s*track|deluxe\\s*edition|live|album\\s*version|acoustic).*$"), "")
        .trim()

    private fun stripArtistPrefix(title: String, artist: String): String {
        if (artist.isBlank()) return title
        val clean = artist.removeSuffix(" - Topic").trim()
        if (clean.isBlank()) return title
        val escaped = Regex.escape(clean)
        return title.replace(Regex("^(?i)\\s*$escaped\\s*[-–—:]\\s*"), "").trim()
    }

    private fun splitArtists(artist: String): List<String> =
        artist.removeSuffix(" - Topic")
            .split(Regex("(?i)\\s*(?:,|&|/|;|feat\\.?|ft\\.?|featuring|with|x)\\s*"))
            .map(String::trim)
            .filter(String::isNotBlank)

    private fun trackFrom(raw: CsvRawTrack, target: YouTubeMusicTrack): GeneratedTrack = GeneratedTrack(
        name = raw.title.ifBlank { target.title },
        artist = raw.artist.takeUnless { it.isUnknownArtistLabel() }.orEmpty().ifBlank { target.artist },
        album = raw.album ?: target.album,
        artworkUrl = target.artworkUrl
            ?: target.videoId.takeIf { VIDEO_ID.matches(it) }?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" },
        url = "https://music.youtube.com/watch?v=${target.videoId}",
    )

    internal fun isExactMatch(source: CsvRawTrack, target: YouTubeMusicTrack): Boolean {
        if (!VIDEO_ID.matches(target.videoId)) return false
        if (source.videoId != null && source.videoId != target.videoId) return false
        if (source.videoId == null && (source.title.isBlank() || source.artist.isBlank())) return false
        if (source.artist.isUnknownArtistLabel()) return false
        val cleanTargetTitle = stripArtistPrefix(target.title, target.artist.removeSuffix(" - Topic"))
        val titleMatches = sameText(source.title, target.title, allowSafeVideoLabel = true) ||
            sameText(source.title, cleanTargetTitle, allowSafeVideoLabel = true)
        if (source.title.isNotBlank() && !titleMatches) return false
        if (source.artist.isNotBlank() && !sameArtist(source.artist, target.artist.removeSuffix(" - Topic"))) return false
        if (!source.album.isNullOrBlank() && !target.album.isNullOrBlank() && !sameText(source.album, target.album.orEmpty())) return false
        return true
    }

    private fun sameText(source: String, target: String, allowSafeVideoLabel: Boolean = false): Boolean {
        val srcText = if (allowSafeVideoLabel) stripSafeVideoLabel(source) else source
        val targetText = if (allowSafeVideoLabel) stripSafeVideoLabel(target) else target
        val normalizedSrc = normalize(srcText)
        val normalizedTarget = normalize(targetText)
        return normalizedSrc.isNotBlank() && normalizedSrc == normalizedTarget
    }

    private fun sameArtist(source: String, target: String): Boolean {
        val cleanTarget = target
            .removeSuffix(" - Topic")
            .replace(Regex("(?i)\\b(?:vevo|official|music|records|topic)\\b"), "")
            .trim()
        if (sameText(source, cleanTarget)) return true
        val sourceList = splitArtists(source)
        val targetList = splitArtists(cleanTarget)
        val primarySource = sourceList.firstOrNull() ?: source
        val primaryTarget = targetList.firstOrNull() ?: cleanTarget
        if (sameText(primarySource, primaryTarget)) return true
        if (sourceList.any { s -> targetList.any { t -> sameText(s, t) } }) return true
        val normSource = normalize(primarySource).removePrefix("the ").trim()
        val normTarget = normalize(primaryTarget).removePrefix("the ").trim()
        return normSource.isNotBlank() && normSource == normTarget
    }

    private fun stripSafeVideoLabel(title: String): String = title
        .replace(SAFE_VIDEO_LABEL_REGEX, "")
        .replace(SAFE_BRACKET_LABEL_REGEX, "")
        .trim()

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .replace(Regex("['’\"“”]"), "")
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()

    private fun String.isUnknownArtistLabel(): Boolean =
        isBlank() || normalize(this) in UNKNOWN_ARTIST_LABELS

    private fun rowIdentity(track: CsvRawTrack): String =
        track.videoId?.let { "id:$it" } ?: "text:${normalize(track.title)}|${normalize(track.artist)}"

    private fun List<GeneratedTrack>.dedupeByVideoId(): List<GeneratedTrack> {
        if (size < 2) return this
        val seen = HashSet<String>(size)
        return filter { track ->
            val id = track.youtubeVideoIdOrNull()
            id == null || seen.add(id)
        }
    }

    private fun decodeText(inputStream: InputStream): String {
        val bytes = inputStream.readBytes()
        if (bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) {
            return String(bytes, Charsets.UTF_16LE).removePrefix("\uFEFF")
        }
        if (bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()) {
            return String(bytes, Charsets.UTF_16BE).removePrefix("\uFEFF")
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val utf8String = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            null
        }
        return (utf8String ?: String(bytes, Charsets.ISO_8859_1)).removePrefix("\uFEFF")
    }

    internal fun parseTracks(text: String, filename: String): List<CsvRawTrack> {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        if (lines.isEmpty()) return emptyList()
        if (filename.endsWith(".m3u", true) || filename.endsWith(".m3u8", true) || lines.first().equals("#EXTM3U", true)) {
            return parseM3u(text.lineSequence().map(String::trim).toList())
        }
        if (filename.endsWith(".txt", true) && lines.none { '\t' in it } &&
            lines.first().split(',', ';').map(::normalize).all { it in TITLE_HEADERS || it in ARTIST_HEADERS || it in URL_HEADERS }.not()) {
            return lines.filterNot { it.startsWith('#') || it.startsWith("//") }.map(::parseTextTrack)
        }

        // Drop comment or separator directive lines at the top of CSV files (e.g. Exportify, Soundiiz, Excel)
        val cleanedLines = text.lineSequence()
            .map(String::trim)
            .filter { line ->
                line.isNotBlank() &&
                    !line.startsWith('#') &&
                    !line.startsWith("//") &&
                    !line.startsWith("sep=", ignoreCase = true)
            }
            .toList()
        if (cleanedLines.isEmpty()) return emptyList()
        val cleanedText = cleanedLines.joinToString("\n")

        val delimiter = detectDelimiter(cleanedText)
        val records = repairCollapsedRows(parseRecords(cleanedText, delimiter), delimiter)
            .filter { row -> row.isNotEmpty() && row.any(String::isNotBlank) }
        val first = records.firstOrNull() ?: return emptyList()
        val headers = first.map(::normalize)

        val dataRows = records.drop(1)
        fun isColMostlyNumeric(colIdx: Int): Boolean {
            if (dataRows.isEmpty()) return false
            val nonBlank = dataRows.mapNotNull { it.getOrNull(colIdx)?.trim() }.filter { it.isNotEmpty() }
            if (nonBlank.isEmpty()) return false
            return nonBlank.count { it.all(Char::isDigit) }.toDouble() / nonBlank.size >= 0.7
        }

        val isExplicitIndexCol = { idx: Int ->
            val h = headers.getOrNull(idx).orEmpty()
            h in INDEX_HEADERS ||
                (h == "track" && headers.any { it in PRIMARY_TITLE_HEADERS || it in SECONDARY_TITLE_HEADERS }) ||
                isColMostlyNumeric(idx)
        }

        val titleIndex = headers.indexOfFirst { it in PRIMARY_TITLE_HEADERS }.takeIf { it >= 0 }
            ?: headers.indexOfFirst { it in SECONDARY_TITLE_HEADERS }.takeIf { it >= 0 }
            ?: headers.indexOfFirst { it == "track" }.takeIf { it >= 0 && !isExplicitIndexCol(it) }
            ?: -1

        val artistIndex = headers.indexOfFirst { it in PRIMARY_ARTIST_HEADERS }.takeIf { it >= 0 }
            ?: headers.indexOfFirst { it in SECONDARY_ARTIST_HEADERS }.takeIf { it >= 0 }
            ?: -1

        val albumIndex = headers.indexOfFirst { it in ALBUM_HEADERS }
        val urlIndex = headers.indexOfFirst { it in URL_HEADERS }

        // Only a multi-column first row without purely numeric index cells is treated as a header. In a
        // single-column file that guess is indistinguishable from data.
        val hasHeader = first.size >= 2 &&
            !first.any { it.trim().all(Char::isDigit) && it.trim().isNotEmpty() } &&
            (titleIndex >= 0 || artistIndex >= 0 || albumIndex >= 0 || urlIndex >= 0)
        if (first.size == 1 && !hasHeader) return cleanedLines.map(::parseTextTrack)

        val titleColumn = if (hasHeader && titleIndex >= 0) {
            titleIndex
        } else if (hasHeader) {
            (0 until first.size).firstOrNull { it != artistIndex && it != albumIndex && it != urlIndex && !isExplicitIndexCol(it) } ?: 0
        } else {
            (0 until first.size).firstOrNull { !isExplicitIndexCol(it) } ?: 0
        }

        val artistColumn = if (hasHeader && artistIndex >= 0) {
            artistIndex
        } else if (hasHeader) {
            -1
        } else {
            (0 until first.size).firstOrNull { it != titleColumn && !isColMostlyNumeric(it) } ?: 1
        }

        return records.drop(if (hasHeader) 1 else 0).mapNotNull { row ->
            if (hasHeader && row.map(::normalize) == headers) return@mapNotNull null
            val titleCell = row.getOrNull(titleColumn).orEmpty().trim()
            val rawArtist = if (artistColumn >= 0) row.getOrNull(artistColumn).orEmpty().trim() else ""
            val rawVideoId = if (urlIndex >= 0) row.getOrNull(urlIndex)?.trim()?.let(::youtubeId) else null
            val videoId = rawVideoId ?: row.firstNotNullOfOrNull { cell ->
                val trimmed = cell.trim()
                if (trimmed.startsWith("https://", true) || trimmed.startsWith("http://", true)) {
                    youtubeId(trimmed)
                } else {
                    null
                }
            }
            val title = titleCell.takeUnless { videoId != null && youtubeId(it.trim()) == videoId }
            if (title.isNullOrBlank() && videoId == null) return@mapNotNull null

            var finalTitle = title.orEmpty()
            var finalArtist = rawArtist
            if (finalArtist.isBlank() && finalTitle.isNotBlank() && videoId == null) {
                val parsed = parseTextTrack(finalTitle)
                if (parsed.title.isNotBlank() && parsed.artist.isNotBlank()) {
                    finalTitle = parsed.title
                    finalArtist = parsed.artist
                }
            }

            CsvRawTrack(finalTitle, finalArtist, row.getOrNull(albumIndex)?.trim()?.takeIf(String::isNotBlank), videoId)
        }
    }

    /**
     * Splits rows that a stray unclosed quote collapsed together.
     *
     * An unclosed quote swallows every following line into a single field, so a
     * 300-song file can come back as one row. The two causes are told apart by
     * the shape of the swallowed lines: a genuinely multi-line quoted field
     * (quoted lyrics, a wrapped title) continues with fragments that are not
     * rows in their own right, while an unclosed quote continues with lines
     * that each carry the same field count as a normal row. Only the second
     * shape is split, so real multi-line fields survive untouched.
     */
    private fun repairCollapsedRows(records: List<List<String>>, delimiter: Char): List<List<String>> {
        val modal = records.groupingBy { it.size }.eachCount().maxByOrNull { it.value }?.key ?: return records
        if (modal < 2) return records
        val repaired = ArrayList<List<String>>(records.size)
        for (record in records) {
            val collapsed = record.indexOfFirst { '\n' in it }
            if (collapsed < 0) {
                repaired += record
                continue
            }
            val lines = record[collapsed].split('\n')
            val swallowed = lines.drop(1)
            val looksLikeUnclosedQuote = swallowed.isNotEmpty() && swallowed.all { line ->
                cellsOf(line, delimiter).size == modal
            }
            if (!looksLikeUnclosedQuote) {
                repaired += record
                continue
            }
            repaired += record.take(collapsed) + cellsOf(lines.first(), delimiter)
            swallowed.forEach { line -> repaired += cellsOf(line, delimiter) }
        }
        return repaired
    }

    private fun cellsOf(line: String, delimiter: Char): List<String> =
        parseRecords(line, delimiter).firstOrNull() ?: listOf(line.trim())

    /**
     * Picks the delimiter that actually splits the file into consistent
     * multi-column rows. Counting delimiters alone is not enough: a comma
     * hiding inside a tab-separated artist field ties with the real delimiter,
     * and the tie used to be handed to the comma parser, shifting every column
     * and importing a differently-named song for every row.
     */
    private fun detectDelimiter(text: String): Char {
        var best = DELIMITER_CANDIDATES.first()
        var bestScore = Int.MIN_VALUE
        for (candidate in DELIMITER_CANDIDATES) {
            val rows = parseRecords(text, candidate).take(DELIMITER_SAMPLE_ROWS)
            if (rows.isEmpty()) continue
            val modal = rows.groupingBy { it.size }.eachCount()
                .filterKeys { it >= 2 }
                .maxByOrNull { it.value }
                ?: continue
            val score = modal.value * DELIMITER_CONSISTENCY_WEIGHT +
                rows.sumOf { (it.size - 1).coerceAtLeast(0) }
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }

    private fun parseTextTrack(line: String): CsvRawTrack {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return CsvRawTrack("", "")
        val videoId = youtubeId(trimmed)
        if (videoId != null) {
            return CsvRawTrack("", "", videoId = videoId)
        }
        // Strip leading track numbering: "1. ", "01. ", "1) ", "[1] ", "1 - "
        val cleaned = trimmed.replace(Regex("^\\s*(?:\\[?\\d+[.)\\]]|\\d+\\s*[-–—])\\s*"), "").trim()
        if (cleaned.isBlank()) return CsvRawTrack("", "")

        // Check for " by " separator
        val byMatch = Regex("(?i)\\s+by\\s+").find(cleaned)
        if (byMatch != null) {
            val title = cleaned.substring(0, byMatch.range.first).trim()
            val artist = cleaned.substring(byMatch.range.last + 1).trim()
            if (title.isNotBlank()) return CsvRawTrack(title = title, artist = artist)
        }

        val separator = Regex("\\s+[-–—|:]\\s+").find(cleaned)
            ?: return CsvRawTrack(cleaned, "")
        return CsvRawTrack(
            title = cleaned.substring(separator.range.last + 1).trim(),
            artist = cleaned.substring(0, separator.range.first).trim(),
        )
    }

    private fun parseM3u(lines: List<String>): List<CsvRawTrack> {
        val tracks = mutableListOf<CsvRawTrack>()
        var pending: CsvRawTrack? = null
        for (line in lines) {
            when {
                line.startsWith("#EXTINF:", true) -> {
                    pending?.let { tracks += it }
                    val info = line.substringAfter(':')
                    val artist = Regex("""artist="([^"]+)""", RegexOption.IGNORE_CASE).find(info)?.groupValues?.get(1)
                    val title = Regex("""title="([^"]+)""", RegexOption.IGNORE_CASE).find(info)?.groupValues?.get(1)
                    pending = if (artist != null && title != null) CsvRawTrack(title, artist)
                    else parseTextTrack(info.substringAfter(',', ""))
                }
                line.isBlank() -> Unit
                line.startsWith('#') -> Unit
                else -> {
                    val videoId = youtubeId(line)
                    val track = pending ?: if (videoId != null) CsvRawTrack("", "", videoId = videoId)
                    else parseTextTrack(line.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.'))
                    tracks += track.copy(videoId = videoId)
                    pending = null
                }
            }
        }
        pending?.let { tracks += it }
        return tracks
    }

    private fun youtubeId(value: String): String? {
        if (!VIDEO_ID.matches(value) && !value.startsWith("https://", true) && !value.startsWith("http://", true)) return null
        return GeneratedTrack(name = "", artist = "", artworkUrl = null, url = value).youtubeVideoIdOrNull()
    }

    private fun parseRecords(text: String, delimiter: Char): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        fun finishField() {
            row += field.toString().trim()
            field.setLength(0)
        }
        fun finishRow() {
            finishField()
            if (row.any(String::isNotBlank)) records += row.toList()
            row.clear()
        }
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' && quoted && text.getOrNull(index + 1) == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' && (quoted || field.isBlank()) -> quoted = !quoted
                char == delimiter && !quoted -> finishField()
                (char == '\n' || char == '\r') && !quoted -> {
                    finishRow()
                    if (char == '\r' && text.getOrNull(index + 1) == '\n') index++
                }
                else -> field.append(char)
            }
            index++
        }
        // A hand-edited or truncated file can leave a quote open. Treating end
        // of input as the closing quote keeps every row before it importable
        // instead of discarding the whole playlist.
        if (quoted || field.isNotEmpty() || row.isNotEmpty()) finishRow()
        return records
    }

    private companion object {
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

        /** InnerTube rate-limits bursts, so keep fewer rows in flight than a
         *  plain map/awaitAll would and let the retry pass cover the fallout. */
        const val SEARCH_CONCURRENCY = 4
        const val SEARCH_ATTEMPTS = 3
        const val SEARCH_RETRY_BACKOFF_MS = 400L
        const val SEARCH_RESULT_LIMIT = 30
        const val LINK_ATTEMPTS = 3

        val SAFE_VIDEO_LABEL_REGEX = Regex(
            "(?i)\\s*[\\[(](?:official\\s+)?(?:music\\s+video|lyric\\s+video|audio\\s+video|visualizer|audio|video|track|hd|hq|4k)\\b[^\\])]*[\\])]",
        )
        val SAFE_BRACKET_LABEL_REGEX = Regex(
            "(?i)\\s*[\\[(](?:official|full\\s+audio|audio\\s+track|official\\s+track)[\\])]",
        )
        val FEATURE_REGEX = Regex(
            "(?i)\\s*[\\[(](?:feat\\.?|ft\\.?|featuring|with)\\s+[^\\])]*[\\])]",
        )

        /** Trailing "..." noise stripped from retry queries only. */
        val NOISE_SUFFIX = Regex(
            "\\s*[\\[(](?:official\\s*(?:music\\s*video|lyric\\s*video|audio|video)|music\\s*video|lyric\\s*video|visualizer|audio|video|hd|hq|4k|" +
                "remaster(?:ed)?|live|deluxe|explicit)\\b[^\\])]*[\\])]\\s*$",
            RegexOption.IGNORE_CASE,
        )

        val DELIMITER_CANDIDATES = listOf(',', ';', '\t', '|')
        const val DELIMITER_SAMPLE_ROWS = 8
        /** Outweighs the raw delimiter count so a consistent split always beats
         *  a delimiter that merely appears more often. */
        const val DELIMITER_CONSISTENCY_WEIGHT = 1000

        val UNKNOWN_ARTIST_LABELS = setOf("unknown", "unknown artist")

        val INDEX_HEADERS = setOf(
            "index", "#", "no", "no.", "number", "num", "track number", "track no",
            "track num", "track count", "track index", "track id", "position", "pos", "order",
            "disc", "disc number", "disc no", "disc num", "disc count", "side",
            "duration", "duration ms", "duration_ms", "time", "length", "bpm", "year", "date",
            "genre", "genres", "isrc", "key", "popularity", "bitrate", "added at", "added by",
        )
        val PRIMARY_TITLE_HEADERS = setOf(
            "track name", "trackname", "song title", "songtitle", "track title", "tracktitle",
            "song name", "songname", "title", "titles", "video title", "item name", "audio title",
            "titre", "titres", "titulo", "titulos", "cancion", "canciones", "lied", "lieder", "titel",
        )
        val SECONDARY_TITLE_HEADERS = setOf(
            "song", "songs", "name", "music", "item", "headline",
        )
        val TITLE_HEADERS = PRIMARY_TITLE_HEADERS + SECONDARY_TITLE_HEADERS + setOf("track", "tracks")

        val PRIMARY_ARTIST_HEADERS = setOf(
            "artist name s", "artist names", "artist name", "artistname", "track artist",
            "track artists", "lead artist", "primary artist", "main artist", "channel title",
            "channel name", "track artist name", "artiste", "artistes", "artista", "artistas",
            "interpret", "interpreten", "kuenstler", "künstler",
        )
        val SECONDARY_ARTIST_HEADERS = setOf(
            "artist s", "artist", "artists", "channel", "uploader", "author", "creator",
            "performer", "performers", "singer", "singers", "band", "by",
        )
        val ARTIST_HEADERS = PRIMARY_ARTIST_HEADERS + SECONDARY_ARTIST_HEADERS

        val ALBUM_HEADERS = setOf(
            "album name", "albumname", "album", "albums", "release", "collection", "record",
            "album title", "album name s", "record title", "album_name", "album_title",
        )
        val URL_HEADERS = setOf(
            "url", "uri", "track url", "track uri", "youtube url", "video id", "videoid",
            "link", "track link", "spotify uri", "spotify url", "youtube link", "video url",
            "spotify id", "spotify track id", "id", "yt url", "youtube", "soundcloud url",
        )
    }
}
