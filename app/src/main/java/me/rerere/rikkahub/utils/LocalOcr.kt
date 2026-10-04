package me.rerere.rikkahub.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * 离线 OCR（ML Kit 中文识别，模型内置在 APK 内）。
 *
 * 为什么需要它：在此之前，图片识别只有一条路——远程调用用户自己配置的视觉模型
 * （见 OcrTransformer.performOcr）。没配 `ocrModelId` 时直接返回 "[Image]"，
 * 用户发来的截图、菜单、公式、书页对模型来说是一片空白。
 *
 * 这里提供的是本地兜底：不联网、不上传图片、离线可用。
 * 中文识别包同时覆盖拉丁字母与数字，日常场景足够；识别质量和远程大模型相比
 * 有差距（尤其手写、复杂排版），所以调用方应当优先用远程模型、失败或未配置时
 * 再退到本地。
 */
object LocalOcr {

    private const val TAG = "LocalOcr"

    /**
     * 单张图最长边超过该值时先缩放。
     * ML Kit 内部也会处理大图，但 4000px 以上的截图会明显拖慢首次识别并推高内存，
     * 而 OCR 精度在 2048px 以上几乎没有收益。
     */
    private const val MAX_DIMENSION = 2048

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * 识别一张图片里的文字。
     *
     * @return 识别出的纯文本（按 ML Kit 的段落顺序拼接，行内用空格连接）；
     *         图片不存在/无法解码/识别失败返回 null，由调用方决定兜底文案。
     */
    suspend fun recognize(context: Context, url: String): String? {
        val bitmap = loadBitmap(context, url) ?: return null
        val scaled = downscaleIfNeeded(bitmap)
        return try {
            recognizeBitmap(scaled)
        } catch (e: Throwable) {
            Log.w(TAG, "recognize failed: ${e.message}")
            null
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }

    /** 直接识别一个已经解码好的 Bitmap（供工具侧复用，避免重复解码）。 */
    suspend fun recognizeBitmap(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    // ML Kit 的 text 已按阅读顺序分好 block/line；
                    // block 之间空行分隔，行与行之间换行，保持原有版式结构
                    val joined = text.textBlocks
                        .joinToString("\n\n") { block ->
                            block.lines.joinToString("\n") { it.text }
                        }
                        .trim()
                    cont.resume(joined.ifBlank { null })
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "mlkit process failed: ${e.message}")
                    cont.resume(null)
                }
        }
    }

    /** 图片是否可被本地识别器直接处理（用于判断要不要走这条兜底路径）。 */
    fun isSupported(url: String): Boolean =
        url.isNotBlank() && !url.startsWith("http://") && !url.startsWith("https://")

    private fun downscaleIfNeeded(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_DIMENSION) return bitmap
        val ratio = MAX_DIMENSION.toFloat() / longest
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    /**
     * 把 UIMessagePart.Image 的 url 解成 Bitmap。
     *
     * url 有三种形态，按出现频率排序：
     *   1. 本地绝对路径（用户从相册选图后落盘）——最常见
     *   2. file:// URI
     *   3. content:// URI（少数从系统选择器拿到的）
     * 远程 http(s) 不在这里处理：那属于远程模型的活，本地识别下载图片既慢又无必要。
     */
    private fun loadBitmap(context: Context, url: String): Bitmap? {
        return try {
            when {
                url.startsWith("content://") -> {
                    context.contentResolver.openInputStream(Uri.parse(url))?.use {
                        BitmapFactory.decodeStream(it)
                    }
                }
                url.startsWith("file://") -> {
                    BitmapFactory.decodeFile(Uri.parse(url).path)
                }
                url.startsWith("http://") || url.startsWith("https://") -> null
                else -> {
                    val f = File(url)
                    if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "loadBitmap failed for $url: ${e.message}")
            null
        }
    }
}
