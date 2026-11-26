package com.tvware.launcher.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.tvware.launcher.data.AppInfo
import com.tvware.launcher.ui.theme.LauncherColors

@Composable
fun AppContextDialog(
    app: AppInfo,
    isPinned: Boolean,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    onTogglePin: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onHide: () -> Unit,
    onAppInfo: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        val firstFocus = remember { FocusRequester() }
        val confirmInput = remember { MenuConfirmInput() }
        LaunchedEffect(app.packageName) { firstFocus.requestFocus() }
        val panelShape = RoundedCornerShape(20.dp)

        Column(
            modifier = Modifier
                .onPreviewKeyEvent { event ->
                    val key = event.nativeKeyEvent
                    when (key.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> confirmInput.consume(
                            keyCode = key.keyCode,
                            down = key.action == KeyEvent.ACTION_DOWN,
                            repeatCount = key.repeatCount,
                            canceled = key.isCanceled,
                        )
                        else -> false
                    }
                }
                .width(384.dp)
                .clip(panelShape)
                .background(LauncherColors.SurfaceCard)
                .border(1.dp, LauncherColors.BorderSubtle, panelShape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = app.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = LauncherColors.TextPrimary,
                ),
            )
            Text(
                text = app.packageName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    color = LauncherColors.TextMuted,
                ),
            )

            Spacer(modifier = Modifier.height(8.dp))

            DialogActionButton(
                label = if (isPinned) "Убрать из избранного" else "В избранное",
                onClick = onTogglePin,
                modifier = Modifier.focusRequester(firstFocus),
            )

            if (canMoveLeft) {
                DialogActionButton(
                    label = "Переместить влево",
                    onClick = onMoveLeft,
                )
            }

            if (canMoveRight) {
                DialogActionButton(
                    label = "Переместить вправо",
                    onClick = onMoveRight,
                )
            }

            DialogActionButton(
                label = "Скрыть приложение",
                onClick = onHide,
            )

            DialogActionButton(
                label = "О приложении",
                onClick = onAppInfo,
            )
        }
    }
}

@Composable
private fun DialogActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = LauncherColors.SurfaceElevated,
            focusedContainerColor = LauncherColors.FocusSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f, pressedScale = 0.98f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, LauncherColors.BorderSubtle), shape = shape),
            focusedBorder = Border.None,
        ),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
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