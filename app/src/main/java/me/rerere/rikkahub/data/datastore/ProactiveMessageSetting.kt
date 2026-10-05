package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

/**
 * AI 主动发消息配置.
 *
 * 开启后 AI 会在 [minIntervalMinutes] ~ [maxIntervalMinutes] 之间随机一个时间点，
 * 主动给用户发一条消息（没什么好说的可以 [PASS] 跳过）。
 *
 * 字段:
 *  - [enabled]: 总开关. 开启后按间隔循环调度.
 *  - [minIntervalMinutes] / [maxIntervalMinutes]: 随机触发间隔范围 (分钟).
 *    两者下限都是 1 —— 之前 UI 上最小值受 placeholder 提示影响、且输入过程
 *    会被静默丢弃，等于改不动。
 *  - [assistantId]: 使用的助手. 留空 = 用当前助手.
 *  - [conversationId]: 固定发送到哪个对话. 留空 = 该助手最近的一个对话.
 *    主动消息会写进这个对话的历史，选错对话等于污染别处，所以要能指定。
 *  - [allowForceJump]: 是否允许 AI 通过 [JUMP] 标记拉起聊天界面.
 *  - [jumpIdleThresholdMinutes]: 用户多久没回复 (分钟) 才允许 [JUMP] 跳转屏幕.
 */
@Serializable
data class ProactiveMessageSetting(
    val enabled: Boolean = false,
    val minIntervalMinutes: Int = 30,
    val maxIntervalMinutes: Int = 90,
    val assistantId: String = "",
    /** 固定对话；空字符串表示「该助手最近的一个对话」。 */
    val conversationId: String = "",
    val allowForceJump: Boolean = false,
    val jumpIdleThresholdMinutes: Int = 120,
) {
    companion object {
        /** 间隔下限。0 或负数会让闹钟立刻连环触发，必须挡住。 */
        const val MIN_INTERVAL_MINUTES = 1

        /** 间隔上限。再大也没有实际意义，同时避免 Long 溢出。 */
        const val MAX_INTERVAL_MINUTES = 60 * 24 * 365
    }

    /**
     * 归一化：把非法值夹回合理范围，并保证 min <= max。
     *
     * 所有写入路径都应该过一遍这个函数，而不是各自判断——之前 UI 里
     * 两个输入框各写各的校验，导致「把 max 调到小于 min」时输入被静默
     * 丢弃、界面看起来没反应。
     */
    fun normalized(): ProactiveMessageSetting {
        val lo = minIntervalMinutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
        val hi = maxIntervalMinutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
        return copy(
            minIntervalMinutes = minOf(lo, hi),
            maxIntervalMinutes = maxOf(lo, hi),
            jumpIdleThresholdMinutes = jumpIdleThresholdMinutes.coerceAtLeast(0),
        )
    }
}
