package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Skill 自动触发转换器 — Claude Code 风格
 * 有关键词的：扫描对话匹配，命中后直接注入 SKILL.md body
 * 无关键词的：由 use_skill 工具处理（工具 systemPrompt 列出全部 skill）
 */
object SkillAutoTriggerTransformer : InputMessageTransformer, KoinComponent {

    private val skillManager: SkillManager by inject()

    /**
     * 按用户轮冻结匹配结果：agentic 工具循环每步重跑本 transformer，若每步重扫
     * 增长的消息列表，工具输出里新出现的触发词会让 SKILL.md 在第 N 步突然出现在
     * 前缀顶部，整条前缀缓存作废。匹配结果按 (助手, 对话, 最新用户消息) 冻结，
     * 一轮内只算一次。
     */
    private val frozenMatches =
        java.util.concurrent.ConcurrentHashMap<String, Pair<String, FrozenSkillMatch>>()

    private class FrozenSkillMatch(
        val beforeSystem: List<SkillMetadata>,
        val afterSystem: List<SkillMetadata>,
        val inChat: List<SkillMetadata>,
        val anchorMsgId: String?,
    )

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val enabledNames = ctx.assistant.enabledSkills
        if (enabledNames.isEmpty()) return messages

        val allSkills = skillManager.listSkills()
        val enabledSkills = allSkills.filter { it.name in enabledNames }
        if (enabledSkills.isEmpty()) return messages

        val turnKey = "${ctx.assistant.id}:${ctx.conversationId ?: "no-conversation"}"
        val lastUserMsgId = messages.lastOrNull { it.role == MessageRole.USER }?.id?.toString()
        val frozen = lastUserMsgId?.let { id ->
            frozenMatches[turnKey]?.takeIf { it.first == id }?.second
        }

        val (beforeSystem, afterSystem, inChat) = if (frozen != null) {
            Triple(frozen.beforeSystem, frozen.afterSystem, frozen.inChat)
        } else {
            // 拼接上下文用于匹配
            val context = messages.joinToString("\n") { it.toText() }

            // 分类 skill
            val before = mutableListOf<SkillMetadata>()
            val after = mutableListOf<SkillMetadata>()
            val inChatMatched = mutableListOf<SkillMetadata>()

            for (skill in enabledSkills) {
                // 有触发词的：检测是否匹配
                if (skill.triggers.isNotEmpty()) {
                    val matched = skill.triggers.any { trigger ->
                        context.contains(trigger, ignoreCase = true)
                    }
                    if (matched) {
                        when (skill.injectPosition) {
                            "before_system" -> before.add(skill)
                            "in_chat" -> inChatMatched.add(skill)
                            else -> after.add(skill)
                        }
                    }
                }
                // 无触发词的：由 use_skill 工具处理，不在此注入
            }

            if (lastUserMsgId != null) {
                val anchorId = messages.lastOrNull()?.id?.toString()
                frozenMatches[turnKey] = lastUserMsgId to FrozenSkillMatch(
                    before, after, inChatMatched, anchorId,
                )
            }
            Triple(before, after, inChatMatched)
        }

        if (beforeSystem.isEmpty() && afterSystem.isEmpty() && inChat.isEmpty()) {
            return messages
        }

        // 构建注入
        val result = mutableListOf<UIMessage>()

        // Before system
        beforeSystem.forEach { skill ->
            val body = skillManager.readSkillBody(skill.name) ?: return@forEach
            result.add(UIMessage.system("[Skill: ${skill.name}]\n$body"))
        }

        // System prompt
        if (messages.isNotEmpty()) {
            result.add(messages.first())
        }

        // After system
        afterSystem.forEach { skill ->
            val body = skillManager.readSkillBody(skill.name) ?: return@forEach
            result.add(UIMessage.system("[Skill: ${skill.name}]\n$body"))
        }

        // Rest of messages
        if (messages.size > 1) {
            result.addAll(messages.drop(1))
        }

        // In-chat skills：插入点锚定本轮首步的最后一条消息（默认插在末尾前一格，
        // 每步后移一格会让前缀每步分叉）
        if (inChat.isNotEmpty()) {
            val anchorId = frozen?.anchorMsgId ?: messages.lastOrNull()?.id?.toString()
            val anchoredIdx = anchorId?.let { id -> result.indexOfFirst { it.id.toString() == id } }
            var insertIdx = (anchoredIdx?.takeIf { it >= 0 } ?: (result.size - 1)).coerceAtLeast(0)
            inChat.forEach { skill ->
                val body = skillManager.readSkillBody(skill.name) ?: return@forEach
                result.add(insertIdx, UIMessage.user("[Skill: ${skill.name}]\n$body"))
                insertIdx++
            }
        }

        return result
    }
}
