package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.extractContextForMatching
import me.rerere.rikkahub.data.model.isTriggered
import kotlin.uuid.Uuid
import kotlin.random.Random

/**
 * 提示词注入转换器
 *
 * 根据 Assistant 关联的 ModeInjection 和 Lorebook 进行提示词注入
 */
object PromptInjectionTransformer : InputMessageTransformer {

    // 粘性追踪：assistantId → (injectionId → 剩余轮数)
    private val stickyTracker = mutableMapOf<String, MutableMap<Uuid, Int>>()
    // 冷却追踪：assistantId → (injectionId → 剩余冷却轮数)
    private val cooldownTracker = mutableMapOf<String, MutableMap<Uuid, Int>>()

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val key = ctx.assistant.id.toString()
        val activeSticky = stickyTracker.getOrPut(key) { mutableMapOf() }
        val cooldowns = cooldownTracker.getOrPut(key) { mutableMapOf() }

        val result = transformMessages(
            messages = messages,
            assistant = ctx.assistant,
            modeInjections = ctx.settings.modeInjections,
            lorebooks = ctx.settings.lorebooks,
            conversationModeInjectionIds = ctx.conversationModeInjectionIds,
            conversationLorebookIds = ctx.conversationLorebookIds,
            activeStickyEntries = activeSticky,
            cooldownEntries = cooldowns,
            authorNotePosition = ctx.settings.authorNotePosition,
            authorNoteDepth = ctx.settings.authorNoteDepth,
            worldInfoBudget = ctx.settings.worldInfoBudget,
            worldInfoMinActivations = ctx.settings.worldInfoMinActivations,
            worldInfoRecursive = ctx.settings.worldInfoRecursive,
            worldInfoMaxRecursionSteps = ctx.settings.worldInfoMaxRecursionSteps,
        )

        return result
    }
}

/**
 * 核心注入逻辑（可测试的纯函数）
 */
internal fun transformMessages(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
    activeStickyEntries: MutableMap<Uuid, Int> = mutableMapOf(),
    cooldownEntries: MutableMap<Uuid, Int> = mutableMapOf(),
    authorNotePosition: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
    authorNoteDepth: Int = 4,
    worldInfoBudget: Int = 25,
    worldInfoMinActivations: Int = 0,
    worldInfoRecursive: Boolean = false,
    worldInfoMaxRecursionSteps: Int = 0,
): List<UIMessage> {
    // 收集所有需要注入的内容
    val injections = collectInjections(
        messages = messages,
        assistant = assistant,
        modeInjections = modeInjections,
        lorebooks = lorebooks,
        conversationModeInjectionIds = conversationModeInjectionIds,
        conversationLorebookIds = conversationLorebookIds,
        activeStickyEntries = activeStickyEntries,
        cooldownEntries = cooldownEntries,
        worldInfoBudget = worldInfoBudget,
        worldInfoMinActivations = worldInfoMinActivations,
        worldInfoRecursive = worldInfoRecursive,
        worldInfoMaxRecursionSteps = worldInfoMaxRecursionSteps,
    )

    if (injections.isEmpty()) {
        // 无注入时仍要推进粘性和冷却状态
        tickSticky(activeStickyEntries, cooldownEntries, emptyList())
        tickCooldowns(cooldownEntries)
        return messages
    }

    // 解析 AUTHOR_NOTE 到实际位置
    val resolvedInjections = injections.map { injection ->
        if (injection.position == InjectionPosition.AUTHOR_NOTE) {
            when (authorNotePosition) {
                InjectionPosition.AT_DEPTH -> when (injection) {
                    is PromptInjection.RegexInjection -> injection.copy(
                        position = InjectionPosition.AT_DEPTH,
                        injectDepth = authorNoteDepth,
                    )
                    is PromptInjection.ModeInjection -> injection.copy(
                        position = InjectionPosition.AT_DEPTH,
                    )
                }
                else -> when (injection) {
                    is PromptInjection.RegexInjection -> injection.copy(position = authorNotePosition)
                    is PromptInjection.ModeInjection -> injection.copy(position = authorNotePosition)
                }
            }
        } else {
            injection
        }
    }

    // 按位置和优先级分组
    val byPosition = resolvedInjections
        .sortedByDescending { it.priority }
        .groupBy { it.position }

    // 应用注入
    val result = applyInjections(messages, byPosition)

    // 推进粘性和冷却
    tickSticky(activeStickyEntries, cooldownEntries, injections.filterIsInstance<PromptInjection.RegexInjection>())
    tickCooldowns(cooldownEntries)

    return result
}

/**
 * 收集需要注入的内容
 */
internal fun collectInjections(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
    activeStickyEntries: MutableMap<Uuid, Int> = mutableMapOf(),
    cooldownEntries: MutableMap<Uuid, Int> = mutableMapOf(),
    worldInfoBudget: Int = 25,
    worldInfoMinActivations: Int = 0,
    worldInfoRecursive: Boolean = false,
    worldInfoMaxRecursionSteps: Int = 0,
): List<PromptInjection> {
    val injections = mutableListOf<PromptInjection>()
    val effectiveModeInjectionIds = if (assistant.allowConversationPromptInjection) {
        conversationModeInjectionIds
    } else {
        assistant.modeInjectionIds
    }
    val effectiveLorebookIds = if (assistant.allowConversationPromptInjection) {
        conversationLorebookIds
    } else {
        assistant.lorebookIds
    }

    // 1. 获取关联的 ModeInjection
    modeInjections
        .filter { it.enabled && effectiveModeInjectionIds.contains(it.id) }
        .forEach { injections.add(it) }

    // 2. 获取关联的 Lorebook 中被触发的 RegexInjection
    val enabledLorebooks = lorebooks.filter {
        it.enabled && effectiveLorebookIds.contains(it.id)
    }
    if (enabledLorebooks.isNotEmpty()) {
        // 提取上下文用于匹配（只取非 SYSTEM 消息）
        val nonSystemMessages = messages.filter { it.role != MessageRole.SYSTEM }
        // 对齐酒馆：扫描上下文除了对话消息，还包含角色卡描述/性格/场景/作者备注
        val charScanContext = buildString {
            assistant.tavernData?.let { tav ->
                if (tav.description.isNotBlank()) appendLine(tav.description)
                if (tav.personality.isNotBlank()) appendLine(tav.personality)
                if (tav.scenario.isNotBlank()) appendLine(tav.scenario)
                if (tav.creatorNotes.isNotBlank()) appendLine(tav.creatorNotes)
            }
        }.trim()

        // 扫描函数：对每条 Lorebook 检查触发并做同组权重选择
        fun evaluateLorebooks(
            scanDepthOverride: Int? = null,
            extraContext: String = "",
            isRecursion: Boolean = false,
        ): List<PromptInjection.RegexInjection> {
            val activated = mutableListOf<PromptInjection.RegexInjection>()
            enabledLorebooks.forEach { lorebook ->
                val newlyTriggered = mutableListOf<PromptInjection.RegexInjection>()

                for (entry in lorebook.entries) {
                    // 冷却中的条目跳过
                    if (cooldownEntries.containsKey(entry.id)) continue

                    // 延迟到递归才检查的条目：正常扫描跳过（酒馆 delay_until_recursion）
                    if (entry.delayUntilRecursion && !isRecursion) continue

                    // 禁止递归触发的条目：递归扫描跳过（酒馆 prevent_recursion）
                    if (entry.preventRecursion && isRecursion) continue

                    // 粘性条目：只要在 activeSticky 中就自动包含
                    if (activeStickyEntries.containsKey(entry.id)) {
                        newlyTriggered.add(entry)
                        continue
                    }

                    // delay：消息数不够则不激活
                    if (entry.delay > 0 && nonSystemMessages.size < entry.delay) continue

                    // 正常触发检查（min_activations 重扫时扩大扫描深度）
                    val depth = scanDepthOverride ?: entry.scanDepth
                    val chatContext = extractContextForMatching(nonSystemMessages, depth)
                    val context = buildString {
                        if (charScanContext.isNotEmpty()) {
                            append(charScanContext)
                            appendLine()
                        }
                        if (extraContext.isNotEmpty()) {
                            append(extraContext)
                            appendLine()
                        }
                        append(chatContext)
                    }
                    if (entry.isTriggered(context)) {
                        newlyTriggered.add(entry)
                    }
                }

                // 同组条目权重随机选择：同一 group 的条目只选一条
                val grouped = newlyTriggered.filter { it.group.isNotBlank() }.groupBy { it.group }
                val ungrouped = newlyTriggered.filter { it.group.isBlank() }
                activated.addAll(ungrouped)
                for ((_, entries) in grouped) {
                    val override = entries.find { it.groupOverride }
                    val selected = if (override != null) {
                        override
                    } else {
                        val totalWeight = entries.sumOf { it.groupWeight.toLong() }
                        if (totalWeight <= 0) {
                            entries.first()
                        } else {
                            var roll = Random.nextLong(totalWeight)
                            var picked = entries.first()
                            for (entry in entries) {
                                roll -= entry.groupWeight.toLong()
                                if (roll < 0) {
                                    picked = entry
                                    break
                                }
                            }
                            picked
                        }
                    }
                    activated.add(selected)
                }
            }
            return activated
        }

        var activatedEntries = evaluateLorebooks()

        // 最少激活数（酒馆 min_activations）：激活不足时用更大扫描深度重扫补足
        if (worldInfoMinActivations > 0 && activatedEntries.isNotEmpty() &&
            activatedEntries.size < worldInfoMinActivations
        ) {
            val knownIds = activatedEntries.map { it.id }.toSet()
            val extra = evaluateLorebooks(scanDepthOverride = Int.MAX_VALUE)
                .filter { it.id !in knownIds }
            activatedEntries = activatedEntries + extra
        }

        // 递归扫描（酒馆 world_info_recursive）：用已注入条目的内容再扫描关联条目
        if (worldInfoRecursive) {
            var recursionContext = activatedEntries
                .filter { !it.excludeRecursion }
                .joinToString("\n") { it.content }
            var steps = 0
            // 0 = 不限制，但加 10 层安全上限防止极端循环
            val maxSteps = if (worldInfoMaxRecursionSteps > 0) worldInfoMaxRecursionSteps else 10
            while (recursionContext.isNotBlank() && steps < maxSteps) {
                steps++
                val knownIds = activatedEntries.map { it.id }.toSet()
                val newOnes = evaluateLorebooks(extraContext = recursionContext, isRecursion = true)
                    .filter { it.id !in knownIds }
                if (newOnes.isEmpty()) break
                activatedEntries = activatedEntries + newOnes
                recursionContext = newOnes
                    .filter { !it.excludeRecursion }
                    .joinToString("\n") { it.content }
                if (recursionContext.isBlank()) break
            }
        }

        // token 预算（酒馆 world_info_budget_cap）：按优先级注入，直到估计 token 数达到上限
        if (worldInfoBudget > 0) {
            val sorted = activatedEntries.sortedByDescending { it.priority }
            val selected = mutableListOf<PromptInjection.RegexInjection>()
            var usedTokens = 0
            for (entry in sorted) {
                val cost = estimateTokens(entry.content)
                if (selected.isNotEmpty() && usedTokens + cost > worldInfoBudget) break
                selected.add(entry)
                usedTokens += cost
            }
            // 预算再小也至少注入最高优先级的一条，避免世界书整体失效
            activatedEntries = selected.ifEmpty { sorted.take(1) }
        }

        for (entry in activatedEntries) {
            injections.add(entry)
            handleStickyCooldown(entry, activeStickyEntries, cooldownEntries)
        }
    }

    return injections
}

/** 估算文本 token 数：中日韩字符按 1 token，其余字符按 4 字符 1 token（近似） */
internal fun estimateTokens(text: String): Int {
    if (text.isEmpty()) return 0
    var cjk = 0
    var other = 0
    for (ch in text) {
        if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3040..0x30FF || ch.code in 0xAC00..0xD7AF) {
            cjk++
        } else {
            other++
        }
    }
    return cjk + (other + 3) / 4
}

/** 处理粘性和冷却状态 */
private fun handleStickyCooldown(
    entry: PromptInjection.RegexInjection,
    activeStickyEntries: MutableMap<Uuid, Int>,
    cooldownEntries: MutableMap<Uuid, Int>,
) {
    if (entry.sticky > 0) {
        activeStickyEntries[entry.id] = entry.sticky
    }
    if (entry.cooldown > 0) {
        cooldownEntries[entry.id] = entry.cooldown
    }
}

/** 推进粘性计数器：每次调用减1，到0时若条目有cooldown则自动进入冷却 */
private fun tickSticky(activeStickyEntries: MutableMap<Uuid, Int>, cooldownTracker: MutableMap<Uuid, Int>, entries: List<PromptInjection.RegexInjection>) {
    val entriesById = entries.associateBy { it.id }
    val toRemove = mutableListOf<Uuid>()
    for ((id, remaining) in activeStickyEntries) {
        if (remaining <= 1) {
            toRemove.add(id)
            // sticky 到期 → 自动设 cooldown（对齐酒馆）
            val entry = entriesById[id]
            if (entry != null && entry.cooldown > 0) {
                cooldownTracker[id] = entry.cooldown
            }
        } else {
            activeStickyEntries[id] = remaining - 1
        }
    }
    toRemove.forEach { activeStickyEntries.remove(it) }
}

/** 推进冷却计数器：每次调用减1，到0移除 */
private fun tickCooldowns(cooldownEntries: MutableMap<Uuid, Int>) {
    val toRemove = mutableListOf<Uuid>()
    for ((id, remaining) in cooldownEntries) {
        if (remaining <= 1) {
            toRemove.add(id)
        } else {
            cooldownEntries[id] = remaining - 1
        }
    }
    toRemove.forEach { cooldownEntries.remove(it) }
}

/**
 * 应用注入到消息列表
 */
internal fun applyInjections(
    messages: List<UIMessage>,
    byPosition: Map<InjectionPosition, List<PromptInjection>>
): List<UIMessage> {
    val result = messages.toMutableList()

    // 找到系统消息的索引（通常是第一条）
    val systemIndex = result.indexOfFirst { it.role == MessageRole.SYSTEM }

    // 处理 BEFORE_SYSTEM_PROMPT 和 AFTER_SYSTEM_PROMPT
    if (systemIndex >= 0) {
        val beforeContent = byPosition[InjectionPosition.BEFORE_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""
        val beforeCharContent = byPosition[InjectionPosition.BEFORE_CHARACTER]
            ?.joinToString("\n") { it.content } ?: ""
        val afterContent = byPosition[InjectionPosition.AFTER_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""
        val afterCharContent = byPosition[InjectionPosition.AFTER_CHARACTER]
            ?.joinToString("\n") { it.content } ?: ""

        if (beforeContent.isNotEmpty() || beforeCharContent.isNotEmpty() ||
            afterContent.isNotEmpty() || afterCharContent.isNotEmpty()
        ) {
            val systemMessage = result[systemIndex]
            val originalText = systemMessage.parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("") { it.text }

            val newText = buildString {
                // 角色卡信息之前（酒馆 before_char）
                if (beforeCharContent.isNotEmpty()) {
                    append(beforeCharContent)
                    appendLine()
                }
                if (beforeContent.isNotEmpty()) {
                    append(beforeContent)
                    appendLine()
                }
                append(originalText)
                if (afterContent.isNotEmpty()) {
                    appendLine()
                    append(afterContent)
                }
                // 角色卡信息之后（酒馆 after_char）
                if (afterCharContent.isNotEmpty()) {
                    appendLine()
                    append(afterCharContent)
                }
            }

            result[systemIndex] = systemMessage.copy(
                parts = listOf(UIMessagePart.Text(newText))
            )
        }
    } else {
        // 没有系统消息时，创建一个新的系统消息
        val beforeContent = byPosition[InjectionPosition.BEFORE_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""
        val beforeCharContent = byPosition[InjectionPosition.BEFORE_CHARACTER]
            ?.joinToString("\n") { it.content } ?: ""
        val afterContent = byPosition[InjectionPosition.AFTER_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""
        val afterCharContent = byPosition[InjectionPosition.AFTER_CHARACTER]
            ?.joinToString("\n") { it.content } ?: ""

        val combinedContent = buildString {
            if (beforeCharContent.isNotEmpty()) {
                append(beforeCharContent)
                appendLine()
            }
            if (beforeContent.isNotEmpty()) {
                append(beforeContent)
                if (afterContent.isNotEmpty() || afterCharContent.isNotEmpty()) appendLine()
            }
            if (afterContent.isNotEmpty()) {
                append(afterContent)
            }
            if (afterCharContent.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                append(afterCharContent)
            }
        }

        if (combinedContent.isNotEmpty()) {
            result.add(0, UIMessage.system(combinedContent))
        }
    }

    // 处理 ANTAGONIZE：角色卡（系统消息）之后、第一条对话消息之前
    val antagonizeInjections = byPosition[InjectionPosition.ANTAGONIZE]
    if (!antagonizeInjections.isNullOrEmpty()) {
        var insertIndex = result.indexOfFirst { it.role != MessageRole.SYSTEM }
            .takeIf { it >= 0 } ?: result.size
        insertIndex = findSafeInsertIndex(result, insertIndex)
        createMergedInjectionMessages(antagonizeInjections).forEach { message ->
            result.add(insertIndex, message)
            insertIndex++
        }
    }

    // 处理 TOP_OF_CHAT：在第一条用户消息之前插入
    val topInjections = byPosition[InjectionPosition.TOP_OF_CHAT]
    if (!topInjections.isNullOrEmpty()) {
        // 重新计算索引（因为可能插入了系统消息）
        var insertIndex = result.indexOfFirst { it.role == MessageRole.USER }
            .takeIf { it >= 0 } ?: result.size
        insertIndex = findSafeInsertIndex(result, insertIndex)
        createMergedInjectionMessages(topInjections).forEach { message ->
            result.add(insertIndex, message)
            insertIndex++
        }
    }

    // 处理 BOTTOM_OF_CHAT：在最后一条消息之前插入
    val bottomInjections = byPosition[InjectionPosition.BOTTOM_OF_CHAT]
    if (!bottomInjections.isNullOrEmpty()) {
        var insertIndex = (result.size - 1).coerceAtLeast(0)
        insertIndex = findSafeInsertIndex(result, insertIndex)
        createMergedInjectionMessages(bottomInjections).forEach { message ->
            result.add(insertIndex, message)
            insertIndex++
        }
    }

    // 处理 AFTER_DIALOG：在最后一条 AI 回复之后插入
    val afterDialogInjections = byPosition[InjectionPosition.AFTER_DIALOG]
    if (!afterDialogInjections.isNullOrEmpty()) {
        val lastAssistantIndex = result.indexOfLast { it.role == MessageRole.ASSISTANT }
        var insertIndex = if (lastAssistantIndex >= 0) lastAssistantIndex + 1 else result.size
        insertIndex = findSafeInsertIndex(result, insertIndex)
        createMergedInjectionMessages(afterDialogInjections).forEach { message ->
            result.add(insertIndex, message)
            insertIndex++
        }
    }

    // 处理 AT_DEPTH：在指定深度位置插入（从最新消息往前数）
    // 按 injectDepth 分组，相同深度的合并，按深度从大到小处理（避免索引变化问题）
    val atDepthInjections = byPosition[InjectionPosition.AT_DEPTH]
    if (!atDepthInjections.isNullOrEmpty()) {
        val byDepth = atDepthInjections.groupBy { it.injectDepth }
        byDepth.keys.sortedDescending().forEach { depth ->
            val injections = byDepth[depth] ?: return@forEach
            // 计算插入位置：result.size - depth，但要确保在有效范围内
            // depth=1 表示在最后一条消息之前，depth=2 表示在倒数第二条之前...
            var insertIndex = (result.size - depth).coerceIn(0, result.size)
            insertIndex = findSafeInsertIndex(result, insertIndex)
            createMergedInjectionMessages(injections).forEach { message ->
                result.add(insertIndex, message)
                insertIndex++
            }
        }
    }

    return result
}

/**
 * 将同一 role 的注入合并成消息列表
 * 按 role 分组后合并内容，返回合并后的消息列表
 */
private fun createMergedInjectionMessages(injections: List<PromptInjection>): List<UIMessage> {
    return injections
        .groupBy { it.role }
        .map { (role, grouped) ->
            val mergedContent = grouped.joinToString("\n") { it.content }
            when (role) {
                MessageRole.ASSISTANT -> UIMessage.assistant(mergedContent)
                else -> UIMessage.user(mergedContent)
            }
        }
}

/**
 * 查找安全的插入位置，避免注入到 USER → ASSISTANT(含Tool) 之间
 *
 * 某些提供商（如 deepseek）要求 USER 之后紧跟带工具的 ASSISTANT，
 * 在两者之间插入消息会导致报错或破坏推理连续性。
 */
internal fun findSafeInsertIndex(messages: List<UIMessage>, targetIndex: Int): Int {
    var index = targetIndex.coerceIn(0, messages.size)

    // 向前查找，直到找到一个安全的位置
    while (index > 0) {
        val prevMessage = messages.getOrNull(index - 1)
        val currentMessage = messages.getOrNull(index)

        // 不能插入到 USER → ASSISTANT(含Tool) 之间
        val isPrevUser = prevMessage?.role == MessageRole.USER
        val isCurrentAssistantWithTools = currentMessage?.role == MessageRole.ASSISTANT
            && currentMessage.getTools().isNotEmpty()

        if (isPrevUser && isCurrentAssistantWithTools) {
            index--
        } else {
            break
        }
    }

    return index
}
