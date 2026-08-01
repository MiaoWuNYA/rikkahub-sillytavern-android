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
import kotlin.random.Random

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
            val groups = it.settingsStore.settingsFlow.value.groupChats
            val group = groups.firstOrNull { g -> it.assistant.id in g.memberIds }
            group?.memberIds?.mapNotNull { memberId ->
                it.settingsStore.settingsFlow.value.assistants
                    .firstOrNull { a -> a.id == memberId }?.name
            }?.joinToString(", ") ?: ""
        }
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
                            text = replacePlaceholders(text = part.text, ctx = ctx, settingsStore = settingsStore)
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
        settingsStore: SettingsStore
    ): String {
        var result = text

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
