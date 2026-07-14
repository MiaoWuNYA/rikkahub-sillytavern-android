package me.rerere.rikkahub.ui.pages.setting

import me.rerere.ai.core.ReasoningLevel
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.FileZip
import me.rerere.hugeicons.stroke.Mortarboard01
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.MessageMultiple01
import me.rerere.hugeicons.stroke.Notebook01
import me.rerere.hugeicons.stroke.Tools
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun SettingModelPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_model_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor
            ) {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    icon = { Icon(HugeIcons.AiBrain01, null) },
                    label = { Text(stringResource(R.string.setting_model_page_tab_model)) }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    icon = { Icon(HugeIcons.AiEditing, null) },
                    label = { Text(stringResource(R.string.setting_model_page_tab_prompt)) }
                )
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DefaultChatModelSetting(settings = settings, vm = vm)
            }

            item {
                DefaultTitleModelSetting(settings = settings, vm = vm)
            }

            item {
                DefaultSuggestionModelSetting(settings = settings, vm = vm)
            }

            item {
                DefaultTranslationModelSetting(settings = settings, vm = vm)
            }

            item {
                DefaultOcrModelSetting(settings = settings, vm = vm)
            }

            item {
                DefaultCompressModelSetting(settings = settings, vm = vm)
            }
        }
    }
}

@Composable
private fun ModelSettingsPage(settings: Settings, vm: SettingVM, contentPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_chat_model),
                description = stringResource(R.string.setting_model_page_chat_model_desc),
                modelId = settings.chatModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(chatModelId = it.id)) },
            )
        },
        description = {
            Text(stringResource(R.string.setting_model_page_translate_model_desc))
        },
        icon = {
            Icon(HugeIcons.Earth, null)
        },
        actions = {
            Box(modifier = Modifier.weight(1f)) {
                ModelSelector(
                    modelId = settings.translateModeId,
                    type = ModelType.CHAT,
                    onSelect = {
                        vm.updateSettings(
                            settings.copy(
                                translateModeId = it.id
                            )
                        )
                    },
                    providers = settings.providers,
                    modifier = Modifier.wrapContentWidth()
                )
            }
            IconButton(
                onClick = {
                    showModal = true
                },
                colors = IconButtonDefaults.filledTonalIconButtonColors()
            ) {
                Icon(HugeIcons.Tools, null)
            }
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_title_model),
                description = stringResource(R.string.setting_model_page_title_model_desc),
                modelId = settings.titleModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(titleModelId = it.id)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_suggestion_model),
                description = stringResource(R.string.setting_model_page_suggestion_model_desc),
                modelId = settings.suggestionModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(suggestionModelId = it.id)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_translate_model),
                description = stringResource(R.string.setting_model_page_translate_model_desc),
                modelId = settings.translateModeId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(translateModeId = it.id)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_ocr_model),
                description = stringResource(R.string.setting_model_page_ocr_model_desc),
                modelId = settings.ocrModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(ocrModelId = it.id)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_compress_model),
                description = stringResource(R.string.setting_model_page_compress_model_desc),
                modelId = settings.compressModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(compressModelId = it.id)) },
            )
        }
    }
}

@Composable
private fun ModelSettingItem(
    title: String,
    description: String,
    modelId: Uuid?,
    providers: List<ProviderSetting>,
    onSelect: (Model) -> Unit,
) {
    val state = rememberModelListState(
        modelId = modelId,
        providers = providers,
        type = ModelType.CHAT,
    )

    Column {
        CardGroup(title = { Text(title) }) {
            item(
                onClick = { state.open() },
                headlineContent = { Text(title) },
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = state.currentModel?.displayName
                                ?: stringResource(R.string.model_list_select_model),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            HugeIcons.ArrowRight01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }

    ModelListSheet(state = state, onSelect = onSelect)
}
