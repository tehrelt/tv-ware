package com.tvware.launcher.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.tvware.launcher.data.NetworkStatus
import com.tvware.launcher.ui.theme.LauncherColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun StatusBar(
    networkStatus: NetworkStatus,
    onVpnClick: () -> Unit,
    onLauncherSettingsClick: () -> Unit,
    onSystemSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
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
    val dateFormat = remember { SimpleDateFormat("EEE, d MMM", Locale.getDefault()) }
    val dateObj = remember(nowMillis) { Date(nowMillis) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = timeFormat.format(dateObj),
                style = TextStyle(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Medium,
                    color = LauncherColors.TextPrimary,
                    fontFeatureSettings = "tnum",
                ),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = dateFormat.format(dateObj),
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    color = LauncherColors.TextSecondary,
                ),
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NetworkStatusIndicator(networkStatus = networkStatus)
            VpnStatusButton(isVpnActive = networkStatus.isVpnActive, onClick = onVpnClick)
            StatusIconButton(
                description = "Настройки лаунчера",
                onClick = onLauncherSettingsClick,
            ) { tint ->
                SlidersIcon(tint = tint, modifier = Modifier.size(18.dp))
            }
            StatusIconButton(
                description = "Системные настройки",
                onClick = onSystemSettingsClick,
            ) { tint ->
                SettingsGearIcon(tint = tint, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun NetworkStatusIndicator(networkStatus: NetworkStatus) {
    val connected = networkStatus.isWifiConnected || networkStatus.isEthernetConnected
    val label = when {
        networkStatus.isWifiConnected -> "Wi-Fi"
        networkStatus.isEthernetConnected -> "LAN"
        else -> "Нет сети"
    }
    val tint = if (connected) LauncherColors.TextSecondary else LauncherColors.TextMuted

    Row(
        modifier = Modifier
            .height(36.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!networkStatus.isWifiConnected && networkStatus.isEthernetConnected) {
            EthernetIcon(tint = tint, modifier = Modifier.size(18.dp))
        } else {
            WifiSignalIcon(
                connected = networkStatus.isWifiConnected,
                level = networkStatus.wifiLevel,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = label,
            style = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = tint,
            ),
        )
    }
}

@Composable
private fun VpnStatusButton(
    isVpnActive: Boolean,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val dotColor = when {
        isVpnActive && isFocused -> LauncherColors.VpnActiveOnLight
        isVpnActive -> LauncherColors.VpnActive
        else -> LauncherColors.VpnInactive
    }
    val textColor = when {
        isFocused -> LauncherColors.FocusContent
        isVpnActive -> LauncherColors.TextPrimary
        else -> LauncherColors.TextSecondary
    }

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = LauncherColors.SurfaceCard,
            focusedContainerColor = LauncherColors.FocusSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f, pressedScale = 0.98f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, LauncherColors.BorderSubtle), shape = shape),
            focusedBorder = Border.None,
        ),
        modifier = Modifier
            .height(36.dp)
            .onFocusChanged { isFocused = it.isFocused },
    ) {
        Row(
            modifier = Modifier
                .height(36.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Text(
                text = "VPN",
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColor,
                ),
            )
        }
    }
}

@Composable
private fun StatusIconButton(
    description: String,
    onClick: () -> Unit,
    content: @Composable (Color) -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val tint = if (isFocused) LauncherColors.FocusContent else LauncherColors.TextSecondary

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = LauncherColors.SurfaceCard,
            focusedContainerColor = LauncherColors.FocusSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f, pressedScale = 0.98f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, LauncherColors.BorderSubtle), shape = shape),
            focusedBorder = Border.None,
        ),
        modifier = Modifier
            .size(36.dp)
            .semantics { contentDescription = description }
            .onFocusChanged { isFocused = it.isFocused },
    ) {
        Box(
            modifier = Modifier.size(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            content(tint)
        }
    }
}

@Composable
private fun WifiSignalIcon(
    connected: Boolean,
    level: Int,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        val activeColor = if (connected) tint else tint.copy(alpha = 0.4f)

        drawCircle(
            color = activeColor,
            radius = 1.2.dp.toPx(),
            center = Offset(w * 0.5f, h * 0.8f),
        )

        val arcs = listOf(0.36f to 1, 0.60f to 2, 0.84f to 3)
        arcs.forEach { (fraction, minLevel) ->
            val arcColor = if (connected && level >= minLevel) tint else tint.copy(alpha = 0.25f)
            val diameter = w * fraction
            val topLeft = Offset((w - diameter) / 2f, h * 0.8f - diameter / 2f)
            drawArc(
                color = arcColor,
                startAngle = -135f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = stroke,
            )
        }
    }
}

@Composable
private fun EthernetIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokePx = 1.5.dp.toPx()
        val stroke = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)

        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.16f, h * 0.22f),
            size = Size(w * 0.68f, h * 0.56f),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            style = stroke,
        )
        listOf(0.36f, 0.5f, 0.64f).forEach { xFrac ->
            drawLine(
                color = tint,
                start = Offset(w * xFrac, h * 0.22f),
                end = Offset(w * xFrac, h * 0.44f),
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun SlidersIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokePx = 1.5.dp.toPx()
        val ys = listOf(h * 0.26f, h * 0.5f, h * 0.74f)
        val knobs = listOf(w * 0.34f, w * 0.66f, w * 0.44f)
        val tickHalf = h * 0.09f
        ys.forEachIndexed { idx, y ->
            drawLine(
                color = tint,
                start = Offset(w * 0.14f, y),
                end = Offset(w * 0.86f, y),
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = tint,
                start = Offset(knobs[idx], y - tickHalf),
                end = Offset(knobs[idx], y + tickHalf),
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun SettingsGearIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val strokePx = 1.5.dp.toPx()
        val stroke = Stroke(width = strokePx, cap = StrokeCap.Round)
        val outerR = size.minDimension * 0.30f
        val innerR = size.minDimension * 0.12f
        val toothStart = size.minDimension * 0.31f
        val toothEnd = size.minDimension * 0.42f

        drawCircle(color = tint, radius = outerR, center = Offset(cx, cy), style = stroke)
        drawCircle(color = tint, radius = innerR, center = Offset(cx, cy), style = stroke)

        for (i in 0 until 8) {
            val angle = Math.toRadians(i * 45.0)
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()
            drawLine(
                color = tint,
                start = Offset(cx + toothStart * cosA, cy + toothStart * sinA),
                end = Offset(cx + toothEnd * cosA, cy + toothEnd * sinA),
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
        }
    }
}