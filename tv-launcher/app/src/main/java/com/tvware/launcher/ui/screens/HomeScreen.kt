package com.tvware.launcher.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.tvware.launcher.data.AppArtwork
import com.tvware.launcher.data.AppInfo
import com.tvware.launcher.data.BackgroundMode
import com.tvware.launcher.ui.LauncherUiState
import com.tvware.launcher.ui.components.AppTile
import com.tvware.launcher.ui.components.StatusBar
import com.tvware.launcher.ui.theme.LauncherColors

@Composable
fun HomeScreen(
    uiState: LauncherUiState,
    getCachedArtwork: (AppInfo) -> AppArtwork?,
    loadArtwork: suspend (AppInfo) -> AppArtwork?,
    onAppClick: (AppInfo) -> Unit,
    onAppLongClick: (AppInfo, Boolean, Int, Int) -> Unit,
    onVpnClick: () -> Unit,
    onLauncherSettingsClick: () -> Unit,
    onSystemSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstTileFocus = remember { FocusRequester() }
    var initialFocusRequested by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.initializedDefaults, uiState.pinnedApps.size, uiState.gridApps.size) {
        if (!initialFocusRequested && uiState.initializedDefaults && (uiState.pinnedApps.isNotEmpty() || uiState.gridApps.isNotEmpty())) {
            initialFocusRequested = true
            runCatching { firstTileFocus.requestFocus() }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU
                ) {
                    onLauncherSettingsClick()
                    true
                } else {
                    false
                }
            },
    ) {
        LauncherBackground(
            mode = uiState.backgroundMode,
            customBitmap = uiState.customBackgroundBitmap,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 32.dp),
        ) {
            StatusBar(
                networkStatus = uiState.networkStatus,
                onVpnClick = onVpnClick,
                onLauncherSettingsClick = onLauncherSettingsClick,
                onSystemSettingsClick = onSystemSettingsClick,
            )

            Spacer(modifier = Modifier.height(24.dp))

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val columns = uiState.gridColumns
                val tileWidth = (maxWidth - 16.dp * (columns - 1)) / columns
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                if (uiState.pinnedApps.isNotEmpty()) {
                    item(
                        key = "pinned_section",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionTitle(text = "Избранное")
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                itemsIndexed(
                                    items = uiState.pinnedApps,
                                    key = { _, app -> "pin_${app.packageName}" },
                                ) { index, app ->
                                    val tileModifier = if (index == 0) {
                                        Modifier
                                            .width(tileWidth)
                                            .focusRequester(firstTileFocus)
                                    } else {
                                        Modifier.width(tileWidth)
                                    }
                                    AppTile(
                                        app = app,
                                        getCachedArtwork = getCachedArtwork,
                                        loadArtwork = loadArtwork,
                                        onClick = { onAppClick(app) },
                                        onLongClick = {
                                            onAppLongClick(app, true, index, uiState.pinnedApps.size)
                                        },
                                        modifier = tileModifier,
                                    )
                                }
                            }
                        }
                    }
                }

                item(
                    key = "grid_header",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    SectionTitle(
                        text = "Все приложения",
                        modifier = Modifier.padding(top = if (uiState.pinnedApps.isNotEmpty()) 16.dp else 0.dp),
                    )
                }

                itemsIndexed(
                    items = uiState.gridApps,
                    key = { _, app -> "grid_${app.packageName}" },
                ) { index, app ->
                    val tileModifier = if (uiState.pinnedApps.isEmpty() && index == 0) {
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(firstTileFocus)
                    } else {
                        Modifier.fillMaxWidth()
                    }
                    AppTile(
                        app = app,
                        getCachedArtwork = getCachedArtwork,
                        loadArtwork = loadArtwork,
                        onClick = { onAppClick(app) },
                        onLongClick = {
                            onAppLongClick(app, false, index, uiState.gridApps.size)
                        },
                        modifier = tileModifier,
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = TextStyle(
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = LauncherColors.TextSecondary,
        ),
        modifier = modifier,
    )
}

@Composable
internal fun LauncherBackground(
    mode: BackgroundMode,
    customBitmap: ImageBitmap?,
    scrimAlpha: Float = 0.52f,
) {
    when (mode) {
        BackgroundMode.BLACK -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LauncherColors.BgBlack),
            )
        }

        BackgroundMode.GRADIENT -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                LauncherColors.BgGraphiteTop,
                                LauncherColors.BgGraphiteBottom,
                            ),
                        ),
                    ),
            )
        }

        BackgroundMode.CUSTOM_FILE -> {
            if (customBitmap != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Image(
                        bitmap = customBitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = scrimAlpha)),
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LauncherColors.BgBlack),
                )
            }
        }
    }
}