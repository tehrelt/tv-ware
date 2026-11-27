package com.tvware.launcher.ui.screens

import android.graphics.Bitmap
import android.graphics.Color
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

private const val MeshSize = 16
private const val WarpPeriodMs = 14_000
private const val SampleGridPx = 24
private const val ColorFieldPx = 64

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
    val bitmap = remember(artwork) { createColorFieldBitmap(artwork.asAndroidBitmap()) }
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
                sin(u * PI).toFloat() * sin(v * PI).toFloat() * 0.16f
            } else {
                0f
            }
            val dx = sin(cycle + 4.2f * v + 2.1f * u) + 0.55f * cos(2f * cycle - 3.4f * u + 2.8f * v)
            val dy = sin(cycle + 4.0f * u - 2.3f * v + 1.4f) + 0.55f * cos(2f * cycle + 3.1f * v + 2.6f * u)
            vertices[index++] = width * (u + strength * dx)
            vertices[index++] = height * (v + strength * dy)
        }
    }
}

private fun createColorFieldBitmap(source: Bitmap): Bitmap {
    if (source.width <= 0 || source.height <= 0) return source
    val sample = runCatching {
        Bitmap.createScaledBitmap(source, SampleGridPx, SampleGridPx, true)
    }.getOrDefault(source)
    val sw = sample.width
    val sh = sample.height
    val pixels = IntArray(sw * sh)
    sample.getPixels(pixels, 0, sw, 0, 0, sw, sh)

    val halfW = sw / 2
    val halfH = sh / 2
    val palette = intArrayOf(
        pickRegionColor(pixels, sw, 0, halfW, 0, halfH),
        pickRegionColor(pixels, sw, halfW, sw, 0, halfH),
        pickRegionColor(pixels, sw, 0, halfW, halfH, sh),
        pickRegionColor(pixels, sw, halfW, sw, halfH, sh),
        pickRegionColor(pixels, sw, sw / 4, (sw * 3) / 4, sh / 4, (sh * 3) / 4),
    )

    val anchorsX = floatArrayOf(0.18f, 0.82f, 0.22f, 0.78f, 0.50f)
    val anchorsY = floatArrayOf(0.22f, 0.18f, 0.80f, 0.76f, 0.48f)
    val rComp = FloatArray(palette.size) { Color.red(palette[it]).toFloat() }
    val gComp = FloatArray(palette.size) { Color.green(palette[it]).toFloat() }
    val bComp = FloatArray(palette.size) { Color.blue(palette[it]).toFloat() }

    val out = IntArray(ColorFieldPx * ColorFieldPx)
    val invSigma = 1f / 0.045f
    for (y in 0 until ColorFieldPx) {
        val ny = y.toFloat() / (ColorFieldPx - 1)
        for (x in 0 until ColorFieldPx) {
            val nx = x.toFloat() / (ColorFieldPx - 1)
            var wSum = 0f
            var rSum = 0f
            var gSum = 0f
            var bSum = 0f
            for (i in palette.indices) {
                val dx = nx - anchorsX[i]
                val dy = ny - anchorsY[i]
                val w = exp(-(dx * dx + dy * dy) * invSigma)
                wSum += w
                rSum += rComp[i] * w
                gSum += gComp[i] * w
                bSum += bComp[i] * w
            }
            val r = (rSum / wSum).toInt().coerceIn(0, 255)
            val g = (gSum / wSum).toInt().coerceIn(0, 255)
            val b = (bSum / wSum).toInt().coerceIn(0, 255)
            out[y * ColorFieldPx + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    return Bitmap.createBitmap(out, ColorFieldPx, ColorFieldPx, Bitmap.Config.ARGB_8888)
}

private fun pickRegionColor(
    pixels: IntArray,
    stride: Int,
    xStart: Int,
    xEnd: Int,
    yStart: Int,
    yEnd: Int,
): Int {
    val hsv = FloatArray(3)
    var wSum = 0f
    var rSum = 0f
    var gSum = 0f
    var bSum = 0f
    for (y in yStart until yEnd) {
        for (x in xStart until xEnd) {
            val c = pixels[y * stride + x]
            Color.colorToHSV(c, hsv)
            val sat = hsv[1]
            val valLum = hsv[2]
            // Favor saturated, non-black pixels so complementary colors don't average to gray mud.
            val weight = (0.15f + sat * sat * 3.0f) * (0.25f + valLum)
            wSum += weight
            rSum += Color.red(c) * weight
            gSum += Color.green(c) * weight
            bSum += Color.blue(c) * weight
        }
    }
    if (wSum <= 0f) return Color.BLACK
    val r = (rSum / wSum).toInt().coerceIn(0, 255)
    val g = (gSum / wSum).toInt().coerceIn(0, 255)
    val b = (bSum / wSum).toInt().coerceIn(0, 255)
    return Color.rgb(r, g, b)
}
