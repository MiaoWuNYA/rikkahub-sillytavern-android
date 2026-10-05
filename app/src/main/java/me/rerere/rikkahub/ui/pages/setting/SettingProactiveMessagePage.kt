package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.service.ProactiveMessageService
import me.rerere.rikkahub.data.service.ProactiveMessageWorker
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

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
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    var showProactiveRiskDialog by remember { mutableStateOf(false) }
    val proactive = settings.proactiveMessageSetting

    /**
     * 写入设置并**立即重新排程**。
     *
     * 之前所有改动路径（间隔、助手、阈值）都只写 DataStore，不碰调度器，
     * 于是改完看起来毫无反应，必须把开关关了再开一次才生效——这就是
     * 「不生效」的直接原因。这里统一走一个入口，写完就重排。
     */
    fun applySetting(newValue: ProactiveMessageSetting) {
        val safe = newValue.normalized()
        vm.updateSettings(settings.copy(proactiveMessageSetting = safe))
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
                vm.updateSettings(settings.copy(proactiveMessageSetting = newSetting.normalized()))
                ProactiveMessageService.triggerNow(context, newSetting)
            },
            onDismiss = { showProactiveRiskDialog = false }
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.proactive_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                CardGroup {
                    item(
                        headlineContent = { Text(stringResource(R.string.proactive_enable)) },
                        supportingContent = { Text(stringResource(R.string.proactive_enable_desc)) },
                        trailingContent = {
                            Switch(
                                checked = proactive.enabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        showProactiveRiskDialog = true
                                    } else {
                                        val newSetting = proactive.copy(enabled = false)
                                        vm.updateSettings(settings.copy(proactiveMessageSetting = newSetting))
                                        ProactiveMessageService.cancel(context)
                                    }
                                }
                            )
                        }
                    )
                }
            }

            if (proactive.enabled) {
                item {
                    // 下次触发时间：每秒刷新（原来 10 秒一跳，秒数看起来是卡住的）
                    var nextTime by remember { mutableStateOf(ProactiveMessageService.getNextTriggerTime(context)) }
                    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(proactive) {
                        while (true) {
                            val t = System.currentTimeMillis()
                            nowTick = t
                            nextTime = ProactiveMessageService.getNextTriggerTime(context)
                            kotlinx.coroutines.delay(1000L)
                        }
                    }
                    CardGroup {
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
                            }
                        )
                    }
                }
            }

            item {
                CardGroup {
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
                val assistants = settings.assistants
                val current = settings.getCurrentAssistant()
                AssistantPicker(
                    assistants = assistants.map { it.id.toString() to it.name },
                    selectedId = proactive.assistantId.ifBlank { current.id.toString() },
                    onSelect = { applySetting(proactive.copy(assistantId = it)) },
                )
            }

            if (proactive.enabled) {
                item {
                    val assistantIdForList = proactive.assistantId
                        .takeIf { it.isNotBlank() }
                        ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                        ?: settings.getCurrentAssistant().id
                    var conversations by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
                    val untitled = stringResource(R.string.proactive_untitled_conversation)
                    LaunchedEffect(assistantIdForList, untitled) {
                        conversations = runCatching {
                            conversationRepository.getRecentConversations(assistantIdForList, limit = 50)
                                .map { it.id.toString() to it.title.ifBlank { untitled } }
                        }.getOrDefault(emptyList())
                    }
                    ConversationPicker(
                        conversations = conversations,
                        selectedId = proactive.conversationId,
                        onSelect = { applySetting(proactive.copy(conversationId = it)) },
                    )
                }
            }

            item {
                CardGroup {
                    item(
                        headlineContent = { Text(stringResource(R.string.proactive_force_jump)) },
                        supportingContent = { Text(stringResource(R.string.proactive_force_jump_desc)) },
                        trailingContent = {
                            Switch(
                                checked = proactive.allowForceJump,
                                onCheckedChange = { applySetting(proactive.copy(allowForceJump = it)) }
                            )
                        }
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    val hasExactAlarm = ProactiveMessageWorker.canScheduleExactAlarms(context)
                    CardGroup {
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
                            } else null
                        )
                    }
                }
            }
            item {
                val isIgnoring = ProactiveMessageWorker.isIgnoringBatteryOptimizations(context)
                CardGroup {
                    item(
                        headlineContent = { Text(stringResource(R.string.proactive_battery_optimization)) },
                        supportingContent = {
                            if (isIgnoring) {
                                Text(stringResource(R.string.proactive_battery_ignored))
                            } else {
                                Text(stringResource(R.string.proactive_battery_not_ignored))
                            }
                        },
                        onClick = if (!isIgnoring) {
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
                        } else null
                    )
                }
            }
            item {
                CardGroup {
                    item(
                        headlineContent = { Text(stringResource(R.string.proactive_section_about)) },
                        supportingContent = { Text(stringResource(R.string.proactive_about_desc)) },
                    )
                }
            }
        }
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

@Composable
private fun AssistantPicker(
    assistants: List<Pair<String, String>>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = assistants.firstOrNull { it.first == selectedId }?.second
        ?: stringResource(R.string.proactive_use_current_assistant)

    CardGroup {
        item(
            headlineContent = { Text(stringResource(R.string.proactive_use_assistant)) },
            supportingContent = { Text(selectedName) },
            onClick = { expanded = true },
        )
    }

    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text(stringResource(R.string.proactive_use_assistant)) },
            text = {
                LazyColumn {
                    item {
                        TextButton(onClick = {
                            onSelect("")
                            expanded = false
                        }) {
                            Text(stringResource(R.string.proactive_use_current_assistant))
                        }
                    }
                    items(assistants.size) { i ->
                        val (id, name) = assistants[i]
                        TextButton(onClick = {
                            onSelect(id)
                            expanded = false
                        }) {
                            Text(name.ifBlank { stringResource(R.string.proactive_unnamed) })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { expanded = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ConversationPicker(
    conversations: List<Pair<String, String>>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = conversations.firstOrNull { it.first == selectedId }?.second
        ?: stringResource(R.string.proactive_conversation_auto)

    CardGroup {
        item(
            headlineContent = { Text(stringResource(R.string.proactive_conversation)) },
            supportingContent = { Text(selectedName) },
            onClick = { expanded = true },
        )
    }

    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text(stringResource(R.string.proactive_conversation)) },
            text = {
                LazyColumn {
                    item {
                        TextButton(onClick = {
                            onSelect("")
                            expanded = false
                        }) {
                            Text(stringResource(R.string.proactive_conversation_auto))
                        }
                    }
                    items(conversations.size) { i ->
                        val (id, title) = conversations[i]
                        TextButton(onClick = {
                            onSelect(id)
                            expanded = false
                        }) {
                            Text(title)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { expanded = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
