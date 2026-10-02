package me.rerere.rikkahub.data.ai.python

import android.content.Context
import com.soreverse.mcp.nativecore.RizinBridge
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.TavernCharacterData
import me.rerere.rikkahub.data.model.TavernEmbeddedBook
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.ai.ui.UIMessagePart
import org.koin.java.KoinJavaComponent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

class PythonBridge(
    private val context: Context,
    private val db: AppDatabase,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
) {

    private fun td(a: Assistant) = a.tavernData ?: TavernCharacterData()
    private fun book(a: Assistant) = td(a).embeddedBook ?: TavernEmbeddedBook()

    private fun toggleTool(a: Assistant, tool: LocalToolOption, enable: Boolean): Assistant {
        return if (enable) {
            if (tool in a.localTools) a else a.copy(localTools = a.localTools + tool)
        } else {
            a.copy(localTools = a.localTools - tool)
        }
    }

    // ============================================================
    // 对话（只读）
    // ============================================================

    fun listConversations(limit: Int = 10): String = runBlocking {
        try {
            // 轻量投影只取 id/title，不用 getAll()（那会把每个会话的完整实体都拉一遍）
            db.conversationDao().getRecentTitlesAnyAssistant(limit).joinToString("\n") {
                "[${it.id}] ${it.title.ifEmpty { "无标题" }}"
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun getConversationMessages(conversationId: String, limit: Int = 50): String = runBlocking {
        try {
            val conv = conversationRepo.getConversationById(Uuid.parse(conversationId))
                ?: return@runBlocking "Error: 对话 $conversationId 不存在"
            conv.currentMessages.take(limit).joinToString("\n---\n") {
                val text = it.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("") { part -> part.text }
                // take(300) 永不返回 null，?: 分支不可达导致空消息渲染成 "null"；
                // 空文本（纯工具调用轮）显式给占位
                "${it.role}: ${text.take(300).ifEmpty { "(工具调用)" }}"
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // ============================================================
    // 助理设置
    // ============================================================

    fun listAssistants(): String = runBlocking {
        try {
            settingsStore.settingsFlow.value.assistants.joinToString("\n") { a ->
                "[${a.id}] ${a.name} | 模型:${a.chatModelId?.toString()?.take(8) ?: "默认"} | " +
                "轮数:${a.totalStepsLimit} | 超时:${a.toolExecTimeout}s"
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun getAssistantSettings(assistantId: String): String = runBlocking {
        try {
            val a = settingsStore.settingsFlow.value.assistants.find { it.id.toString() == assistantId }
                ?: return@runBlocking "Error: 助理 $assistantId 不存在"
            buildString {
                appendLine("ID: ${a.id}")
                appendLine("名称: ${a.name}")
                appendLine("模型ID: ${a.chatModelId?.toString()?.take(8) ?: "使用全局默认"}")
                appendLine("System Prompt: ${a.systemPrompt?.take(200) ?: "无"}")
                appendLine("温度: ${a.temperature ?: "默认"}")
                appendLine("TopP: ${a.topP ?: "默认"}")
                appendLine("最大Token: ${a.maxTokens ?: "不限制"}")
                appendLine("流式输出: ${a.streamOutput}")
                appendLine("启用记忆: ${a.enableMemory}")
                appendLine("并行执行: ${a.enableParallelToolExecution}")
                appendLine("自动压缩: ${a.enableAutoCompact}")
                appendLine("总轮数上限: ${a.totalStepsLimit}")
                appendLine("工具超时: ${a.toolExecTimeout}s")
                appendLine("JS超时: ${a.jsTimeout}s")
                appendLine("Shell超时: ${a.shellTimeout}s")
                appendLine("时间提醒: ${a.enableTimeReminder}")
                appendLine("角色卡: ${if (a.tavernData != null) "有" else "无"}")
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun updateAssistantSetting(assistantId: String, key: String, value: String): String = runBlocking {
        try {
            val s = settingsStore.settingsFlow.value
            val idx = s.assistants.indexOfFirst { it.id.toString() == assistantId }
            if (idx == -1) return@runBlocking "Error: 助理 $assistantId 不存在"
            val a = s.assistants[idx]

            fun bool() = value.toBooleanStrictOrNull() ?: throw IllegalArgumentException("需要 true/false")
            fun int() = value.toIntOrNull() ?: throw IllegalArgumentException("需要整数")
            fun float() = value.toFloatOrNull() ?: throw IllegalArgumentException("需要数字")

            val updated = when (key) {
                "name" -> a.copy(name = value)
                "chatModelId", "model_id" -> a.copy(chatModelId = Uuid.parse(value))
                "system_prompt", "systemPrompt" -> a.copy(systemPrompt = value)
                "temperature" -> a.copy(temperature = float())
                "top_p", "topP" -> a.copy(topP = float())
                "max_tokens", "maxTokens" -> a.copy(maxTokens = int())
                "stream_output", "streamOutput" -> a.copy(streamOutput = bool())
                "enable_memory", "enableMemory" -> a.copy(enableMemory = bool())
                "enable_parallel_tools", "enableParallelToolExecution" -> a.copy(enableParallelToolExecution = bool())
                "enable_auto_compact", "enableAutoCompact" -> a.copy(enableAutoCompact = bool())
                "total_steps", "totalStepsLimit" -> a.copy(totalStepsLimit = int())
                "tool_timeout", "toolExecTimeout" -> a.copy(toolExecTimeout = int())
                "js_timeout", "jsTimeout" -> a.copy(jsTimeout = int())
                "shell_timeout", "shellTimeout" -> a.copy(shellTimeout = int())
                "background" -> a.copy(background = if (value.isEmpty()) null else value)

                // 角色卡
                "tavern_name" -> a.copy(tavernData = td(a).copy(name = value))
                "tavern_description" -> a.copy(tavernData = td(a).copy(description = value))
                "tavern_personality" -> a.copy(tavernData = td(a).copy(personality = value))
                "tavern_scenario" -> a.copy(tavernData = td(a).copy(scenario = value))
                "tavern_first_message" -> a.copy(tavernData = td(a).copy(firstMessage = value))
                "tavern_system_prompt" -> a.copy(tavernData = td(a).copy(systemPrompt = value))
                "tavern_mes_example" -> a.copy(tavernData = td(a).copy(mesExample = value))

                // 内嵌世界书
                "book_name" -> a.copy(tavernData = td(a).copy(embeddedBook = book(a).copy(name = value)))
                "book_description" -> a.copy(tavernData = td(a).copy(embeddedBook = book(a).copy(description = value)))

                // -- 工具开关 --
                "tool_python_engine", "tool_python" -> toggleTool(a, LocalToolOption.PythonEngine, bool())
                "tool_file_tools", "tool_file" -> toggleTool(a, LocalToolOption.FileTools, bool())
                "tool_shell_tools", "tool_shell" -> toggleTool(a, LocalToolOption.ShellTools, bool())
                "tool_javascript" -> toggleTool(a, LocalToolOption.JavascriptEngine, bool())
                "tool_clipboard" -> toggleTool(a, LocalToolOption.Clipboard, bool())
                "tool_tts" -> toggleTool(a, LocalToolOption.Tts, bool())
                "tool_ask_user" -> toggleTool(a, LocalToolOption.AskUser, bool())
                "tool_present_file" -> toggleTool(a, LocalToolOption.PresentFile, bool())
                "tool_time_info" -> toggleTool(a, LocalToolOption.TimeInfo, bool())
                "tool_task_tools" -> toggleTool(a, LocalToolOption.TaskTools, bool())
                "tool_calculator" -> toggleTool(a, LocalToolOption.Calculator, bool())
                "tool_worker_tools" -> toggleTool(a, LocalToolOption.WorkerTools, bool())
                // 二进制分析工具
                "tool_disassemble", "tool_disasm" -> toggleTool(a, LocalToolOption.Disassemble, bool())
                "tool_analyze_binary" -> toggleTool(a, LocalToolOption.AnalyzeBinary, bool())
                "tool_decompile" -> toggleTool(a, LocalToolOption.Decompile, bool())
                "tool_scan_binary" -> toggleTool(a, LocalToolOption.ScanBinary, bool())
                "tool_emulate_code" -> toggleTool(a, LocalToolOption.EmulateCode, bool())
                "tool_diff_binary" -> toggleTool(a, LocalToolOption.DiffBinary, bool())
                "tool_binary_command" -> toggleTool(a, LocalToolOption.BinaryCommand, bool())

                else -> return@runBlocking "Error: 未知设置 $key"
            }

            val newAssistants = s.assistants.toMutableList().apply { set(idx, updated) }
            settingsStore.update(s.copy(assistants = newAssistants))
            "ok: $key = $value"
        } catch (e: Exception) { if (e.message?.startsWith("Error:") == true) e.message!! else "Error: ${e.message}" }
    }

    // ============================================================
    // 全局设置
    // ============================================================

    fun getSetting(key: String): String = runBlocking {
        try {
            val s = settingsStore.settingsFlow.value
            when (key) {
                "theme" -> s.themeId
                "dynamic_color", "dynamicColor" -> s.dynamicColor.toString()
                "web_search", "enableWebSearch" -> s.enableWebSearch.toString()
                "default_chat_model", "chatModelId" -> s.chatModelId.toString()
                "web_server_enabled", "webServerEnabled" -> s.webServerEnabled.toString()
                "web_server_port", "webServerPort" -> s.webServerPort.toString()
                else -> "未知 key: $key"
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun updateSetting(key: String, value: String): String = runBlocking {
        try {
            val s = settingsStore.settingsFlow.value
            fun bool() = value.toBooleanStrictOrNull() ?: throw IllegalArgumentException("需要 true/false")
            fun int() = value.toIntOrNull() ?: throw IllegalArgumentException("需要整数")

            val updated = when (key) {
                "theme" -> s.copy(themeId = value)
                "dynamic_color", "dynamicColor" -> s.copy(dynamicColor = bool())
                "web_search", "enableWebSearch" -> s.copy(enableWebSearch = bool())
                "default_chat_model", "chatModelId" -> s.copy(chatModelId = Uuid.parse(value))
                "web_server_enabled", "webServerEnabled" -> s.copy(webServerEnabled = bool())
                "web_server_port", "webServerPort" -> s.copy(webServerPort = int())
                else -> return@runBlocking "Error: 未知设置 $key"
            }
            settingsStore.update(updated)
            "ok: $key = $value"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // ============================================================
    // 系统信息
    // ============================================================

    fun getAppInfo(): String = buildString {
        appendLine("App: Rikkahub")
        appendLine("Version: ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}")
        appendLine("FilesDir: ${context.filesDir.absolutePath}")
        appendLine("SkillsDir: ${context.filesDir.resolve("skills").absolutePath}")
    }

    // ============================================================
    // 逆向引擎（Rizin / Capstone / Keystone / Unicorn / Ghidra）
    // ------------------------------------------------------------
    // 全部由 librz_native.so 提供，走 com.soreverse.mcp.nativecore.RizinBridge。
    // 未加载（ABI 不匹配）时统一返回 "Error: ..." 而非抛异常，
    // 这样 AI 能拿到可读的失败原因而不是一段堆栈。
    // ============================================================

    /**
     * native 层的 profileFor() 只认这几个值，其余一律静默退化成 x86/32。
     * 静默退化会让 AI 拿到完全错误的反汇编且毫无察觉，因此在这里提前拦截。
     */
    private val VALID_ARCH = setOf("arm64", "arm32", "x86_64", "x86", "mips")

    /** native 用 %llu 打印未设值的地址字段时产生的哨兵值（UT64_MAX）。 */
    private val CFG_SENTINEL = "18446744073709551615"

    /** 逐字节差分的输入上限；超过它算法复杂度会失控。 */
    private val MAX_DIFF_BYTES = 128L * 1024

    /** 十六进制串上限：解析成字节后不超过 4MB，避免误把超长字符串当 hex 处理。 */
    private val MAX_HEX_CHARS = 8 * 1024 * 1024

    /** 二进制文件上限：整个文件会读进内存，设上限避免 OOM。 */
    private val MAX_BINARY_BYTES = 64L * 1024 * 1024

    private fun checkArch(arch: String) {
        require(arch in VALID_ARCH) {
            "无效架构 '$arch'，可选：${VALID_ARCH.joinToString(" / ")}" +
                "（native 层遇到未知值会静默按 x86/32 处理）"
        }
    }

    private fun rizinGuard(block: () -> String): String =
        if (!RizinBridge.available()) "Error: 逆向引擎未加载（${RizinBridge.status()}）"
        else try { block() } catch (e: Throwable) { "Error: ${e.message}" }

    /** 把 AI 传入的十六进制字符串或文件路径统一转成字节数组。 */
    /**
     * target 参数有两种形态：文件路径或十六进制串。
     *
     * 这里的判定顺序很重要：如果先按路径判断、失败后静默当作 hex 解析，
     * 那么"路径写错/无权限/文件不存在"会被报成"非法十六进制"，
     * 而十六进制串本身也长得像相对路径，两者很容易混淆。
     * 因此改为：**先判断它像不像 hex，不像才当路径处理**，并给出可读的错误。
     */
    private fun toBytes(input: String): ByteArray {
        val t = input.trim()
        if (t.isEmpty()) throw IllegalArgumentException("target 为空")

        // 形如 "1f2003d5" / "1f 20 03 d5" / "1f:20:03:d5"，且不包含路径分隔符
        val hexCandidate = !t.contains('/') && !t.contains('\\') && t.length <= MAX_HEX_CHARS
        if (hexCandidate) {
            val hex = t.filter { !it.isWhitespace() && it != ':' && it != '-' }
            if (hex.length % 2 == 0 && hex.isNotEmpty() && hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                return ByteArray(hex.length / 2) {
                    hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
                }
            }
        }

        val f = File(t)
        if (!f.exists()) throw IllegalArgumentException("文件不存在：$t（如需按十六进制传入，请不要包含 / 且只含 0-9a-f）")
        if (!f.canRead()) throw IllegalArgumentException("文件不可读（权限不足）：$t")
        if (!f.isFile) throw IllegalArgumentException("不是普通文件：$t")
        val size = f.length()
        if (size > MAX_BINARY_BYTES) {
            throw IllegalArgumentException(
                "文件过大（${size / 1048576}MB，上限 ${MAX_BINARY_BYTES / 1048576}MB）：$t"
            )
        }
        return f.readBytes()
    }

    fun rizinStatus(): String = "engine=${RizinBridge.status()}"

    /** 反汇编：返回 "0xADDR: BB BB  mnemonic op" 逐行文本。 */
    fun rizinDisasm(input: String, arch: String = "arm64", address: Long = 0L,
                    thumb: Boolean = false, limit: Int = 200): String =
        rizinGuard {
            checkArch(arch)
            RizinBridge.disassemble(toBytes(input), arch, address, thumb, limit)
        }

    /** 汇编：返回十六进制机器码。 */
    fun rizinAsm(asm: String, arch: String = "arm64", address: Long = 0L,
                 thumb: Boolean = false): String = rizinGuard {
            checkArch(arch)
        RizinBridge.assemble(asm, arch, address, thumb)
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * 自动分析概览。
     *
     * native 的 rzAnalyze 在 rz_core_new() 之后调用了 applyGhidraConfig()，
     * 那一步会 setenv + rz_core_loadlibs 改写 core 状态，导致紧随其后的
     * rz_core_file_open_load 返回失败，最终只能回 {"error":"open"}。
     * 对照 rzFunctions（同样流程但不调 applyGhidraConfig）则一切正常。
     *
     * 无法改 native（预编译 so），因此这里改走 rzCommand：
     * 它内部另起 core 且不碰 ghidra 配置，能稳定拿到统计信息。
     * 失败时再退回 rzAnalyze，至少不吞掉任何一条路径。
     */
    fun rizinAnalyze(input: String, arch: String = "arm64"): String =
        rizinGuard {
            checkArch(arch)
            val bytes = toBytes(input)
            // 逐条执行再合并，不要用 "iI; ie; aflc" 这种分号链：
            // 实测单条 rev_cmd(target,"iI") 正常，而 "iI; ie; aflc; izc; iSc; ii"
            // 返回空串——rizin 的 rz_core_cmd_str 在多命令链下遇到
            // 上下文不一致的命令会整体返回空，导致 summary 聚合失败。
            // 逐条调用则互不影响，且能明确指出哪一条没拿到数据。
            val cmds = listOf("iI" to "文件信息", "ie" to "入口点", "aflc" to "函数数",
                              "izc" to "字符串数", "iSc" to "节区数", "ii" to "导入数")
            val parts = mutableListOf<String>()
            val missing = mutableListOf<String>()
            for ((c, label) in cmds) {
                val r = RizinBridge.command(bytes, arch, c, false).trim()
                if (r.isNotEmpty() && !r.contains("\"error\"")) {
                    parts.add("--- $c ($label) ---\n$r")
                } else {
                    missing.add("$c($label)")
                }
            }
            if (parts.isNotEmpty()) {
                val head = "已自动分析：${parts.size}/${cmds.size} 项可用"
                val tail = if (missing.isEmpty()) "" else "\n未取得数据：${missing.joinToString(", ")}"
                return@rizinGuard "$head\n${parts.joinToString("\n")}$tail"
            }
            RizinBridge.analyze(bytes, arch)
        }

    /** 列出识别出的函数。 */
    fun rizinFunctions(input: String, arch: String = "arm64"): String =
        rizinGuard {
            checkArch(arch)
            RizinBridge.functions(toBytes(input), arch) }

    /** 交叉引用。direction 取 "to" 或 "from"。 */
    fun rizinXrefs(input: String, atVa: Long, arch: String = "arm64",
                   direction: String = "to"): String =
        rizinGuard {
            checkArch(arch)
            RizinBridge.xrefs(toBytes(input), arch, atVa, direction) }

    /**
     * 控制流图。
     *
     * native 侧基本块输出用 %llu 直接打印 jump / fail 字段，
     * 未做 UT64_MAX 检查，因此没有后继的分支会显示成 18446744073709551615。
     * 那不是一个地址，是「无目标」的哨兵值。这里把它改写成 null，
     * 避免模型误以为存在一个位于 0xFFFFFFFFFFFFFFFF 的跳转目标。
     */
    fun rizinCfg(input: String, funcVa: Long, arch: String = "arm64"): String =
        rizinGuard {
            checkArch(arch)
            val raw = RizinBridge.cfg(toBytes(input), arch, funcVa)
            normalizeCfgSentinel(raw)
        }

    /** 把 64 位无符号上限（UT64_MAX）写成 null。 */
    private fun normalizeCfgSentinel(json: String): String {
        if (json.isEmpty()) return json
        val fixed = json.replace(CFG_SENTINEL, "null")
        // 只做字符串替换，不解析重排，保证任何情况下都不丢字段
        return if (fixed == json) json else fixed
    }

    /**
     * 字节模式搜索（支持 ?? 通配）。
     *
     * native 层的 rzSearchBytes 虽然签收了 fromVa/toVa，但函数体里从未使用，
     * 因此 range 参数实际上被忽略、永远全文件扫描。
     * 无法改 native（预编译 so），改为在返回的 JSON 上做后置过滤：
     * 解析 hits 数组，丢弃落在 [fromVa, toVa] 之外的项。
     */
    fun rizinSearchBytes(input: String, pattern: String, arch: String = "arm64",
                         fromVa: Long = 0L, toVa: Long = 0L): String =
        rizinGuard {
            checkArch(arch)
            val raw = RizinBridge.searchBytes(toBytes(input), arch, pattern, fromVa, toVa)
            filterHitsByRange(raw, fromVa, toVa)
        }

    /**
     * 按地址区间过滤 searchBytes 返回的 JSON。
     * toVa <= 0 表示不设上界；解析失败时原样返回，绝不吞掉结果。
     */
    private fun filterHitsByRange(json: String, fromVa: Long, toVa: Long): String {
        if (fromVa <= 0L && toVa <= 0L) return json
        return try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
            val hits = obj["hits"]?.jsonArray ?: return json
            val kept = hits.filter { h ->
                val addr = h.jsonObject["addr"]?.jsonPrimitive?.content?.toLongOrNull()
                addr != null && (fromVa <= 0L || addr >= fromVa) && (toVa <= 0L || addr <= toVa)
            }
            val dropped = hits.size - kept.size
            buildJsonObject {
                obj.forEach { (k, v) -> if (k != "hits") put(k, v) }
                put("hits", JsonArray(kept))
                put("rangeStart", JsonPrimitive(fromVa))
                put("rangeEnd", JsonPrimitive(toVa))
                put("droppedOutsideRange", JsonPrimitive(dropped))
            }.toString()
        } catch (_: Exception) {
            json
        }
    }

    /** 扫描加密常量（AES S-box、CRC 表、魔数等）。 */
    fun rizinScanCrypto(input: String, arch: String = "arm64"): String =
        rizinGuard {
            checkArch(arch)
            RizinBridge.scanCrypto(toBytes(input), arch) }

    /** ESIL 指令级模拟执行。 */
    fun rizinEsil(input: String, startVa: Long, steps: Int, arch: String = "arm64"): String =
        rizinGuard {
            checkArch(arch)
            RizinBridge.esilStep(toBytes(input), arch, startVa, steps) }

    /**
     * 二进制差异。
     *
     * 底层是 O(N·D) 的逐字节差分：几十万字节的 .so 会退化成"跑不完"，
     * 表现为调用方卡死（native 是同步阻塞，协程超时也打断不了它）。
     * 因此在入口就限制规模，超限直接给出可执行的替代建议，而不是挂住。
     */
    fun rizinDiff(a: String, b: String): String = rizinGuard {
        val sizes = listOf(a, b).map { t ->
            val f = File(t)
            if (f.isFile) f.length() else t.length / 2L
        }
        val over = sizes.filter { it > MAX_DIFF_BYTES }
        if (over.isNotEmpty()) {
            return@rizinGuard "Error: 文件过大，逐字节差分不可行（" +
                sizes.joinToString(" vs ") { "$it 字节" } +
                "，上限 $MAX_DIFF_BYTES）。\n" +
                "建议改用以下方式定位差异：\n" +
                "  1) binary_command 执行 'iS' 对比节区表\n" +
                "  2) scan_binary view='pattern' 按特征字节搜索\n" +
                "  3) 先用 execute_python 的 hashlib 算各段哈希，只对哈希不同的段做细粒度对比"
        }
        RizinBridge.diff(toBytes(a), toBytes(b))
    }

    /**
     * 预热 Ghidra：释放 sleigh 数据（392 个文件 / 约 13MB）到 filesDir。
     *
     * 这一步是同步阻塞的，若放在首次 rev_decompile 里做，很容易撞上工具超时
     * （默认 120s；释放 + 架构定义加载在大机型上偶尔会超）。
     * 单独暴露出来，让 AI 在真正反编译之前先热身一次，
     * 之后 rev_decompile 就只剩纯反编译耗时。已释放过则直接返回，可安全重复调用。
     */
    fun rizinPrepare(): String {
        if (!RizinBridge.available()) return "Error: 逆向引擎未加载（${RizinBridge.status()}）"
        val t0 = System.currentTimeMillis()
        return try {
            val ok = RizinBridge.configureGhidra(context)
            val ms = System.currentTimeMillis() - t0
            if (ok) "ok: Ghidra sleigh 就绪（${ms}ms）" else "Error: sleigh 配置失败（${ms}ms）"
        } catch (e: Throwable) {
            "Error: ${e.message}"
        }
    }

    /** 反编译为伪 C 代码（依赖 sleigh 数据，首次调用会自动释放）。 */
    fun rizinDecompile(input: String, funcVa: Long, arch: String = "arm64"): String = rizinGuard {
            checkArch(arch)
        RizinBridge.configureGhidra(context)
        RizinBridge.decompile(toBytes(input), arch, funcVa)
    }

    /** 执行任意 rizin 命令（如 `aaa; afl`、`iS`、`iz`）。 */
    fun rizinCmd(input: String, cmd: String, arch: String = "arm64"): String =
        rizinGuard {
            checkArch(arch)
            RizinBridge.command(toBytes(input), arch, cmd, false) }
}
