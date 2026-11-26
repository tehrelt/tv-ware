package com.tvware.launcher.data

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.LruCache
import android.view.KeyEvent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MediaMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val pm = context.packageManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listenerComponent = ComponentName(context, MediaNotificationListenerService::class.java)

    private val _state = MutableStateFlow(MediaMonitorState())
    val state: StateFlow<MediaMonitorState> = _state.asStateFlow()

    private val artworkCache = object : LruCache<String, ImageBitmap>(6 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            ((value.width * value.height * 4) / 1024).coerceAtLeast(1)
    }
    private val appLabelCache = HashMap<String, String>()

    private val controllerCallbacks = LinkedHashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var currentControllers: List<MediaController> = emptyList()
    private var activeToken: MediaSession.Token? = null
    private val metadataSnapshots = mutableMapOf<MediaSession.Token, MediaMetadata?>()
    private var artworkJob: Job? = null
    private var loadingArtworkKey: String? = null

    private var started = false
    private var listenerRegistered = false
    private var registeredWithComponent = false
    private var observerRegistered = false

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateControllers(controllers.orEmpty())
    }

    private val settingsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            refreshAccessAndSessions()
        }
    }

    fun start() {
        runOnMain {
            if (started) return@runOnMain
            started = true
            if (!observerRegistered) {
                runCatching {
                    context.contentResolver.registerContentObserver(
                        Settings.Secure.getUriFor("enabled_notification_listeners"),
                        false,
                        settingsObserver,
                    )
                    observerRegistered = true
                }
            }
            attachAndQuery()
        }
    }

    fun stop() {
        runOnMain {
            if (!started) return@runOnMain
            started = false
            if (observerRegistered) {
                runCatching { context.contentResolver.unregisterContentObserver(settingsObserver) }
                observerRegistered = false
            }
            detachSessionsListener()
            clearControllerCallbacks()
            cancelArtworkLoad()
            currentControllers = emptyList()
            activeToken = null
        }
    }

    fun refreshAccessAndSessions() {
        runOnMain {
            if (!started) return@runOnMain
            attachAndQuery()
        }
    }

    fun togglePlayPause() {
        runOnMain {
            val controller = findActiveController() ?: return@runOnMain
            val pb = controller.playbackState
            val actions = pb?.actions ?: 0L
            val playing = isPlaybackActiveState(pb?.state ?: PlaybackState.STATE_NONE)
            if (playing) {
                when {
                    (actions and PlaybackState.ACTION_PAUSE) != 0L -> controller.transportControls.pause()
                    (actions and PlaybackState.ACTION_PLAY_PAUSE) != 0L -> dispatchPlayPauseKey(controller)
                    else -> controller.transportControls.pause()
                }
            } else {
                when {
                    (actions and PlaybackState.ACTION_PLAY) != 0L -> controller.transportControls.play()
                    (actions and PlaybackState.ACTION_PLAY_PAUSE) != 0L -> dispatchPlayPauseKey(controller)
                    else -> controller.transportControls.play()
                }
            }
        }
    }

    fun skipToPrevious() {
        runOnMain {
            val controller = findActiveController() ?: return@runOnMain
            val actions = controller.playbackState?.actions ?: 0L
            when {
                (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L ->
                    controller.transportControls.skipToPrevious()
                (actions and PlaybackState.ACTION_REWIND) != 0L ->
                    controller.transportControls.rewind()
            }
        }
    }

    fun skipToNext() {
        runOnMain {
            val controller = findActiveController() ?: return@runOnMain
            val actions = controller.playbackState?.actions ?: 0L
            when {
                (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L ->
                    controller.transportControls.skipToNext()
                (actions and PlaybackState.ACTION_FAST_FORWARD) != 0L ->
                    controller.transportControls.fastForward()
            }
        }
    }

    private fun attachAndQuery() {
        val manager = msm ?: run {
            _state.value = MediaMonitorState(hasAccess = false, activeMedia = null)
            return
        }

        val listenerGranted = nm?.isNotificationListenerAccessGranted(listenerComponent) == true
        val targetComponent = if (listenerGranted) listenerComponent else null

        if (listenerRegistered && registeredWithComponent != listenerGranted) {
            detachSessionsListener()
        }

        try {
            if (!listenerRegistered) {
                manager.addOnActiveSessionsChangedListener(sessionsListener, targetComponent, mainHandler)
                listenerRegistered = true
                registeredWithComponent = listenerGranted
            }
            val controllers = manager.getActiveSessions(targetComponent)
            updateControllers(controllers)
        } catch (_: SecurityException) {
            detachSessionsListener()
            clearControllerCallbacks()
            currentControllers = emptyList()
            activeToken = null
            cancelArtworkLoad()
            _state.value = MediaMonitorState(hasAccess = false, activeMedia = null)
        }
    }

    private fun detachSessionsListener() {
        if (listenerRegistered) {
            runCatching { msm?.removeOnActiveSessionsChangedListener(sessionsListener) }
            listenerRegistered = false
        }
    }

    private fun updateControllers(controllers: List<MediaController>) {
        val filtered = controllers.filter { it.packageName != context.packageName }
        val activeTokens = filtered.mapTo(HashSet()) { it.sessionToken }

        val iterator = controllerCallbacks.entries.iterator()
        while (iterator.hasNext()) {
            val (token, pair) = iterator.next()
            if (token !in activeTokens) {
                runCatching { pair.first.unregisterCallback(pair.second) }
                metadataSnapshots.remove(token)
                iterator.remove()
            }
        }

        filtered.forEach { controller ->
            if (!controllerCallbacks.containsKey(controller.sessionToken)) {
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        publishActiveSession()
                    }

                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        metadataSnapshots[controller.sessionToken] = metadata
                        publishActiveSession()
                    }

                    override fun onSessionDestroyed() {
                        refreshAccessAndSessions()
                    }
                }
                runCatching {
                    controller.registerCallback(callback, mainHandler)
                    controllerCallbacks[controller.sessionToken] = controller to callback
                }
            }
        }

        currentControllers = filtered
        publishActiveSession()
    }

    private fun clearControllerCallbacks() {
        controllerCallbacks.values.forEach { (controller, callback) ->
            runCatching { controller.unregisterCallback(callback) }
        }
        controllerCallbacks.clear()
        metadataSnapshots.clear()
    }

    private fun publishActiveSession() {
        val states = currentControllers.map { it.playbackState?.state ?: PlaybackState.STATE_NONE }
        val currentIndex = activeToken?.let { tok ->
            currentControllers.indexOfFirst { it.sessionToken == tok }.takeIf { it >= 0 }
        }
        val selectedIndex = selectPreferredSessionIndex(states, currentIndex)
        if (selectedIndex == null) {
            activeToken = null
            cancelArtworkLoad()
            _state.value = MediaMonitorState(hasAccess = true, activeMedia = null)
            return
        }

        val controller = currentControllers[selectedIndex]
        activeToken = controller.sessionToken
        val info = buildSessionInfo(controller)
        _state.value = MediaMonitorState(hasAccess = true, activeMedia = info)
    }

    private fun buildSessionInfo(controller: MediaController): MediaSessionInfo {
        val meta = if (metadataSnapshots.containsKey(controller.sessionToken)) {
            metadataSnapshots[controller.sessionToken]
        } else {
            controller.metadata.also { metadataSnapshots[controller.sessionToken] = it }
        }
        val pb = controller.playbackState
        val pbState = pb?.state ?: PlaybackState.STATE_NONE
        val playing = isPlaybackActiveState(pbState)
        val paused = isPlaybackPausedState(pbState)
        val pkg = controller.packageName
        val appLabel = resolveAppLabel(pkg)

        val rawTitle = meta?.firstNonBlankString(
            MediaMetadata.METADATA_KEY_TITLE,
            MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
        ).orEmpty()
        val title = rawTitle.ifEmpty { appLabel }
        val artist = meta?.firstNonBlankString(
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
            MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
        ).orEmpty()
        val rawAlbum = meta?.firstNonBlankString(
            MediaMetadata.METADATA_KEY_ALBUM,
            MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION,
        ).orEmpty()
        val album = if (rawAlbum == artist || rawAlbum == title) "" else rawAlbum

        val durationMs = (meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)
        val actions = pb?.actions ?: 0L
        val canPlayPause = (actions and (
            PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE
            )) != 0L || playing || paused
        val canSkipPrevious = (actions and (
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_REWIND
            )) != 0L
        val canSkipNext = (actions and (
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_FAST_FORWARD
            )) != 0L

        // DISPLAY_ICON может содержать логотип плеера, а не обложку.
        val (embeddedBitmap, artworkUri) = selectMediaArtwork(
            albumBitmap = meta?.firstValidBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART),
            albumUri = meta?.firstNonBlankString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
            trackBitmap = meta?.firstValidBitmap(MediaMetadata.METADATA_KEY_ART),
            trackUri = meta?.firstNonBlankString(MediaMetadata.METADATA_KEY_ART_URI),
        )

        val trackKey = MediaTrackKey(
            sessionKey = controller.sessionToken.toString(),
            mediaId = meta?.firstNonBlankString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            title = title,
            artist = artist,
            album = album,
        )
        val artworkKey = trackKey.artworkKey(artworkUri, embeddedBitmap?.generationId)

        val currentMedia = _state.value.activeMedia
        val cachedArtwork = when {
            artworkKey == null -> null
            currentMedia?.artworkKey == artworkKey && currentMedia.artwork != null -> currentMedia.artwork
            else -> artworkCache.get(artworkKey)
        }

        if (artworkKey != null && cachedArtwork == null) {
            scheduleArtworkLoad(artworkKey, embeddedBitmap, artworkUri)
        } else {
            cancelArtworkLoad()
        }

        return MediaSessionInfo(
            sessionKey = controller.sessionToken.toString(),
            packageName = pkg,
            appLabel = appLabel,
            title = title,
            artist = artist,
            album = album,
            isPlaying = playing,
            isPaused = paused,
            durationMs = durationMs,
            positionMs = (pb?.position ?: 0L).coerceAtLeast(0L),
            playbackSpeed = pb?.playbackSpeed ?: if (playing) 1f else 0f,
            positionUpdateTimeMs = pb?.lastPositionUpdateTime ?: 0L,
            canPlayPause = canPlayPause,
            canSkipPrevious = canSkipPrevious,
            canSkipNext = canSkipNext,
            artwork = cachedArtwork,
            artworkKey = artworkKey,
            mediaId = trackKey.mediaId,
        )
    }

    private fun cancelArtworkLoad() {
        artworkJob?.cancel()
        artworkJob = null
        loadingArtworkKey = null
    }

    private fun scheduleArtworkLoad(
        artworkKey: String,
        embeddedBitmap: Bitmap?,
        artworkUri: String?,
    ) {
        if (artworkJob?.isActive == true && loadingArtworkKey == artworkKey) return
        artworkJob?.cancel()
        loadingArtworkKey = artworkKey
        artworkJob = scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val fromBitmap = embeddedBitmap?.let(::scaleBitmapIfNeeded)
                    fromBitmap ?: artworkUri?.let(::loadBitmapFromUri)
                } ?: return@launch

                artworkCache.put(artworkKey, loaded)
                runOnMain {
                    val current = _state.value.activeMedia ?: return@runOnMain
                    if (current.artworkKey == artworkKey) {
                        _state.value = _state.value.copy(
                            activeMedia = current.copy(artwork = loaded),
                        )
                    }
                }
            } finally {
                runOnMain {
                    if (loadingArtworkKey == artworkKey) {
                        loadingArtworkKey = null
                    }
                }
            }
        }
    }

    private fun scaleBitmapIfNeeded(source: Bitmap): ImageBitmap? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
        val srcCfg = source.config
        val softwareSource = if (srcCfg == null || srcCfg == Bitmap.Config.HARDWARE) {
            runCatching { source.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return null
        } else {
            source
        }
        val maxDim = maxOf(softwareSource.width, softwareSource.height)
        val prepared = if (maxDim > MAX_ARTWORK_PX) {
            val scale = MAX_ARTWORK_PX.toFloat() / maxDim
            val w = (softwareSource.width * scale).toInt().coerceAtLeast(1)
            val h = (softwareSource.height * scale).toInt().coerceAtLeast(1)
            runCatching { Bitmap.createScaledBitmap(softwareSource, w, h, true) }.getOrNull() ?: return null
        } else if (softwareSource === source) {
            runCatching { softwareSource.copy(srcCfg ?: Bitmap.Config.ARGB_8888, false) }.getOrNull()
                ?: softwareSource
        } else {
            softwareSource
        }
        return prepared.asImageBitmap()
    }

    private fun loadBitmapFromUri(rawUri: String): ImageBitmap? {
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return null
        val bytes = when (uri.scheme?.lowercase()) {
            "content", "android.resource" -> readContentUriBytes(uri)
            "https", "http" -> readHttpBytes(rawUri)
            else -> null
        } ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_ARTWORK_PX, MAX_ARTWORK_PX)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }

    private fun readContentUriBytes(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            readBoundedBytes(input)
        }
    }.getOrNull()

    private fun readHttpBytes(rawUrl: String): ByteArray? = runCatching {
        val conn = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = false
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            if (conn.contentLengthLong > MAX_ARTWORK_BYTES) return@runCatching null
            conn.inputStream.use { input -> readBoundedBytes(input) }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun readBoundedBytes(input: java.io.InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_ARTWORK_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        while (width / inSampleSize > reqW || height / inSampleSize > reqH) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun resolveAppLabel(packageName: String): String {
        appLabelCache[packageName]?.let { return it }
        val label = runCatching {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(appInfo).toString().trim()
        }.getOrNull().orEmpty().ifEmpty { packageName }
        appLabelCache[packageName] = label
        return label
    }

    private fun findActiveController(): MediaController? =
        activeToken?.let { tok -> currentControllers.firstOrNull { it.sessionToken == tok } }

    private fun dispatchPlayPauseKey(controller: MediaController) {
        controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun MediaMetadata.firstNonBlankString(vararg keys: String): String? {
        for (key in keys) {
            val value = getString(key)?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }

    private fun MediaMetadata.firstValidBitmap(vararg keys: String): Bitmap? {
        for (key in keys) {
            val bmp = getBitmap(key)
            if (bmp != null && !bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
                return bmp
            }
        }
        return null
    }

    private companion object {
        const val MAX_ARTWORK_PX = 720
        const val MAX_ARTWORK_BYTES = 5 * 1024 * 1024
        const val HTTP_TIMEOUT_MS = 5_000
    }
}
