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
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.time.LocalDate
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

data class PlaceholderCtx(
    val context: Context,
    val settingsStore: SettingsStore,
    val model: Model,
    val assistant: Assistant,
    val messages: List<UIMessage> = emptyList(),
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
            it.context.batteryLevel().toString()
        }

        placeholder("nickname", { Text(stringResource(R.string.placeholder_nickname)) }) {
            it.settingsStore.settingsFlow.value.displaySetting.userNickname.ifBlank { "user" }
        }

        placeholder("char", { Text(stringResource(R.string.placeholder_char)) }) {
            it.assistant.name.ifBlank { "assistant" }
        }

        placeholder("user", { Text(stringResource(R.string.placeholder_user)) }) {
            it.settingsStore.settingsFlow.value.displaySetting.userNickname.ifBlank { "user" }
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
            LocalTime.now().format(
                DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                    .withLocale(Locale.getDefault())
            )
        }
        placeholder("random", { Text(stringResource(R.string.placeholder_random)) }) {
            Random.nextInt(1, 101).toString()
        }
        placeholder("input", { Text(stringResource(R.string.placeholder_input)) }) {
            it.messages.lastOrNull { msg -> msg.role == MessageRole.USER }?.let(::textOf) ?: ""
        }
        placeholder("lastMessage", { Text(stringResource(R.string.placeholder_last_message)) }) {
            it.messages.lastOrNull()?.let(::textOf) ?: ""
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
        placeholder("group", { Text(stringResource(R.string.placeholder_group)) }) {
            it.groupMembers()
        }

        // ── 对齐酒馆官方实用宏（第二批）──
        placeholder("persona", { Text(stringResource(R.string.placeholder_persona)) }) {
            val s = it.settingsStore.settingsFlow.value
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
            val diff = Clock.System.now() - instant
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

        placeholder("original", { Text(stringResource(R.string.placeholder_original)) }) {
            it.assistant.systemPrompt
        }

        placeholder("authorNote", { Text(stringResource(R.string.placeholder_author_note)) }) {
            it.settingsStore.settingsFlow.value.authorNote
        }

        placeholder("groupMembers", { Text(stringResource(R.string.placeholder_group_members)) }) {
            it.groupMembers()
        }

        placeholder("pipe", { Text(stringResource(R.string.placeholder_pipe)) }) {
            "|"
        }

        placeholder("newline", { Text(stringResource(R.string.placeholder_newline)) }) {
            "\n"
        }

        placeholder("trim", { Text(stringResource(R.string.placeholder_trim)) }) {
            ""
        }
    }

    private fun PlaceholderCtx.groupMembers(): String {
        val groups = settingsStore.settingsFlow.value.groupChats
        val group = groups.firstOrNull { g -> assistant.id in g.memberIds }
        return group?.memberIds?.mapNotNull { memberId ->
            settingsStore.settingsFlow.value.assistants
                .firstOrNull { a -> a.id == memberId }?.name
        }?.joinToString(", ") ?: ""
    }

    /** 从消息列表末尾找真正的用户/角色消息，跳过注入块（作者注释、人设） */
    private fun PlaceholderCtx.lastRealMessage(role: MessageRole): UIMessage? =
        messages.lastOrNull { m ->
            if (m.role != role) return@lastOrNull false
            val t = textOf(m)
            !t.startsWith("[Author's Note]") && !t.startsWith("[User Persona:")
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

    private fun Context.batteryLevel(): Int {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun textOf(message: UIMessage): String =
        message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
}

object PlaceholderTransformer : InputMessageTransformer, KoinComponent {
    private val defaultProvider = DefaultPlaceholderProvider

    private val trimRegex = Regex("""(?:\r?\n)?\s*\{\{?trim\}\}?\s*(?:\r?\n)?""", RegexOption.IGNORE_CASE)
    private val randomArgsRegex = Regex("""\{\{?random::([^}]+)\}\}?""", RegexOption.IGNORE_CASE)
    private val randomLegacyRegex = Regex("""\{\{?random:([^|}]+(?:\|[^|}]+)+)\}\}?""", RegexOption.IGNORE_CASE)
    private val rollRegex = Regex("""\{\{?roll(?:::|:|\s)([^}]+)\}\}?""", RegexOption.IGNORE_CASE)

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val settingsStore = get<SettingsStore>()
        return messages.map {
            it.copy(
                parts = it.parts.map { part ->
                    if (part is UIMessagePart.Text) {
                        part.copy(
                            text = replacePlaceholders(
                                text = part.text,
                                ctx = ctx,
                                settingsStore = settingsStore,
                                messages = messages,
                            )
                        )
                    } else {
                        part
                    }
                }
            )
        }
    }

    private fun replacePlaceholders(
        text: String,
        ctx: TransformerContext,
        settingsStore: SettingsStore,
        messages: List<UIMessage>,
    ): String {
        var result = text

        // 对齐酒馆格式宏：{{trim}} 去除周围的换行
        result = result.replace(trimRegex) { "" }

        // 带参数宏：{{random::a::b}} / {{random:a|b|c}} / {{roll 1d20}} / {{roll::2d6+3}}
        result = result.replace(randomArgsRegex) { m ->
            val opts = m.groupValues[1].split("::").map { it.trim() }.filter { it.isNotEmpty() }
            if (opts.isEmpty()) "" else opts[Random.nextInt(opts.size)]
        }
        result = result.replace(randomLegacyRegex) { m ->
            val opts = m.groupValues[1].split("|").map { it.trim() }.filter { it.isNotEmpty() }
            if (opts.isEmpty()) "" else opts[Random.nextInt(opts.size)]
        }
        result = result.replace(rollRegex) { m ->
            DefaultPlaceholderProvider.rollDice(m.groupValues[1]) ?: ""
        }

        val ctx = PlaceholderCtx(
            context = ctx.context,
            settingsStore = settingsStore,
            model = ctx.model,
            assistant = ctx.assistant,
            messages = messages,
        )
        defaultProvider.placeholders.forEach { (key, placeholderInfo) ->
            val value = placeholderInfo.resolver(ctx)
            result = result
                .replace(oldValue = "{{$key}}", newValue = value, ignoreCase = true)
                .replace(oldValue = "{$key}", newValue = value, ignoreCase = true)
        }

        return result
    }
}
