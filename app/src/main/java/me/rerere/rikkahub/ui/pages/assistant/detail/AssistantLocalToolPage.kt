package me.rerere.rikkahub.ui.pages.assistant.detail

import android.Manifest
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ui.toast.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.ChartLineData01
import me.rerere.hugeicons.stroke.ClipboardPaste
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Database
import me.rerere.hugeicons.stroke.Diff
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Gift
import me.rerere.hugeicons.stroke.Heart
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.JavaScript
import me.rerere.hugeicons.stroke.MessageQuestion
import me.rerere.hugeicons.stroke.Python
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.SourceCode
import me.rerere.hugeicons.stroke.Task01
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.Terminal
import me.rerere.hugeicons.stroke.Toolbox
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.Calculator
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.permission.PermissionInfo
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.hasUsageStatsPermission
import me.rerere.rikkahub.utils.openUsageAccessSettings
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantLocalToolPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_local_tools))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantLocalToolContent(
            innerPadding = innerPadding,
            assistant = assistant,
            settings = settings,
            scope = scope,
            onUpdate = { vm.update(it) },
        )
    }
}

@Composable
private fun AssistantLocalToolContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    settings: Settings,
    scope: kotlinx.coroutines.CoroutineScope,
    onUpdate: (Assistant) -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val permissionRequiredText =
        stringResource(R.string.assistant_page_local_tools_screen_time_permission_required)

    val calendarPermissionState = rememberPermissionState(
        permissions = setOf(
            PermissionInfo(
                permission = Manifest.permission.READ_CALENDAR,
                displayName = { Text(stringResource(R.string.permission_calendar_read)) },
                usage = { Text(stringResource(R.string.permission_calendar_read_desc)) },
                required = true
            ),
            PermissionInfo(
                permission = Manifest.permission.WRITE_CALENDAR,
                displayName = { Text(stringResource(R.string.permission_calendar_write)) },
                usage = { Text(stringResource(R.string.permission_calendar_write_desc)) },
                required = true
            ),
        )
    )
    PermissionManager(permissionState = calendarPermissionState)

    fun toggleLocalTool(option: LocalToolOption, enabled: Boolean) {
        if (enabled && option == LocalToolOption.ScreenTime && !context.hasUsageStatsPermission()) {
            toaster.show(message = permissionRequiredText, type = ToastType.Warning)
            context.openUsageAccessSettings()
        }
        if (enabled && option == LocalToolOption.Calendar && !calendarPermissionState.allPermissionsGranted) {
            calendarPermissionState.requestPermissions()
            return
        }
        val newLocalTools = if (enabled) {
            assistant.localTools + option
        } else {
            assistant.localTools - option
        }
        onUpdate(assistant.copy(localTools = newLocalTools))
    }

    fun CardGroupScope.toolItem(
        option: LocalToolOption,
        icon: ImageVector,
        @StringRes title: Int,
        @StringRes description: Int,
    ) {
        val checked = assistant.localTools.contains(option)
        switchItem(
            checked = checked,
            onCheckedChange = { toggleLocalTool(option, it) },
            // 图标底色跟着开关状态变化
            leadingContent = {
                CardGroupIcon(
                    icon = icon,
                    containerColor = if (checked) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (checked) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            supportingContent = { Text(stringResource(description)) },
            headlineContent = { Text(stringResource(title)) },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .padding(bottom = 16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.local_tool_parallel_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.local_tool_parallel_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableParallelToolExecution,
                        onCheckedChange = { onUpdate(assistant.copy(enableParallelToolExecution = it)) }
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.local_tool_recurring_limit_title)) },
                supportingContent = { Text(stringResource(R.string.local_tool_recurring_limit_desc)) },
                trailingContent = {
                    OutlinedTextField(
                        value = assistant.toolRecurringLimit.toString(),
                        onValueChange = { v ->
                            v.toIntOrNull()?.let { onUpdate(assistant.copy(toolRecurringLimit = it)) }
                        },
                        modifier = Modifier.width(70.dp),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.local_tool_total_steps_limit_title)) },
                supportingContent = { Text(stringResource(R.string.local_tool_total_steps_limit_desc)) },
                trailingContent = {
                    OutlinedTextField(
                        value = assistant.totalStepsLimit.toString(),
                        onValueChange = { v ->
                            v.toIntOrNull()?.let { onUpdate(assistant.copy(totalStepsLimit = it)) }
                        },
                        modifier = Modifier.width(70.dp),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.local_tool_exec_timeout_title)) },
                supportingContent = { Text(stringResource(R.string.local_tool_exec_timeout_desc)) },
                trailingContent = {
                    OutlinedTextField(
                        value = assistant.toolExecTimeout.toString(),
                        onValueChange = { v ->
                            v.toIntOrNull()?.let { onUpdate(assistant.copy(toolExecTimeout = it)) }
                        },
                        modifier = Modifier.width(70.dp),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.local_tool_js_timeout_title)) },
                supportingContent = { Text(stringResource(R.string.local_tool_js_timeout_desc)) },
                trailingContent = {
                    OutlinedTextField(
                        value = assistant.jsTimeout.toString(),
                        onValueChange = { v ->
                            v.toIntOrNull()?.let { onUpdate(assistant.copy(jsTimeout = it)) }
                        },
                        modifier = Modifier.width(70.dp),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.local_tool_shell_timeout_title)) },
                supportingContent = { Text(stringResource(R.string.local_tool_shell_timeout_desc)) },
                trailingContent = {
                    OutlinedTextField(
                        value = assistant.shellTimeout.toString(),
                        onValueChange = { v ->
                            v.toIntOrNull()?.let { onUpdate(assistant.copy(shellTimeout = it)) }
                        },
                        modifier = Modifier.width(70.dp),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            )
        }
        CardGroup {
            toolItem(
                option = LocalToolOption.JavascriptEngine,
                icon = HugeIcons.JavaScript,
                title = R.string.assistant_page_local_tools_javascript_engine_title,
                description = R.string.assistant_page_local_tools_javascript_engine_desc,
            )
            toolItem(
                option = LocalToolOption.TimeInfo,
                icon = HugeIcons.Clock01,
                title = R.string.assistant_page_local_tools_time_info_title,
                description = R.string.assistant_page_local_tools_time_info_desc,
            )
            toolItem(
                option = LocalToolOption.Clipboard,
                icon = HugeIcons.ClipboardPaste,
                title = R.string.assistant_page_local_tools_clipboard_title,
                description = R.string.assistant_page_local_tools_clipboard_desc,
            )
            toolItem(
                option = LocalToolOption.Tts,
                icon = HugeIcons.VolumeHigh,
                title = R.string.assistant_page_local_tools_tts_title,
                description = R.string.assistant_page_local_tools_tts_desc,
            )
            toolItem(
                option = LocalToolOption.AskUser,
                icon = HugeIcons.MessageQuestion,
                title = R.string.assistant_page_local_tools_ask_user_title,
                description = R.string.assistant_page_local_tools_ask_user_desc,
            )
            toolItem(
                option = LocalToolOption.PythonEngine,
                icon = HugeIcons.Python,
                title = R.string.local_tool_python_title,
                description = R.string.local_tool_python_desc,
            )
            toolItem(
                option = LocalToolOption.FileTools,
                icon = HugeIcons.Folder01,
                title = R.string.local_tool_file_title,
                description = R.string.local_tool_file_desc,
            )
            toolItem(
                option = LocalToolOption.ShellTools,
                icon = HugeIcons.Terminal,
                title = R.string.local_tool_shell_cmd_title,
                description = R.string.local_tool_shell_cmd_desc,
            )
            toolItem(
                option = LocalToolOption.DatabaseQuery,
                icon = HugeIcons.Database,
                title = R.string.local_tool_database_title,
                description = R.string.local_tool_database_desc,
            )
            toolItem(
                option = LocalToolOption.ScreenTime,
                icon = HugeIcons.SmartPhone01,
                title = R.string.assistant_page_local_tools_screen_time_title,
                description = R.string.assistant_page_local_tools_screen_time_desc,
            )
            toolItem(
                option = LocalToolOption.Calendar,
                icon = HugeIcons.Calendar03,
                title = R.string.assistant_page_local_tools_calendar_title,
                description = R.string.assistant_page_local_tools_calendar_desc,
            )
            toolItem(
                option = LocalToolOption.DeviceToolbox,
                icon = HugeIcons.Toolbox,
                title = R.string.local_tool_device_toolbox_title,
                description = R.string.local_tool_device_toolbox_desc,
            )
            toolItem(
                option = LocalToolOption.LifeCompanion,
                icon = HugeIcons.Heart,
                title = R.string.local_tool_life_companion_title,
                description = R.string.local_tool_life_companion_desc,
            )
            toolItem(
                option = LocalToolOption.CoupleSpace,
                icon = HugeIcons.Gift,
                title = R.string.local_tool_couple_space_title,
                description = R.string.local_tool_couple_space_desc,
            )
            // 上游 2.5.6：chart_display 本地工具
            toolItem(
                option = LocalToolOption.ChartDisplay,
                icon = HugeIcons.ChartLineData01,
                title = R.string.assistant_page_local_tools_chart_display_title,
                description = R.string.assistant_page_local_tools_chart_display_desc,
            )
            // 代码静态检查
            toolItem(
                option = LocalToolOption.CodeCheck,
                icon = HugeIcons.SourceCode,
                title = R.string.local_tool_code_check_title,
                description = R.string.local_tool_code_check_desc,
            )
            // 计算器（数值）
            toolItem(
                option = LocalToolOption.Calculator,
                icon = HugeIcons.Calculator,
                title = R.string.local_tool_calculator_title,
                description = R.string.local_tool_calculator_desc,
            )
            // 分享文件
            toolItem(
                option = LocalToolOption.PresentFile,
                icon = HugeIcons.Share04,
                title = R.string.local_tool_present_file_title,
                description = R.string.local_tool_present_file_desc,
            )
            // 任务清单
            toolItem(
                option = LocalToolOption.TaskTools,
                icon = HugeIcons.Task01,
                title = R.string.local_tool_task_title,
                description = R.string.local_tool_task_desc,
            )
            // WorkerTools / TeammateTools / SendMessage 不在这里出开关：
            // 这三个枚举没有任何地方消费它们去注册工具，开关拨了不会有
            // 任何效果。给用户一个假的开关比不给更糟。
            // 枚举本身保留，以免破坏已存配置的反序列化。
            // 在聊天里显示本地图片
            toolItem(
                option = LocalToolOption.ShowImage,
                icon = HugeIcons.Image02,
                title = R.string.local_tool_show_image_title,
                description = R.string.local_tool_show_image_desc,
            )
            // 通用文本差异对比
            toolItem(
                option = LocalToolOption.DiffText,
                icon = HugeIcons.Diff,
                title = R.string.local_tool_diff_text_title,
                description = R.string.local_tool_diff_text_desc,
            )
        }
    }
}
