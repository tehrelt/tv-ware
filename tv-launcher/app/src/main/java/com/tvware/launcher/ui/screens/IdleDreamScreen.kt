package com.tvware.launcher.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.tvware.launcher.data.BackgroundMode
import com.tvware.launcher.data.MediaSessionInfo
import com.tvware.launcher.data.NetworkStatus
import com.tvware.launcher.ui.components.NetworkStatusIndicator
import com.tvware.launcher.ui.theme.LauncherColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val DriftPeriodSteps = 12
private const val DriftIntervalMs = 30_000L

internal fun idleDriftOffset(step: Int): Pair<Float, Float> {
    val angle = 2.0 * PI * (step.mod(DriftPeriodSteps)) / DriftPeriodSteps
    val dx = (sin(angle) * 18.0).toFloat()
    val dy = (cos(angle * 2.0) * 12.0).toFloat()
    return dx to dy
}

internal fun shouldShowDreamMedia(media: MediaSessionInfo?): Boolean =
    media != null && (media.isPlaying || media.isPaused)

@Composable
fun IdleDreamScreen(
    media: MediaSessionInfo?,
    networkStatus: NetworkStatus,
    backgroundMode: BackgroundMode,
    customBackgroundBitmap: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var driftStep by remember { mutableIntStateOf(0) }

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

    LaunchedEffect(Unit) {
        while (true) {
            delay(DriftIntervalMs)
            nowMillis = System.currentTimeMillis()
            driftStep = (driftStep + 1) % DriftPeriodSteps
        }
    }

    val (targetX, targetY) = idleDriftOffset(driftStep)
    val driftX by animateFloatAsState(targetValue = targetX, animationSpec = tween(1_200), label = "dream_dx")
    val driftY by animateFloatAsState(targetValue = targetY, animationSpec = tween(1_200), label = "dream_dy")

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()) }
    val nowDate = Date(nowMillis)
    val timeText = timeFormat.format(nowDate)
    val dateText = dateFormat.format(nowDate)

    Box(modifier = modifier.fillMaxSize()) {
        if (shouldShowDreamMedia(media) && media != null) {
            MediaBackdrop(
                artwork = media.artwork,
                animate = true,
                backgroundMode = backgroundMode,
                customBackgroundBitmap = customBackgroundBitmap,
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = driftX.dp.toPx()
                        translationY = driftY.dp.toPx()
                    }
                    .padding(horizontal = 56.dp, vertical = 40.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        NetworkStatusIndicator(networkStatus = networkStatus)
                        Text(
                            text = dateText,
                            style = TextStyle(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Normal,
                                color = LauncherColors.TextSecondary,
                            ),
                        )
                        Text(
                            text = timeText,
                            style = TextStyle(
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Medium,
                                color = LauncherColors.TextPrimary,
                                fontFeatureSettings = "tnum",
                            ),
                        )
                    }
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
                                fontSize = 34.sp,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 40.sp,
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
                                isVisible = true,
                            )
                        }
                    }
                }
            }
        } else {
            LauncherBackground(
                mode = backgroundMode,
                customBitmap = customBackgroundBitmap,
                scrimAlpha = 0.68f,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                LauncherColors.Accent.copy(alpha = 0.12f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .graphicsLayer {
                        translationX = driftX.dp.toPx()
                        translationY = driftY.dp.toPx()
                    }
                    .padding(horizontal = 64.dp, vertical = 56.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = timeText,
                    style = TextStyle(
                        fontSize = 96.sp,
                        fontWeight = FontWeight.Light,
                        color = LauncherColors.TextPrimary,
                        fontFeatureSettings = "tnum",
                    ),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = dateText,
                        style = TextStyle(
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Normal,
                            color = LauncherColors.TextSecondary,
                        ),
                    )
                    NetworkStatusIndicator(networkStatus = networkStatus)
                }
            }
        }
    }
}
