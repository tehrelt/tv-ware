package com.tvware.launcher

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.tvware.launcher.data.AppInfo
import com.tvware.launcher.data.MediaNotificationListenerService
import com.tvware.launcher.ui.LauncherViewModel
import com.tvware.launcher.ui.components.AppContextDialog
import com.tvware.launcher.ui.screens.HomeScreen
import com.tvware.launcher.ui.screens.SettingsScreen
import com.tvware.launcher.ui.theme.TvLauncherTheme

private data class TileMenu(val app: AppInfo, val pinnedRow: Boolean, val index: Int, val count: Int)

class MainActivity : ComponentActivity() {

    private val model: LauncherViewModel by viewModels()
    private var closeOverlays: (() -> Unit)? = null
    private var isSettingsOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.maybeAutoStartHapp()
            }
        }
        setContent {
            TvLauncherTheme {
                val state by model.uiState.collectAsStateWithLifecycle()
                var settings by remember { mutableStateOf(false) }
                var menu by remember { mutableStateOf<TileMenu?>(null) }
                isSettingsOpen = settings
                closeOverlays = {
                    settings = false
                    menu = null
                }
                LaunchedEffect(state.showMediaScreen) {
                    if (state.showMediaScreen) {
                        menu = null
                    }
                }
                LaunchedEffect(settings) {
                    if (settings) {
                        model.startWebSettingsServer()
                    }
                }
                BackHandler(enabled = !settings && menu == null && !state.showMediaScreen) {}
                if (settings) {
                    SettingsScreen(
                        uiState = state,
                        onRequestMediaAccess = ::openMediaAccessSettings,
                        onRetryServer = model::startWebSettingsServer,
                        onBack = { settings = false },
                    )
                } else {
                    HomeScreen(
                        uiState = state,
                        getCachedArtwork = model::getCachedArtwork,
                        loadArtwork = model::loadArtwork,
                        onAppClick = { if (!model.launchApp(it)) message("Не удалось открыть ${it.label}") },
                        onAppLongClick = { app, pinned, index, count -> menu = TileMenu(app, pinned, index, count) },
                        onVpnClick = { if (!model.launchHapp()) message("Не удалось открыть Happ") },
                        onLauncherSettingsClick = { settings = true },
                        onSystemSettingsClick = { openSettings(Intent(Settings.ACTION_SETTINGS)) },
                        onDismissMediaScreen = model::dismissMediaScreen,
                        onOpenMediaScreen = model::openMediaScreen,
                        onToggleMediaPlayPause = model::toggleMediaPlayPause,
                        onSkipMediaPrevious = model::skipMediaPrevious,
                        onSkipMediaNext = model::skipMediaNext,
                        onToggleLyrics = model::toggleLyricsEnabled,
                        onOpenMediaApp = {
                            if (!model.launchMediaApp()) message("Не удалось открыть приложение")
                        },
                        onMediaKeyInteraction = model::markMediaInteraction,
                    )
                }
                menu?.let { target ->
                    val pkg = target.app.packageName
                    AppContextDialog(
                        app = target.app,
                        isPinned = pkg in state.pinnedPackages,
                        canMoveLeft = target.index > 0,
                        canMoveRight = target.index + 1 < target.count,
                        onTogglePin = { model.togglePin(pkg); menu = null },
                        onMoveLeft = { model.moveApp(pkg, target.pinnedRow, -1); menu = null },
                        onMoveRight = { model.moveApp(pkg, target.pinnedRow, 1); menu = null },
                        onHide = { model.hideApp(pkg); menu = null },
                        onAppInfo = {
                            menu = null
                            openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))
                        },
                        onDismiss = { menu = null },
                    )
                }
            }
        }
        handleDebugIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        model.onLauncherResumed(allowAutoShow = !isSettingsOpen)
    }

    override fun onStop() {
        model.stopWebSettingsServer()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            isSettingsOpen = false
            closeOverlays?.invoke()
            model.onLauncherResumed(allowAutoShow = true)
        }
        handleDebugIntent(intent)
    }

    private fun handleDebugIntent(intent: Intent?) {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        if (intent?.action == "com.tvware.launcher.DEBUG_MEDIA_PREVIEW") {
            val enabled = intent.getBooleanExtra("enabled", true)
            val posMs = intent.getLongExtra("position_ms", 12_000L)
            val isPlaying = intent.getBooleanExtra("is_playing", true)
            model.setDebugMediaPreview(enabled = enabled, positionMs = posMs, isPlaying = isPlaying)
        }
    }

    private fun openMediaAccessSettings() {
        val component = ComponentName(this, MediaNotificationListenerService::class.java).flattenToString()
        val detailIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
            putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
        }
        val listIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        val opened = runCatching { startActivity(detailIntent) }.isSuccess ||
            runCatching { startActivity(listIntent) }.isSuccess
        if (!opened) {
            message("Откройте доступ к уведомлениям в настройках или через ADB")
        }
    }

    private fun openSettings(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { message("Системные настройки недоступны") }
    }

    private fun message(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
