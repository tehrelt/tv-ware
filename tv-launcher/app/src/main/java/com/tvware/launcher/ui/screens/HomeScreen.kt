package com.tvware.launcher.ui.screens

import android.view.KeyEvent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
    onDismissMediaScreen: () -> Unit,
    onOpenMediaScreen: () -> Unit,
    onToggleMediaPlayPause: () -> Unit,
    onSkipMediaPrevious: () -> Unit,
    onSkipMediaNext: () -> Unit,
    onToggleLyrics: () -> Unit,
    onOpenMediaApp: () -> Unit,
    onMediaKeyInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstTileFocus = remember { FocusRequester() }
    var initialFocusRequested by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val density = LocalDensity.current
    val dragThresholdPx = with(density) { 48.dp.toPx() }
    val topFadePx = with(density) { 36.dp.toPx() }

    val mediaProgress by animateFloatAsState(
        targetValue = if (uiState.showMediaScreen) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "media_screen_progress",
    )

    var lastMedia by remember { mutableStateOf(uiState.mediaState.activeMedia) }
    LaunchedEffect(uiState.mediaState.activeMedia, mediaProgress) {
        if (uiState.mediaState.activeMedia != null) {
            lastMedia = uiState.mediaState.activeMedia
        } else if (mediaProgress == 0f) {
            lastMedia = null
        }
    }

    val playingArtwork = uiState.mediaState.activeMedia?.takeIf { it.isPlaying }?.artwork
    var lastGlowArtwork by remember { mutableStateOf(playingArtwork) }
    val glowAlpha by animateFloatAsState(
        targetValue = if (playingArtwork != null) 1f else 0f,
        animationSpec = tween(durationMillis = 500),
        label = "home_warp_glow_alpha",
    )
    LaunchedEffect(playingArtwork, glowAlpha) {
        if (playingArtwork != null) {
            lastGlowArtwork = playingArtwork
        } else if (glowAlpha == 0f) {
            lastGlowArtwork = null
        }
    }

    LaunchedEffect(
        uiState.initializedDefaults,
        uiState.pinnedApps.size,
        uiState.gridApps.size,
        uiState.showMediaScreen,
    ) {
        if (!uiState.showMediaScreen &&
            !initialFocusRequested &&
            uiState.initializedDefaults &&
            (uiState.pinnedApps.isNotEmpty() || uiState.gridApps.isNotEmpty())
        ) {
            initialFocusRequested = true
            withFrameNanos { }
            runCatching { firstTileFocus.requestFocus() }
        }
    }

    var wasShowingMedia by remember { mutableStateOf(uiState.showMediaScreen) }
    LaunchedEffect(uiState.showMediaScreen) {
        if (wasShowingMedia && !uiState.showMediaScreen) {
            initialFocusRequested = true
            gridState.scrollToItem(0)
            withFrameNanos { }
            runCatching { firstTileFocus.requestFocus() }
        }
        wasShowingMedia = uiState.showMediaScreen
    }

    val canScrollUpToMedia = !uiState.showMediaScreen &&
        uiState.mediaScreenEnabled &&
        uiState.mediaState.activeMedia?.isPlaying == true

    Box(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                if (!uiState.showMediaScreen) {
                    if (canScrollUpToMedia &&
                        event.type == KeyEventType.KeyDown &&
                        event.nativeKeyEvent.repeatCount == 0 &&
                        code == KeyEvent.KEYCODE_PAGE_UP &&
                        gridState.firstVisibleItemIndex == 0 &&
                        gridState.firstVisibleItemScrollOffset == 0
                    ) {
                        onOpenMediaScreen()
                        true
                    } else {
                        false
                    }
                } else if (event.type == KeyEventType.KeyDown) {
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_PAGE_DOWN -> {
                            if (event.nativeKeyEvent.repeatCount == 0) {
                                onDismissMediaScreen()
                            }
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY,
                        KeyEvent.KEYCODE_MEDIA_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_NEXT,
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                            onMediaKeyInteraction()
                            false
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
            .onKeyEvent { event ->
                if (!uiState.showMediaScreen &&
                    event.type == KeyEventType.KeyUp &&
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

        val glowArtwork = playingArtwork ?: lastGlowArtwork
        if (glowArtwork != null && glowAlpha > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(240.dp)
                    .graphicsLayer {
                        alpha = glowAlpha * 0.55f
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.White,
                                0.5f to Color.White.copy(alpha = 0.45f),
                                1f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
                WarpedArtwork(
                    artwork = glowArtwork,
                    animate = !uiState.showMediaScreen && playingArtwork != null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .focusProperties {
                    onEnter = {
                        if (uiState.showMediaScreen) cancelFocusChange()
                    }
                }
                .onPreviewKeyEvent { uiState.showMediaScreen }
                .graphicsLayer {
                    translationY = size.height * 0.14f * mediaProgress
                    alpha = (1f - mediaProgress * 0.85f).coerceIn(0f, 1f)
                    val scale = 1f - 0.04f * mediaProgress
                    scaleX = scale
                    scaleY = scale
                }
                .pointerInput(canScrollUpToMedia, dragThresholdPx) {
                    if (!canScrollUpToMedia) return@pointerInput
                    awaitPointerEventScope {
                        var tracking = false
                        var startX = 0f
                        var startY = 0f
                        var triggered = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val atTop = gridState.firstVisibleItemIndex == 0 &&
                                gridState.firstVisibleItemScrollOffset == 0
                            when (event.type) {
                                PointerEventType.Scroll -> {
                                    if (atTop) {
                                        val dy = event.changes.fold(0f) { acc, change -> acc + change.scrollDelta.y }
                                        if (dy < 0f) {
                                            onOpenMediaScreen()
                                        }
                                    }
                                }
                                PointerEventType.Press -> {
                                    val first = event.changes.firstOrNull()
                                    if (first != null && atTop) {
                                        tracking = true
                                        startX = first.position.x
                                        startY = first.position.y
                                        triggered = false
                                    } else {
                                        tracking = false
                                    }
                                }
                                PointerEventType.Move -> {
                                    if (tracking && !triggered) {
                                        val first = event.changes.firstOrNull()
                                        if (first != null && first.pressed && atTop) {
                                            val dy = first.position.y - startY
                                            val dx = kotlin.math.abs(first.position.x - startX)
                                            if (dy > dragThresholdPx && dy > dx) {
                                                triggered = true
                                                tracking = false
                                                onOpenMediaScreen()
                                            }
                                        } else {
                                            tracking = false
                                        }
                                    }
                                }
                                PointerEventType.Release,
                                PointerEventType.Exit -> {
                                    tracking = false
                                    triggered = false
                                }
                            }
                        }
                    }
                }
                .padding(start = 48.dp, top = 32.dp, end = 48.dp),
        ) {
            StatusBar(
                networkStatus = uiState.networkStatus,
                onVpnClick = onVpnClick,
                onLauncherSettingsClick = onLauncherSettingsClick,
                onSystemSettingsClick = onSystemSettingsClick,
                activeMedia = if (uiState.mediaScreenEnabled) uiState.mediaState.activeMedia else null,
                onMediaClick = onOpenMediaScreen,
                onUpFromStatusBar = onOpenMediaScreen,
            )

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val columns = uiState.gridColumns
                val tileWidth = (maxWidth - 16.dp * (columns - 1)) / columns
                val isGridScrolled = gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (isGridScrolled) {
                                Modifier
                                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                    .drawWithContent {
                                        drawContent()
                                        val fadeFraction = (topFadePx / size.height).coerceIn(0f, 1f)
                                        drawRect(
                                            brush = Brush.verticalGradient(
                                                0f to Color.Transparent,
                                                fadeFraction to Color.White,
                                            ),
                                            blendMode = BlendMode.DstIn,
                                        )
                                    }
                            } else {
                                Modifier
                            },
                        ),
                    contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp),
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

        val displayMedia = uiState.mediaState.activeMedia ?: lastMedia
        if ((uiState.showMediaScreen || mediaProgress > 0f) && displayMedia != null) {
            MediaScreen(
                media = displayMedia,
                networkStatus = uiState.networkStatus,
                backgroundMode = uiState.backgroundMode,
                customBackgroundBitmap = uiState.customBackgroundBitmap,
                isVisible = uiState.showMediaScreen,
                lyricsEnabled = uiState.lyricsEnabled,
                lyricsState = uiState.lyricsState,
                onTogglePlayPause = onToggleMediaPlayPause,
                onSkipPrevious = onSkipMediaPrevious,
                onSkipNext = onSkipMediaNext,
                onToggleLyrics = onToggleLyrics,
                onOpenApp = onOpenMediaApp,
                onScrollToApps = onDismissMediaScreen,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = -size.height * (1f - mediaProgress)
                        alpha = mediaProgress
                    },
            )
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