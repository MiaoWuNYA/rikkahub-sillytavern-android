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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.AlarmClock
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Brain01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.MessageMultiple01
import me.rerere.hugeicons.stroke.Notebook
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus

/** 酒馆设置：角色卡、世界书、人设与兼容开关统一从这一页进。 */
@Composable
fun SettingTavernPage() {
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_tavern)) },
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
            item("tavernCards") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.tavern_section_cards)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.Assistant) },
                        leadingContent = { CardGroupIcon(HugeIcons.LookTop) },
                        supportingContent = { Text(stringResource(R.string.setting_page_assistant_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_assistant)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Persona) },
                        leadingContent = { CardGroupIcon(HugeIcons.Edit01) },
                        supportingContent = { Text(stringResource(R.string.setting_page_persona_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_persona)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.AuthorsNote) },
                        leadingContent = { CardGroupIcon(HugeIcons.Notebook) },
                        supportingContent = { Text(stringResource(R.string.setting_page_authors_note_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_authors_note)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.GroupChatList) },
                        leadingContent = { CardGroupIcon(HugeIcons.MessageMultiple01) },
                        supportingContent = { Text(stringResource(R.string.setting_page_group_chat_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_group_chat)) },
                    )
                }
            }

            item("tavernPrompts") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.tavern_section_prompts)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.Prompts) },
                        leadingContent = { CardGroupIcon(HugeIcons.Book01) },
                        supportingContent = { Text(stringResource(R.string.extensions_page_prompts_desc)) },
                        headlineContent = { Text(stringResource(R.string.extensions_page_prompts)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.QuickMessages) },
                        leadingContent = { CardGroupIcon(HugeIcons.Message01) },
                        supportingContent = { Text(stringResource(R.string.extensions_page_quick_messages_desc)) },
                        headlineContent = { Text(stringResource(R.string.assistant_page_quick_messages)) },
                    )
                }
            }

            item("tavernCompat") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.tavern_section_compat)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingHuaDengCompat) },
                        leadingContent = { CardGroupIcon(HugeIcons.Settings03) },
                        supportingContent = { Text(stringResource(R.string.setting_page_huadeng_title_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_huadeng_compat)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingHuaDeng) },
                        leadingContent = { CardGroupIcon(HugeIcons.AiMagic) },
                        supportingContent = { Text(stringResource(R.string.setting_page_huadeng_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_huadeng)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingJev) },
                        leadingContent = { CardGroupIcon(HugeIcons.Brain01) },
                        supportingContent = { Text(stringResource(R.string.setting_page_jev_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_jev)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingSecurity) },
                        leadingContent = { CardGroupIcon(HugeIcons.Shield01) },
                        supportingContent = { Text(stringResource(R.string.setting_page_security_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_security)) },
                    )
                }
            }

            item("tavernAutomation") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.tavern_section_automation)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingProactiveMessage) },
                        leadingContent = { CardGroupIcon(HugeIcons.AlarmClock) },
                        supportingContent = { Text(stringResource(R.string.setting_page_proactive_message_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_proactive_message)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingWeixinBot) },
                        leadingContent = { CardGroupIcon(HugeIcons.Message01) },
                        supportingContent = { Text(stringResource(R.string.setting_page_weixin_bot_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_weixin_bot)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingQqBot) },
                        leadingContent = { CardGroupIcon(HugeIcons.MessageMultiple01) },
                        supportingContent = { Text(stringResource(R.string.setting_page_qq_bot_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_qq_bot)) },
                    )
                }
            }
        }
    }
}
