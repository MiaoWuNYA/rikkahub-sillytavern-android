package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.ai.prompts.TITLE_PROMPT_REVISION
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.parseAuthorNotePosition
import me.rerere.rikkahub.data.model.withNewLocalTools
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.reflect.KProperty1
import kotlin.uuid.Uuid

/**
 * [Settings] 的一个字段和它在 DataStore 里的 key 的对应关系。
 *
 * 读、写、判断有没有变化都从这里走，新增字段只要在 [SETTINGS_FIELDS] 里加一行。
 */
internal class SettingsField<T, P : Any>(
    val key: Preferences.Key<P>,
    val property: KProperty1<Settings, T>,
    private val set: Settings.(T) -> Settings,
    private val encode: (T) -> P?,
    private val decode: (P) -> T,
) {
    fun changed(old: Settings, new: Settings): Boolean = property.get(old) != property.get(new)

    /** key 不存在时保留 [settings] 里原有的值。 */
    fun read(preferences: Preferences, settings: Settings): Settings {
        val stored = preferences[key] ?: return settings
        return settings.set(decode(stored))
    }

    /** 编码结果为 null 时删除 key。 */
    fun write(preferences: MutablePreferences, settings: Settings) {
        val encoded = encode(property.get(settings))
        if (encoded != null) preferences[key] = encoded else preferences.remove(key)
    }
}

/** 原样存取的基本类型。 */
private fun <T : Any> plain(
    key: Preferences.Key<T>,
    property: KProperty1<Settings, T>,
    set: Settings.(T) -> Settings,
) = SettingsField(key, property, set, encode = { it }, decode = { it })

/** 存成 JSON 字符串。 */
private inline fun <reified T> json(
    key: Preferences.Key<String>,
    property: KProperty1<Settings, T>,
    noinline set: Settings.(T) -> Settings,
) = SettingsField(
    key, property, set,
    encode = { JsonInstant.encodeToString(it) },
    decode = { JsonInstant.decodeFromString<T>(it) },
)

/** 存成 JSON 字符串，解析失败时回退默认值，避免一条脏数据让整个设置读不出来。 */
private inline fun <reified T> jsonSafe(
    key: Preferences.Key<String>,
    property: KProperty1<Settings, T>,
    noinline set: Settings.(T) -> Settings,
    noinline fallback: () -> T,
) = SettingsField(
    key, property, set,
    encode = { JsonInstant.encodeToString(it) },
    decode = { stored -> runCatching { JsonInstant.decodeFromString<T>(stored) }.getOrElse { fallback() } },
)

private fun uuid(
    key: Preferences.Key<String>,
    property: KProperty1<Settings, Uuid>,
    set: Settings.(Uuid) -> Settings,
) = SettingsField(key, property, set, encode = { it.toString() }, decode = { Uuid.parse(it) })

internal val SETTINGS_FIELDS: List<SettingsField<*, *>> = with(SettingsStore) {
    listOf(
        // UI设置
        plain(DYNAMIC_COLOR, Settings::dynamicColor) { copy(dynamicColor = it) },
        plain(THEME_ID, Settings::themeId) { copy(themeId = it) },
        json(CUSTOM_THEMES, Settings::customThemes) { copy(customThemes = it) },
        json(DISPLAY_SETTING, Settings::displaySetting) { copy(displaySetting = it) },
        json(NETWORK_SETTING, Settings::networkSetting) { copy(networkSetting = it) },
        jsonSafe(HUADENG_SETTINGS, Settings::huadengSettings, { copy(huadengSettings = it) }, ::HuaDengSettings),
        jsonSafe(WECHAT_BOT_SETTING, Settings::wechatBotSetting, { copy(wechatBotSetting = it) }, ::WechatBotSetting),
        jsonSafe(QQ_BOT_SETTING, Settings::qqBotSetting, { copy(qqBotSetting = it) }, ::QqBotSetting),
        jsonSafe(
            PROACTIVE_MESSAGE_SETTING, Settings::proactiveMessageSetting,
            { copy(proactiveMessageSetting = it) }, ::ProactiveMessageSetting,
        ),
        jsonSafe(SECURITY_SETTING, Settings::securitySetting, { copy(securitySetting = it) }, ::SecuritySetting),
        plain(DEVELOPER_MODE, Settings::developerMode) { copy(developerMode = it) },

        // 模型选择
        json(FAVORITE_MODELS, Settings::favoriteModels) { copy(favoriteModels = it) },
        uuid(SELECT_MODEL, Settings::chatModelId) { copy(chatModelId = it) },
        uuid(FAST_MODEL, Settings::fastModelId) { copy(fastModelId = it) },
        SettingsField(
            TITLE_MODEL, Settings::titleModelId, { copy(titleModelId = it) },
            encode = { it?.toString() },
            decode = { stored -> stored.takeIf { it.isNotBlank() }?.let { Uuid.parse(it) } },
        ),
        SettingsField(
            FAST_MODEL_REASONING_LEVEL, Settings::fastModelReasoningLevel, { copy(fastModelReasoningLevel = it) },
            encode = { it.name },
            // 认不出的值按 AUTO 处理
            decode = { name -> ReasoningLevel.entries.find { it.name == name } ?: ReasoningLevel.AUTO },
        ),
        SettingsField(
            TITLE_REASONING_LEVEL, Settings::titleReasoningLevel, { copy(titleReasoningLevel = it) },
            encode = { it.name },
            decode = { name -> ReasoningLevel.entries.find { it.name == name } ?: ReasoningLevel.OFF },
        ),
        uuid(TRANSLATE_MODEL, Settings::translateModeId) { copy(translateModeId = it) },
        plain(ENABLE_SUGGESTION, Settings::enableSuggestion) { copy(enableSuggestion = it) },
        uuid(IMAGE_GENERATION_MODEL, Settings::imageGenerationModelId) { copy(imageGenerationModelId = it) },
        plain(IMAGE_GENERATION_SIZE, Settings::imageGenerationSize) { copy(imageGenerationSize = it) },
        SettingsField(
            IMAGE_GENERATION_REASONING_LEVEL, Settings::imageGenerationReasoningLevel,
            { copy(imageGenerationReasoningLevel = it) },
            encode = { it.name },
            decode = { name -> ReasoningLevel.entries.find { it.name == name } ?: ReasoningLevel.AUTO },
        ),
        plain(ENABLE_AUTO_TITLE, Settings::enableAutoTitle) { copy(enableAutoTitle = it) },
        plain(TITLE_PROMPT, Settings::titlePrompt) { copy(titlePrompt = it) },
        plain(TRANSLATION_PROMPT, Settings::translatePrompt) { copy(translatePrompt = it) },
        plain(TRANSLATE_THINKING_BUDGET, Settings::translateThinkingBudget) { copy(translateThinkingBudget = it) },
        plain(TRANSLATE_TARGET_LANGUAGE, Settings::translateTargetLanguage) { copy(translateTargetLanguage = it) },
        plain(SUGGESTION_PROMPT, Settings::suggestionPrompt) { copy(suggestionPrompt = it) },
        uuid(OCR_MODEL, Settings::ocrModelId) { copy(ocrModelId = it) },
        plain(OCR_PROMPT, Settings::ocrPrompt) { copy(ocrPrompt = it) },
        uuid(COMPRESS_MODEL, Settings::compressModelId) { copy(compressModelId = it) },
        plain(COMPRESS_PROMPT, Settings::compressPrompt) { copy(compressPrompt = it) },

        // 提供商
        json(PROVIDERS, Settings::providers) { copy(providers = it) },

        // 助手
        uuid(SELECT_ASSISTANT, Settings::assistantId) { copy(assistantId = it) },
        SettingsField(
            ASSISTANTS, Settings::assistants, { copy(assistants = it) },
            encode = { JsonInstant.encodeToString(it) },
            // 旧数据里的助手补上后来新增的本地工具选项
            decode = { stored ->
                JsonInstant.decodeFromString<List<Assistant>>(stored).map { it.withNewLocalTools() }
            },
        ),
        json(ASSISTANT_TAGS, Settings::assistantTags) { copy(assistantTags = it) },

        // 搜索
        json(SEARCH_SERVICES, Settings::searchServices) { copy(searchServices = it) },
        json(SEARCH_COMMON, Settings::searchCommonOptions) { copy(searchCommonOptions = it) },
        plain(SEARCH_SELECTED, Settings::searchServiceSelected) { copy(searchServiceSelected = it) },

        // MCP
        json(MCP_SERVERS, Settings::mcpServers) { copy(mcpServers = it) },

        // WebDAV
        json(WEBDAV_CONFIG, Settings::webDavConfig) { copy(webDavConfig = it) },

        // S3
        json(S3_CONFIG, Settings::s3Config) { copy(s3Config = it) },
        json(UPLOAD_S3_CONFIG, Settings::uploadS3Config) { copy(uploadS3Config = it) },

        // 媒体生成
        json(MEDIA_GENERATION_PROVIDERS, Settings::mediaGenerationProviders) { copy(mediaGenerationProviders = it) },

        // TTS
        json(TTS_PROVIDERS, Settings::ttsProviders) { copy(ttsProviders = it) },
        uuid(SELECTED_TTS_PROVIDER, Settings::selectedTTSProviderId) { copy(selectedTTSProviderId = it) },
        plain(DEFAULT_TTS_PLAYBACK_SPEED, Settings::defaultTTSPlaybackSpeed) { copy(defaultTTSPlaybackSpeed = it) },

        // ASR
        json(ASR_PROVIDERS, Settings::asrProviders) { copy(asrProviders = it) },
        SettingsField(
            SELECTED_ASR_PROVIDER, Settings::selectedASRProviderId, { copy(selectedASRProviderId = it) },
            // 没选时不存这个 key
            encode = { it?.toString() },
            decode = { Uuid.parse(it) },
        ),

        // Web Server
        plain(WEB_SERVER_ENABLED, Settings::webServerEnabled) { copy(webServerEnabled = it) },
        plain(WEB_SERVER_PORT, Settings::webServerPort) { copy(webServerPort = it) },
        plain(ENABLE_WEB_SEARCH, Settings::enableWebSearch) { copy(enableWebSearch = it) },
        plain(FILE_RETENTION_DAYS, Settings::fileRetentionDays) { copy(fileRetentionDays = it) },
        plain(LAUNCH_COUNT, Settings::launchCount) { copy(launchCount = it) },
        plain(WEB_SERVER_JWT_ENABLED, Settings::webServerJwtEnabled) { copy(webServerJwtEnabled = it) },
        plain(WEB_SERVER_ACCESS_PASSWORD, Settings::webServerAccessPassword) { copy(webServerAccessPassword = it) },
        plain(WEB_SERVER_LOCALHOST_ONLY, Settings::webServerLocalhostOnly) { copy(webServerLocalhostOnly = it) },

        // 提示词注入
        json(MODE_INJECTIONS, Settings::modeInjections) { copy(modeInjections = it) },
        json(LOREBOOKS, Settings::lorebooks) { copy(lorebooks = it) },
        json(REGEX_GROUPS, Settings::regexGroups) { copy(regexGroups = it) },
        json(QUICK_MESSAGES, Settings::quickMessages) { copy(quickMessages = it) },

        // 世界书（酒馆 world_info）
        plain(WORLD_INFO_BUDGET, Settings::worldInfoBudget) { copy(worldInfoBudget = it) },
        plain(WORLD_INFO_BUDGET_CAP, Settings::worldInfoBudgetCap) { copy(worldInfoBudgetCap = it) },
        plain(WORLD_INFO_MIN_ACTIVATIONS, Settings::worldInfoMinActivations) { copy(worldInfoMinActivations = it) },
        plain(WORLD_INFO_MIN_ACTIVATIONS_DEPTH_MAX, Settings::worldInfoMinActivationsDepthMax) {
            copy(worldInfoMinActivationsDepthMax = it)
        },
        plain(WORLD_INFO_RECURSIVE, Settings::worldInfoRecursive) { copy(worldInfoRecursive = it) },
        plain(WORLD_INFO_MAX_RECURSION_STEPS, Settings::worldInfoMaxRecursionSteps) {
            copy(worldInfoMaxRecursionSteps = it)
        },
        plain(WORLD_INFO_DEPTH, Settings::worldInfoDepth) { copy(worldInfoDepth = it) },
        plain(WORLD_INFO_CHARACTER_STRATEGY, Settings::worldInfoCharacterStrategy) {
            copy(worldInfoCharacterStrategy = it)
        },
        plain(WORLD_INFO_OVERFLOW_ALERT, Settings::worldInfoOverflowAlert) { copy(worldInfoOverflowAlert = it) },
        plain(WORLD_INFO_USE_GROUP_SCORING, Settings::worldInfoUseGroupScoring) {
            copy(worldInfoUseGroupScoring = it)
        },

        // 向量检索（Vector Storage）
        plain(VECTOR_STORAGE_ENABLED, Settings::vectorStorageEnabled) { copy(vectorStorageEnabled = it) },
        SettingsField(
            VECTOR_STORAGE_MODEL_ID, Settings::vectorStorageModelId, { copy(vectorStorageModelId = it) },
            encode = { it?.toString() },
            decode = { Uuid.parse(it) },
        ),
        plain(VECTOR_STORAGE_THRESHOLD, Settings::vectorStorageThreshold) { copy(vectorStorageThreshold = it) },
        plain(VECTOR_STORAGE_SCAN_DEPTH, Settings::vectorStorageScanDepth) { copy(vectorStorageScanDepth = it) },

        // 人设 & 导演备注
        json(PERSONAS, Settings::personas) { copy(personas = it) },
        SettingsField(
            ACTIVE_PERSONA_ID, Settings::activePersonaId, { copy(activePersonaId = it) },
            encode = { it?.toString() },
            decode = { Uuid.parse(it) },
        ),
        plain(AUTHOR_NOTE, Settings::authorNote) { copy(authorNote = it) },
        plain(AUTHOR_NOTE_ENABLED, Settings::authorNoteEnabled) { copy(authorNoteEnabled = it) },
        SettingsField(
            AUTHOR_NOTE_POSITION, Settings::authorNotePosition, { copy(authorNotePosition = it) },
            encode = { it.name },
            decode = { parseAuthorNotePosition(it) },
        ),
        plain(AUTHOR_NOTE_DEPTH, Settings::authorNoteDepth) { copy(authorNoteDepth = it) },
        SettingsField(
            AUTHOR_NOTE_ROLE, Settings::authorNoteRole, { copy(authorNoteRole = it) },
            encode = { it.name },
            decode = { name -> runCatching { MessageRole.valueOf(name) }.getOrDefault(MessageRole.SYSTEM) },
        ),
        plain(AUTHOR_NOTE_INTERVAL, Settings::authorNoteInterval) { copy(authorNoteInterval = it) },
        SettingsField(
            AUTHOR_NOTE_ENTRIES, Settings::authorNoteEntries, { copy(authorNoteEntries = it) },
            encode = { it?.let { entry -> JsonInstant.encodeToString(entry) } },
            decode = { JsonInstant.decodeFromString(it) },
        ),

        // 群聊
        json(GROUP_CHATS, Settings::groupChats) { copy(groupChats = it) },

        // 宏引擎变量（酒馆 Macro 2.0 变量持久化）
        json(MACRO_GLOBAL_VARIABLES, Settings::macroGlobalVariables) { copy(macroGlobalVariables = it) },
        json(MACRO_CHAT_VARIABLES, Settings::macroChatVariables) { copy(macroChatVariables = it) },

        // 备份提醒
        json(BACKUP_REMINDER_CONFIG, Settings::backupReminderConfig) { copy(backupReminderConfig = it) },

        // GitHub
        plain(GITHUB_TOKEN, Settings::githubToken) { copy(githubToken = it) },

        // 赞助提醒
        plain(SPONSOR_ALERT_DISMISSED_AT, Settings::sponsorAlertDismissedAt) { copy(sponsorAlertDismissedAt = it) },
    )
}

/**
 * key 不存在时各字段的取值。
 *
 * 和 [Settings] 构造函数的默认值只有一处不同，沿用的是一直以来的读取行为：没选过的模型指向内置的自动模型。
 */
private val MISSING_KEY_DEFAULTS = Settings(
    chatModelId = DEFAULT_AUTO_MODEL_ID,
    fastModelId = DEFAULT_AUTO_MODEL_ID,
    translateModeId = DEFAULT_AUTO_MODEL_ID,
    compressModelId = DEFAULT_AUTO_MODEL_ID,
)

/** 解码出的是盘上的原始值，还没有经过 [normalized]。 */
internal fun Preferences.toSettings(): Settings {
    val settings = SETTINGS_FIELDS.fold(MISSING_KEY_DEFAULTS) { acc, field -> field.read(this, acc) }
    // 标题提示词带 revision 迁移：旧默认提示词升级到新版，用户手改过的不动
    return settings.copy(
        titlePrompt = Settings.resolveTitlePrompt(
            stored = this[SettingsStore.TITLE_PROMPT],
            storedRevision = this[SettingsStore.TITLE_PROMPT_REVISION_KEY],
        ),
    )
}

/**
 * 把 [settings] 写进 Preferences。
 *
 * 给了 [persisted]（盘上现有的那一份）时只写和它不同的字段：整份设置的 JSON 编码很重，没变的字段不用重新编码。
 */
internal fun MutablePreferences.putSettings(settings: Settings, persisted: Settings? = null) {
    SETTINGS_FIELDS.forEach { field ->
        if (persisted == null || field.changed(persisted, settings)) {
            field.write(this, settings)
        }
    }
    // 标题提示词的 revision 跟着每次写入刷新，读取端靠它判断旧默认值要不要升级
    this[SettingsStore.TITLE_PROMPT_REVISION_KEY] = TITLE_PROMPT_REVISION
}
