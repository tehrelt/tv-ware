package com.tvware.launcher.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToLong

enum class LyricsSyncType {
    SYLLABLE,
    LINE,
    STATIC,
}

@Immutable
data class LyricSyllable(
    val text: String,
    val isPartOfWord: Boolean,
    val startMs: Long,
    val endMs: Long,
)

@Immutable
data class LyricLine(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val syllables: List<LyricSyllable> = emptyList(),
    val isOppositeAligned: Boolean = false,
)

@Immutable
data class LyricsData(
    val trackId: String,
    val syncType: LyricsSyncType,
    val lines: List<LyricLine>,
    val source: String? = null,
    val attribution: String? = null,
)

@Immutable
sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    data class Ready(val data: LyricsData) : LyricsState
    data object NotFound : LyricsState
    data class Error(val message: String) : LyricsState
}

private val SPOTIFY_URI_REGEX = Regex("""spotify:track:([A-Za-z0-9]{22})""")
private val SPOTIFY_URL_REGEX = Regex("""open\.spotify\.com/track/([A-Za-z0-9]{22})""")
private val SPOTIFY_ID_REGEX = Regex("""^[A-Za-z0-9]{22}$""")
private val ISRC_REGEX = Regex("""^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$""")
private val APPLE_ID_REGEX = Regex("""^[0-9]{6,15}$""")

internal fun extractDirectTrackId(mediaId: String?): String? {
    val raw = mediaId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    SPOTIFY_URI_REGEX.find(raw)?.groupValues?.getOrNull(1)?.let { return it }
    SPOTIFY_URL_REGEX.find(raw)?.groupValues?.getOrNull(1)?.let { return it }
    if (SPOTIFY_ID_REGEX.matches(raw)) return raw
    if (APPLE_ID_REGEX.matches(raw)) return raw
    val normalizedIsrc = raw.filter { it.isLetterOrDigit() }.uppercase(Locale.ROOT)
    if (ISRC_REGEX.matches(normalizedIsrc)) return normalizedIsrc
    return null
}

internal fun parseTrackSearchResponse(json: String): String? {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
    if (root.has("Status") && root.optInt("Status", 200) != 200) return null
    val body = root.optJSONObject("Body") ?: root
    val results = body.optJSONArray("results") ?: return null
    for (i in 0 until results.length()) {
        val item = results.optJSONObject(i) ?: continue
        val id = item.optNonBlank("id")
        if (id != null) return id
    }
    return null
}

internal fun parseSpicyLyricsResponse(json: String, fallbackTrackId: String): LyricsData? {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
    if (root.has("Status") && root.optInt("Status", 200) != 200) return null
    val body = root.optJSONObject("Body") ?: return null
    val trackId = body.optNonBlank("id") ?: fallbackTrackId
    val source = body.optNonBlank("source")
    val attribution = buildAttribution(body, source)
    val content = body.optJSONArray("Content") ?: return null

    return when (body.optNonBlank("Type")) {
        "Syllable" -> {
            val lines = parseSyllableLines(content)
            if (lines.isEmpty()) null else LyricsData(trackId, LyricsSyncType.SYLLABLE, lines, source, attribution)
        }
        "Line" -> {
            val lines = parseTimedLines(content)
            if (lines.isEmpty()) null else LyricsData(trackId, LyricsSyncType.LINE, lines, source, attribution)
        }
        "Static" -> {
            val lines = parseStaticLines(content)
            if (lines.isEmpty()) null else LyricsData(trackId, LyricsSyncType.STATIC, lines, source, attribution)
        }
        else -> null
    }
}

private fun parseSyllableLines(content: JSONArray): List<LyricLine> = buildList {
    for (i in 0 until content.length()) {
        val item = content.optJSONObject(i) ?: continue
        if (item.optNonBlank("Type") != "Vocal") continue
        val lead = item.optJSONObject("Lead") ?: continue
        val rawSyllables = lead.optJSONArray("Syllables") ?: continue
        val syllables = buildList {
            for (j in 0 until rawSyllables.length()) {
                val s = rawSyllables.optJSONObject(j) ?: continue
                val text = s.optNonBlank("Text")?.trim() ?: continue
                val startMs = secondsToMs(s.optDouble("StartTime", 0.0))
                val endMs = secondsToMs(s.optDouble("EndTime", startMs / 1000.0)).coerceAtLeast(startMs)
                add(
                    LyricSyllable(
                        text = text,
                        isPartOfWord = s.optBoolean("IsPartOfWord", false),
                        startMs = startMs,
                        endMs = endMs,
                    ),
                )
            }
        }
        if (syllables.isEmpty()) continue
        val lineText = buildString {
            syllables.forEachIndexed { idx, syl ->
                append(syl.text)
                if (!syl.isPartOfWord && idx < syllables.lastIndex) append(' ')
            }
        }.trim()
        if (lineText.isEmpty()) continue
        val startMs = secondsToMs(lead.optDouble("StartTime", syllables.first().startMs / 1000.0))
        val endMs = secondsToMs(lead.optDouble("EndTime", syllables.last().endMs / 1000.0)).coerceAtLeast(startMs)
        add(
            LyricLine(
                text = lineText,
                startMs = startMs,
                endMs = endMs,
                syllables = syllables,
                isOppositeAligned = item.optBoolean("OppositeAligned", false),
            ),
        )
    }
}

private fun parseTimedLines(content: JSONArray): List<LyricLine> = buildList {
    for (i in 0 until content.length()) {
        val item = content.optJSONObject(i) ?: continue
        val text = item.optNonBlank("Text")?.trim() ?: continue
        val startMs = secondsToMs(item.optDouble("StartTime", 0.0))
        val endMs = secondsToMs(item.optDouble("EndTime", startMs / 1000.0)).coerceAtLeast(startMs)
        add(LyricLine(text = text, startMs = startMs, endMs = endMs))
    }
}

private fun parseStaticLines(content: JSONArray): List<LyricLine> = buildList {
    for (i in 0 until content.length()) {
        val text = when (val raw = content.opt(i)) {
            is JSONObject -> raw.optNonBlank("Text")?.trim()
            is String -> raw.trim().takeIf { it.isNotEmpty() }
            else -> null
        } ?: continue
        add(LyricLine(text = text, startMs = 0L, endMs = 0L))
    }
}

private fun buildAttribution(body: JSONObject, source: String?): String? {
    val attr = body.optJSONObject("UploadAttribution")
    val uploader = attr?.optJSONObject("Uploader")?.optNonBlank("username")
    val maker = attr?.optJSONObject("Maker")?.optNonBlank("username")
    val creditUser = uploader ?: maker
    return when {
        source == "spicy_lyrics" && creditUser != null -> "Spicy Lyrics · @$creditUser"
        source == "spicy_lyrics" -> "Spicy Lyrics"
        source == "apple_music" -> "Apple Music"
        source == "spotify" -> "Spotify"
        source != null -> source.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.ROOT) }
        else -> null
    }
}

internal fun findActiveLyricLineIndex(
    lines: List<LyricLine>,
    positionMs: Long,
    syncType: LyricsSyncType,
): Int {
    if (lines.isEmpty() || syncType == LyricsSyncType.STATIC) return -1
    if (positionMs < lines.first().startMs) return -1
    var lastStarted = -1
    for (i in lines.indices) {
        val line = lines[i]
        if (positionMs >= line.startMs) {
            lastStarted = i
            if (positionMs <= line.endMs) return i
        } else {
            break
        }
    }
    return lastStarted
}

private fun secondsToMs(seconds: Double): Long =
    if (seconds.isNaN() || seconds.isInfinite()) 0L else (seconds * 1000.0).roundToLong().coerceAtLeast(0L)

private fun JSONObject.optNonBlank(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }
