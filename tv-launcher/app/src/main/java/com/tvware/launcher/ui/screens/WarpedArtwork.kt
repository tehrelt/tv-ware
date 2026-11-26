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
private const val SoftWarpMaxPx = 48
private const val SoftWarpBlurRadius = 5

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
    if (source.width <= 0 || source.height <= 0) return source
    val maxDim = maxOf(source.width, source.height)
    val scaled = if (maxDim > SoftWarpMaxPx) {
        val scale = SoftWarpMaxPx.toFloat() / maxDim
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        runCatching { Bitmap.createScaledBitmap(source, w, h, true) }.getOrDefault(source)
    } else {
        source
    }
    return boxBlurBitmap(scaled, SoftWarpBlurRadius)
}

// Pre-blur the tiny warp bitmap once per track change to avoid a full-screen GPU blur every frame.
private fun boxBlurBitmap(source: Bitmap, radius: Int): Bitmap {
    val w = source.width
    val h = source.height
    if (w <= 1 || h <= 1 || radius <= 0) return source
    val argb = if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
    val pixels = IntArray(w * h)
    val temp = IntArray(w * h)
    argb.getPixels(pixels, 0, w, 0, 0, w, h)
    repeat(2) {
        boxBlurPass(pixels, temp, w, h, radius, horizontal = true)
        boxBlurPass(temp, pixels, w, h, radius, horizontal = false)
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}

private fun boxBlurPass(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
    val outer = if (horizontal) h else w
    val inner = if (horizontal) w else h
    val window = radius * 2 + 1
    for (o in 0 until outer) {
        var aSum = 0
        var rSum = 0
        var gSum = 0
        var bSum = 0
        for (k in -radius..radius) {
            val idx = k.coerceIn(0, inner - 1)
            val c = src[if (horizontal) o * w + idx else idx * w + o]
            aSum += (c ushr 24) and 0xFF
            rSum += (c ushr 16) and 0xFF
            gSum += (c ushr 8) and 0xFF
            bSum += c and 0xFF
        }
        for (i in 0 until inner) {
            dst[if (horizontal) o * w + i else i * w + o] =
                ((aSum / window) shl 24) or ((rSum / window) shl 16) or ((gSum / window) shl 8) or (bSum / window)
            val addIdx = (i + radius + 1).coerceAtMost(inner - 1)
            val remIdx = (i - radius).coerceAtLeast(0)
            val addC = src[if (horizontal) o * w + addIdx else addIdx * w + o]
            val remC = src[if (horizontal) o * w + remIdx else remIdx * w + o]
            aSum += ((addC ushr 24) and 0xFF) - ((remC ushr 24) and 0xFF)
            rSum += ((addC ushr 16) and 0xFF) - ((remC ushr 16) and 0xFF)
            gSum += ((addC ushr 8) and 0xFF) - ((remC ushr 8) and 0xFF)
            bSum += (addC and 0xFF) - (remC and 0xFF)
        }
    }
}
