package me.rerere.rikkahub.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ScreenshotEngine(private val context: Context) {

    data class Shot(
        val file: File,
        val width: Int,
        val height: Int,
        val bytes: Long,
        val fullPage: Boolean,
        val pageHash: String,
        val cached: Boolean,
    )

    private data class CacheKey(
        val pageHash: String,
        val fullPage: Boolean,
        val maxWidth: Int,
        val quality: Int,
        val region: String,
    )

    private val shotsDir = File(context.filesDir, "browser/shots")
    private val cache = ConcurrentHashMap<CacheKey, File>()

    private val sensitiveHosts = listOf("pay", "login", "alipay", "passport", "auth")

    suspend fun capture(
        page: BrowserPage,
        info: PageInfo,
        settings: BrowserSettings,
        fullPage: Boolean,
        maxWidth: Int,
        quality: Int,
        region: IntArray?,
    ): Shot {
        if (!settings.allowScreenshot) throw BrowserFailure(BrowserError.DISABLED, "截图已在设置中关闭")
        if (fullPage && !settings.allowFullPageScreenshot) {
            throw BrowserFailure(BrowserError.DISABLED, "全页截图已在设置中关闭")
        }
        if (settings.blockSensitiveScreenshots) {
            if (sensitiveHosts.any { info.url.contains(it, ignoreCase = true) }) {
                throw BrowserFailure(BrowserError.SENSITIVE_PAGE, "敏感页面禁止截图，请改用文本快照")
            }
            if (hasPasswordField(page)) {
                throw BrowserFailure(BrowserError.SENSITIVE_PAGE, "页面含密码输入框，禁止截图")
            }
        }
        if (!page.isVisible()) throw BrowserFailure(BrowserError.NOT_VISIBLE, "浏览器不在前台，无法截图")

        val safeWidth = maxWidth.coerceIn(240, 4096)
        val safeQuality = quality.coerceIn(40, 85)
        val regionKey = region?.joinToString(",") ?: ""
        val key = CacheKey(info.hash, fullPage, safeWidth, safeQuality, regionKey)
        if (info.hash.isNotEmpty()) {
            cache[key]?.takeIf { it.exists() }?.let { return toShot(it, fullPage, info.hash, cached = true) }
        }

        val raw = if (fullPage) captureFullPage(page) else page.captureViewport()
        val scaled = scaleDown(raw, safeWidth)
        val cropped = region?.let { crop(scaled, it) } ?: scaled
        val file = write(cropped, safeQuality, fullPage)
        if (raw !== scaled) raw.recycle()
        if (scaled !== cropped) scaled.recycle()

        if (info.hash.isNotEmpty()) cache[key] = file
        cleanup(settings.keepScreenshots)
        return toShot(file, fullPage, info.hash, cached = false)
    }

    private suspend fun hasPasswordField(page: BrowserPage): Boolean {
        val raw = runCatching { page.evaluate(SnapshotScript.HAS_PASSWORD_FIELD) }.getOrNull() ?: return false
        return raw.contains("\"n\":") &&
            raw.substringAfter("\"n\":").substringBefore("}").trim().toIntOrNull()?.let { it > 0 } == true
    }

    private suspend fun captureFullPage(page: BrowserPage): Bitmap {
        val startMetrics = page.metrics()
        val viewport = startMetrics.viewportHeight
        val originY = startMetrics.scrollY
        if (viewport <= 0) return page.captureViewport()

        val slices = mutableListOf<Bitmap>()
        var coveredCss = 0f
        var requested = startMetrics.scrollY
        val deadline = SystemClock.uptimeMillis() + 6000

        try {
            while (slices.size < 10 && coveredCss < startMetrics.scrollHeight &&
                SystemClock.uptimeMillis() < deadline
            ) {
                page.scrollTo(requested)
                delay(180)
                val bitmap = page.captureViewport()
                val y = page.metrics().scrollY
                val devicePerCss = bitmap.height.toFloat() / viewport.toFloat()
                val skip = ((coveredCss - y) * devicePerCss).toInt().coerceIn(0, bitmap.height)
                val usable = bitmap.height - skip
                if (usable <= 4) {
                    bitmap.recycle()
                    val next = y + viewport
                    if (next <= requested) break
                    requested = next
                    continue
                }
                slices += if (skip == 0) bitmap else Bitmap.createBitmap(bitmap, 0, skip, bitmap.width, usable)
                if (skip != 0) bitmap.recycle()
                coveredCss = y + bitmap.height / devicePerCss
                if (coveredCss >= 8000) break
                val next = y + viewport
                if (next <= requested || next >= startMetrics.scrollHeight) break
                requested = next
            }
        } finally {
            runCatching { page.scrollTo(originY) }
        }

        if (slices.isEmpty()) return page.captureViewport()
        if (slices.size == 1) return slices.first()

        val width = slices.minOf { it.width }
        val height = slices.sumOf { it.height }.coerceAtMost(8000)
        val stitched = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(stitched)
        var top = 0
        slices.forEach { slice ->
            if (top < height) {
                canvas.drawBitmap(slice, 0f, top.toFloat(), null)
                top += slice.height
            }
            slice.recycle()
        }
        return stitched
    }

    private fun scaleDown(bitmap: Bitmap, maxWidth: Int): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val height = (bitmap.height * maxWidth.toFloat() / bitmap.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, maxWidth, height, true)
    }

    private fun crop(bitmap: Bitmap, region: IntArray): Bitmap {
        if (region.size < 4) return bitmap
        val rect = Rect(
            region[0].coerceIn(0, bitmap.width - 1),
            region[1].coerceIn(0, bitmap.height - 1),
            0,
            0,
        )
        rect.right = (rect.left + region[2]).coerceIn(rect.left + 1, bitmap.width)
        rect.bottom = (rect.top + region[3]).coerceIn(rect.top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
    }

    private fun write(bitmap: Bitmap, quality: Int, fullPage: Boolean): File {
        shotsDir.mkdirs()
        val prefix = if (fullPage) "full" else "shot"
        val file = File(shotsDir, "${prefix}_${System.currentTimeMillis()}.jpg")
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            file.writeBytes(out.toByteArray())
        }
        bitmap.recycle()
        return file
    }

    private fun toShot(file: File, fullPage: Boolean, hash: String, cached: Boolean): Shot {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
        return Shot(
            file = file,
            width = options.outWidth,
            height = options.outHeight,
            bytes = file.length(),
            fullPage = fullPage,
            pageHash = hash,
            cached = cached,
        )
    }

    fun cleanup(keep: Int) {
        val files = shotsDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        val expireAt = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        files.forEachIndexed { index, file ->
            if (index >= keep.coerceAtLeast(1) || file.lastModified() < expireAt) {
                cache.values.remove(file)
                file.delete()
            }
        }
    }
}
