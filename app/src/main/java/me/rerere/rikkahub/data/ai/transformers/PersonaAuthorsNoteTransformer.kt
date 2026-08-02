package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.InjectionPosition

/**
 * Persona 注入 — 将激活的用户人设注入到系统提示词或对话头部
 *
 * 增强功能：
 * - 短标题（展示用）
 * - 角色锁定（仅当当前角色在锁定列表中时才注入）
 */
object PersonaTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val persona = ctx.settings.personas.find { it.id == ctx.settings.activePersonaId }
            ?: return messages
        if (!persona.enabled || persona.description.isBlank()) return messages

        // 角色锁定检查：如果锁定了角色但当前不在列表中则不注入
        if (persona.lockedCharacterIds.isNotEmpty()) {
            val currentAssistantId = ctx.assistant.id
            if (currentAssistantId !in persona.lockedCharacterIds) return messages
        }

        val displayName = persona.title.ifBlank { persona.name }
        val personaText = "[User Persona: $displayName]\n${persona.description}"
        val personaMsg = when (persona.role) {
            MessageRole.SYSTEM -> UIMessage.system(personaText)
            MessageRole.ASSISTANT -> UIMessage.assistant(personaText)
            else -> UIMessage.user(personaText)
        }

        return when (persona.position) {
            // IN_PROMPT 已由系统提示词组装嵌入；NONE 不注入
            me.rerere.rikkahub.data.model.PersonaInjectionPosition.IN_PROMPT,
            me.rerere.rikkahub.data.model.PersonaInjectionPosition.NONE -> messages

            me.rerere.rikkahub.data.model.PersonaInjectionPosition.TOP_OF_CHAT ->
                if (messages.isNotEmpty()) {
                    listOf(messages.first()) + personaMsg + messages.drop(1)
                } else listOf(personaMsg) + messages

            me.rerere.rikkahub.data.model.PersonaInjectionPosition.BOTTOM_OF_CHAT ->
                messages + personaMsg

            me.rerere.rikkahub.data.model.PersonaInjectionPosition.AT_DEPTH -> {
                val depth = persona.depth.coerceAtLeast(1)
                val insertIdx = (messages.size - depth).coerceAtLeast(0)
                messages.take(insertIdx) + personaMsg + messages.drop(insertIdx)
            }
        }
    }
}

/**
 * Author's Note 注入 — 在指定深度插入作者的引导
 *
 * 增强功能：
 * - 注入角色（SYSTEM/USER/ASSISTANT）
 * - 间隔注入（每N条消息注入一次）
 */
object AuthorsNoteTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val note = ctx.settings.authorNote
        if (!ctx.settings.authorNoteEnabled || note.isBlank()) return messages

        // 官方计数语义（对齐 SillyTavern authors-note.js）：
        // - 按当前对话的用户消息条数计数，不跨对话、不按生成次数
        // - interval == 1：每次都注入（有用户消息即可）
        // - interval <= 0：禁用
        // - 其他：用户消息条数恰好是 interval 的整数倍时注入
        val interval = ctx.settings.authorNoteInterval
        val userCount = messages.count { it.role == MessageRole.USER }
        val shouldInject = when {
            interval == 1 -> userCount > 0
            interval <= 0 -> false
            else -> userCount >= interval && userCount % interval == 0
        }
        if (!shouldInject) return messages

        val role = ctx.settings.authorNoteRole
        val noteMsg = when (role) {
            MessageRole.SYSTEM -> UIMessage.system("[Author's Note]\n$note")
            MessageRole.ASSISTANT -> UIMessage.assistant("[Author's Note]\n$note")
            else -> UIMessage.user("[Author's Note]\n$note")
        }

        val depth = ctx.settings.authorNoteDepth.coerceAtLeast(1)
        val pos = ctx.settings.authorNotePosition

        return when (pos) {
            InjectionPosition.BEFORE_SYSTEM_PROMPT ->
                listOf(noteMsg) + messages
            InjectionPosition.AFTER_SYSTEM_PROMPT ->
                if (messages.isNotEmpty()) {
                    listOf(messages.first()) + noteMsg + messages.drop(1)
                } else listOf(noteMsg) + messages
            InjectionPosition.TOP_OF_CHAT -> {
                val userIdx = messages.indexOfFirst { it.role == MessageRole.USER }
                val insertIdx = if (userIdx >= 0) userIdx else messages.size
                messages.take(insertIdx) + noteMsg + messages.drop(insertIdx)
            }
            InjectionPosition.BOTTOM_OF_CHAT -> {
                val insertIdx = (messages.size - 1).coerceAtLeast(0)
                messages.take(insertIdx) + noteMsg + messages.drop(insertIdx)
            }
            InjectionPosition.BEFORE_CHARACTER ->
                listOf(noteMsg) + messages
            InjectionPosition.AFTER_CHARACTER ->
                if (messages.isNotEmpty()) {
                    listOf(messages.first()) + noteMsg + messages.drop(1)
                } else listOf(noteMsg) + messages
            InjectionPosition.ANTAGONIZE -> {
                val userIdx = messages.indexOfFirst { it.role == MessageRole.USER }
                val insertIdx = if (userIdx >= 0) userIdx else messages.size
                messages.take(insertIdx) + noteMsg + messages.drop(insertIdx)
            }
            InjectionPosition.AFTER_DIALOG -> {
                val insertIdx = (messages.size - 1).coerceAtLeast(0)
                messages.take(insertIdx) + noteMsg + messages.drop(insertIdx)
            }
            InjectionPosition.AUTHOR_NOTE -> {
                if (messages.isNotEmpty()) {
                    listOf(messages.first()) + noteMsg + messages.drop(1)
                } else listOf(noteMsg) + messages
            }
            InjectionPosition.AT_DEPTH -> {
                val insertIdx = (messages.size - depth).coerceAtLeast(0)
                messages.take(insertIdx) + noteMsg + messages.drop(insertIdx)
            }
            // AN 位置不会配置为示例消息锚点，按官方 story string 后的近似位置处理
            InjectionPosition.EM_TOP, InjectionPosition.EM_BOTTOM ->
                if (messages.isNotEmpty()) {
                    listOf(messages.first()) + noteMsg + messages.drop(1)
                } else listOf(noteMsg) + messages
        }
    }
}
