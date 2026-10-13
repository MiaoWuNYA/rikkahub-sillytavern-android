package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

/**
 * 朗读设置：默认语速、只读引号内容、跳过括号内容、生成后自动朗读。
 * 从语音页的 TTS 列表拆出为独立页，列表页不再被静态表单挤压。
 */
@Composable
fun SettingTtsPlaybackPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_tts_settings)) },
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
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                TtsPlaybackSettingsCard(
                    settings = settings,
                    onUpdateSettings = vm::updateSettings,
                )
            }
        }
    }
}

@Composable
private fun TtsPlaybackSettingsCard(
    settings: Settings,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
) {
    var playbackSpeed by remember(settings.defaultTTSPlaybackSpeed) {
        mutableStateOf(settings.defaultTTSPlaybackSpeed)
    }
    CardGroup(
        title = { Text(stringResource(R.string.setting_page_tts_settings)) },
    ) {
        formItem(
            headlineContent = { Text(stringResource(R.string.setting_tts_page_default_playback_speed)) },
            supportingContent = { Text(stringResource(R.string.setting_tts_page_default_playback_speed_description)) },
            trailingContent = { CardGroupValue("x${"%.1f".format(playbackSpeed)}") },
        ) {
            Slider(
                value = playbackSpeed,
                onValueChange = { playbackSpeed = (it * 10).roundToInt() / 10f },
                onValueChangeFinished = {
                    onUpdateSettings { it.copy(defaultTTSPlaybackSpeed = playbackSpeed) }
                },
                valueRange = 0.5f..2.0f,
                steps = 14,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        switchItem(
            checked = settings.displaySetting.ttsOnlyReadQuoted,
            onCheckedChange = { v ->
                onUpdateSettings {
                    it.copy(displaySetting = it.displaySetting.copy(ttsOnlyReadQuoted = v))
                }
            },
            supportingContent = { Text(stringResource(R.string.setting_display_page_tts_only_read_quoted_desc)) },
            headlineContent = { Text(stringResource(R.string.setting_display_page_tts_only_read_quoted_title)) },
        )
        switchItem(
            checked = settings.displaySetting.ttsOnlyReadOutsideBrackets,
            onCheckedChange = { v ->
                onUpdateSettings {
                    it.copy(displaySetting = it.displaySetting.copy(ttsOnlyReadOutsideBrackets = v))
                }
            },
            supportingContent = { Text(stringResource(R.string.setting_display_page_tts_read_outside_brackets_desc)) },
            headlineContent = { Text(stringResource(R.string.setting_display_page_tts_read_outside_brackets_title)) },
        )
        switchItem(
            checked = settings.displaySetting.autoPlayTTSAfterGeneration,
            onCheckedChange = { v ->
                onUpdateSettings {
                    it.copy(displaySetting = it.displaySetting.copy(autoPlayTTSAfterGeneration = v))
                }
            },
            supportingContent = { Text(stringResource(R.string.setting_display_page_auto_play_tts_desc)) },
            headlineContent = { Text(stringResource(R.string.setting_display_page_auto_play_tts_title)) },
        )
    }
}
