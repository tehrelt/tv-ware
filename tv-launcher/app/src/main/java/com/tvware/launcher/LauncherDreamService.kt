package com.tvware.launcher

import android.service.dreams.DreamService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.tvware.launcher.data.BackgroundMode
import com.tvware.launcher.data.LauncherSettings
import com.tvware.launcher.data.MediaMonitorState
import com.tvware.launcher.data.NetworkStatus
import com.tvware.launcher.ui.screens.IdleDreamScreen
import com.tvware.launcher.ui.theme.TvLauncherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LauncherDreamService : DreamService(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true

        val app = application as LauncherApp
        window.decorView.setViewTreeLifecycleOwner(this)
        window.decorView.setViewTreeSavedStateRegistryOwner(this)

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                TvLauncherTheme {
                    val mediaState by app.mediaMonitor.state.collectAsStateWithLifecycle(
                        initialValue = MediaMonitorState(),
                    )
                    val networkStatus by app.networkMonitor.status.collectAsStateWithLifecycle(
                        initialValue = NetworkStatus(),
                    )
                    val settings by app.preferences.settingsFlow.collectAsStateWithLifecycle(
                        initialValue = LauncherSettings(),
                    )
                    val customBg by produceState<ImageBitmap?>(
                        initialValue = null,
                        settings.backgroundMode,
                        settings.customBackgroundPath,
                    ) {
                        val path = settings.customBackgroundPath
                        value = if (settings.backgroundMode == BackgroundMode.CUSTOM_FILE && !path.isNullOrBlank()) {
                            withContext(Dispatchers.IO) {
                                app.iconCache.loadCustomBackground(path)
                            }
                        } else {
                            null
                        }
                    }

                    IdleDreamScreen(
                        media = mediaState.activeMedia,
                        networkStatus = networkStatus,
                        backgroundMode = settings.backgroundMode,
                        customBackgroundBitmap = customBg,
                    )
                }
            }
        }

        setContentView(composeView)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        (application as? LauncherApp)?.mediaMonitor?.refreshAccessAndSessions()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onDreamingStopped() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onDetachedFromWindow()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }
}
