package me.rerere.rikkahub.browser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.utils.JsonInstant
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 浏览器登录凭据。只在 Android Keystore 里落盘，读取只发生在本地填充那一刻，
 * 任何返回值都不带密码，模型侧永远看不到。
 */
class LoginVault(context: Context) {

    @Serializable
    private data class Payload(val username: String, val password: String, val updatedAt: Long)

    data class Credentials(val username: String, val password: String, val updatedAt: Long) {
        /** 只给模型和日志看脱敏用户名。 */
        val masked: String
            get() = when {
                username.isEmpty() -> ""
                username.length <= 2 -> username.take(1) + "***"
                else -> username.take(1) + "***" + username.takeLast(1)
            }
    }

    private val prefs = context.getSharedPreferences("browser_login_vault", Context.MODE_PRIVATE)

    fun save(username: String, password: String): Boolean = runCatching {
        val payload = Payload(username, password, System.currentTimeMillis())
        val bytes = JsonInstant.encodeToString(payload).toByteArray()
        prefs.edit().putString(BLOB, encrypt(bytes)).apply()
        true
    }.getOrDefault(false)

    fun load(): Credentials? = runCatching {
        val raw = prefs.getString(BLOB, null) ?: return null
        val payload = JsonInstant.decodeFromString<Payload>(String(decrypt(raw)))
        Credentials(payload.username, payload.password, payload.updatedAt)
    }.getOrNull()

    fun maskedUser(): String? = load()?.masked

    fun clear() {
        prefs.edit().remove(BLOB).apply()
    }

    private fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(plain)
        val combined = cipher.iv + cipherText
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): ByteArray {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, IV_SIZE)
        val cipherText = combined.copyOfRange(IV_SIZE, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(cipherText)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "rikkahub_browser_login"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val GCM_TAG_BITS = 128
        const val BLOB = "blob"
    }
}
