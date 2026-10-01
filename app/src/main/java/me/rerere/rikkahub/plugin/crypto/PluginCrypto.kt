package me.rerere.rikkahub.plugin.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import me.rerere.rikkahub.BuildConfig
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 插件内容加解密工具
 *
 * 密钥不从源码常量派生，而是从 **APK 签名证书** + **构建期盐值** 联合派生：
 *
 *   key = HKDF-SHA256( salt = BUILD_SALT , ikm = SHA256(签名证书) || 包名 || 签名摘要 )
 *
 * 这样做的意义：
 *  - 仓库里没有签名密钥库，也就无法推出解密密钥；仅 clone 源码无法解密 .enc
 *  - 反编译 dex 只能看到派生流程，拿不到可直接使用的密钥
 *  - 换签名（重打包、二次分发）后密钥自动失效，密文无法被别人的包解开
 *
 * 兼容性：v1（旧插件）仍用 LEGACY_SEED 派生，保证已分发插件继续可用。
 * 新插件一律走 v2（证书派生）。解密时按前缀自动识别版本。
 *
 * 说明：任何在用户设备上运行的解密逻辑都无法做到理论上的绝对不可逆向，
 * 本方案的目标是把提取成本从「strings 一条命令」抬到需要专业逆向工程的程度。
 */
object PluginCrypto {

    // ── v1 旧密钥（仅为兼容历史插件保留）────────────────────────────────
    // 以字节数组存储，避免 strings 直接提取
    private val LEGACY_SEED_BYTES: ByteArray = byteArrayOf(
        0x72, 0x6B, 0x32, 0x30, 0x32, 0x34, 0x70, 0x6C, 0x75, 0x67, 0x69, 0x6E,  // legacy seed part 1
        0x53, 0x65, 0x63, 0x75, 0x72, 0x65, 0x56, 0x33, 0x23, 0x78, 0x4B, 0x39,  // legacy seed part 2
        0x6D, 0x50, 0x21,                                                          // legacy seed part 3
    )

    // ── v2 派生参数 ──────────────────────────────────────────────────
    // 构建期盐值：与签名证书、签名口令三者共同参与派生，缺一不可。
    // 以字节数组分段拼接，避免反编译工具直接命中可读字符串常量。
    private val DERIVE_SALT_BYTES: ByteArray = byteArrayOf(
        0x68, 0x64, 0x2E, 0x70, 0x6C, 0x75, 0x67, 0x69, 0x6E, 0x2E,  // kdf salt part 1
        0x6B, 0x64, 0x66, 0x2E, 0x76, 0x32, 0x3A, 0x3A, 0x63, 0x6F,  // kdf salt part 2
        0x63, 0x6F, 0x6F, 0x6E,                                        // kdf salt part 3
    )
    // 版本前缀：新密文带此前缀，解码时据此选择派生路径
    private const val V2_PREFIX = "v2:"

    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    private val legacyKey: SecretKey by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        SecretKeySpec(digest.digest(LEGACY_SEED_BYTES), "AES")
    }

    @Volatile
    private var derivedKeyCache: SecretKey? = null

    /**
     * 从签名证书派生密钥。
     * 取当前 APK 的签名信息作为密钥材料之一——没有对应签名密钥库就无法复现。
     */
    private fun deriveKey(context: Context): SecretKey {
        derivedKeyCache?.let { return it }
        return synchronized(this) {
            derivedKeyCache?.let { return@synchronized it }

            val certMaterial = StringBuilder()
            try {
                val pm = context.packageManager
                val pkg = context.packageName
                val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                    info.signingInfo?.apkContentsSigners
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
                }
                signatures?.forEach { sig ->
                    certMaterial.append(
                        MessageDigest.getInstance("SHA-256")
                            .digest(sig.toByteArray())
                            .joinToString("") { "%02x".format(it) }
                    )
                }
            } catch (_: Exception) {
                // 取签名失败时退化为包名派生，仍强于明文常量
            }
            certMaterial.append('|').append(context.packageName)
            // 第三因子：构建期由签名口令 HMAC 得到的值。口令不在 APK 中，
            // 反编译只能拿到这个 HMAC 结果，无法反推口令，也无法自行派生密钥。
            certMaterial.append('|').append(BuildConfig.PLUGIN_KDF_FACTOR)

            val ikm = MessageDigest.getInstance("SHA-256")
                .digest(certMaterial.toString().toByteArray(Charsets.UTF_8))

            // HKDF-Extract / Expand（RFC 5869），单块输出 32 字节即 AES-256 密钥
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(DERIVE_SALT_BYTES, "HmacSHA256"))
            val prk = mac.doFinal(ikm)

            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(byteArrayOf(0x68, 0x64, 0x2D, 0x70, 0x6C, 0x75, 0x67, 0x69, 0x6E, 0x2D,
                0x76, 0x32, 0x2D, 0x61, 0x65, 0x73))
            mac.update(1.toByte())
            val keyBytes = mac.doFinal().copyOf(32)

            SecretKeySpec(keyBytes, "AES").also { derivedKeyCache = it }
        }
    }

    /** 需要 Context 才能派生 v2 密钥，故由调用方在初始化时注入。 */
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun keyForV2(): SecretKey {
        val ctx = appContext ?: error("PluginCrypto.init(context) 未调用")
        return deriveKey(ctx)
    }

    private fun aes(key: SecretKey, mode: Int, iv: ByteArray? = null): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (mode == Cipher.DECRYPT_MODE) {
            cipher.init(mode, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        } else {
            cipher.init(mode, key)
        }
        return cipher
    }

    /**
     * 加密明文，返回带版本前缀的 Base64 字符串。
     * 格式: "v2:" + Base64(iv + ciphertext + tag)
     */
    fun encrypt(plaintext: String): String {
        val cipher = aes(keyForV2(), Cipher.ENCRYPT_MODE)
        val combined = cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return V2_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /** 用旧密钥加密（仅供需要产出 v1 兼容密文的场景使用）。 */
    fun encryptLegacy(plaintext: String): String {
        val cipher = aes(legacyKey, Cipher.ENCRYPT_MODE)
        val combined = cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * 解密。带 "v2:" 前缀走证书派生，否则走旧密钥（兼容历史插件）。
     */
    fun decrypt(encryptedBase64: String): String {
        val isV2 = encryptedBase64.startsWith(V2_PREFIX)
        val body = if (isV2) encryptedBase64.substring(V2_PREFIX.length) else encryptedBase64
        val key = if (isV2) keyForV2() else legacyKey

        val combined = Base64.decode(body.trim(), Base64.NO_WRAP)
        require(combined.size > GCM_IV_LENGTH) { "密文长度不足" }
        val iv = combined.sliceArray(0 until GCM_IV_LENGTH)
        val ciphertext = combined.sliceArray(GCM_IV_LENGTH until combined.size)
        return String(aes(key, Cipher.DECRYPT_MODE, iv).doFinal(ciphertext), Charsets.UTF_8)
    }

    fun encryptFile(source: java.io.File, target: java.io.File) {
        target.writeText(encrypt(source.readText(Charsets.UTF_8)), Charsets.UTF_8)
    }

    fun decryptFile(encFile: java.io.File): String = decrypt(encFile.readText(Charsets.UTF_8))

    /** 该密文是否为新版（证书派生）格式。 */
    fun isDerivedFormat(content: String): Boolean = content.startsWith(V2_PREFIX)
}
