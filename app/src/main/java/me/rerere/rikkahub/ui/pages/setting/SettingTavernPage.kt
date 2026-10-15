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
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.MessageMultiple01
import me.rerere.hugeicons.stroke.Notebook
import me.rerere.hugeicons.stroke.Package
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus

/** 酒馆设置：只收酒馆上游同款菜单，兼容与自动化仍在华灯设置里。 */
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
                        onClick = { navController.navigate(Screen.SettingTavernRegex) },
                        leadingContent = { CardGroupIcon(HugeIcons.MagicWand01) },
                        supportingContent = { Text(stringResource(R.string.setting_page_tavern_regex_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_tavern_regex)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.QuickMessages) },
                        leadingContent = { CardGroupIcon(HugeIcons.Message01) },
                        supportingContent = { Text(stringResource(R.string.extensions_page_quick_messages_desc)) },
                        headlineContent = { Text(stringResource(R.string.assistant_page_quick_messages)) },
                    )
                }
            }

            item("tavernExtensions") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.tavern_section_extensions)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.Extensions) },
                        leadingContent = { CardGroupIcon(HugeIcons.Package) },
                        supportingContent = { Text(stringResource(R.string.setting_page_extensions_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_extensions)) },
                    )
                }
            }
        }
    }
}
