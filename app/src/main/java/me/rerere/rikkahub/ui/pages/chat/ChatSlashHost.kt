package me.rerere.rikkahub.ui.pages.chat

import android.content.Context
import androidx.lifecycle.viewModelScope
import me.rerere.ui.toast.ToastType
import me.rerere.ui.toast.ToasterState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AuthorNotePosition
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.SelectiveLogic
import me.rerere.rikkahub.data.model.TavernBookEntry
import me.rerere.rikkahub.data.st.script.StSlashExecutor
import me.rerere.rikkahub.data.st.runtime.isHidden
import me.rerere.rikkahub.data.st.runtime.withHidden
import me.rerere.rikkahub.data.st.runtime.withoutHidden
import me.rerere.rikkahub.data.st.script.togglePresetEntryForAssistant
import me.rerere.rikkahub.data.st.script.togglePresetGroupForAssistant
import me.rerere.rikkahub.data.st.expressions.ExpressionLabels
import me.rerere.rikkahub.data.st.runtime.InjectedPromptStore
import me.rerere.rikkahub.data.st.runtime.TavernRuntimeManager
import me.rerere.rikkahub.data.st.runtime.TavernVariableStore
import me.rerere.rikkahub.data.st.runtime.extractWorldName
import me.rerere.rikkahub.data.st.runtime.findLorebookIndexByName
import me.rerere.rikkahub.data.st.runtime.lorebookNameMatches
import me.rerere.rikkahub.data.st.script.StSlashHost
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.hooks.ChatInputState
import kotlin.uuid.Uuid

private val SLASH_GETVAR_REGEX = Regex("""\{\{\s*getvar::([^}]+)\}\}""", RegexOption.IGNORE_CASE)
private val SLASH_GETGLOBALVAR_REGEX = Regex("""\{\{\s*getglobalvar::([^}]+)\}\}""", RegexOption.IGNORE_CASE)
private val SLASH_CHAR_REGEX = Regex("""\{\{\s*char\s*\}\}""", RegexOption.IGNORE_CASE)
private val SLASH_USER_REGEX = Regex("""\{\{\s*user\s*\}\}""", RegexOption.IGNORE_CASE)

/**
 * 构建聊天页 STscript 宿主：把 [me.rerere.rikkahub.data.st.script.StSlashExecutor]
 * 的命令路由到 ChatVM / 酒馆运行时。
 *
 * 供两条通道共用：快速回复自动执行（UI 线程）与 JS `triggerSlash`（JavaBridge 线程）。
 * 全部状态经 VM 的 StateFlow 读取，故可在任意线程调用。
 */
internal fun buildChatSlashHost(
    context: Context,
    vm: ChatVM,
    toaster: ToasterState? = null,
    inputState: ChatInputState? = null,
    onOpenConversation: ((Uuid) -> Unit)? = null,
): StSlashHost {
    fun conversationKey(): String = vm.conversation.value.id.toString()
    var workingSettings: Settings = vm.latestSettingsSnapshot()
    fun latestSettings(): Settings = workingSettings
    fun commitSettings(transform: (Settings) -> Settings) {
        val next = transform(workingSettings)
        if (next !== workingSettings) {
            workingSettings = next
            vm.patchSettings(transform)
        }
    }
    fun currentAssistant() = latestSettings().let { s ->
        s.getAssistantById(vm.conversation.value.assistantId) ?: s.getCurrentAssistant()
    }

    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    fun runOnUiThread(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
    fun hasModel() = vm.currentChatModel.value != null
    fun toast(text: String, type: ToastType = ToastType.Normal) {
        runOnUiThread {
            toaster?.show(text, type = type)
        }
    }

    /** 对话是否正在生成（等价 ST is_send_press / is_group_generating 信号）。 */
    fun isGenerating(): Boolean = vm.conversationJob.value?.isActive == true

    /** 等待生成解锁（对齐 ST waitUntilCondition）：最多等 [maxWaitMs] 毫秒；超时仍忙碌返回 false。 */
    suspend fun waitUntilNotGenerating(maxWaitMs: Long): Boolean = withTimeoutOrNull(maxWaitMs) {
        while (isGenerating()) delay(100)
    } != null

    /** 切换某个消息节点的当前 swipe 变体索引（写回会话并持久化）。 */
    fun setNodeSelectIndex(nodeId: Uuid, newIndex: Int) {
        val c = vm.conversation.value
        vm.updateConversation(
            c.copy(
                messageNodes = c.messageNodes.map { node ->
                    if (node.id == nodeId) node.copy(selectIndex = newIndex) else node
                }
            )
        )
        vm.saveConversationAsync()
    }

    /**
     * [P0 修复] 斜杠命令的失败必须带 `(slash error: ...)` 前缀。
     *
     * JS 侧的错误判定是白名单（st-compat/slash-commands.js toExecutionResult）：只认
     * `(slash error:` / `(slash runner not ready)` / `未知命令：` / `命令不可用：` / `命令缺少`。
     * 旧实现直接返回中文自然语言（"没有可触发的消息" 等），会被判成 isError=false →
     * 卡片 `await triggerSlash('/trigger')` 以为成功 → 既不生成也不报错 → 用户只看到转圈。
     */
    fun slashError(reason: String): String = "(slash error: $reason)"

    // 表情兜底标签：本仓库 Settings 无该字段，进程内保留
    var expressionFallbackLabel: String = ExpressionLabels.DEFAULT_FALLBACK

    return object : StSlashHost {
        override val chatKey: String?
            get() = conversationKey()

        override fun varOp(cmd: String, name: String, value: String): String? {
            // 全局族命令 chatKey 传 null，与宏引擎 Variables.global 同源
            val scope = if (cmd.contains("global")) null else conversationKey()
            return when (cmd) {
                "setvar", "setglobalvar" -> {
                    TavernVariableStore.macroSet(scope, name, value)
                    "$name = $value"
                }
                "getvar", "getglobalvar" -> TavernVariableStore.macroGet(scope, name) ?: "（未设置）"
                "addvar", "addglobalvar" -> TavernVariableStore.macroAdd(scope, name, value)
                "incvar", "incglobalvar" -> TavernVariableStore.macroInc(scope, name)
                "decvar", "decglobalvar" -> TavernVariableStore.macroDec(scope, name)
                "flushvar", "flushglobalvar" -> {
                    TavernVariableStore.macroDelete(scope, name)
                    "已删除 $name"
                }
                "listvar", "listglobalvar" -> {
                    val vars = if (scope == null) TavernVariableStore.getGlobal() else TavernVariableStore.getChat(scope)
                    if (vars.isEmpty()) {
                        "（暂无变量）"
                    } else {
                        val prefix = if (scope == null) "全局: " else "本对话: "
                        prefix + vars.entries.joinToString("、") { (k, v) -> k + "=" + ((v as? JsonPrimitive)?.content ?: v.toString()) }
                    }
                }
                else -> null
            }
        }

        override fun insertMessage(role: MessageRole, text: String, name: String?, at: Int?): String? {
            vm.handleInsertMessage(role, text, name, at)
            return ""
        }

        override fun trigger(): String? {
            // [v244 BUGFIX D2] 旧实现三条分支里只有一条（成功）会真的触发生成，另外两条**静默**返回字符串：
            // 卡片 `await triggerSlash('/trigger')` 拿到的是普通字符串（isError=false）→ 不抛错、不生成、
            // 用户只看到「AI 不自动回复」。这里把每条分支都写进 tavern-runtime.log。
            if (!hasModel()) {
                TavernRuntimeManager.appendLog("warn", "[slash] /trigger 中止：未选择模型（currentChatModel=null）")
                return slashError("未选择模型，无法生成")
            }
            if (vm.conversation.value.currentMessages.isEmpty()) {
                TavernRuntimeManager.appendLog("warn", "[slash] /trigger 中止：没有可触发的消息（currentMessages 为空）")
                return slashError("没有可触发的消息")
            }
            TavernRuntimeManager.appendLog(
                "info",
                "[slash] /trigger → handleTriggerGeneration conv=" + vm.conversation.value.id,
            )
            vm.handleTriggerGeneration()
            return ""
        }

        override fun continueGen(prompt: String): String? {
            if (!hasModel()) return slashError("未选择模型，无法生成")
            vm.continueGeneration(prompt)
            return ""
        }

        override fun impersonate(prompt: String): String? {
            if (!hasModel()) return slashError("未选择模型，无法生成")
            val state = inputState ?: return null
            var announced = false
            vm.impersonateDraft(prompt) { draft ->
                // 官方流式写输入框：每次 chunk 全量替换（主线程安全派发）
                runOnUiThread {
                    state.setMessageText(draft)
                }
                if (!announced) {
                    announced = true
                    toast(context.getString(R.string.slash_toast_draft_ready))
                }
            }
            return ""
        }

        override fun sysgen(prompt: String, name: String?, at: Int?, trim: Boolean): String? {
            if (!hasModel()) return slashError("未选择模型，无法生成")
            vm.handleGenerateSystemNarration(prompt, name, at, trim)
            return ""
        }

        override fun gen(
            prompt: String,
            asRole: String,
            lock: Boolean,
            length: Int,
            name: String?,
            trim: Boolean,
        ): String? {
            if (!hasModel()) return slashError("未选择模型，无法生成")
            val asEnum = when (asRole.lowercase()) {
                "char" -> ChatService.QuietPromptAs.CHAR
                else -> ChatService.QuietPromptAs.SYSTEM
            }
            return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(60_000L) {
                    vm.quietGenerateSync(
                        prompt = prompt,
                        includeHistory = true,
                        systemPrompt = "",
                        asRole = asEnum,
                        length = length,
                        name = name,
                        trim = trim,
                    ).getOrNull()
                }
            }.orEmpty()
        }

        override fun genRaw(
            prompt: String,
            systemPrompt: String,
            length: Int,
            trim: Boolean,
        ): String? {
            if (!hasModel()) return slashError("未选择模型，无法生成")
            return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(60_000L) {
                    vm.quietGenerateSync(
                        prompt = prompt,
                        includeHistory = false,
                        systemPrompt = systemPrompt,
                        length = length,
                        trim = trim,
                    ).getOrNull()
                }
            }.orEmpty()
        }

        override fun persona(name: String, mode: String): String? {
            val s = latestSettings()
            val personas = s.personas
            val current = personas.find { it.id == s.activePersonaId }
            return when {
                name.isBlank() -> {
                    if (current != null) {
                        context.getString(R.string.slash_toast_persona_current, current.name)
                    } else {
                        context.getString(
                            R.string.slash_toast_persona_none_active,
                            personas.joinToString(context.getString(R.string.slash_field_sep)) { it.name },
                        )
                    }
                }

                name.equals("off", ignoreCase = true) || name.equals("none", ignoreCase = true) -> {
                    if (s.activePersonaId != null) {
                        commitSettings { it.copy(activePersonaId = null) }
                        context.getString(R.string.slash_toast_persona_off)
                    } else {
                        context.getString(R.string.slash_toast_persona_already_off)
                    }
                }

                else -> {
                    val target = personas.firstOrNull {
                        it.name.equals(name, ignoreCase = true) || it.title.equals(name, ignoreCase = true)
                    }
                    when {
                        // 官方 /persona-set mode=lookup：只选已有人设
                        target == null && mode == "lookup" ->
                            context.getString(R.string.slash_toast_persona_not_found, name)

                        // 官方 /persona-set mode=temp：只设置临时用户名，不找/不选人设
                        target != null && mode == "temp" -> {
                            commitSettings { it.copy(displaySetting = it.displaySetting.copy(userNickname = name)) }
                            context.getString(R.string.slash_toast_persona_temp_set, name)
                        }

                        // 官方 /persona-set mode=all（默认）：先找已有，找不到则设置临时用户名
                        target != null -> {
                            commitSettings { it.copy(activePersonaId = target.id) }
                            context.getString(R.string.slash_toast_persona_switched, target.name)
                        }

                        else -> {
                            commitSettings { it.copy(displaySetting = it.displaySetting.copy(userNickname = name)) }
                            context.getString(R.string.slash_toast_persona_fallback, name, name)
                        }
                    }
                }
            }
        }

        override fun renameChar(name: String): String? {
            val assistant = currentAssistant()
            val updated = assistant.copy(
                name = name,
                tavernData = assistant.tavernData?.copy(name = name),
            )
            commitSettings { s ->
                s.copy(
                    assistants = s.assistants.map { if (it.id == updated.id) updated else it },
                    assistantId = updated.id,
                )
            }
            return context.getString(R.string.slash_toast_renamed, name)
        }

        override fun js(code: String): String? {
            TavernRuntimeManager.runScript(code)
            return ""
        }

        override fun tavern(sub: String): String? {
            return when (sub) {
                "reload" -> {
                    TavernRuntimeManager.reloadMvu()
                    toast(
                        "正在重载酒馆运行时",
                        type = ToastType.Success,
                    )
                    ""
                }

                "status" -> {
                    TavernRuntimeManager.queryMvuStatus { status ->
                        toast("酒馆状态：" + status)
                    }
                    ""
                }

                else -> "用法：/tavern reload|status"
            }
        }

        override fun echo(text: String, title: String?): String? {
            toast(if (!title.isNullOrBlank()) "$title: $text" else text)
            return text
        }

        override fun showPopup(
            text: String,
            title: String?,
            expectResult: Boolean,
            okButton: String?,
            cancelButton: String?,
        ): String? {
            return TavernRuntimeManager.awaitSlashModal(
                me.rerere.rikkahub.data.st.runtime.SlashModalRequest.Popup(
                    text = text,
                    title = title,
                    expectResult = expectResult,
                    okButton = okButton,
                    cancelButton = cancelButton,
                )
            )
        }

        override fun showInput(
            prompt: String,
            title: String?,
            defaultValue: String,
        ): String? {
            return TavernRuntimeManager.awaitSlashModal(
                me.rerere.rikkahub.data.st.runtime.SlashModalRequest.Input(
                    prompt = prompt,
                    title = title,
                    defaultValue = defaultValue,
                )
            )
        }

        override fun showButtons(
            prompt: String,
            labels: List<String>,
            multiple: Boolean,
        ): String? {
            return TavernRuntimeManager.awaitSlashModal(
                me.rerere.rikkahub.data.st.runtime.SlashModalRequest.Buttons(
                    prompt = prompt,
                    labels = labels,
                    multiple = multiple,
                )
            )
        }

        /**
         * /expression-fallback：读取/设置表情立绘兜底标签。
         *
         * 官方语义（expressions/index.js:792-813）：不传参数 → 返回当前兜底；
         * 传合法标签 → 设为兜底并返回；匹配不到 → 返回空串且不修改。
         * 兜底额外支持官方两个特殊选项 #none / #emoji。
         */
        override fun expressionFallback(label: String): String? {
            if (label.isBlank()) return expressionFallbackLabel
            val normalized = when {
                label.equals(ExpressionLabels.OPTION_NONE, ignoreCase = true) -> ExpressionLabels.OPTION_NONE
                label.equals(ExpressionLabels.OPTION_EMOJI, ignoreCase = true) -> ExpressionLabels.OPTION_EMOJI
                else -> ExpressionLabels.matchLabel(label)
            } ?: return ""
            expressionFallbackLabel = normalized
            return normalized
        }

        override fun hideMessages(value: String, unhide: Boolean, nameFilter: String?): String? {
            val conv = vm.conversation.value
            val nodes = conv.messageNodes
            if (nodes.isEmpty()) return ""
            val maxIndex = nodes.lastIndex
            val range = if (value.isBlank()) {
                maxIndex to maxIndex
            } else {
                parseMessageRange(value, maxIndex) ?: run {
                    toast(
                        "消息范围无效：" + value,
                        type = ToastType.Warning,
                    )
                    return ""
                }
            }
            var changed = 0
            val updatedNodes = nodes.mapIndexed { index, node ->
                if (index < range.first || index > range.second) return@mapIndexed node
                if (nameFilter != null) {
                    val current = node.messages.getOrNull(node.selectIndex) ?: return@mapIndexed node
                    if (current.name != nameFilter) return@mapIndexed node
                }
                // 对齐 ST hideChatMessageRange：隐藏作用于整条消息（全部 swipe 变体）
                val newMessages = node.messages.map { msg ->
                    if (unhide) msg.withoutHidden() else msg.withHidden()
                }
                if (newMessages == node.messages) {
                    node
                } else {
                    changed++
                    node.copy(messages = newMessages)
                }
            }
            if (changed > 0) {
                vm.updateConversation(conv.copy(messageNodes = updatedNodes))
                vm.saveConversationAsync()
                toast(
                    if (unhide) "已取消隐藏 " + changed + " 条消息" else "已隐藏 " + changed + " 条消息",
                    type = ToastType.Success,
                )
            }
            return ""
        }

        override fun swipe(direction: String, await: Boolean): String? {
            if (vm.conversation.value.messageNodes.isEmpty()) {
                toast("没有可切换的消息", type = ToastType.Warning)
                return ""
            }
            vm.viewModelScope.launch {
                // 对齐 ST swipeChatCallback：先等生成解锁（10 秒超时），仍忙碌则提示并放弃
                if (!waitUntilNotGenerating(10_000)) {
                    toast("生成仍在进行，稍后再试", type = ToastType.Warning)
                    return@launch
                }
                val conv = vm.conversation.value
                val node = conv.messageNodes.lastOrNull() ?: return@launch
                if (node.messages.isEmpty()) return@launch
                val current = node.messages.getOrNull(node.selectIndex) ?: return@launch
                val lastIndex = node.messages.lastIndex

                if (direction == "left") {
                    // 对齐 ST：左向越界回绕到最后一条变体
                    val newIndex = if (node.selectIndex > 0) node.selectIndex - 1 else maxOf(0, lastIndex)
                    if (newIndex != node.selectIndex) setNodeSelectIndex(node.id, newIndex)
                    return@launch
                }

                // 右向：优先切换到下一条变体
                if (node.selectIndex < lastIndex) {
                    setNodeSelectIndex(node.id, node.selectIndex + 1)
                    return@launch
                }

                // 右向越界：对齐 ST getOverswipeBehavior —— 非用户且未隐藏 → 重新生成（生成新 swipe 变体）
                if (current.role != MessageRole.USER && !current.isHidden) {
                    if (!hasModel()) {
                        toast("未选择模型，无法生成", type = ToastType.Warning)
                        return@launch
                    }
                    vm.regenerateAtMessage(current)
                    if (await) {
                        // 先等本轮生成真正开始，再等它结束（避免任务启动竞态）
                        withTimeoutOrNull(15_000) { while (!isGenerating()) delay(50) }
                        while (isGenerating()) delay(100)
                    }
                } else {
                    // 其余（用户消息 / 已隐藏消息）：回绕到第一条变体（ST LOOP）
                    if (lastIndex > 0 && node.selectIndex != 0) setNodeSelectIndex(node.id, 0)
                }
            }
            return ""
        }

        // ---- ST Checkpoints（书签）：本仓库无 ST 检查点模型，全部降级 ----

        override fun checkpointCreate(mesId: Int?, name: String?): String? {
            val reason = "checkpoint not supported in this build"
            toast(reason, type = ToastType.Warning)
            return slashError(reason)
        }

        override fun checkpointGo(mesId: Int?): String? = slashError("checkpoint not supported in this build")

        override fun checkpointExit(): String? = slashError("checkpoint not supported in this build")

        override fun checkpointParent(): String? = ""

        override fun checkpointGet(mesId: Int?): String? = ""

        override fun checkpointList(links: Boolean): String? = "[]"

        override fun branchCreate(mesId: Int?): String? {
            val conv = vm.conversation.value
            val index = mesId ?: conv.messageNodes.lastIndex
            val node = conv.messageNodes.getOrNull(index)
            if (node == null) {
                toast("指定的消息不存在", type = ToastType.Warning)
                return ""
            }
            val message = node.messages.firstOrNull() ?: return ""
            vm.viewModelScope.launch {
                try {
                    val fork = vm.forkMessage(message)
                    runOnUiThread { onOpenConversation?.invoke(fork.id) }
                } catch (e: Exception) {
                    toast(
                        "创建分支失败：" + (e.message ?: e.javaClass.simpleName),
                        type = ToastType.Error,
                    )
                }
            }
            return ""
        }

        override fun deleteMessages(spec: String, cut: Boolean): String? {
            val nodes = vm.conversation.value.messageNodes
            if (nodes.isEmpty()) return ""
            val maxIndex = nodes.lastIndex
            val trimmed = spec.trim()
            val indices: Set<Int> = when {
                trimmed.isEmpty() -> setOf(maxIndex)
                trimmed.contains('-') -> {
                    val range = parseMessageRange(trimmed, maxIndex) ?: return ""
                    (range.first..range.second).toSet()
                }
                cut -> {
                    val idx = trimmed.toIntOrNull() ?: return ""
                    if (idx in 0..maxIndex) setOf(idx) else return ""
                }
                else -> {
                    // ST /del N：删除末尾 N 条消息
                    val count = (trimmed.toIntOrNull() ?: 1).coerceAtLeast(1)
                    val start = (nodes.size - count).coerceAtLeast(0)
                    (start..maxIndex).toSet()
                }
            }
            val removed = vm.deleteChatMessageIndices(indices)
            return if (cut) removed.joinToString("\n") else ""
        }

        override fun injectPrompt(
            id: String,
            content: String,
            position: String,
            depth: Int,
            role: String,
            scan: Boolean,
            once: Boolean,
        ): String? {
            val key = conversationKey()
            if (content.isBlank()) {
                InjectedPromptStore.uninject(key, listOf(id))
            } else {
                InjectedPromptStore.inject(
                    key,
                    listOf(
                        InjectedPromptStore.InjectedPrompt(
                            id = id,
                            position = position,
                            depth = depth,
                            role = role,
                            content = content,
                            shouldScan = scan,
                            once = once,
                        )
                    ),
                )
            }
            return ""
        }

        override fun listInjects(): String? {
            val list = InjectedPromptStore.snapshot(conversationKey())
            return buildJsonArray {
                list.forEach { item ->
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive(item.id))
                            put("position", JsonPrimitive(item.position))
                            put("depth", JsonPrimitive(item.depth))
                            put("role", JsonPrimitive(item.role))
                            put("content", JsonPrimitive(item.content))
                            put("scan", JsonPrimitive(item.shouldScan))
                            put("once", JsonPrimitive(item.once))
                        }
                    )
                }
            }.toString()
        }

        override fun flushInjects(id: String): String? {
            val key = conversationKey()
            if (id.isBlank()) {
                InjectedPromptStore.clear(key)
            } else {
                InjectedPromptStore.uninject(key, listOf(id))
            }
            return ""
        }

        override fun dupeChar(): String? {
            val assistant = currentAssistant()
            val newName = "${assistant.name} (Copy)"
            val duplicated = assistant.copy(
                id = Uuid.random(),
                name = newName,
                tavernData = assistant.tavernData?.copy(name = newName),
            )
            commitSettings { s ->
                s.copy(assistants = s.assistants + duplicated)
            }
            return newName
        }

        override fun toggleWorld(name: String, state: Boolean?): String? {
            val s = latestSettings()
            if (name.isBlank()) {
                val activeNames = s.lorebooks.filter { it.enabled }.map { it.name }
                return buildJsonArray { activeNames.forEach { add(JsonPrimitive(it)) } }.toString()
            }
            val idx = findLorebookIndexByName(s.lorebooks, name)
            if (idx < 0) return ""
            val currentBook = s.lorebooks[idx]
            val targetEnabled = state ?: !currentBook.enabled
            commitSettings { current ->
                val i = findLorebookIndexByName(current.lorebooks, name)
                if (i >= 0) {
                    val updated = current.lorebooks.toMutableList()
                    updated[i] = updated[i].copy(enabled = targetEnabled)
                    current.copy(lorebooks = updated)
                } else current
            }
            return targetEnabled.toString()
        }

        override fun getEntryField(file: String, uid: String, field: String): String? {
            val s = latestSettings()
            val assistant = currentAssistant()
            val tav = assistant.tavernData
            val embedded = tav?.embeddedBook
            val worldName = extractWorldName(tav?.extensionsRaw)
            val embeddedName = embedded?.name?.ifBlank { worldName ?: "" } ?: ""
            val normField = field.trim().lowercase()
            if (embedded != null && (file.equals("current", ignoreCase = true) ||
                    lorebookNameMatches(embeddedName, file) || lorebookNameMatches(worldName, file))
            ) {
                val uidInt = uid.toIntOrNull()
                val entry = embedded.entries.firstOrNull {
                    (uidInt != null && it.id == uidInt) || it.comment.equals(uid, ignoreCase = true)
                } ?: return ""
                return readTavernBookEntryField(entry, normField)
            }
            val bookIdx = findLorebookIndexByName(s.lorebooks, file)
            if (bookIdx < 0) return ""
            val book = s.lorebooks[bookIdx]
            val uidInt = uid.toIntOrNull()
            val entry = if (uidInt != null && uidInt in book.entries.indices) {
                book.entries[uidInt]
            } else {
                book.entries.firstOrNull { it.name.equals(uid, ignoreCase = true) }
            } ?: return ""
            return readRegexInjectionField(entry, normField)
        }

        override fun setEntryField(file: String, uid: String, field: String, value: String): String? {
            val s = latestSettings()
            val assistant = currentAssistant()
            val tav = assistant.tavernData
            val embedded = tav?.embeddedBook
            val worldName = extractWorldName(tav?.extensionsRaw)
            val embeddedName = embedded?.name?.ifBlank { worldName ?: "" } ?: ""
            val normField = field.trim().lowercase()
            if (embedded != null && (file.equals("current", ignoreCase = true) ||
                    lorebookNameMatches(embeddedName, file) || lorebookNameMatches(worldName, file))
            ) {
                val uidInt = uid.toIntOrNull()
                val newEntries = embedded.entries.map { entry ->
                    if ((uidInt != null && entry.id == uidInt) || entry.comment.equals(uid, ignoreCase = true)) {
                        writeTavernBookEntryField(entry, normField, value)
                    } else entry
                }
                val newBook = embedded.copy(entries = newEntries)
                commitSettings { current ->
                    current.copy(
                        assistants = current.assistants.map { a ->
                            if (a.id == assistant.id) {
                                a.copy(tavernData = a.tavernData?.copy(embeddedBook = newBook))
                            } else a
                        }
                    )
                }
                return ""
            }
            val bookIdx = findLorebookIndexByName(s.lorebooks, file)
            if (bookIdx < 0) return ""
            val uidInt = uid.toIntOrNull()
            commitSettings { current ->
                val idx = findLorebookIndexByName(current.lorebooks, file)
                if (idx >= 0) {
                    val targetBook = current.lorebooks[idx]
                    val newEntries = targetBook.entries.mapIndexed { index, entry ->
                        if ((uidInt != null && index == uidInt) || entry.name.equals(uid, ignoreCase = true)) {
                            writeRegexInjectionField(entry, normField, value)
                        } else entry
                    }
                    val list = current.lorebooks.toMutableList()
                    list[idx] = targetBook.copy(entries = newEntries)
                    current.copy(lorebooks = list)
                } else current
            }
            return ""
        }

        override fun createEntry(file: String, key: String, content: String, comment: String): String? {
            val s = latestSettings()
            val assistant = currentAssistant()
            val tav = assistant.tavernData
            val embedded = tav?.embeddedBook
            val worldName = extractWorldName(tav?.extensionsRaw)
            val embeddedName = embedded?.name?.ifBlank { worldName ?: "" } ?: ""
            val keys = key.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (embedded != null && (file.equals("current", ignoreCase = true) ||
                    lorebookNameMatches(embeddedName, file) || lorebookNameMatches(worldName, file))
            ) {
                val newUid = (embedded.entries.maxOfOrNull { it.id } ?: -1) + 1
                val newEntry = TavernBookEntry(
                    id = newUid,
                    keys = keys,
                    comment = comment.ifBlank { keys.firstOrNull() ?: "New Entry" },
                    content = content,
                )
                val newBook = embedded.copy(entries = embedded.entries + newEntry)
                commitSettings { current ->
                    current.copy(
                        assistants = current.assistants.map { a ->
                            if (a.id == assistant.id) {
                                a.copy(tavernData = a.tavernData?.copy(embeddedBook = newBook))
                            } else a
                        }
                    )
                }
                return newUid.toString()
            }
            val bookIdx = findLorebookIndexByName(s.lorebooks, file)
            if (bookIdx < 0) return ""
            val targetBook = s.lorebooks[bookIdx]
            val newUid = targetBook.entries.size
            val newEntry = PromptInjection.RegexInjection(
                name = comment.ifBlank { keys.firstOrNull() ?: "New Entry" },
                keywords = keys,
                content = content,
                enabled = true,
            )
            commitSettings { current ->
                val idx = findLorebookIndexByName(current.lorebooks, file)
                if (idx >= 0) {
                    val b = current.lorebooks[idx]
                    val list = current.lorebooks.toMutableList()
                    list[idx] = b.copy(entries = b.entries + newEntry)
                    current.copy(lorebooks = list)
                } else current
            }
            return newUid.toString()
        }

        override fun toggleRegex(nameOrId: String, state: Boolean?): String? {
            if (nameOrId.isBlank()) return ""
            val assistant = currentAssistant()
            commitSettings { current ->
                fun matches(r: me.rerere.rikkahub.data.model.AssistantRegex) =
                    r.id.toString() == nameOrId || r.name.equals(nameOrId, ignoreCase = true)
                val updatedGroups = current.regexGroups.map { group ->
                    if (group.regexes.none { matches(it) }) group
                    else group.copy(regexes = group.regexes.map { r ->
                        if (matches(r)) r.copy(enabled = state ?: !r.enabled) else r
                    })
                }
                val updatedAssistants = current.assistants.map { a ->
                    if (a.id == assistant.id && a.regexes.any { matches(it) }) {
                        a.copy(regexes = a.regexes.map { r ->
                            if (matches(r)) r.copy(enabled = state ?: !r.enabled) else r
                        })
                    } else a
                }
                current.copy(regexGroups = updatedGroups, assistants = updatedAssistants)
            }
            return ""
        }

        override fun setInput(text: String): String? {
            runOnUiThread {
                inputState?.setMessageText(text)
            }
            return text
        }

        override fun getMessages(spec: String, includeNames: Boolean): String? {
            val conv = vm.conversation.value
            val nodes = conv.messageNodes
            if (nodes.isEmpty()) return ""
            val maxIndex = nodes.lastIndex
            val range = if (spec.isBlank()) {
                maxIndex to maxIndex
            } else {
                parseMessageRange(spec, maxIndex) ?: return ""
            }
            val s = latestSettings()
            val assistant = currentAssistant()
            val charName = assistant.tavernData?.nickname?.takeIf { it.isNotBlank() }
                ?: assistant.name.ifBlank { "Assistant" }
            val activePersona = s.personas.firstOrNull { it.id == s.activePersonaId }
            val userName = activePersona?.name?.takeIf { it.isNotBlank() }
                ?: s.displaySetting.userNickname.ifBlank { "User" }
            val lines = mutableListOf<String>()
            for (idx in range.first..range.second) {
                val node = nodes.getOrNull(idx) ?: continue
                val msg = node.messages.getOrNull(node.selectIndex) ?: continue
                val text = msg.toText()
                if (includeNames) {
                    val speaker = msg.name?.takeIf { it.isNotBlank() } ?: when (msg.role) {
                        MessageRole.USER -> userName
                        MessageRole.ASSISTANT -> charName
                        MessageRole.SYSTEM -> "System"
                        else -> ""
                    }
                    lines += if (speaker.isNotBlank()) "$speaker: $text" else text
                } else {
                    lines += text
                }
            }
            return lines.joinToString("\n")
        }

        override fun charFind(name: String): String? {
            val q = name.trim()
            if (q.isEmpty()) return currentAssistant().name
            val s = latestSettings()
            val hit = s.assistants.firstOrNull { a ->
                a.name.equals(q, ignoreCase = true) ||
                    a.tavernData?.name?.equals(q, ignoreCase = true) == true ||
                    a.tavernData?.nickname?.equals(q, ignoreCase = true) == true ||
                    a.name.contains(q, ignoreCase = true) ||
                    a.tavernData?.tags?.any { it.equals(q, ignoreCase = true) } == true
            }
            return hit?.name.orEmpty()
        }

        override fun authorNote(text: String?): String? {
            if (text == null) return latestSettings().authorNote
            commitSettings { s ->
                s.copy(
                    authorNote = text,
                    authorNoteEnabled = text.isNotBlank() || s.authorNoteEnabled,
                )
            }
            return text
        }

        override fun authorNoteDepth(depth: Int?): String? {
            if (depth == null) return latestSettings().authorNoteDepth.toString()
            val d = depth.coerceAtLeast(0)
            commitSettings { s -> s.copy(authorNoteDepth = d) }
            return d.toString()
        }

        override fun authorNoteFrequency(freq: Int?): String? {
            if (freq == null) return latestSettings().authorNoteInterval.toString()
            val f = freq.coerceAtLeast(0)
            commitSettings { s -> s.copy(authorNoteInterval = f) }
            return f.toString()
        }

        override fun authorNotePosition(pos: String?): String? {
            if (pos.isNullOrBlank()) return latestSettings().authorNotePosition.name.lowercase()
            val mapped = when (pos.trim().lowercase()) {
                "0", "after", "in_prompt", "prompt" -> AuthorNotePosition.IN_PROMPT
                "1", "chat", "in_chat" -> AuthorNotePosition.IN_CHAT
                "2", "before", "before_prompt" -> AuthorNotePosition.BEFORE_PROMPT
                else -> return latestSettings().authorNotePosition.name.lowercase()
            }
            commitSettings { s -> s.copy(authorNotePosition = mapped) }
            return mapped.name.lowercase()
        }

        override fun authorNoteRole(role: String?): String? {
            if (role.isNullOrBlank()) return latestSettings().authorNoteRole.name.lowercase()
            val mapped = when (role.trim().lowercase()) {
                "0", "system" -> MessageRole.SYSTEM
                "1", "user" -> MessageRole.USER
                "2", "assistant", "char", "character" -> MessageRole.ASSISTANT
                else -> return latestSettings().authorNoteRole.name.lowercase()
            }
            commitSettings { s -> s.copy(authorNoteRole = mapped) }
            return mapped.name.lowercase()
        }

        override fun quickReply(label: String): String? {
            val target = label.trim()
            if (target.isEmpty()) return ""
            val qr = latestSettings().quickMessages.firstOrNull {
                it.title.equals(target, ignoreCase = true)
            } ?: return ""
            val content = qr.content.trim()
            if (content.isEmpty()) return ""
            return if (content.startsWith("/")) {
                StSlashExecutor.execute(content, this)
            } else {
                vm.handleMessageSend(listOf(UIMessagePart.Text(content)))
                content
            }
        }

        override fun quickReplyArg(name: String, value: String): String? {
            if (name.isNotBlank()) {
                TavernVariableStore.macroSet(conversationKey(), name.trim(), value)
            }
            return value
        }

        override fun translateText(text: String, targetLang: String): String? {
            if (text.isBlank()) return ""
            if (!hasModel()) return text
            val langDesc = if (targetLang.isNotBlank()) targetLang else "中文"
            val prompt = "请将以下文本准确翻译为${langDesc}，只输出翻译结果本身，不要附加解释：\n$text"
            return genRaw(prompt = prompt, systemPrompt = "", length = 0, trim = true)?.ifBlank { text } ?: text
        }

        override fun summarizeChat(prompt: String): String? {
            if (!hasModel()) return ""
            val actualPrompt = prompt.ifBlank { "请简要总结当前对话的核心剧情与状态进展。" }
            val summary = gen(prompt = actualPrompt, asRole = "system", lock = false, length = 0, name = null, trim = true).orEmpty()
            if (summary.isNotBlank()) {
                val ck = conversationKey()
                TavernVariableStore.macroSet(ck, "summary", summary)
                TavernVariableStore.macroSet(ck, "__st_memory_summary", summary)
            }
            return summary
        }

        override fun captionImage(prompt: String): String? {
            if (!hasModel()) return ""
            val latestImageUrl = vm.conversation.value.currentMessages
                .asReversed()
                .firstNotNullOfOrNull { msg ->
                    msg.parts.filterIsInstance<UIMessagePart.Image>().lastOrNull()?.url
                }
            val basePrompt = prompt.ifBlank { "请详细描述对话中最新一张画面或场景的内容。" }
            val actualPrompt = if (!latestImageUrl.isNullOrBlank() && !basePrompt.contains(latestImageUrl)) {
                "$basePrompt\n[Image Reference: $latestImageUrl]"
            } else {
                basePrompt
            }
            return gen(prompt = actualPrompt, asRole = "system", lock = false, length = 0, name = null, trim = true)
        }

        override fun executeJsSlashCommand(
            name: String,
            namedArgs: Map<String, String>,
            unnamedArgs: String,
            pipe: String,
        ): String? {
            // 防死锁保护：
            // 1) 若当前处于 WebView @JavascriptInterface (JavaBridge) 调用栈内，禁止反向重入同一 WebView 的 V8 线程；
            // 2) 若当前命令未在 JS 侧注册，O(1) 立即返回 null；
            // 3) 若意外在 Android UI 主线程被同步调用，不执行会阻塞 mainHandler.post 的 runBlocking。
            if (TavernRuntimeManager.isInJavaBridgeCall()) return null
            if (!TavernRuntimeManager.hasJsSlashCommand(name)) return null
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                TavernRuntimeManager.appendLog("warn", "[slash] executeJsSlashCommand skipped on Main thread to prevent deadlock: /$name")
                return null
            }
            return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                TavernRuntimeManager.executeJsSlashCommand(
                    name = name,
                    namedArgs = namedArgs,
                    unnamedArgs = unnamedArgs,
                    pipe = pipe,
                )
            }
        }

        override fun expandMacros(text: String): String {
            if (!text.contains("{{")) return text
            val s = latestSettings()
            val ck = conversationKey()
            val assistant = currentAssistant()
            val charName = assistant.tavernData?.nickname?.takeIf { it.isNotBlank() }
                ?: assistant.name.ifBlank { "Assistant" }
            val activePersona = s.personas.firstOrNull { it.id == s.activePersonaId && it.enabled }
                ?: s.personas.firstOrNull { it.id == s.activePersonaId }
            val userName = activePersona?.name?.takeIf { it.isNotBlank() }
                ?: s.displaySetting.userNickname.ifBlank { "User" }
            var out = text
            out = SLASH_GETVAR_REGEX.replace(out) { m ->
                val key = m.groupValues[1].trim()
                (TavernVariableStore.macroGet(ck, key)
                    ?: if (TavernVariableStore.isChatInitialized(ck) || TavernVariableStore.isDeleted(ck, key)) null else s.macroChatVariables[ck]?.get(key))
                    .orEmpty()
            }
            out = SLASH_GETGLOBALVAR_REGEX.replace(out) { m ->
                val key = m.groupValues[1].trim()
                (TavernVariableStore.macroGet(null, key)
                    ?: if (TavernVariableStore.isGlobalInitialized() || TavernVariableStore.isDeleted(null, key)) null else s.macroGlobalVariables[key])
                    .orEmpty()
            }
            out = SLASH_CHAR_REGEX.replace(out, charName)
            out = SLASH_USER_REGEX.replace(out, userName)
            if (out.contains("{{")) {
                out = runCatching {
                    me.rerere.rikkahub.data.st.macro.MacroDefinitions.registerAll()
                    val localStore = me.rerere.rikkahub.data.st.macro.InMemoryMacroVariableStore()
                    if (!TavernVariableStore.isChatInitialized(ck)) {
                        s.macroChatVariables[ck]?.forEach { (k, v) ->
                            if (!TavernVariableStore.isDeleted(ck, k)) localStore.set(k, v)
                        }
                    }
                    TavernVariableStore.getChatAsStringMap(ck).forEach { (k, v) -> localStore.set(k, v) }
                    val globalStore = me.rerere.rikkahub.data.st.macro.InMemoryMacroVariableStore()
                    if (!TavernVariableStore.isGlobalInitialized()) {
                        s.macroGlobalVariables.forEach { (k, v) ->
                            if (!TavernVariableStore.isDeleted(null, k)) globalStore.set(k, v)
                        }
                    }
                    TavernVariableStore.getGlobal().forEach { (k, v) ->
                        val strVal = (v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: v.toString()
                        globalStore.set(k, strVal)
                    }
                    val dyn = LinkedHashMap<String, me.rerere.rikkahub.data.st.macro.DynamicMacro>()
                    for ((mName, entry) in TavernRuntimeManager.macrosRegistry.get()) {
                        val lower = mName.lowercase()
                        if (!me.rerere.rikkahub.data.st.macro.MacroRegistry.hasMacro(lower) && entry.first != "function") {
                            dyn[lower] = me.rerere.rikkahub.data.st.macro.DynamicMacro.Value(entry.second)
                        }
                    }
                    val tav = assistant.tavernData
                    val env = me.rerere.rikkahub.data.st.macro.MacroEnv(
                        names = me.rerere.rikkahub.data.st.macro.MacroEnv.Names(
                            user = userName,
                            char = charName,
                            group = charName,
                            groupNotMuted = charName,
                            notChar = userName,
                        ),
                        character = me.rerere.rikkahub.data.st.macro.MacroEnv.CharacterInfo(
                            charPrompt = tav?.systemPrompt ?: "",
                            charInstruction = tav?.postHistoryInstructions ?: "",
                            description = tav?.description ?: "",
                            personality = tav?.personality ?: "",
                            scenario = tav?.scenario ?: "",
                            persona = activePersona?.description ?: "",
                            mesExamplesRaw = tav?.mesExample ?: "",
                            version = tav?.characterVersion ?: "",
                            charDepthPrompt = tav?.depthPrompt ?: "",
                            creatorNotes = tav?.creatorNotes ?: "",
                            firstMessage = tav?.firstMessage ?: "",
                            alternateGreetings = tav?.alternateGreetings ?: emptyList(),
                        ),
                        variables = me.rerere.rikkahub.data.st.macro.VariableStores(
                            local = localStore,
                            global = globalStore,
                        ),
                        dynamicMacros = dyn,
                        outlets = me.rerere.rikkahub.data.st.runtime.StWorldInfoOutlets.snapshot(ck),
                    )
                    val localBefore = localStore.snapshot()
                    val globalBefore = globalStore.snapshot()
                    val evaluated = me.rerere.rikkahub.data.st.macro.MacroEngine.evaluate(out, env)
                    val localAfter = localStore.snapshot()
                    val globalAfter = globalStore.snapshot()
                    for (k in localBefore.keys - localAfter.keys) {
                        varOp("flushvar", k, "")
                    }
                    for ((k, v) in localAfter) {
                        if (localBefore[k] != v) {
                            varOp("setvar", k, v)
                        }
                    }
                    for (k in globalBefore.keys - globalAfter.keys) {
                        varOp("flushglobalvar", k, "")
                    }
                    for ((k, v) in globalAfter) {
                        if (globalBefore[k] != v) {
                            varOp("setglobalvar", k, v)
                        }
                    }
                    evaluated
                }.getOrDefault(out)
            }
            return out
        }

        override fun executeExtendedCommand(
            cmd: String,
            named: Map<String, String>,
            unnamed: String,
            pipe: String,
        ): String? {
            val s = latestSettings()
            val ck = conversationKey()
            val assistant = currentAssistant()
            return when (cmd) {
                // ---- 1. 世界书高级命令 ----
                "getcharbook" -> assistant.tavernData?.embeddedBook?.name?.takeIf { it.isNotBlank() }
                    ?: s.lorebooks.firstOrNull { it.id in assistant.lorebookIds }?.name.orEmpty()
                "getchatbook" -> s.lorebooks.firstOrNull { it.id in vm.conversation.value.lorebookIds }?.name.orEmpty()
                "getglobalbooks" -> buildJsonArray {
                    s.lorebooks.filter { it.enabled && !it.isCharacterBook }.forEach { add(JsonPrimitive(it.name)) }
                }.toString()
                "getpersonabook" -> ""
                "findentry" -> {
                    val file = (named["file"] ?: named["book"] ?: "current").trim()
                    val field = (named["field"] ?: "key").trim().lowercase()
                    val query = unnamed.trim()
                    val embedded = assistant.tavernData?.embeddedBook
                    if (embedded != null && (file.equals("current", ignoreCase = true) || lorebookNameMatches(embedded.name, file))) {
                        val hit = embedded.entries.firstOrNull { e ->
                            readTavernBookEntryField(e, field).contains(query, ignoreCase = true)
                        }
                        hit?.id?.toString().orEmpty()
                    } else {
                        val bookIdx = findLorebookIndexByName(s.lorebooks, file)
                        val book = s.lorebooks.getOrNull(bookIdx) ?: return ""
                        val hitIdx = book.entries.indexOfFirst { e ->
                            readRegexInjectionField(e, field).contains(query, ignoreCase = true)
                        }
                        if (hitIdx >= 0) hitIdx.toString() else ""
                    }
                }
                "wi-get-timed-effect" -> {
                    val format = (named["format"] ?: "bool").trim().lowercase()
                    if (format == "number") "0" else "false"
                }
                "wi-set-timed-effect" -> slashError("world info timed effect not supported in this build")

                // ---- 2. Quick Reply：本仓库 QuickMessage 仅有 title/content，按最小面降级 ----
                "qr-list" -> {
                    buildJsonArray { s.quickMessages.forEach { add(JsonPrimitive(it.title)) } }.toString()
                }
                "qr-get" -> {
                    val label = (named["label"] ?: named["id"] ?: unnamed).trim()
                    val hit = s.quickMessages.firstOrNull {
                        it.title.equals(label, ignoreCase = true) || it.id.toString() == label
                    } ?: return ""
                    buildJsonObject {
                        put("id", JsonPrimitive(hit.id.toString()))
                        put("label", JsonPrimitive(hit.title))
                        put("message", JsonPrimitive(hit.content))
                        put("set", JsonPrimitive("Default"))
                        put("automationId", JsonPrimitive(""))
                    }.toString()
                }
                "qr-create" -> {
                    val label = (named["label"] ?: named["title"] ?: unnamed).trim()
                    val message = if (named.containsKey("label") || named.containsKey("title")) unnamed else (named["message"] ?: "")
                    val newQm = me.rerere.rikkahub.data.model.QuickMessage(title = label, content = message)
                    commitSettings { cur ->
                        cur.copy(
                            quickMessages = cur.quickMessages + newQm,
                            assistants = cur.assistants.map { a ->
                                if (a.id == assistant.id) a.copy(quickMessageIds = a.quickMessageIds + newQm.id) else a
                            },
                        )
                    }
                    label
                }
                "qr-update" -> {
                    val label = (named["label"] ?: named["id"] ?: "").trim()
                    val newLabel = named["newlabel"]?.trim()
                    val newMsg = unnamed.takeIf { it.isNotEmpty() } ?: named["message"]
                    commitSettings { cur ->
                        cur.copy(
                            quickMessages = cur.quickMessages.map { qm ->
                                if (qm.title.equals(label, ignoreCase = true) || qm.id.toString() == label) {
                                    qm.copy(title = newLabel ?: qm.title, content = newMsg ?: qm.content)
                                } else qm
                            }
                        )
                    }
                    newLabel ?: label
                }
                "qr-delete" -> {
                    val label = (named["label"] ?: named["id"] ?: unnamed).trim()
                    commitSettings { cur ->
                        cur.copy(
                            quickMessages = cur.quickMessages.filterNot { qm ->
                                qm.title.equals(label, ignoreCase = true) || qm.id.toString() == label
                            }
                        )
                    }
                    ""
                }
                "qr-set-list" -> buildJsonArray { add(JsonPrimitive("Default")) }.toString()
                "qr-set", "qrset", "qr-set-on", "qr-set-off", "qr-chat-set", "qr-chat-set-on", "qr-chat-set-off" ->
                    (named["set"] ?: named["name"] ?: unnamed).trim()
                "qr-set-create", "qr-set-update" -> (named["newname"] ?: named["name"] ?: named["set"] ?: unnamed).trim()
                "qr-set-delete" -> ""
                "qr-contextadd", "qr-contextdel", "qr-contextclear" ->
                    (named["label"] ?: named["id"] ?: unnamed).trim()
                "import" -> ""

                // ---- 3. 群聊成员控制 ----
                "member-count" -> {
                    val group = s.groupChats.firstOrNull { it.conversationId == vm.conversation.value.id || assistant.id in it.memberIds }
                    (group?.memberIds?.size ?: 1).toString()
                }
                "member-get", "member-peek" -> {
                    val group = s.groupChats.firstOrNull { it.conversationId == vm.conversation.value.id || assistant.id in it.memberIds }
                    val q = unnamed.trim()
                    val idx = q.toIntOrNull()
                    val targetAssistant = if (group != null) {
                        val memberId = if (idx != null) group.memberIds.getOrNull(idx) else group.memberIds.firstOrNull { id ->
                            s.assistants.firstOrNull { it.id == id }?.name.equals(q, ignoreCase = true)
                        }
                        s.assistants.firstOrNull { it.id == memberId }
                    } else assistant
                    targetAssistant?.name.orEmpty()
                }
                "member-add" -> {
                    val target = s.assistants.firstOrNull { it.name.equals(unnamed.trim(), ignoreCase = true) || it.id.toString() == unnamed.trim() }
                        ?: return ""
                    commitSettings { cur ->
                        cur.copy(
                            groupChats = cur.groupChats.map { g ->
                                if (g.conversationId == vm.conversation.value.id || assistant.id in g.memberIds) {
                                    g.copy(memberIds = (g.memberIds + target.id).distinct())
                                } else g
                            }
                        )
                    }
                    target.name
                }
                "member-remove" -> {
                    val target = s.assistants.firstOrNull { it.name.equals(unnamed.trim(), ignoreCase = true) || it.id.toString() == unnamed.trim() }
                        ?: return ""
                    commitSettings { cur ->
                        cur.copy(
                            groupChats = cur.groupChats.map { g ->
                                if (g.conversationId == vm.conversation.value.id || assistant.id in g.memberIds) {
                                    g.copy(memberIds = g.memberIds - target.id, disabledMemberIds = g.disabledMemberIds - target.id)
                                } else g
                            }
                        )
                    }
                    target.name
                }
                "member-enable", "member-disable" -> {
                    val disable = cmd == "member-disable"
                    val target = s.assistants.firstOrNull { it.name.equals(unnamed.trim(), ignoreCase = true) || it.id.toString() == unnamed.trim() }
                        ?: return ""
                    commitSettings { cur ->
                        cur.copy(
                            groupChats = cur.groupChats.map { g ->
                                if (g.conversationId == vm.conversation.value.id || assistant.id in g.memberIds) {
                                    val newDisabled = if (disable) (g.disabledMemberIds + target.id).distinct() else (g.disabledMemberIds - target.id)
                                    g.copy(disabledMemberIds = newDisabled)
                                } else g
                            }
                        )
                    }
                    target.name
                }
                "member-up", "member-down" -> {
                    val up = cmd == "member-up"
                    val target = s.assistants.firstOrNull { it.name.equals(unnamed.trim(), ignoreCase = true) || it.id.toString() == unnamed.trim() }
                        ?: return ""
                    commitSettings { cur ->
                        cur.copy(
                            groupChats = cur.groupChats.map { g ->
                                val idx = g.memberIds.indexOf(target.id)
                                val swapIdx = if (up) idx - 1 else idx + 1
                                if (idx >= 0 && swapIdx in g.memberIds.indices) {
                                    val list = g.memberIds.toMutableList()
                                    val tmp = list[idx]
                                    list[idx] = list[swapIdx]
                                    list[swapIdx] = tmp
                                    g.copy(memberIds = list)
                                } else g
                            }
                        )
                    }
                    target.name
                }

                // ---- 4. 角色、人设、Swipe 与会话控制 ----
                "go" -> {
                    val q = unnamed.trim()
                    val hit = s.assistants.firstOrNull {
                        it.name.equals(q, ignoreCase = true) || it.id.toString() == q || it.name.contains(q, ignoreCase = true)
                    } ?: return ""
                    commitSettings { cur -> cur.copy(assistantId = hit.id) }
                    hit.name
                }
                "char-get" -> {
                    val field = (named["field"] ?: unnamed).trim().lowercase()
                    val tav = assistant.tavernData
                    when (field) {
                        "name" -> assistant.name
                        "description", "desc" -> tav?.description ?: assistant.systemPrompt
                        "personality" -> tav?.personality.orEmpty()
                        "scenario" -> tav?.scenario.orEmpty()
                        "first_mes", "greeting" -> tav?.firstMessage.orEmpty()
                        "mes_example", "examples" -> tav?.mesExample.orEmpty()
                        "system_prompt" -> tav?.systemPrompt ?: assistant.systemPrompt
                        else -> assistant.name
                    }
                }
                "char-create" -> {
                    val name = (named["name"] ?: unnamed).trim().ifEmpty { "New Character" }
                    val created = me.rerere.rikkahub.data.model.Assistant(name = name)
                    commitSettings { cur -> cur.copy(assistants = cur.assistants + created, assistantId = created.id) }
                    name
                }
                "char-update" -> {
                    val field = (named["field"] ?: "description").trim().lowercase()
                    val tav = assistant.tavernData ?: me.rerere.rikkahub.data.model.TavernCharacterData(name = assistant.name)
                    val newTav = when (field) {
                        "name" -> tav.copy(name = unnamed)
                        "description", "desc" -> tav.copy(description = unnamed)
                        "personality" -> tav.copy(personality = unnamed)
                        "scenario" -> tav.copy(scenario = unnamed)
                        "first_mes", "greeting" -> tav.copy(firstMessage = unnamed)
                        else -> tav
                    }
                    val updated = assistant.copy(
                        name = if (field == "name") unnamed else assistant.name,
                        tavernData = newTav,
                    )
                    commitSettings { cur ->
                        cur.copy(assistants = cur.assistants.map { if (it.id == updated.id) updated else it })
                    }
                    unnamed
                }
                "char-delete" -> {
                    val q = (named["name"] ?: unnamed).trim()
                    commitSettings { cur ->
                        cur.copy(assistants = cur.assistants.filterNot { it.name.equals(q, ignoreCase = true) && cur.assistants.size > 1 })
                    }
                    ""
                }
                "persona-get" -> {
                    val p = s.personas.firstOrNull { it.id == s.activePersonaId }
                    if (named["field"] == "description") p?.description.orEmpty() else p?.name.orEmpty()
                }
                "persona-create", "persona-update" -> {
                    val name = (named["name"] ?: unnamed).trim().ifEmpty { "User" }
                    val desc = if (named.containsKey("name")) unnamed else named["description"].orEmpty()
                    val existing = s.personas.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    if (existing != null) {
                        commitSettings { cur ->
                            cur.copy(
                                personas = cur.personas.map { if (it.id == existing.id) it.copy(description = desc.ifEmpty { it.description }) else it },
                                activePersonaId = existing.id,
                            )
                        }
                    } else {
                        val np = me.rerere.rikkahub.data.model.Persona(name = name, description = desc, enabled = true)
                        commitSettings { cur -> cur.copy(personas = cur.personas + np, activePersonaId = np.id) }
                    }
                    name
                }
                "persona-delete" -> {
                    val name = (named["name"] ?: unnamed).trim()
                    commitSettings { cur ->
                        cur.copy(personas = cur.personas.filterNot { it.name.equals(name, ignoreCase = true) })
                    }
                    ""
                }
                "persona-duplicate" -> {
                    val p = s.personas.firstOrNull { it.id == s.activePersonaId } ?: return ""
                    val dup = p.copy(id = Uuid.random(), name = "${p.name} (Copy)")
                    commitSettings { cur -> cur.copy(personas = cur.personas + dup, activePersonaId = dup.id) }
                    dup.name
                }
                "persona-lock" -> {
                    val activeP = s.personas.firstOrNull { it.id == s.activePersonaId } ?: return "false"
                    val stateRaw = (named["state"] ?: unnamed).trim().lowercase()
                    val currentlyLocked = assistant.id in activeP.lockedCharacterIds
                    val targetLocked = when (stateRaw) {
                        "on", "true", "1", "yes" -> true
                        "off", "false", "0", "no" -> false
                        "toggle" -> !currentlyLocked
                        else -> if (stateRaw.isEmpty()) !currentlyLocked else currentlyLocked
                    }
                    commitSettings { cur ->
                        cur.copy(
                            personas = cur.personas.map { p ->
                                if (p.id == activeP.id) {
                                    val nextIds = if (targetLocked) {
                                        (p.lockedCharacterIds + assistant.id).distinct()
                                    } else {
                                        p.lockedCharacterIds.filterNot { it == assistant.id }
                                    }
                                    p.copy(lockedCharacterIds = nextIds)
                                } else p
                            }
                        )
                    }
                    targetLocked.toString()
                }
                "persona-sync" -> {
                    val lockedForChar = s.personas.firstOrNull { assistant.id in it.lockedCharacterIds }
                    if (lockedForChar != null && s.activePersonaId != lockedForChar.id) {
                        commitSettings { cur -> cur.copy(activePersonaId = lockedForChar.id) }
                    }
                    "true"
                }
                "getchatname" -> vm.conversation.value.title.ifBlank { vm.conversation.value.id.toString() }
                "renamechat" -> {
                    val newTitle = unnamed.trim()
                    if (newTitle.isNotEmpty()) {
                        vm.updateConversation(vm.conversation.value.copy(title = newTitle))
                    }
                    newTitle
                }
                "addswipe" -> {
                    val text = unnamed
                    val conv = vm.conversation.value
                    val nodes = conv.messageNodes.toMutableList()
                    if (nodes.isNotEmpty() && text.isNotEmpty()) {
                        val last = nodes.last()
                        val role = last.messages.firstOrNull()?.role ?: MessageRole.ASSISTANT
                        val added = UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)))
                        val newMessages = last.messages + added
                        val switchIdx = if (named["switch"]?.equals("true", ignoreCase = true) == true) newMessages.lastIndex else last.selectIndex
                        nodes[nodes.lastIndex] = last.copy(messages = newMessages, selectIndex = switchIdx)
                        vm.updateConversation(conv.copy(messageNodes = nodes))
                    }
                    text
                }
                "delswipe" -> {
                    val conv = vm.conversation.value
                    val nodes = conv.messageNodes.toMutableList()
                    if (nodes.isNotEmpty()) {
                        val last = nodes.last()
                        if (last.messages.size > 1) {
                            val targetIdx = (unnamed.trim().toIntOrNull()?.minus(1) ?: last.selectIndex).coerceIn(0, last.messages.lastIndex)
                            val newMessages = last.messages.filterIndexed { i, _ -> i != targetIdx }
                            nodes[nodes.lastIndex] = last.copy(
                                messages = newMessages,
                                selectIndex = last.selectIndex.coerceAtMost(newMessages.lastIndex),
                            )
                            vm.updateConversation(conv.copy(messageNodes = nodes))
                        }
                    }
                    ""
                }
                "regenerate" -> {
                    trigger()
                }
                "stop" -> {
                    vm.stopGeneration()
                    ""
                }
                "message-name", "sysname" -> {
                    val conv = vm.conversation.value
                    val idx = named["id"]?.toIntOrNull() ?: conv.messageNodes.lastIndex
                    val node = conv.messageNodes.getOrNull(idx) ?: return ""
                    val msg = node.messages.getOrNull(node.selectIndex) ?: return ""
                    if (unnamed.isNotEmpty()) {
                        val updatedMsg = msg.copy(name = unnamed.trim())
                        val newMessages = node.messages.toMutableList().apply { set(node.selectIndex, updatedMsg) }
                        val newNodes = conv.messageNodes.toMutableList().apply { set(idx, node.copy(messages = newMessages)) }
                        vm.updateConversation(conv.copy(messageNodes = newNodes))
                        unnamed.trim()
                    } else {
                        msg.name.orEmpty()
                    }
                }
                "delname" -> {
                    val conv = vm.conversation.value
                    val idx = named["id"]?.toIntOrNull() ?: conv.messageNodes.lastIndex
                    val node = conv.messageNodes.getOrNull(idx) ?: return ""
                    val msg = node.messages.getOrNull(node.selectIndex) ?: return ""
                    val updatedMsg = msg.copy(name = null)
                    val newMessages = node.messages.toMutableList().apply { set(node.selectIndex, updatedMsg) }
                    val newNodes = conv.messageNodes.toMutableList().apply { set(idx, node.copy(messages = newMessages)) }
                    vm.updateConversation(conv.copy(messageNodes = newNodes))
                    ""
                }
                "message-role" -> {
                    val conv = vm.conversation.value
                    val idx = named["id"]?.toIntOrNull() ?: conv.messageNodes.lastIndex
                    val node = conv.messageNodes.getOrNull(idx) ?: return ""
                    val msg = node.messages.getOrNull(node.selectIndex) ?: return ""
                    if (unnamed.isNotEmpty()) {
                        val newRole = when (unnamed.trim().lowercase()) {
                            "user" -> MessageRole.USER
                            "system" -> MessageRole.SYSTEM
                            else -> MessageRole.ASSISTANT
                        }
                        val updatedMsg = msg.copy(role = newRole)
                        val newMessages = node.messages.toMutableList().apply { set(node.selectIndex, updatedMsg) }
                        val newNodes = conv.messageNodes.toMutableList().apply { set(idx, node.copy(messages = newMessages)) }
                        vm.updateConversation(conv.copy(messageNodes = newNodes))
                        newRole.name.lowercase()
                    } else {
                        msg.role.name.lowercase()
                    }
                }
                "clipboard-set" -> {
                    runOnUiThread {
                        runCatching {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                            cm?.setPrimaryClip(android.content.ClipData.newPlainText("STscript", unnamed))
                        }
                    }
                    unnamed
                }
                "clipboard-get" -> {
                    runCatching {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    }.getOrDefault("")
                }

                // ---- 5. 预设、提示词条目、Instruct、Sysprompt 与模型 ----
                "preset" -> {
                    val targetGroup = unnamed.trim()
                    if (targetGroup.isEmpty()) {
                        me.rerere.rikkahub.data.st.runtime.enabledPresetGroups(assistant, s, vm.conversation.value.modeInjectionIds).firstOrNull().orEmpty()
                    } else {
                        commitSettings { cur ->
                            togglePresetGroupForAssistant(cur, assistant.id, targetGroup, true)
                        }
                        targetGroup
                    }
                }
                "getpromptentry" -> {
                    val target = (named["identifier"] ?: named["name"] ?: unnamed).trim()
                    val hit = s.modeInjections.firstOrNull {
                        it.name.equals(target, ignoreCase = true) || it.id.toString().equals(target, ignoreCase = true)
                    }
                    val returnType = (named["return"] ?: "bool").trim().lowercase()
                    if (returnType == "content") hit?.content.orEmpty() else (hit?.enabled == true).toString()
                }
                "setpromptentry" -> {
                    val target = (named["identifier"] ?: named["name"] ?: "").trim().ifEmpty { unnamed.trim() }
                    val stateRaw = (named["state"] ?: unnamed).trim().lowercase()
                    val enable = stateRaw == "on" || stateRaw == "true" || stateRaw == "1"
                    val hit = s.modeInjections.firstOrNull {
                        it.name.equals(target, ignoreCase = true) || it.id.toString().equals(target, ignoreCase = true)
                    } ?: return ""
                    commitSettings { cur ->
                        togglePresetEntryForAssistant(cur, assistant.id, hit.id, enable)
                    }
                    enable.toString()
                }
                // 本仓库 Assistant 无 instructTemplate 字段，instruct* 降级
                "instruct", "instruct-on", "instruct-off", "instruct-state" ->
                    slashError("instruct template not supported in this build")
                "sysprompt", "sysprompt-on", "sysprompt-off", "sysprompt-state" -> {
                    if (cmd == "sysprompt" && unnamed.isNotEmpty()) {
                        commitSettings { cur ->
                            cur.copy(assistants = cur.assistants.map { if (it.id == assistant.id) it.copy(systemPrompt = unnamed) else it })
                        }
                        unnamed
                    } else {
                        assistant.systemPrompt.isNotBlank().toString()
                    }
                }
                "model" -> {
                    val allModels = s.providers.flatMap { it.models }
                    val target = unnamed.trim()
                    if (target.isEmpty()) {
                        allModels.firstOrNull { it.id == (assistant.chatModelId ?: s.chatModelId) }?.displayName.orEmpty()
                    } else {
                        val hit = allModels.firstOrNull {
                            it.displayName.equals(target, ignoreCase = true) || it.modelId.equals(target, ignoreCase = true)
                        }
                        if (hit != null) {
                            commitSettings { cur -> cur.copy(chatModelId = hit.id) }
                            hit.displayName
                        } else ""
                    }
                }
                "api" -> s.providers.firstOrNull { p -> p.models.any { it.id == (assistant.chatModelId ?: s.chatModelId) } }?.name ?: "openai"
                "api-url", "proxy" -> {
                    val activeModelId = assistant.chatModelId ?: s.chatModelId
                    val apiFilter = named["api"]?.trim()
                    val targetProvider = if (!apiFilter.isNullOrEmpty()) {
                        s.providers.firstOrNull { it.name.equals(apiFilter, ignoreCase = true) }
                    } else {
                        s.providers.firstOrNull { p -> p.models.any { it.id == activeModelId } }
                            ?: s.providers.firstOrNull { it.enabled }
                    }
                    val newUrl = unnamed.trim()
                    if (targetProvider == null) {
                        ""
                    } else if (newUrl.isEmpty()) {
                        when (targetProvider) {
                            is me.rerere.ai.provider.ProviderSetting.OpenAI -> targetProvider.baseUrl
                            is me.rerere.ai.provider.ProviderSetting.Google -> targetProvider.baseUrl
                            is me.rerere.ai.provider.ProviderSetting.Claude -> targetProvider.baseUrl
                            is me.rerere.ai.provider.ProviderSetting.Account -> ""
                        }
                    } else {
                        val normalizedUrl = newUrl.trimEnd('/')
                        commitSettings { cur ->
                            cur.copy(
                                providers = cur.providers.map { p ->
                                    if (p.id == targetProvider.id) {
                                        when (p) {
                                            is me.rerere.ai.provider.ProviderSetting.OpenAI -> p.copy(baseUrl = normalizedUrl)
                                            is me.rerere.ai.provider.ProviderSetting.Google -> p.copy(baseUrl = normalizedUrl)
                                            is me.rerere.ai.provider.ProviderSetting.Claude -> p.copy(baseUrl = normalizedUrl)
                                            is me.rerere.ai.provider.ProviderSetting.Account -> p
                                        }
                                    } else p
                                }
                            )
                        }
                        normalizedUrl
                    }
                }

                // ---- 6. 表情立绘命令族 ----
                "expression-set" -> {
                    val label = ExpressionLabels.matchLabel(unnamed.trim()) ?: unnamed.trim()
                    if (label.isNotEmpty()) {
                        val lastMsgId = vm.conversation.value.currentMessages.lastOrNull()?.id?.toString() ?: "manual"
                        me.rerere.rikkahub.data.st.expressions.ExpressionStore.select(ck, lastMsgId, label)
                    }
                    label
                }
                "expression-last" -> me.rerere.rikkahub.data.st.expressions.ExpressionStore.selections.value[ck]?.label.orEmpty()
                "expression-classify" -> {
                    ExpressionLabels.matchLabel(unnamed.trim()) ?: expressionFallbackLabel
                }
                "expression-list" -> {
                    val labels = me.rerere.rikkahub.data.st.expressions.SpriteRepository.listLabels(context, assistant.id)
                        .ifEmpty { ExpressionLabels.ALL }
                    if (named["return"] == "json") {
                        buildJsonArray { labels.forEach { add(JsonPrimitive(it)) } }.toString()
                    } else {
                        labels.joinToString(", ")
                    }
                }
                "expression-folder-override" -> {
                    val folder = unnamed.trim()
                    TavernVariableStore.macroSet(ck, "__expression_folder_override__", folder)
                    folder
                }
                "expression-upload" -> unnamed

                // ---- 7. 正则补充命令与标签命令 ----
                "regex-state", "regex-preset" -> {
                    val q = unnamed.trim()
                    val hit = (s.regexGroups.flatMap { it.regexes } + assistant.regexes).firstOrNull {
                        it.id.toString() == q || it.name.equals(q, ignoreCase = true)
                    }
                    hit?.enabled?.toString() ?: "true"
                }
                "tag-list" -> assistant.tavernData?.tags.orEmpty().joinToString(", ")
                "tag-exists" -> (assistant.tavernData?.tags?.any { it.equals(unnamed.trim(), ignoreCase = true) } == true).toString()
                "tag-add" -> {
                    val tag = unnamed.trim()
                    if (tag.isNotEmpty()) {
                        val tav = assistant.tavernData ?: me.rerere.rikkahub.data.model.TavernCharacterData(name = assistant.name)
                        val updated = assistant.copy(tavernData = tav.copy(tags = (tav.tags + tag).distinct()))
                        commitSettings { cur -> cur.copy(assistants = cur.assistants.map { if (it.id == updated.id) updated else it }) }
                    }
                    tag
                }
                "tag-remove" -> {
                    val tag = unnamed.trim()
                    val tav = assistant.tavernData
                    if (tav != null && tag.isNotEmpty()) {
                        val updated = assistant.copy(tavernData = tav.copy(tags = tav.tags.filterNot { it.equals(tag, ignoreCase = true) }))
                        commitSettings { cur -> cur.copy(assistants = cur.assistants.map { if (it.id == updated.id) updated else it }) }
                    }
                    tag
                }
                "reroll-pick" -> {
                    TavernVariableStore.macroSet(ck, "__pick_reroll_seed__", java.util.UUID.randomUUID().toString())
                    ""
                }
                "newchat", "tempchat", "closechat" -> {
                    val newId = Uuid.random()
                    runOnUiThread { onOpenConversation?.invoke(newId) }
                    newId.toString()
                }
                "delchat" -> {
                    val curConv = vm.conversation.value
                    val newId = Uuid.random()
                    runOnUiThread {
                        vm.deleteConversation(curConv)
                        onOpenConversation?.invoke(newId)
                    }
                    ""
                }
                "extension-exists" -> {
                    val q = unnamed.trim().removePrefix("third-party/")
                    val installed = me.rerere.rikkahub.data.st.extensions.listExtensions(context)
                    val hit = installed.any { it.folder.equals(q, ignoreCase = true) || it.displayName.equals(q, ignoreCase = true) } ||
                        q.lowercase() in setOf("regex", "expressions", "quick-reply", "vectors", "memory", "summarize", "caption", "attachments")
                    hit.toString()
                }
                "extension-state" -> {
                    val q = unnamed.trim().removePrefix("third-party/")
                    (!s.huadengSettings.tavernThirdPartyDisabled.any { it.equals(q, ignoreCase = true) }).toString()
                }
                "extension-enable", "extension-disable", "extension-toggle" -> {
                    val q = unnamed.trim().removePrefix("third-party/")
                    if (q.isEmpty()) return "false"
                    val isDisabled = s.huadengSettings.tavernThirdPartyDisabled.any { it.equals(q, ignoreCase = true) }
                    val targetEnable = when (cmd) {
                        "extension-enable" -> true
                        "extension-disable" -> false
                        else -> isDisabled
                    }
                    commitSettings { cur ->
                        val nextDisabled = if (targetEnable) {
                            cur.huadengSettings.tavernThirdPartyDisabled.filterNot { it.equals(q, ignoreCase = true) }.toSet()
                        } else {
                            cur.huadengSettings.tavernThirdPartyDisabled + q
                        }
                        cur.copy(huadengSettings = cur.huadengSettings.copy(tavernThirdPartyDisabled = nextDisabled))
                    }
                    targetEnable.toString()
                }
                "bg" -> {
                    val newBg = unnamed.trim()
                    if (newBg.isEmpty()) {
                        assistant.background.orEmpty()
                    } else {
                        val updated = assistant.copy(background = newBg)
                        commitSettings { cur -> cur.copy(assistants = cur.assistants.map { if (it.id == updated.id) updated else it }) }
                        newBg
                    }
                }
                "lockbg", "unlockbg", "autobg" -> assistant.background.orEmpty()
                "theme" -> {
                    val newTheme = unnamed.trim()
                    if (newTheme.isEmpty()) {
                        s.themeId
                    } else {
                        commitSettings { cur -> cur.copy(themeId = newTheme) }
                        newTheme
                    }
                }
                "context" -> {
                    val newTpl = unnamed.trim()
                    if (newTpl.isEmpty()) {
                        assistant.contextTemplate
                    } else {
                        val updated = assistant.copy(contextTemplate = newTpl)
                        commitSettings { cur -> cur.copy(assistants = cur.assistants.map { if (it.id == updated.id) updated else it }) }
                        newTpl
                    }
                }
                "forcesave" -> {
                    TavernVariableStore.flushNow()
                    ""
                }
                else -> null
            }
        }
    }
}

private fun readTavernBookEntryField(entry: TavernBookEntry, field: String): String = when (field) {
    "content" -> entry.content
    "comment", "name", "memo" -> entry.comment
    "key", "keys" -> entry.keys.joinToString(",")
    "keysecondary", "secondary_keys", "secondarykeys" -> entry.secondaryKeys.joinToString(",")
    "constant" -> entry.constant.toString()
    "disable", "disabled" -> entry.disable.toString()
    "enabled" -> (!entry.disable).toString()
    "position" -> entry.position.toString()
    "depth" -> entry.depth.toString()
    "order", "priority", "insertion_order" -> entry.priority.toString()
    "probability" -> entry.probability.toString()
    "selectivelogic", "selective_logic" -> entry.selectiveLogic.toString()
    "sticky" -> entry.sticky.toString()
    "cooldown" -> entry.cooldown.toString()
    "delay" -> entry.delay.toString()
    "group" -> entry.group
    "role" -> entry.role
    "outlet", "outletname", "outlet_name" -> entry.outletName
    else -> ""
}

private fun writeTavernBookEntryField(entry: TavernBookEntry, field: String, value: String): TavernBookEntry = when (field) {
    "content" -> entry.copy(content = value)
    "comment", "name", "memo" -> entry.copy(comment = value)
    "key", "keys" -> entry.copy(keys = value.split(',').map { it.trim() }.filter { it.isNotEmpty() })
    "keysecondary", "secondary_keys", "secondarykeys" -> entry.copy(secondaryKeys = value.split(',').map { it.trim() }.filter { it.isNotEmpty() })
    "constant" -> entry.copy(constant = value.equals("true", ignoreCase = true) || value == "1")
    "disable", "disabled" -> entry.copy(disable = value.equals("true", ignoreCase = true) || value == "1")
    "enabled" -> entry.copy(disable = !(value.equals("true", ignoreCase = true) || value == "1"))
    "position" -> entry.copy(position = value.toIntOrNull() ?: entry.position)
    "depth" -> entry.copy(depth = value.toIntOrNull() ?: entry.depth)
    "order", "priority", "insertion_order" -> entry.copy(priority = value.toIntOrNull() ?: entry.priority)
    "probability" -> entry.copy(probability = value.toIntOrNull() ?: entry.probability)
    "selectivelogic", "selective_logic" -> entry.copy(selectiveLogic = value.toIntOrNull() ?: entry.selectiveLogic)
    "sticky" -> entry.copy(sticky = value.toIntOrNull() ?: entry.sticky)
    "cooldown" -> entry.copy(cooldown = value.toIntOrNull() ?: entry.cooldown)
    "delay" -> entry.copy(delay = value.toIntOrNull() ?: entry.delay)
    "group" -> entry.copy(group = value)
    "role" -> entry.copy(role = value.lowercase())
    "outlet", "outletname", "outlet_name" -> entry.copy(outletName = value)
    else -> entry
}

private fun readRegexInjectionField(entry: PromptInjection.RegexInjection, field: String): String = when (field) {
    "content" -> entry.content
    "comment", "name", "memo" -> entry.name
    "key", "keys" -> entry.keywords.joinToString(",")
    "keysecondary", "secondary_keys", "secondarykeys" -> entry.secondaryKeys.joinToString(",")
    "constant" -> entry.constantActive.toString()
    "disable", "disabled" -> (!entry.enabled).toString()
    "enabled" -> entry.enabled.toString()
    "depth" -> entry.injectDepth.toString()
    "order", "priority", "insertion_order" -> entry.priority.toString()
    "probability" -> entry.probability.toString()
    "sticky" -> entry.sticky.toString()
    "cooldown" -> entry.cooldown.toString()
    "delay" -> entry.delay.toString()
    "group" -> entry.group
    "role" -> entry.role.name.lowercase()
    "outlet", "outletname", "outlet_name" -> entry.outletName
    else -> ""
}

private fun writeRegexInjectionField(
    entry: PromptInjection.RegexInjection,
    field: String,
    value: String,
): PromptInjection.RegexInjection = when (field) {
    "content" -> entry.copy(content = value)
    "comment", "name", "memo" -> entry.copy(name = value)
    "key", "keys" -> entry.copy(keywords = value.split(',').map { it.trim() }.filter { it.isNotEmpty() })
    "keysecondary", "secondary_keys", "secondarykeys" -> entry.copy(secondaryKeys = value.split(',').map { it.trim() }.filter { it.isNotEmpty() })
    "constant" -> entry.copy(constantActive = value.equals("true", ignoreCase = true) || value == "1")
    "disable", "disabled" -> entry.copy(enabled = !(value.equals("true", ignoreCase = true) || value == "1"))
    "enabled" -> entry.copy(enabled = value.equals("true", ignoreCase = true) || value == "1")
    "depth" -> entry.copy(injectDepth = value.toIntOrNull() ?: entry.injectDepth)
    "order", "priority", "insertion_order" -> entry.copy(priority = value.toIntOrNull() ?: entry.priority)
    "probability" -> entry.copy(probability = value.toIntOrNull() ?: entry.probability)
    "sticky" -> entry.copy(sticky = value.toIntOrNull() ?: entry.sticky)
    "cooldown" -> entry.copy(cooldown = value.toIntOrNull() ?: entry.cooldown)
    "delay" -> entry.copy(delay = value.toIntOrNull() ?: entry.delay)
    "group" -> entry.copy(group = value)
    "role" -> entry.copy(
        role = when (value.lowercase()) {
            "user" -> MessageRole.USER
            "assistant" -> MessageRole.ASSISTANT
            else -> MessageRole.SYSTEM
        }
    )
    "outlet", "outletname", "outlet_name" -> entry.copy(outletName = value)
    else -> entry
}

/** ST stringToRange 移植：解析 `N` 或 `start-end`（闭区间，0 基）；非法/越界返回 null。 */
internal fun parseMessageRange(value: String, maxIndex: Int): Pair<Int, Int>? {
    val text = value.trim()
    val dash = text.indexOf('-')
    val start: Int
    val end: Int
    if (dash >= 0) {
        start = text.substring(0, dash).trim().toIntOrNull() ?: return null
        end = text.substring(dash + 1).trim().toIntOrNull() ?: return null
    } else {
        start = text.toIntOrNull() ?: return null
        end = start
    }
    if (start > end || start < 0 || end > maxIndex) return null
    return start to end
}


/**
 * 筛选满足指定生命周期事件或世界书 `automationId` 的 Quick Reply 列表（对齐官方 Quick Reply v2 Auto-Execute）。
 */
internal fun selectAutoTriggeredQuickReplies(
    quickMessages: List<me.rerere.rikkahub.data.model.QuickMessage>,
    eventType: String,
    automationIds: List<String> = emptyList(),
): List<me.rerere.rikkahub.data.model.QuickMessage> =
    quickMessages.filter { it.content.isNotBlank() }
