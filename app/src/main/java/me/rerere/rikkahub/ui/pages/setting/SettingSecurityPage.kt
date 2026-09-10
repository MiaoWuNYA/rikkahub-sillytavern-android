package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * 安全设置：工具调用审批策略。
 */
@Composable
fun SettingSecurityPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_security)) },
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
            // ── 工具调用审批 ──
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_security_tool_calls)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.security_force_confirm_tool_calls)) },
                        supportingContent = {
                            Text(stringResource(R.string.security_force_confirm_tool_calls_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.securitySetting.forceConfirmToolCalls,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings(
                                        settings.copy(
                                            securitySetting = settings.securitySetting.copy(
                                                forceConfirmToolCalls = enabled,
                                                // 两项互斥：强制确认开启时关闭自动批准
                                                autoApproveAllTools =
                                                    if (enabled) false else settings.securitySetting.autoApproveAllTools,
                                            ),
                                        )
                                    )
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.security_auto_approve_all_tools)) },
                        supportingContent = {
                            Text(stringResource(R.string.security_auto_approve_all_tools_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.securitySetting.autoApproveAllTools,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings(
                                        settings.copy(
                                            securitySetting = settings.securitySetting.copy(
                                                autoApproveAllTools = enabled,
                                                // 两项互斥：自动批准开启时关闭强制确认
                                                forceConfirmToolCalls =
                                                    if (enabled) false else settings.securitySetting.forceConfirmToolCalls,
                                            ),
                                        )
                                    )
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}
