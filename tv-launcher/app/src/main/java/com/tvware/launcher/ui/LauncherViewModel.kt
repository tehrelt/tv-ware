package com.tvware.launcher.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvware.launcher.LauncherApp
import com.tvware.launcher.data.AppArtwork
import com.tvware.launcher.data.AppInfo
import com.tvware.launcher.data.BackgroundMode
import com.tvware.launcher.data.LauncherSettings
import com.tvware.launcher.data.LyricsState
import com.tvware.launcher.data.MediaMonitorState
import com.tvware.launcher.data.MediaScreenReducer
import com.tvware.launcher.data.MediaScreenVisibilityState
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
        combine(mediaMonitor.state, mediaVisibility, lyricsState, webSettingsUrl) { media, visibility, lyrics, webUrl ->
            MediaUiBundle(media, visibility, lyrics, webUrl)
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
            lyricsState = bundle.lyrics,
            webSettingsUrl = bundle.webUrl,
            autoStartHapp = settings.autoStartHapp,
            mediaState = mediaState,
            showMediaScreen = showMedia,
            initializedDefaults = settings.initializedDefaults,
        )
    }
}
