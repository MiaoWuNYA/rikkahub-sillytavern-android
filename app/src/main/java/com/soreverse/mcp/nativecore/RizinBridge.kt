package com.soreverse.mcp.nativecore

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Rizin 反汇编/反编译引擎的 JNI 桥接层。
 *
 * JNI 符号由 C++ 侧写死为 Java_com_soreverse_mcp_nativecore_RizinNativeEngine_*，
 * 因此本文件的包名与类名**不能修改**，否则 native 层找不到对应函数。
 *
 * native 库依赖：librz_native.so + librz_*.so（26 个）+ libcore_ghidra.so
 * + libc++_shared.so，全部位于 jniLibs/arm64-v8a。
 */
object RizinNativeEngine {
    private const val TAG = "RizinNativeEngine"

    /**
     * JNI 符号由 native 层写死为 Java_com_soreverse_mcp_nativecore_RizinNativeEngine_*，
     * 因此本 object 必须叫 RizinNativeEngine —— 改名会让所有 external fun 解析不到符号，
     * 表现为「loadLibrary 成功、调用返回空串或抛 UnsatisfiedLinkError」。
     */

    @Volatile
    private var loaded = false

    @Volatile
    private var loadError = ""

    init {
        // 注意：System.loadLibrary 只做 dlopen，**不解析 JNI 符号**。
        // 符号是在首次调用某个 external fun 时才按 类名+方法名 查找的，
        // 若类名与 native 层不一致，这里会 false 地报告成功，
        // 而真正调用时抛 UnsatisfiedLinkError。因此必须做一次真实调用验证。
        loaded = runCatching {
            System.loadLibrary("rz_native")
            // 用空输入探一次：能进 native 就说明符号解析成功。
            // 空数组会走 "len <= 0" 早退分支，不会真的启动分析引擎，代价极低。
            rzDisassemble(ByteArray(0), "arm64", 0L, false, 0)
            true
        }.getOrElse { e ->
            loadError = when (e) {
                is UnsatisfiedLinkError ->
                    "JNI 符号未解析：${e.message}（Kotlin 类名必须与 native 层一致）"
                is SecurityException -> "加载被拒绝：${e.message}"
                else -> "${e::class.simpleName}: ${e.message}"
            }
            Log.w(TAG, "逆向引擎加载失败：$loadError")
            false
        }
        if (loaded) Log.i(TAG, "逆向引擎加载成功（符号已验证）")
    }

    fun available(): Boolean = loaded

    fun status(): String = if (loaded) "loaded（符号已验证）" else loadError

    // ── JNI 接口（实现位于 cpp/rizin_core.cpp）──────────────────────────
    external fun rzDisassemble(bytes: ByteArray, arch: String, address: Long, thumb: Boolean, limit: Int): String
    external fun rzAssemble(asm: String, arch: String, address: Long, thumb: Boolean): ByteArray
    external fun rzXrefs(bytes: ByteArray, arch: String, atVa: Long, direction: String): String
    external fun rzAnalyze(bytes: ByteArray, arch: String): String
    external fun rzFunctions(bytes: ByteArray, arch: String): String
    external fun rzCfg(bytes: ByteArray, arch: String, funcVa: Long): String
    external fun rzSearchBytes(bytes: ByteArray, arch: String, pattern: String, fromVa: Long, toVa: Long): String
    external fun rzScanCrypto(bytes: ByteArray, arch: String): String
    external fun rzEsilStep(bytes: ByteArray, arch: String, startVa: Long, stepCount: Int): String
    external fun rzDiff(bytesA: ByteArray, bytesB: ByteArray): String
    external fun rzCommand(bytes: ByteArray, arch: String, command: String, unsafe: Boolean): String
    external fun rzDecompile(bytes: ByteArray, arch: String, funcVa: Long): String
    external fun rzConfigureGhidra(pluginDir: String, sleighHome: String): Boolean

    // ── 安全包装：native 异常一律吞掉并返回空结果 ─────────────────────
    fun disassemble(bytes: ByteArray, arch: String, address: Long = 0L, thumb: Boolean = false, limit: Int = 200): String =
        if (!loaded) "" else runCatching { rzDisassemble(bytes, arch, address, thumb, limit) }.getOrDefault("")

    fun assemble(asm: String, arch: String, address: Long = 0L, thumb: Boolean = false): ByteArray =
        if (!loaded) ByteArray(0) else runCatching { rzAssemble(asm, arch, address, thumb) }.getOrDefault(ByteArray(0))

    fun xrefs(bytes: ByteArray, arch: String, atVa: Long, direction: String = "to"): String =
        if (!loaded) "" else runCatching { rzXrefs(bytes, arch, atVa, direction) }.getOrDefault("")

    fun analyze(bytes: ByteArray, arch: String): String =
        if (!loaded) "" else runCatching { rzAnalyze(bytes, arch) }.getOrDefault("")

    fun functions(bytes: ByteArray, arch: String): String =
        if (!loaded) "" else runCatching { rzFunctions(bytes, arch) }.getOrDefault("")

    fun cfg(bytes: ByteArray, arch: String, funcVa: Long): String =
        if (!loaded) "" else runCatching { rzCfg(bytes, arch, funcVa) }.getOrDefault("")

    fun searchBytes(bytes: ByteArray, arch: String, pattern: String, fromVa: Long = 0L, toVa: Long = 0L): String =
        if (!loaded) "" else runCatching { rzSearchBytes(bytes, arch, pattern, fromVa, toVa) }.getOrDefault("")

    fun scanCrypto(bytes: ByteArray, arch: String): String =
        if (!loaded) "" else runCatching { rzScanCrypto(bytes, arch) }.getOrDefault("")

    fun esilStep(bytes: ByteArray, arch: String, startVa: Long, stepCount: Int): String =
        if (!loaded) "" else runCatching { rzEsilStep(bytes, arch, startVa, stepCount) }.getOrDefault("")

    fun diff(bytesA: ByteArray, bytesB: ByteArray): String =
        if (!loaded) "" else runCatching { rzDiff(bytesA, bytesB) }.getOrDefault("")

    fun command(bytes: ByteArray, arch: String, cmd: String, unsafe: Boolean = false): String =
        if (!loaded) "" else runCatching { rzCommand(bytes, arch, cmd, unsafe) }.getOrDefault("")

    fun decompile(bytes: ByteArray, arch: String, funcVa: Long): String =
        if (!loaded) "" else runCatching { rzDecompile(bytes, arch, funcVa) }.getOrDefault("")

    /**
     * 将 assets/rizin/plugins/rz_ghidra_sleigh 释放到 filesDir，
     * 再告知 native 层 sleigh 路径，反编译才可用。
     */
    fun configureGhidra(context: Context): Boolean {
        if (!loaded) return false
        return runCatching {
            val sleigh = File(context.filesDir, "rizin/plugins/rz_ghidra_sleigh")
            if (!sleigh.exists() || sleigh.listFiles()?.isEmpty() != false) {
                sleigh.deleteRecursively()
                copyAssetDir(context, "rizin/plugins/rz_ghidra_sleigh", sleigh)
            }
            val ok = rzConfigureGhidra(context.applicationInfo.nativeLibraryDir, sleigh.absolutePath)
            Log.i(TAG, "ghidra 配置 ok=$ok sleigh=${sleigh.absolutePath}")
            ok
        }.getOrDefault(false)
    }

    private fun copyAssetDir(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        for (child in children) copyAssetDir(context, "$assetPath/$child", File(target, child))
    }
}

/**
 * 兼容别名：对外仍以 RizinBridge 暴露。
 *
 * JNI 要求类名与 native 符号严格对应（RizinNativeEngine），
 * 但调用方不需要知道这个实现细节，所以保留一个别名。
 */
@Suppress("unused")
typealias RizinBridge = RizinNativeEngine
