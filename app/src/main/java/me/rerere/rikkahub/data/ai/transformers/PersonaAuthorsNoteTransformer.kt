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
        val personaMsg = UIMessage.user("[User Persona: $displayName]\n${persona.description}")

        return when (persona.position) {
            me.rerere.rikkahub.data.model.PersonaInjectionPosition.BEFORE_SYSTEM ->
                listOf(personaMsg) + messages
            me.rerere.rikkahub.data.model.PersonaInjectionPosition.AFTER_SYSTEM ->
                if (messages.isNotEmpty()) {
                    listOf(messages.first()) + personaMsg + messages.drop(1)
                } else listOf(personaMsg) + messages
            me.rerere.rikkahub.data.model.PersonaInjectionPosition.TOP_OF_CHAT ->
                messages + personaMsg
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
    private var messageCounter = 0

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val note = ctx.settings.authorNote
        if (!ctx.settings.authorNoteEnabled || note.isBlank()) return messages

        // 间隔检查
        messageCounter++
        if (ctx.settings.authorNoteInterval > 0) {
            if (messageCounter % ctx.settings.authorNoteInterval != 0) return messages
        }

        // 频率检查
        if (ctx.settings.authorNoteFrequency < 1.0f) {
            if (kotlin.random.Random.nextFloat() > ctx.settings.authorNoteFrequency) {
                messageCounter-- // 不计入本次
                return messages
            }
        }

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
