package com.tvware.launcher.data

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class IconCache(private val context: Context) {

    private val pm: PackageManager = context.packageManager
    private val diskDir: File = File(context.cacheDir, "icons").apply { mkdirs() }

    // 16 MB in-memory cache keeps RAM well under the 60-80 MB budget.
    private val memoryCache = object : LruCache<String, AppArtwork>(16 * 1024) {
        override fun sizeOf(key: String, value: AppArtwork): Int {
            return (value.bitmap.width * value.bitmap.height * 4) / 1024
        }
    }

    fun getFromMemory(app: AppInfo): AppArtwork? = memoryCache.get(cacheKey(app))

    suspend fun loadArtwork(app: AppInfo): AppArtwork? {
        val key = cacheKey(app)
        memoryCache.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            memoryCache.get(key)?.let { return@withContext it }

            readFromDisk(key)?.let { artwork ->
                memoryCache.put(key, artwork)
                return@withContext artwork
            }

            val loaded = loadFromPackageManager(app) ?: return@withContext null
            memoryCache.put(key, loaded.first)
            writeToDisk(key, loaded.second, loaded.first.isBanner)
            loaded.first
        }
    }

    suspend fun loadCustomBackground(path: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists() || !file.isFile) return@withContext null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, 1920, 1080)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
    }

    fun evictPackage(packageName: String) {
        val prefix = "${packageName}_"
        memoryCache.snapshot().keys
            .filter { it.startsWith(prefix) }
            .forEach { memoryCache.remove(it) }
        diskDir.listFiles { _, name -> name.startsWith(prefix) }
            ?.forEach { it.delete() }
    }

    private fun cacheKey(app: AppInfo): String = "${app.packageName}_${app.versionCode}"

    private fun readFromDisk(key: String): AppArtwork? {
        val bannerFile = File(diskDir, "${key}_b.webp")
        if (bannerFile.exists()) {
            BitmapFactory.decodeFile(bannerFile.absolutePath)?.let {
                return AppArtwork(it.asImageBitmap(), isBanner = true)
            }
        }
        val iconFile = File(diskDir, "${key}_i.webp")
        if (iconFile.exists()) {
            BitmapFactory.decodeFile(iconFile.absolutePath)?.let {
                return AppArtwork(it.asImageBitmap(), isBanner = false)
            }
        }
        return null
    }

    private fun writeToDisk(key: String, bitmap: Bitmap, isBanner: Boolean) {
        val suffix = if (isBanner) "b" else "i"
        val target = File(diskDir, "${key}_$suffix.webp")
        runCatching {
            FileOutputStream(target).use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, out)
            }
        }
    }

    private fun loadFromPackageManager(app: AppInfo): Pair<AppArtwork, Bitmap>? {
        val bannerDrawable = runCatching {
            pm.getActivityBanner(app.componentName) ?: pm.getApplicationBanner(app.packageName)
        }.getOrNull()

        if (bannerDrawable != null) {
            val bmp = drawableToBitmap(bannerDrawable, BANNER_WIDTH, BANNER_HEIGHT)
            return AppArtwork(bmp.asImageBitmap(), isBanner = true) to bmp
        }

        val iconDrawable = runCatching {
            pm.getActivityIcon(app.componentName)
        }.getOrElse {
            runCatching { pm.getApplicationIcon(app.packageName) }.getOrNull()
        } ?: return null

        val bmp = drawableToBitmap(iconDrawable, ICON_SIZE, ICON_SIZE)
        return AppArtwork(bmp.asImageBitmap(), isBanner = false) to bmp
    }

    private fun drawableToBitmap(drawable: Drawable, maxWidth: Int, maxHeight: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val src = drawable.bitmap
            if (src.width <= maxWidth && src.height <= maxHeight) {
                return src
            }
            val scale = minOf(maxWidth.toFloat() / src.width, maxHeight.toFloat() / src.height)
            val w = (src.width * scale).toInt().coerceAtLeast(1)
            val h = (src.height * scale).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(src, w, h, true)
        }

        val intrinsicW = drawable.intrinsicWidth.takeIf { it > 0 } ?: maxWidth
        val intrinsicH = drawable.intrinsicHeight.takeIf { it > 0 } ?: maxHeight
        val scale = minOf(maxWidth.toFloat() / intrinsicW, maxHeight.toFloat() / intrinsicH, 1f)
        val w = (intrinsicW * scale).toInt().coerceAtLeast(1)
        val h = (intrinsicH * scale).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return bitmap
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        while (width / inSampleSize > reqW || height / inSampleSize > reqH) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    private companion object {
        const val BANNER_WIDTH = 320
        const val BANNER_HEIGHT = 180
        const val ICON_SIZE = 128
    }
}
