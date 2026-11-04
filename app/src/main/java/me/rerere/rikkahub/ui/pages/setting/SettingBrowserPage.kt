package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.browser.LoginVault
import me.rerere.rikkahub.browser.SnapshotDetail
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.components.Switch
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingBrowserPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val vault = remember { LoginVault(context) }
    var savedUser by remember { mutableStateOf(vault.load()?.masked) }
    val browser = settings.browserSettings

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_browser_title)) },
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
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_browser_enabled)) },
                        supportingContent = { Text(stringResource(R.string.setting_browser_enabled_desc)) },
                        trailingContent = {
                            Switch(
                                checked = browser.enabled,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings { it.copy(browserSettings = it.browserSettings.copy(enabled = enabled)) }
                                },
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_browser_permissions)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_browser_allow_open_close)) },
                        trailingContent = {
                            Switch(
                                checked = browser.allowOpenClose,
                                onCheckedChange = { value ->
                                    vm.updateSettings {
                                        it.copy(browserSettings = it.browserSettings.copy(allowOpenClose = value))
                                    }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_browser_allow_screenshot)) },
                        trailingContent = {
                            Switch(
                                checked = browser.allowScreenshot,
                                onCheckedChange = { value ->
                                    vm.updateSettings {
                                        it.copy(browserSettings = it.browserSettings.copy(allowScreenshot = value))
                                    }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_browser_allow_full_page)) },
                        supportingContent = { Text(stringResource(R.string.setting_browser_allow_full_page_desc)) },
                        trailingContent = {
                            Switch(
                                checked = browser.allowFullPageScreenshot,
                                onCheckedChange = { value ->
                                    vm.updateSettings {
                                        it.copy(browserSettings = it.browserSettings.copy(allowFullPageScreenshot = value))
                                    }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_browser_allow_form_submit)) },
                        supportingContent = { Text(stringResource(R.string.setting_browser_allow_form_submit_desc)) },
                        trailingContent = {
                            Switch(
                                checked = browser.allowFormSubmit,
                                onCheckedChange = { value ->
                                    vm.updateSettings {
                                        it.copy(browserSettings = it.browserSettings.copy(allowFormSubmit = value))
                                    }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_browser_allow_eval)) },
                        trailingContent = {
                            Switch(
                                checked = browser.allowEval,
                                onCheckedChange = { value ->
                                    vm.updateSettings {
                                        it.copy(browserSettings = it.browserSettings.copy(allowEval = value))
                                    }
                                },
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_browser_privacy)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_browser_block_sensitive)) },
                        supportingContent = { Text(stringResource(R.string.setting_browser_block_sensitive_desc)) },
                        trailingContent = {
                            Switch(
                                checked = browser.blockSensitiveScreenshots,
                                onCheckedChange = { value ->
                                    vm.updateSettings {
                                        it.copy(browserSettings = it.browserSettings.copy(blockSensitiveScreenshots = value))
                                    }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_browser_taobao_rules)) },
                        supportingContent = { Text(stringResource(R.string.setting_browser_taobao_rules_desc)) },
                        trailingContent = {
                            Switch(
                                checked = browser.taobaoRulesEnabled,
                                onCheckedChange = { value ->
                                    vm.updateSettings {
                                        it.copy(browserSettings = it.browserSettings.copy(taobaoRulesEnabled = value))
                                    }
                                },
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_browser_sites)) },
                ) {
                    formItem(
                        headlineContent = { Text(stringResource(R.string.setting_browser_allow_list)) },
                        supportingContent = { Text(stringResource(R.string.setting_browser_domains_hint)) },
                    ) {
                        OutlinedTextField(
                            value = browser.domainAllowList,
                            onValueChange = { value ->
                                vm.updateSettings {
                                    it.copy(browserSettings = it.browserSettings.copy(domainAllowList = value))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                    }
                    formItem(
                        headlineContent = { Text(stringResource(R.string.setting_browser_block_list)) },
                        supportingContent = { Text(stringResource(R.string.setting_browser_domains_hint)) },
                    ) {
                        OutlinedTextField(
                            value = browser.domainBlockList,
                            onValueChange = { value ->
                                vm.updateSettings {
                                    it.copy(browserSettings = it.browserSettings.copy(domainBlockList = value))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_browser_performance)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_browser_snapshot_detail)) },
                        supportingContent = { Text(stringResource(R.string.setting_browser_snapshot_detail_desc)) },
                        trailingContent = {
                            Text(
                                text = stringResource(
                                    when (browser.snapshotDetail) {
                                        SnapshotDetail.COMPACT -> R.string.setting_browser_snapshot_compact
                                        SnapshotDetail.STANDARD -> R.string.setting_browser_snapshot_standard
                                        SnapshotDetail.DETAILED -> R.string.setting_browser_snapshot_detailed
                                    }
                                )
                            )
                        },
                        onClick = {
                            val next = when (browser.snapshotDetail) {
                                SnapshotDetail.COMPACT -> SnapshotDetail.STANDARD
                                SnapshotDetail.STANDARD -> SnapshotDetail.DETAILED
                                SnapshotDetail.DETAILED -> SnapshotDetail.COMPACT
                            }
                            vm.updateSettings {
                                it.copy(browserSettings = it.browserSettings.copy(snapshotDetail = next))
                            }
                        },
                    )
                    formItem(
                        headlineContent = { Text(stringResource(R.string.setting_browser_idle_timeout)) },
                        supportingContent = { Text(stringResource(R.string.setting_browser_idle_timeout_desc)) },
                    ) {
                        OutlinedTextField(
                            value = browser.idleTimeoutMinutes.toString(),
                            onValueChange = { value ->
                                val minutes = value.filter { it.isDigit() }.toIntOrNull() ?: return@OutlinedTextField
                                vm.updateSettings {
                                    it.copy(
                                        browserSettings = it.browserSettings.copy(idleTimeoutMinutes = minutes.coerceIn(0, 1440))
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                    formItem(
                        headlineContent = { Text(stringResource(R.string.setting_browser_keep_shots)) },
                    ) {
                        OutlinedTextField(
                            value = browser.keepScreenshots.toString(),
                            onValueChange = { value ->
                                val keep = value.filter { it.isDigit() }.toIntOrNull() ?: return@OutlinedTextField
                                vm.updateSettings {
                                    it.copy(
                                        browserSettings = it.browserSettings.copy(keepScreenshots = keep.coerceIn(0, 200))
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_browser_logs)) },
                        supportingContent = { Text(stringResource(R.string.setting_browser_logs_desc)) },
                        trailingContent = {
                            Switch(
                                checked = browser.showToolLog,
                                onCheckedChange = { value ->
                                    vm.updateSettings {
                                        it.copy(browserSettings = it.browserSettings.copy(showToolLog = value))
                                    }
                                },
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_browser_session)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_browser_saved_account)) },
                        supportingContent = {
                            Text(savedUser ?: stringResource(R.string.setting_browser_no_account))
                        },
                        onClick = {
                            vault.clear()
                            savedUser = null
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_browser_clear_cookies)) },
                        supportingContent = { Text(stringResource(R.string.setting_browser_clear_cookies_desc)) },
                        onClick = {
                            val cookies = android.webkit.CookieManager.getInstance()
                            cookies.removeAllCookies(null)
                            cookies.flush()
                        },
                    )
                }
            }
        }
    }
}
