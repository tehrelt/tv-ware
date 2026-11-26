package com.tvware.launcher.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class SpicyLyricsTest {

    @Test
    fun extractDirectTrackIdRecognizesSpotifyIsrcAndAppleIds() {
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", extractDirectTrackId("spotify:track:4uLU6hMCjMI75M1A2tKUQC"))
        assertEquals(
            "4uLU6hMCjMI75M1A2tKUQC",
            extractDirectTrackId("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC?si=12345"),
        )
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", extractDirectTrackId("4uLU6hMCjMI75M1A2tKUQC"))
        assertEquals("USAAA0000001", extractDirectTrackId("us-aaa-00-00001"))
        assertEquals("1440857781", extractDirectTrackId("1440857781"))
        assertNull(extractDirectTrackId("Midnight City"))
        assertNull(extractDirectTrackId("   "))
        assertNull(extractDirectTrackId(null))
    }

    @Test
    fun parseSpicyLyricsResponseParsesSyllableLineAndStatic() {
        val syllableJson = """
            {
              "Status": 200,
              "Body": {
                "id": "track123",
                "Type": "Syllable",
                "source": "spicy_lyrics",
                "UploadAttribution": {
                  "Uploader": { "username": "alice" }
                },
                "Content": [
                  {
                    "Type": "Vocal",
                    "OppositeAligned": true,
                    "Lead": {
                      "StartTime": 1.25,
                      "EndTime": 3.5,
                      "Syllables": [
                        { "Text": "Hel", "IsPartOfWord": true, "StartTime": 1.25, "EndTime": 1.6 },
                        { "Text": "lo", "IsPartOfWord": false, "StartTime": 1.6, "EndTime": 2.1 },
                        { "Text": "world", "IsPartOfWord": false, "StartTime": 2.2, "EndTime": 3.5 }
                      ]
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val syllable = parseSpicyLyricsResponse(syllableJson, "fallback")
        assertNotNull(syllable)
        assertEquals("track123", syllable!!.trackId)
        assertEquals(LyricsSyncType.SYLLABLE, syllable.syncType)
        assertEquals(1, syllable.lines.size)
        assertEquals("Hello world", syllable.lines[0].text)
        assertEquals(1250L, syllable.lines[0].startMs)
        assertEquals(3500L, syllable.lines[0].endMs)
        assertTrue(syllable.lines[0].isOppositeAligned)
        assertEquals(3, syllable.lines[0].syllables.size)
        assertEquals("Spicy Lyrics · @alice", syllable.attribution)

        val lineJson = """
            {
              "Status": 200,
              "Body": {
                "id": "track456",
                "Type": "Line",
                "source": "apple_music",
                "Content": [
                  { "Text": "First line", "StartTime": 2.0, "EndTime": 5.0 },
                  { "Text": "Second line", "StartTime": 5.5, "EndTime": 9.0 }
                ]
              }
            }
        """.trimIndent()

        val line = parseSpicyLyricsResponse(lineJson, "fallback")
        assertNotNull(line)
        assertEquals(LyricsSyncType.LINE, line!!.syncType)
        assertEquals(2, line.lines.size)
        assertEquals(2000L, line.lines[0].startMs)
        assertEquals("Apple Music", line.attribution)

        val staticJson = """
            {
              "Status": 200,
              "Body": {
                "id": "track789",
                "Type": "Static",
                "Content": [
                  { "Text": "Untimed line" }
                ]
              }
            }
        """.trimIndent()

        val staticData = parseSpicyLyricsResponse(staticJson, "fallback")
        assertNotNull(staticData)
        assertEquals(LyricsSyncType.STATIC, staticData!!.syncType)
        assertEquals("Untimed line", staticData.lines[0].text)
    }

    @Test
    fun findActiveLyricLineIndexTracksPositionAndStaticMode() {
        val lines = listOf(
            LyricLine("Line 1", startMs = 1000L, endMs = 3000L),
            LyricLine("Line 2", startMs = 4000L, endMs = 7000L),
        )
        assertEquals(-1, findActiveLyricLineIndex(lines, 500L, LyricsSyncType.LINE))
        assertEquals(0, findActiveLyricLineIndex(lines, 1500L, LyricsSyncType.LINE))
        assertEquals(0, findActiveLyricLineIndex(lines, 3500L, LyricsSyncType.LINE))
        assertEquals(1, findActiveLyricLineIndex(lines, 4000L, LyricsSyncType.LINE))
        assertEquals(-1, findActiveLyricLineIndex(lines, 4000L, LyricsSyncType.STATIC))
    }

    @Test
    fun clientSearchesByTitleArtistCachesResultAndHandlesAuthError() = runBlocking {
        val requests = AtomicInteger(0)
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { client ->
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1))
                    val requestLine = reader.readLine() ?: return@use
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val idx = line.indexOf(':')
                        if (idx > 0) {
                            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                        }
                    }
                    requests.incrementAndGet()
                    val path = requestLine.split(' ').getOrNull(1).orEmpty()
                    val auth = headers["authorization"]
                    val (status, body) = when {
                        path.startsWith("/v1/tracks/search") -> {
                            assertEquals("Bearer sl_test_key", auth)
                            200 to """{"Status":200,"Body":{"results":[{"id":"4uLU6hMCjMI75M1A2tKUQC"}]}}"""
                        }
                        path == "/v1/lyrics/4uLU6hMCjMI75M1A2tKUQC" && auth == "Bearer bad_key" -> {
                            401 to ""
                        }
                        path == "/v1/lyrics/4uLU6hMCjMI75M1A2tKUQC" -> {
                            200 to """{"Status":200,"Body":{"id":"4uLU6hMCjMI75M1A2tKUQC","Type":"Line","Content":[{"Text":"Never gonna give you up","StartTime":1.0,"EndTime":4.0}]}}"""
                        }
                        else -> 404 to ""
                    }
                    val bytes = body.toByteArray(StandardCharsets.UTF_8)
                    val respHeader = "HTTP/1.1 $status OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    client.getOutputStream().apply {
                        write(respHeader.toByteArray(StandardCharsets.ISO_8859_1))
                        if (bytes.isNotEmpty()) write(bytes)
                        flush()
                    }
                }
            }
        }
        try {
            val client = SpicyLyricsClient(baseUrl = "http://127.0.0.1:${server.localPort}")
            val first = client.fetchLyrics(
                apiKey = "sl_test_key",
                mediaId = null,
                title = "Never Gonna Give You Up",
                artist = "Rick Astley",
                album = "",
                durationMs = 213_000L,
            )
            assertTrue(first is LyricsState.Ready)
            assertEquals(2, requests.get())

            val cached = client.fetchLyrics(
                apiKey = "sl_test_key",
                mediaId = null,
                title = "Never Gonna Give You Up",
                artist = "Rick Astley",
                album = "",
                durationMs = 213_000L,
            )
            assertTrue(cached is LyricsState.Ready)
            assertEquals(2, requests.get())

            val unauthorized = client.fetchLyrics(
                apiKey = "bad_key",
                mediaId = "spotify:track:4uLU6hMCjMI75M1A2tKUQC",
                title = "Never Gonna Give You Up",
                artist = "Rick Astley",
                album = "",
                durationMs = 213_000L,
            )
            assertTrue(unauthorized is LyricsState.Error)
        } finally {
            runCatching { server.close() }
            worker.join(1_000)
        }
    }
}
