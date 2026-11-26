package com.tvware.launcher.web

import com.tvware.launcher.data.BackgroundMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class WebSettingsServerTest {

    @Test
    fun serverEnforcesTokenAndHandlesSettingsAndBackground() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var state = WebSettingsSnapshot(
            backgroundMode = BackgroundMode.GRADIENT,
            hasCustomBackground = false,
            gridColumns = 5,
            mediaScreenEnabled = true,
            mediaAccessGranted = true,
            lyricsEnabled = true,
            hasSpicyLyricsKey = false,
            spicyLyricsKeyHint = "",
            autoStartHapp = true,
            hiddenApps = listOf(WebAppItem("com.hidden.app", "Hidden App", isPinned = false)),
            visibleApps = listOf(WebAppItem("com.youtube.tv", "YouTube", isPinned = true)),
        )
        var uploadedBytes: ByteArray? = null
        var clearedBg = false
        var savedApiKey: String? = null

        val server = WebSettingsServer(
            scope = scope,
            preferredPort = 0,
            hostAddressProvider = { "127.0.0.1" },
            handler = object : WebSettingsHandler {
                override suspend fun getSnapshot(): WebSettingsSnapshot = state

                override suspend fun updateSettings(patch: WebSettingsPatch): WebSettingsSnapshot {
                    patch.backgroundMode?.let { state = state.copy(backgroundMode = it) }
                    patch.gridColumns?.let { state = state.copy(gridColumns = it) }
                    patch.mediaScreenEnabled?.let { state = state.copy(mediaScreenEnabled = it) }
                    patch.lyricsEnabled?.let { state = state.copy(lyricsEnabled = it) }
                    patch.autoStartHapp?.let { state = state.copy(autoStartHapp = it) }
                    patch.spicyLyricsApiKey?.let {
                        savedApiKey = it
                        state = state.copy(hasSpicyLyricsKey = it.isNotBlank(), spicyLyricsKeyHint = "••••${it.takeLast(4)}")
                    }
                    if (patch.clearSpicyLyricsKey) {
                        savedApiKey = ""
                        state = state.copy(hasSpicyLyricsKey = false, spicyLyricsKeyHint = "")
                    }
                    patch.unhidePackage?.let { pkg ->
                        val item = state.hiddenApps.firstOrNull { it.packageName == pkg }
                        if (item != null) {
                            state = state.copy(
                                hiddenApps = state.hiddenApps.filterNot { it.packageName == pkg },
                                visibleApps = state.visibleApps + item,
                            )
                        }
                    }
                    return state
                }

                override suspend fun saveCustomBackground(bytes: ByteArray): Boolean {
                    uploadedBytes = bytes
                    state = state.copy(backgroundMode = BackgroundMode.CUSTOM_FILE, hasCustomBackground = true)
                    return true
                }

                override suspend fun clearCustomBackground() {
                    clearedBg = true
                    state = state.copy(backgroundMode = BackgroundMode.GRADIENT, hasCustomBackground = false)
                }
            },
        )

        try {
            val info = server.start()
            assertNotNull(info)
            val baseUrl = "http://127.0.0.1:${info!!.port}"

            val unauth = (URL("$baseUrl/api/state").openConnection() as HttpURLConnection)
            assertEquals(401, unauth.responseCode)
            unauth.disconnect()

            val htmlConn = (URL("$baseUrl/?t=${info.token}").openConnection() as HttpURLConnection)
            assertEquals(200, htmlConn.responseCode)
            val html = htmlConn.inputStream.bufferedReader().readText()
            assertTrue(html.contains("TV Launcher"))
            assertTrue(html.contains("Spicy Lyrics"))
            htmlConn.disconnect()

            val postConn = (URL("$baseUrl/api/settings").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("X-Auth-Token", info.token)
                setRequestProperty("Content-Type", "application/json")
            }
            postConn.outputStream.use {
                it.write(
                    """{"gridColumns":6,"spicyLyricsApiKey":"sl_sk_secret_9876","unhidePackage":"com.hidden.app"}"""
                        .toByteArray(),
                )
            }
            assertEquals(200, postConn.responseCode)
            val updatedJson = JSONObject(postConn.inputStream.bufferedReader().readText())
            assertEquals(6, updatedJson.getInt("gridColumns"))
            assertTrue(updatedJson.getBoolean("hasSpicyLyricsKey"))
            assertEquals("••••9876", updatedJson.getString("spicyLyricsKeyHint"))
            assertFalse(updatedJson.toString().contains("sl_sk_secret_9876"))
            assertEquals("sl_sk_secret_9876", savedApiKey)
            assertEquals(0, updatedJson.getJSONArray("hiddenApps").length())
            postConn.disconnect()

            val uploadConn = (URL("$baseUrl/api/background").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("X-Auth-Token", info.token)
                setRequestProperty("Content-Type", "image/png")
            }
            val sampleBytes = byteArrayOf(1, 2, 3, 4, 5)
            uploadConn.outputStream.use { it.write(sampleBytes) }
            assertEquals(200, uploadConn.responseCode)
            assertArrayEquals(sampleBytes, uploadedBytes)
            uploadConn.disconnect()

            val deleteConn = (URL("$baseUrl/api/background").openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                setRequestProperty("X-Auth-Token", info.token)
            }
            assertEquals(200, deleteConn.responseCode)
            assertTrue(clearedBg)
            deleteConn.disconnect()
        } finally {
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun qrMatrixEncodesUrlIntoSquareGrid() {
        val matrix = encodeQrMatrix("http://192.168.1.42:8765/?t=abc123", sizePx = 128)
        assertNotNull(matrix)
        assertEquals(128, matrix!!.width)
        assertEquals(128, matrix.height)
        var darkCount = 0
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) darkCount++
            }
        }
        assertTrue(darkCount in 100 until (128 * 128))
    }
}
