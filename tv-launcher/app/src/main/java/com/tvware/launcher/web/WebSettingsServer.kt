package com.tvware.launcher.web

import com.tvware.launcher.data.BackgroundMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

data class WebAppItem(
    val packageName: String,
    val label: String,
    val isPinned: Boolean,
)

data class WebSettingsSnapshot(
    val backgroundMode: BackgroundMode,
    val hasCustomBackground: Boolean,
    val gridColumns: Int,
    val mediaScreenEnabled: Boolean,
    val mediaAccessGranted: Boolean,
    val lyricsEnabled: Boolean,
    val hasSpicyLyricsKey: Boolean,
    val spicyLyricsKeyHint: String,
    val autoStartHapp: Boolean,
    val hiddenApps: List<WebAppItem>,
    val visibleApps: List<WebAppItem>,
)

data class WebSettingsPatch(
    val backgroundMode: BackgroundMode? = null,
    val gridColumns: Int? = null,
    val mediaScreenEnabled: Boolean? = null,
    val lyricsEnabled: Boolean? = null,
    val spicyLyricsApiKey: String? = null,
    val clearSpicyLyricsKey: Boolean = false,
    val autoStartHapp: Boolean? = null,
    val unhidePackage: String? = null,
    val hidePackage: String? = null,
    val togglePinPackage: String? = null,
)

interface WebSettingsHandler {
    suspend fun getSnapshot(): WebSettingsSnapshot
    suspend fun updateSettings(patch: WebSettingsPatch): WebSettingsSnapshot
    suspend fun saveCustomBackground(bytes: ByteArray): Boolean
    suspend fun clearCustomBackground()
}

data class WebServerInfo(
    val host: String,
    val port: Int,
    val token: String,
) {
    val url: String get() = "http://$host:$port/?t=$token"
}

class WebSettingsServer(
    private val scope: CoroutineScope,
    private val handler: WebSettingsHandler,
    private val preferredPort: Int = DEFAULT_PORT,
    private val hostAddressProvider: () -> String? = ::findLanIpv4Address,
    private val socketTimeoutMs: Int = SOCKET_TIMEOUT_MS,
) {
    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var currentInfo: WebServerInfo? = null

    fun start(): WebServerInfo? = synchronized(lock) {
        currentInfo?.let { existing ->
            if (serverSocket?.isClosed == false) return existing
        }
        val host = hostAddressProvider() ?: return null
        val socket = openServerSocket(preferredPort) ?: return null
        val token = generateToken()
        val info = WebServerInfo(host = host, port = socket.localPort, token = token)
        serverSocket = socket
        currentInfo = info
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && !socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                launch(Dispatchers.IO) {
                    handleClient(client, token)
                }
            }
        }
        info
    }

    fun stop() {
        synchronized(lock) {
            acceptJob?.cancel()
            acceptJob = null
            runCatching { serverSocket?.close() }
            serverSocket = null
            currentInfo = null
        }
    }

    private fun openServerSocket(port: Int): ServerSocket? {
        if (port > 0) {
            runCatching {
                return ServerSocket(port, 16, InetAddress.getByName("0.0.0.0")).apply {
                    reuseAddress = true
                }
            }
        }
        return runCatching {
            ServerSocket(0, 16, InetAddress.getByName("0.0.0.0")).apply {
                reuseAddress = true
            }
        }.getOrNull()
    }

    private suspend fun handleClient(socket: Socket, expectedToken: String) {
        try {
            socket.use { client ->
                client.soTimeout = socketTimeoutMs
                val input = BufferedInputStream(client.getInputStream())
                val output = client.getOutputStream()
                val request = readHttpRequest(input) ?: run {
                    writeResponse(output, 400, "text/plain; charset=utf-8", "Bad Request".toByteArray())
                    return
                }
                if (request.tooLarge) {
                    writeResponse(output, 413, "text/plain; charset=utf-8", "Payload Too Large".toByteArray())
                    return
                }

                val providedToken = request.headers["x-auth-token"] ?: request.queryParams["t"]
                if (!tokensEqual(expectedToken, providedToken)) {
                    writeResponse(output, 401, "text/plain; charset=utf-8", "Unauthorized".toByteArray())
                    return
                }

                when {
                    request.method == "GET" && request.path == "/" -> {
                        val html = buildWebSettingsHtml().toByteArray(StandardCharsets.UTF_8)
                        writeResponse(output, 200, "text/html; charset=utf-8", html)
                    }
                    request.method == "GET" && request.path == "/api/state" -> {
                        val json = snapshotToJson(handler.getSnapshot()).toString().toByteArray(StandardCharsets.UTF_8)
                        writeResponse(output, 200, "application/json; charset=utf-8", json)
                    }
                    request.method == "POST" && request.path == "/api/settings" -> {
                        if (request.body.size > MAX_JSON_BYTES) {
                            writeResponse(output, 413, "text/plain; charset=utf-8", "Payload Too Large".toByteArray())
                            return
                        }
                        val patch = parsePatch(String(request.body, StandardCharsets.UTF_8)) ?: run {
                            writeResponse(output, 400, "text/plain; charset=utf-8", "Invalid JSON".toByteArray())
                            return
                        }
                        val updated = handler.updateSettings(patch)
                        val json = snapshotToJson(updated).toString().toByteArray(StandardCharsets.UTF_8)
                        writeResponse(output, 200, "application/json; charset=utf-8", json)
                    }
                    request.method == "POST" && request.path == "/api/background" -> {
                        if (request.body.isEmpty()) {
                            writeResponse(output, 400, "text/plain; charset=utf-8", "Empty image".toByteArray())
                            return
                        }
                        val saved = handler.saveCustomBackground(request.body)
                        if (!saved) {
                            writeResponse(output, 400, "text/plain; charset=utf-8", "Invalid image".toByteArray())
                            return
                        }
                        val json = snapshotToJson(handler.getSnapshot()).toString().toByteArray(StandardCharsets.UTF_8)
                        writeResponse(output, 200, "application/json; charset=utf-8", json)
                    }
                    request.method == "DELETE" && request.path == "/api/background" -> {
                        handler.clearCustomBackground()
                        val json = snapshotToJson(handler.getSnapshot()).toString().toByteArray(StandardCharsets.UTF_8)
                        writeResponse(output, 200, "application/json; charset=utf-8", json)
                    }
                    else -> {
                        writeResponse(output, 404, "text/plain; charset=utf-8", "Not Found".toByteArray())
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Ignore per-client socket/IO errors (e.g. browser preconnect timeouts) so the server stays alive.
        }
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val queryParams: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
        val tooLarge: Boolean = false,
    )

    private fun readHttpRequest(input: InputStream): HttpRequest? {
        val headerBytes = ByteArrayOutputStream()
        var matched = 0
        while (headerBytes.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null
            headerBytes.write(b)
            matched = when {
                matched == 0 && b == '\r'.code -> 1
                matched == 1 && b == '\n'.code -> 2
                matched == 2 && b == '\r'.code -> 3
                matched == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) break
        }
        if (matched != 4) return null

        val headerText = headerBytes.toString(StandardCharsets.ISO_8859_1.name())
        val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val requestParts = lines[0].split(' ')
        if (requestParts.size < 2) return null
        val method = requestParts[0].uppercase()
        val rawTarget = requestParts[1]
        val qIdx = rawTarget.indexOf('?')
        val path = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
        val queryParams = if (qIdx >= 0) parseQuery(rawTarget.substring(qIdx + 1)) else emptyMap()

        val headers = LinkedHashMap<String, String>()
        for (i in 1 until lines.size) {
            val colon = lines[i].indexOf(':')
            if (colon > 0) {
                val name = lines[i].substring(0, colon).trim().lowercase()
                val value = lines[i].substring(colon + 1).trim()
                headers[name] = value
            }
        }

        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
        if (contentLength < 0L || contentLength > MAX_UPLOAD_BYTES) {
            return HttpRequest(method, path, queryParams, headers, ByteArray(0), tooLarge = true)
        }
        val body = ByteArray(contentLength.toInt())
        var offset = 0
        while (offset < body.size) {
            val read = input.read(body, offset, body.size - offset)
            if (read < 0) return null
            offset += read
        }
        return HttpRequest(method, path, queryParams, headers, body)
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        raw.split('&').forEach { pair ->
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val k = runCatching { URLDecoder.decode(pair.substring(0, eq), "UTF-8") }.getOrNull()
                val v = runCatching { URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }.getOrNull()
                if (k != null && v != null) map[k] = v
            }
        }
        return map
    }

    private fun parsePatch(rawJson: String): WebSettingsPatch? {
        val obj = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        val bgMode = if (obj.has("backgroundMode") && !obj.isNull("backgroundMode")) {
            runCatching { BackgroundMode.valueOf(obj.getString("backgroundMode")) }.getOrNull()
        } else {
            null
        }
        val gridColumns = if (obj.has("gridColumns") && !obj.isNull("gridColumns")) {
            obj.optInt("gridColumns", -1).takeIf { it in 4..6 }
        } else {
            null
        }
        return WebSettingsPatch(
            backgroundMode = bgMode,
            gridColumns = gridColumns,
            mediaScreenEnabled = obj.optNullableBoolean("mediaScreenEnabled"),
            lyricsEnabled = obj.optNullableBoolean("lyricsEnabled"),
            spicyLyricsApiKey = obj.optNullableString("spicyLyricsApiKey"),
            clearSpicyLyricsKey = obj.optBoolean("clearSpicyLyricsKey", false),
            autoStartHapp = obj.optNullableBoolean("autoStartHapp"),
            unhidePackage = obj.optNullableString("unhidePackage"),
            hidePackage = obj.optNullableString("hidePackage"),
            togglePinPackage = obj.optNullableString("togglePinPackage"),
        )
    }

    private fun snapshotToJson(snapshot: WebSettingsSnapshot): JSONObject = JSONObject().apply {
        put("backgroundMode", snapshot.backgroundMode.name)
        put("hasCustomBackground", snapshot.hasCustomBackground)
        put("gridColumns", snapshot.gridColumns)
        put("mediaScreenEnabled", snapshot.mediaScreenEnabled)
        put("mediaAccessGranted", snapshot.mediaAccessGranted)
        put("lyricsEnabled", snapshot.lyricsEnabled)
        put("hasSpicyLyricsKey", snapshot.hasSpicyLyricsKey)
        put("spicyLyricsKeyHint", snapshot.spicyLyricsKeyHint)
        put("autoStartHapp", snapshot.autoStartHapp)
        put("hiddenApps", appsToJson(snapshot.hiddenApps))
        put("visibleApps", appsToJson(snapshot.visibleApps))
    }

    private fun appsToJson(apps: List<WebAppItem>): JSONArray = JSONArray().apply {
        apps.forEach { app ->
            put(
                JSONObject().apply {
                    put("packageName", app.packageName)
                    put("label", app.label)
                    put("isPinned", app.isPinned)
                },
            )
        }
    }

    private fun writeResponse(output: OutputStream, status: Int, contentType: String, body: ByteArray) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)
        runCatching {
            output.write(header)
            if (body.isNotEmpty()) output.write(body)
            output.flush()
        }
    }

    private fun tokensEqual(expected: String, provided: String?): Boolean {
        if (provided == null) return false
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            provided.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun generateToken(): String {
        val rng = SecureRandom()
        return buildString(TOKEN_LENGTH) {
            repeat(TOKEN_LENGTH) {
                append(TOKEN_ALPHABET[rng.nextInt(TOKEN_ALPHABET.length)])
            }
        }
    }

    private fun JSONObject.optNullableBoolean(key: String): Boolean? =
        if (has(key) && !isNull(key)) optBoolean(key) else null

    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).trim().takeIf { it.isNotEmpty() } else null

    companion object {
        const val DEFAULT_PORT = 8765
        private const val TOKEN_LENGTH = 6
        private const val TOKEN_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_JSON_BYTES = 64 * 1024
        private const val MAX_UPLOAD_BYTES = 20L * 1024L * 1024L
    }
}

internal fun findLanIpv4Address(): String? {
    val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull() ?: return null
    val candidates = mutableListOf<Pair<Int, String>>()
    for (iface in interfaces) {
        val up = runCatching { iface.isUp && !iface.isLoopback && !iface.isPointToPoint }.getOrDefault(false)
        if (!up) continue
        val name = iface.name.lowercase()
        if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("wg") || name.startsWith("rmnet")) {
            continue
        }
        val priority = when {
            name.startsWith("eth") || name.startsWith("en") -> 0
            name.startsWith("wlan") || name.startsWith("wl") -> 1
            else -> 2
        }
        for (addr in iface.inetAddresses) {
            if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                val host = addr.hostAddress ?: continue
                val siteBonus = if (addr.isSiteLocalAddress) 0 else 10
                candidates.add((priority + siteBonus) to host)
            }
        }
    }
    return candidates.minByOrNull { it.first }?.second
}
