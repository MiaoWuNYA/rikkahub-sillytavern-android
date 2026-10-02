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
object RizinBridge {
    private const val TAG = "RizinBridge"

    @Volatile
    private var loaded = false

    @Volatile
    private var loadError = ""

    init {
        loaded = runCatching { System.loadLibrary("rz_native") }.isSuccess
        if (!loaded) {
            loadError = "librz_native.so 加载失败（该 ABI 可能未提供）"
            Log.w(TAG, loadError)
        } else {
            Log.i(TAG, "librz_native.so 加载成功")
        }
    }

    fun available(): Boolean = loaded

    fun status(): String = if (loaded) "loaded" else loadError

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
