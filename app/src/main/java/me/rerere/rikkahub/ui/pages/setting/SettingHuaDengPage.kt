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
 * 华灯设置：全局兼容 / 辅助功能开关。
 * 包含中转站兼容、防空回复（全局版）、云财教务系统入口。
 */
@Composable
fun SettingHuaDengPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_huadeng)) },
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
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_huadeng_title)) },
                ) {
                    // 中转站兼容（全局）
                    item(
                        headlineContent = { Text(stringResource(R.string.assistant_page_proxy_fix)) },
                        supportingContent = {
                            Text(stringResource(R.string.assistant_page_proxy_fix_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.huadengSettings.enableProxyFix,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings(
                                        settings.copy(
                                            huadengSettings = settings.huadengSettings.copy(
                                                enableProxyFix = enabled,
                                            ),
                                        )
                                    )
                                },
                            )
                        },
                    )
                    // 防空回复（全局版）
                    item(
                        headlineContent = { Text(stringResource(R.string.assistant_page_anti_empty_response)) },
                        supportingContent = {
                            Text(stringResource(R.string.assistant_page_anti_empty_response_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.huadengSettings.enableAntiEmptyResponse,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings(
                                        settings.copy(
                                            huadengSettings = settings.huadengSettings.copy(
                                                enableAntiEmptyResponse = enabled,
                                            ),
                                        )
                                    )
                                },
                            )
                        },
                    )
                    // 云财教务系统
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_ynufe)) },
                        supportingContent = {
                            Text(stringResource(R.string.setting_page_ynufe_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.huadengSettings.enableYnufeTools,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings(
                                        settings.copy(
                                            huadengSettings = settings.huadengSettings.copy(
                                                enableYnufeTools = enabled,
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
