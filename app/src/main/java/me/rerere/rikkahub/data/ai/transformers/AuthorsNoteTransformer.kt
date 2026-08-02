package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.InjectionPosition

object AuthorsNoteTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val settings = ctx.settings
        if (!settings.authorNoteEnabled || settings.authorNote.isBlank()) return messages

        val interval = settings.authorNoteInterval
        val userCount = messages.count { it.role == MessageRole.USER }
        val shouldInject = when {
            interval == 1 -> true
            interval <= 0 -> false
            else -> userCount >= interval && userCount % interval == 0
        }
        if (!shouldInject) return messages

        val persona = settings.personas.find { it.id == settings.activePersonaId }
        val personaActive = persona != null && persona.enabled && persona.description.isNotBlank() &&
            (persona.lockedCharacterIds.isEmpty() || ctx.assistant.id in persona.lockedCharacterIds)
        val personaDesc = persona?.takeIf { personaActive }?.description
        val personaTop = personaActive && persona?.position == me.rerere.rikkahub.data.model.PersonaInjectionPosition.TOP_OF_CHAT
        val personaBottom = personaActive && persona?.position == me.rerere.rikkahub.data.model.PersonaInjectionPosition.BOTTOM_OF_CHAT
        val noteText = when {
            personaTop -> "${personaDesc ?: ""}\n${settings.authorNote}"

            personaBottom -> "${settings.authorNote}\n${personaDesc ?: ""}"

            else -> settings.authorNote
        }

        val noteMsg = when (settings.authorNoteRole) {
            MessageRole.ASSISTANT -> UIMessage.assistant("[Author's Note]\n$noteText")
            MessageRole.USER -> UIMessage.user("[Author's Note]\n$noteText")
            else -> UIMessage.system("[Author's Note]\n$noteText")
        }

        val depth = settings.authorNoteDepth.coerceAtLeast(1)
        return when (settings.authorNotePosition) {
            InjectionPosition.BEFORE_SYSTEM_PROMPT -> listOf(noteMsg) + messages

            InjectionPosition.AFTER_SYSTEM_PROMPT ->
                if (messages.isNotEmpty()) {
                    listOf(messages.first()) + noteMsg + messages.drop(1)
                } else {
                    listOf(noteMsg) + messages
                }

            InjectionPosition.TOP_OF_CHAT -> {
                val idx = messages.indexOfFirst { it.role == MessageRole.USER }
                val insertIdx = if (idx >= 0) idx else messages.size
                messages.take(insertIdx) + noteMsg + messages.drop(insertIdx)
            }

            InjectionPosition.BOTTOM_OF_CHAT -> {
                val insertIdx = (messages.size - 1).coerceAtLeast(0)
                messages.take(insertIdx) + noteMsg + messages.drop(insertIdx)
            }

            InjectionPosition.BEFORE_CHARACTER -> listOf(noteMsg) + messages

            InjectionPosition.AFTER_CHARACTER ->
                if (messages.isNotEmpty()) {
                    listOf(messages.first()) + noteMsg + messages.drop(1)
                } else {
                    listOf(noteMsg) + messages
                }

            InjectionPosition.ANTAGONIZE -> {
                val idx = messages.indexOfFirst { it.role == MessageRole.USER }
                val insertIdx = if (idx >= 0) idx else messages.size
                messages.take(insertIdx) + noteMsg + messages.drop(insertIdx)
            }

            InjectionPosition.AFTER_DIALOG -> {
                val insertIdx = (messages.size - 1).coerceAtLeast(0)
                messages.take(insertIdx) + noteMsg + messages.drop(insertIdx)
            }

            InjectionPosition.AUTHOR_NOTE ->
                if (messages.isNotEmpty()) {
                    listOf(messages.first()) + noteMsg + messages.drop(1)
                } else {
                    listOf(noteMsg) + messages
                }

            InjectionPosition.AT_DEPTH -> {
                val insertIdx = (messages.size - depth).coerceAtLeast(0)
                messages.take(insertIdx) + noteMsg + messages.drop(insertIdx)
            }

            InjectionPosition.EM_TOP, InjectionPosition.EM_BOTTOM ->
                if (messages.isNotEmpty()) {
                    listOf(messages.first()) + noteMsg + messages.drop(1)
                } else {
                    listOf(noteMsg) + messages
                }
        }
    }
}
