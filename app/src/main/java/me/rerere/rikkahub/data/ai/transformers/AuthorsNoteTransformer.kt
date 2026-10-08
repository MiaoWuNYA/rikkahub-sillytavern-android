package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.authorNoteEntriesOrLegacy
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AuthorNoteEntry
import me.rerere.rikkahub.data.model.AuthorNotePosition
import me.rerere.rikkahub.data.model.LEGACY_AUTHOR_NOTE_ENTRY_ID
import me.rerere.rikkahub.data.model.PersonaInjectionPosition
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

object AuthorsNoteTransformer : InputMessageTransformer {
    /**
     * 每条 In-chat 备注按自己的 entry/depth 冻结锚点。
     *
     * key = assistantId:conversationId:entryId:depth，value = (lastUserMsgId, "before:<消息id>")。
     * 这样 agentic 工具循环继续复用首步前缀，同时不同 depth 不会挤到同一个旧锚点。
     */
    private val frozenInChatAnchors =
        ConcurrentHashMap<String, Pair<String, String>>()

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = transformAuthorNotes(
        settings = ctx.settings,
        assistant = ctx.assistant,
        conversationId = ctx.conversationId,
        chatUserMessageCount = ctx.chatUserMessageCount,
        chatMessageCount = ctx.chatMessageCount,
        messages = messages,
        frozenAnchors = frozenInChatAnchors,
    )
}

private data class AuthorNoteCandidate(
    val anchorId: String,
    val content: String,
    val position: AuthorNotePosition,
    val depth: Int,
    val role: MessageRole,
)

private data class PlannedAuthorNote(
    val index: Int,
    val order: Int,
    val message: UIMessage,
)

internal fun transformAuthorNotes(
    settings: Settings,
    assistant: Assistant,
    conversationId: Uuid?,
    chatUserMessageCount: Int?,
    chatMessageCount: Int?,
    messages: List<UIMessage>,
    frozenAnchors: MutableMap<String, Pair<String, String>> = mutableMapOf(),
): List<UIMessage> {
    if (!settings.authorNoteEnabled) return messages

    val userCount = chatUserMessageCount ?: messages.count {
        it.role == MessageRole.USER && !it.isInjectedBlock()
    }
    val entries = settings.authorNoteEntriesOrLegacy()
    val persona = settings.personas.find { it.id == settings.activePersonaId }
    val personaActive = persona != null && persona.enabled && persona.description.isNotBlank() &&
        (persona.lockedCharacterIds.isEmpty() || assistant.id in persona.lockedCharacterIds)
    val personaAtTop = personaActive && persona?.position == PersonaInjectionPosition.TOP_OF_CHAT
    val personaAtBottom = personaActive && persona?.position == PersonaInjectionPosition.BOTTOM_OF_CHAT

    // TOP/BOTTOM 在旧实现里与单条导演备注共用参数和 interval。多条模式下仍只生成一个
    // Persona 候选：优先沿用物化后的 legacy entry，否则沿用保留的旧全局参数。
    val personaCarrier = entries.firstOrNull { it.id == LEGACY_AUTHOR_NOTE_ENTRY_ID }
        ?: AuthorNoteEntry(
            id = LEGACY_AUTHOR_NOTE_ENTRY_ID,
            position = settings.authorNotePosition,
            depth = settings.authorNoteDepth,
            role = settings.authorNoteRole,
            interval = settings.authorNoteInterval,
        )
    val personaShouldInject = (personaAtTop || personaAtBottom) &&
        shouldInjectAuthorNote(personaCarrier.interval, userCount)
    val activeEntries = entries.filter {
        it.enabled && it.content.isNotBlank() && shouldInjectAuthorNote(it.interval, userCount)
    }
    // 旧单条数据继续保持“Persona 与 Author's Note 合并成同一条消息”的结构。
    // 纯新模型没有 legacy 载体时，才退化为一条独立候选，且全程只创建一次。
    val mergePersonaIntoLegacy = personaShouldInject &&
        activeEntries.any { it.id == LEGACY_AUTHOR_NOTE_ENTRY_ID }
    val noteCandidates = activeEntries.map { entry ->
        val candidate = entry.toCandidate()
        if (entry.id != LEGACY_AUTHOR_NOTE_ENTRY_ID || !mergePersonaIntoLegacy) {
            candidate
        } else {
            candidate.copy(
                content = if (personaAtTop) {
                    "${persona?.description.orEmpty()}\n${candidate.content}"
                } else {
                    "${candidate.content}\n${persona?.description.orEmpty()}"
                }
            )
        }
    }
    val personaCandidate = persona?.takeIf {
        personaShouldInject && !mergePersonaIntoLegacy
    }?.let {
        AuthorNoteCandidate(
            anchorId = "persona:${it.id}",
            content = it.description,
            position = personaCarrier.position,
            depth = personaCarrier.depth,
            role = personaCarrier.role,
        )
    }

    val candidates = buildList {
        if (personaAtTop && personaCandidate != null) add(personaCandidate)
        addAll(noteCandidates)
        if (personaAtBottom && personaCandidate != null) add(personaCandidate)
    }
    if (candidates.isEmpty()) return messages

    val chatSize = (chatMessageCount ?: messages.size).coerceIn(0, messages.size)
    val turnKey = "${assistant.id}:${conversationId ?: "no-conversation"}"
    val lastUserMsgId = messages.lastOrNull {
        it.role == MessageRole.USER && !it.isInjectedBlock()
    }?.id?.toString()

    val planned = candidates.mapIndexed { order, candidate ->
        val targetIndex = when (candidate.position) {
            AuthorNotePosition.BEFORE_PROMPT -> 0
            AuthorNotePosition.IN_PROMPT ->
                (messages.indexOfFirst { it.role == MessageRole.SYSTEM } + 1).coerceAtLeast(0)
            AuthorNotePosition.IN_CHAT -> resolveInChatIndex(
                messages = messages,
                chatSize = chatSize,
                depth = candidate.depth.coerceAtLeast(0),
                cacheKey = "$turnKey:${candidate.anchorId}:${candidate.depth}",
                lastUserMsgId = lastUserMsgId,
                frozenAnchors = frozenAnchors,
            )
        }
        PlannedAuthorNote(
            index = if (candidate.position == AuthorNotePosition.IN_CHAT) {
                findSafeInsertIndex(messages, targetIndex)
            } else {
                targetIndex
            },
            order = order,
            message = authorNoteMessage(candidate),
        )
    }

    // 所有位置都先基于原始 messages 计算，再一次性插入；前一条备注不会改变后一条的 depth。
    val byIndex = planned
        .sortedWith(compareBy<PlannedAuthorNote> { it.index }.thenBy { it.order })
        .groupBy { it.index }
    return buildList(messages.size + planned.size) {
        for (index in 0..messages.size) {
            byIndex[index].orEmpty().forEach { add(it.message) }
            if (index < messages.size) add(messages[index])
        }
    }
}

internal fun shouldInjectAuthorNote(interval: Int, userCount: Int): Boolean = when {
    interval == 1 -> true
    interval <= 0 -> false
    else -> userCount >= interval && userCount % interval == 0
}

private fun AuthorNoteEntry.toCandidate() = AuthorNoteCandidate(
    anchorId = id,
    content = content,
    position = position,
    depth = depth,
    role = role,
)

private fun authorNoteMessage(candidate: AuthorNoteCandidate): UIMessage {
    val body = "[Author's Note]\n${candidate.content}"
    return when (candidate.role) {
        MessageRole.ASSISTANT -> UIMessage.assistant(body)
        MessageRole.USER -> UIMessage.user(body)
        else -> UIMessage.system(body)
    }
}

private fun resolveInChatIndex(
    messages: List<UIMessage>,
    chatSize: Int,
    depth: Int,
    cacheKey: String,
    lastUserMsgId: String?,
    frozenAnchors: MutableMap<String, Pair<String, String>>,
): Int {
    val lowerBound = messages.size - chatSize
    val fallback = {
        (messages.size - minOf(depth, chatSize)).coerceIn(lowerBound, messages.size)
    }
    val cached = frozenAnchors[cacheKey]
        ?.takeIf { it.first == lastUserMsgId }
        ?.second
    if (cached != null) {
        val mode = cached.substringBefore(':')
        val anchorId = cached.substringAfter(':')
        val anchorIdx = messages.indexOfFirst { it.id.toString() == anchorId }
        return when {
            anchorIdx < 0 -> fallback()
            mode == "after" -> anchorIdx + 1
            else -> anchorIdx
        }
    }

    val computed = fallback()
    if (lastUserMsgId != null && messages.isNotEmpty()) {
        val mode = if (computed >= messages.size) "after" else "before"
        val anchorIdx = if (mode == "after") messages.lastIndex else computed
        messages.getOrNull(anchorIdx)?.id?.toString()?.let { anchor ->
            if (frozenAnchors.size >= 256) frozenAnchors.clear()
            frozenAnchors[cacheKey] = lastUserMsgId to "$mode:$anchor"
        }
    }
    return computed
}

/** 注入块内部标记识别（与 PlaceholderTransformer 的剥离逻辑保持一致） */
internal fun UIMessage.isInjectedBlock(): Boolean {
    val text = parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
        .joinToString("") { it.text }
    return text.startsWith("[Author's Note]") || text.startsWith("[User Persona]")
}
