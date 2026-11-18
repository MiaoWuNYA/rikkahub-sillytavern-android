package me.rerere.rikkahub.ui.pages.extensions

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection

internal enum class EntrySettingKey(val label: String) {
    ENABLED("启用状态"),
    PRIORITY("优先级"),
    POSITION("注入位置"),
    DEPTH("注入深度"),
    EXCLUDE_RECURSION("排除递归"),
    PREVENT_RECURSION("禁止递归"),
    DELAY_RECURSION("递归延迟等级"),
    USE_PROBABILITY("使用概率"),
    PROBABILITY("注入概率"),
    STICKY("粘性"),
    COOLDOWN("冷却"),
    DELAY("延迟"),
    CONSTANT_ACTIVE("永久"),
    VECTORIZED("向量检索激活"),
    SCAN_DEPTH("扫描深度"),
    GROUP_SCORING("组评分"),
    IGNORE_BUDGET("忽略预算"),
    ROLE("注入角色");
    fun read(entry: PromptInjection.RegexInjection): Any? = when (this) {
        ENABLED -> entry.enabled
        PRIORITY -> entry.priority
        POSITION -> entry.position
        DEPTH -> entry.injectDepth
        EXCLUDE_RECURSION -> entry.excludeRecursion
        PREVENT_RECURSION -> entry.preventRecursion
        DELAY_RECURSION -> entry.delayUntilRecursion
        USE_PROBABILITY -> entry.useProbability
        PROBABILITY -> entry.probability
        STICKY -> entry.sticky
        COOLDOWN -> entry.cooldown
        DELAY -> entry.delay
        CONSTANT_ACTIVE -> entry.constantActive
        VECTORIZED -> entry.vectorized
        SCAN_DEPTH -> entry.scanDepth
        GROUP_SCORING -> entry.useGroupScoring
        IGNORE_BUDGET -> entry.ignoreBudget
        ROLE -> entry.role
    }
    fun write(entry: PromptInjection.RegexInjection, value: Any?): PromptInjection.RegexInjection = when (this) {
        ENABLED -> entry.copy(enabled = value as Boolean)
        PRIORITY -> entry.copy(priority = value as Int)
        POSITION -> entry.copy(position = value as InjectionPosition)
        DEPTH -> entry.copy(injectDepth = value as Int)
        EXCLUDE_RECURSION -> entry.copy(excludeRecursion = value as Boolean)
        PREVENT_RECURSION -> entry.copy(preventRecursion = value as Boolean)
        DELAY_RECURSION -> entry.copy(delayUntilRecursion = value as Int)
        USE_PROBABILITY -> entry.copy(useProbability = value as Boolean)
        PROBABILITY -> entry.copy(probability = value as Int)
        STICKY -> entry.copy(sticky = value as Int)
        COOLDOWN -> entry.copy(cooldown = value as Int)
        DELAY -> entry.copy(delay = value as Int)
        CONSTANT_ACTIVE -> entry.copy(constantActive = value as Boolean)
        VECTORIZED -> entry.copy(vectorized = value as Boolean)
        SCAN_DEPTH -> entry.copy(scanDepth = value as Int?)
        GROUP_SCORING -> entry.copy(useGroupScoring = value as Boolean)
        IGNORE_BUDGET -> entry.copy(ignoreBudget = value as Boolean)
        ROLE -> entry.copy(role = value as MessageRole)
    }

    // 读取旧配置不校验未选字段；只校验本次明确参与应用的新值。
    fun accepts(value: Any?): Boolean = when (this) {
        PROBABILITY -> value is Int && value in 0..100
        DELAY_RECURSION -> value is Int && value >= 0
        else -> true
    }
}
