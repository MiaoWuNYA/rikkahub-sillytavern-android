package com.soreverse.mcp.nativecore

import android.util.Log

/**
 * 插件密钥派生因子的运行时供给。
 *
 * 因子原先以 BuildConfig.PLUGIN_KDF_FACTOR 的形式编译进 dex，
 * 任何人 `strings classes*.dex | grep` 就能拿到，进而完整复现密钥派生、
 * 解开插件提示词。现改为存放于独立的 libhdguard.so：
 *
 *   - 因子在 so 内以多段异或形态存放，段间夹噪音字节，无连续明文；
 *   - 期望校验值同样异或存储，且密钥下标在运行时计算，
 *     避免编译器把「常量数组 ^ 常量数组」折叠成明文字面量；
 *   - 取因子前校验调用方证书摘要前缀，使 so 被单独抠出去调用也不成立。
 *
 * 边界说明：这挡得住 grep / strings 这类一击命中，
 * 挡不住耐心读反汇编的人。它抬高成本，不提供不可破解性。
 *
 * 独立成库是刻意的：它与任何其它 native 组件的加载状态完全解耦，
 * 插件解密不会被无关组件的加载失败牵连。
 */
object HdGuard {

    private const val TAG = "HdGuard"

    @Volatile
    private var libLoaded = false

    @Volatile
    private var loadError = ""

    /** 缓存结果，避免每次派生都走一次 JNI。 */
    @Volatile
    private var cached: String? = null

    /**
     * 与 libhdguard.so 的 JNI 符号绑定。
     *
     * 必须保持 public：JNI 按「类的全限定名 + 方法名」解析符号，
     * 声明为 private 会被 R8 改名，而改名后 native 侧就找不到这个符号，
     * 表现为 UnsatisfiedLinkError，进而使整个插件解密失败。
     */
    external fun hdKdfFactor(certDigest: ByteArray): String?

    init {
        libLoaded = runCatching {
            System.loadLibrary("hdguard")
            true
        }.getOrElse { e ->
            loadError = when (e) {
                is UnsatisfiedLinkError -> "JNI 符号未解析：${e.message}"
                is SecurityException -> "加载被拒绝：${e.message}"
                else -> "${e::class.simpleName}: ${e.message}"
            }
            Log.w(TAG, "hdguard 加载失败：$loadError")
            false
        }
        if (libLoaded) Log.i(TAG, "hdguard 加载成功")
    }

    /**
     * 使用证书摘要推导派生因子。
     *
     * @param certDigestHex 证书摘要的十六进制字符串（PluginCrypto 所用形态）。
     *                      前 16 个 ASCII 字符会与 so 内置的期望值比对。
     * @return 因子字符串；库未加载或校验不通过时返回 null。
     */
    fun kdfFactor(certDigestHex: String): String? {
        cached?.let { return it }
        if (!libLoaded) return null
        if (certDigestHex.length < 16) return null
        val prefix = certDigestHex.substring(0, 16).toByteArray(Charsets.UTF_8)
        val v = runCatching { hdKdfFactor(prefix) }.getOrElse { e ->
            Log.w(TAG, "取派生因子失败：${e.message}")
            null
        }
        if (!v.isNullOrEmpty()) cached = v
        return v
    }

    /** 供诊断使用：库是否就绪、失败原因。 */
    fun status(): String =
        if (libLoaded) "loaded" else "unavailable（$loadError）"
}
