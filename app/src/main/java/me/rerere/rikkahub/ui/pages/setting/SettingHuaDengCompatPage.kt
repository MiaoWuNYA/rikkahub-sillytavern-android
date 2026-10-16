package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

// 兼容与辅助：连接修复、空响应兜底、内容裁剪、工具结果截断等全局开关。
@Composable
fun SettingHuaDengCompatPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_huadeng_title)) },
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
                    item(
                        headlineContent = { Text(stringResource(R.string.assistant_page_proxy_fix)) },
                        supportingContent = {
                            Text(stringResource(R.string.assistant_page_proxy_fix_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.huadengSettings.enableProxyFix,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings {
                                        it.copy(
                                            huadengSettings = it.huadengSettings.copy(
                                                enableProxyFix = enabled,
                                            ),
                                        )
                                    }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.assistant_page_anti_empty_response)) },
                        supportingContent = {
                            Text(stringResource(R.string.assistant_page_anti_empty_response_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.huadengSettings.enableAntiEmptyResponse,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings {
                                        it.copy(
                                            huadengSettings = it.huadengSettings.copy(
                                                enableAntiEmptyResponse = enabled,
                                            ),
                                        )
                                    }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.huadeng_transient_prune)) },
                        supportingContent = {
                            Text(stringResource(R.string.huadeng_transient_prune_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.huadengSettings.enableTransientContentPrune,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings {
                                        it.copy(
                                            huadengSettings = it.huadengSettings.copy(
                                                enableTransientContentPrune = enabled,
                                            ),
                                        )
                                    }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.huadeng_clean_mode)) },
                        supportingContent = {
                            Text(stringResource(R.string.huadeng_clean_mode_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.huadengSettings.enableCleanMode,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings {
                                        it.copy(
                                            huadengSettings = it.huadengSettings.copy(
                                                enableCleanMode = enabled,
                                            ),
                                        )
                                    }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.huadeng_tool_result_truncation)) },
                        supportingContent = {
                            Text(stringResource(R.string.huadeng_tool_result_truncation_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.huadengSettings.enableToolResultTruncation,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings {
                                        it.copy(
                                            huadengSettings = it.huadengSettings.copy(
                                                enableToolResultTruncation = enabled,
                                            ),
                                        )
                                    }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.huadeng_tavern_mode)) },
                        supportingContent = {
                            Text(stringResource(R.string.huadeng_tavern_mode_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.huadengSettings.enableTavernMode,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings {
                                        it.copy(
                                            huadengSettings = it.huadengSettings.copy(
                                                enableTavernMode = enabled,
                                            ),
                                        )
                                    }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.huadeng_tavern_keep_tools)) },
                        supportingContent = {
                            Text(stringResource(R.string.huadeng_tavern_keep_tools_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.huadengSettings.tavernModeKeepTools,
                                // 仅在酒馆模式开启时可用，避免误认为独立功能
                                enabled = settings.huadengSettings.enableTavernMode,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings {
                                        it.copy(
                                            huadengSettings = it.huadengSettings.copy(
                                                tavernModeKeepTools = enabled,
                                            ),
                                        )
                                    }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.huadeng_system_prompt_escape)) },
                        supportingContent = {
                            Text(stringResource(R.string.huadeng_system_prompt_escape_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.huadengSettings.enableSystemPromptEscape,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings {
                                        it.copy(
                                            huadengSettings = it.huadengSettings.copy(
                                                enableSystemPromptEscape = enabled,
                                            ),
                                        )
                                    }
                                },
                            )
                        },
                    )
                    // ── 通用能力 ──
                    item(
                        headlineContent = { Text(stringResource(R.string.huadeng_python_hints_title)) },
                        supportingContent = {
                            Text(stringResource(R.string.huadeng_python_hints_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.huadengSettings.enablePythonLibraryHints,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings {
                                        it.copy(
                                            huadengSettings = it.huadengSettings.copy(
                                                enablePythonLibraryHints = enabled,
                                            ),
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}
