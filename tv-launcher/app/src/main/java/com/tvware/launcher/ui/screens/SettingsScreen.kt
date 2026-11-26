package com.tvware.launcher.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.tvware.launcher.data.BackgroundMode
import com.tvware.launcher.ui.LauncherUiState
import com.tvware.launcher.ui.theme.LauncherColors

@Composable
fun SettingsScreen(
    uiState: LauncherUiState,
    onSelectBackgroundMode: (BackgroundMode) -> Unit,
    onPickCustomBackground: () -> Unit,
    onClearCustomBackground: () -> Unit,
    onSelectGridColumns: (Int) -> Unit,
    onSetMediaScreenEnabled: (Boolean) -> Unit,
    onRequestMediaAccess: () -> Unit,
    onUnhideApp: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LauncherBackground(
            mode = uiState.backgroundMode,
            customBitmap = uiState.customBackgroundBitmap,
            scrimAlpha = 0.72f,
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 32.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
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
                    OptionChip(
                        label = "Назад",
                        selected = false,
                        onClick = onBack,
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsSectionTitle("Фон")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OptionChip(
                            label = "Чёрный",
                            selected = uiState.backgroundMode == BackgroundMode.BLACK,
                            onClick = { onSelectBackgroundMode(BackgroundMode.BLACK) },
                            modifier = Modifier.focusRequester(firstFocus),
                        )
                        OptionChip(
                            label = "Графит",
                            selected = uiState.backgroundMode == BackgroundMode.GRADIENT,
                            onClick = { onSelectBackgroundMode(BackgroundMode.GRADIENT) },
                        )
                        OptionChip(
                            label = "Изображение",
                            selected = uiState.backgroundMode == BackgroundMode.CUSTOM_FILE,
                            onClick = {
                                if (uiState.customBackgroundPath != null) {
                                    onSelectBackgroundMode(BackgroundMode.CUSTOM_FILE)
                                } else {
                                    onPickCustomBackground()
                                }
                            },
                        )
                        if (uiState.customBackgroundPath != null) {
                            OptionChip(
                                label = "Заменить",
                                selected = false,
                                onClick = onPickCustomBackground,
                            )
                            OptionChip(
                                label = "Сбросить",
                                selected = false,
                                onClick = onClearCustomBackground,
                            )
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsSectionTitle("Колонок в сетке")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        (4..6).forEach { n ->
                            OptionChip(
                                label = n.toString(),
                                selected = uiState.gridColumns == n,
                                onClick = { onSelectGridColumns(n) },
                            )
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsSectionTitle("Медиа-экран")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OptionChip(
                            label = "Включён",
                            selected = uiState.mediaScreenEnabled,
                            onClick = { onSetMediaScreenEnabled(true) },
                        )
                        OptionChip(
                            label = "Выключен",
                            selected = !uiState.mediaScreenEnabled,
                            onClick = { onSetMediaScreenEnabled(false) },
                        )
                        if (uiState.mediaScreenEnabled && !uiState.mediaState.hasAccess) {
                            OptionChip(
                                label = "Разрешить доступ",
                                selected = false,
                                onClick = onRequestMediaAccess,
                            )
                        }
                    }
                    if (uiState.mediaScreenEnabled) {
                        Text(
                            text = if (uiState.mediaState.hasAccess) {
                                "Доступ к медиа активен"
                            } else {
                                "Нужен доступ к уведомлениям для чтения активных плееров"
                            },
                            style = TextStyle(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Normal,
                                color = if (uiState.mediaState.hasAccess) {
                                    LauncherColors.VpnActive
                                } else {
                                    LauncherColors.TextMuted
                                },
                            ),
                        )
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsSectionTitle("Скрытые приложения")
                    if (uiState.hiddenApps.isEmpty()) {
                        Text(
                            text = "Нет скрытых приложений",
                            style = TextStyle(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Normal,
                                color = LauncherColors.TextMuted,
                            ),
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            uiState.hiddenApps.forEach { app ->
                                OptionChip(
                                    label = "Показать: ${app.label}",
                                    selected = false,
                                    onClick = { onUnhideApp(app.packageName) },
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
private fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        style = TextStyle(
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = LauncherColors.TextSecondary,
        ),
    )
}

@Composable
private fun OptionChip(
    label: String,
    selected: Boolean,
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
            containerColor = if (selected) LauncherColors.Accent.copy(alpha = 0.18f) else LauncherColors.SurfaceCard,
            focusedContainerColor = LauncherColors.FocusSurface,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                BorderStroke(
                    1.dp,
                    if (selected) LauncherColors.Accent.copy(alpha = 0.65f) else LauncherColors.BorderSubtle,
                ),
                shape = shape,
            ),
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