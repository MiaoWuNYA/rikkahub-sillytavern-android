package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.utils.SimpleCache
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

@Serializable
data class Assistant(
    val id: Uuid = Uuid.random(),
    val chatModelId: Uuid? = null, // 如果为null, 使用全局默认模型
    val name: String = "",
    val avatar: Avatar = Avatar.Dummy,
    val useAssistantAvatar: Boolean = false, // 使用助手头像替代模型头像
    val tags: List<Uuid> = emptyList(),
    val systemPrompt: String = "",
    val temperature: Float? = null,
    val topP: Float? = null,
    // 上下文消息条数上限, 超出后阶梯式截断; 0 表示不限制
    val contextMessageLimit: Int = 0,
    val streamOutput: Boolean = true,
    val enableMemory: Boolean = false,
    val useGlobalMemory: Boolean = false, // 使用全局共享记忆而非助手隔离记忆
    val enableRecentChatsReference: Boolean = false,
    val messageTemplate: String = "{{ message }}",
    val contextTemplate: String = DEFAULT_CONTEXT_TEMPLATE, // 上下文组装模板（ADF风格）
    val presetMessages: List<UIMessage> = emptyList(),
    val quickMessageIds: Set<Uuid> = emptySet(),
    val regexes: List<AssistantRegex> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val maxTokens: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val mcpServers: Set<Uuid> = emptySet(),
    val localTools: List<LocalToolOption> = listOf(
        LocalToolOption.TimeInfo,
        LocalToolOption.FileTools,
        LocalToolOption.ShellTools,
        LocalToolOption.TaskTools,
        LocalToolOption.Calculator,
        LocalToolOption.AskUser,
        LocalToolOption.Clipboard,
    ),
    val enableWebSearch: Boolean = false, // 网络搜索开关(每个助手独立)
    val workspaceId: Uuid? = null,
    val background: String? = null, // 聊天页背景图地址(本地文件 URI 或网络 URL), 为 null 时无背景
    val backgroundOpacity: Float = 1.0f, // 背景图不透明度(0~1)
    val useGradientBackground: Boolean = false, // 开启后聊天页使用动态渐变背景
    val modeInjectionIds: Set<Uuid> = emptySet(),      // 关联的模式注入 ID
    val lorebookIds: Set<Uuid> = emptySet(),            // 关联的 Lorebook ID
    val enabledSkills: Set<String> = emptySet(),        // 启用的 skill 名称列表
    val enableTimeReminder: Boolean = false,            // 时间间隔提醒注入
    val allowConversationSystemPrompt: Boolean = false, // 允许对话单独重写 system prompt
    val allowConversationPromptInjection: Boolean = false, // 允许对话单独绑定提示词注入
    val tavernData: TavernCharacterData? = null,       // 酒馆角色卡结构化数据（从PNG/JSON导入时填充）
    val enableParallelToolExecution: Boolean = true,    // 并行执行多个工具调用
    val enableAutoFixCi: Boolean = false,               // GitHub 工具自动修复 CI
    val enableCiTimeout: Int = 120,                     // CI 工具操作超时(秒)，默认 120
    val toolRecurringLimit: Int = 8,                    // 单批同工具调用上限
    val totalStepsLimit: Int = 256,                     // 总工具调用轮数上限
    val toolExecTimeout: Int = 120,                     // 单工具执行超时(秒)
    val jsTimeout: Int = 15,                            // JavaScript引擎超时(秒)
    val shellTimeout: Int = 60,                         // Shell命令超时(秒)
    val enableKnowledgeBase: Boolean = false,           // 启用知识库检索
    val talkativeness: Float = 0.5f,                    // 群聊发言倾向 (0-1)，酒馆对齐
    val embeddingModelId: Uuid? = null,                  // 专用embedding模型（null=使用聊天模型）
    val enableAutoCompact: Boolean = true,               // 自动压缩对话历史（token过多时）
    val enableAutoMemoryExtract: Boolean = true,          // [新增] 自动从对话提取记忆（独立开关，不依赖 enableMemory）
    val autoMemoryExtractInterval: Int = 5,                // 每 N 轮对话提取一次记忆
)

@Serializable
data class QuickMessage(
    val id: Uuid = Uuid.random(),
    val title: String = "",
    val content: String = "",
)

@Serializable
data class AssistantMemory(
    val id: Int,
    val content: String = "",
)

@Serializable
enum class AssistantAffectScope {
    USER,
    ASSISTANT,
}

@Serializable
data class AssistantRegex(
    val id: Uuid,
    val name: String = "",
    val enabled: Boolean = true,
    val findRegex: String = "", // 正则表达式
    val replaceString: String = "", // 替换字符串
    val affectingScope: Set<AssistantAffectScope> = setOf(),
    val visualOnly: Boolean = false, // 是否仅在视觉上影响
)

// 流式输出时每个chunk都会调用replaceRegexes，正则必须缓存编译结果，
// 否则长回复期间会重复编译上万次；编译失败也缓存，避免反复构造异常
private val regexCache = SimpleCache.builder<String, Result<Regex>>()
    .expireAfterWrite(10, TimeUnit.MINUTES)
    .build()

private fun compileRegexCached(pattern: String): Regex? {
    regexCache.getIfPresent(pattern)?.let { return it.getOrNull() }
    val result = runCatching { Regex(pattern) }.onFailure { it.printStackTrace() }
    regexCache.put(pattern, result)
    return result.getOrNull()
}

fun String.replaceRegexes(
    assistant: Assistant?,
    scope: AssistantAffectScope,
    visual: Boolean = false
): String {
    if (assistant == null) return this
    if (assistant.regexes.isEmpty()) return this
    return assistant.regexes.fold(this) { acc, regex ->
        if (regex.enabled && regex.visualOnly == visual && regex.affectingScope.contains(scope)) {
            val compiled = compileRegexCached(regex.findRegex) ?: return@fold acc
            try {
                acc.replace(
                    regex = compiled,
                    replacement = regex.replaceString,
                )
            } catch (e: Exception) {
                e.printStackTrace()
                // 替换字符串可能引用不存在的分组，失败时返回原字符串
                acc
            }
        } else {
            acc
        }
    }
}

/**
 * 注入位置
 */
@Serializable
enum class InjectionPosition {
    @SerialName("before_system_prompt")
    BEFORE_SYSTEM_PROMPT,   // 系统提示词之前

    @SerialName("after_system_prompt")
    AFTER_SYSTEM_PROMPT,    // 系统提示词之后（最常用）

    @SerialName("before_character")
    BEFORE_CHARACTER,       // 角色卡信息之前（酒馆 before_char）

    @SerialName("after_character")
    AFTER_CHARACTER,        // 角色卡信息之后（酒馆 after_char）

    @SerialName("antagonize")
    ANTAGONIZE,             // 对抗位：角色卡与对话之间（酒馆 antagonize）

    @SerialName("top_of_chat")
    TOP_OF_CHAT,            // 对话最开头（第一条用户消息之前）

    @SerialName("bottom_of_chat")
    BOTTOM_OF_CHAT,         // 最新消息之前（当前用户输入之前）

    @SerialName("after_dialog")
    AFTER_DIALOG,           // 最近一条 AI 回复之后（酒馆 after_dialog）

    @SerialName("at_depth")
    AT_DEPTH,               // 在指定深度位置插入（从最新消息往前数）

    @SerialName("author_note")
    AUTHOR_NOTE,            // Author's Note 位置（由用户设置决定，不固定）

    @SerialName("em_top")
    EM_TOP,                 // 官方 EMTop：示例消息之前

    @SerialName("em_bottom")
    EM_BOTTOM,              // 官方 EMBottom：示例消息之后
}

/**
 * 提示词注入
 *
 * - ModeInjection: 基于模式开关的注入（如学习模式）
 * - RegexInjection: 基于正则匹配的注入（Lorebook）
 */
@Serializable
sealed class PromptInjection {
    abstract val id: Uuid
    abstract val name: String
    abstract val enabled: Boolean
    abstract val priority: Int
    abstract val position: InjectionPosition
    abstract val content: String
    abstract val injectDepth: Int  // 当 position 为 AT_DEPTH 时使用，表示从最新消息往前数的位置
    abstract val role: MessageRole  // 注入角色：USER 或 ASSISTANT

    /**
     * 模式注入 - 基于开关状态触发
     */
    @Serializable
    @SerialName("mode")
    data class ModeInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 0,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.USER,
    ) : PromptInjection()

    /**
     * 正则注入 - 基于内容匹配触发（世界书）
     */
    @Serializable
    @SerialName("regex")
    data class RegexInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 0,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.USER,
        val keywords: List<String> = emptyList(),       // 主触发关键词
        val secondaryKeys: List<String> = emptyList(), // 二级触发关键词
        val useRegex: Boolean = false,                  // 是否使用正则匹配
        val caseSensitive: Boolean = false,             // 大小写敏感
        val matchWholeWords: Boolean = false,           // 整词匹配（酒馆 match_whole_words）
        val scanDepth: Int = 1000,                      // 扫描最近N条消息（酒馆默认1000）
        val constantActive: Boolean = false,            // 常驻激活（无需匹配）
        val selective: Boolean = false,                 // 是否启用二级关键词逻辑
        val selectiveLogic: SelectiveLogic = SelectiveLogic.AND_ANY, // 触发逻辑
        val group: String = "",                         // 分组标签（同组条目互斥时只激活一个）
        val groupWeight: Int = 100,                     // 同组权重（随机选择时使用）
        val groupOverride: Boolean = false,             // 是否覆盖同组其他条目
        val probability: Int = 100,                     // 触发概率 0-100
        val sticky: Int = 0,                         // 激活后持续保留N轮（0=不粘）
        val cooldown: Int = 0,                          // 冷却轮数（0=无冷却）
        val delay: Int = 0,                             // 延迟激活轮数（0=立即，酒馆 extensions.delay）
        val excludeRecursion: Boolean = false,          // 内容不参与递归扫描（酒馆 extensions.exclude_recursion）
        val preventRecursion: Boolean = false,          // 禁止被递归触发（酒馆 extensions.prevent_recursion）
        // 官方 extensions.delay_until_recursion：true 或数字层级（1/2/3…）；0=关闭
        @Serializable(with = DelayUntilRecursionSerializer::class)
        val delayUntilRecursion: Int = 0,
        val useProbability: Boolean = true,              // 是否启用概率过滤（false=忽略probability直接触发）
        val inclusionGroup: String = "",                 // 本地遗留字段（官方无此字段；官方分组用顶层 group 逗号分隔）
        val useGroupScoring: Boolean = false,            // 酒馆 extensions.use_group_scoring（按匹配关键词数选组胜者）
        val groupPriority: Boolean = false,              // 本地遗留字段（官方无此字段；官方优先用 group_override）
        val automationId: String = "",                   // 酒馆 extensions.automation_id（本App暂不执行，仅保留）
        val displayIndex: Int = 0,                       // 酒馆 display_index（展示顺序）
        val displayPosition: Int = 0,                    // 酒馆 display_position（展示位置）
        val triggers: List<String> = emptyList(),        // 酒馆 triggers（生成类型过滤，本App暂不执行）
        val matchPersonaDescription: Boolean = false,    // 酒馆 extensions.match_persona_description
        val matchCharacterDescription: Boolean = false,  // 酒馆 extensions.match_character_description
        val matchCharacterPersonality: Boolean = false,  // 酒馆 extensions.match_character_personality
        val matchCharacterDepthPrompt: Boolean = false,  // 酒馆 extensions.match_character_depth_prompt
        val matchScenario: Boolean = false,              // 酒馆 extensions.match_scenario
        val matchCreatorNotes: Boolean = false,          // 酒馆 extensions.match_creator_notes
        val ignoreBudget: Boolean = false,               // 酒馆 extensions.ignore_budget
    ) : PromptInjection()
}

/**
 * selectiveLogic 触发逻辑
 */
@Serializable
enum class SelectiveLogic {
    @SerialName("and_any")
    AND_ANY,      // 官方 0：主关键词命中 + 任一二级关键词命中（默认）
    @SerialName("and_all")
    AND_ALL,      // 官方 3：主关键词命中 + 全部二级关键词命中
    @SerialName("or_any")
    OR_ANY,       // 本地遗留扩展（官方无此模式）
    @SerialName("not_any")
    NOT_ANY,      // 官方 2：主关键词命中 + 没有任何二级关键词命中
    @SerialName("not_all")
    NOT_ALL,      // 官方 1：主关键词命中 + 二级关键词非全部命中
}

/**
 * 官方 extensions.delay_until_recursion 序列化：接受 true/false、数字层级（1/2/3…）与字符串，
 * 统一存成 Int（0=关闭，N=第 N 级）。
 */
object DelayUntilRecursionSerializer : kotlinx.serialization.KSerializer<Int> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
        "DelayUntilRecursion",
        kotlinx.serialization.descriptors.PrimitiveKind.INT,
    )

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Int) {
        encoder.encodeInt(value)
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Int {
        val element = (decoder as? kotlinx.serialization.json.JsonDecoder)
            ?.decodeJsonElement()
            ?: return decoder.decodeInt()
        val content = element.jsonPrimitive.contentOrNull ?: return 0
        content.toBooleanStrictOrNull()?.let { return if (it) 1 else 0 }
        return content.toIntOrNull() ?: 0
    }
}

/**
 * Lorebook - 组织管理多个 RegexInjection
 */
@Serializable
data class Lorebook(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val entries: List<PromptInjection.RegexInjection> = emptyList(),
)

/**
 * 检查 RegexInjection 是否被触发
 *
 * @param context 要扫描的上下文文本
 * @return 是否触发
 */
fun PromptInjection.RegexInjection.isTriggered(
    context: String,
    activeSticky: Boolean = false,
    rollProbability: Boolean = true,
): Boolean {
    if (!enabled) return false

    // 粘性条目在激活后持续生效
    if (sticky > 0 && activeSticky) return true

    // 概率过滤（useProbability=false 时跳过；官方对 constant 条目同样掷概率）
    if (rollProbability) {
        val effectiveProb = if (useProbability) probability else 100
        if (effectiveProb < 100 && kotlin.random.Random.nextInt(100) >= effectiveProb) return false
    }

    // 官方：constant 条目无需关键词即可激活（概率已参与）
    if (constantActive) return true

    // 没有关键词 → 不触发
    if (keywords.isEmpty() && secondaryKeys.isEmpty()) return false

    if (selective) {
        // 官方 checkWorldInfo：必须先命中任意主关键词，否则直接跳过
        // （NOT_ANY / NOT_ALL 也必须以主关键词命中为前提，不能因“都没匹配”而触发）
        if (keywords.isEmpty()) return false
        val anyPrimary = keywords.any { keyMatches(it, context, useRegex, caseSensitive, matchWholeWords) }
        if (!anyPrimary) return false

        // 官方：无二级关键词时，命中主关键词即激活（selectiveLogic 不再参与）
        if (secondaryKeys.isEmpty()) return true

        val anySecondary = secondaryKeys.any { keyMatches(it, context, useRegex, caseSensitive, matchWholeWords) }
        val allSecondary = secondaryKeys.all { keyMatches(it, context, useRegex, caseSensitive, matchWholeWords) }

        return when (selectiveLogic) {
            // 官方 AND_ANY：主关键词命中 + 任一二级关键词命中
            SelectiveLogic.AND_ANY -> anySecondary
            // 官方 AND_ALL：主关键词命中 + 全部二级关键词命中
            SelectiveLogic.AND_ALL -> allSecondary
            // 官方 NOT_ANY：主关键词命中 + 没有任何二级关键词命中
            SelectiveLogic.NOT_ANY -> !anySecondary
            // 官方 NOT_ALL：主关键词命中 + 二级关键词非全部命中
            SelectiveLogic.NOT_ALL -> !allSecondary
            // 本地遗留扩展（官方无此模式）：主关键词已命中即可
            SelectiveLogic.OR_ANY -> true
        }
    } else {
        // 非选择性模式：只检查主关键词
        if (keywords.isEmpty()) return false
        return keywords.any { keyMatches(it, context, useRegex, caseSensitive, matchWholeWords) }
    }
}

/** 酒馆 use_group_scoring 用：统计匹配到的关键词数（主关键词1条=1分，二级按逻辑计分） */
fun PromptInjection.RegexInjection.matchedKeyScore(context: String): Int {
    if (!enabled) return 0
    val primaryMatches = keywords.count { keyMatches(it, context, useRegex, caseSensitive, matchWholeWords) }
    if (!selective) return primaryMatches
    val secondaryMatches = secondaryKeys.count { keyMatches(it, context, useRegex, caseSensitive, matchWholeWords) }
    return when (selectiveLogic) {
        SelectiveLogic.AND_ANY -> primaryMatches + secondaryMatches
        SelectiveLogic.AND_ALL -> if (secondaryKeys.isEmpty() || secondaryMatches == secondaryKeys.size) {
            primaryMatches + secondaryMatches
        } else {
            primaryMatches
        }
        SelectiveLogic.NOT_ANY, SelectiveLogic.NOT_ALL -> primaryMatches
        SelectiveLogic.OR_ANY -> primaryMatches + secondaryMatches
    }
}

/** 单个关键词匹配 */
private fun keyMatches(
    key: String,
    context: String,
    useRegex: Boolean,
    caseSensitive: Boolean,
    matchWholeWords: Boolean,
): Boolean {
    // 官方 matchKeys：先按 parseRegexFromString 识别 /pattern/flags 形式（覆盖所有其他开关）
    val officialRegex = parseRegexFromString(key)
    if (officialRegex != null) {
        return officialRegex.containsMatchIn(context)
    }

    return if (useRegex) {
        try {
            val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            val pattern = if (matchWholeWords) "\\b(?:${key.trim().trim('^', '$')})\\b" else key
            Regex(pattern, options).containsMatchIn(context)
        } catch (_: Exception) { false }
    } else {
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val pattern = if (matchWholeWords) "\\b${Regex.escape(key)}\\b" else Regex.escape(key)
        try {
            Regex(pattern, options).containsMatchIn(context)
        } catch (_: Exception) { false }
    }
}

/**
 * 官方 parseRegexFromString 的 Kotlin 版本：
 * 形如 /pattern/flags（flags ∈ gimsuy）且模式内没有未转义斜杠时按正则处理。
 * Kotlin Regex 无 g/u/y 标志，忽略（containsMatchIn 天然全局；u=unicode 默认；y=sticky 不适用）。
 */
private fun parseRegexFromString(input: String): Regex? {
    val match = Regex("^/([\\w\\W]+?)/([gimsuy]*)$").find(input) ?: return null
    val pattern = match.groupValues[1]
    val flags = match.groupValues[2]
    // 模式里出现未转义斜杠 → 官方视为非法正则，按普通文本处理
    if (Regex("(^|[^\\\\])/").containsMatchIn(pattern)) return null
    val unescaped = pattern.replace("\\/", "/")
    return try {
        val options = buildSet {
            if ('i' in flags) add(RegexOption.IGNORE_CASE)
            if ('m' in flags) add(RegexOption.MULTILINE)
            if ('s' in flags) add(RegexOption.DOT_MATCHES_ALL)
        }
        Regex(unescaped, options)
    } catch (_: Exception) {
        null
    }
}

/**
 * 从消息列表中提取用于匹配的上下文文本
 *
 * @param messages 消息列表
 * @param scanDepth 扫描深度（最近N条消息）
 * @return 拼接的文本内容
 */
fun extractContextForMatching(
    messages: List<UIMessage>,
    scanDepth: Int
): String {
    return messages
        .takeLast(scanDepth)
        .joinToString("\n") { it.toText() }
}

/**
 * 获取所有被触发的注入，按优先级排序
 *
 * @param injections 所有注入规则
 * @param context 上下文文本
 * @return 被触发的注入列表，按优先级降序排列
 */
fun getTriggeredInjections(
    injections: List<PromptInjection.RegexInjection>,
    context: String
): List<PromptInjection.RegexInjection> {
    return injections
        .filter { it.isTriggered(context) }
        .sortedByDescending { it.priority }
}

/**
 * 默认上下文模板 — ADF 风格宏
 * 可用宏: {{system}}, {{description}}, {{personality}}, {{scenario}},
 *          {{mesExamples}}, {{char}}, {{user}}, {{persona}}
 * 结构与官方 SillyTavern 默认 Story String 对齐（mes_example 独立作为示例消息注入）
 */
val DEFAULT_CONTEXT_TEMPLATE = """
{{system}}

{{description}}

{{char}}'s personality: {{personality}}

Scenario: {{scenario}}

{{persona}}
""".trim()

/**
 * 根据上下文模板组装 system prompt
 * 展开 ADF 风格宏: {{char}}, {{user}}, {{description}}, {{personality}},
 *   {{scenario}}, {{mesExamples}}, {{system}}
 */
fun Assistant.assembleContext(
    userName: String,
    personaDesc: String,
): String {
    val template = this.contextTemplate.ifBlank { DEFAULT_CONTEXT_TEMPLATE }
    val tav = this.tavernData
    return template
        .replace("{{char}}", this.name)
        .replace("{{user}}", userName)
        .replace("{{persona}}", personaDesc)
        .replace("{{system}}", tav?.systemPrompt ?: this.systemPrompt.take(200))
        .replace("{{description}}", tav?.description ?: "")
        .replace("{{personality}}", tav?.personality ?: "")
        .replace("{{scenario}}", tav?.scenario ?: "")
        .replace("{{mesExamples}}", tav?.mesExample ?: "")
        .replace("{{original}}", this.systemPrompt)
}

/**
 * 官方 Chat Completion 结构拆分 — 主提示消息（对应官方 main prompt / system_prompt）
 */
fun Assistant.assembleMainPrompt(): String {
    return tavernData?.systemPrompt?.takeIf { it.isNotBlank() } ?: systemPrompt
}

/**
 * 官方 Chat Completion 结构拆分 — 角色卡字段消息（对应官方 persona/description/personality/scenario 独立消息）。
 * 内部顺序对齐官方 OpenAI 模式：人设(按位置设置) → 描述 → 性格 → 场景
 */
fun Assistant.assembleCharacterCardBlock(
): String {
    val tav = this.tavernData ?: return ""
    val description = tav.description.takeIf { it.isNotBlank() }
    val personality = tav.personality.takeIf { it.isNotBlank() }
    val scenario = tav.scenario.takeIf { it.isNotBlank() }
    if (description == null && personality == null && scenario == null) return ""

    return buildString {
        description?.let {
            if (isNotEmpty()) appendLine()
            append(it)
        }
        personality?.let {
            if (isNotEmpty()) appendLine()
            append("${this@assembleCharacterCardBlock.name}'s personality: $it")
        }
        scenario?.let {
            if (isNotEmpty()) appendLine()
            append("Scenario: $it")
        }
    }
}

/**
 * 解析角色卡 mes_example 为示例消息（对齐官方 parseMesExamples + parseExampleIntoIndividual）：
 * - 按 <START> 分块
 * - 块内按 "{{user}}:" / "{{char}}:" 前缀行切分 user/assistant 消息
 * - 官方跳过块首的 "This is how X should talk" 说明行
 */
fun Assistant.buildExampleMessages(userName: String): List<UIMessage> {
    val raw = this.tavernData?.mesExample ?: return emptyList()
    if (raw.isBlank() || raw.trim().equals("<START>", ignoreCase = true)) return emptyList()

    val text = if (!raw.trimStart().startsWith("<START>", ignoreCase = true)) {
        "<START>\n" + raw.trim()
    } else {
        raw
    }
    val blocks = Regex("<START>", RegexOption.IGNORE_CASE).split(text).drop(1)

    return blocks.flatMap { block ->
        parseExampleBlock(block, charName = this.name, userName = userName)
    }
}

private fun parseExampleBlock(block: String, charName: String, userName: String): List<UIMessage> {
    val lines = block.trim().lines()
    // 官方跳过第一行（如 "This is how {bot name} should talk"）
    val startIndex = if (lines.firstOrNull()?.contains("should talk", ignoreCase = true) == true) 1 else 0

    val result = mutableListOf<UIMessage>()
    val currentLines = mutableListOf<String>()
    var currentRole: MessageRole? = null

    fun flush() {
        val role = currentRole ?: return
        val content = currentLines.joinToString("\n").trim()
        if (content.isNotBlank()) {
            val isUser = role == MessageRole.USER
            result.add(
                UIMessage(
                    role = if (isUser) MessageRole.USER else MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(content)),
                    annotations = listOf(UIMessageAnnotation.ExampleMessage),
                )
            )
        }
        currentLines.clear()
    }

    for (i in startIndex until lines.size) {
        val line = lines[i]
        val isUserLine = line.startsWith("$userName:")
        val isCharLine = line.startsWith("$charName:")
        if (isUserLine || isCharLine) {
            flush()
            currentRole = if (isUserLine) MessageRole.USER else MessageRole.ASSISTANT
            currentLines.add(line.substringAfter(':'))
        } else {
            currentLines.add(line)
        }
    }
    flush()
    return result
}
