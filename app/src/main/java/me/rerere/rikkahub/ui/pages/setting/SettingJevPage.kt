package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.Brain
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.jev.JEV_DEFAULT_BASE_URL
import me.rerere.rikkahub.data.ai.jev.JEV_DEFAULT_MODEL
import me.rerere.rikkahub.data.ai.jev.resolveJevEndpoint
import me.rerere.rikkahub.data.datastore.HuaDengSettings
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.ui.components.Switch
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * Jev 智能决策设置页.
 *
 * Jev（TypeSafe System One Model）只做判断、不生成文本，是客户端内部的隐形决策层，
 * 不会出现在用户可选的模型列表里。三个接管开关各自顶掉一块既有功能，被顶掉的那个
 * 入口会同步进入禁用态，避免"开关开了但旧入口还在生效"。
 *
 * Key 没填或地址非法时开关照样能打开，只是实际不生效——这时顶部出黄色提示，
 * 所有请求失败一律静默回退原逻辑。
 */
@Composable
fun SettingJevPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val huaDeng = settings.huadengSettings

    var baseUrl by remember(huaDeng) { mutableStateOf(huaDeng.jevBaseUrl) }
    var apiKey by remember(huaDeng) { mutableStateOf(huaDeng.jevApiKey) }
    var model by remember(huaDeng) { mutableStateOf(huaDeng.jevModel) }
    LaunchedEffect(huaDeng) {
        baseUrl = huaDeng.jevBaseUrl
        apiKey = huaDeng.jevApiKey
        model = huaDeng.jevModel
    }

    fun updateHuaDeng(newValue: HuaDengSettings) {
        vm.updateSettings { it.copy(huadengSettings = newValue) }
    }

    fun commitAll() {
        val url = baseUrl.trim().ifEmpty { JEV_DEFAULT_BASE_URL }
        val key = apiKey.trim()
        val newModel = model.trim().ifEmpty { JEV_DEFAULT_MODEL }
        if (url != huaDeng.jevBaseUrl || key != huaDeng.jevApiKey || newModel != huaDeng.jevModel) {
            updateHuaDeng(huaDeng.copy(jevBaseUrl = url, jevApiKey = key, jevModel = newModel))
        }
    }

    val dirty = baseUrl.trim().ifEmpty { JEV_DEFAULT_BASE_URL } != huaDeng.jevBaseUrl ||
        apiKey.trim() != huaDeng.jevApiKey ||
        model.trim().ifEmpty { JEV_DEFAULT_MODEL } != huaDeng.jevModel
    val configured = huaDeng.jevApiKey.isNotBlank() && huaDeng.jevBaseUrl.isValidHttpUrl()
    val anyTakeover = huaDeng.jevTakeoverMemory || huaDeng.jevJudgeTool
    // 第三方中转/自建代理要照着这个填，所以显示拼好的完整端点而不是只给个域名
    val endpoint = remember(baseUrl) { resolveJevEndpoint(baseUrl) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_jev)) },
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
            // 配置不可用时的黄色提示：开关能开，但不生效
            if (!configured) {
                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        item(
                            leadingContent = {
                                Icon(
                                    imageVector = HugeIcons.Alert02,
                                    contentDescription = null,
                                    tint = JevWarningColor,
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.setting_page_jev_warning_title)) },
                            supportingContent = {
                                Text(stringResource(R.string.setting_page_jev_warning_desc))
                            },
                        )
                    }
                }
            } else if (anyTakeover) {
                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        item(
                            leadingContent = {
                                Icon(imageVector = HugeIcons.Brain, contentDescription = null)
                            },
                            headlineContent = { Text(stringResource(R.string.setting_page_jev_active_title)) },
                            supportingContent = {
                                Text(stringResource(R.string.setting_page_jev_active_desc))
                            },
                        )
                    }
                }
            }

            // 说明
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_jev_about)) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.Brain, null) },
                        headlineContent = { Text(stringResource(R.string.setting_page_jev_what)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_jev_what_desc)) },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_jev_fallback)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_jev_fallback_desc)) },
                    )
                }
            }

            // 接入凭证
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_jev_credentials)) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_jev_base_url)) },
                        supportingContent = {
                            Column {
                                Text(
                                    text = stringResource(R.string.setting_page_jev_base_url_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(R.string.setting_page_jev_endpoint, endpoint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedTextField(
                                    value = baseUrl,
                                    onValueChange = { baseUrl = it },
                                    placeholder = { Text("$JEV_DEFAULT_BASE_URL/v1/systemone") },
                                    singleLine = true,
                                    shape = MaterialTheme.shapes.small,
                                    colors = TextFieldDefaults.colors(
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                    ),
                                    trailingIcon = { CopyEndpointButton(endpoint) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                )
                            }
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_jev_api_key)) },
                        supportingContent = {
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                placeholder = { Text("apikey_...") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                shape = MaterialTheme.shapes.small,
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_jev_model)) },
                        supportingContent = {
                            OutlinedTextField(
                                value = model,
                                onValueChange = { model = it },
                                placeholder = { Text(JEV_DEFAULT_MODEL) },
                                singleLine = true,
                                shape = MaterialTheme.shapes.small,
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                    )
                    formItem {
                        Button(
                            onClick = ::commitAll,
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

            // 阈值
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_jev_threshold)) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_jev_threshold_title)) },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.setting_page_jev_threshold_desc))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = huaDeng.jevConfidenceThreshold,
                                        onValueChange = {
                                            updateHuaDeng(huaDeng.copy(jevConfidenceThreshold = it))
                                        },
                                        valueRange = 0f..1f,
                                        steps = 19,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = "%.2f".format(huaDeng.jevConfidenceThreshold),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                        },
                    )
                }
            }

            // 接管开关
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_jev_takeover)) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_jev_memory)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_jev_memory_desc)) },
                        trailingContent = {
                            Switch(
                                checked = huaDeng.jevTakeoverMemory,
                                onCheckedChange = { enabled ->
                                    updateHuaDeng(huaDeng.copy(jevTakeoverMemory = enabled))
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_jev_tool)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_jev_tool_desc)) },
                        trailingContent = {
                            Switch(
                                checked = huaDeng.jevJudgeTool,
                                onCheckedChange = { enabled ->
                                    updateHuaDeng(huaDeng.copy(jevJudgeTool = enabled))
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

private val JevWarningColor = Color(0xFFF0A020)

private fun String.isValidHttpUrl(): Boolean =
    startsWith("http://") || startsWith("https://")

/**
 * 点一下把完整端点拷走，省得第三方服务后台一个个字符照着敲
 */
@Composable
private fun CopyEndpointButton(endpoint: String) {
    val clipboard = LocalClipboardManager.current
    IconButton(onClick = { clipboard.setText(AnnotatedString(endpoint)) }) {
        Icon(
            imageVector = HugeIcons.Copy01,
            contentDescription = stringResource(R.string.setting_page_jev_endpoint_copy),
        )
    }
}
