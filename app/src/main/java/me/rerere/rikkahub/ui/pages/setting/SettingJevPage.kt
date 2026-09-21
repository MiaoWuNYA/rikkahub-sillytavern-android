package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.Brain
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.jev.JEV_DEFAULT_BASE_URL
import me.rerere.rikkahub.data.datastore.HuaDengSettings
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Switch
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
    LaunchedEffect(huaDeng) {
        baseUrl = huaDeng.jevBaseUrl
        apiKey = huaDeng.jevApiKey
    }

    fun updateHuaDeng(newValue: HuaDengSettings) {
        vm.updateSettings(settings.copy(huadengSettings = newValue))
    }

    fun commitBaseUrl() {
        val normalized = baseUrl.trim().ifEmpty { JEV_DEFAULT_BASE_URL }
        if (normalized != huaDeng.jevBaseUrl) {
            updateHuaDeng(huaDeng.copy(jevBaseUrl = normalized))
        }
    }

    fun commitApiKey() {
        val normalized = apiKey.trim()
        if (normalized != huaDeng.jevApiKey) {
            updateHuaDeng(huaDeng.copy(jevApiKey = normalized))
        }
    }

    val configured = huaDeng.jevApiKey.isNotBlank() && huaDeng.jevBaseUrl.isValidHttpUrl()
    val anyTakeover = huaDeng.jevTakeoverTitle || huaDeng.jevTakeoverMemory || huaDeng.jevJudgeTool

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
                            OutlinedTextField(
                                value = baseUrl,
                                onValueChange = { baseUrl = it },
                                placeholder = { Text(JEV_DEFAULT_BASE_URL) },
                                singleLine = true,
                                shape = MaterialTheme.shapes.small,
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                ),
                                trailingIcon = {
                                    ConfirmIconButton(
                                        visible = baseUrl.trim() != huaDeng.jevBaseUrl,
                                        onClick = ::commitBaseUrl,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
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
                                trailingIcon = {
                                    ConfirmIconButton(
                                        visible = apiKey.trim() != huaDeng.jevApiKey,
                                        onClick = ::commitApiKey,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                    )
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
                        headlineContent = { Text(stringResource(R.string.setting_page_jev_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_jev_title_desc)) },
                        trailingContent = {
                            Switch(
                                checked = huaDeng.jevTakeoverTitle,
                                onCheckedChange = { enabled ->
                                    updateHuaDeng(huaDeng.copy(jevTakeoverTitle = enabled))
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

/** 文本框改动没有落盘时露出一个确认按钮，避免每敲一个字符就写一次 DataStore */
@Composable
private fun ConfirmIconButton(visible: Boolean, onClick: () -> Unit) {
    if (!visible) return
    IconButton(onClick = onClick) {
        Icon(
            imageVector = HugeIcons.CheckmarkCircle02,
            contentDescription = stringResource(R.string.common_save),
        )
    }
}
