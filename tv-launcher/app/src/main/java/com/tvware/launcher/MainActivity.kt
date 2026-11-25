package com.tvware.launcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tvware.launcher.data.AppInfo
import com.tvware.launcher.ui.LauncherViewModel
import com.tvware.launcher.ui.components.AppContextDialog
import com.tvware.launcher.ui.screens.HomeScreen
import com.tvware.launcher.ui.screens.SettingsScreen
import com.tvware.launcher.ui.theme.TvLauncherTheme

private data class TileMenu(val app: AppInfo, val pinnedRow: Boolean, val index: Int, val count: Int)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TvLauncherTheme {
                val model: LauncherViewModel = viewModel()
                val state by model.uiState.collectAsStateWithLifecycle()
                var settings by remember { mutableStateOf(false) }
                var menu by remember { mutableStateOf<TileMenu?>(null) }
                val picker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
                    uri?.let(model::importCustomBackground)
                }
                BackHandler(enabled = !settings && menu == null) {}
                if (settings) {
                    SettingsScreen(state, model::setBackgroundMode, {
                        runCatching { picker.launch(arrayOf("image/*")) }
                            .onFailure { message("Не удалось открыть выбор файла") }
                    }, model::clearCustomBackground, model::setGridColumns, model::unhideApp, { settings = false })
                } else {
                    HomeScreen(
                        state, model::getCachedArtwork, model::loadArtwork,
                        onAppClick = { if (!model.launchApp(it)) message("Не удалось открыть ${it.label}") },
                        onAppLongClick = { app, pinned, index, count -> menu = TileMenu(app, pinned, index, count) },
                        onVpnClick = { if (!model.launchHapp()) message("Не удалось открыть Happ") },
                        onLauncherSettingsClick = { settings = true },
                        onSystemSettingsClick = { openSettings(Intent(Settings.ACTION_SETTINGS)) },
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
    }

    private fun openSettings(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { message("Системные настройки недоступны") }
    }

    private fun message(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
