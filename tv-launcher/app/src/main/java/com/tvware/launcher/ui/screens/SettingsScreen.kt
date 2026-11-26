package com.tvware.launcher.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.tvware.launcher.ui.LauncherUiState
import com.tvware.launcher.ui.theme.LauncherColors
import com.tvware.launcher.web.generateQrBitmap

@Composable
fun SettingsScreen(
    uiState: LauncherUiState,
    onRequestMediaAccess: () -> Unit,
    onRetryServer: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }

    val url = uiState.webSettingsUrl
    val qrBitmap = remember(url) {
        url?.let { generateQrBitmap(it, sizePx = 512) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LauncherBackground(
            mode = uiState.backgroundMode,
            customBitmap = uiState.customBackgroundBitmap,
            scrimAlpha = 0.72f,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 32.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Настройки лаунчера",
                    style = TextStyle(
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Medium,
                        color = LauncherColors.TextPrimary,
                    ),
                )
                ActionChip(
                    label = "Назад",
                    onClick = onBack,
                    modifier = Modifier.focusRequester(firstFocus),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                val cardShape = RoundedCornerShape(24.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.86f)
                        .clip(cardShape)
                        .background(LauncherColors.SurfaceCard.copy(alpha = 0.92f))
                        .border(1.dp, LauncherColors.BorderSubtle, cardShape)
                        .padding(32.dp),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val qrShape = RoundedCornerShape(20.dp)
                    Box(
                        modifier = Modifier
                            .size(264.dp)
                            .clip(qrShape)
                            .background(if (qrBitmap != null) Color.White else LauncherColors.SurfaceElevated)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap,
                                contentDescription = "QR-код настроек",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text(
                                text = "Нет сети",
                                style = TextStyle(
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = LauncherColors.TextMuted,
                                ),
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            text = "Откройте настройки в браузере",
                            style = TextStyle(
                                fontSize = 24.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = LauncherColors.TextPrimary,
                            ),
                        )
                        Text(
                            text = "Отсканируйте QR-код телефоном или откройте ссылку на устройстве в той же сети Wi-Fi / LAN.",
                            style = TextStyle(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Normal,
                                lineHeight = 22.sp,
                                color = LauncherColors.TextSecondary,
                            ),
                        )

                        val urlShape = RoundedCornerShape(12.dp)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(urlShape)
                                .background(LauncherColors.BgBlack.copy(alpha = 0.75f))
                                .border(1.dp, LauncherColors.BorderSubtle, urlShape)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = url ?: "Подключите ТВ к Wi-Fi или LAN и нажмите «Повторить»",
                                style = TextStyle(
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = if (url != null) FontFamily.Monospace else FontFamily.Default,
                                    color = if (url != null) LauncherColors.TextPrimary else LauncherColors.TextMuted,
                                ),
                            )
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        StatusLine(
                            label = "Доступ к медиа:",
                            value = if (uiState.mediaState.hasAccess) "активен" else "не выдан",
                            active = uiState.mediaState.hasAccess,
                        )
                        StatusLine(
                            label = "Spicy Lyrics:",
                            value = if (uiState.hasSpicyLyricsKey) "ключ задан" else "ключ не задан",
                            active = uiState.hasSpicyLyricsKey,
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (!uiState.mediaState.hasAccess) {
                                ActionChip(
                                    label = "Разрешить доступ к медиа",
                                    onClick = onRequestMediaAccess,
                                )
                            }
                            if (url == null) {
                                ActionChip(
                                    label = "Повторить",
                                    onClick = onRetryServer,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(
    label: String,
    value: String,
    active: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = LauncherColors.TextSecondary,
            ),
        )
        Text(
            text = value,
            style = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (active) LauncherColors.VpnActive else LauncherColors.TextMuted,
            ),
        )
    }
}

@Composable
private fun ActionChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)

    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f, pressedScale = 0.98f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = LauncherColors.SurfaceElevated,
            focusedContainerColor = LauncherColors.FocusSurface,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, LauncherColors.BorderSubtle), shape = shape),
            focusedBorder = Border.None,
        ),
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (isFocused) LauncherColors.FocusContent else LauncherColors.TextPrimary,
            ),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
