package com.tvware.launcher.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.tvware.launcher.data.BackgroundMode
import com.tvware.launcher.data.MediaSessionInfo
import com.tvware.launcher.data.NetworkStatus
import com.tvware.launcher.data.computePlaybackPositionMs
import com.tvware.launcher.data.formatMediaDuration
import com.tvware.launcher.ui.components.NetworkStatusIndicator
import com.tvware.launcher.ui.theme.LauncherColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val MediaControlsWakeKeys = setOf(
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
)

internal fun mediaControlsHideAfterMs(isVisible: Boolean, isPlaying: Boolean): Long? =
    if (isVisible && isPlaying) 5_000L else null

@Composable
fun MediaScreen(
    media: MediaSessionInfo,
    networkStatus: NetworkStatus,
    backgroundMode: BackgroundMode,
    customBackgroundBitmap: ImageBitmap?,
    isVisible: Boolean,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onOpenApp: () -> Unit,
    onScrollToApps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playPauseFocus = remember { FocusRequester() }
    val idleFocus = remember { FocusRequester() }
    var controlsVisible by remember(isVisible) { mutableStateOf(true) }
    var lastInteraction by remember(isVisible) { mutableLongStateOf(0L) }
    var wakeKey by remember(isVisible) { mutableStateOf<Int?>(null) }
    val dragThresholdPx = with(LocalDensity.current) { 48.dp.toPx() }

    BackHandler(enabled = isVisible) {
        onScrollToApps()
    }

    LaunchedEffect(isVisible, media.isPlaying, lastInteraction) {
        if (!isVisible) return@LaunchedEffect
        controlsVisible = true
        val timeout = mediaControlsHideAfterMs(isVisible, media.isPlaying) ?: return@LaunchedEffect
        delay(timeout)
        if (idleFocus.requestFocus()) controlsVisible = false
    }

    LaunchedEffect(isVisible, controlsVisible, media.canPlayPause) {
        if (isVisible && controlsVisible) {
            val focused = runCatching { playPauseFocus.requestFocus() }.getOrDefault(false)
            if (!focused) {
                withFrameNanos { }
                runCatching { playPauseFocus.requestFocus() }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusProperties {
                onEnter = {
                    if (!isVisible) cancelFocusChange()
                }
            }
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                if (event.type == KeyEventType.KeyUp && wakeKey == code) {
                    wakeKey = null
                    true
                } else if (isVisible && event.type == KeyEventType.KeyDown &&
                    code in MediaControlsWakeKeys
                ) {
                    val consume = !controlsVisible || wakeKey == code
                    if (consume) wakeKey = code
                    lastInteraction++
                    consume || code == KeyEvent.KEYCODE_DPAD_UP
                } else {
                    false
                }
            }
            .pointerInput(isVisible, dragThresholdPx) {
                if (!isVisible) return@pointerInput
                awaitPointerEventScope {
                    var tracking = false
                    var startX = 0f
                    var startY = 0f
                    var triggered = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        when (event.type) {
                            PointerEventType.Scroll -> {
                                val dy = event.changes.fold(0f) { acc, change -> acc + change.scrollDelta.y }
                                if (dy > 0f) {
                                    onScrollToApps()
                                }
                            }
                            PointerEventType.Press -> {
                                lastInteraction++
                                val first = event.changes.firstOrNull()
                                if (first != null) {
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
                                    if (first != null && first.pressed) {
                                        val dy = startY - first.position.y
                                        val dx = kotlin.math.abs(first.position.x - startX)
                                        if (dy > dragThresholdPx && dy > dx) {
                                            triggered = true
                                            tracking = false
                                            onScrollToApps()
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
            },
    ) {
        Spacer(Modifier.size(1.dp).focusRequester(idleFocus).focusable(enabled = isVisible))

        MediaBackdrop(
            artwork = media.artwork,
            animate = isVisible,
            backgroundMode = backgroundMode,
            customBackgroundBitmap = customBackgroundBitmap,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 32.dp),
        ) {
            MediaChromeSlot(visible = controlsVisible, height = 44.dp) {
                MediaTopBar(
                    appLabel = media.appLabel,
                    isPlaying = media.isPlaying,
                    networkStatus = networkStatus,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(48.dp),
            ) {
                MediaArtworkCard(
                    artwork = media.artwork,
                    title = media.title,
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = media.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            fontSize = 32.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 38.sp,
                            color = LauncherColors.TextPrimary,
                        ),
                    )

                    if (media.artist.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = media.artist,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Normal,
                                color = LauncherColors.TextSecondary,
                            ),
                        )
                    }

                    if (media.album.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = media.album,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Normal,
                                color = LauncherColors.TextMuted,
                            ),
                        )
                    }

                    if (media.durationMs > 0L) {
                        Spacer(modifier = Modifier.height(24.dp))
                        MediaProgressSection(
                            media = media,
                            isVisible = isVisible,
                        )
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    MediaChromeSlot(visible = controlsVisible, height = 60.dp) {
                        MediaControlsRow(
                            media = media,
                            isVisible = isVisible && controlsVisible,
                            playPauseFocus = playPauseFocus,
                            onTogglePlayPause = onTogglePlayPause,
                            onSkipPrevious = onSkipPrevious,
                            onSkipNext = onSkipNext,
                            onOpenApp = onOpenApp,
                        )
                    }
                }
            }

            MediaChromeSlot(visible = controlsVisible, height = 28.dp) {
                MediaScrollCue(enabled = isVisible && controlsVisible, onClick = onScrollToApps)
            }
        }
    }
}

@Composable
internal fun MediaBackdrop(
    artwork: ImageBitmap?,
    animate: Boolean,
    backgroundMode: BackgroundMode,
    customBackgroundBitmap: ImageBitmap?,
) {
    LauncherBackground(
        mode = backgroundMode,
        customBitmap = customBackgroundBitmap,
        scrimAlpha = 0.72f,
    )

    if (artwork != null) {
        WarpedArtwork(
            artwork = artwork,
            animate = animate,
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.72f)
                .blur(72.dp, edgeTreatment = BlurredEdgeTreatment.Rectangle),
        )
    }
    val scrim = if (artwork != null) {
        Brush.verticalGradient(
            colors = listOf(
                LauncherColors.BgBlack.copy(alpha = 0.35f),
                LauncherColors.BgBlack.copy(alpha = 0.57f),
                LauncherColors.BgBlack.copy(alpha = 0.85f),
            ),
        )
    } else {
        Brush.radialGradient(listOf(LauncherColors.Accent.copy(alpha = 0.14f), Color.Transparent))
    }
    Box(Modifier.fillMaxSize().background(scrim))
}

@Composable
private fun MediaTopBar(
    appLabel: String,
    isPlaying: Boolean,
    networkStatus: NetworkStatus,
) {
    val context = LocalContext.current
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                nowMillis = System.currentTimeMillis()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        context.registerReceiver(receiver, filter)
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val badgeShape = RoundedCornerShape(10.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .focusProperties { canFocus = false },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier
                .clip(badgeShape)
                .background(LauncherColors.SurfaceCard.copy(alpha = 0.72f))
                .border(1.dp, LauncherColors.BorderSubtle, badgeShape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isPlaying) LauncherColors.VpnActive else LauncherColors.TextMuted),
            )
            Text(
                text = appLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = LauncherColors.TextPrimary,
                ),
            )
            Text(
                text = if (isPlaying) "• Воспроизведение" else "• Пауза",
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = LauncherColors.TextSecondary,
                ),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NetworkStatusIndicator(networkStatus = networkStatus)
            Text(
                text = timeFormat.format(Date(nowMillis)),
                style = TextStyle(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    color = LauncherColors.TextPrimary,
                    fontFeatureSettings = "tnum",
                ),
            )
        }
    }
}

@Composable
internal fun MediaArtworkCard(artwork: ImageBitmap?, title: String) {
    val cardShape = RoundedCornerShape(20.dp)
    val cardModifier = if (artwork != null && artwork.width > artwork.height * 1.25f) {
        Modifier.width(360.dp).aspectRatio(16f / 9f)
    } else {
        Modifier.size(264.dp)
    }
    Box(
        modifier = cardModifier
            .clip(cardShape)
            .background(LauncherColors.SurfaceCard.copy(alpha = 0.85f))
            .border(1.dp, LauncherColors.BorderSubtle, cardShape),
        contentAlignment = Alignment.Center,
    ) {
        if (artwork != null) {
            Image(
                bitmap = artwork,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            MusicNoteIcon(tint = LauncherColors.TextMuted, modifier = Modifier.size(64.dp))
        }
    }
}

@Composable
internal fun MediaProgressSection(
    media: MediaSessionInfo,
    isVisible: Boolean,
) {
    var currentPosMs by remember(
        media.sessionKey,
        media.positionMs,
        media.positionUpdateTimeMs,
        media.playbackSpeed,
        media.durationMs,
        media.isPlaying,
    ) {
        mutableLongStateOf(
            computePlaybackPositionMs(
                positionMs = media.positionMs,
                durationMs = media.durationMs,
                playbackSpeed = media.playbackSpeed,
                lastUpdateElapsedMs = media.positionUpdateTimeMs,
                nowElapsedMs = SystemClock.elapsedRealtime(),
                isPlaying = media.isPlaying,
            ),
        )
    }

    LaunchedEffect(
        isVisible,
        media.sessionKey,
        media.isPlaying,
        media.positionMs,
        media.positionUpdateTimeMs,
        media.playbackSpeed,
        media.durationMs,
    ) {
        while (isVisible && media.isPlaying) {
            currentPosMs = computePlaybackPositionMs(
                positionMs = media.positionMs,
                durationMs = media.durationMs,
                playbackSpeed = media.playbackSpeed,
                lastUpdateElapsedMs = media.positionUpdateTimeMs,
                nowElapsedMs = SystemClock.elapsedRealtime(),
                isPlaying = true,
            )
            delay(500L)
        }
    }

    val fraction = if (media.durationMs > 0L) {
        (currentPosMs.toFloat() / media.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.16f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(2.dp))
                    .background(LauncherColors.TextPrimary),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatMediaDuration(currentPosMs),
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = LauncherColors.TextMuted,
                    fontFeatureSettings = "tnum",
                ),
            )
            Text(
                text = formatMediaDuration(media.durationMs),
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = LauncherColors.TextMuted,
                    fontFeatureSettings = "tnum",
                ),
            )
        }
    }
}

@Composable
private fun MediaChromeSlot(visible: Boolean, height: Dp, content: @Composable () -> Unit) {
    Box(Modifier.height(height)) {
        AnimatedVisibility(visible = visible, enter = fadeIn(tween(180)), exit = fadeOut(tween(240))) {
            content()
        }
    }
}

@Composable
private fun MediaControlsRow(
    media: MediaSessionInfo,
    isVisible: Boolean,
    playPauseFocus: FocusRequester,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onOpenApp: () -> Unit,
) {
    Row(
        modifier = Modifier.focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (media.canSkipPrevious) {
            MediaCircleButton(
                description = "Предыдущий трек",
                size = 52.dp,
                canFocus = isVisible,
                onClick = onSkipPrevious,
            ) { tint ->
                SkipIcon(forward = false, tint = tint, modifier = Modifier.size(20.dp))
            }
        }

        if (media.canPlayPause) {
            MediaCircleButton(
                description = if (media.isPlaying) "Пауза" else "Воспроизвести",
                size = 60.dp,
                canFocus = isVisible,
                onClick = onTogglePlayPause,
                modifier = Modifier.focusRequester(playPauseFocus),
            ) { tint ->
                if (media.isPlaying) {
                    PauseIcon(tint = tint, modifier = Modifier.size(22.dp))
                } else {
                    PlayIcon(tint = tint, modifier = Modifier.size(22.dp))
                }
            }
        }

        if (media.canSkipNext) {
            MediaCircleButton(
                description = "Следующий трек",
                size = 52.dp,
                canFocus = isVisible,
                onClick = onSkipNext,
            ) { tint ->
                SkipIcon(forward = true, tint = tint, modifier = Modifier.size(20.dp))
            }
        }

        MediaPillButton(
            label = "Открыть",
            canFocus = isVisible,
            onClick = onOpenApp,
            modifier = if (!media.canPlayPause) Modifier.focusRequester(playPauseFocus) else Modifier,
        )
    }
}

@Composable
private fun MediaCircleButton(
    description: String,
    size: Dp,
    canFocus: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Color) -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val tint = if (isFocused) LauncherColors.FocusContent else LauncherColors.TextPrimary

    Surface(
        onClick = onClick,
        enabled = canFocus,
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = LauncherColors.SurfaceElevated.copy(alpha = 0.85f),
            focusedContainerColor = LauncherColors.FocusSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f, pressedScale = 0.96f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, LauncherColors.BorderSubtle), shape = CircleShape),
            focusedBorder = Border.None,
        ),
        modifier = modifier
            .size(size)
            .focusProperties { this.canFocus = canFocus }
            .semantics { contentDescription = description }
            .onFocusChanged { isFocused = it.isFocused },
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            content(tint)
        }
    }
}

@Composable
private fun MediaPillButton(
    label: String,
    canFocus: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(26.dp)
    val tint = if (isFocused) LauncherColors.FocusContent else LauncherColors.TextPrimary

    Surface(
        onClick = onClick,
        enabled = canFocus,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = LauncherColors.SurfaceCard.copy(alpha = 0.85f),
            focusedContainerColor = LauncherColors.FocusSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f, pressedScale = 0.97f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, LauncherColors.BorderSubtle), shape = shape),
            focusedBorder = Border.None,
        ),
        modifier = modifier
            .height(52.dp)
            .focusProperties { this.canFocus = canFocus }
            .onFocusChanged { isFocused = it.isFocused },
    ) {
        Row(
            modifier = Modifier
                .height(52.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OpenExternalIcon(tint = tint, modifier = Modifier.size(16.dp))
            Text(
                text = label,
                style = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = tint,
                ),
            )
        }
    }
}

@Composable
private fun MediaScrollCue(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .focusProperties { canFocus = false },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { onClick() }
                }
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ChevronDownIcon(
                tint = LauncherColors.TextMuted,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "Приложения",
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = LauncherColors.TextMuted,
                ),
            )
        }
    }
}

@Composable
private fun PlayIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.26f, h * 0.16f)
            lineTo(w * 0.84f, h * 0.50f)
            lineTo(w * 0.26f, h * 0.84f)
            close()
        }
        drawPath(path = path, color = tint)
    }
}

@Composable
private fun PauseIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val barW = w * 0.24f
        val barH = h * 0.68f
        val top = h * 0.16f
        val radius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.20f, top),
            size = Size(barW, barH),
            cornerRadius = radius,
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.56f, top),
            size = Size(barW, barH),
            cornerRadius = radius,
        )
    }
}

@Composable
private fun SkipIcon(forward: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val barW = w * 0.12f
        val barH = h * 0.64f
        val top = h * 0.18f
        val radius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
        if (forward) {
            val path = Path().apply {
                moveTo(w * 0.18f, h * 0.20f)
                lineTo(w * 0.68f, h * 0.50f)
                lineTo(w * 0.18f, h * 0.80f)
                close()
            }
            drawPath(path = path, color = tint)
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.72f, top),
                size = Size(barW, barH),
                cornerRadius = radius,
            )
        } else {
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.16f, top),
                size = Size(barW, barH),
                cornerRadius = radius,
            )
            val path = Path().apply {
                moveTo(w * 0.82f, h * 0.20f)
                lineTo(w * 0.32f, h * 0.50f)
                lineTo(w * 0.82f, h * 0.80f)
                close()
            }
            drawPath(path = path, color = tint)
        }
    }
}

@Composable
private fun OpenExternalIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val boxPath = Path().apply {
            moveTo(w * 0.52f, h * 0.20f)
            lineTo(w * 0.22f, h * 0.20f)
            lineTo(w * 0.22f, h * 0.78f)
            lineTo(w * 0.80f, h * 0.78f)
            lineTo(w * 0.80f, h * 0.48f)
        }
        drawPath(path = boxPath, color = tint, style = stroke)
        drawLine(
            color = tint,
            start = Offset(w * 0.48f, h * 0.52f),
            end = Offset(w * 0.82f, h * 0.18f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        val arrowPath = Path().apply {
            moveTo(w * 0.58f, h * 0.18f)
            lineTo(w * 0.82f, h * 0.18f)
            lineTo(w * 0.82f, h * 0.42f)
        }
        drawPath(path = arrowPath, color = tint, style = stroke)
    }
}

@Composable
private fun MusicNoteIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(
            color = tint,
            radius = w * 0.12f,
            center = Offset(w * 0.30f, h * 0.74f),
        )
        drawCircle(
            color = tint,
            radius = w * 0.12f,
            center = Offset(w * 0.70f, h * 0.66f),
        )
        val stemPath = Path().apply {
            moveTo(w * 0.40f, h * 0.72f)
            lineTo(w * 0.40f, h * 0.24f)
            lineTo(w * 0.80f, h * 0.16f)
            lineTo(w * 0.80f, h * 0.64f)
        }
        drawPath(path = stemPath, color = tint, style = stroke)
    }
}

@Composable
private fun ChevronDownIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(w * 0.20f, h * 0.35f)
            lineTo(w * 0.50f, h * 0.68f)
            lineTo(w * 0.80f, h * 0.35f)
        }
        drawPath(path = path, color = tint, style = stroke)
    }
}
