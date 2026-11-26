package com.tvware.launcher.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvware.launcher.LauncherApp
import com.tvware.launcher.data.AppArtwork
import com.tvware.launcher.data.AppInfo
import com.tvware.launcher.data.BackgroundMode
import com.tvware.launcher.data.LauncherSettings
import com.tvware.launcher.data.LyricLine
import com.tvware.launcher.data.LyricSyllable
import com.tvware.launcher.data.LyricsData
import com.tvware.launcher.data.LyricsState
import com.tvware.launcher.data.LyricsSyncType
import com.tvware.launcher.data.MediaMonitorState
import com.tvware.launcher.data.MediaScreenReducer
import com.tvware.launcher.data.MediaScreenVisibilityState
import com.tvware.launcher.data.MediaSessionInfo
import com.tvware.launcher.data.NetworkStatus
import com.tvware.launcher.data.SpicyLyricsClient
import com.tvware.launcher.data.shouldAutoStartHapp
import com.tvware.launcher.web.WebAppItem
import com.tvware.launcher.web.WebSettingsHandler
import com.tvware.launcher.web.WebSettingsPatch
import com.tvware.launcher.web.WebSettingsServer
import com.tvware.launcher.web.WebSettingsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Immutable
data class LauncherUiState(
    val pinnedApps: List<AppInfo> = emptyList(),
    val gridApps: List<AppInfo> = emptyList(),
    val hiddenApps: List<AppInfo> = emptyList(),
    val pinnedPackages: Set<String> = emptySet(),
    val gridColumns: Int = 5,
    val backgroundMode: BackgroundMode = BackgroundMode.GRADIENT,
    val customBackgroundPath: String? = null,
    val customBackgroundBitmap: ImageBitmap? = null,
    val networkStatus: NetworkStatus = NetworkStatus(),
    val mediaScreenEnabled: Boolean = true,
    val lyricsEnabled: Boolean = true,
    val hasSpicyLyricsKey: Boolean = false,
    val lyricsState: LyricsState = LyricsState.Idle,
    val webSettingsUrl: String? = null,
    val autoStartHapp: Boolean = true,
    val mediaState: MediaMonitorState = MediaMonitorState(),
    val showMediaScreen: Boolean = false,
    val initializedDefaults: Boolean = false,
)

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as LauncherApp
    private val repository = app.appRepository
    private val preferences = app.preferences
    private val iconCache = app.iconCache
    private val networkMonitor = app.networkMonitor
    private val mediaMonitor = app.mediaMonitor

    private val backgroundLock = Mutex()
    private val customBgBitmap = MutableStateFlow<ImageBitmap?>(null)
    private val mediaVisibility = MutableStateFlow(MediaScreenVisibilityState())
    private val lyricsState = MutableStateFlow<LyricsState>(LyricsState.Idle)
    private val webSettingsUrl = MutableStateFlow<String?>(null)
    private val debugMediaOverride = MutableStateFlow<Pair<MediaMonitorState, LyricsState>?>(null)
    private val spicyLyricsClient = SpicyLyricsClient()
    private val webSettingsServer = WebSettingsServer(
        scope = viewModelScope,
        handler = createWebSettingsHandler(),
    )
    @Volatile
    private var latestMediaEnabled = true

    private data class MediaUiBundle(
        val media: MediaMonitorState,
        val visibility: MediaScreenVisibilityState,
        val lyrics: LyricsState,
        val webUrl: String?,
    )

    val uiState: StateFlow<LauncherUiState> = combine(
        repository.apps,
        preferences.settingsFlow,
        networkMonitor.status,
        customBgBitmap,
        combine(
            mediaMonitor.state,
            mediaVisibility,
            lyricsState,
            webSettingsUrl,
            debugMediaOverride,
        ) { media, visibility, lyrics, webUrl, debugOverride ->
            if (debugOverride != null) {
                MediaUiBundle(
                    media = debugOverride.first,
                    visibility = visibility.copy(isVisible = true),
                    lyrics = debugOverride.second,
                    webUrl = webUrl,
                )
            } else {
                MediaUiBundle(media, visibility, lyrics, webUrl)
            }
        },
    ) { installed, settings, netStatus, bgBitmap, bundle ->
        buildUiState(installed, settings, netStatus, bgBitmap, bundle)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LauncherUiState(),
    )

    init {
        viewModelScope.launch {
            combine(
                repository.apps,
                preferences.settingsFlow.map { it.initializedDefaults }.distinctUntilChanged(),
            ) { installed, initialized ->
                installed to initialized
            }.collect { (installed, initialized) ->
                if (!initialized && installed.isNotEmpty()) {
                    preferences.ensureDefaultPins(installed.mapTo(HashSet()) { it.packageName })
                }
            }
        }

        viewModelScope.launch {
            preferences.settingsFlow
                .map { it.backgroundMode to it.customBackgroundPath }
                .distinctUntilChanged()
                .collect { (mode, path) ->
                    customBgBitmap.value = if (mode == BackgroundMode.CUSTOM_FILE && !path.isNullOrBlank()) {
                        iconCache.loadCustomBackground(path)
                    } else {
                        null
                    }
                }
        }

        viewModelScope.launch {
            combine(
                mediaMonitor.state,
                preferences.settingsFlow.map { it.mediaScreenEnabled }.distinctUntilChanged(),
            ) { media, enabled -> media to enabled }
                .collect { (media, enabled) ->
                    latestMediaEnabled = enabled
                    val session = media.activeMedia
                    mediaVisibility.update { current ->
                        MediaScreenReducer.onMediaUpdated(
                            state = current,
                            hasSession = session != null,
                            isPlaying = session?.isPlaying == true,
                            isPaused = session?.isPaused == true,
                            enabled = enabled,
                            nowMs = SystemClock.elapsedRealtime(),
                        )
                    }
                }
        }

        viewModelScope.launch {
            combine(
                mediaMonitor.state.map { state ->
                    state.activeMedia?.let {
                        LyricsTrackQuery(
                            mediaId = it.mediaId,
                            title = it.title,
                            artist = it.artist,
                            album = it.album,
                            durationMs = it.durationMs,
                        )
                    }
                }.distinctUntilChanged(),
                preferences.settingsFlow.map { it.lyricsEnabled to it.spicyLyricsApiKey }.distinctUntilChanged(),
            ) { track, (enabled, apiKey) ->
                Triple(track, enabled, apiKey)
            }.collectLatest { (track, enabled, apiKey) ->
                if (!enabled || apiKey.isBlank() || track == null) {
                    lyricsState.value = LyricsState.Idle
                    return@collectLatest
                }
                lyricsState.value = LyricsState.Loading
                lyricsState.value = spicyLyricsClient.fetchLyrics(
                    apiKey = apiKey,
                    mediaId = track.mediaId,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs,
                )
            }
        }
    }

    private data class LyricsTrackQuery(
        val mediaId: String?,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
    )

    fun startWebSettingsServer() {
        viewModelScope.launch(Dispatchers.IO) {
            val info = webSettingsServer.start()
            webSettingsUrl.value = info?.url
        }
    }

    fun stopWebSettingsServer() {
        webSettingsServer.stop()
        webSettingsUrl.value = null
    }

    override fun onCleared() {
        webSettingsServer.stop()
        super.onCleared()
    }

    fun getCachedArtwork(appInfo: AppInfo): AppArtwork? = iconCache.getFromMemory(appInfo)

    suspend fun loadArtwork(appInfo: AppInfo): AppArtwork? = iconCache.loadArtwork(appInfo)

    fun launchApp(appInfo: AppInfo): Boolean = repository.launchApp(appInfo)

    fun launchHapp(): Boolean = repository.launchHapp()

    fun launchMediaApp(): Boolean {
        val pkg = mediaMonitor.state.value.activeMedia?.packageName ?: return false
        val appInfo = repository.apps.value.firstOrNull { it.packageName == pkg }
        return if (appInfo != null) repository.launchApp(appInfo) else repository.launchPackage(pkg)
    }

    fun refreshApps() = repository.refresh()

    fun togglePin(packageName: String) {
        viewModelScope.launch { preferences.togglePin(packageName) }
    }

    fun moveApp(packageName: String, fromPinned: Boolean, delta: Int) {
        viewModelScope.launch {
            if (fromPinned) {
                preferences.movePinned(packageName, delta)
            } else {
                val visibleGrid = uiState.value.gridApps.map { it.packageName }
                preferences.moveInGrid(packageName, visibleGrid, delta)
            }
        }
    }

    fun hideApp(packageName: String) {
        viewModelScope.launch { preferences.hideApp(packageName) }
    }

    fun unhideApp(packageName: String) {
        viewModelScope.launch { preferences.unhideApp(packageName) }
    }

    fun setGridColumns(columns: Int) {
        viewModelScope.launch { preferences.setGridColumns(columns) }
    }

    fun setBackgroundMode(mode: BackgroundMode) {
        viewModelScope.launch { preferences.setBackgroundMode(mode) }
    }

    fun setMediaScreenEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setMediaScreenEnabled(enabled) }
    }

    fun setAutoStartHapp(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutoStartHapp(enabled) }
    }

    suspend fun maybeAutoStartHapp() {
        if (app.happAutoStartAttempted) return
        delay(1_500L)
        val enabled = preferences.settingsFlow.first().autoStartHapp
        val isVpnActive = networkMonitor.status.value.isVpnActive
        if (isVpnActive) {
            app.happAutoStartAttempted = true
            return
        }
        if (shouldAutoStartHapp(
                enabled = enabled,
                alreadyAttempted = app.happAutoStartAttempted,
                isVpnActive = false,
                isHappInstalled = repository.isHappInstalled(),
            )
        ) {
            app.happAutoStartAttempted = true
            repository.connectHapp()
        }
    }

    fun onLauncherResumed(allowAutoShow: Boolean = true) {
        mediaMonitor.refreshAccessAndSessions()
        if (!allowAutoShow) return
        val session = mediaMonitor.state.value.activeMedia
        mediaVisibility.update { current ->
            MediaScreenReducer.onLauncherResumed(
                state = current,
                hasSession = session != null,
                isPlaying = session?.isPlaying == true,
                enabled = latestMediaEnabled,
                nowMs = SystemClock.elapsedRealtime(),
            )
        }
    }

    fun dismissMediaScreen() {
        mediaVisibility.update { MediaScreenReducer.onDismissToApps(it) }
    }

    fun openMediaScreen() {
        val session = mediaMonitor.state.value.activeMedia
        mediaVisibility.update { current ->
            MediaScreenReducer.onOpenMediaScreen(
                state = current,
                hasSession = session != null,
                isPlaying = session?.isPlaying == true,
                isPaused = session?.isPaused == true,
                enabled = latestMediaEnabled,
            )
        }
    }

    fun toggleMediaPlayPause() {
        mediaVisibility.update { MediaScreenReducer.onUserMediaControl(it) }
        mediaMonitor.togglePlayPause()
    }

    fun skipMediaPrevious() {
        mediaVisibility.update { MediaScreenReducer.onUserMediaControl(it) }
        mediaMonitor.skipToPrevious()
    }

    fun skipMediaNext() {
        mediaVisibility.update { MediaScreenReducer.onUserMediaControl(it) }
        mediaMonitor.skipToNext()
    }

    fun markMediaInteraction() {
        mediaVisibility.update { MediaScreenReducer.onUserMediaControl(it) }
    }

    fun importCustomBackground(uri: Uri) = editBackground {
        val old = preferences.settingsFlow.first().customBackgroundPath
        val target = withContext(Dispatchers.IO) {
            File.createTempFile("custom_bg_", ".img", app.filesDir)
        }
        var committed = false
        try {
            withContext(Dispatchers.IO) {
                val source = app.contentResolver.openInputStream(uri) ?: throw IOException()
                source.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var remaining = 20 * 1024 * 1024
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            remaining -= read
                            if (remaining < 0) throw IOException()
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            if (iconCache.loadCustomBackground(target.absolutePath) == null) throw IOException()
            preferences.setCustomBackgroundPath(target.absolutePath)
            committed = true
            removeBackground(old)
        } finally {
            if (!committed) removeBackground(target.absolutePath)
        }
    }

    fun clearCustomBackground() = editBackground {
        val old = preferences.settingsFlow.first().customBackgroundPath
        preferences.setCustomBackgroundPath(null)
        removeBackground(old)
    }

    private suspend fun saveCustomBackgroundBytes(bytes: ByteArray): Boolean = backgroundLock.withLock {
        if (bytes.isEmpty() || bytes.size > 20 * 1024 * 1024) return@withLock false
        val old = preferences.settingsFlow.first().customBackgroundPath
        val target = withContext(Dispatchers.IO) {
            File.createTempFile("custom_bg_", ".img", app.filesDir)
        }
        var committed = false
        try {
            withContext(Dispatchers.IO) {
                target.writeBytes(bytes)
            }
            if (iconCache.loadCustomBackground(target.absolutePath) == null) return@withLock false
            preferences.setCustomBackgroundPath(target.absolutePath)
            committed = true
            removeBackground(old)
            true
        } catch (_: IOException) {
            false
        } finally {
            if (!committed) removeBackground(target.absolutePath)
        }
    }

    private fun createWebSettingsHandler(): WebSettingsHandler = object : WebSettingsHandler {
        override suspend fun getSnapshot(): WebSettingsSnapshot {
            val settings = preferences.settingsFlow.first()
            val installed = repository.apps.value
            val hiddenSet = settings.hiddenPackages
            val pinnedSet = settings.pinnedPackages.toSet()
            val hiddenApps = installed
                .filter { it.packageName in hiddenSet }
                .map { WebAppItem(it.packageName, it.label, isPinned = false) }
            val visibleApps = installed
                .filter { it.packageName !in hiddenSet }
                .map { WebAppItem(it.packageName, it.label, isPinned = it.packageName in pinnedSet) }
            val key = settings.spicyLyricsApiKey
            val hint = if (key.length >= 4) "••••${key.takeLast(4)}" else if (key.isNotEmpty()) "••••" else ""
            return WebSettingsSnapshot(
                backgroundMode = settings.backgroundMode,
                hasCustomBackground = !settings.customBackgroundPath.isNullOrBlank(),
                gridColumns = settings.gridColumns,
                mediaScreenEnabled = settings.mediaScreenEnabled,
                mediaAccessGranted = mediaMonitor.state.value.hasAccess,
                lyricsEnabled = settings.lyricsEnabled,
                hasSpicyLyricsKey = key.isNotEmpty(),
                spicyLyricsKeyHint = hint,
                autoStartHapp = settings.autoStartHapp,
                hiddenApps = hiddenApps,
                visibleApps = visibleApps,
            )
        }

        override suspend fun updateSettings(patch: WebSettingsPatch): WebSettingsSnapshot {
            patch.backgroundMode?.let { mode ->
                if (mode != BackgroundMode.CUSTOM_FILE || !preferences.settingsFlow.first().customBackgroundPath.isNullOrBlank()) {
                    preferences.setBackgroundMode(mode)
                }
            }
            patch.gridColumns?.let { preferences.setGridColumns(it) }
            patch.mediaScreenEnabled?.let { preferences.setMediaScreenEnabled(it) }
            patch.lyricsEnabled?.let { preferences.setLyricsEnabled(it) }
            patch.spicyLyricsApiKey?.let { preferences.setSpicyLyricsApiKey(it) }
            if (patch.clearSpicyLyricsKey) {
                preferences.setSpicyLyricsApiKey("")
            }
            patch.autoStartHapp?.let { preferences.setAutoStartHapp(it) }
            patch.unhidePackage?.let { preferences.unhideApp(it) }
            patch.hidePackage?.let { preferences.hideApp(it) }
            patch.togglePinPackage?.let { preferences.togglePin(it) }
            return getSnapshot()
        }

        override suspend fun saveCustomBackground(bytes: ByteArray): Boolean =
            saveCustomBackgroundBytes(bytes)

        override suspend fun clearCustomBackground() {
            backgroundLock.withLock {
                val old = preferences.settingsFlow.first().customBackgroundPath
                preferences.setCustomBackgroundPath(null)
                removeBackground(old)
            }
        }
    }

    private fun editBackground(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                backgroundLock.withLock { action() }
            } catch (e: IOException) {
                Toast.makeText(app, "Не удалось изменить фон", Toast.LENGTH_SHORT).show()
            } catch (e: SecurityException) {
                Toast.makeText(app, "Нет доступа к изображению", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun removeBackground(path: String?) {
        withContext(Dispatchers.IO) {
            val file = path?.let(::File)
            if (file?.parentFile == app.filesDir && file.name.startsWith("custom_bg")) file.delete()
        }
    }

    fun toggleLyricsEnabled() {
        viewModelScope.launch {
            preferences.setLyricsEnabled(!uiState.value.lyricsEnabled)
        }
    }

    fun setDebugMediaPreview(
        enabled: Boolean,
        positionMs: Long = 12_000L,
        isPlaying: Boolean = true,
    ) {
        if (!enabled) {
            debugMediaOverride.value = null
            return
        }
        val artwork = createDebugArtwork()
        val now = SystemClock.elapsedRealtime()
        val media = MediaSessionInfo(
            sessionKey = "debug_preview",
            packageName = "com.spotify.tv.android",
            appLabel = "Spotify",
            title = "See You Again (feat. Kali Uchis)",
            artist = "Tyler, The Creator",
            album = "Flower Boy",
            isPlaying = isPlaying,
            isPaused = !isPlaying,
            durationMs = 180_000L,
            positionMs = positionMs,
            playbackSpeed = 1f,
            positionUpdateTimeMs = now,
            canPlayPause = true,
            canSkipPrevious = true,
            canSkipNext = true,
            artwork = artwork,
            artworkKey = "debug_artwork",
        )
        val lines = listOf(
            LyricLine(
                text = "Okay, okay, okay, okay",
                startMs = 4_000L,
                endMs = 7_000L,
                syllables = listOf(
                    LyricSyllable("Okay,", false, 4_000L, 4_700L),
                    LyricSyllable("okay,", false, 4_750L, 5_450L),
                    LyricSyllable("okay,", false, 5_500L, 6_200L),
                    LyricSyllable("okay", false, 6_250L, 7_000L),
                ),
            ),
            LyricLine(
                text = "You live in my dream state",
                startMs = 7_200L,
                endMs = 10_200L,
                syllables = listOf(
                    LyricSyllable("You", false, 7_200L, 7_600L),
                    LyricSyllable("live", false, 7_650L, 8_100L),
                    LyricSyllable("in", false, 8_150L, 8_450L),
                    LyricSyllable("my", false, 8_500L, 8_900L),
                    LyricSyllable("dream", false, 8_950L, 9_550L),
                    LyricSyllable("state", false, 9_600L, 10_200L),
                ),
            ),
            LyricLine(
                text = "Relocate my fantasy",
                startMs = 10_400L,
                endMs = 13_400L,
                syllables = listOf(
                    LyricSyllable("Re", true, 10_400L, 10_750L),
                    LyricSyllable("lo", true, 10_750L, 11_100L),
                    LyricSyllable("cate", false, 11_100L, 11_600L),
                    LyricSyllable("my", false, 11_650L, 12_050L),
                    LyricSyllable("fan", true, 12_100L, 12_500L),
                    LyricSyllable("ta", true, 12_500L, 12_900L),
                    LyricSyllable("sy", false, 12_900L, 13_400L),
                ),
            ),
            LyricLine(
                text = "I stay in reality",
                startMs = 13_600L,
                endMs = 16_400L,
                syllables = listOf(
                    LyricSyllable("I", false, 13_600L, 14_000L),
                    LyricSyllable("stay", false, 14_050L, 14_600L),
                    LyricSyllable("in", false, 14_650L, 15_000L),
                    LyricSyllable("re", true, 15_050L, 15_400L),
                    LyricSyllable("al", true, 15_400L, 15_750L),
                    LyricSyllable("i", true, 15_750L, 16_050L),
                    LyricSyllable("ty", false, 16_050L, 16_400L),
                ),
            ),
            LyricLine(
                text = "You exist behind my eyelids, my eyelids",
                startMs = 20_000L,
                endMs = 24_000L,
                syllables = listOf(
                    LyricSyllable("You", false, 20_000L, 20_400L),
                    LyricSyllable("ex", true, 20_450L, 20_800L),
                    LyricSyllable("ist", false, 20_800L, 21_200L),
                    LyricSyllable("be", true, 21_250L, 21_550L),
                    LyricSyllable("hind", false, 21_550L, 21_950L),
                    LyricSyllable("my", false, 22_000L, 22_350L),
                    LyricSyllable("eye", true, 22_400L, 22_800L),
                    LyricSyllable("lids,", false, 22_800L, 23_200L),
                    LyricSyllable("my", false, 23_250L, 23_550L),
                    LyricSyllable("eyelids", false, 23_600L, 24_000L),
                ),
            ),
            LyricLine(
                text = "Now I don't wanna wake up",
                startMs = 24_200L,
                endMs = 27_500L,
            ),
            LyricLine(
                text = "20/20, 20/20 vision",
                startMs = 27_700L,
                endMs = 31_000L,
            ),
            LyricLine(
                text = "Cupid hit me, Cupid hit me with precision",
                startMs = 31_200L,
                endMs = 35_000L,
            ),
        )
        val lyrics = LyricsState.Ready(
            LyricsData(
                trackId = "debug_track",
                syncType = LyricsSyncType.SYLLABLE,
                lines = lines,
                source = "spicy_lyrics",
                attribution = "Spicy Lyrics · @debug",
            ),
        )
        debugMediaOverride.value = MediaMonitorState(hasAccess = true, activeMedia = media) to lyrics
    }

    private fun createDebugArtwork(): ImageBitmap {
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint().apply {
            shader = LinearGradient(
                0f,
                0f,
                256f,
                256f,
                intArrayOf(0xFFE89B3C.toInt(), 0xFF3B5998.toInt(), 0xFF1E2A4A.toInt()),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, 256f, 256f, paint)
        return bmp.asImageBitmap()
    }

    private fun buildUiState(
        installed: List<AppInfo>,
        settings: LauncherSettings,
        netStatus: NetworkStatus,
        bgBitmap: ImageBitmap?,
        bundle: MediaUiBundle,
    ): LauncherUiState {
        val mediaState = bundle.media
        val visibility = bundle.visibility
        val byPkg = installed.associateBy { it.packageName }
        val hiddenSet = settings.hiddenPackages

        val pinnedApps = settings.pinnedPackages
            .asSequence()
            .filter { it !in hiddenSet }
            .mapNotNull { byPkg[it] }
            .toList()

        val visibleApps = installed.filter { it.packageName !in hiddenSet }
        val visibleByPkg = visibleApps.associateBy { it.packageName }
        val orderedGrid = LinkedHashMap<String, AppInfo>(visibleApps.size)
        settings.gridOrder.forEach { pkg ->
            visibleByPkg[pkg]?.let { orderedGrid[pkg] = it }
        }
        visibleApps.forEach { appInfo ->
            orderedGrid.putIfAbsent(appInfo.packageName, appInfo)
        }

        val hiddenApps = installed.filter { it.packageName in hiddenSet }
        val showMedia = settings.mediaScreenEnabled &&
            visibility.isVisible &&
            mediaState.activeMedia != null

        return LauncherUiState(
            pinnedApps = pinnedApps,
            gridApps = orderedGrid.values.toList(),
            hiddenApps = hiddenApps,
            pinnedPackages = pinnedApps.mapTo(HashSet()) { it.packageName },
            gridColumns = settings.gridColumns,
            backgroundMode = settings.backgroundMode,
            customBackgroundPath = settings.customBackgroundPath,
            customBackgroundBitmap = bgBitmap,
            networkStatus = netStatus,
            mediaScreenEnabled = settings.mediaScreenEnabled,
            lyricsEnabled = settings.lyricsEnabled,
            hasSpicyLyricsKey = settings.spicyLyricsApiKey.isNotBlank(),
            lyricsState = if (settings.lyricsEnabled) bundle.lyrics else LyricsState.Idle,
            webSettingsUrl = bundle.webUrl,
            autoStartHapp = settings.autoStartHapp,
            mediaState = mediaState,
            showMediaScreen = showMedia,
            initializedDefaults = settings.initializedDefaults,
        )
    }
}
