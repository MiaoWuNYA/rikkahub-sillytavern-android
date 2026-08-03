package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AuthorNotePosition
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.extractContextForMatching
import me.rerere.rikkahub.data.model.isTriggered
import me.rerere.rikkahub.data.model.matchedKeyScore
import kotlin.uuid.Uuid
import kotlin.random.Random

/**
 * 提示词注入转换器
 *
 * 根据 Assistant 关联的 ModeInjection 和 Lorebook 进行提示词注入
 */
object PromptInjectionTransformer : InputMessageTransformer {

    // 粘性追踪：assistantId:conversationId → (injectionId → 剩余轮数)
    private val stickyTracker = mutableMapOf<String, MutableMap<Uuid, Int>>()
    // 冷却追踪：assistantId:conversationId → (injectionId → 剩余冷却轮数)
    private val cooldownTracker = mutableMapOf<String, MutableMap<Uuid, Int>>()

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        // 官方把 sticky/cooldown 存在 chat_metadata（按对话），这里也必须按对话隔离，
        // 避免 A 对话的粘性/冷却泄漏到同一助手的 B 对话
        val key = "${ctx.assistant.id}:${ctx.conversationId ?: "no-conversation"}"
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
            generationType = ctx.generationType,
            personaDescription = ctx.settings.personas
                .firstOrNull { p -> p.id == ctx.settings.activePersonaId && p.enabled }
                ?.description ?: "",
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
    authorNotePosition: AuthorNotePosition = AuthorNotePosition.IN_CHAT,
    authorNoteDepth: Int = 4,
    worldInfoBudget: Int = 25,
    worldInfoMinActivations: Int = 0,
    worldInfoRecursive: Boolean = false,
    worldInfoMaxRecursionSteps: Int = 0,
    generationType: me.rerere.rikkahub.data.model.GenerationType = me.rerere.rikkahub.data.model.GenerationType.NORMAL,
    personaDescription: String = "",
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
        generationType = generationType,
        personaDescription = personaDescription,
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
                AuthorNotePosition.IN_CHAT -> when (injection) {
                    is PromptInjection.RegexInjection -> injection.copy(
                        position = InjectionPosition.AT_DEPTH,
                        injectDepth = authorNoteDepth,
                    )
                    is PromptInjection.ModeInjection -> injection.copy(
                        position = InjectionPosition.AT_DEPTH,
                    )
                }
                // After Main Prompt / Story String：角色卡之后、对话之前
                AuthorNotePosition.IN_PROMPT -> when (injection) {
                    is PromptInjection.RegexInjection -> injection.copy(position = InjectionPosition.ANTAGONIZE)
                    is PromptInjection.ModeInjection -> injection.copy(position = InjectionPosition.ANTAGONIZE)
                }
                // Before Main Prompt / Story String：提示词最前面
                AuthorNotePosition.BEFORE_PROMPT -> when (injection) {
                    is PromptInjection.RegexInjection -> injection.copy(position = InjectionPosition.BEFORE_SYSTEM_PROMPT)
                    is PromptInjection.ModeInjection -> injection.copy(position = InjectionPosition.BEFORE_SYSTEM_PROMPT)
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
    generationType: me.rerere.rikkahub.data.model.GenerationType = me.rerere.rikkahub.data.model.GenerationType.NORMAL,
    personaDescription: String = "",
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
        // 官方 failedProbabilityChecks：本次扫描中概率未通过的条目，后续递归/补扫不再重新掷
        val failedProbabilityIds = mutableSetOf<Uuid>()
        // 官方 match_* 开关：按条目决定哪些角色卡字段纳入扫描（默认只扫聊天）
        val tav = assistant.tavernData
        fun buildCharScanContext(entry: PromptInjection.RegexInjection): String = buildString {
            if (tav == null) return@buildString
            if (entry.matchCharacterDescription && tav.description.isNotBlank()) appendLine(tav.description)
            if (entry.matchCharacterPersonality && tav.personality.isNotBlank()) appendLine(tav.personality)
            if (entry.matchScenario && tav.scenario.isNotBlank()) appendLine(tav.scenario)
            if (entry.matchCreatorNotes && tav.creatorNotes.isNotBlank()) appendLine(tav.creatorNotes)
            if (entry.matchCharacterDepthPrompt && tav.depthPrompt.isNotBlank()) appendLine(tav.depthPrompt)
            if (entry.matchPersonaDescription && personaDescription.isNotBlank()) appendLine(personaDescription)
        }.trim()

        // 扫描函数：跨全部 Lorebook 检查触发，再按官方 filterByInclusionGroups 做同组选择
        fun evaluateLorebooks(
            scanDepthOverride: Int? = null,
            extraContext: String = "",
            isRecursion: Boolean = false,
            currentRecursionDelayLevel: Int = 0,
            alreadyActivated: List<PromptInjection.RegexInjection> = emptyList(),
        ): List<PromptInjection.RegexInjection> {
            val newlyTriggered = mutableListOf<PromptInjection.RegexInjection>()
            // 触发时的关键词匹配分（酒馆 use_group_scoring 用）
            val triggeredScores = mutableMapOf<Uuid, Int>()

            enabledLorebooks.forEach { lorebook ->
                for (entry in lorebook.entries) {
                    // 冷却中的条目跳过
                    if (cooldownEntries.containsKey(entry.id)) continue

                    // 生成类型过滤（酒馆 triggers）：已激活的粘性条目不受影响
                    if (!activeStickyEntries.containsKey(entry.id) &&
                        entry.triggers.isNotEmpty() &&
                        generationType.value !in entry.triggers
                    ) {
                        continue
                    }

                    // 官方：普通扫描跳过所有 delay_until_recursion 条目（粘性豁免）
                    if (entry.delayUntilRecursion > 0 &&
                        !isRecursion &&
                        !activeStickyEntries.containsKey(entry.id)
                    ) {
                        continue
                    }

                    // 官方：递归扫描只放行层级 <= 当前开放层级的条目（粘性豁免）
                    if (isRecursion &&
                        entry.delayUntilRecursion > currentRecursionDelayLevel &&
                        !activeStickyEntries.containsKey(entry.id)
                    ) {
                        continue
                    }

                    // 官方：exclude_recursion 条目在递归扫描中被跳过（内容是否进递归缓冲另算）
                    if (entry.excludeRecursion && isRecursion) continue

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
                    // 官方 match_*：只把该条目开启的角色卡字段纳入扫描
                    val entryCharScan = buildCharScanContext(entry)
                    val context = buildString {
                        if (entryCharScan.isNotEmpty()) {
                            append(entryCharScan)
                            appendLine()
                        }
                        if (extraContext.isNotEmpty()) {
                            append(extraContext)
                            appendLine()
                        }
                        append(chatContext)
                    }
                    // 官方顺序：先关键词激活，再 verifyProbability（粘性免掷；失败后本次扫描不再参与）
                    if (failedProbabilityIds.contains(entry.id)) continue
                    if (entry.isTriggered(context, rollProbability = false)) {
                        val effectiveProb = if (entry.useProbability) entry.probability else 100
                        if (effectiveProb < 100 && Random.nextInt(100) >= effectiveProb) {
                            failedProbabilityIds.add(entry.id)
                            continue
                        }
                        newlyTriggered.add(entry)
                        triggeredScores[entry.id] = entry.matchedKeyScore(context)
                    }
                }
            }

            return selectGroupWinners(
                newlyTriggered = newlyTriggered,
                triggeredScores = triggeredScores,
                activeStickyEntries = activeStickyEntries,
                alreadyActivated = alreadyActivated,
            )
        }

        var activatedEntries = evaluateLorebooks()

        // 最少激活数（酒馆 min_activations）：激活不足时用更大扫描深度重扫补足
        if (worldInfoMinActivations > 0 && activatedEntries.size < worldInfoMinActivations) {
            val extra = evaluateLorebooks(
                scanDepthOverride = Int.MAX_VALUE,
                alreadyActivated = activatedEntries,
            )
                .filter { it.id !in activatedEntries.map { e -> e.id } }
            activatedEntries = activatedEntries + extra
        }

        // 递归扫描（酒馆 world_info_recursive）：用已注入条目的内容再扫描关联条目
        if (worldInfoRecursive) {
            // 官方：可用延迟层级 = 全部条目 delay_until_recursion 去重升序，逐级开放
            val availableLevels = enabledLorebooks
                .flatMap { it.entries.map { e -> e.delayUntilRecursion } }
                .filter { it > 0 }
                .distinct()
                .sorted()
                .toMutableList()
            var currentLevel = availableLevels.firstOrNull() ?: 0
            if (availableLevels.isNotEmpty()) availableLevels.removeAt(0)

            // 官方 successfulNewEntriesForRecursion：prevent_recursion 条目的内容不进递归缓冲
            var recursionContext = activatedEntries
                .filter { !it.preventRecursion }
                .joinToString("\n") { it.content }
            var steps = 0
            // 官方 max_recursion_steps 统计的是总扫描循环数（含首轮），递归轮数 = 值 - 1；
            // 0 = 不限制，但加 10 层安全上限防止极端循环
            val maxSteps = when {
                worldInfoMaxRecursionSteps > 1 -> worldInfoMaxRecursionSteps - 1
                worldInfoMaxRecursionSteps == 1 -> 0
                else -> 10
            }
            // 官方：即使首轮没有新条目，只要还有未开放的层级，也会继续递归扫描
            // （聊天内容本身可能命中更高级别的延迟条目）
            while ((recursionContext.isNotBlank() || availableLevels.isNotEmpty()) && steps < maxSteps) {
                steps++
                val knownIds = activatedEntries.map { it.id }.toSet()
                val newOnes = evaluateLorebooks(
                    extraContext = recursionContext,
                    isRecursion = true,
                    alreadyActivated = activatedEntries,
                    currentRecursionDelayLevel = currentLevel,
                )
                    .filter { it.id !in knownIds }
                if (newOnes.isEmpty()) {
                    // 官方：本轮无新条目 → 开放下一延迟层级继续扫描；层级耗尽才停
                    if (availableLevels.isNotEmpty()) {
                        currentLevel = availableLevels.removeAt(0)
                        continue
                    }
                    break
                }
                activatedEntries = activatedEntries + newOnes
                val newRecursionText = newOnes
                    .filter { !it.preventRecursion }
                    .joinToString("\n") { it.content }
                // 官方递归缓冲是逐轮累积的：下一轮可命中跨多轮内容组合的关键词
                recursionContext = listOf(recursionContext, newRecursionText)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                if (recursionContext.isBlank() && availableLevels.isEmpty()) break
            }
        }

        // token 预算（酒馆 world_info_budget_cap）：按优先级注入，直到估计 token 数达到上限
        if (worldInfoBudget > 0) {
            val sorted = activatedEntries.sortedByDescending { it.priority }
            val selected = mutableListOf<PromptInjection.RegexInjection>()
            var usedTokens = 0
            for (entry in sorted) {
                val cost = estimateTokens(entry.content)
                // 官方 ignore_budget：豁免预算，总是注入且不计入预算
                if (entry.ignoreBudget) {
                    selected.add(entry)
                    continue
                }
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

/**
 * 官方 filterByInclusionGroups 的本地实现：
 * 1. 同组内有粘性条目 → 只保留粘性条目（官方 filterGroupsByTimedEffects）
 * 2. 本次运行已有同组条目被激活 → 整组跳过（官方 allActivatedEntries 检查）
 * 3. group_override 条目 → 取优先级（order）最高的
 * 4. use_group_scoring 生效 → 移除分数低于组内最高分的条目（官方仅移除非最高分，未开启评分的条目保留）
 * 5. 剩余条目按 group_weight 加权随机选 1 条
 *
 * 分组跨全部 Lorebook（官方 global/character/chat/persona 世界书共同参与分组）。
 */
private fun selectGroupWinners(
    newlyTriggered: List<PromptInjection.RegexInjection>,
    triggeredScores: Map<Uuid, Int>,
    activeStickyEntries: Map<Uuid, Int>,
    alreadyActivated: List<PromptInjection.RegexInjection>,
): List<PromptInjection.RegexInjection> {
    if (newlyTriggered.isEmpty()) return emptyList()

    val grouped = newlyTriggered
        .filter { it.group.isNotBlank() || it.inclusionGroup.isNotBlank() }
        .flatMap { entry ->
            val labels = buildList {
                if (entry.group.isNotBlank()) add(entry.group)
                entry.inclusionGroup.split(",").map { it.trim() }.filter { it.isNotEmpty() }.let { addAll(it) }
            }.distinct()
            labels.map { label -> label to entry }
        }
        .groupBy({ it.first }, { it.second })
    val ungrouped = newlyTriggered.filter { it.group.isBlank() && it.inclusionGroup.isBlank() }
    val activated = mutableListOf<PromptInjection.RegexInjection>()
    activated.addAll(ungrouped)

    for ((_, entries) in grouped) {
        // 官方 filterGroupsByTimedEffects：组内粘性条目胜出，非粘性全部移除
        val stickyEntries = entries.filter { activeStickyEntries.containsKey(it.id) }
        if (stickyEntries.isNotEmpty()) {
            activated.addAll(stickyEntries)
            continue
        }

        // 官方：该组标签在本次扫描中已激活过任何条目 → 其余条目全部移除
        // （官方按 group 标签比对 allActivatedEntries，即使上轮的胜者本轮不再命中也要拦下）
        val alreadyActivatedLabels = alreadyActivated.flatMap { entry ->
            buildList {
                if (entry.group.isNotBlank()) add(entry.group)
                entry.inclusionGroup.split(",").map { it.trim() }.filter { it.isNotEmpty() }.let { addAll(it) }
            }.distinct()
        }.toSet()
        if (entries.any { entry ->
                buildList {
                    if (entry.group.isNotBlank()) add(entry.group)
                    entry.inclusionGroup.split(",").map { it.trim() }.filter { it.isNotEmpty() }.let { addAll(it) }
                }.any { it in alreadyActivatedLabels }
            }
        ) continue

        // 官方 filterGroupsByScoring：先移除“参与评分且分数低于组内最高”的条目
        // （未开启评分的条目保留，随后仍参与 override/加权随机）
        val scored = entries.filter { it.useGroupScoring }
        val survivors = if (scored.isNotEmpty()) {
            val maxScore = scored.maxOf { triggeredScores[it.id] ?: 0 }
            entries.filter { !it.useGroupScoring || (triggeredScores[it.id] ?: 0) == maxScore }
        } else {
            entries
        }

        // 官方 groupOverride：在评分幸存者中取 order（优先级）最高的覆盖条目
        val overrides = survivors.filter { it.groupOverride || it.groupPriority }
        val finalCandidates = if (overrides.isNotEmpty()) {
            listOf(overrides.maxByOrNull { it.priority } ?: overrides.first())
        } else {
            survivors
        }

        // 官方：加权随机选 1 条
        val totalWeight = finalCandidates.sumOf { it.groupWeight.toLong() }
        val selected = if (totalWeight <= 0) {
            finalCandidates.firstOrNull()
        } else {
            var roll = Random.nextLong(totalWeight)
            var picked: PromptInjection.RegexInjection? = null
            for (entry in finalCandidates) {
                roll -= entry.groupWeight.toLong()
                if (roll < 0) {
                    picked = entry
                    break
                }
            }
            picked ?: finalCandidates.first()
        }
        selected?.let { activated.add(it) }
    }
    return activated
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
    // 官方 setTimedEffectOfType：效果已存在时不重置计数（重复激活不延长粘性）
    if (entry.sticky > 0 && !activeStickyEntries.containsKey(entry.id)) {
        activeStickyEntries[entry.id] = entry.sticky
    }
    if (entry.cooldown > 0 && !cooldownEntries.containsKey(entry.id)) {
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

    // 示例消息索引（角色卡 mes_example 解析出的消息，带 ExampleMessage 标记）
    val exampleIndices = result.indices.filter { idx ->
        result[idx].annotations.any { it is UIMessageAnnotation.ExampleMessage }
    }
    // 无示例消息时退化为系统消息之后（官方 story string 之后）
    val fallbackAfterSystem = result.indexOfFirst { it.role == MessageRole.SYSTEM }
        .let { if (it >= 0) it + 1 else 0 }

    // 角色卡消息锚点（官方独立消息，CharacterCardData 标记）
    val cardIndices = result.indices.filter { idx ->
        result[idx].annotations.any { it is UIMessageAnnotation.CharacterCardData }
    }

    // 处理 BEFORE_CHARACTER：主提示之后、角色卡消息之前（官方 ↑Char）
    val beforeCharInjections = byPosition[InjectionPosition.BEFORE_CHARACTER]
    if (!beforeCharInjections.isNullOrEmpty()) {
        var insertIndex = if (cardIndices.isNotEmpty()) cardIndices.first()
        else (result.indexOfFirst { it.role == MessageRole.SYSTEM }.let { if (it >= 0) it + 1 else 0 })
        insertIndex = findSafeInsertIndex(result, insertIndex)
        createMergedInjectionMessages(beforeCharInjections).forEach { message ->
            result.add(insertIndex, message)
            insertIndex++
        }
    }

    // 处理 AFTER_CHARACTER：角色卡消息之后（官方 ↓Char）
    val afterCharInjections = byPosition[InjectionPosition.AFTER_CHARACTER]
    if (!afterCharInjections.isNullOrEmpty()) {
        val currentCardIndices = result.indices.filter { idx ->
            result[idx].annotations.any { it is UIMessageAnnotation.CharacterCardData }
        }
        var insertIndex = if (currentCardIndices.isNotEmpty()) currentCardIndices.last() + 1 else fallbackAfterSystem
        insertIndex = findSafeInsertIndex(result, insertIndex)
        createMergedInjectionMessages(afterCharInjections).forEach { message ->
            result.add(insertIndex, message)
            insertIndex++
        }
    }

    // 处理 EM_TOP：第一条示例消息之前
    val emTopInjections = byPosition[InjectionPosition.EM_TOP]
    if (!emTopInjections.isNullOrEmpty()) {
        var insertIndex = if (exampleIndices.isNotEmpty()) exampleIndices.first() else fallbackAfterSystem
        insertIndex = findSafeInsertIndex(result, insertIndex)
        createMergedInjectionMessages(emTopInjections).forEach { message ->
            result.add(insertIndex, message)
            insertIndex++
        }
    }

    // 处理 EM_BOTTOM：最后一条示例消息之后
    val emBottomInjections = byPosition[InjectionPosition.EM_BOTTOM]
    if (!emBottomInjections.isNullOrEmpty()) {
        // EM_TOP 插入后重新定位示例消息（插入内容不带标记，索引可能已变化）
        val currentExampleIndices = result.indices.filter { idx ->
            result[idx].annotations.any { it is UIMessageAnnotation.ExampleMessage }
        }
        var insertIndex = if (currentExampleIndices.isNotEmpty()) currentExampleIndices.last() + 1 else fallbackAfterSystem
        insertIndex = findSafeInsertIndex(result, insertIndex)
        createMergedInjectionMessages(emBottomInjections).forEach { message ->
            result.add(insertIndex, message)
            insertIndex++
        }
    }

    // 找到系统消息的索引（通常是第一条）
    val systemIndex = result.indexOfFirst { it.role == MessageRole.SYSTEM }

    // 处理 BEFORE_SYSTEM_PROMPT 和 AFTER_SYSTEM_PROMPT
    if (systemIndex >= 0) {
        val beforeContent = byPosition[InjectionPosition.BEFORE_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""
        val afterContent = byPosition[InjectionPosition.AFTER_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""

        if (beforeContent.isNotEmpty() || afterContent.isNotEmpty()) {
            val systemMessage = result[systemIndex]
            val originalText = systemMessage.parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("") { it.text }

            val newText = buildString {
                if (beforeContent.isNotEmpty()) {
                    append(beforeContent)
                    appendLine()
                }
                append(originalText)
                if (afterContent.isNotEmpty()) {
                    appendLine()
                    append(afterContent)
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
        val afterContent = byPosition[InjectionPosition.AFTER_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""

        val combinedContent = buildString {
            if (beforeContent.isNotEmpty()) {
                append(beforeContent)
                if (afterContent.isNotEmpty()) appendLine()
            }
            if (afterContent.isNotEmpty()) {
                append(afterContent)
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
                MessageRole.SYSTEM -> UIMessage.system(mergedContent)
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
