package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.RiskConfirmDialog
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.service.ProactiveMessageService
import me.rerere.rikkahub.data.service.ProactiveMessageWorker
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.uuid.Uuid
import android.widget.Toast
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import me.rerere.rikkahub.data.service.ProactiveMessageLog
import me.rerere.rikkahub.utils.writeClipboardText
import me.rerere.rikkahub.utils.plus
import androidx.compose.material3.ListItem
import androidx.compose.material3.Icon
import androidx.compose.foundation.clickable
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide

/**
 * AI 主动发消息设置页.
 *
 * 总开关（带风险确认）/ 触发间隔 / 固定助手与对话 / 精确闹钟与电池优化指引.
 *
 * 修过的几处（都是「看起来能用、实际没反应」的坑）：
 *  - 改完间隔不重新排程，必须关掉再开才生效 → 任何改动都立即 reschedule
 *  - 间隔输入框在输入过程中被静默丢弃，退格删空就卡住 → 用本地文本态，
 *    只在合法时才回写；非法时显示错误提示而不是装作没发生
 *  - 下限被 placeholder 的 "30"/"90" 误导成 30/90 → 明确标注最小 1 分钟
 *  - 无法指定对话，只能发到「最近的对话」→ 增加对话选择
 */
@Composable
fun SettingProactiveMessagePage(
    vm: SettingVM = koinViewModel(),
    conversationRepository: ConversationRepository = koinInject(),
) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var showProactiveRiskDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    val proactive = settings.proactiveMessageSetting

    // 发送目标（助手 / 对话）与其它选项并进同一组列表，避免每行各自成一个圆角卡片
    val untitledConversation = stringResource(R.string.proactive_untitled_conversation)
    val currentAssistantId = settings.getCurrentAssistant().id.toString()
    val assistantIdForEffective = proactive.assistantId
        .takeIf { it.isNotBlank() }
        ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        ?: settings.getCurrentAssistant().id
    val assistantLabel = settings.assistants
        .firstOrNull { it.id.toString() == proactive.assistantId.ifBlank { currentAssistantId } }
        ?.name
        ?: stringResource(R.string.proactive_use_current_assistant)
    var assistantPickerExpanded by remember { mutableStateOf(false) }
    var conversationPickerExpanded by remember { mutableStateOf(false) }
    var conversations by remember(assistantIdForEffective) {
        mutableStateOf<List<Triple<String, String, Long>>>(emptyList())
    }
    var conversationLoadFailed by remember(assistantIdForEffective) { mutableStateOf(false) }
    LaunchedEffect(assistantIdForEffective) {
        try {
            conversations = conversationRepository
                .getRecentConversationTitles(assistantIdForEffective, limit = 50)
                .map { Triple(it.id, it.title.ifBlank { untitledConversation }, it.updateAt) }
            conversationLoadFailed = false
        } catch (e: Exception) {
            Log.e("ProactiveSetting", "Failed to load conversations", e)
            conversations = emptyList()
            conversationLoadFailed = true
        }
    }
    val conversationLabel = conversations.firstOrNull { it.first == proactive.conversationId }?.second
        ?: stringResource(R.string.proactive_conversation_auto)

    // 日志内容随对话框打开时重新取，避免看到打开那一刻的旧快照
    val logText = remember(showLogDialog) {
        if (showLogDialog) ProactiveMessageLog.exportAsText(context) else ""
    }

    if (showLogDialog) {
        ProactiveMessageLogDialog(
            text = logText,
            onDismiss = { showLogDialog = false },
        )
    }

    /**
     * 写入设置并**立即重新排程**。
     *
     * 之前所有改动路径（间隔、助手、阈值）都只写 DataStore，不碰调度器，
     * 于是改完看起来毫无反应，必须把开关关了再开一次才生效——这就是
     * 「不生效」的直接原因。这里统一走一个入口，写完就重排。
     */
    fun applySetting(newValue: ProactiveMessageSetting) {
        val safe = newValue.normalized()
        vm.updateSettings { it.copy(proactiveMessageSetting = safe) }
        if (safe.enabled) {
            ProactiveMessageService.scheduleNext(context, safe)
        }
    }

    if (showProactiveRiskDialog) {
        RiskConfirmDialog(
            title = stringResource(R.string.risk_proactive_message_title),
            message = stringResource(R.string.risk_proactive_message_message),
            onConfirm = {
                showProactiveRiskDialog = false
                val newSetting = proactive.copy(enabled = true)
                vm.updateSettings { it.copy(proactiveMessageSetting = newSetting.normalized()) }
                ProactiveMessageService.triggerNow(context, newSetting)
            },
            onDismiss = { showProactiveRiskDialog = false }
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.proactive_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                // 下次触发时间：每秒刷新（原来 10 秒一跳，秒数看起来是卡住的）
                var nextTime by remember { mutableStateOf(ProactiveMessageService.getNextTriggerTime(context)) }
                var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
                LaunchedEffect(proactive) {
                    while (true) {
                        if (proactive.enabled) {
                            val t = System.currentTimeMillis()
                            nowTick = t
                            nextTime = ProactiveMessageService.getNextTriggerTime(context)
                        }
                        kotlinx.coroutines.delay(1000L)
                    }
                }
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.proactive_section_trigger)) },
                ) {
                    switchItem(
                        checked = proactive.enabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                showProactiveRiskDialog = true
                            } else {
                                val newSetting = proactive.copy(enabled = false)
                                vm.updateSettings { it.copy(proactiveMessageSetting = newSetting) }
                                ProactiveMessageService.cancel(context)
                            }
                        },
                        headlineContent = { Text(stringResource(R.string.proactive_enable)) },
                        supportingContent = { Text(stringResource(R.string.proactive_enable_desc)) },
                    )
                    if (proactive.enabled) {
                        item(
                            headlineContent = { Text(stringResource(R.string.proactive_next_trigger)) },
                            supportingContent = {
                                val triggerTime = nextTime
                                if (triggerTime != null && triggerTime > nowTick) {
                                    val remaining = triggerTime - nowTick
                                    val sdf = java.text.SimpleDateFormat(
                                        "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()
                                    )
                                    Text(
                                        stringResource(
                                            R.string.proactive_next_trigger_time,
                                            sdf.format(java.util.Date(triggerTime)),
                                            remaining / 60_000,
                                            (remaining % 60_000) / 1000,
                                        )
                                    )
                                } else {
                                    Text(stringResource(R.string.proactive_waiting_schedule))
                                }
                            },
                        )
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.proactive_section_interval)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.proactive_min_interval)) },
                        supportingContent = {
                            IntervalField(
                                value = proactive.minIntervalMinutes,
                                placeholder = "30",
                                onValueChange = { applySetting(proactive.copy(minIntervalMinutes = it)) },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.proactive_max_interval)) },
                        supportingContent = {
                            IntervalField(
                                value = proactive.maxIntervalMinutes,
                                placeholder = "90",
                                onValueChange = { applySetting(proactive.copy(maxIntervalMinutes = it)) },
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.proactive_section_target)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.proactive_use_assistant)) },
                        supportingContent = { Text(assistantLabel) },
                        onClick = { assistantPickerExpanded = true },
                    )
                    // 对话选择器不依赖开关键：它是配置项，不是运行状态。
                    item(
                        headlineContent = { Text(stringResource(R.string.proactive_conversation)) },
                        supportingContent = {
                            Column {
                                Text(conversationLabel)
                                if (conversationLoadFailed) {
                                    Text(
                                        text = stringResource(R.string.proactive_conversation_load_failed),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                } else if (conversations.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.proactive_conversation_empty),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        onClick = { conversationPickerExpanded = true },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.proactive_section_force_jump)) },
                ) {
                    switchItem(
                        checked = proactive.allowForceJump,
                        onCheckedChange = { applySetting(proactive.copy(allowForceJump = it)) },
                        headlineContent = { Text(stringResource(R.string.proactive_force_jump)) },
                        supportingContent = { Text(stringResource(R.string.proactive_force_jump_desc)) },
                    )
                    if (proactive.allowForceJump) {
                        item(
                            headlineContent = { Text(stringResource(R.string.proactive_idle_threshold)) },
                            supportingContent = {
                                IntervalField(
                                    value = proactive.jumpIdleThresholdMinutes,
                                    placeholder = "120",
                                    allowZero = true,
                                    onValueChange = { applySetting(proactive.copy(jumpIdleThresholdMinutes = it)) },
                                )
                            },
                        )
                    }
                }
            }

            item {
                val hasExactAlarm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ProactiveMessageWorker.canScheduleExactAlarms(context)
                val isIgnoringBattery = ProactiveMessageWorker.isIgnoringBatteryOptimizations(context)
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.proactive_section_system)) },
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        item(
                            headlineContent = { Text(stringResource(R.string.proactive_exact_alarm)) },
                            supportingContent = {
                                if (hasExactAlarm) {
                                    Text(stringResource(R.string.proactive_exact_alarm_granted))
                                } else {
                                    Text(stringResource(R.string.proactive_exact_alarm_denied))
                                }
                            },
                            onClick = if (!hasExactAlarm) {
                                {
                                    try {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", context.packageName, null)
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        val intent = Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
                                        context.startActivity(intent)
                                    }
                                }
                            } else null,
                        )
                    }
                    item(
                        headlineContent = { Text(stringResource(R.string.proactive_battery_optimization)) },
                        supportingContent = {
                            if (isIgnoringBattery) {
                                Text(stringResource(R.string.proactive_battery_ignored))
                            } else {
                                Text(stringResource(R.string.proactive_battery_not_ignored))
                            }
                        },
                        onClick = if (!isIgnoringBattery) {
                            {
                                try {
                                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    context.startActivity(intent)
                                }
                            }
                        } else null,
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.proactive_section_diagnostics)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.proactive_log_title)) },
                        supportingContent = { Text(stringResource(R.string.proactive_log_desc)) },
                        onClick = { showLogDialog = true },
                    )
                }
            }

            item {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    item(
                        headlineContent = { Text(stringResource(R.string.proactive_section_about)) },
                        supportingContent = { Text(stringResource(R.string.proactive_about_desc)) },
                    )
                }
            }
        }
    }

    if (assistantPickerExpanded) {
        AlertDialog(
            onDismissRequest = { assistantPickerExpanded = false },
            title = { Text(stringResource(R.string.proactive_use_assistant)) },
            text = {
                LazyColumn {
                    item {
                        PickerRow(
                            label = stringResource(R.string.proactive_use_current_assistant),
                            selected = proactive.assistantId.isBlank(),
                            onClick = {
                                applySetting(proactive.copy(assistantId = ""))
                                assistantPickerExpanded = false
                            },
                        )
                    }
                    items(settings.assistants.size) { i ->
                        val assistant = settings.assistants[i]
                        PickerRow(
                            label = assistant.name.ifBlank { stringResource(R.string.proactive_unnamed) },
                            selected = proactive.assistantId.isNotBlank() &&
                                assistant.id.toString() == proactive.assistantId,
                            onClick = {
                                applySetting(proactive.copy(assistantId = assistant.id.toString()))
                                assistantPickerExpanded = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { assistantPickerExpanded = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (conversationPickerExpanded) {
        AlertDialog(
            onDismissRequest = { conversationPickerExpanded = false },
            title = { Text(stringResource(R.string.proactive_conversation)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    item {
                        PickerRow(
                            label = stringResource(R.string.proactive_conversation_auto),
                            selected = proactive.conversationId.isBlank(),
                            onClick = {
                                applySetting(
                                    proactive.copy(
                                        conversationId = "",
                                        assistantId = assistantIdForEffective.toString(),
                                    )
                                )
                                conversationPickerExpanded = false
                            },
                        )
                    }
                    items(conversations.size) { i ->
                        val (id, title, updateAt) = conversations[i]
                        PickerRow(
                            label = title,
                            secondary = if (updateAt > 0) java.text.SimpleDateFormat(
                                "yyyy-MM-dd HH:mm", java.util.Locale.getDefault()
                            ).format(java.util.Date(updateAt)) else null,
                            selected = id == proactive.conversationId,
                            onClick = {
                                applySetting(
                                    proactive.copy(
                                        conversationId = id,
                                        assistantId = assistantIdForEffective.toString(),
                                    )
                                )
                                conversationPickerExpanded = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { conversationPickerExpanded = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

/**
 * 正整数输入框。
 *
 * 关键是**本地文本态**：直接把 Int 绑到 value 上时，用户退格删空会得到
 * null，校验失败就不回写，输入框立刻弹回原值——表现是「删不掉、改不动」。
 * 这里让文本自己管自己，只在解析成功且合法时才向上回写。
 */
@Composable
private fun IntervalField(
    value: Int,
    placeholder: String,
    allowZero: Boolean = false,
    onValueChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val minAllowed = if (allowZero) 0 else ProactiveMessageSetting.MIN_INTERVAL_MINUTES
    val isError = parsed == null || parsed < minAllowed ||
        (!allowZero && parsed > ProactiveMessageSetting.MAX_INTERVAL_MINUTES)

    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            // 只接受数字，避免输入字母后陷入无法解析的状态
            val digits = raw.filter { it.isDigit() }.take(7)
            text = digits
            digits.toIntOrNull()?.let { n ->
                if (n >= minAllowed && n <= ProactiveMessageSetting.MAX_INTERVAL_MINUTES) {
                    onValueChange(n)
                }
            }
        },
        placeholder = { Text(placeholder) },
        isError = isError,
        supportingText = {
            Text(
                if (isError) {
                    stringResource(R.string.proactive_interval_invalid, minAllowed)
                } else {
                    stringResource(R.string.proactive_interval_unit)
                }
            )
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * 主动消息的诊断日志对话框。
 *
 * 内容可全选复制——用户贴到别处排查，比让他去翻 logcat 现实得多。
 */
@Composable
private fun ProactiveMessageLogDialog(
    text: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.proactive_log_title)) },
        text = {
            if (text.isBlank()) {
                Text(stringResource(R.string.proactive_log_empty))
            } else {
                SelectionContainer {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // 用项目的剪贴板写入（内部已做 ClipData 包装）
                context.writeClipboardText(text)
                Toast.makeText(
                    context,
                    context.getString(R.string.proactive_log_copied),
                    Toast.LENGTH_SHORT,
                ).show()
            }) {
                Text(stringResource(R.string.proactive_log_copy))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    ProactiveMessageLog.clear(context)
                    onDismiss()
                }) {
                    Text(stringResource(R.string.proactive_log_clear))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
    )
}

/**
 * 选择弹窗里的一行。
 *
 * 刻意不用 TextButton：那套按钮在 Material 3 Expressive 下默认是全圆角
 * 胶囊，逐个套在候选项上就成了一列各自独立的药丸，而不是一组连续的选项。
 *
 * 这里用 ListItem —— 整行可点、没有独立圆角，选中态交给尾部对勾，
 * 和其它设置项的观感一致。
 */
@Composable
private fun PickerRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    secondary: String? = null,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = secondary?.let { { Text(it) } },
        trailingContent = if (selected) {
            {
                Icon(
                    imageVector = Lucide.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else null,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}
