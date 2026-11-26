package com.tvware.launcher.ui.screens

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlin.math.PI
import kotlin.math.sin

private const val MeshSize = 12
private const val WarpPeriodMs = 18_000
private const val SoftWarpMaxPx = 96

@Composable
internal fun WarpedArtwork(artwork: ImageBitmap, animate: Boolean, modifier: Modifier = Modifier) {
    val phase = if (animate) {
        rememberInfiniteTransition(label = "artwork_warp").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(WarpPeriodMs, easing = LinearEasing), RepeatMode.Restart),
            label = "warp_phase",
        )
    } else {
        null
    }
    val bitmap = remember(artwork) { prepareSoftWarpBitmap(artwork.asAndroidBitmap()) }
    val vertices = remember { FloatArray((MeshSize + 1) * (MeshSize + 1) * 2) }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    Canvas(modifier) {
        val scale = maxOf(size.width / bitmap.width, size.height / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        warpArtworkMesh(vertices, MeshSize, width, height, phase?.value ?: 0f)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            try {
                native.clipRect(0f, 0f, size.width, size.height)
                native.translate((size.width - width) / 2f, (size.height - height) / 2f)
                native.drawBitmapMesh(bitmap, MeshSize, MeshSize, vertices, 0, null, 0, paint)
            } finally {
                native.restore()
            }
        }
    }
}

internal fun warpArtworkMesh(vertices: FloatArray, divisions: Int, width: Float, height: Float, phase: Float) {
    val cycle = (2.0 * PI * phase).toFloat()
    var index = 0
    for (row in 0..divisions) {
        val v = row.toFloat() / divisions
        for (column in 0..divisions) {
            val u = column.toFloat() / divisions
            val strength = if (row in 1 until divisions && column in 1 until divisions) {
                sin(u * PI).toFloat() * sin(v * PI).toFloat() * 0.15f
            } else {
                0f
            }
            vertices[index++] = width * (u + strength * sin(cycle + 5f * v))
            vertices[index++] = height * (v + strength * sin(cycle + 5f * u + 1.6f))
        }
    }
}

private fun prepareSoftWarpBitmap(source: Bitmap): Bitmap {
    val maxDim = maxOf(source.width, source.height)
    if (maxDim <= SoftWarpMaxPx || source.width <= 0 || source.height <= 0) return source
    val scale = SoftWarpMaxPx.toFloat() / maxDim
    val w = (source.width * scale).toInt().coerceAtLeast(1)
    val h = (source.height * scale).toInt().coerceAtLeast(1)
    return runCatching { Bitmap.createScaledBitmap(source, w, h, true) }.getOrDefault(source)
}
