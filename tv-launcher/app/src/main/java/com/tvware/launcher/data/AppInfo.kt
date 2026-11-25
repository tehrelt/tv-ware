package com.tvware.launcher.data

import android.content.ComponentName
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

@Immutable
data class AppInfo(
    val packageName: String,
    val label: String,
    val componentName: ComponentName,
    val versionCode: Long,
    val isLeanback: Boolean,
)

@Immutable
data class AppArtwork(
    val bitmap: ImageBitmap,
    val isBanner: Boolean,
)
