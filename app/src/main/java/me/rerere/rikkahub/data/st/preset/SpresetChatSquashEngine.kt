package me.rerere.rikkahub.data.st.preset

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * ChatSquash 执行器，对齐 SPreset inject.js 的 squashPrompts / postProcess 语义。
 *
 * 两条路径：
 * 1. `conditional_enabled=true`：真实小猫之神预设使用的“标记消息 → 严格三条 [system, assistant, user]”路径；
 * 2. 旧版路径：按连续 role 合并纯文本消息，遇到工具/图片消息原样保留。
 */
internal object SpresetChatSquashEngine {
    private const val POST_SCRIPT_FN = "__rikkahub_spreset_squash_post"

    suspend fun apply(
        messages: List<UIMessage>,
        cfg: StSpresetChatSquash,
        userName: String,
        charName: String,
    ): List<UIMessage> {
        if (messages.isEmpty() || !cfg.enabled) return messages
        return presetRunCatching(messages) {
            if (cfg.conditionalEnabled && cfg.conditionalTag.isNotBlank()) {
                // 条件模式是严格 [system, assistant, user] 契约；失败时保留原消息，
                // 绝不能静默退化成旧版两条结构，否则 FinalRequestProcessing 会抛错。
                applyConditional(messages, cfg, userName, charName) ?: messages
            } else {
                applyLegacy(messages, cfg, userName, charName)
            }
        }
    }

    /**
     * 真实小猫之神路径：
     * - 含 conditional_tag 的 system 消息作为最终 system（保留 <run_tool_replacement>）；
     * - 它之前的所有消息 + 它之后到最后一个真实 user 之前的所有消息，按原顺序和各自 role 的
     *   prefix/suffix 折叠为一条 assistant 文本；
     * - 最后一条 user 原样保留；最终严格 3 条消息。
     */
    private suspend fun applyConditional(
        messages: List<UIMessage>,
        cfg: StSpresetChatSquash,
        userName: String,
        charName: String,
    ): List<UIMessage>? {
        val markerIndex = messages.indexOfFirst { message ->
            message.parts.filterIsInstance<UIMessagePart.Text>().any { it.text.contains(cfg.conditionalTag) }
        }
        if (markerIndex < 0) return null
        val markerMessage = messages[markerIndex]
        if (markerMessage.role != MessageRole.SYSTEM) return null

        val lastRealUserIndex = messages.indexOfLast { message ->
            message.role == MessageRole.USER && !message.isSynthetic
        }
        val lastUserIndex = if (lastRealUserIndex >= 0) {
            lastRealUserIndex
        } else {
            messages.indexOfLast { it.role == MessageRole.USER }
        }
        if (lastUserIndex < 0 || lastUserIndex == markerIndex) return null

        val systemText = removeConditionalMarkup(markerMessage.toText(), cfg).trim()
        // 保留最后一个真实 user；其余消息（含 lastUser 之后注入的 AFTER_DIALOG system）
        // 全部按原顺序折叠进 assistant。
        val foldedMessages = messages.filterIndexed { index, _ ->
            index != markerIndex && index != lastUserIndex
        }
        val foldedText = buildFoldedAssistantText(foldedMessages, cfg, userName, charName)
        val postProcessed = postProcess(foldedText, cfg)
        val finalAssistantText = applyStopStrings(postProcessed, cfg)
        val systemMessage = markerMessage.copy(
            parts = listOf(UIMessagePart.Text(systemText))
        )
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(finalAssistantText)),
        )
        return listOf(systemMessage, assistantMessage, messages[lastUserIndex])
    }

    private suspend fun buildFoldedAssistantText(
        messages: List<UIMessage>,
        cfg: StSpresetChatSquash,
        userName: String,
        charName: String,
    ): String {
        val segments = mutableListOf<String>()
        messages.forEach { message ->
            val effectiveRole = effectiveRole(message.role, cfg.userRoleSystem)
            var content = removeConditionalMarkup(message.toText(), cfg)
            if (content.isBlank()) return@forEach

            val prefix = substituteNames(prefixFor(effectiveRole, cfg), userName, charName)
            val suffix = substituteNames(suffixFor(effectiveRole, cfg), userName, charName)
            segments += prefix + content + suffix
        }
        return segments.joinToString("\n")
    }

    /**
     * 旧版 ChatSquash：只合并纯文本消息；工具/图片消息不合并、原样保留。
     */
    private suspend fun applyLegacy(
        messages: List<UIMessage>,
        cfg: StSpresetChatSquash,
        userName: String,
        charName: String,
    ): List<UIMessage> {
        val output = mutableListOf<UIMessage>()
        val merged = StringBuilder()
        var lastRole: MessageRole? = null
        var squashRole = roleFromString(cfg.role)

        suspend fun flush() {
            if (merged.isEmpty()) return
            val text = applyStopStrings(postProcess(merged.toString(), cfg), cfg)
            output += UIMessage(
                role = squashRole,
                parts = listOf(UIMessagePart.Text(text)),
            )
            merged.clear()
            lastRole = null
        }

        messages.forEach { message ->
            val pureText = message.parts.isNotEmpty() &&
                message.parts.all { it is UIMessagePart.Text }
            if (!pureText) {
                flush()
                output += message
                return@forEach
            }

            val role = effectiveRole(message.role, cfg.userRoleSystem)
            val originalText = message.toText()
            if (originalText.isBlank()) return@forEach

            var content = originalText
            var separate = false
            if (cfg.enableSquashedSeparator && cfg.squashedSeparatorString.isNotBlank()) {
                val separator = cfg.squashedSeparatorString
                if (cfg.squashedSeparatorRegex) {
                    val regex = runCatching { Regex(separator) }.getOrNull()
                    if (regex != null && regex.containsMatchIn(content)) {
                        content = regex.find(content)?.let { content.removeRange(it.range) } ?: content
                        separate = true
                    }
                } else if (content.contains(separator)) {
                    content = content.replaceFirst(separator, "")
                    separate = true
                }
            }
            if (!separate && cfg.parseClewd) {
                val marker = "<|no-trans|>"
                if (content.contains(marker)) {
                    content = content.replace(marker, "")
                    separate = true
                }
            }

            if (separate) {
                flush()
                if (cfg.role == "follow") squashRole = role
                output += message.copy(
                    role = role,
                    parts = listOf(UIMessagePart.Text(content)),
                )
                return@forEach
            }

            if (cfg.role == "follow" && merged.isEmpty()) {
                squashRole = role
            }
            val previousRole = lastRole
            if (previousRole != null && previousRole != role) {
                merged.append(substituteNames(suffixFor(previousRole, cfg), userName, charName))
                merged.append(substituteNames(prefixFor(role, cfg), userName, charName))
            } else if (previousRole != null) {
                merged.append('\n')
            } else {
                merged.append(substituteNames(prefixFor(role, cfg), userName, charName))
            }
            merged.append(content)
            lastRole = role
        }
        flush()
        return output
    }

    private suspend fun postProcess(text: String, cfg: StSpresetChatSquash): String {
        var result = text
        if (cfg.parseClewd) result = parseClewdClean(result)
        if (cfg.squashedPostScriptEnable && cfg.squashedPostScript.isNotBlank()) {
            val scripted = runPostScript(cfg.squashedPostScript, result)
            if (scripted != null) result = scripted
        }
        return result
    }

    private suspend fun runPostScript(script: String, text: String): String? {
        return presetRunCatching(null) {
            withSpresetQuickJs {
                installSpresetConsole()
                installSpresetFunction(POST_SCRIPT_FN, script)
                callSpresetFunctionWithStringArg(POST_SCRIPT_FN, text)
            }
        }
    }

    private fun removeConditionalMarkup(text: String, cfg: StSpresetChatSquash): String {
        var result = text.replace("{{trim}}", "")
        if (cfg.conditionalTag.isNotBlank()) {
            result = result.replace(cfg.conditionalTag, "")
        }
        val separator = cfg.squashedSeparatorString
        if (separator.isNotBlank()) {
            result = if (cfg.squashedSeparatorRegex) {
                val regex = runCatching { Regex(separator) }.getOrNull()
                if (regex != null) regex.replace(result, "") else result.replace(separator, "")
            } else {
                result.replace(separator, "")
            }
        }
        return result
    }

    private fun effectiveRole(role: MessageRole, userRoleSystem: Boolean): MessageRole {
        return if (userRoleSystem && role == MessageRole.SYSTEM) MessageRole.USER else role
    }

    private fun roleFromString(role: String): MessageRole = when (role.trim().lowercase()) {
        "system" -> MessageRole.SYSTEM
        "user" -> MessageRole.USER
        "assistant" -> MessageRole.ASSISTANT
        else -> MessageRole.ASSISTANT
    }

    private fun prefixFor(role: MessageRole, cfg: StSpresetChatSquash): String = when (role) {
        MessageRole.SYSTEM -> cfg.prefixSystem
        MessageRole.USER -> cfg.userPrefix
        MessageRole.ASSISTANT -> cfg.charPrefix
        MessageRole.TOOL -> ""
    }

    private fun suffixFor(role: MessageRole, cfg: StSpresetChatSquash): String = when (role) {
        MessageRole.SYSTEM -> cfg.suffixSystem
        MessageRole.USER -> cfg.userSuffix
        MessageRole.ASSISTANT -> cfg.charSuffix
        MessageRole.TOOL -> ""
    }

    private fun substituteNames(value: String, userName: String, charName: String): String {
        return value
            .replace(Regex("\\{\\{trim}}", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\{\\{user}}", RegexOption.IGNORE_CASE)) { userName }
            .replace(Regex("\\{\\{char}}", RegexOption.IGNORE_CASE)) { charName }
    }

    private fun applyStopStrings(text: String, cfg: StSpresetChatSquash): String {
        if (!cfg.enableStopString) return text
        val stops = parseStopStrings(cfg.stopString)
        var earliest = -1
        stops.forEach { stop ->
            if (stop.isNotBlank()) {
                val index = text.indexOf(stop)
                if (index >= 0 && (earliest < 0 || index < earliest)) earliest = index
            }
        }
        return if (earliest >= 0) text.substring(0, earliest).trimEnd() else text
    }

    private fun parseStopStrings(raw: String): List<String> {
        val element = parseJsonElementOrNull(raw)
        if (element is JsonArray) {
            return element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        }
        return listOf(raw).filter(String::isNotBlank)
    }

    /** 对齐 SPreset HyperPmtProcess 的基本 clewd 转义清理。 */
    private fun parseClewdClean(text: String): String {
        var result = text.replace("\r\n", "\n").replace('\r', '\n')
        result = result.replace(Regex("""\s*<\|curtail\|>\s*"""), "\n")
        result = result.replace(Regex("""\s*<\|join\|>\s*"""), "")
        result = result.replace(Regex("""\s*<\|space\|>\s*"""), " ")
        result = result.replace(Regex("""<\|(\\.*?)\|>""")) { match ->
            val inner = match.groupValues[1]
            runCatching {
                spresetJson.parseToJsonElement("\"$inner\"") as? JsonPrimitive
            }.getOrNull()?.contentOrNull ?: match.value
        }
        result = result.replace(Regex("""\s*<\|.*?\|>\s*"""), "\n\n")
        return result.trim()
    }
}
