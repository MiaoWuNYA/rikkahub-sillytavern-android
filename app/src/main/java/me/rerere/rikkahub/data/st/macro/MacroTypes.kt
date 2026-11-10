package me.rerere.rikkahub.data.st.macro

import java.time.Clock
import java.time.ZoneId

/**
 * ST 宏系统移植 —— 基础类型定义。
 *
 * 移植自 SillyTavern 1.18.0 `public/scripts/macros/`：
 * - [MacroFlags] ← engine/MacroFlags.js
 * - [MacroDefinition] / [MacroExecutionContext] ← engine/MacroRegistry.js
 * - [MacroEnv] ← engine/MacroEnv.types.js / engine/MacroEnvBuilder.js
 *
 * 命名与语义尽量与 ST 保持一致，便于后续对照升级。
 */

// ==================== Flags ====================

/**
 * 宏执行 flags（位于 `{{` 与标识符之间）。
 *
 * 已实现：
 * - `/` 闭合块标志：`{{setvar::x}}...{{/setvar}}`，内容成为最后一个参数
 * - `#` 保留空白：scoped 内容不做自动 trim（兼容 legacy `{{#if}}` 写法）
 *
 * 已解析但暂未实现（与 ST 状态一致）：`!`（immediate）、`?`（delayed）、
 * `~`（re-evaluate）、`>`（filter，SV 中标注 planned）。
 */
data class MacroFlags(
    val immediate: Boolean = false,
    val delayed: Boolean = false,
    val reevaluate: Boolean = false,
    val filter: Boolean = false,
    val closingBlock: Boolean = false,
    val preserveWhitespace: Boolean = false,
    /** 原始 flag 符号序列（按出现顺序） */
    val raw: List<String> = emptyList(),
) {
    companion object {
        val EMPTY = MacroFlags()

        /** 全部合法 flag 符号（与 ST ValidFlagSymbols 对齐） */
        val VALID_SYMBOLS = setOf("!", "?", "~", ">", "/", "#")

        /** 解析自 MacroFlags.js parseFlags() */
        fun parse(symbols: List<String>): MacroFlags {
            var immediate = false
            var delayed = false
            var reevaluate = false
            var filter = false
            var closingBlock = false
            var preserveWhitespace = false
            for (symbol in symbols) {
                when (symbol) {
                    "!" -> immediate = true
                    "?" -> delayed = true
                    "~" -> reevaluate = true
                    ">" -> filter = true
                    "/" -> closingBlock = true
                    "#" -> preserveWhitespace = true
                }
            }
            return MacroFlags(
                immediate = immediate,
                delayed = delayed,
                reevaluate = reevaluate,
                filter = filter,
                closingBlock = closingBlock,
                preserveWhitespace = preserveWhitespace,
                raw = symbols,
            )
        }
    }
}

// ==================== 宏定义 ====================

/**
 * 无名参数定义。对应 ST MacroUnnamedArgDef。
 *
 * @property name 参数名（校验失败消息中使用）
 * @property optional 是否可选（可选参数必须是后缀；minArgs = 首个 optional 的 index）
 * @property types 可接受的类型（ST 默认 ['string']；常用 "string"/"integer"/"number"/"boolean"）
 */
class MacroArgDef(
    val name: String,
    val optional: Boolean = false,
    val types: List<String> = listOf("string"),
)

/**
 * 宏定义。对应 ST MacroRegistry 的 MacroDefinition。
 *
 * @property name 主名称
 * @property minArgs 最少参数数（不含可选参数）；由 argDefs 推导：首个 optional 的 index，
 *                   无 optional 时等于 [maxArgs]
 * @property maxArgs 最多匿名参数数；超出部分对 [list] 宏进入 list
 * @property list 是否为列表型宏（`{{random::a::b::c}}` 的 a/b/c 均进入 list；
 *                列表型宏不接受 scoped 内容）
 * @property strictArgs 参数校验失败时是否抛错（throw → 宏保留原文；false = 仅警告并继续）。
 *                      注意：ST 默认值为 true（见 MacroDefinitionOptions），仅 `//` 等少数宏显式 false。
 * @property delayArgResolution 是否延迟参数解析（`if` 等宏选择分支后再解析）
 * @property aliases 别名（指向同一定义）
 * @property argDefs 无名参数定义（类型校验用；为空表示未文档化参数）
 * @property handler 处理器
 */
class MacroDefinition(
    val name: String,
    val minArgs: Int = 0,
    val maxArgs: Int = 0,
    val list: Boolean = false,
    val strictArgs: Boolean = true,
    val delayArgResolution: Boolean = false,
    val aliases: List<String> = emptyList(),
    val argDefs: List<MacroArgDef> = emptyList(),
    val handler: (MacroExecutionContext) -> String,
) {
    /** 复制为新名称（dynamic macro 覆盖注册名时使用） */
    fun withName(newName: String): MacroDefinition = MacroDefinition(
        name = newName,
        minArgs = minArgs,
        maxArgs = maxArgs,
        list = list,
        strictArgs = strictArgs,
        delayArgResolution = delayArgResolution,
        aliases = aliases,
        argDefs = argDefs,
        handler = handler,
    )
}

// ==================== 动态宏 ====================

/**
 * 动态宏（对应 ST env.dynamicMacros 的三形态）：
 * - [Value]：字符串值，不可带参数（arity 校验按 maxArgs=0 处理）
 * - [Handler]：处理函数，不可带参数
 * - [Definition]：完整定义（可配置参数、list、strictArgs 等）
 *
 * 键为小写形式（与 ST 一致，查找时按小写归一化）。
 */
sealed interface DynamicMacro {
    data class Value(val value: String) : DynamicMacro
    data class Handler(val handler: (MacroExecutionContext) -> String) : DynamicMacro
    data class Definition(val definition: MacroDefinition) : DynamicMacro
}

// ==================== 执行上下文 ====================

/**
 * 宏执行上下文。对应 ST executeMacro 构建的 MacroExecutionContext。
 *
 * @property args 全部参数（已按宏设置完成嵌套解析；delayArgResolution 时为原文）
 * @property unnamedArgs 前 [MacroDefinition.maxArgs] 个参数（缺失位为 null）
 * @property list 列表宏的剩余参数
 * @property flags 执行 flags
 * @property isScoped 是否以 scoped 形式调用（内容已作为最后一个参数追加）
 * @property raw 宏括号内未经重建的原文（rawInner）
 * @property rawOriginal 含括号的原文（rawWithBraces）
 * @property rawArgs 各参数在原文中的原始文本
 * @property globalOffset 宏在原文档中的全局偏移（用于 pick 等定位种子）
 * @property resolve 递归解析嵌套内容的函数（等价 ST 的 resolve）
 * @property normalize 结果归一化（null→""，集合→JSON 等）
 * @property trimContent scoped 内容 trim（含 dedent）
 * @property warn 运行时警告记录
 */
class MacroExecutionContext(
    val name: String,
    val args: List<String?>,
    val unnamedArgs: List<String?>,
    val list: List<String>,
    val flags: MacroFlags,
    val isScoped: Boolean,
    val raw: String,
    val rawOriginal: String,
    val rawArgs: List<String>,
    val globalOffset: Int,
    val env: MacroEnv,
    val resolve: (String) -> String,
    val normalize: (Any?) -> String,
    val trimContent: (String) -> String,
    val warn: (String) -> Unit,
)

// ==================== 环境 ====================

/** 聊天消息的轻量视图（供 chat.* 宏使用；对应 ST `chat` 数组的元素） */
data class MacroChatMessage(
    val text: String,
    val isUser: Boolean,
    val isSystem: Boolean = false,
    /** 1-based 当前 swipe 序号（无 swipe 时为 null） */
    val currentSwipeId: Int? = null,
    val swipeCount: Int? = null,
    /** 消息时间（用于 idleDuration） */
    val sentAt: java.time.Instant? = null,
)

/**
 * 宏评估环境。对应 ST 的 MacroEnv。
 *
 * 注意：ST 中 env 字段在评估期间被冻结（safeEnv），变量/动态宏等内容为引用共享；
 * Kotlin 侧保持同样的「只读约定」，由构建方保证不在评估期间修改结构字段。
 */
class MacroEnv(
    /** 参与者名字 */
    val names: Names = Names(),
    /** 角色卡字段 */
    val character: CharacterInfo = CharacterInfo(),
    /** 系统级信息 */
    val system: SystemInfo = SystemInfo(),
    /** 变量存储 */
    val variables: VariableStores = VariableStores(),
    /** 聊天记录（chat.* / idleDuration 等宏使用） */
    val chat: List<MacroChatMessage> = emptyList(),
    /** 本次评估的顶层原文（pick 等位置种子使用；对应 ST env.content） */
    var content: String = "",
    /** 顶层原文的哈希（对应 ST env.contentHash；为 0 时由使用方惰性计算） */
    var contentHash: Long = 0,
    /** 动态宏（本次评估可见的临时宏；键小写） */
    val dynamicMacros: Map<String, DynamicMacro> = emptyMap(),
    /**
     * 世界书 outlet 快照（官方 extension_prompts[CUSTOM_WI_OUTLET]）。
     * 由世界书扫描发布；`{{outlet::name}}` 宏按 name 读取（不存在时为空串）。
     */
    val outlets: Map<String, String> = emptyMap(),
    /** 时间源（测试可注入） */
    val clock: Clock = Clock.systemDefaultZone(),
    /** 时区（格式化用） */
    val zone: ZoneId = ZoneId.systemDefault(),
    /** 本地化区域（date/weekday/time 等格式化用小写相关；对应 ST 的 moment locale） */
    val locale: java.util.Locale = java.util.Locale.getDefault(),
    /** 运行时警告回调（对应 ST 的 warn；用于 arity/类型校验失败时的非致命告警） */
    val warn: (String) -> Unit = {},
    /** original 宏的一次性取值函数（对应 ST env.functions.original） */
    val originalFn: (() -> String)? = null,
    /** 结果后处理（对应 ST env.functions.postProcess；默认恒等） */
    val postProcess: (String) -> String = { it },
    /** Instruct 模板快照（instruct* 系宏与 exampleSeparator/chatStart 的数据源） */
    val instruct: InstructInfo = InstructInfo(),
    /** 导演备注快照（authorsNote / charAuthorsNote / defaultAuthorsNote 的数据源） */
    val authorNotes: AuthorNotes = AuthorNotes(),
) {
    /** original 单次消费状态（对齐 ST env.functions.original 的 originalSubstituted 闭包变量） */
    private var originalConsumed = false

    /**
     * 取 original 值：首次返回原值并消费，之后返回空串；
     * 未配置供给函数时抛错（引擎捕获后保留原文，对齐 ST 的报错兜底）。
     */
    fun takeOriginal(): String {
        val fn = originalFn ?: error("original function is not available")
        if (originalConsumed) return ""
        originalConsumed = true
        return fn()
    }

    class Names(
        var user: String = "",
        var char: String = "",
        var group: String = "",
        var groupNotMuted: String = "",
        var notChar: String = "",
    )

    class CharacterInfo(
        var charPrompt: String = "",
        var charInstruction: String = "",
        var description: String = "",
        var personality: String = "",
        var scenario: String = "",
        var persona: String = "",
        var mesExamplesRaw: String = "",
        var version: String = "",
        var charDepthPrompt: String = "",
        var creatorNotes: String = "",
        var firstMessage: String = "",
        var alternateGreetings: List<String> = emptyList(),
    )

    class SystemInfo(
        var model: String = "",
        /** 会话 ID 的哈希（pick 种子用；无会话时为 null） */
        var chatIdHash: Long? = null,
        /** 会话级 pick 重掷种子（对应 chat_metadata.pick_reroll_seed） */
        var pickRerollSeed: String? = null,
        /** 生成类型（normal/regenerate/swipe/continue 等；对应 lastGenerationType） */
        var generationType: String = "",
        /** 已启用的 skill 名单（hasExtension 检查用） */
        var enabledExtensions: Set<String> = emptySet(),
        /** 上下文 token 上限（maxContext 宏；null 表示未知） */
        var maxContextTokens: Int? = null,
        /** 响应 token 上限（maxResponse 宏；null 表示未知） */
        var maxResponseTokens: Int? = null,
        /** 当前上下文首条消息 ID（firstIncludedMessageId 宏；对应 chat_metadata.lastInContextMessageId） */
        var lastInContextMessageId: Int? = null,
        /** 视图首条消息 ID（firstDisplayedMessageId 宏；null 时默认视 chat 非空为 0） */
        var firstDisplayedMessageId: Int? = null,
        /** sysprompt 开关（systemPrompt 宏；宿主口径 = 助手系统提示词非空） */
        var syspromptEnabled: Boolean = false,
        /** sysprompt 内容（systemPrompt 宏的兜底取值） */
        var syspromptContent: String = "",
        /** 当前输入框文本（{{input}} 宏数据源） */
        var input: String = "",
        /** {{banned::word}} 宏收集的禁用词列表 */
        val bannedWords: MutableList<String> = mutableListOf(),
    )

    /**
     * 导演备注快照（对齐 ST `public/scripts/authors-note.js:604-614` 的宏注册）。
     *
     * 官方数据源：
     * - `authorsNote`        → `chat_metadata[metadata_keys.prompt]`（**按对话**存的作者备注）；
     * - `charAuthorsNote`    → `extension_settings.note.chara` 里按角色卡名匹配的 prompt；
     * - `defaultAuthorsNote` → `extension_settings.note.default`（全局默认模板）；
     *
     * rikkaST 现状（PORT NOTE，语义差异）：导演备注只有全局单字段 `Settings.authorNote`，
     * 没有 chat_metadata / 角色卡绑定 / 默认模板三层结构，因此宿主把：
     * - `current` / `default` 都取自 `Settings.authorNote`；
     * - `character` 恒为空串（无数据源，本批不新增字段）。
     * 与官方“按对话备注”的差异已在此登记，后续若做按对话备注需分别回填三个字段。
     */
    class AuthorNotes(
        /** `{{authorsNote}}`：当前对话的导演备注文本 */
        var current: String = "",
        /** `{{charAuthorsNote}}`：按角色卡绑定的导演备注（rikkaST 无数据源 → 空串） */
        var character: String = "",
        /** `{{defaultAuthorsNote}}`：默认模板（rikkaST 无模板概念 → 等同全局备注） */
        var default: String = "",
    )

    /** Instruct 模板快照（对齐 ST power_user.instruct / context 的宏可见子集） */
    class InstructInfo(
        var enabled: Boolean = false,
        var storyStringPrefix: String = "",
        var storyStringSuffix: String = "",
        var userPrefix: String = "",
        var userSuffix: String = "",
        var assistantPrefix: String = "",
        var assistantSuffix: String = "",
        var systemPrefix: String = "",
        var systemSuffix: String = "",
        var firstAssistantPrefix: String = "",
        var lastAssistantPrefix: String = "",
        /** 宿主未覆盖的 ST 模板字段，恒为空串 */
        var stopSequence: String = "",
        var userFiller: String = "",
        var systemInstructionPrefix: String = "",
        var firstUserPrefix: String = "",
        var lastUserPrefix: String = "",
        /** 上下文模板宏（ST defaultExampleSeparator） */
        var exampleSeparator: String = "***",
        /** 上下文模板宏（ST defaultChatStart） */
        var chatStart: String = "***",
    )
}

/** 变量存储容器 */
class VariableStores(
    val local: MacroVariableStore = InMemoryMacroVariableStore(),
    val global: MacroVariableStore = InMemoryMacroVariableStore(),
)

/**
 * 宏变量存储接口（对应 ST context.variables.local / .global 的能力面）。
 *
 * 返回值约定：get() 对不存在的变量返回 null（ST 中为 undefined）。
 */
interface MacroVariableStore {
    fun get(name: String): String?
    fun has(name: String): Boolean
    fun set(name: String, value: String)

    /** 删除变量（对应 flushvar / flushglobalvar 语义） */
    fun del(name: String)

    /** 自增 1 并返回新值（不存在则创建，从 1 开始） */
    fun inc(name: String): String

    /** 自减 1 并返回新值 */
    fun dec(name: String): String

    /** 数值相加或字符串拼接（对齐 ST add 语义） */
    fun add(name: String, value: String)

    /** 键级读（varkey 系宏；对齐 ST variables.js get 的 index 分支） */
    fun getAtKey(name: String, key: String): String?

    /** 键级写（varkey 系宏；对齐 ST variables.js set 的 index 分支） */
    fun setAtKey(name: String, key: String, value: String)
}
