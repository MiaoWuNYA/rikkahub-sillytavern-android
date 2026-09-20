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
                        headlineContent = { Text("上下文瞬态内容裁剪") },
                        supportingContent = {
                            Text("超过两轮对话之前的网页搜索结果、图片、音视频不再随每次请求发送（占位说明附带消息 ID，AI 可通过 read_history_message 工具按需取回原文），大幅减少图片与搜索类长对话的 token 消耗；消息存储与聊天记录显示不受影响")
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
                        headlineContent = { Text("清爽简洁模式") },
                        supportingContent = {
                            Text("开启后隐藏情侣空间、生活空间等娱乐功能入口，并且不再向 AI 注册对应工具，界面更简洁、上下文更省 token")
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
                        headlineContent = { Text("上下文滚动压缩") },
                        supportingContent = {
                            Text("对话过长时自动将早期消息压缩为摘要以节省 token。关闭后不再自动压缩（助手级开关仍可单独启用）；若遇到每轮都重复压缩或缓存失效，可尝试关闭")
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
                        headlineContent = { Text("工具结果截断") },
                        supportingContent = {
                            Text("工具输出超过 32KB 时自动截断并保存到文件。关闭后工具结果不截断，完整内容保留在消息历史中（若中转站导致工具调用异常，可尝试关闭)")
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
                        headlineContent = { Text("系统提示词转义") },
                        supportingContent = {
                            Text("将系统消息中的 < > 转为 HTML 实体，绕过中转站 WAF 安全策略拦截（如遇到 upstream_content_rejected 错误可开启）。一般不需要开启")
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
                    title = { Text("接入与自动化") },
                ) {
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
