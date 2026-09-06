package me.rerere.rikkahub.data.ai.tools.ynufe

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets

/**
 * 云南财经大学教务网专属账号密码 encodeInp 加密算法（登录协议用，变种 Base64）。
 * 原样移植自 云南财经/src/utils/crypto.ts -> encodeInp。
 */
fun encodeInp(input: String): String {
    if (input.isEmpty()) return ""
    val keyStr = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
    val sb = StringBuilder()
    var i = 0
    val bytes = input.toByteArray(StandardCharsets.UTF_8)
    while (i < bytes.size) {
        val chr1 = bytes[i++].toInt() and 0xFF
        val chr2 = if (i < bytes.size) bytes[i++].toInt() and 0xFF else -1
        val chr3 = if (i < bytes.size) bytes[i++].toInt() and 0xFF else -1

        val enc1 = chr1 shr 2
        val enc2 = ((chr1 and 3) shl 4) or (if (chr2 < 0) 0 else chr2 shr 4)
        // 注意：chr2/chr3 缺席时为 -1，必须先判空再做移位（-1 shr 6 == -1 会污染 or 运算）
        val enc3 = when {
            chr2 < 0 -> 64
            chr3 < 0 -> (chr2 and 15) shl 2
            else -> ((chr2 and 15) shl 2) or (chr3 shr 6)
        }
        val enc4 = if (chr3 < 0) 64 else (chr3 and 63)

        sb.append(keyStr[enc1])
        sb.append(keyStr[enc2])
        sb.append(keyStr[enc3])
        sb.append(keyStr[enc4])
    }
    return sb.toString()
}

/**
 * 本地凭据混淆存储（XOR + Base64，附完整性头部校验）。
 * 说明：纯客户端无法做到真正安全的密码存储（密钥必然也在本地），
 * 以下只是「混淆」而非强加密，防止密码以明文形式直接躺在磁盘里被随手看到。
 */
private const val DEVICE_KEY_PREFS = "ynufe_device_key"
private const val MAGIC_PREFIX = "YNUFE_OK:"

class CredentialObfuscator(private val context: Context) {
    private val prefs get() = context.getSharedPreferences("ynufe_secret", Context.MODE_PRIVATE)

    private fun getDeviceKey(): String {
        prefs.getString(DEVICE_KEY_PREFS, null)?.let { return it }
        val bytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val key = bytes.joinToString("") { java.lang.Long.toHexString((it.toLong()) and 0xFF).padStart(2, '0') }
        prefs.edit().putString(DEVICE_KEY_PREFS, key).apply()
        return key
    }

    fun obfuscate(plain: String): String {
        if (plain.isEmpty()) return ""
        val key = getDeviceKey()
        val data = (MAGIC_PREFIX + plain).toByteArray(StandardCharsets.UTF_8)
        val out = ByteArray(data.size)
        for (i in data.indices) out[i] = (data[i].toInt() xor key[i % key.length].code).toByte()
        return "v2:" + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun deobfuscate(stored: String): String {
        if (stored.isEmpty()) return ""
        if (stored.startsWith("v2:")) {
            return try {
                val key = getDeviceKey()
                val bin = Base64.decode(stored.substring(3), Base64.DEFAULT)
                val bytes = ByteArray(bin.size)
                for (i in bin.indices) bytes[i] = (bin[i].toInt() xor key[i % key.length].code).toByte()
                val decoded = String(bytes, StandardCharsets.UTF_8)
                if (!decoded.startsWith(MAGIC_PREFIX)) "" else decoded.removePrefix(MAGIC_PREFIX)
            } catch (e: Exception) {
                ""
            }
        }
        if (stored.startsWith("v1:")) {
            return try {
                val key = getDeviceKey()
                val bin = Base64.decode(stored.substring(3), Base64.DEFAULT)
                val bytes = ByteArray(bin.size)
                for (i in bin.indices) bytes[i] = (bin[i].toInt() xor key[i % key.length].code).toByte()
                String(bytes, StandardCharsets.UTF_8)
            } catch (e: Exception) {
                ""
            }
        }
        return stored
    }
}