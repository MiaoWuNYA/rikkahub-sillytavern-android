package me.rerere.rikkahub.ui.pages.setting

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlarmClock
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Brain01
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.MessageMultiple01
import me.rerere.hugeicons.stroke.Notebook
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.rikkahub.Screen
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * 华灯设置：全局兼容与辅助功能。
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
            // ── 兼容与辅助 ──
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
                    item(
                        headlineContent = { Text(stringResource(R.string.huadeng_transient_prune)) },
                        supportingContent = {
                            Text(stringResource(R.string.huadeng_transient_prune_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.huadengSettings.enableTransientContentPrune,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings(
                                        settings.copy(
                                            huadengSettings = settings.huadengSettings.copy(
                                                enableTransientContentPrune = enabled,
                                            ),
                                        )
                                    )
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
                                    vm.updateSettings(
                                        settings.copy(
                                            huadengSettings = settings.huadengSettings.copy(
                                                enableCleanMode = enabled,
                                            ),
                                        )
                                    )
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.huadeng_rolling_compression)) },
                        supportingContent = {
                            Text(stringResource(R.string.huadeng_rolling_compression_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.huadengSettings.enableRollingContextCompression,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings(
                                        settings.copy(
                                            huadengSettings = settings.huadengSettings.copy(
                                                enableRollingContextCompression = enabled,
                                            ),
                                        )
                                    )
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
                                    vm.updateSettings(
                                        settings.copy(
                                            huadengSettings = settings.huadengSettings.copy(
                                                enableToolResultTruncation = enabled,
                                            ),
                                        )
                                    )
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
                                    vm.updateSettings(
                                        settings.copy(
                                            huadengSettings = settings.huadengSettings.copy(
                                                enableSystemPromptEscape = enabled,
                                            ),
                                        )
                                    )
                                },
                            )
                        },
                    )
                }
            }

            // ── 接入与自动化 ──
            item {
                val navController = LocalNavController.current
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.huadeng_section_integration)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingJev) },
                        leadingContent = { Icon(HugeIcons.Brain01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_jev_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_jev)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingWeixinBot) },
                        leadingContent = { Icon(HugeIcons.Message01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_weixin_bot_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_weixin_bot)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingQqBot) },
                        leadingContent = { Icon(HugeIcons.MessageMultiple01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_qq_bot_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_qq_bot)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingProactiveMessage) },
                        leadingContent = { Icon(HugeIcons.AlarmClock, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_proactive_message_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_proactive_message)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingSecurity) },
                        leadingContent = { Icon(HugeIcons.Shield01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_security_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_security)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.AuthorsNote) },
                        leadingContent = { Icon(HugeIcons.Notebook, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_authors_note_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_authors_note)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
                    )
                }
            }

        }
    }
}
