package me.rerere.rikkahub.data.ai.python

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.knowledge.KnowledgeBaseService
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.TavernCharacterData
import me.rerere.rikkahub.data.model.TavernEmbeddedBook
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.ai.ui.UIMessagePart
import org.koin.java.KoinJavaComponent
import kotlin.uuid.Uuid

class PythonBridge(
    private val context: Context,
    private val db: AppDatabase,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val kbService: KnowledgeBaseService,
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
    // 知识库
    // ============================================================

    fun queryKnowledgeBase(query: String, limit: Int = 10): String = runBlocking {
        try {
            db.knowledgeBaseDao().getAllSources().take(limit).joinToString("\n---\n") {
                "[${it.id}] ${it.name}\n"
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun addKnowledgeEntry(title: String, content: String, assistantId: String? = null): String = runBlocking {
        try {
            val sourceId = kbService.importText(title, content, assistantId)
            if (sourceId != null) "ok: $sourceId" else "Error: empty content"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun listKnowledgeEntries(limit: Int = 20): String = runBlocking {
        try {
            db.knowledgeBaseDao().getAllSources().take(limit).joinToString("\n") {
                "[${it.id}] ${it.name} (${it.type})"
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun deleteKnowledgeEntry(id: String): String = runBlocking {
        try {
            kbService.deleteSource(id)
            "ok"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun updateKnowledgeEntry(id: String, title: String? = null, content: String? = null): String = runBlocking {
        try {
            db.knowledgeBaseDao().let { dao ->
                val existing = dao.getSourceById(id) ?: return@runBlocking "Error: 条目 $id 不存在"
                val newTitle = title ?: existing.name
                val newContent = content ?: ""
                // 删除旧条目并重新导入
                kbService.deleteSource(id)
                val newId = kbService.importText(newTitle, newContent, existing.assistantId)
                if (newId != null) "ok: $id → $newId" else "Error: 更新失败"
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // ============================================================
    // 对话（只读）
    // ============================================================

    fun listConversations(limit: Int = 10): String = runBlocking {
        try {
            db.conversationDao().getAll().first().take(limit).joinToString("\n") {
                "[${it.id}] ${it.title.ifEmpty { "无标题" }}"
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun getConversationMessages(conversationId: String, limit: Int = 50): String = runBlocking {
        try {
            val conv = conversationRepo.getConversationById(Uuid.parse(conversationId))
                ?: return@runBlocking "Error: 对话 $conversationId 不存在"
            conv.currentMessages.take(limit).joinToString("\n---\n") {
                "${it.role}: ${it.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }.take(300) ?: "(工具调用)"}"
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
                appendLine("上下文长度: ${a.contextMessageSize}")
                appendLine("流式输出: ${a.streamOutput}")
                appendLine("启用记忆: ${a.enableMemory}")
                appendLine("并行执行: ${a.enableParallelToolExecution}")
                appendLine("子Agent: ${a.enableSubAgent}")
                appendLine("自动压缩: ${a.enableAutoCompact}")
                appendLine("知识库: ${a.enableKnowledgeBase}")
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
                "context_size", "contextMessageSize" -> a.copy(contextMessageSize = int())
                "stream_output", "streamOutput" -> a.copy(streamOutput = bool())
                "enable_memory", "enableMemory" -> a.copy(enableMemory = bool())
                "enable_knowledge_base", "enableKnowledgeBase" -> a.copy(enableKnowledgeBase = bool())
                "enable_parallel_tools", "enableParallelToolExecution" -> a.copy(enableParallelToolExecution = bool())
                "enable_sub_agent", "enableSubAgent" -> a.copy(enableSubAgent = bool())
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
                "book_scan_depth" -> a.copy(tavernData = td(a).copy(embeddedBook = book(a).copy(scanDepth = int())))
                "book_token_budget" -> a.copy(tavernData = td(a).copy(embeddedBook = book(a).copy(tokenBudget = int())))

                // -- 工具开关 --
                "tool_python_engine", "tool_python" -> toggleTool(a, LocalToolOption.PythonEngine, bool())
                "tool_file_tools", "tool_file" -> toggleTool(a, LocalToolOption.FileTools, bool())
                "tool_shell_tools", "tool_shell" -> toggleTool(a, LocalToolOption.ShellTools, bool())
                "tool_asset_generator", "tool_asset" -> toggleTool(a, LocalToolOption.AssetGenerator, bool())
                "tool_data_process", "tool_data" -> toggleTool(a, LocalToolOption.DataProcess, bool())
                "tool_javascript" -> toggleTool(a, LocalToolOption.JavascriptEngine, bool())
                "tool_clipboard" -> toggleTool(a, LocalToolOption.Clipboard, bool())
                "tool_tts" -> toggleTool(a, LocalToolOption.Tts, bool())
                "tool_ask_user" -> toggleTool(a, LocalToolOption.AskUser, bool())
                "tool_present_file" -> toggleTool(a, LocalToolOption.PresentFile, bool())
                "tool_time_info" -> toggleTool(a, LocalToolOption.TimeInfo, bool())
                "tool_task_tools" -> toggleTool(a, LocalToolOption.TaskTools, bool())
                "tool_plan_mode" -> toggleTool(a, LocalToolOption.PlanMode, bool())
                "tool_calculator" -> toggleTool(a, LocalToolOption.Calculator, bool())
                "tool_worker_tools" -> toggleTool(a, LocalToolOption.WorkerTools, bool())

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
                "embedding_model", "embeddingModelId" -> s.embeddingModelId?.toString() ?: "使用聊天模型"
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
}
