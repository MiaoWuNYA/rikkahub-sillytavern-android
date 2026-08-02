package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 群聊配置 — 对齐酒馆 GroupChat
 */
@Serializable
data class GroupChat(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val memberIds: List<Uuid> = emptyList(),    // 引用 assistants
    val activationStrategy: GroupActivationStrategy = GroupActivationStrategy.NATURAL,
    val generationMode: GroupGenerationMode = GroupGenerationMode.APPEND,
    val disabledMemberIds: List<Uuid> = emptyList(),  // 禁言成员
    val speakerWeights: Map<Uuid, Int> = emptyMap(),  // for POOLED
    val conversationId: Uuid? = null,  // 关联的 Conversation ID
    val allowSelfResponses: Boolean = false,
    val autoModeDelay: Int = 5,  // 自动接话延迟（秒）
    val autoChatRounds: Int = 5, // 自动接话轮数上限（0=无上限；每轮全体已选成员各发言一次）
    val enabled: Boolean = true,
    val chatModelId: Uuid? = null,  // 群聊级模型覆盖（不选则用各成员自己的模型）
)

@Serializable
enum class GroupActivationStrategy {
    NATURAL,   // AI根据上下文决定谁说话
    LIST,      // 按名单顺序轮流
    MANUAL,    // 用户手动选择
    POOLED,    // 加权随机抽取
}

@Serializable
enum class GroupGenerationMode {
    SWAP,             // 替换上一条消息
    APPEND,           // 追加新消息
    APPEND_DISABLED,  // 追加，但包含禁言成员的上一条消息
}

/**
 * 群聊会话
 */
@Serializable
data class GroupConversation(
    val id: Uuid = Uuid.random(),
    val groupId: Uuid,
    val title: String = "",
    val speakerQueue: List<Int> = emptyList(),
    val currentSpeakerIndex: Int = 0,
    val lastSpeakerId: Uuid? = null,
)
