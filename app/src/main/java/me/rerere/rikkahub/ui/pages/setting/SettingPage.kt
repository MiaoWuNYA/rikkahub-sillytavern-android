package me.rerere.rikkahub.ui.pages.setting

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.BookOpen02
import me.rerere.hugeicons.stroke.BookmarkAdd01
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Clapping01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.ImageUpload
import me.rerere.hugeicons.stroke.InLove
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.GraduationCap
import me.rerere.hugeicons.stroke.MessageMultiple01
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Moon02
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.Tiktok
import me.rerere.hugeicons.stroke.WavingHand01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.isNotConfigured
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.rememberColorMode
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.joinQQGroup
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.writeClipboardText
import me.rerere.ui.icons.TencentQQIcon
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import androidx.compose.runtime.LaunchedEffect

@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val launchCount by vm.launchCount.collectAsStateWithLifecycle()
    val filesManager: FilesManager = koinInject()
    val context = LocalContext.current

    // 公告在**进入设置主界面**时弹出。
    //
    // 不放启动：用户一打开软件就被一张二维码拦住，而他可能只是想让 AI
    // 继续刚才那段话。公告是「有空时想了解的信息」，不是启动阻塞项。
    //
    // 设置页是个自然的落点：用户主动进来、心态是「我来看看有什么」，
    // 这时候告诉他有反馈群正合适。启动流程因此完全不受公告影响。
    AnnouncementHost()

    if (!settings.init && launchCount > 100 && (launchCount - settings.sponsorAlertDismissedAt) >= 50) {
        AlertDialog(
            onDismissRequest = {
                vm.updateSettings { it.copy(sponsorAlertDismissedAt = launchCount) }
            },
            icon = { Icon(HugeIcons.WavingHand01, null) },
            title = { Text(stringResource(R.string.setting_page_sponsor_alert_title)) },
            text = { Text(stringResource(R.string.setting_page_sponsor_alert_desc)) },
            confirmButton = {
                Button(onClick = {
                    vm.updateSettings { it.copy(sponsorAlertDismissedAt = launchCount) }
                    // 直接开仓库，不走捐赠页。
                    //
                    // 「支持」这件事本来就不必花钱：点个 Star 对项目的帮助
                    // 更实在，而且不打扰用户。捐赠页仍然保留在设置里，
                    // 有需要的人自己找得到。
                    context.openUrl("https://rikkahub.luoca.cn")
                }) {
                    Text(stringResource(R.string.setting_page_sponsor_alert_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.updateSettings { it.copy(sponsorAlertDismissedAt = launchCount) }
                }) {
                    Text(stringResource(R.string.setting_page_sponsor_alert_dismiss))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(text = stringResource(R.string.settings))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (settings.isNotConfigured()) {
                item {
                    ProviderConfigWarningCard(
                        onClick = { navController.navigate(Screen.SettingProvider) }
                    )
                }
            }

            item("generalSettings") {
                val containerColor = MaterialTheme.colorScheme.primaryContainer
                val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_general_settings)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingPreferencesTheme) },
                        leadingContent = { CardGroupIcon(HugeIcons.Sun01, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_preferences_theme_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_preferences_theme)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingPreferencesGeneral) },
                        leadingContent = { CardGroupIcon(HugeIcons.Settings03, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_preferences_general_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_preferences_general)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Assistant) },
                        leadingContent = { CardGroupIcon(HugeIcons.LookTop, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_assistant_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_assistant)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Extensions) },
                        leadingContent = { CardGroupIcon(HugeIcons.Package, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_extensions_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_extensions)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingHuaDeng) },
                        leadingContent = { CardGroupIcon(HugeIcons.Settings03, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_huadeng_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_huadeng)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingTavern) },
                        leadingContent = { CardGroupIcon(HugeIcons.BookOpen02, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_tavern_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_tavern)) },
                    )
                }
            }

            item("modelServices") {
                val containerColor = MaterialTheme.colorScheme.tertiaryContainer
                val contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_model_and_services)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingModels) },
                        leadingContent = { CardGroupIcon(HugeIcons.AiMagic, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_default_model_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_default_model)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingProvider) },
                        leadingContent = { CardGroupIcon(HugeIcons.Brain02, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_providers_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_providers)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingSearch) },
                        leadingContent = { CardGroupIcon(HugeIcons.GlobalSearch, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_search_service_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_search_service)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingSpeech) },
                        leadingContent = { CardGroupIcon(HugeIcons.Megaphone01, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_tts_service_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_tts_service)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingMedia) },
                        leadingContent = { CardGroupIcon(HugeIcons.Image02, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_media_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_media)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingWeb) },
                        leadingContent = { CardGroupIcon(HugeIcons.ServerStack01, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_web_server_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server)) },
                    )
                }
            }

            item("dataSettings") {
                val storageState by produceState(-1 to 0L) {
                    value = filesManager.countChatFiles()
                }
                val containerColor = MaterialTheme.colorScheme.secondaryContainer
                val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_data_settings)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.Backup) },
                        leadingContent = { CardGroupIcon(HugeIcons.Database02, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_data_backup_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_data_backup)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingFiles) },
                        leadingContent = { CardGroupIcon(HugeIcons.ImageUpload, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = {
                            if (storageState.first == -1) {
                                Text(stringResource(R.string.calculating))
                            } else {
                                Text(
                                    stringResource(
                                        R.string.setting_page_chat_storage_desc,
                                        storageState.first,
                                        storageState.second / 1024 / 1024.0
                                    )
                                )
                            }
                        },
                        headlineContent = { Text(stringResource(R.string.setting_page_chat_storage)) },
                    )
                }
            }

            item("aboutSettings") {
                val context = LocalContext.current
                val containerColor = MaterialTheme.colorScheme.primaryContainer
                val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_about)) },
                ) {
                    // 允许重看公告。
                    //
                    // 「不再提示」是个一旦点上就再也回不来的开关，
                    // 用户手滑点了之后得有地方能找回来。
                    item(
                        onClick = {
                            me.rerere.rikkahub.data.service.AnnouncementManager.reset(context)
                            Toast.makeText(
                                context,
                                "公告已重置，下次启动会重新显示",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        leadingContent = { CardGroupIcon(HugeIcons.Clapping01, containerColor = containerColor, contentColor = contentColor) },
                        headlineContent = { Text("公告") },
                        supportingContent = { Text("重置「不再提示」，下次启动重新显示") },
                    )
                    // 允许重看引导。
                    //
                    // 首次引导里那个「我自己配，别烦我」是必要的出口，
                    // 但用户手滑点了、或者当时没看懂，之后得能找回来。
                    // 没有这个入口的话，跳过就等于永久失去引导。
                    item(
                        onClick = { navController.navigate(Screen.Onboarding) },
                        leadingContent = { CardGroupIcon(HugeIcons.Clapping01, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text("重新看一遍新手引导") },
                        headlineContent = { Text("新手引导") },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingAbout) },
                        leadingContent = { CardGroupIcon(HugeIcons.Clapping01, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_about_desc)) },
                        trailingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                var showQQGroupSheet by remember { mutableStateOf(false) }
                                IconButton(
                                    onClick = { showQQGroupSheet = true }
                                ) {
                                    Icon(
                                        imageVector = TencentQQIcon,
                                        contentDescription = "QQ",
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                if (showQQGroupSheet) {
                                    QQGroupBottomSheet(
                                        onDismiss = { showQQGroupSheet = false }
                                    )
                                }
                            }
                        },
                        headlineContent = { Text(stringResource(R.string.setting_page_about)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Log) },
                        leadingContent = { CardGroupIcon(HugeIcons.Bookshelf01, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_request_logs_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_request_logs)) },
                    )
                }
            }
        }
    }

}

private fun ColorMode.icon(): ImageVector = when (this) {
    ColorMode.SYSTEM -> HugeIcons.SmartPhone01
    ColorMode.LIGHT -> HugeIcons.Sun01
    ColorMode.DARK -> HugeIcons.Moon02
}

@Composable
private fun ProviderConfigWarningCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(MaterialTheme.colorScheme.error, MaterialShapes.Cookie9Sided.toShape()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HugeIcons.Alert01,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onError,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_page_config_api_title),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                    )
                    Text(
                        text = stringResource(R.string.setting_page_config_api_desc),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Button(
                onClick = onClick,
                modifier = Modifier.align(Alignment.End),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.setting_page_config))
            }
        }
    }
}

private data class QQGroup(
    val name: String,
    val key: String? = null,
    val number: String? = null,
    val icon: ImageVector = TencentQQIcon,
)

private val QQ_GROUPS = listOf(
    QQGroup("华灯反馈群", number = "1129035651"),
)

@Composable
private fun QQGroupBottomSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            QQ_GROUPS.forEach { group ->
                ListItem(
                    onClick = {
                        if (group.number != null) {
                            context.writeClipboardText(group.number)
                            Toast.makeText(context, "群号已复制", Toast.LENGTH_SHORT).show()
                        } else {
                            context.joinQQGroup(group.key)
                        }
                        onDismiss()
                    },
                    supportingContent = group.number?.let { number ->
                        { Text(number) }
                    },
                    leadingContent = {
                        Icon(
                            imageVector = group.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    },
                ) {
                    Text(group.name)
                }
            }
        }
    }
}


/**
 * 公告的加载与展示。
 *
 * 挂在设置页而不是启动流程：用户点进设置时心态是「来看看有什么」，
 * 这时候弹公告正合适；而启动时弹会拦住只想继续聊天的人。
 *
 * 加载与展示都**不阻塞**：读不到、格式坏了、图片缺失，都只是不弹，
 * 不会报错也不会有空白。
 */
@Composable
private fun AnnouncementHost() {
    val context = LocalContext.current
    var announcement by remember { mutableStateOf<me.rerere.rikkahub.data.model.Announcement?>(null) }

    LaunchedEffect(Unit) {
        val loaded = me.rerere.rikkahub.data.service.AnnouncementManager.load(context)
        if (loaded != null &&
            !me.rerere.rikkahub.data.service.AnnouncementManager.isDismissed(context, loaded.id)
        ) {
            announcement = loaded
        }
    }

    val current = announcement
    if (current != null) {
        me.rerere.rikkahub.ui.components.ui.AnnouncementDialog(
            announcement = current,
            onDismiss = { dontShowAgain ->
                // 「知道了」也要记 1 小时静默，否则下次进来立刻又弹
                val mgr = me.rerere.rikkahub.data.service.AnnouncementManager
                if (dontShowAgain && current.dismissible) {
                    mgr.dismissForever(context, current.id)
                } else {
                    mgr.dismiss(context, current.id)
                }
                announcement = null
            },
        )
    }
}
