package com.tvware.launcher.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.tvware.launcher.data.AppArtwork
import com.tvware.launcher.data.AppInfo
import com.tvware.launcher.ui.theme.LauncherColors

@Composable
fun AppTile(
    app: AppInfo,
    getCachedArtwork: (AppInfo) -> AppArtwork?,
    loadArtwork: suspend (AppInfo) -> AppArtwork?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    val initialArtwork = remember(app.packageName, app.versionCode) { getCachedArtwork(app) }
    val artwork by produceState(initialValue = initialArtwork, app.packageName, app.versionCode) {
        if (value == null) {
            value = loadArtwork(app)
        }
    }

    val outerShape = RoundedCornerShape(16.dp)
    val separatorShape = RoundedCornerShape(14.dp)
    val shape = RoundedCornerShape(12.dp)
    val frameModifier = if (isFocused) {
        Modifier
            .fillMaxWidth()
            .border(2.dp, LauncherColors.FocusRing, outerShape)
            .padding(2.dp)
            .border(2.dp, LauncherColors.BgBlack, separatorShape)
            .padding(2.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(4.dp)
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = frameModifier) {
            Surface(
                onClick = onClick,
                onLongClick = onLongClick,
                shape = ClickableSurfaceDefaults.shape(shape = shape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = LauncherColors.SurfaceCard,
                    focusedContainerColor = LauncherColors.SurfaceElevated,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f, pressedScale = 0.98f),
                border = ClickableSurfaceDefaults.border(
                    border = Border(BorderStroke(1.dp, LauncherColors.BorderSubtle), shape = shape),
                    focusedBorder = Border(BorderStroke(1.dp, Color.Transparent), shape = shape),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .onFocusChanged { isFocused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyUp &&
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU
                        ) {
                            onLongClick()
                            true
                        } else {
                            false
                        }
                    },
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    val art = artwork
                    when {
                        art != null && art.isBanner -> {
                            Image(
                                bitmap = art.bitmap,
                                contentDescription = app.label,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        art != null -> {
                            Image(
                                bitmap = art.bitmap,
                                contentDescription = app.label,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(52.dp),
                            )
                        }
                        else -> {
                            Text(
                                text = app.label.take(1).uppercase(),
                                style = TextStyle(
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = LauncherColors.TextMuted,
                                ),
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = app.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontSize = 14.sp,
                fontWeight = if (isFocused) FontWeight.Medium else FontWeight.Normal,
                color = if (isFocused) LauncherColors.TextPrimary else LauncherColors.TextSecondary,
            ),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
