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
import kotlin.math.roundToInt

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
            worldInfoBudgetCap = ctx.settings.worldInfoBudgetCap,
            worldInfoMinActivations = ctx.settings.worldInfoMinActivations,
            worldInfoMinActivationsDepthMax = ctx.settings.worldInfoMinActivationsDepthMax,
            worldInfoRecursive = ctx.settings.worldInfoRecursive,
            worldInfoMaxRecursionSteps = ctx.settings.worldInfoMaxRecursionSteps,
            worldInfoDepth = ctx.settings.worldInfoDepth,
            worldInfoCharacterStrategy = ctx.settings.worldInfoCharacterStrategy,
            worldInfoOverflowAlert = ctx.settings.worldInfoOverflowAlert,
            worldInfoUseGroupScoring = ctx.settings.worldInfoUseGroupScoring,
            generationType = ctx.generationType,
            onOverflow = { ctx.processingStatus?.value = "世界书预算已满，部分条目未注入" },
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
    worldInfoBudgetCap: Int = 0,
    worldInfoMinActivations: Int = 0,
    worldInfoMinActivationsDepthMax: Int = 0,
    worldInfoRecursive: Boolean = false,
    worldInfoMaxRecursionSteps: Int = 0,
    worldInfoDepth: Int = 2,
    worldInfoCharacterStrategy: Int = 1,
    worldInfoOverflowAlert: Boolean = false,
    worldInfoUseGroupScoring: Boolean = false,
    generationType: me.rerere.rikkahub.data.model.GenerationType = me.rerere.rikkahub.data.model.GenerationType.NORMAL,
    personaDescription: String = "",
    onOverflow: () -> Unit = {},
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
        worldInfoBudgetCap = worldInfoBudgetCap,
        worldInfoMinActivations = worldInfoMinActivations,
        worldInfoMinActivationsDepthMax = worldInfoMinActivationsDepthMax,
        worldInfoRecursive = worldInfoRecursive,
        worldInfoMaxRecursionSteps = worldInfoMaxRecursionSteps,
        worldInfoDepth = worldInfoDepth,
        worldInfoCharacterStrategy = worldInfoCharacterStrategy,
        worldInfoOverflowAlert = worldInfoOverflowAlert,
        worldInfoUseGroupScoring = worldInfoUseGroupScoring,
        generationType = generationType,
        personaDescription = personaDescription,
        onOverflow = onOverflow,
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

    // 按位置分组。官方构建提示词时按 order 降序遍历 + unshift（world-info.js sortFn + WIBeforeEntries.unshift），
    // 最终注入顺序 = order 升序（先写的在前），这里直接按 priority 升序对齐
    val byPosition = resolvedInjections
        .sortedBy { it.priority }
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
    worldInfoBudgetCap: Int = 0,
    worldInfoMinActivations: Int = 0,
    worldInfoMinActivationsDepthMax: Int = 0,
    worldInfoRecursive: Boolean = false,
    worldInfoMaxRecursionSteps: Int = 0,
    worldInfoDepth: Int = 2,
    worldInfoCharacterStrategy: Int = 1,
    worldInfoOverflowAlert: Boolean = false,
    worldInfoUseGroupScoring: Boolean = false,
    generationType: me.rerere.rikkahub.data.model.GenerationType = me.rerere.rikkahub.data.model.GenerationType.NORMAL,
    personaDescription: String = "",
    onOverflow: () -> Unit = {},
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

    // 2. 获取关联的 Lorebook 中被触发的 RegexInjection。
    //    官方模型（world-info.js checkWorldInfo）：角色卡内嵌书在导入时转成独立外置书并绑定，
    //    注入只读外置绑定；解绑后不再注入。所有选中书的条目合并成一个列表统一扫描（全局设置）
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

        // 官方 getSortedEntries：所有选中书的条目合并成一个列表，按策略排序。
        // 排序顺序影响扫描顺序（概率/预算检查顺序），官方 sortFn = (a, b) => b.order - a.order（order 降序）
        val sortedEntries = enabledLorebooks
            .flatMap { book -> book.entries.map { entry -> book to entry } }
            .let { pairs ->
                when (worldInfoCharacterStrategy) {
                    // 0 = evenly：全局与角色卡条目混排（官方 [...globalLore, ...characterLore].sort(sortFn)）
                    0 -> pairs.sortedWith(compareByDescending { it.second.priority })
                    // 1 = character_first：角色卡条目在前
                    1 -> pairs.sortedWith(
                        compareByDescending<Pair<Lorebook, PromptInjection.RegexInjection>> { it.first.isCharacterBook }
                            .thenByDescending { it.second.priority }
                    )
                    // 2 = global_first：全局条目在前
                    else -> pairs.sortedWith(
                        compareBy<Pair<Lorebook, PromptInjection.RegexInjection>> { it.first.isCharacterBook }
                            .thenByDescending { it.second.priority }
                    )
                }
            }

        // 官方 checkWorldInfo 单循环共享状态：
        // allActivatedEntries（Map，key=world.uid 去重）、递归缓冲、failedProbabilityChecks、skew、预算
        val activatedEntries = mutableListOf<PromptInjection.RegexInjection>()
        val knownIds = mutableSetOf<Uuid>()
        var recursionContext = ""
        var skew = 0
        var currentLevel = 0
        var overflowed = false
        var count = 0
        // 官方 scan_state：INITIAL / RECURSION / MIN_ACTIVATIONS（官方 world_info_scan_type 枚举）
        var scanState = 0 // 0=INITIAL 1=RECURSION 2=MIN_ACTIVATIONS
        // 官方 availableRecursionDelayLevels：全部条目的 delay_until_recursion 去重升序，逐级开放
        val availableLevels = sortedEntries
            .map { it.second.delayUntilRecursion }
            .filter { it > 0 }
            .distinct()
            .sorted()
            .toMutableList()
        // 官方预算：budget = round(world_info_budget% × maxContext / 100) || 1；cap > 0 时封顶。
        // maxContext 本地无独立上下文预算字段，用当前消息总 token 估算
        val maxContext = estimateTokens(messages.joinToString("\n") { it.toText() })
        val budget = ((worldInfoBudget * maxContext) / 100.0).roundToInt().coerceAtLeast(1)
            .let { if (worldInfoBudgetCap > 0 && it > worldInfoBudgetCap) worldInfoBudgetCap else it }

        // 官方 while (scanState)：INITIAL → (RECURSION / MIN_ACTIVATIONS / 层级开放) 循环
        // max_recursion_steps 语义（官方）：每轮开头检查 count >= 上限则停止，count 从 0 起算，
        // 即总扫描轮数上限 = max_recursion_steps；0 = 不限制
        while (worldInfoMaxRecursionSteps <= 0 || count < worldInfoMaxRecursionSteps) {
            count++
            val isRecursion = scanState == 1
            val newlyTriggered = mutableListOf<PromptInjection.RegexInjection>()
            // 触发时的关键词匹配分（酒馆 use_group_scoring 用）
            val triggeredScores = mutableMapOf<Uuid, Int>()

            for ((lorebook, entry) in sortedEntries) {
                // 官方：已激活条目和概率失败过的条目直接跳过
                if (knownIds.contains(entry.id) || failedProbabilityIds.contains(entry.id)) continue

                // 官方：disable 条目跳过（在 triggers 过滤之前；官方 disable 字段导入后映射为 enabled）
                if (!entry.enabled) continue

                // 生成类型过滤（酒馆 triggers）
                if (entry.triggers.isNotEmpty() && generationType.value !in entry.triggers) continue

                // 官方：delay 中的条目跳过（在 cooldown 之前，无豁免）
                if (entry.delay > 0 && nonSystemMessages.size < entry.delay) continue

                // 冷却中的条目跳过（官方 isCooldown && !isSticky：粘性豁免）
                if (cooldownEntries.containsKey(entry.id) && !activeStickyEntries.containsKey(entry.id)) continue

                // 官方：非递归扫描跳过所有 delay_until_recursion 条目（粘性豁免）
                if (entry.delayUntilRecursion > 0 &&
                    !isRecursion &&
                    !activeStickyEntries.containsKey(entry.id)
                ) {
                    continue
                }

                // 官方：递归扫描只放行层级 <= 当前开放层级的条目（粘性豁免）
                if (isRecursion &&
                    entry.delayUntilRecursion > currentLevel &&
                    !activeStickyEntries.containsKey(entry.id)
                ) {
                    continue
                }

                // 官方：exclude_recursion 条目在递归扫描中被跳过（官方还要求全局递归开关开启；
                // 全局递归关时 delay_until_recursion 层级开放也走 RECURSION 状态，但官方不视其为递归，不排除）
                if (isRecursion && worldInfoRecursive && entry.excludeRecursion && !activeStickyEntries.containsKey(entry.id)) continue

                // 官方：constant / 激活中 sticky 条目直接加入（constant 在前，sticky 在后）
                if (entry.constantActive || activeStickyEntries.containsKey(entry.id)) {
                    newlyTriggered.add(entry)
                    continue
                }

                // 官方 WorldInfoBuffer.get：条目 scanDepth 优先，否则全局深度 + skew；
                // 官方 startDepth 恒为 0（advanceScan 只增 skew），min_activations 推进时整段重扫
                val depth = entry.scanDepth ?: (worldInfoDepth + skew)
                val chatContext = extractContextForMatching(nonSystemMessages, depth, 0)
                // 官方 match_*：只把该条目开启的角色卡字段纳入扫描
                val entryCharScan = buildCharScanContext(entry)
                val context = buildString {
                    if (entryCharScan.isNotEmpty()) {
                        append(entryCharScan)
                        appendLine()
                    }
                    // 官方 buffer.get：递归缓冲拼入除 MIN_ACTIVATIONS 外的所有扫描
                    if (scanState != 2 && recursionContext.isNotEmpty()) {
                        append(recursionContext)
                        appendLine()
                    }
                    append(chatContext)
                }
                if (entry.isTriggered(context, rollProbability = false)) {
                    newlyTriggered.add(entry)
                    triggeredScores[entry.id] = entry.matchedKeyScore(context)
                }
            }

            // 官方：组选后逐条掷概率 + 预算（newEntries.sort：粘性优先，再按 sortedEntries 顺序）
            val found = selectGroupWinners(
                newlyTriggered = newlyTriggered,
                triggeredScores = triggeredScores,
                activeStickyEntries = activeStickyEntries,
                alreadyActivated = activatedEntries,
                globalUseGroupScoring = worldInfoUseGroupScoring,
            )
            val accepted = mutableListOf<PromptInjection.RegexInjection>()
            var pendingIgnoreBudget = found.count { it.ignoreBudget }
            // 官方 newContent：本轮概率已通过的条目内容（含预算溢出的，官方 += 在预算检查之前）
            var newContentTokens = 0
            for (entry in found.sortedWith(
                compareByDescending<PromptInjection.RegexInjection> { activeStickyEntries.containsKey(it.id) }
                    .thenByDescending { it.priority }
            )) {
                pendingIgnoreBudget -= if (entry.ignoreBudget) 1 else 0
                // 官方：预算溢出后非 ignoreBudget 条目不再注入（后面还有 ignoreBudget 则跳过，否则停止）
                if (overflowed && !entry.ignoreBudget) {
                    if (pendingIgnoreBudget > 0) continue else break
                }
                // 官方 verifyProbability：useProbability 且 <100 才掷；sticky 免掷；失败记入 failedProbabilityChecks
                if (entry.useProbability && entry.probability < 100 && !activeStickyEntries.containsKey(entry.id)) {
                    if (Random.nextInt(100) >= entry.probability) {
                        failedProbabilityIds.add(entry.id)
                        continue
                    }
                }
                if (!entry.ignoreBudget) {
                    // 官方预算检查：递归缓冲 token + 本轮内容 token >= 预算 → 溢出，该条目也不注入
                    if (estimateTokens(recursionContext) + newContentTokens + estimateTokens(entry.content) >= budget) {
                        overflowed = true
                        if (worldInfoOverflowAlert) onOverflow()
                        newContentTokens += estimateTokens(entry.content)
                        continue
                    }
                    newContentTokens += estimateTokens(entry.content)
                }
                accepted.add(entry)
            }

            val newOnes = accepted.filter { it.id !in knownIds }
            newOnes.forEach { knownIds.add(it.id) }
            activatedEntries.addAll(newOnes)

            // 官方 successfulNewEntriesForRecursion：prevent_recursion 条目的内容不进递归缓冲，逐轮累积
            val newRecursionText = newOnes
                .filter { !it.preventRecursion }
                .joinToString("\n") { it.content }
            if (newRecursionText.isNotBlank()) {
                recursionContext = listOf(recursionContext, newRecursionText)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
            }

            // 官方状态机：
            // 1. 本轮有成功新条目（不含 prevent_recursion）且未溢出 → 递归扫描
            var nextScanState = -1 // -1 = 无下一步（停止）
            if (worldInfoRecursive && !overflowed && newRecursionText.isNotBlank()) {
                nextScanState = 1
            }
            // 2. min_activations 扫描中且有递归缓冲 → 先递归一次（官方 buffer.hasRecurse() 分支）
            if (nextScanState == -1 && worldInfoRecursive && !overflowed &&
                scanState == 2 && recursionContext.isNotBlank()
            ) {
                nextScanState = 1
            }
            // 3. min_activations 未满足 → 扫描深度 +1 重扫（官方 buffer.advanceScan），
            //    深度超限（min_activations_depth_max 或全部消息）才停；min 扫描不带递归缓冲
            val minNotSatisfied = worldInfoMinActivations > 0 && activatedEntries.size < worldInfoMinActivations
            val overMaxDepth = (worldInfoMinActivationsDepthMax > 0 &&
                worldInfoDepth + skew > worldInfoMinActivationsDepthMax) ||
                (worldInfoDepth + skew > nonSystemMessages.size)
            if (nextScanState == -1 && !overflowed && minNotSatisfied && !overMaxDepth) {
                nextScanState = 2
                skew++
            }
            // 4. 还有未开放的 delay_until_recursion 层级 → 开放下一级继续扫描
            if (nextScanState == -1 && availableLevels.isNotEmpty()) {
                nextScanState = 1
                currentLevel = availableLevels.removeAt(0)
            }
            if (nextScanState == -1) break
            scanState = nextScanState
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
    globalUseGroupScoring: Boolean = false,
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

        // 官方 filterGroupsByScoring：全局 use_group_scoring 与条目开关取或
        // 先移除“参与评分且分数低于组内最高”的条目（未参与评分的条目保留，随后仍参与 override/加权随机）
        val isScored = { entry: PromptInjection.RegexInjection -> entry.useGroupScoring || globalUseGroupScoring }
        val scored = entries.filter(isScored)
        val survivors = if (scored.isNotEmpty()) {
            val maxScore = scored.maxOf { triggeredScores[it.id] ?: 0 }
            entries.filter { !isScored(it) || (triggeredScores[it.id] ?: 0) == maxScore }
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

    // 处理 BEFORE_CHARACTER：主提示之后（官方 promptManagerDefaultPromptOrder：main → ↑Char → 人设 → 角色卡字段）
    val beforeCharInjections = byPosition[InjectionPosition.BEFORE_CHARACTER]
    if (!beforeCharInjections.isNullOrEmpty()) {
        var insertIndex = result.indexOfFirst { it.role == MessageRole.SYSTEM }
            .let { if (it >= 0) it + 1 else 0 }
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
