package me.rerere.rikkahub.data.ai

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 向量存储（Vector Storage）本地缓存：
 * 以 (嵌入模型ID, 文本内容) 为键，把嵌入向量持久化到 filesDir/vector_cache，
 * 条目内容不变就不重复调嵌入接口。官方 Vector Storage 同样只在内容变化时重新向量化。
 */
object VectorStoreCache {
    private fun dir(context: Context): File =
        File(context.filesDir, "vector_cache").apply { if (!exists()) mkdirs() }

    private fun file(context: Context, key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(dir(context), "$name.vec")
    }

    fun get(context: Context, key: String): FloatArray? {
        val f = file(context, key)
        if (!f.exists()) return null
        return runCatching {
            f.readText().split(",").map { it.trim().toFloat() }.toFloatArray()
        }.getOrNull()
    }

    fun put(context: Context, key: String, vector: FloatArray) {
        runCatching {
            file(context, key).writeText(vector.joinToString(",") { it.toString() })
        }
    }

    /** 余弦相似度；任一向量全零或维度不一致返回 0 */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0f || nb == 0f) return 0f
        return dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
    }
}
