package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.HuaDengSettings
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.components.Switch
import org.koin.androidx.compose.koinViewModel

private const val ARGS_TEMPLATE_HINT = "{\"query\": \"\${last_user_message}\"}"

/**
 * 发消息前自动执行 MCP Hook 设置页。
 *
 * 每次提交请求前静默调用指定 MCP 工具，把返回文本拼进当轮请求（不落库），
 * 省掉模型先 tool_call 再往返一轮的延迟；配合长期记忆类 MCP 效果最明显。
 */
@Composable
fun SettingPreRequestHookPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val huaDeng = settings.huadengSettings
    val servers = settings.mcpServers

    var enabled by remember(huaDeng) { mutableStateOf(huaDeng.enablePreRequestHook) }
    var serverId by remember(huaDeng) { mutableStateOf(huaDeng.preRequestHookServerId) }
    var toolName by remember(huaDeng) { mutableStateOf(huaDeng.preRequestHookToolName) }
    var argsTemplate by remember(huaDeng) { mutableStateOf(huaDeng.preRequestHookArgsTemplate) }
    LaunchedEffect(huaDeng) {
        enabled = huaDeng.enablePreRequestHook
        serverId = huaDeng.preRequestHookServerId
        toolName = huaDeng.preRequestHookToolName
        argsTemplate = huaDeng.preRequestHookArgsTemplate
    }

    fun updateHuaDeng(newValue: HuaDengSettings) {
        vm.updateSettings { it.copy(huadengSettings = newValue) }
    }

    val dirty = enabled != huaDeng.enablePreRequestHook ||
        serverId != huaDeng.preRequestHookServerId ||
        toolName.trim() != huaDeng.preRequestHookToolName ||
        argsTemplate.trim() != huaDeng.preRequestHookArgsTemplate

    fun commit() {
        updateHuaDeng(
            huaDeng.copy(
                enablePreRequestHook = enabled,
                preRequestHookServerId = serverId,
                preRequestHookToolName = toolName.trim(),
                preRequestHookArgsTemplate = argsTemplate.trim().ifBlank { "{}" },
            )
        )
    }

    val selectedServer = servers.firstOrNull { it.id.toString() == serverId }
    val tools = selectedServer?.commonOptions?.tools?.filter { it.name.isNotBlank() }.orEmpty()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_pre_request_hook)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_pre_request_hook_about)) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_pre_request_hook_what)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_pre_request_hook_what_desc)) },
                    )
                    switchItem(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        headlineContent = { Text(stringResource(R.string.setting_page_pre_request_hook_enable)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_pre_request_hook_enable_desc)) },
                    )
                }
            }

            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_pre_request_hook_target)) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_pre_request_hook_server)) },
                        supportingContent = {
                            Column {
                                Text(
                                    text = stringResource(R.string.setting_page_pre_request_hook_server_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (servers.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.setting_page_pre_request_hook_no_server),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                } else {
                                    Select(
                                        options = servers,
                                        selectedOption = selectedServer ?: servers.first(),
                                        onOptionSelected = {
                                            serverId = it.id.toString()
                                            toolName = ""
                                        },
                                        optionToString = { it.commonOptions.name.ifBlank { it.id.toString() } },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                    )
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_pre_request_hook_tool)) },
                        supportingContent = {
                            Column {
                                Text(
                                    text = stringResource(R.string.setting_page_pre_request_hook_tool_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (tools.isNotEmpty()) {
                                    Select(
                                        options = tools,
                                        selectedOption = tools.firstOrNull { it.name == toolName } ?: tools.first(),
                                        onOptionSelected = { toolName = it.name },
                                        optionToString = { it.name },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                    )
                                }
                                OutlinedTextField(
                                    value = toolName,
                                    onValueChange = { toolName = it },
                                    placeholder = { Text("tool_name") },
                                    singleLine = true,
                                    shape = MaterialTheme.shapes.small,
                                    colors = TextFieldDefaults.colors(
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                )
                            }
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_pre_request_hook_args)) },
                        supportingContent = {
                            Column {
                                Text(
                                    text = stringResource(R.string.setting_page_pre_request_hook_args_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedTextField(
                                    value = argsTemplate,
                                    onValueChange = { argsTemplate = it },
                                    placeholder = { Text(ARGS_TEMPLATE_HINT) },
                                    shape = MaterialTheme.shapes.small,
                                    colors = TextFieldDefaults.colors(
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                )
                            }
                        },
                    )
                    formItem {
                        Button(
                            onClick = ::commit,
                            enabled = dirty,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                        ) {
                            Text(stringResource(R.string.common_save))
                        }
                    }
                }
            }
        }
    }
}
