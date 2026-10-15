package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.export.SillyTavernRegexImporter
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantRegexCard
import me.rerere.rikkahub.ui.pages.assistant.detail.SectionAddButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/** 消息正则集中页：脚本仍随角色卡保存，但入口不再埋在单张卡里。 */
@Composable
fun TavernRegexPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var expandedCards by remember { mutableStateOf(emptySet<Uuid>()) }
    var expandedRegexes by remember { mutableStateOf(emptySet<Uuid>()) }
    var importTargetId by remember { mutableStateOf<Uuid?>(null) }

    val importedMsg = stringResource(R.string.assistant_page_regex_imported)
    val importFailedMsg = stringResource(R.string.assistant_page_regex_import_failed)
    val importErrorMsg = stringResource(R.string.export_import_failed)

    // 只回写 regexes：卡片其他字段以设置里的最新值为准
    fun applyRegexes(cardId: Uuid, regexes: List<AssistantRegex>) {
        vm.updateSettings { current ->
            current.copy(
                assistants = current.assistants.map { card ->
                    if (card.id == cardId) card.copy(regexes = regexes) else card
                },
            )
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val targetId = importTargetId
        importTargetId = null
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: error(importErrorMsg)
                    val parsed = SillyTavernRegexImporter.parse(text)
                    if (parsed.isEmpty()) error(importFailedMsg)
                    parsed
                }
            }
            result.onSuccess { parsed ->
                if (targetId != null) {
                    vm.updateSettings { current ->
                        current.copy(
                            assistants = current.assistants.map { card ->
                                if (card.id == targetId) card.copy(regexes = card.regexes + parsed) else card
                            },
                        )
                    }
                }
                toaster.show(importedMsg.format(parsed.size))
            }.onFailure {
                toaster.show(importErrorMsg.format(it.message))
            }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_tavern_regex)) },
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
            item("regexIntro") {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    formItem {
                        Text(
                            text = stringResource(R.string.tavern_regex_intro),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (settings.assistants.isEmpty()) {
                item("regexEmpty") {
                    CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                        formItem { Text(stringResource(R.string.tavern_regex_empty)) }
                    }
                }
            }

            items(settings.assistants, key = { it.id }) { card ->
                val expanded = card.id in expandedCards
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = {
                        Text(card.name.ifBlank { stringResource(R.string.setting_memory_unnamed_assistant) })
                    },
                ) {
                    item(
                        onClick = {
                            expandedCards = if (expanded) expandedCards - card.id else expandedCards + card.id
                        },
                        headlineContent = { Text(stringResource(R.string.assistant_page_regex_title)) },
                        supportingContent = {
                            Text(stringResource(R.string.tavern_regex_card_count, card.regexes.size))
                        },
                        trailingContent = {
                            Icon(
                                imageVector = HugeIcons.ArrowDown01,
                                contentDescription = null,
                                modifier = Modifier.rotate(if (expanded) 180f else 0f),
                            )
                        },
                    )
                    if (expanded) {
                        card.regexes.forEachIndexed { index, regex ->
                            formItem {
                                AssistantRegexCard(
                                    regex = regex,
                                    assistant = card,
                                    index = index,
                                    shape = MaterialTheme.shapes.large,
                                    expanded = regex.id in expandedRegexes,
                                    onExpandedChange = { open ->
                                        expandedRegexes =
                                            if (open) expandedRegexes + regex.id else expandedRegexes - regex.id
                                    },
                                    onUpdate = { updated -> applyRegexes(card.id, updated.regexes) },
                                )
                            }
                        }
                        formItem {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                SectionAddButton(
                                    onClick = {
                                        applyRegexes(card.id, card.regexes + AssistantRegex(id = Uuid.random()))
                                    },
                                )
                                IconButton(
                                    onClick = {
                                        importTargetId = card.id
                                        importLauncher.launch(arrayOf("*/*"))
                                    },
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.FileImport,
                                        contentDescription = stringResource(R.string.assistant_page_regex_import),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
