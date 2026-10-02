package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.python.PythonBridge
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import org.koin.java.KoinJavaComponent

/**
 * 二进制分析工具集。
 *
 * 拆成多个独立开关：只装 Python 的用户不会被这些工具打扰，需要时逐个打开。
 * 每个工具只做一件事，描述聚焦，模型更容易选对，
 * 而不是把一堆能力塞进一个函数里让它猜参数含义。
 *
 * 底层由 librz_native.so（Rizin + Ghidra）提供，经 PythonBridge 直连 ——
 * 不走 executor 的字符串拼接，避免参数转义问题。
 */

private const val ARCH_DOC =
    "Target architecture: arm64 / arm32 / x86_64 / x86 / mips. Default arm64."

private const val TARGET_DOC =
    "Absolute path to an existing binary, e.g. /storage/emulated/0/Download/libfoo.so — " +
        "or a hex string without separators, e.g. 1f2003d5. " +
        "Use a real path the user provided or that you confirmed with file tools; " +
        "never invent a filename or pass a placeholder."

// ── 共用辅助 ─────────────────────────────────────────────────────────

/**
 * 每次调用新建一个 bridge。
 *
 * PythonBridge 持有的都是无状态引用（db / settingsStore / repo），
 * 构造代价可以忽略；用全局可变 Context 反而会在多会话并发调用工具时串台。
 * 因此把 context 作为参数逐层传下来，而不是存成全局。
 */
private fun bridge(context: Context): PythonBridge = PythonBridge(
    context = context,
    db = KoinJavaComponent.get<AppDatabase>(AppDatabase::class.java),
    settingsStore = KoinJavaComponent.get<SettingsStore>(SettingsStore::class.java),
    conversationRepo = KoinJavaComponent.get<ConversationRepository>(ConversationRepository::class.java),
)

private fun text(s: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(s.ifBlank { "(empty result)" }))

private fun props(extra: MutableMap<String, kotlinx.serialization.json.JsonElement> = mutableMapOf()) = buildJsonObject {
    put("target", buildJsonObject {
        put("type", "string")
        put("description", TARGET_DOC)
    })
    put("arch", buildJsonObject {
        put("type", "string")
        put("description", ARCH_DOC)
    })
    extra.forEach { (k, v) -> put(k, v) }
}

private fun argStr(a: kotlinx.serialization.json.JsonObject, k: String) =
    a[k]?.jsonPrimitive?.content

private fun argLong(a: kotlinx.serialization.json.JsonObject, k: String, def: Long = 0L) =
    a[k]?.jsonPrimitive?.content?.toLongOrNull() ?: def

private fun argInt(a: kotlinx.serialization.json.JsonObject, k: String, def: Int) =
    a[k]?.jsonPrimitive?.content?.toIntOrNull() ?: def

private fun argBool(a: kotlinx.serialization.json.JsonObject, k: String) =
    a[k]?.jsonPrimitive?.content?.toBoolean() ?: false

// ═══════════════════════════════════════════════════════════════
// 1. disassemble — 反汇编 / 汇编
// ═══════════════════════════════════════════════════════════════

fun createDisassembleTool(context: Context): Tool = Tool(
    name = "disassemble",
    description = "Decode machine code into readable instructions, or encode instructions into bytes.\n" +
        "Use this to read what a native binary does at the instruction level.\n" +
        "Disassembly returns one line per instruction: '0xADDR: BB BB BB BB  mnemonic operands'.\n" +
        "Assembly returns a lowercase hex string like '1f2003d5'.\n" +
        "To recover whole functions first use analyze_binary; for pseudocode use decompile.",
    parameters = {
        InputSchema.Obj(
            properties = props(mutableMapOf(
                "mode" to buildJsonObject {
                    put("type", "string")
                    put("description", "'disasm' (default) decodes bytes into instructions; " +
                        "'asm' encodes instructions into bytes.")
                },
                "instructions" to buildJsonObject {
                    put("type", "string")
                    put("description", "Assembly text to encode. Required only when mode='asm'.")
                },
                "offset" to buildJsonObject {
                    put("type", "integer")
                    put("description", "Virtual address of the first byte. Default 0.")
                },
                "count" to buildJsonObject {
                    put("type", "integer")
                    put("description", "Max instructions to decode. Default 100. " +
                        "Output beyond 32K chars gets truncated, so keep it modest and page through instead.")
                },
                "thumb" to buildJsonObject {
                    put("type", "boolean")
                    put("description", "ARM Thumb mode (arm32 only). Default false.")
                },
            )),
            required = listOf("target"),
        )
    },
    execute = { args ->
        val a = args.jsonObject
        val target = argStr(a, "target") ?: error("target is required")
        val arch = argStr(a, "arch") ?: "arm64"
        withContext(Dispatchers.IO) {
            if (argStr(a, "mode") == "asm") {
                val asm = argStr(a, "instructions") ?: error("instructions is required when mode='asm'")
                text(bridge(context).rizinAsm(asm, arch, argLong(a, "offset"), argBool(a, "thumb")))
            } else {
                text(
                    bridge(context).rizinDisasm(
                        target, arch, argLong(a, "offset"),
                        argBool(a, "thumb"), argInt(a, "count", 100)
                    )
                )
            }
        }
    },
)

// ═══════════════════════════════════════════════════════════════
// 2. analyze_binary — 函数识别 / 交叉引用 / 控制流图
// ═══════════════════════════════════════════════════════════════

fun createAnalyzeBinaryTool(context: Context): Tool = Tool(
    name = "analyze_binary",
    description = "Recover structure from a native binary: function boundaries, cross references, control flow.\n" +
        "VIEWS:\n" +
        "  functions (default) — list every recovered function with name, address, size,\n" +
        "                        instruction count, cyclomatic complexity, loop count.\n" +
        "                        Returns [{name, addr, size, ninstr, complexity, loops, isPure}].\n" +
        "                        Start here: it gives you the addresses every other view needs.\n" +
        "  summary             — engine-level stats after auto-analysis (a quick sanity check).\n" +
        "  xrefs               — who calls / is called by a given address.\n" +
        "                        Returns {xrefs:[{from, to, type, direction}]}. Needs 'address'.\n" +
        "  cfg                 — basic blocks and edges of one function. Needs 'address'.\n" +
        "Typical flow: functions -> pick a target address -> xrefs and/or cfg.",
    parameters = {
        InputSchema.Obj(
            properties = props(mutableMapOf(
                "view" to buildJsonObject {
                    put("type", "string")
                    put("description", "functions (default) / summary / xrefs / cfg.")
                },
                "address" to buildJsonObject {
                    put("type", "integer")
                    put("description", "Function or instruction address. Required for view='xrefs' and view='cfg'.")
                },
                "direction" to buildJsonObject {
                    put("type", "string")
                    put("description", "For view='xrefs': 'to' (default, who references this address) " +
                        "or 'from' (what this address references).")
                },
            )),
            required = listOf("target"),
        )
    },
    execute = { args ->
        val a = args.jsonObject
        val target = argStr(a, "target") ?: error("target is required")
        val arch = argStr(a, "arch") ?: "arm64"
        val addr = argLong(a, "address")
        val view = argStr(a, "view") ?: "functions"
        if ((view == "xrefs" || view == "cfg") && a["address"] == null) {
            return@Tool listOf(UIMessagePart.Text(
                "address is required for view='$view'. Run view='functions' first to list addresses."
            ))
        }
        withContext(Dispatchers.IO) {
            val b = bridge(context)
            val out = when (view) {
                "summary" -> b.rizinAnalyze(target, arch)
                "xrefs" -> b.rizinXrefs(target, addr, arch, argStr(a, "direction") ?: "to")
                "cfg" -> b.rizinCfg(target, addr, arch)
                else -> b.rizinFunctions(target, arch)
            }
            text(out)
        }
    },
)

// ═══════════════════════════════════════════════════════════════
// 3. decompile — 反编译为伪 C
// ═══════════════════════════════════════════════════════════════

fun createDecompileTool(context: Context): Tool = Tool(
    name = "decompile",
    description = "Recover readable pseudocode from a compiled function.\n" +
        "IMPORTANT: the very first call must warm up the decompiler — it unpacks ~13MB of\n" +
        "architecture definition data and can take a while. Pass action='prepare' for that\n" +
        "first call, then decompile normally. Later calls to prepare are cheap and safe.\n" +
        "Returns {addr, engine, backend, command, diagnostic, evidence} on success,\n" +
        "or {error, message} on failure.\n" +
        "REQUIRES a numeric function address. If the user only names a function and gives no\n" +
        "address, call analyze_binary (view='functions') first to resolve it — do not guess.",
    parameters = {
        InputSchema.Obj(
            properties = props(mutableMapOf(
                "action" to buildJsonObject {
                    put("type", "string")
                    put("description", "'prepare' warms up the decompiler (do this once before the first " +
                        "real decompile); 'decompile' (default) recovers pseudocode.")
                },
                "address" to buildJsonObject {
                    put("type", "integer")
                    put("description", "Virtual address of the function to decompile. " +
                        "Required unless action='prepare'.")
                },
            )),
            required = listOf("target"),
        )
    },
    execute = { args ->
        val a = args.jsonObject
        val target = argStr(a, "target") ?: ""
        val arch = argStr(a, "arch") ?: "arm64"
        if (argStr(a, "action") != "prepare" && a["address"] == null) {
            return@Tool listOf(UIMessagePart.Text(
                "address is required to decompile. Run analyze_binary (view='functions') first to " +
                    "list function addresses, then pass the one you want."
            ))
        }
        withContext(Dispatchers.IO) {
            val b = bridge(context)
            if (argStr(a, "action") == "prepare") {
                text(b.rizinPrepare())
            } else {
                text(b.rizinDecompile(target, argLong(a, "address"), arch))
            }
        }
    },
)

// ═══════════════════════════════════════════════════════════════
// 4. scan_binary — 字节模式搜索 / 加密常量扫描
// ═══════════════════════════════════════════════════════════════

fun createScanBinaryTool(context: Context): Tool = Tool(
    name = "scan_binary",
    description = "Search inside a native binary for byte patterns or cryptographic constants.\n" +
        "VIEWS:\n" +
        "  crypto (default) — locate known crypto material (AES S-box, CRC tables, hash constants).\n" +
        "                     Returns {hits:[{type, addr, size}]}. Handy for locating where " +
        "                     encryption lives before reading the surrounding code.\n" +
        "  pattern          — search for your own byte pattern with '??' wildcards.\n" +
        "                     Example: '1f 20 ?? d5'. Needs 'pattern'.\n" +
        "Narrow with range_start / range_end when you already know which region matters.",
    parameters = {
        InputSchema.Obj(
            properties = props(mutableMapOf(
                "view" to buildJsonObject {
                    put("type", "string")
                    put("description", "'crypto' (default) or 'pattern'.")
                },
                "pattern" to buildJsonObject {
                    put("type", "string")
                    put("description", "Byte pattern with optional '??' wildcards, e.g. '1f 20 ?? d5'. " +
                        "Required when view='pattern'.")
                },
                "range_start" to buildJsonObject {
                    put("type", "integer")
                    put("description", "Search range start address. Default 0 (beginning).")
                },
                "range_end" to buildJsonObject {
                    put("type", "integer")
                    put("description", "Search range end address. Default 0 (end of input).")
                },
            )),
            required = listOf("target"),
        )
    },
    execute = { args ->
        val a = args.jsonObject
        val target = argStr(a, "target") ?: error("target is required")
        val arch = argStr(a, "arch") ?: "arm64"
        withContext(Dispatchers.IO) {
            val b = bridge(context)
            val out = if (argStr(a, "view") == "pattern") {
                val pat = argStr(a, "pattern") ?: return@withContext listOf(UIMessagePart.Text(
                    "pattern is required for view='pattern', e.g. '1f 20 ?? d5'."
                ))
                b.rizinSearchBytes(target, pat, arch, argLong(a, "range_start"), argLong(a, "range_end"))
            } else {
                b.rizinScanCrypto(target, arch)
            }
            text(out)
        }
    },
)

// ═══════════════════════════════════════════════════════════════
// 5. emulate_code — 指令级模拟
// ═══════════════════════════════════════════════════════════════

fun createEmulateCodeTool(context: Context): Tool = Tool(
    name = "emulate_code",
    description = "Step through machine code on an emulated CPU and inspect register state.\n" +
        "Use this to trace what a small routine computes without running it on real hardware —\n" +
        "useful for unpacking a decoder loop or checking what a key-derivation stub produces.\n" +
        "Give a start address and a step count; returns a register snapshot per step.\n" +
        "Keep the step count small (tens, not thousands) — the trace grows fast.",
    parameters = {
        InputSchema.Obj(
            properties = props(mutableMapOf(
                "start_address" to buildJsonObject {
                    put("type", "integer")
                    put("description", "Address of the first instruction to emulate.")
                },
                "steps" to buildJsonObject {
                    put("type", "integer")
                    put("description", "How many instructions to execute. Default 20; keep it small.")
                },
            )),
            required = listOf("target", "start_address"),
        )
    },
    execute = { args ->
        val a = args.jsonObject
        val target = argStr(a, "target") ?: error("target is required")
        val arch = argStr(a, "arch") ?: "arm64"
        val start = a["start_address"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: error("start_address is required")
        withContext(Dispatchers.IO) {
            text(bridge(context).rizinEsil(target, start, argInt(a, "steps", 20), arch))
        }
    },
)

// ═══════════════════════════════════════════════════════════════
// 6. diff_binary — 二进制差异
// ═══════════════════════════════════════════════════════════════

fun createDiffBinaryTool(context: Context): Tool = Tool(
    name = "diff_binary",
    description = "Compare two binaries and report how they differ.\n" +
        "Useful for spotting what a patch changed, or whether two builds of the same library\n" +
        "are meaningfully different. Returns byte-level differences plus a similarity measure.\n" +
        "Both arguments are file paths (a hex string works too, but files are the usual case).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("file_a", buildJsonObject {
                    put("type", "string")
                    put("description", "Path to the first binary.")
                })
                put("file_b", buildJsonObject {
                    put("type", "string")
                    put("description", "Path to the second binary.")
                })
            },
            required = listOf("file_a", "file_b"),
        )
    },
    execute = { args ->
        val a = args.jsonObject
        val fa = argStr(a, "file_a") ?: error("file_a is required")
        val fb = argStr(a, "file_b") ?: error("file_b is required")
        withContext(Dispatchers.IO) { text(bridge(context).rizinDiff(fa, fb)) }
    },
)

// ═══════════════════════════════════════════════════════════════
// 7. run_rizin_command — 原生命令逃生舱
// ═══════════════════════════════════════════════════════════════

fun createBinaryCommandTool(context: Context): Tool = Tool(
    name = "binary_command",
    description = "Escape hatch: run a low-level analysis command against a binary for anything\n" +
        "the dedicated tools above do not cover.\n" +
        "Common commands:\n" +
        "  aaa; afl      analyze, then list all functions\n" +
        "  iI            file info (arch, bits, endianness, type)\n" +
        "  iS            list sections\n" +
        "  iz            list strings in data sections\n" +
        "  ii            list imports\n" +
        "  ie            list entry points\n" +
        "Multiple commands can be chained with ';'.\n" +
        "Prefer the specific tools when one fits — they return cleaner, structured output.",
    parameters = {
        InputSchema.Obj(
            properties = props(mutableMapOf(
                "command" to buildJsonObject {
                    put("type", "string")
                    put("description", "Command or ';'-separated command chain, e.g. 'aaa; afl'.")
                },
            )),
            required = listOf("target", "command"),
        )
    },
    execute = { args ->
        val a = args.jsonObject
        val target = argStr(a, "target") ?: error("target is required")
        val cmd = argStr(a, "command") ?: error("command is required")
        val arch = argStr(a, "arch") ?: "arm64"
        withContext(Dispatchers.IO) { text(bridge(context).rizinCmd(target, cmd, arch)) }
    },
)
