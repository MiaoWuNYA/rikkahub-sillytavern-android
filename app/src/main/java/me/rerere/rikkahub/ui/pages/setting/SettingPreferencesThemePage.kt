package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Moon02
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
import me.rerere.rikkahub.data.datastore.AppIcon
import me.rerere.rikkahub.data.datastore.BackgroundEffectType
import me.rerere.rikkahub.data.datastore.DisplayMaterialMode
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.utils.AppIconSwitcher
import me.rerere.rikkahub.utils.label
import me.rerere.rikkahub.utils.previewRes
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.hooks.rememberColorMode
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.material3.Slider
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
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
                top = 8.dp,
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
    @param:StringRes private val labelRes: Int,
) : PagerTab {
    THEME(HugeIcons.Sun01, R.string.setting_page_preferences_theme),
    COLOR(HugeIcons.PaintBoard, R.string.setting_page_display_color),
    UI(HugeIcons.Edit01, R.string.setting_page_preferences_ui);

    override val label: String
        @Composable get() = stringResource(labelRes)
}

@Composable
private fun ThemeContent(vm: SettingVM, contentPadding: PaddingValues) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var amoledDarkMode by rememberAmoledDarkMode()
    val navController = LocalNavController.current
    val activity = LocalActivity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingBgPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val oldPath = settings.displaySetting.settingBackgroundImagePath
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { importThemeImage(context, uri) }
            }.onSuccess { path ->
                deleteThemeFileIfOwned(context, oldPath, path)
                vm.updateSettings {
                    it.copy(displaySetting = it.displaySetting.copy(settingBackgroundImagePath = path))
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            var colorMode by rememberColorMode()
            val containerColor = MaterialTheme.colorScheme.primaryContainer
            val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            CardGroup(
                title = { Text(stringResource(R.string.setting_page_general_settings)) },
            ) {
                formItem {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CardGroupIcon(
                            when (colorMode) {
                                ColorMode.SYSTEM -> HugeIcons.SmartPhone01
                                ColorMode.LIGHT -> HugeIcons.Sun01
                                ColorMode.DARK -> HugeIcons.Moon02
                            },
                            containerColor = containerColor,
                            contentColor = contentColor,
                        )
                        Text(stringResource(R.string.setting_page_color_mode))
                    }
                    ConnectedChoiceButtons(
                        options = ColorMode.entries,
                        selected = colorMode,
                        onSelect = { mode ->
                            colorMode = mode
                            // 主题资源在 Activity 创建时解析，切换后重建才能整体生效。
                            // 以前靠重新导航到设置主页来触发，会把当前页一起弹掉，
                            // 看起来就是「刚选完就闪退回上一页」。
                            activity?.recreate()
                        },
                    ) { mode, selected ->
                        ConnectedChoiceLabel(
                            text = when (mode) {
                                ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
                                ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
                                ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
                            },
                            selected = selected,
                            icon = when (mode) {
                                        ColorMode.SYSTEM -> HugeIcons.SmartPhone01
                                        ColorMode.LIGHT -> HugeIcons.Sun01
                                        ColorMode.DARK -> HugeIcons.Moon02
                                    },
                        )
                    }
                }
            }
        }

        item {
            CardGroup(title = { Text("应用图标") }) {
                formItem {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // 固定两排三列：兔盔/地球/谷歌，探喵/脸猫/萌猫
                        listOf(
                            listOf(AppIcon.RABBIT, AppIcon.EARTH, AppIcon.GOOGLE),
                            listOf(AppIcon.CAT_BODY, AppIcon.CAT_HEAD, AppIcon.CAT_CUTE),
                        ).forEach { row ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                row.forEach { icon ->
                                    val selected = settings.displaySetting.appIcon == icon
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(16.dp))
                                            .clickable {
                                                vm.updateSettings { latest ->
                                                    latest.copy(displaySetting = latest.displaySetting.copy(appIcon = icon))
                                                }
                                                AppIconSwitcher.apply(context, icon)
                                            }
                                            .padding(4.dp),
                                    ) {
                                        Image(
                                            painter = painterResource(icon.previewRes),
                                            contentDescription = icon.label,
                                            modifier = Modifier
                                                .size(56.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                                .then(
                                                    if (selected) Modifier.border(
                                                        2.dp,
                                                        MaterialTheme.colorScheme.primary,
                                                        RoundedCornerShape(14.dp),
                                                    ) else Modifier
                                                ),
                                        )
                                        Text(
                                            text = icon.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

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
                    var effectAlpha by remember(settings.displaySetting.inputEffectAlpha) {
                        mutableStateOf(settings.displaySetting.inputEffectAlpha)
                    }
                    CardGroup(
                        title = { Text(stringResource(R.string.setting_display_page_title)) },
                    ) {
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
                            formItem(
                                headlineContent = { Text(stringResource(R.string.setting_display_page_background_effect_alpha)) },
                                supportingContent = { Text(stringResource(R.string.setting_display_page_background_effect_alpha_desc)) },
                                trailingContent = { CardGroupValue("${(effectAlpha * 100).roundToInt()}%") },
                            ) {
                                Slider(
                                    value = effectAlpha,
                                    onValueChange = { effectAlpha = it },
                                    onValueChangeFinished = {
                                        vm.updateSettings {
                                            it.copy(displaySetting = it.displaySetting.copy(inputEffectAlpha = effectAlpha))
                                        }
                                    },
                                    valueRange = 0.1f..1f,
                                    steps = 8,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        formItem(
                            headlineContent = { Text(stringResource(R.string.theme_material_title)) },
                            supportingContent = { Text(stringResource(R.string.theme_material_desc)) },
                        ) {
                            ConnectedChoiceButtons(
                                options = DisplayMaterialMode.entries,
                                selected = settings.displaySetting.materialMode,
                                onSelect = { mode ->
                                    vm.updateSettings {
                                        it.copy(displaySetting = it.displaySetting.copy(materialMode = mode))
                                    }
                                },
                            ) { mode, selected ->
                                ConnectedChoiceLabel(
                                    text = when (mode) {
                                        DisplayMaterialMode.FOLLOW_THEME -> stringResource(R.string.theme_material_follow)
                                        DisplayMaterialMode.FLAT -> stringResource(R.string.theme_material_off)
                                        DisplayMaterialMode.TRANSLUCENT -> stringResource(R.string.theme_material_translucent)
                                        DisplayMaterialMode.GLASS -> stringResource(R.string.theme_material_glass)
                                    },
                                    selected = selected,
                                )
                            }
                        }
                        val materialMode = settings.displaySetting.materialMode
                        if (materialMode == DisplayMaterialMode.TRANSLUCENT ||
                            materialMode == DisplayMaterialMode.GLASS
                        ) {
                            formItem(
                                headlineContent = { Text(stringResource(R.string.theme_material_opacity_title)) },
                                supportingContent = { Text(stringResource(R.string.theme_material_opacity_desc)) },
                            ) {
                                var surfaceAlpha by remember(settings.displaySetting.interfaceSurfaceOpacity) {
                                    mutableStateOf(settings.displaySetting.interfaceSurfaceOpacity)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = surfaceAlpha,
                                        onValueChange = { surfaceAlpha = it },
                                        onValueChangeFinished = {
                                            vm.updateSettings {
                                                it.copy(
                                                    displaySetting = it.displaySetting.copy(
                                                        interfaceSurfaceOpacity = surfaceAlpha
                                                    )
                                                )
                                            }
                                        },
                                        valueRange = 0f..95f,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text("${surfaceAlpha.roundToInt()}%")
                                }
                            }
                        }
                        item(
                            onClick = { settingBgPicker.launch("image/*") },
                            headlineContent = { Text(stringResource(R.string.theme_setting_background)) },
                            supportingContent = {
                                Text(
                                    if (settings.displaySetting.settingBackgroundImagePath.isBlank()) stringResource(R.string.theme_background_not_set)
                                    else stringResource(R.string.theme_background_set)
                                )
                            },
                            trailingContent = {
                                ItemActionMenu(
                                    actions = listOf(
                                        ItemAction(
                                            text = stringResource(R.string.action_reset),
                                            icon = HugeIcons.Delete01,
                                            destructive = true,
                                            enabled = settings.displaySetting.settingBackgroundImagePath.isNotBlank(),
                                            onClick = {
                                                deleteThemeFileIfOwned(
                                                    context,
                                                    settings.displaySetting.settingBackgroundImagePath,
                                                    null,
                                                )
                                                vm.updateSettings {
                                                    it.copy(displaySetting = it.displaySetting.copy(settingBackgroundImagePath = ""))
                                                }
                                            },
                                        ),
                                    )
                                )
                            },
                        )
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
