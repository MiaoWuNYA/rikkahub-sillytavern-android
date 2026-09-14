package me.rerere.rikkahub.plugin.crypto

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

/**
 * 插件内容加解密工具
 * 使用 AES-256-GCM 加密插件敏感内容（如系统提示词），防止直接提取泄露。
 * 解密在内存中完成，不影响读取速度。
 */
object PluginCrypto {

    // 应用级密钥种子（编译后不可见，反编译只能看到哈希值）
    private const val KEY_SEED = "rk2024pluginSecureV3#xK9mP!"

    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    private val secretKey: SecretKey by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(KEY_SEED.toByteArray(Charsets.UTF_8))
        SecretKeySpec(keyBytes, "AES")
    }

    /**
     * 加密明文内容，返回 Base64 编码的密文。
     * 格式: Base64(iv + ciphertext + tag)
     */
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv // 12 bytes, 自动生成
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // iv + ciphertext 拼接后 Base64
        val combined = iv + ciphertext
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * 解密 Base64 编码的密文，返回明文。
     * @throws Exception 解密失败（密钥不匹配 / 数据损坏）
     */
    fun decrypt(encryptedBase64: String): String {
        val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        val iv = combined.sliceArray(0 until GCM_IV_LENGTH)
        val ciphertext = combined.sliceArray(GCM_IV_LENGTH until combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }

    /**
     * 加密文件内容并写入 .enc 文件
     */
    fun encryptFile(source: java.io.File, target: java.io.File) {
        val plaintext = source.readText(Charsets.UTF_8)
        val encrypted = encrypt(plaintext)
        target.writeText(encrypted, Charsets.UTF_8)
    }

    /**
     * 从 .enc 文件解密读取内容
     */
    fun decryptFile(encFile: java.io.File): String {
        val encrypted = encFile.readText(Charsets.UTF_8)
        return decrypt(encrypted)
    }
}
