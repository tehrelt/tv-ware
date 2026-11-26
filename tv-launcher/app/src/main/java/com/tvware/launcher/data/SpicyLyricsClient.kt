package com.tvware.launcher.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class SpicyLyricsClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class CacheEntry(
        val state: LyricsState,
        val expiresAtMs: Long,
    )

    private sealed interface HttpResult {
        data class Ok(val body: String) : HttpResult
        data object NotFound : HttpResult
        data object Unauthorized : HttpResult
        data object RateLimited : HttpResult
        data object Failed : HttpResult
    }

    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    @Volatile
    private var rateLimitedUntilMs: Long = 0L

    suspend fun fetchLyrics(
        apiKey: String,
        mediaId: String?,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
    ): LyricsState = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isEmpty()) return@withContext LyricsState.Idle
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty() && mediaId.isNullOrBlank()) return@withContext LyricsState.NotFound

        val cacheKey = buildCacheKey(key, mediaId, cleanTitle, artist.trim(), album.trim())
        val now = nowMs()
        synchronized(cacheLock) {
            val cached = cache[cacheKey]
            if (cached != null && cached.expiresAtMs > now) {
                return@withContext cached.state
            }
        }

        if (now < rateLimitedUntilMs) {
            return@withContext LyricsState.Error("Превышен лимит запросов Spicy Lyrics")
        }

        val directId = extractDirectTrackId(mediaId)
        if (directId != null) {
            when (val direct = requestLyricsById(key, directId)) {
                is HttpResult.Ok -> {
                    val parsed = parseSpicyLyricsResponse(direct.body, directId)
                    if (parsed != null) {
                        return@withContext cacheAndReturn(cacheKey, LyricsState.Ready(parsed), POSITIVE_TTL_MS)
                    }
                }
                HttpResult.Unauthorized -> {
                    return@withContext cacheAndReturn(
                        cacheKey,
                        LyricsState.Error("Неверный API-ключ Spicy Lyrics"),
                        NEGATIVE_TTL_MS,
                    )
                }
                HttpResult.RateLimited -> {
                    rateLimitedUntilMs = nowMs() + RATE_LIMIT_COOLDOWN_MS
                    return@withContext LyricsState.Error("Превышен лимит запросов Spicy Lyrics")
                }
                HttpResult.NotFound, HttpResult.Failed -> Unit
            }
        }

        if (cleanTitle.isEmpty()) {
            return@withContext cacheAndReturn(cacheKey, LyricsState.NotFound, NEGATIVE_TTL_MS)
        }

        val searchedId = when (val search = searchTrackId(key, cleanTitle, artist.trim(), album.trim(), durationMs)) {
            is HttpResult.Ok -> parseTrackSearchResponse(search.body)
            HttpResult.Unauthorized -> {
                return@withContext cacheAndReturn(
                    cacheKey,
                    LyricsState.Error("Неверный API-ключ Spicy Lyrics"),
                    NEGATIVE_TTL_MS,
                )
            }
            HttpResult.RateLimited -> {
                rateLimitedUntilMs = nowMs() + RATE_LIMIT_COOLDOWN_MS
                return@withContext LyricsState.Error("Превышен лимит запросов Spicy Lyrics")
            }
            HttpResult.NotFound, HttpResult.Failed -> null
        } ?: return@withContext cacheAndReturn(cacheKey, LyricsState.NotFound, NEGATIVE_TTL_MS)

        when (val lyrics = requestLyricsById(key, searchedId)) {
            is HttpResult.Ok -> {
                val parsed = parseSpicyLyricsResponse(lyrics.body, searchedId)
                val state = if (parsed != null) LyricsState.Ready(parsed) else LyricsState.NotFound
                val ttl = if (state is LyricsState.Ready) POSITIVE_TTL_MS else NEGATIVE_TTL_MS
                cacheAndReturn(cacheKey, state, ttl)
            }
            HttpResult.Unauthorized -> cacheAndReturn(
                cacheKey,
                LyricsState.Error("Неверный API-ключ Spicy Lyrics"),
                NEGATIVE_TTL_MS,
            )
            HttpResult.RateLimited -> {
                rateLimitedUntilMs = nowMs() + RATE_LIMIT_COOLDOWN_MS
                LyricsState.Error("Превышен лимит запросов Spicy Lyrics")
            }
            HttpResult.NotFound -> cacheAndReturn(cacheKey, LyricsState.NotFound, NEGATIVE_TTL_MS)
            HttpResult.Failed -> LyricsState.Error("Не удалось загрузить текст")
        }
    }

    private fun cacheAndReturn(cacheKey: String, state: LyricsState, ttlMs: Long): LyricsState {
        synchronized(cacheLock) {
            cache[cacheKey] = CacheEntry(state, nowMs() + ttlMs)
        }
        return state
    }

    private fun requestLyricsById(apiKey: String, trackId: String): HttpResult {
        val encodedId = URLEncoder.encode(trackId, StandardCharsets.UTF_8.name()).replace("+", "%20")
        return executeGet("${baseUrl.trimEnd('/')}/v1/lyrics/$encodedId", apiKey)
    }

    private fun searchTrackId(
        apiKey: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
    ): HttpResult {
        val params = buildList {
            add("title=${encodeQuery(title)}")
            if (artist.isNotEmpty()) add("artist=${encodeQuery(artist)}")
            if (album.isNotEmpty()) add("album=${encodeQuery(album)}")
            if (durationMs > 0L) add("duration=${durationMs / 1000L}")
        }.joinToString("&")
        return executeGet("${baseUrl.trimEnd('/')}/v1/tracks/search?$params", apiKey)
    }

    private fun executeGet(rawUrl: String, apiKey: String): HttpResult {
        val conn = runCatching { URL(rawUrl).openConnection() as HttpURLConnection }.getOrNull()
            ?: return HttpResult.Failed
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("User-Agent", "tv-launcher/1.0")

            when (val code = conn.responseCode) {
                in 200..299 -> {
                    val body = conn.inputStream.use(::readBoundedUtf8) ?: return HttpResult.Failed
                    HttpResult.Ok(body)
                }
                400, 404 -> HttpResult.NotFound
                401, 403 -> HttpResult.Unauthorized
                429 -> HttpResult.RateLimited
                else -> HttpResult.Failed
            }
        } catch (_: Exception) {
            HttpResult.Failed
        } finally {
            conn.disconnect()
        }
    }

    private fun readBoundedUtf8(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buf)
            if (read < 0) break
            total += read
            if (total > MAX_RESPONSE_BYTES) return null
            out.write(buf, 0, read)
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun buildCacheKey(
        apiKey: String,
        mediaId: String?,
        title: String,
        artist: String,
        album: String,
    ): String = "${apiKey.hashCode()}|${mediaId.orEmpty()}|${title.lowercase()}|${artist.lowercase()}|${album.lowercase()}"

    companion object {
        const val DEFAULT_BASE_URL = "https://api.spicylyrics.org"
        private const val TIMEOUT_MS = 6_000
        private const val MAX_RESPONSE_BYTES = 1024 * 1024
        private const val MAX_CACHE_ENTRIES = 64
        private const val POSITIVE_TTL_MS = 30 * 60_000L
        private const val NEGATIVE_TTL_MS = 5 * 60_000L
        private const val RATE_LIMIT_COOLDOWN_MS = 5 * 60_000L
    }
}
