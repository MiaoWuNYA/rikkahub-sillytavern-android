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
 *  - [assistantId]: 使用的助手. 留空 = 用当前助手.
 *  - [allowForceJump]: 是否允许 AI 通过 [PASS] 之外的 [JUMP] 标记拉起聊天界面.
 *  - [jumpIdleThresholdMinutes]: 用户多久没回复 (分钟) 才允许 [JUMP] 跳转屏幕.
 */
@Serializable
data class ProactiveMessageSetting(
    val enabled: Boolean = false,
    val minIntervalMinutes: Int = 30,
    val maxIntervalMinutes: Int = 90,
    val assistantId: String = "",
    val allowForceJump: Boolean = false,
    val jumpIdleThresholdMinutes: Int = 120,
)
