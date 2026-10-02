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

    /** 单次反汇编的指令条数上限。 */
    private const val MAX_DISASM_INSN = 4000

    /** 单次反汇编返回的字符上限，约 8 万字符，足够阅读且不会撑爆上下文。 */
    private const val MAX_DISASM_CHARS = 80_000

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
    /**
     * 反汇编。
     *
     * native 侧（预编译 librz_native.so，无源码可改）的实现有个规模缺陷：
     * 它的循环条件虽是 count < limit，但每轮把「剩余全部字节」交给
     * rz_asm_mdisassemble，该调用会一次性解码 buf 内能解出的所有指令，
     * 于是 code->assembly 可能是几千条指令拼成的巨型字符串，而 count 只 +1。
     * 结果 limit=6 也可能产出上百万字符。
     *
     * 修复：在 Kotlin 层按「单条指令长度」切片，逐片调用 native，
     * 让每次 native 调用只面对一条指令，count 才真正等于指令数。
     * 指令长度按架构给定：arm64 固定 4；arm32 为 4（thumb 为 2）；
     * x86/mips 不定长，退回整体调用并用字符上限兜底。
     */
    fun disassemble(bytes: ByteArray, arch: String, address: Long = 0L, thumb: Boolean = false, limit: Int = 200): String {
        if (!loaded) return ""
        val n = limit.coerceIn(1, MAX_DISASM_INSN)
        val out = runCatching { disassembleStepped(bytes, arch, address, thumb, n) }.getOrDefault("")
        return if (out.length > MAX_DISASM_CHARS) {
            out.substring(0, MAX_DISASM_CHARS) +
                "\n... [输出已截断：共 ${out.length} 字符，上限 $MAX_DISASM_CHARS。" +
                "请用更小的 limit 或更窄的 address 范围分段查看]"
        } else out
    }

    /**
     * 按固定指令长度逐条调用 native，保证 limit 精确等于输出条数。
     *
     * address 是 rizin 语义的**虚拟地址**（与 rev_functions / rev_decompile 返回的
     * addr 同一坐标系）。native 只把它当显示前缀，不会用它定位数据，
     * 因此这里必须自己把 VA 映射成字节数组内的偏移：
     *   - 输入是 ELF 文件时，按程序头表做 VA→文件偏移 映射；
     *   - 输入是裸 hex 时，地址即数组下标。
     * 映射不出来的地址退回 0，并显式标注，避免静默给出错误结果。
     */
    private fun disassembleStepped(
        bytes: ByteArray, arch: String, address: Long, thumb: Boolean, limit: Int
    ): String {
        val step = when (arch) {
            "arm64" -> 4
            "arm32" -> if (thumb) 2 else 4
            else -> 0   // 不定长架构无法切片，走整体调用
        }

        // address 有两个彼此独立的含义，必须分开处理：
        //   1) 从哪儿开始读 —— 只有输入是 ELF 文件时才需要用它定位；
        //   2) 显示成什么地址 —— 任何输入都要用。
        // 早先把两者混为一谈，对裸 hex 数据也拿 address 去当下标，
        // 于是 addr=0x1000 在 8 字节输入上被夹到末尾，输出空串。
        val isElf = bytes.size >= 64 &&
                bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.code.toByte() &&
                bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()

        // 读取起点：ELF 按段表换算；裸数据永远从 0 开始。
        val start = if (isElf) vaToOffset(bytes, address) else 0
        // 显示基址：给了地址就用它，否则从 0 起算。
        val baseVa = if (address > 0L) address else 0L

        // 映射失败时不能退回从文件偏移 0 读——那会静默给出 ELF 头，
        // 而调用方看到地址标签仍然是自己传的值，很容易当成正确结果。
        // 宁可明确报错。
        if (isElf && address > 0L && start <= 0) {
            return "Error: 地址 0x${address.toString(16)} 未落在任何 PT_LOAD 段内，" +
                "无法映射到文件偏移。可用 binary_command 执行 'iS' 查看节区地址范围，" +
                "或 'iE' 查看入口点。"
        }

        if (step == 0) {
            val view = if (isElf && start > 0) bytes.copyOfRange(start, bytes.size) else bytes
            return normalizeDisasm(rzDisassemble(view, arch, baseVa, thumb, limit))
        }

        val sb = StringBuilder()
        var off = if (isElf) start else 0
        var produced = 0
        while (off < bytes.size && produced < limit) {
            val chunk = bytes.copyOfRange(off, minOf(off + step, bytes.size))
            // 显示地址 = 基址 + 相对起点的偏移
            val shownVa = baseVa + (off - start)
            val one = runCatching {
                rzDisassemble(chunk, arch, shownVa, thumb, 1)
            }.getOrDefault("")
            val line = normalizeDisasm(one).trim()
            if (line.isNotEmpty()) {
                sb.append(line).append('\n')
                produced++
            }
            off += step
        }
        return sb.toString()
    }

    /**
     * 把虚拟地址映射为字节数组下标。
     *
     * ELF 按 PT_LOAD 段的 p_vaddr / p_offset / p_filesz 换算；
     * 非 ELF（裸数据或 hex）直接返回原值，由调用方自行判断。
     * 映射不到时返回 0。
     */
    private fun vaToOffset(bytes: ByteArray, va: Long): Int {
        if (va <= 0L) return 0
        if (bytes.size < 64) return 0
        if (bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) {
            return 0
        }
        return try {
            val is64 = bytes[4].toInt() == 2
            val le = bytes[5].toInt() == 1
            fun u16(o: Int): Int = if (le)
                (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)
            else
                ((bytes[o].toInt() and 0xFF) shl 8) or (bytes[o + 1].toInt() and 0xFF)
            // 注意字节序方向：小端是「低地址存放最低有效字节」，
            // 而 (v shl 8) or byte 的累加方式会把先读到的字节放到最高位，
            // 也就是按大端解释。因此小端必须从高地址往低地址读，
            // 否则 p_offset / p_vaddr 会得到 0x4000000000000000 这类量级，
            // 段表匹配必然落空，地址映射静默退回 0——表现为「无论传什么地址，
            // 都从文件偏移 0 开始反汇编，吐出 ELF 头」。
            fun u32(o: Int): Long {
                var v = 0L
                for (i in 3 downTo 0) {
                    val idx = if (le) o + i else o + 3 - i
                    v = (v shl 8) or (bytes[idx].toLong() and 0xFF)
                }
                return v
            }
            fun u64(o: Int): Long {
                var v = 0L
                for (i in 7 downTo 0) {
                    val idx = if (le) o + i else o + 7 - i
                    v = (v shl 8) or (bytes[idx].toLong() and 0xFF)
                }
                return v
            }

            val phOff = if (is64) u64(0x20) else u32(0x1C).toLong()
            val phEntSize = u16(if (is64) 0x36 else 0x2A)
            val phNum = u16(if (is64) 0x38 else 0x2C)
            for (i in 0 until phNum) {
                val base = (phOff + (phEntSize.toLong() * i)).toInt()
                if (base < 0 || base + phEntSize > bytes.size) break
                val type = if (is64) u32(base) else u32(base)
                if (type != 1L) continue          // PT_LOAD
                val pOffset = if (is64) u64(base + 0x08) else u32(base + 0x04).toLong()
                val pVaddr = if (is64) u64(base + 0x10) else u32(base + 0x08).toLong()
                val pFilesz = if (is64) u64(base + 0x20) else u32(base + 0x10).toLong()
                if (va >= pVaddr && va < pVaddr + pFilesz) {
                    val off = pOffset + (va - pVaddr)
                    if (off in 0..bytes.size.toLong()) return off.toInt()
                }
            }
            0
        } catch (_: Exception) {
            0
        }
    }

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
     * 规范化反汇编输出为「每条指令一行」：
     *   0xADDR: BB BB BB BB  mnemonic operands
     *
     * rot 侧给的 assembly 字段偶尔已含地址或冗余空白，导致一行里混入多段内容。
     * 这里统一收口：拆分粘连行、压缩多余空格、丢弃空行，
     * 保证下游（模型、UI）拿到稳定格式。
     */
    private fun normalizeDisasm(raw: String): String {
        if (raw.isEmpty()) return raw
        val sb = StringBuilder(raw.length)
        for (lineRaw in raw.split('\n')) {
            val line = lineRaw.trim()
            if (line.isEmpty()) continue
            // 单条指令形如 "0x1234: 1F 20 03 D5    nop"；
            // 地址与字节之间可能是 tab 或空格，统一成 "addr: bytes  mnemonic"
            val m = Regex("^(0x[0-9a-fA-F]+):\\s*([0-9A-Fa-f ]+?)\\s{2,}(.*)$").find(line)
            if (m != null) {
                val (a, bytesPart, text) = m.destructured
                val bytesClean = bytesPart.trim().replace(Regex("\\s+"), " ")
                sb.append(a).append(": ").append(bytesClean).append("  ")
                    .append(text.trim()).append('\n')
            } else {
                // .byte 回退行或非标准行，原样保留（仅压缩尾部空白）
                sb.append(line).append('\n')
            }
        }
        return sb.toString()
    }

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
