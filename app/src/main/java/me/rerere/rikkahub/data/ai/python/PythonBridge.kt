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
import kotlinx.serialization.json.JsonPrimitive
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

    /** 自动分析（函数识别、符号、字符串）。 */
    fun rizinAnalyze(input: String, arch: String = "arm64"): String =
        rizinGuard {
            checkArch(arch)
            RizinBridge.analyze(toBytes(input), arch) }

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

    /** 控制流图。 */
    fun rizinCfg(input: String, funcVa: Long, arch: String = "arm64"): String =
        rizinGuard {
            checkArch(arch)
            RizinBridge.cfg(toBytes(input), arch, funcVa) }

    /** 字节模式搜索（支持 ?? 通配）。 */
    fun rizinSearchBytes(input: String, pattern: String, arch: String = "arm64",
                         fromVa: Long = 0L, toVa: Long = 0L): String =
        rizinGuard {
            checkArch(arch)
            RizinBridge.searchBytes(toBytes(input), arch, pattern, fromVa, toVa) }

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

    /** 二进制差异。 */
    fun rizinDiff(a: String, b: String): String =
        rizinGuard { RizinBridge.diff(toBytes(a), toBytes(b)) }

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
