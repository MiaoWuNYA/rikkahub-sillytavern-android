package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.PaintBoard
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Sun01
import androidx.annotation.StringRes
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.data.datastore.BackgroundEffectType
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.PagerTab
import me.rerere.rikkahub.ui.components.ui.PagerTabs
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingPreferencesThemePage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pagerState = rememberPagerState { ThemeTab.entries.size }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_theme))
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            PagerTabs(
                pagerState = pagerState,
                tabs = ThemeTab.entries,
                modifier = Modifier.padding(bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            )

            val contentPadding = PaddingValues(
                start = 16.dp,
                top = 8.dp,
                end = 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                when (ThemeTab.entries[page]) {
                    ThemeTab.THEME -> ThemeContent(vm = vm, contentPadding = contentPadding)
                    ThemeTab.COLOR -> SettingDisplayColorContent(vm = vm, contentPadding = contentPadding)
                    ThemeTab.UI -> SettingPreferencesUIContent(vm = vm, contentPadding = contentPadding)
                }
            }
        }
    }
}

private enum class ThemeTab(
    override val icon: ImageVector,
    @param:StringRes override val label: Int,
) : PagerTab {
    THEME(HugeIcons.Sun01, R.string.setting_page_preferences_theme),
    COLOR(HugeIcons.PaintBoard, R.string.setting_page_display_color),
    UI(HugeIcons.Edit01, R.string.setting_page_preferences_ui),
}

@Composable
private fun ThemeContent(vm: SettingVM, contentPadding: PaddingValues) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var amoledDarkMode by rememberAmoledDarkMode()
    val navController = LocalNavController.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            CardGroup {
                switchItem(
                    checked = settings.dynamicColor,
                    onCheckedChange = { vm.updateSettings { latest -> latest.copy(dynamicColor = it) } },
                    supportingContent = { Text(stringResource(R.string.setting_page_dynamic_color_desc)) },
                    headlineContent = { Text(stringResource(R.string.setting_page_dynamic_color)) },
                )
                item(
                    onClick = { navController.navigate(Screen.SettingTheme) },
                    headlineContent = { Text(stringResource(R.string.setting_page_theme_setting)) },
                    supportingContent = { Text(stringResource(R.string.setting_page_theme_setting_desc)) },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                )
                switchItem(
                    checked = amoledDarkMode,
                    onCheckedChange = { amoledDarkMode = it },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_amoled_dark_mode_desc)) },
                    headlineContent = { Text(stringResource(R.string.setting_display_page_amoled_dark_mode_title)) },
                )
                item {
                    CardGroup(
                        title = { Text(stringResource(R.string.setting_display_page_title)) },
                    ) {
                        switchItem(
                            checked = settings.displaySetting.useAppIconStyleLoadingIndicator,
                            onCheckedChange = { v ->
                                vm.updateSettings {
                                    it.copy(displaySetting = it.displaySetting.copy(useAppIconStyleLoadingIndicator = v))
                                }
                            },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_use_app_icon_style_loading_indicator_desc)) },
                            headlineContent = { Text(stringResource(R.string.setting_display_page_use_app_icon_style_loading_indicator_title)) },
                        )
                        switchItem(
                            checked = settings.displaySetting.enableBlurEffect,
                            onCheckedChange = { v ->
                                vm.updateSettings {
                                    it.copy(displaySetting = it.displaySetting.copy(enableBlurEffect = v))
                                }
                            },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_background_effect_desc)) },
                            headlineContent = { Text(stringResource(R.string.setting_display_page_background_effect_title)) },
                        )
                        if (settings.displaySetting.enableBlurEffect) {
                            formItem(
                                headlineContent = { Text(stringResource(R.string.setting_display_page_background_effect_type)) },
                            ) {
                                ConnectedChoiceButtons(
                                    options = BackgroundEffectType.entries,
                                    selected = settings.displaySetting.backgroundEffectType,
                                    onSelect = { type ->
                                        vm.updateSettings {
                                            it.copy(displaySetting = it.displaySetting.copy(backgroundEffectType = type))
                                        }
                                    },
                                ) { type, selected ->
                                    ConnectedChoiceLabel(
                                        text = when (type) {
                                            BackgroundEffectType.BLUR -> stringResource(R.string.setting_display_page_background_effect_blur)
                                            BackgroundEffectType.GLASS -> stringResource(R.string.setting_display_page_background_effect_glass)
                                        },
                                        selected = selected,
                                    )
                                }
                            }
                        }
                        switchItem(
                            checked = settings.displaySetting.enableMessageGenerationHapticEffect,
                            onCheckedChange = { v ->
                                vm.updateSettings {
                                    it.copy(displaySetting = it.displaySetting.copy(enableMessageGenerationHapticEffect = v))
                                }
                            },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_desc)) },
                            headlineContent = { Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_title)) },
                        )
                    }
                }
            }
        }
    }
}
