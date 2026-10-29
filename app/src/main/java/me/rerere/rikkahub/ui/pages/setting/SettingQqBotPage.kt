package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Message01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.QqBotSetting
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.service.QqBotService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.RiskConfirmDialog
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * QQ Bot 设置页.
 *
 * 比微信页简单: 不用扫码, 直接填 AppID + AppSecret (在 q.qq.com 注册机器人后获得).
 * 固定用当前助手.
 */
@Composable
fun SettingQqBotPage(vm: SettingVM = koinViewModel()) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    var botSetting by remember(settings) { mutableStateOf(settings.qqBotSetting) }
    LaunchedEffect(settings) { botSetting = settings.qqBotSetting }
    var showAssistantPicker by remember { mutableStateOf(false) }
    val linkedAssistant = settings.assistants.find { it.id.toString() == botSetting.assistantId }
        ?: settings.getCurrentAssistant()

    fun update(newSetting: QqBotSetting) {
        botSetting = newSetting
        vm.updateSettings { it.copy(qqBotSetting = newSetting) }
    }

    var showEnableRiskDialog by remember { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    if (showEnableRiskDialog) {
        RiskConfirmDialog(
            title = stringResource(R.string.risk_qq_bot_title),
            message = stringResource(R.string.risk_qq_bot_message),
            onConfirm = {
                showEnableRiskDialog = false
                update(botSetting.copy(enabled = true))
                QqBotService.start(context)
            },
            onDismiss = { showEnableRiskDialog = false }
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.qq_bot_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 说明
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.qq_bot_section_about)) },
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    item(
                        leadingContent = { Icon(imageVector = HugeIcons.Message01, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.qq_bot_what_is)) },
                        supportingContent = { Text(stringResource(R.string.qq_bot_what_is_desc)) }
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.qq_bot_how_to_get_credentials)) },
                        supportingContent = { Text(stringResource(R.string.qq_bot_how_to_get_credentials_desc)) }
                    )
                    item(
                        onClick = { showAssistantPicker = true },
                        headlineContent = { Text(stringResource(R.string.qq_bot_linked_assistant)) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.qq_bot_linked_assistant_desc,
                                    linkedAssistant.name.ifBlank { stringResource(R.string.weixin_bot_unnamed) }
                                )
                            )
                        },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) }
                    )
                }
            }

            // 凭证
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.qq_bot_section_credentials)) },
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    item(
                        headlineContent = { Text("AppID") },
                        supportingContent = {
                            OutlinedTextField(
                                value = botSetting.appId,
                                onValueChange = { update(botSetting.copy(appId = it.trim())) },
                                placeholder = { Text(stringResource(R.string.qq_bot_appid_placeholder)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = MaterialTheme.shapes.small,
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                )
                            )
                        }
                    )
                    item(
                        headlineContent = { Text("AppSecret") },
                        supportingContent = {
                            OutlinedTextField(
                                value = botSetting.appSecret,
                                onValueChange = { update(botSetting.copy(appSecret = it.trim())) },
                                placeholder = { Text(stringResource(R.string.qq_bot_secret_placeholder)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                shape = MaterialTheme.shapes.small,
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                )
                            )
                        }
                    )
                }
            }

            // 开关
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.qq_bot_section_run)) },
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.qq_bot_enable)) },
                        supportingContent = { Text(stringResource(R.string.qq_bot_enable_desc)) },
                        trailingContent = {
                            Switch(
                                checked = botSetting.enabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        showEnableRiskDialog = true
                                    } else {
                                        update(botSetting.copy(enabled = false))
                                        QqBotService.stop(context)
                                    }
                                }
                            )
                        }
                    )
                    if (botSetting.enabled && (botSetting.appId.isBlank() || botSetting.appSecret.isBlank())) {
                        item(
                            headlineContent = { Text(stringResource(R.string.qq_bot_credentials_missing_warning)) },
                            supportingContent = { Text(stringResource(R.string.qq_bot_credentials_missing_desc)) }
                        )
                    }
                    if (botSetting.enabled) {
                        item(
                            headlineContent = { Text(stringResource(R.string.qq_bot_run_hint)) },
                            supportingContent = { Text(stringResource(R.string.qq_bot_run_hint_desc)) }
                        )
                    }
                }
            }
        }
    }

    if (showAssistantPicker) {
        AlertDialog(
            onDismissRequest = { showAssistantPicker = false },
            title = { Text(stringResource(R.string.qq_bot_linked_assistant)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    settings.assistants.forEach { assistant ->
                        val selected = assistant.id == linkedAssistant.id
                        Surface(
                            onClick = {
                                update(botSetting.copy(assistantId = assistant.id.toString()))
                                showAssistantPicker = false
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                UIAvatar(
                                    name = assistant.name,
                                    value = assistant.avatar,
                                    modifier = Modifier.size(32.dp),
                                )
                                Text(
                                    text = assistant.name.ifBlank { stringResource(R.string.weixin_bot_unnamed) },
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                RadioButton(selected = selected, onClick = null)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAssistantPicker = false }) {
                    Text(stringResource(R.string.group_cancel))
                }
            },
        )
    }
}
