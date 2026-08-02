package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

/**
 * 酒馆角色卡结构化数据
 * 从 V2/V3 spec 完整解析，不丢失任何字段
 */
@Serializable
data class TavernCharacterData(
    val spec: String = "",                          // "chara_card_v2" or "chara_card_v3"
    val specVersion: String = "",                    // e.g. "3.0"
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",                   // 开场白
    val alternateGreetings: List<String> = emptyList(), // 备选开场白
    val mesExample: String = "",                     // 示例对话
    val systemPrompt: String = "",                   // 系统提示词（角色卡原始system_prompt）
    val creator: String = "",                        // 作者
    val creatorNotes: String = "",                   // 作者备注
    val characterVersion: String = "",               // 角色版本
    val tags: List<String> = emptyList(),            // 文本标签
    val postHistoryInstructions: String = "",        // 历史后指令
    val extensions: Map<String, String> = emptyMap(), // V3 扩展字段
    val extensionsRaw: String = "",                  // V3 扩展字段原始 JSON（无损保留，导出时原样带回）
    val assets: List<TavernAsset> = emptyList(),     // V3 资源引用
    val groupOnlyGreetings: List<String> = emptyList(), // 群聊专用开场白
    // V3 高级字段（官方 spec，导入解析、导出原样写回）
    val nickname: String = "",                       // 角色别名（{{char}} 占位符替换用）
    val creatorNotesMultilingual: String = "",       // 多语言作者备注原始 JSON（无损保留）
    val source: List<String> = emptyList(),          // 来源引用 URL/ID 列表
    val creationDate: String = "",                   // 创建时间戳原始 JSON 文本（数字/字符串原样带回）
    val modificationDate: String = "",               // 修改时间戳原始 JSON 文本
    // 内嵌世界书
    val embeddedBook: TavernEmbeddedBook? = null,
)

@Serializable
data class TavernAsset(
    val type: String = "",      // "image", "audio", etc.
    val name: String = "",
    val uri: String = "",       // asset URI
    val ext: String = "",       // file extension
)

/**
 * 内嵌世界书（character_book）
 * 对齐酒馆 V2/V3 world book 格式
 */
@Serializable
data class TavernEmbeddedBook(
    val name: String = "",
    val description: String = "",
    val scanDepth: Int? = null,
    val tokenBudget: Int? = null,
    val recursiveScanning: Boolean? = null,
    val maxRecursionSteps: Int? = null,
    val minActivations: Int? = null,
    val extensions: Map<String, String> = emptyMap(),
    val extensionsRaw: String = "",              // 顶层 extensions 原始 JSON（无损保留，导出优先）
    val entries: List<TavernBookEntry> = emptyList(),
)

@Serializable
data class TavernBookEntry(
    val id: Int = 0,
    val keys: List<String> = emptyList(),
    val secondaryKeys: List<String> = emptyList(),
    val comment: String = "",
    val content: String = "",
    val constant: Boolean = false,
    val selective: Boolean = false,
    val selectiveLogic: Int = 0,  // 0=AND, 1=OR, 2=NOT_ANY, 3=NOT_ALL
    val group: String = "",
    val position: Int = 1,        // 0=before_char, 1=after_char, 2=before_user, 3=after_user, 4=@D
    val priority: Int = 100,      // order/priority, lower = higher
    val disable: Boolean = false,
    val caseSensitive: Boolean = false,
    val matchWholeWords: Boolean = false, // 整词匹配（酒馆 extensions.match_whole_words）
    val useRegex: Boolean = false,
    val probability: Int = 100,   // 0-100, 触发概率
    val sticky: Int = 0,          // 激活后持续保留N轮（0=不粘）
    val cooldown: Int = 0,       // 冷却轮数
    val depth: Int = 4,          // @D 模式插入深度
    val scanDepth: Int = 1000,   // 扫描最近N条消息（酒馆默认1000）
    val role: String = "system", // system/user/assistant（JSON兼容数字和字符串）
    val groupWeight: Int = 100,  // 同组权重（随机选择时使用）
    val groupOverride: Boolean = false, // 是否覆盖同组其他条目
    val delay: Int = 0,          // 延迟激活轮数（0=立即，酒馆 extensions.delay）
    val excludeRecursion: Boolean = false, // 内容不参与递归扫描（酒馆 extensions.exclude_recursion）
    val preventRecursion: Boolean = false, // 禁止被递归触发（酒馆 extensions.prevent_recursion）
    val delayUntilRecursion: Boolean = false, // 只在递归扫描时检查（酒馆 extensions.delay_until_recursion）
    val useProbability: Boolean = false, // 是否启用概率过滤（酒馆默认false）
    val inclusionGroup: String = "", // 酒馆 extensions.inclusion_group（逗号分隔多组）
    val useGroupScoring: Boolean = false, // 酒馆 extensions.use_group_scoring
    val groupPriority: Boolean = false, // 酒馆 extensions.group_priority
    val automationId: String = "", // 酒馆 extensions.automation_id
    val displayIndex: Int = 0, // 酒馆 display_index
    val displayPosition: Int = 0, // 酒馆 display_position
    val triggers: List<String> = emptyList(), // 酒馆 triggers
    val extensionsRaw: String = "", // 条目 extensions 原始 JSON（无损保留，导出优先）
)
