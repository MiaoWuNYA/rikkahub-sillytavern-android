package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
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
import me.rerere.ui.components.Switch
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

// 酒馆助手兼容：对应酒馆助手「渲染」面板的前端卡渲染设置。
@Composable
fun SettingTavernHelperPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val huaDeng = settings.huadengSettings
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_tavern_helper)) },
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
                    title = { Text(stringResource(R.string.tavern_helper_section_render)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.tavern_helper_render_enabled)) },
                        supportingContent = { Text(stringResource(R.string.tavern_helper_render_enabled_desc)) },
                        trailingContent = {
                            Switch(
                                checked = huaDeng.thRenderEnabled,
                                onCheckedChange = {
                                    vm.updateSettings { s ->
                                        s.copy(huadengSettings = s.huadengSettings.copy(thRenderEnabled = it))
                                    }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.tavern_helper_allow_streaming)) },
                        supportingContent = { Text(stringResource(R.string.tavern_helper_allow_streaming_desc)) },
                        trailingContent = {
                            Switch(
                                checked = huaDeng.thAllowStreaming,
                                onCheckedChange = {
                                    vm.updateSettings { s ->
                                        s.copy(huadengSettings = s.huadengSettings.copy(thAllowStreaming = it))
                                    }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.tavern_helper_code_collapse)) },
                        supportingContent = {
                            androidx.compose.foundation.layout.Column {
                                Text(stringResource(R.string.tavern_helper_code_collapse_desc))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CollapseOption("all", R.string.tavern_helper_code_collapse_all, huaDeng.thCodeCollapse, vm)
                                    CollapseOption("frontend_only", R.string.tavern_helper_code_collapse_frontend, huaDeng.thCodeCollapse, vm)
                                    CollapseOption("none", R.string.tavern_helper_code_collapse_none, huaDeng.thCodeCollapse, vm)
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.tavern_helper_use_blob_url)) },
                        supportingContent = { Text(stringResource(R.string.tavern_helper_use_blob_url_desc)) },
                        trailingContent = {
                            Switch(
                                checked = huaDeng.thUseBlobUrl,
                                onCheckedChange = {
                                    vm.updateSettings { s ->
                                        s.copy(huadengSettings = s.huadengSettings.copy(thUseBlobUrl = it))
                                    }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.tavern_helper_macro_enabled)) },
                        supportingContent = { Text(stringResource(R.string.tavern_helper_macro_enabled_desc)) },
                        trailingContent = {
                            Switch(
                                checked = huaDeng.thMacroEnabled,
                                onCheckedChange = {
                                    vm.updateSettings { s ->
                                        s.copy(huadengSettings = s.huadengSettings.copy(thMacroEnabled = it))
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

@Composable
private fun CollapseOption(
    value: String,
    labelRes: Int,
    current: String,
    vm: SettingVM,
) {
    FilterChip(
        selected = current == value,
        onClick = {
            vm.updateSettings { s ->
                s.copy(huadengSettings = s.huadengSettings.copy(thCodeCollapse = value))
            }
        },
        label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium) },
    )
}
