package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ImageGenSize
import me.rerere.asr.ASRProviderSetting
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.TITLE_PROMPT_REVISION
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.AuthorNoteEntry
import me.rerere.rikkahub.data.model.AuthorNotePosition
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.GroupChat
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.Persona
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.RegexGroup
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.model.ThemeIconSet
import me.rerere.rikkahub.data.model.ThemeMarkdownStyle
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val networkSetting: NetworkSetting = NetworkSetting(),
    val huadengSettings: HuaDengSettings = HuaDengSettings(),
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val fastModelId: Uuid = Uuid.random(),
    // 标题总结模型：null = 跟随快速模型
    val titleModelId: Uuid? = null,
    val fastModelReasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    // 标题推理等级。旧版标题请求直接复用 fastModelReasoningLeve
    // 就会花一二百个 token 在"数字数"上；默认 OFF，让标题请求不走思考链。
    val titleReasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
    // 记忆整理用的模型，没设置时用快速模型
    val memoryModelId: Uuid? = null,
    val memoryModelReasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val imageGenerationModelId: Uuid = Uuid.random(),
    // 生图页的尺寸与思考强度。放在全局设置里而不是页面内 state：用户调过一次就该
    // 每次进页面都被重置回 auto 很烦。默认 auto = 不显式指定
    val imageGenerationSize: String = ImageGenSize.AUTO.value,
    val imageGenerationReasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val enableAutoTitle: Boolean = true,
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    // 翻译页的目标语言，存 BCP 47 语言标签
    val translateTargetLanguage: String = "zh-CN",
    val enableSuggestion: Boolean = true,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val compressModelId: Uuid = Uuid.random(),
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    val enableWebSearch: Boolean = true, // 全局网络搜索开关
    val fileRetentionDays: Int = 0, // 聊天附件与生成图片保留天数（0=不清理，移植自 Rikkahub-Revised 的按日文件清理）
    // RemoteFileStore 上传临时素材用的桶
    val uploadS3Config: S3Config = S3Config(),
    val mediaGenerationProviders: List<MediaGenerationProviderSetting> = emptyList(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val defaultTTSPlaybackSpeed: Float = 1.0f,
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val selectedASRProviderId: Uuid? = null,
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val lorebooks: List<Lorebook> = emptyList(),
    // 全局消息正则分组（页面上的文件夹）：酒馆预设导入的脚本等，对所有角色卡生效
    val regexGroups: List<RegexGroup> = emptyList(),
    // 官方 world_info_budget 是上下文 token 的百分比
    // 本地默认改成 100（不裁剪）：官方那个 25% 是按短条目英文书设计的，
    // 中文角色卡的世界书动辄几万 token——一张卡 22 条里 11 条 cons
    // 3.7 万字符，25% 预算下 <twitter>/<Status>/生成格式 
    // 而「生成格式」正是教模型写 <mainbody>/<phone> 骨架的条目
    // 模型不再输出标签 → 卡内 JS 拿不到数据 → 显示「您未打开 choice 
    // 或只剩一个 HTML 空壳。用户看到的是"卡片坏了"，根因却在这个百分比上。
    val worldInfoBudget: Int = 100,
    val worldInfoBudgetCap: Int = 0,                // 官方 world_info_budget_cap：预算绝对 token 上限（0=不限制，官方默认 0）
    val worldInfoMinActivations: Int = 0,           // 世界书最少激活数（0=关闭，酒馆 min_activations）
    val worldInfoMinActivationsDepthMax: Int = 0,   // 官方 world_info_min_activations_depth_max：min_activations 最大扫描深度（0=不限制）
    val worldInfoRecursive: Boolean = false,        // 递归扫描（酒馆 world_info_recursive）
    val worldInfoMaxRecursionSteps: Int = 0,            // 官方 world_info_max_recursion_steps：总扫描轮数上限（0=不限制，官方默认0）
    val worldInfoDepth: Int = 2,                    // 官方 world_info_depth：条目未设置扫描深度时的默认值（官方默认2）
    val worldInfoCharacterStrategy: Int = 1,        // 官方 world_info_character_strategy：0=均匀 1=角色卡优先 2=全局优先
    val worldInfoOverflowAlert: Boolean = false,    // 官方 world_info_overflow_alert：预算溢出时提示
    val worldInfoUseGroupScoring: Boolean = false,  // 官方 world_info_use_group_scoring：组评分全局默认
    val vectorStorageEnabled: Boolean = false,      // 向量检索（Vector Storage）总开关
    val vectorStorageModelId: Uuid? = null,         // 嵌入模型（仅 EMBEDDING 类型模型可选）
    val vectorStorageThreshold: Float = 0.25f,      // 相似度阈值（官方 Vector Storage 默认约 0.25）
    val vectorStorageScanDepth: Int = 2,            // 用最近 N 条非系统消息作为检索查询
    val quickMessages: List<QuickMessage> = emptyList(),
    val personas: List<Persona> = DEFAULT_PERSONAS,
    val activePersonaId: Uuid? = null,             // 当前激活的 Persona
    val authorNote: String = "",                    // Author's Note 内容
    val authorNoteEnabled: Boolean = false,         // Author's Note 总开关
    val authorNotePosition: AuthorNotePosition = AuthorNotePosition.IN_CHAT,
    val authorNoteDepth: Int = 4,                   // Author's Note 插入深度
    val authorNoteRole: MessageRole = MessageRole.SYSTEM, // 注入角色（官方默认 SYSTEM）
    val authorNoteInterval: Int = 1,                // 官方语义：1=每次注入，0=关闭，N=每N条用户消息注入一次
    // null = 尚未物化的新格式，继续兼容旧 authorNote + 全局参数
    val authorNoteEntries: List<AuthorNoteEntry>? = null,
    val groupChats: List<GroupChat> = emptyList(),   // 群聊列表
    val macroGlobalVariables: Map<String, String> = emptyMap(),        // 宏引擎全局变量（跨对话持久）
    val macroChatVariables: Map<String, Map<String, String>> = emptyMap(), // 宏引擎会话变量（conversationId → 变量）
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    val webServerLocalhostOnly: Boolean = false,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val githubToken: String = "",
    val launchCount: Int = 0,
    val sponsorAlertDismissedAt: Int = 0,
    val wechatBotSetting: WechatBotSetting = WechatBotSetting(),        // 微信 Bot（iLink 长轮询）
    val qqBotSetting: QqBotSetting = QqBotSetting(),                    // QQ Bot（开放平台 WebSocket）
    val proactiveMessageSetting: ProactiveMessageSetting = ProactiveMessageSetting(), // AI 主动发消息
    val securitySetting: SecuritySetting = SecuritySetting(),           // 安全设置（工具调用审批策略）
) {
    /** 所有全局正则：各分组按列表顺序拼接。派生视图，不单独持久化。 */
    val globalRegexes: List<AssistantRegex>
        get() = regexGroups.flatMap { it.regexes }

    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)

        /**
         * 决定标题提示词用哪一份。
         *
         * 用户升级时盘里存的还是旧版默认提示词，光在代码里改 DEFAULT_TITLE_PROMPT 是够不到
         * 他们的。这里靠 prefs 里记的 revision 判断：revision 落后且盘里的值恰好等于某一版
         * 旧默认值，说明用户从没动过，直接换成新默认；只要用户手改过（对不上任何历史默认值），
         * 就原样保留，绝不能覆盖用户自己写的提示词。
         */
        internal fun resolveTitlePrompt(stored: String?, storedRevision: Int?): String {
            if (stored == null) return DEFAULT_TITLE_PROMPT
            if ((storedRevision ?: 0) >= TITLE_PROMPT_REVISION) return stored
            return if (stored in LEGACY_TITLE_PROMPTS) DEFAULT_TITLE_PROMPT else stored
        }

        // 历史版本的默认标题提示词。只增不改：删掉条目会让对应该版本的用户不再被迁移。
        private val LEGACY_TITLE_PROMPTS = listOf(
            """
                I will give you some dialogue content in the `<content>` block.
                You need to summarize the conversation between user and assistant into a short title.
                1. The title language should be consistent with the user's primary language
                2. Do not use punctuation or other special symbols
                3. Reply directly with the title
                4. Summarize using {locale} language
                5. The title should not exceed 10 characters

                <content>
                {content}
                </content>
            """.trimIndent(),
        )
    }
}

@Serializable
data class NetworkSetting(
    val userAgent: String = "",
    val proxyUrl: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val enableAutoRetry: Boolean = true,
)

/**
 * 华灯设置：全局兼容/辅助功能开关。
 * 作为所有助手的默认值；助手级开关可单独覆盖。
 */
@Serializable
data class HuaDengSettings(
    // 中转站兼容：修复 Gemini 经 OpenAI 兼容中转时 reasoning
    val enableProxyFix: Boolean = false,
    // 防空回复（全局）：系统提示词入对话流 + 空回复微扰重试，针对 Gemini
    val enableAntiEmptyResponse: Boolean = false,
    // 上下文瞬态内容裁剪：超过两轮之前的网页搜索结果/图片/音视频不随请求发送（AI 
    // read_history_message 按消息 ID 取回）
    val enableTransientContentPrune: Boolean = true,
    // 清爽简洁模式：隐藏情侣空间/生活空间等娱乐功能入口，并不再注册对应 AI 工具
    val enableCleanMode: Boolean = false,
    // 工具结果截断：关闭后工具输出不再截断（默认截断超过 32KB 的输出）
    val enableToolResultTruncation: Boolean = true,
    // 页面与前端卡的变量域（local / global / message / …），由页面托管、App 落盘
    // 用 JsonElement 保留嵌套对象/数组/数字/布尔，避免被拍平成字符串
    val webviewVariables: Map<String, Map<String, kotlinx.serialization.json.JsonElement>> = emptyMap(),
    // 本地 OCR 兜底：未配置 OCR 模型（或远程识别失败）时
    // 离线识别图片文字。不联网、不上传图片。关掉则回到旧行为——直接给模型
    // 一个 "[Image]" 占位符，图片内容等于不可见。
    // 已废弃：本地离线 OCR（ML Kit 中文包）占用 11 MB 的
    // libmlkit_google_ocr_pipeline.so
    // 已改为完全依赖用户配置的视觉模型。
    // 字段保留是为了让老配置还能正常反序列化，不再读写。
    val enableLocalOcrFallback: Boolean = false,
    // Python 能力说明：在 execute_python 的工具描述里列出可用的
    // （numpy/pandas/pillow/docx/pdf 等）
    // 代价是模型不知道有哪些库可用、倾向于不用它们。
    val enablePythonLibraryHints: Boolean = true,
    // 系统提示词转义：将系统消息中的 < > 转为 HTML 实体
    val enableSystemPromptEscape: Boolean = false,

    // 酒馆助手全局世界书（RikkaHub 无全局概念，此处仅保存酒馆脚本写入的绑定）
    val globalWorldbookNames: List<String> = emptyList(),
    // 酒馆助手预设：整体以原始 JSON 落盘，保证脚本读写无损
    val tavernPresets: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    // 当前从哪个预设加载而来（getLoadedPresetName）
    val loadedPresetName: String? = null,

    // ---- 酒馆模式：纯净请求 ----
    // 开启后请求里只保留「角色卡（含世界书/示例消息/系统提示）+ 聊天历史 + 工具
    // 其余一律不发：插件提示词、记忆检索、跨窗口生活流、模板宏、工作空间提醒
    // 作者注释、技能自动触发、文档转提示词、OCR、占位符替换
    // 用户上下文（记忆/日期）、工具路由与工作伦理区、工具系统提示词。
    val enableTavernMode: Boolean = false,
    // 酒馆模式·保留工具。
    //
    // 默认 **false**（不保留）：开启酒馆模式后连工具都不注册，退化为纯文本
    // 模型，请求体最干净。这正是用户开酒馆模式想要的效果——精简提示词、
    // 减少模型的安全策略拦截。
    //
    // 之前默认 true（保留工具），结果「开了酒馆模式但还是被拦」，
    // 因为工具定义本身也占不少 token、也会触发某些模型的策略。
    // 需要工具的人可以在设置里单独打开。
    val tavernModeKeepTools: Boolean = false,

    // ---- Jev 智能决策（TypeSafe System One Model）
    // 只做判断不生成文本的隐形决策层，永不进入用户可选模型列表
    // 注意：本类整体以 JSON 存在 DataStore
    // 所以这里只能放基础可序列化类型，不要引入自定义 serializer。
    val jevBaseUrl: String = "https://api.typesafe.ai",
    val jevApiKey: String = "",
    // 判断模型：默认 jev-latest
    // 留空按默认处理；请求体里这个字段是必填，绝不能省。
    val jevModel: String = "jev-latest",
    // 低于该置信度不用 Jev 的判断，回退原有逻辑
    // 注意：记忆筛选不使用这个阈值，它有自己的更低门槛（见 MemoryRetriev
    val jevConfidenceThreshold: Float = 0.7f,
    // 自动记忆筛选：记忆检索改由 Jev 判相关性
    val jevTakeoverMemory: Boolean = false,
    // 大模型工具调用：把 Jev 注册成 judge 工具挂给主力模型
    val jevJudgeTool: Boolean = false,
)

/**
 * 安全设置：工具调用审批策略（全局）。
 */
@Serializable
data class SecuritySetting(
    // 强制确认所有工具调用：无视单工具的 needsApproval
    val forceConfirmToolCalls: Boolean = false,
    // 自动批准所有工具调用：跳过审批直接执行（优先级高于强制确认）
    val autoApproveAllTools: Boolean = true,
)

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,

    @SerialName("serif")
    SERIF,

    @SerialName("monospace")
    MONOSPACE,

    @SerialName("custom")
    CUSTOM,
}

@Serializable
enum class BackgroundEffectType {
    @SerialName("blur")
    BLUR,

    @SerialName("glass")
    GLASS,
}

// 界面材质模式：液态玻璃（GLASS）与半透明（TRANSLUCENT）两种玻璃观感，
// FOLLOW_THEME 跟随主题（主题为 liquid_glass 时用 GLASS，否则 FLAT）
@Serializable
enum class DisplayMaterialMode {
    @SerialName("follow_theme")
    FOLLOW_THEME,

    @SerialName("flat")
    FLAT,

    @SerialName("translucent")
    TRANSLUCENT,

    @SerialName("glass")
    GLASS,
}

@Serializable
enum class ConversationSortOrder {
    @SerialName("update_time")
    UPDATE_TIME,

    @SerialName("create_time")
    CREATE_TIME,
}

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val showUserAvatar: Boolean = true,
    // 助手气泡默认开启。
    //
    // 关掉时助手消息是平铺的，和用户消息在视觉上区分度低；开着更像
    // 常见的聊天软件，一眼能看出哪句是谁说的。对多数人是更好的默认。
    val showAssistantBubble: Boolean = true,
    // 气泡不透明度 90%：全不透明时大段文字贴在一起有点闷，
    // 稍微透一点背景能在视觉上分层，同时完全不影响可读性。
    val bubbleOpacity: Float = 0.9f,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateTimeInMessage: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val updateCheckDisabledUntilEpochMillis: Long = 0L,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = true,
    val enableNotificationOnMessageGeneration: Boolean = true,
    val enableLiveUpdateNotification: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    // 代码块字号单独缩放，默认 1.0 = 12sp/16sp
    val codeBlockFontSizeRatio: Float = 1.0f,
    val ttsOnlyReadQuoted: Boolean = false,
    val ttsOnlyReadOutsideBrackets: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val voiceModeListenWhileReplying: Boolean = true,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableQuoteHighlight: Boolean = false,
    val enableBlurEffect: Boolean = false,
    val backgroundEffectType: BackgroundEffectType = BackgroundEffectType.BLUR,
    // 输入框模糊/玻璃的不透明度（0.1~1.0），太低会影响正文可读性
    val inputEffectAlpha: Float = 0.3f,
    // 界面材质模式（液态玻璃自由开关）
    val materialMode: DisplayMaterialMode = DisplayMaterialMode.FOLLOW_THEME,
    // 界面材质底色不透明度（0~95 百分比），数值越低越透
    val interfaceSurfaceOpacity: Float = 82f,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val chatCustomFontPath: String = "",
    val chatCustomFontName: String = "",
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
    val conversationSortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
    val enableTextColor: Boolean = true,
    val quoteColor: String = "",  // empty = theme-follow, otherwise hex like "#E18A24"
    val italicsColor: String = "",  // empty = default (#919191), otherwise hex
    val bracketColor: String = "",  // empty = theme-follow, otherwise hex like "#7EAFC4"
    // ---- 聊天外观自定义覆盖层（null/空串 = 跟随主题） ----
    // 主色调（按钮/链接等强调色），覆盖 colorScheme.primary
    val primaryColor: Long? = null,
    // 全局字体颜色，覆盖 onBackground/onSurface/onSurfa
    val globalTextColor: Long? = null,
    // 气泡/背景颜色（ARGB Long）
    val userBubbleColor: Long? = null,
    val assistantBubbleColor: Long? = null,
    val thinkingBubbleColor: Long? = null,
    val chatBackgroundColor: Long? = null,
    val inputFieldColor: Long? = null,
    // 气泡背景图路径（本地文件路径或 URI），空串 = 不使用
    val userBubbleImagePath: String = "",
    val assistantBubbleImagePath: String = "",
    // 气泡背景图上是否叠加原气泡颜色遮罩（关 = 纯图片）
    val bubbleImageOverlayEnabled: Boolean = false,
    // 气泡圆角半径（dp）
    val bubbleCornerRadius: Float = 16f,
    // 气泡背景图的缩放方式（对齐酒馆 background-size）：
    // "cover" = 裁切铺满；"contain" / 空 = 等比完整显示。
    // 主题常写 width:100% + height:200px + cover
    // 无条件裁切会把图纵向拉扯变形，故默认等比。
    val bubbleBackgroundSize: String = "contain",
    // 抽屉（侧边栏）背景图路径
    val drawerBackgroundPath: String = "",
    // 设置页通用背景图路径，应用于全部设置页面
    val settingBackgroundImagePath: String = "",
    // 聊天背景图路径（酒馆主题导入或手动设置），优先于助手背景；叠加聊天背景色遮罩
    val chatBackgroundImagePath: String = "",
    // 酒馆主题图标定制（发送栏/菜单/扩展/停止/头像框），空值 = 未导入主题图标
    val themeIcons: ThemeIconSet = ThemeIconSet(),
    // 气泡边框：对齐官方 --SmartThemeBorderColor + bord
    val bubbleBorderColor: Long? = null,
    val bubbleBorderWidth: Float = 0f,
    // 气泡阴影：对齐官方 --SmartThemeShadowColor + --sh
    val bubbleShadowColor: Long? = null,
    val bubbleShadowWidth: Float = 0f,
    // 聊天界面按钮隐藏开关（true = 隐藏，默认全部显示）
    val hideTopBarListButton: Boolean = false,
    val hideTopBarNewChatButton: Boolean = false,
    val hideModelPicker: Boolean = false,
    val hideSearchPicker: Boolean = false,
    val hideReasoningPicker: Boolean = false,
    val hideMoreButton: Boolean = false,
    // 侧边栏功能入口隐藏开关（true = 隐藏）
    val hideDrawerMediaCreation: Boolean = false,
    val hideDrawerAiTranslate: Boolean = false,
    // 一键添加符号：开启后输入框下方显示插入按钮
    val enableBracketButton: Boolean = false,
    val bracketButtonSymbol: String = "()",
    // 符号按钮颜色（#RRGGBB，空串 = 跟随主题）
    val bracketButtonColor: String = "",
    // 引用块背景色（官方 --SmartThemeQuoteColor 常同时用于引用
    val quoteBackgroundColor: Long? = null,
    // 下划线颜色（官方 --SmartThemeUnderlineColor）
    val underlineColor: Long? = null,
    // 气泡内层元素样式（代码框/引用块/高亮/斜体/思维链）：主题导入时从 custo
    // 旧实现只搬了纯色 token，导致这些元素"只有文字变色、底色圆角全丢"。
    val themeMarkdownStyle: ThemeMarkdownStyle = ThemeMarkdownStyle(),
)

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "rikkahub_backups",
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.FILES
    ),
) {
    @Serializable
    enum class BackupItem {
        DATABASE,
        FILES,
    }
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)
