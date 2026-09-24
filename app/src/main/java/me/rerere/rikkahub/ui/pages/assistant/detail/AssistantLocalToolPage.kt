package me.rerere.rikkahub.ui.pages.assistant.detail

import android.Manifest
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.permission.PermissionInfo
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.hasUsageStatsPermission
import me.rerere.rikkahub.utils.openUsageAccessSettings
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
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
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_javascript_engine_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_javascript_engine_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.JavascriptEngine),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.JavascriptEngine, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_time_info_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_time_info_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.TimeInfo),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.TimeInfo, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_clipboard_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_clipboard_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Clipboard),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Clipboard, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_tts_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_tts_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Tts),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Tts, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_ask_user_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_ask_user_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.AskUser),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.AskUser, it) }
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.local_tool_python_title)) },
                supportingContent = { Text(stringResource(R.string.local_tool_python_desc)) },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.PythonEngine),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.PythonEngine, it) }
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.local_tool_file_title)) },
                supportingContent = { Text(stringResource(R.string.local_tool_file_desc)) },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.FileTools),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.FileTools, it) }
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.local_tool_shell_cmd_title)) },
                supportingContent = { Text(stringResource(R.string.local_tool_shell_cmd_desc)) },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.ShellTools),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.ShellTools, it) }
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.local_tool_database_title)) },
                supportingContent = { Text(stringResource(R.string.local_tool_database_desc)) },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.DatabaseQuery),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.DatabaseQuery, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_screen_time_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_screen_time_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.ScreenTime),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.ScreenTime, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_calendar_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_calendar_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Calendar),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Calendar, it) }
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.local_tool_device_toolbox_title)) },
                supportingContent = { Text(stringResource(R.string.local_tool_device_toolbox_desc)) },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.DeviceToolbox),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.DeviceToolbox, it) }
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.local_tool_life_companion_title)) },
                supportingContent = { Text(stringResource(R.string.local_tool_life_companion_desc)) },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.LifeCompanion),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.LifeCompanion, it) }
                    )
                }
            )
        }
    }
}
