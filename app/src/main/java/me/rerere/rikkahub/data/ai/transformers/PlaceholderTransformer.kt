package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.ai.provider.Model
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.GenerationType
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.Temporal
import java.util.Locale
import java.util.TimeZone
import kotlinx.datetime.TimeZone as KtzTimeZone
import kotlinx.datetime.toInstant
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

// context/settingsStore 允许为 null：仅供纯 JVM 单元测试构造（真实管线始终传入非空值）
data class PlaceholderCtx(
    val context: Context?,
    val settingsStore: SettingsStore?,
    val settings: Settings = Settings(),
    val model: Model,
    val assistant: Assistant,
    val messages: List<UIMessage> = emptyList(),
    val conversationId: Uuid? = null,
    val generationType: GenerationType? = null,
    /** 本轮快照（按用户轮冻结）：agentic 工具循环每步重跑 transformer 时，
     *  时间族/位置族宏若每步重新取值会改写前缀、打断供应商缓存。为 null 表示不冻结。 */
    val turnFrozen: TurnMacroSnapshot? = null,
    /** 变量宏（setvar/incvar 等）是否允许执行副作用：本轮首步为 true，
     *  复用冻结快照的后续步骤为 false（避免每步重复 inc/add 导致 {{getvar}} 逐步漂移） */
    val allowVarMutations: Boolean = true,
)

/**
 * 按用户轮冻结的宏取值快照（首步生成，后续步骤复用）：
 * - frozenTime：时间族宏（{{datetimeformat}}/{{time}}）共用的冻结时间点
 * - frozenLastIndex：{{allchatrange}} 冻结的 lastIndex（每步 +1 会改写前缀）
 * - frozenLastCharText：{{lastMessage}}/{{lastCharMessage}} 冻结值
 *   （第 2 步起 messages 末尾是本轮新生成的 assistant 消息，实时解析会变）
 */
class TurnMacroSnapshot(
    val frozenTime: LocalDateTime,
    val frozenLastIndex: Int,
    val frozenLastCharText: String,
)

interface PlaceholderProvider {
    val placeholders: Map<String, PlaceholderInfo>
}

data class PlaceholderInfo(
    val displayName: @Composable () -> Unit,
    val resolver: (PlaceholderCtx) -> String
)

class PlaceholderBuilder {
    private val placeholders = mutableMapOf<String, PlaceholderInfo>()

    fun placeholder(
        key: String,
        displayName: @Composable () -> Unit,
        resolver: (PlaceholderCtx) -> String
    ) {
        placeholders[key] = PlaceholderInfo(displayName, resolver)
    }

    fun build(): Map<String, PlaceholderInfo> = placeholders.toMap()
}

fun buildPlaceholders(block: PlaceholderBuilder.() -> Unit): Map<String, PlaceholderInfo> {
    return PlaceholderBuilder().apply(block).build()
}

object DefaultPlaceholderProvider : PlaceholderProvider {
    override val placeholders: Map<String, PlaceholderInfo> = buildPlaceholders {
        placeholder("cur_date", { Text(stringResource(R.string.placeholder_current_date)) }) {
            LocalDate.now().toDateString()
        }

        placeholder("model_id", { Text(stringResource(R.string.placeholder_model_id)) }) {
            it.model.modelId
        }

        placeholder("model_name", { Text(stringResource(R.string.placeholder_model_name)) }) {
            it.model.displayName
        }

        placeholder("locale", { Text(stringResource(R.string.placeholder_locale)) }) {
            Locale.getDefault().displayName
        }

        placeholder("timezone", { Text(stringResource(R.string.placeholder_timezone)) }) {
            TimeZone.getDefault().displayName
        }

        placeholder("system_version", { Text(stringResource(R.string.placeholder_system_version)) }) {
            "Android SDK v${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})"
        }

        placeholder("device_info", { Text(stringResource(R.string.placeholder_device_info)) }) {
            "${Build.BRAND} ${Build.MODEL}"
        }

        placeholder("battery_level", { Text(stringResource(R.string.placeholder_battery_level)) }) {
            (it.context?.batteryLevel() ?: 0).toString()
        }

        placeholder("nickname", { Text(stringResource(R.string.placeholder_nickname)) }) {
            it.settingsStore?.settingsFlow?.value?.displaySetting?.userNickname?.ifBlank { "user" } ?: "user"
        }

        placeholder("char", { Text(stringResource(R.string.placeholder_char)) }) {
            it.assistant.name.ifBlank { "assistant" }
        }

        placeholder("user", { Text(stringResource(R.string.placeholder_user)) }) {
            it.settingsStore?.settingsFlow?.value?.displaySetting?.userNickname?.ifBlank { "user" } ?: "user"
        }

        // 角色卡字段（用于世界书注入内容）
        placeholder("description", { Text("角色描述") }) {
            it.assistant.tavernData?.description ?: ""
        }
        placeholder("personality", { Text("角色性格") }) {
            it.assistant.tavernData?.personality ?: ""
        }
        placeholder("scenario", { Text("角色场景") }) {
            it.assistant.tavernData?.scenario ?: ""
        }

        // 对齐酒馆核心宏
        placeholder("time", { Text(stringResource(R.string.placeholder_time)) }) {
            cacheFriendlyNow(it.assistant.cacheFriendlyTimeMacros).format(
                DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                    .withLocale(Locale.getDefault())
            )
        }
        placeholder("random", { Text(stringResource(R.string.placeholder_random)) }) {
            Random.nextInt(1, 101).toString()
        }
        placeholder("input", { Text(stringResource(R.string.placeholder_input)) }) {
            it.lastRealMessage(MessageRole.USER)?.let(::textOf) ?: ""
        }
        placeholder("lastMessage", { Text(stringResource(R.string.placeholder_last_message)) }) {
            it.messages.lastOrNull { msg -> !msg.isInjectedBlock() && msg.annotations.none { a -> a is UIMessageAnnotation.ExampleMessage } }
                ?.let(::textOf) ?: ""
        }
        placeholder("firstMessage", { Text(stringResource(R.string.placeholder_first_message)) }) {
            it.assistant.tavernData?.firstMessage ?: ""
        }
        placeholder("creatorNotes", { Text(stringResource(R.string.placeholder_creator_notes)) }) {
            it.assistant.tavernData?.creatorNotes ?: ""
        }
        placeholder("charVersion", { Text(stringResource(R.string.placeholder_char_version)) }) {
            it.assistant.tavernData?.characterVersion ?: ""
        }
        // 官方宏名 {{version}}
        placeholder("version", { Text(stringResource(R.string.placeholder_char_version)) }) {
            it.assistant.tavernData?.characterVersion ?: ""
        }
        placeholder("group", { Text(stringResource(R.string.placeholder_group)) }) {
            it.groupMembers()
        }
        // 官方 macro-env-macros.js：charIfNotGroup 是 group 的别名（单聊=角色名，群聊=成员列表）
        placeholder("charIfNotGroup", { Text("角色名或群成员(官方别名)") }) {
            it.groupMembers()
        }

        // ── 对齐酒馆官方实用宏（第二批）──
        placeholder("persona", { Text(stringResource(R.string.placeholder_persona)) }) {
            val s = it.settingsStore?.settingsFlow?.value ?: Settings()
            s.personas.firstOrNull { p -> p.id == s.activePersonaId && p.enabled }?.description ?: ""
        }

        placeholder("date", { Text(stringResource(R.string.placeholder_date)) }) {
            LocalDate.now().toDateString()
        }

        placeholder("lastUserMessage", { Text(stringResource(R.string.placeholder_last_user_message)) }) {
            it.lastRealMessage(MessageRole.USER)?.let(::textOf) ?: ""
        }

        placeholder("lastCharMessage", { Text(stringResource(R.string.placeholder_last_char_message)) }) {
            it.lastRealMessage(MessageRole.ASSISTANT)?.let(::textOf) ?: ""
        }

        placeholder("idleDuration", { Text(stringResource(R.string.placeholder_idle_duration)) }) {
            val lastUser = it.lastRealMessage(MessageRole.USER) ?: return@placeholder ""
            val instant = lastUser.createdAt.toInstant(KtzTimeZone.currentSystemDefault())
            // 缓存友好模式：取值对齐 5 分钟边界，避免每分钟变化打断前缀缓存
            val now = if (it.assistant.cacheFriendlyTimeMacros) {
                Clock.System.now() - (Clock.System.now().epochSeconds % 300).seconds
            } else {
                Clock.System.now()
            }
            val diff = now - instant
            when {
                diff < 1.minutes -> "刚刚"
                diff < 60.minutes -> "${diff.inWholeMinutes} 分钟前"
                diff < 24.hours -> "${diff.inWholeHours} 小时前"
                else -> "${diff.inWholeDays} 天前"
            }
        }

        placeholder("mesExamples", { Text(stringResource(R.string.placeholder_mes_examples)) }) {
            it.assistant.tavernData?.mesExample ?: ""
        }

        placeholder("creator", { Text(stringResource(R.string.placeholder_creator)) }) {
            it.assistant.tavernData?.creator ?: ""
        }

        placeholder("charPrompt", { Text(stringResource(R.string.placeholder_char_prompt)) }) {
            it.assistant.tavernData?.systemPrompt ?: ""
        }

        placeholder("charInstruction", { Text(stringResource(R.string.placeholder_char_instruction)) }) {
            it.assistant.tavernData?.postHistoryInstructions ?: ""
        }
        // 官方新宏名（别名，与上面旧名同值，不替换现有宏）
        placeholder("charDescription", { Text("角色描述(官方别名)") }) {
            it.assistant.tavernData?.description ?: ""
        }
        placeholder("charPersonality", { Text("角色性格(官方别名)") }) {
            it.assistant.tavernData?.personality ?: ""
        }
        placeholder("charScenario", { Text("角色场景(官方别名)") }) {
            it.assistant.tavernData?.scenario ?: ""
        }
        placeholder("charFirstMessage", { Text("开场白(官方别名)") }) {
            it.assistant.tavernData?.firstMessage ?: ""
        }
        placeholder("charCreatorNotes", { Text("作者备注(官方别名)") }) {
            it.assistant.tavernData?.creatorNotes ?: ""
        }
        placeholder("systemPrompt", { Text("系统提示词(官方别名)") }) {
            it.assistant.tavernData?.systemPrompt ?: ""
        }
        placeholder("jailbreak", { Text("历史后指令(官方别名)") }) {
            it.assistant.tavernData?.postHistoryInstructions ?: ""
        }
        placeholder("charDepthPrompt", { Text("角色深度提示") }) {
            it.assistant.tavernData?.depthPrompt ?: ""
        }
        placeholder("mesExamplesRaw", { Text("示例对话原文") }) {
            it.assistant.tavernData?.mesExample ?: ""
        }
        placeholder("model", { Text("当前模型") }) {
            it.model.displayName
        }
        placeholder("weekday", { Text("星期几") }) {
            // 官方 macro-time-macros.js：{{weekday}} = moment 'dddd'，跟随应用语言
            DateTimeFormatter.ofPattern("EEEE").withLocale(Locale.getDefault()).format(LocalDate.now())
        }
        placeholder("isodate", { Text("ISO日期") }) {
            LocalDate.now().toString()
        }
        placeholder("isotime", { Text("ISO时间") }) {
            // 官方 macro-time-macros.js：{{isotime}} = HH:mm
            cacheFriendlyNow(it.assistant.cacheFriendlyTimeMacros).format(DateTimeFormatter.ofPattern("HH:mm"))
        }

        placeholder("original", { Text(stringResource(R.string.placeholder_original)) }) {
            // 官方 macro-env-macros.js：{{original}} = 当前消息的原文（供角色提示词覆盖里引用，避免替换循环）
            it.lastRealMessage(MessageRole.USER)?.let(::textOf) ?: ""
        }

        placeholder("authorNote", { Text(stringResource(R.string.placeholder_author_note)) }) {
            it.settingsStore?.settingsFlow?.value?.authorNote ?: ""
        }

        // 注意：{{newline}} 由 MacroEngine 处理（支持 {{newline::N}} 重复 N 次）；
        // 这里不再注册旧占位符，避免遮蔽参数语义。

        placeholder("trim", { Text(stringResource(R.string.placeholder_trim)) }) {
            ""
        }
    }

    private fun PlaceholderCtx.groupMembers(): String {
        val groups = settingsStore?.settingsFlow?.value?.groupChats ?: emptyList()
        val group = groups.firstOrNull { g -> assistant.id in g.memberIds }
        val members = group?.memberIds?.mapNotNull { memberId ->
            (settingsStore?.settingsFlow?.value?.assistants ?: emptyList())
                .firstOrNull { a -> a.id == memberId }?.name
        }?.joinToString(", ")
        // 官方：单聊时 {{group}} / {{charIfNotGroup}} 返回角色名本身
        return members?.takeIf { it.isNotBlank() } ?: assistant.name.ifBlank { "assistant" }
    }

    /** 从消息列表末尾找真正的用户/角色消息，跳过注入块（作者注释、人设） */
    private fun PlaceholderCtx.lastRealMessage(role: MessageRole): UIMessage? =
        messages.lastOrNull { m ->
            if (m.role != role) return@lastOrNull false
            !m.isInjectedBlock() &&
                m.annotations.none { a -> a is UIMessageAnnotation.ExampleMessage }
        }

    /** 骰子表达式：支持 NdM±K，例如 1d20 / 2d6+3 / 3d6+1d4-2 */
    internal fun rollDice(expr: String): String? {
        val text = expr.trim().replace(" ", "")
        if (text.isEmpty()) return null
        val tokens = Regex("([+-]?\\d*d\\d+|[+-]?\\d+)", RegexOption.IGNORE_CASE).findAll(text).toList()
        if (tokens.isEmpty() || tokens.joinToString("") { it.value } != text) return null

        var total = 0
        for (token in tokens) {
            val raw = token.value
            val sign = if (raw.startsWith("-")) -1 else 1
            val body = raw.drop(1).takeIf { raw.startsWith("+") || raw.startsWith("-") } ?: raw
            if ('d' in body.lowercase()) {
                val parts = body.split(Regex("[dD]"))
                val count = parts.getOrNull(0)?.toIntOrNull() ?: 1
                val sides = parts.getOrNull(1)?.toIntOrNull() ?: return null
                if (count <= 0 || sides <= 0 || count > 1000 || sides > 100000) return null
                repeat(count) { total += sign * (Random.nextInt(sides) + 1) }
            } else {
                val num = body.toIntOrNull() ?: return null
                total += sign * num
            }
        }
        return total.toString()
    }

    private fun Temporal.toDateString() = DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(this)

    /**
     * 提示词缓存优化：时间宏取值对齐 5 分钟边界。
     * {{time}}/{{isotime}} 若按实时取值，每分钟都会改写提示词前缀，打断 LLM 供应商的
     * 前缀缓存；对齐到 5 分钟窗口后，同一窗口内的请求共享同一取值，只在窗口翻转时失效。
     * 关闭（cacheFriendlyTimeMacros=false）时保持实时精度。
     */
    private fun cacheFriendlyNow(cacheFriendly: Boolean): LocalTime {
        if (!cacheFriendly) return LocalTime.now()
        val now = LocalTime.now()
        return now.minusMinutes((now.minute % 5).toLong()).withSecond(0).withNano(0)
    }

    private fun Context.batteryLevel(): Int {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun textOf(message: UIMessage): String =
        message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
}

/** 注入块使用内部标记避免被当作真实消息，发给模型前移除标记（顶层函数，供两个对象共用） */
private fun stripInjectedMarker(text: String): String =
    text.removePrefix("[Author's Note]\n").removePrefix("[User Persona]\n")

object PlaceholderTransformer : InputMessageTransformer, KoinComponent {
    private val defaultProvider = DefaultPlaceholderProvider

    /**
     * 按用户轮冻结的宏快照缓存：key = assistantId:conversationId，value = (lastUserMsgId, 快照)。
     * agentic 工具循环每步重跑本 transformer，时间/位置敏感宏（{{datetimeformat}}、
     * {{allchatrange}}、{{lastMessage}}、{{lastCharMessage}}）若每步重新取值，
     * 会改写前缀稳定区、打断供应商前缀缓存。首步冻结、整轮复用。
     */
    private val frozenTurnMacros =
        java.util.concurrent.ConcurrentHashMap<String, Pair<String, TurnMacroSnapshot>>()

    /**
     * 按消息冻结的宏渲染结果：key = assistantId:messageId，
     * value = (Text 部件内容哈希, 渲染后的 Text 文本列表，非 Text 部件为 null)。
     * 只缓存含花括号的消息（见 transform 内注释），上限超出整体清空。
     */
    private val frozenMessageRenders =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Int, List<String?>>>()

    /** 冻结缓存上限：超出时淘汰最早写入的会话（防长期驻留内存） */
    private const val MAX_FROZEN_TURNS = 64

    /** 按消息冻结的渲染缓存上限（含宏的消息才占位） */
    private const val MAX_FROZEN_MESSAGES = 128

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val settingsStore = get<SettingsStore>()
        val vars = SettingsMacroVars(settingsStore, ctx.settings)
        val engine = MacroEngine(defaultProvider.placeholders, vars)

        // 按用户轮冻结：首步建快照，后续步骤复用（见 frozenTurnMacros 注释）
        val turnKey = "${ctx.assistant.id}:${ctx.conversationId ?: "no-conversation"}"
        val lastUserMsgId = messages.lastOrNull { it.role == MessageRole.USER }?.id?.toString()
        val cached = frozenTurnMacros[turnKey]?.takeIf { it.first == lastUserMsgId }?.second
        val reusedFrozen = cached != null
        val frozen = cached ?: run {
                val now = java.time.LocalDateTime.now()
                val aligned = if (ctx.assistant.cacheFriendlyTimeMacros) {
                    now.minusMinutes((now.minute % 5).toLong()).withSecond(0).withNano(0)
                } else now
                val snapshot = TurnMacroSnapshot(
                    frozenTime = aligned,
                    frozenLastIndex = messages.lastIndex,
                    frozenLastCharText = messages.lastOrNull { msg ->
                        msg.role == MessageRole.ASSISTANT &&
                            !msg.isInjectedBlock() &&
                            msg.annotations.none { a -> a is UIMessageAnnotation.ExampleMessage }
                    }?.let { msg ->
                        msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
                    } ?: "",
                )
                if (lastUserMsgId != null) {
                    if (frozenTurnMacros.size >= MAX_FROZEN_TURNS) frozenTurnMacros.clear()
                    frozenTurnMacros[turnKey] = lastUserMsgId to snapshot
                }
                snapshot
            }

        val result = messages.map { msg ->
            // 按消息冻结宏渲染：存储的历史消息 id 稳定、内容不可变，但其文本里的宏
            // （最典型是把含 {{time}}/{{idleDuration}} 示例的文档/README 粘进对话）
            // 每轮请求都重新渲染 → 该消息每次请求内容都变，其后的缓存全部失效。
            // 首次渲染后按 (assistant, messageId, 内容哈希) 冻结，消息被编辑才重渲染。
            // 注入块每轮生成新 id，天然不命中此缓存（其稳定性由注入侧冻结保证）。
            val textParts = msg.parts.map { (it as? UIMessagePart.Text)?.text }
            val renderKey = "${ctx.assistant.id}:${msg.id}"
            val contentHash = textParts.hashCode()
            val cachedRender = frozenMessageRenders[renderKey]?.takeIf { it.first == contentHash }?.second
            if (cachedRender != null) {
                msg.copy(
                    parts = msg.parts.mapIndexed { i, part ->
                        val t = cachedRender.getOrNull(i)
                        if (part is UIMessagePart.Text && t != null) part.copy(text = t) else part
                    }
                )
            } else {
                val rendered = msg.parts.map { part ->
                    if (part is UIMessagePart.Text) {
                        stripInjectedMarker(
                            replacePlaceholders(
                                text = part.text,
                                ctx = ctx,
                                engine = engine,
                                settingsStore = settingsStore,
                                messages = messages,
                                frozen = frozen,
                                reusedFrozen = reusedFrozen,
                            )
                        )
                    } else {
                        null
                    }
                }
                // 只缓存含宏候选（花括号）的消息，避免纯文本消息白白占内存
                if (textParts.any { it?.contains('{') == true }) {
                    if (frozenMessageRenders.size >= MAX_FROZEN_MESSAGES) frozenMessageRenders.clear()
                    frozenMessageRenders[renderKey] = contentHash to rendered
                }
                msg.copy(
                    parts = msg.parts.mapIndexed { i, part ->
                        val t = rendered.getOrNull(i)
                        if (part is UIMessagePart.Text && t != null) part.copy(text = t) else part
                    }
                )
            }
        }
        vars.flush()
        return result
    }

    /**
     * 开场白等直接写入对话的消息不经过生成管线（PlaceholderTransformer.transform），
     * {{user}} / {{char}} / 变量宏等不会被替换，界面上会显示原文。
     * 在插入对话前调用本函数完成宏替换（宏引擎与生成管线完全一致）。
     */
    suspend fun substituteGreetingMacros(
        context: Context,
        settings: Settings,
        assistant: Assistant,
        text: String,
    ): String {
        if (!text.contains("{{")) return text
        return try {
            val settingsStore = get<SettingsStore>()
            val ctx = PlaceholderCtx(
                context = context,
                settingsStore = settingsStore,
                settings = settings,
                model = Model(),
                assistant = assistant,
            )
            val vars = SettingsMacroVars(settingsStore, settings)
            val engine = MacroEngine(defaultProvider.placeholders, vars)
            // 非作用域 {{trim}} 后处理与旧单花括号兼容均由引擎内部完成，与生成管线保持一致
            engine.substitute(text, ctx).also { vars.flush() }
        } catch (_: Exception) {
            text
        }
    }

    private fun replacePlaceholders(
        text: String,
        ctx: TransformerContext,
        engine: MacroEngine,
        settingsStore: SettingsStore,
        messages: List<UIMessage>,
        frozen: TurnMacroSnapshot,
        reusedFrozen: Boolean,
    ): String {
        var result = text

        val placeholderCtx = PlaceholderCtx(
            context = ctx.context,
            settingsStore = settingsStore,
            settings = ctx.settings,
            model = ctx.model,
            assistant = ctx.assistant,
            messages = messages,
            conversationId = ctx.conversationId,
            generationType = ctx.generationType,
            turnFrozen = frozen,
            // 复用冻结快照的后续步骤不允许再执行变量副作用（首步已执行并 flush）
            allowVarMutations = reusedFrozen,
        )
        result = engine.substitute(result, placeholderCtx)

        // 旧单花括号兼容（{cur_date} {char} 等）由引擎在 substitute 内单遍完成；
        // 切勿在这里对替换结果再逐 key replace——宏的值可能包含整段原文
        // （把宏文档粘贴进消息时 {{lastUserMessage}}/{{original}} 即自引用），
        // 重复扫描会把原文指数级复制后发给模型。

        return result
    }

    /**
     * 变量存储：会话变量（chatKey=conversationId）与全局变量，写入 DataStore 持久化。
     * 所有修改先落在内存 dirty 表，flush() 时一次性合并写回，避免频繁磁盘写入。
     */
    private class SettingsMacroVars(
        private val settingsStore: SettingsStore,
        private val snapshot: Settings,
    ) : MacroVars {
        private val globalDirty = mutableMapOf<String, String>()
        private val chatDirty = mutableMapOf<String, MutableMap<String, String>>()
        private val globalDeleted = mutableSetOf<String>()
        private val chatDeleted = mutableMapOf<String, MutableSet<String>>()

        override fun get(chatKey: String?, name: String): String? {
            if (chatKey == null) {
                if (name in globalDeleted) return null
                globalDirty[name]?.let { return it }
            } else {
                if (chatKey in chatDeleted && name in chatDeleted.getValue(chatKey)) return null
                chatDirty[chatKey]?.get(name)?.let { return it }
            }
            // ctx.settings 是生成开始时的快照，看不到本轮前序步骤 flush 进 DataStore 的值；
            // agentic 循环后续步骤必须读到首步已写入的变量（否则 {{getvar}} 渲染与前一步不一致，
            // 前缀分叉打断缓存），故回退到实时 flow
            val live = settingsStore.settingsFlow.value
            return if (chatKey == null) {
                live.macroGlobalVariables[name] ?: snapshot.macroGlobalVariables[name]
            } else {
                live.macroChatVariables[chatKey]?.get(name)
                    ?: snapshot.macroChatVariables[chatKey]?.get(name)
            }
        }

        override fun set(chatKey: String?, name: String, value: String) {
            if (chatKey == null) {
                globalDirty[name] = value
                globalDeleted.remove(name)
            } else {
                chatDirty.getOrPut(chatKey) { mutableMapOf() }[name] = value
                chatDeleted[chatKey]?.remove(name)
            }
        }

        override fun inc(chatKey: String?, name: String): String {
            val current = get(chatKey, name)?.toLongOrNull() ?: 0L
            val next = current + 1
            set(chatKey, name, next.toString())
            return next.toString()
        }

        override fun dec(chatKey: String?, name: String): String {
            val current = get(chatKey, name)?.toLongOrNull() ?: 0L
            val next = current - 1
            set(chatKey, name, next.toString())
            return next.toString()
        }

        override fun add(chatKey: String?, name: String, value: String) {
            val current = get(chatKey, name)
            val left = current?.toLongOrNull()
            val right = value.toLongOrNull()
            val next = when {
                left != null && right != null -> (left + right).toString()
                current == null -> value
                else -> current + value
            }
            set(chatKey, name, next)
        }

        override fun has(chatKey: String?, name: String): Boolean = get(chatKey, name) != null

        override fun delete(chatKey: String?, name: String) {
            if (chatKey == null) {
                globalDirty.remove(name)
                globalDeleted.add(name)
            } else {
                chatDirty[chatKey]?.remove(name)
                chatDeleted.getOrPut(chatKey) { mutableSetOf() }.add(name)
            }
        }

        suspend fun flush() {
            if (globalDirty.isEmpty() && chatDirty.isEmpty() && globalDeleted.isEmpty() && chatDeleted.isEmpty()) {
                return
            }
            val latest = settingsStore.settingsFlow.value
            val newGlobal = latest.macroGlobalVariables.toMutableMap()
            globalDeleted.forEach { newGlobal.remove(it) }
            newGlobal.putAll(globalDirty)

            val newChat = latest.macroChatVariables.toMutableMap()
            chatDeleted.forEach { (chat, names) ->
                val m = newChat[chat]?.toMutableMap() ?: mutableMapOf()
                names.forEach { m.remove(it) }
                newChat[chat] = m
            }
            chatDirty.forEach { (chat, map) ->
                val m = newChat[chat]?.toMutableMap() ?: mutableMapOf()
                m.putAll(map)
                newChat[chat] = m
            }
            settingsStore.update(
                latest.copy(
                    macroGlobalVariables = newGlobal,
                    macroChatVariables = newChat,
                )
            )
        }
    }
}
