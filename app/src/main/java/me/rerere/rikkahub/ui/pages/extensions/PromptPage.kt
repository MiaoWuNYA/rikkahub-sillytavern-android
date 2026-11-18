package me.rerere.rikkahub.ui.pages.extensions

import kotlin.uuid.Uuid

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Setting07
import me.rerere.hugeicons.stroke.Share03
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.data.datastore.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.export.LorebookSerializer
import me.rerere.rikkahub.data.export.ModeInjectionSerializer
import me.rerere.rikkahub.data.export.SillyTavernRegexImporter
import me.rerere.rikkahub.ui.hooks.readBooleanPreference
import me.rerere.rikkahub.ui.hooks.rememberExporter
import me.rerere.rikkahub.ui.hooks.rememberImporter
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.orderedEntries
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.RegexGroup
import me.rerere.rikkahub.data.model.RegexGroupSource
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.SelectiveLogic
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.ExportDialog
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.IntTextField
import me.rerere.rikkahub.ui.components.ui.NullableIntTextField
import me.rerere.rikkahub.ui.components.ui.InsertionStrategySelector
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.PagerTab
import me.rerere.rikkahub.ui.components.ui.PagerTabs
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.hooks.writeBooleanPreference
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.ui.components.FormItem
import me.rerere.ui.components.RikkaConfirmDialog
import me.rerere.ui.components.Select
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private enum class PromptTab(
    override val icon: ImageVector,
    @param:StringRes private val labelRes: Int,
) : PagerTab {
    MODE_INJECTIONS(HugeIcons.MagicWand01, R.string.prompt_page_mode_injection_tab),
    LOREBOOKS(HugeIcons.Book01, R.string.prompt_page_lorebook_tab);

    override val label: String
        @Composable get() = stringResource(labelRes)
}

private const val KEY_PRESET_NOTICE_DISMISSED = "preset_third_party_notice_dismissed"

@Composable
fun PromptPage(vm: PromptVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { PromptTab.entries.size }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    // 第三方内容提示每次进页面弹一次，「不再提示」后永久关闭
    var showNotice by remember { mutableStateOf(!context.readBooleanPreference(KEY_PRESET_NOTICE_DISMISSED)) }

    if (showNotice) {
        AlertDialog(
            onDismissRequest = { showNotice = false },
            title = { Text(stringResource(R.string.preset_page_notice_title)) },
            text = {
                Text(
                    text = stringResource(R.string.preset_page_third_party_notice),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(onClick = { showNotice = false }) {
                    Text(stringResource(R.string.preset_page_notice_got_it))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        context.writeBooleanPreference(KEY_PRESET_NOTICE_DISMISSED, true)
                        showNotice = false
                    }
                ) {
                    Text(stringResource(R.string.preset_page_notice_never))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = { BackButton() },
                title = { Text(stringResource(R.string.prompt_page_title)) },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PagerTabs(
                pagerState = pagerState,
                tabs = PromptTab.entries,
                modifier = Modifier.padding(bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                when (PromptTab.entries[page]) {
                    PromptTab.MODE_INJECTIONS -> ModeInjectionTab(
                        modeInjections = settings.modeInjections,
                        onUpdate = { vm.updateSettings { latest -> latest.copy(modeInjections = it) } }
                    )

                    PromptTab.LOREBOOKS -> LorebookTab(
                        lorebooks = settings.lorebooks,
                        onUpdate = { newLorebooks ->
                            // 内嵌世界书随卡存储、注入时自动合并，无需再回写角色卡
                            vm.updateSettings { latest -> latest.copy(lorebooks = newLorebooks) }
                        },
                        settings = settings,
                        onCommitBatch = vm::commitLorebookBatch,
                        copyState = vm.lorebookCopies,
                        onSettingsUpdate = { fn -> vm.updateSettings(fn) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeInjectionTab(
    modeInjections: List<PromptInjection.ModeInjection>,
    onUpdate: (List<PromptInjection.ModeInjection>) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val lazyListState = rememberLazyListState()
    val toaster = LocalToaster.current
    val currentModeInjections by rememberUpdatedState(modeInjections)
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // 用最新列表而非首次组合的快照，拖拽中列表变化不再越界
        val current = currentModeInjections
        if (from.index in current.indices && to.index in 0..current.size) {
            val newList = current.toMutableList()
            val item = newList.removeAt(from.index)
            newList.add(to.index, item)
            onUpdate(newList)
        }
    }
    val editState = useEditState<PromptInjection.ModeInjection> { edited ->
        val index = modeInjections.indexOfFirst { it.id == edited.id }
        if (index >= 0) {
            onUpdate(modeInjections.toMutableList().apply { set(index, edited) })
        } else {
            onUpdate(modeInjections + edited)
        }
    }
    val importSuccessMsg = stringResource(R.string.export_import_success)
    val importFailedMsg = stringResource(R.string.export_import_failed)
    val importer = rememberImporter(ModeInjectionSerializer) { result ->
        result.onSuccess { imported ->
            onUpdate(currentModeInjections + imported)
            toaster.show(importSuccessMsg)
        }.onFailure { error ->
            toaster.show(importFailedMsg.format(error.message))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (modeInjections.isEmpty()) {
            ExtensionEmptyState(
                message = stringResource(R.string.prompt_page_mode_injection_empty),
                description = stringResource(R.string.prompt_page_empty_hint),
                icon = HugeIcons.MagicWand01,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .floatingToolbarVerticalNestedScroll(
                        expanded = expanded,
                        onExpand = { expanded = true },
                        onCollapse = { expanded = false }
                    ),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 128.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                state = lazyListState
            ) {
                items(modeInjections, key = { it.id }) { injection ->
                    ReorderableItem(
                        state = reorderableState,
                        key = injection.id
                    ) { isDragging ->
                        ModeInjectionCard(
                            injection = injection,
                            modifier = Modifier
                                .longPressDraggableHandle()
                                .promptReorderLift(isDragging),
                            onEdit = { editState.open(injection) },
                            onDelete = { onUpdate(modeInjections - injection) }
                        )
                    }
                }
            }
        }

        HorizontalFloatingToolbar(
            expanded = expanded,
            colors = CustomColors.floatingToolbarColors,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -ScreenOffset),
            leadingContent = {
                IconButton(onClick = { importer.importFromFile() }) {
                    Icon(HugeIcons.FileImport, null)
                }
            },
        ) {
            Button(onClick = { editState.open(PromptInjection.ModeInjection()) }) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(HugeIcons.Add01, null)
                    AnimatedVisibility(expanded) {
                        Row {
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(stringResource(R.string.prompt_page_add_mode_injection))
                        }
                    }
                }
            }
        }
    }

    if (editState.isEditing) {
        editState.currentState?.let { state ->
            ModeInjectionEditSheet(
                injection = state,
                onDismiss = { editState.dismiss() },
                onConfirm = { editState.confirm() },
                onEdit = { editState.currentState = it }
            )
        }
    }
}

@Composable
private fun ModeInjectionCard(
    injection: PromptInjection.ModeInjection,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showExportDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val exporter = rememberExporter(injection, ModeInjectionSerializer)

    Card(
        onClick = onEdit,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 停用的条目图标褪成中性色
            CardGroupIcon(
                icon = HugeIcons.MagicWand01,
                containerColor = if (injection.enabled) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                contentColor = if (injection.enabled) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = injection.name.ifEmpty { stringResource(R.string.prompt_page_unnamed) },
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Tag(type = TagType.INFO) {
                        Text(getPositionLabel(injection.position))
                    }
                    Tag(type = TagType.DEFAULT) {
                        Text(stringResource(R.string.prompt_page_priority_format, injection.priority))
                    }
                    if (!injection.enabled) {
                        Tag(type = TagType.WARNING) {
                            Text(stringResource(R.string.prompt_page_disabled))
                        }
                    }
                }
            }
            ItemActionMenu(
                actions = listOf(
                    ItemAction(
                        text = stringResource(R.string.export_title),
                        icon = HugeIcons.Share03,
                        onClick = { showExportDialog = true },
                    ),
                    ItemAction(
                        text = stringResource(R.string.prompt_page_delete),
                        icon = HugeIcons.Delete01,
                        destructive = true,
                        onClick = { showDeleteDialog = true },
                    ),
                )
            )
        }
    }

    RikkaConfirmDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showDeleteDialog = false
            onDelete()
        },
        onDismiss = { showDeleteDialog = false },
    ) {
        Text(stringResource(R.string.common_delete_confirm_message, injection.name))
    }

    if (showExportDialog) {
        ExportDialog(
            exporter = exporter,
            onDismiss = { showExportDialog = false }
        )
    }
}

@Composable
private fun ModeInjectionEditSheet(
    injection: PromptInjection.ModeInjection,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: (PromptInjection.ModeInjection) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = {
            IconButton(onClick = {
                scope.launch {
                    sheetState.hide()
                    onDismiss()
                }
            }) {
                Icon(HugeIcons.ArrowDown01, null)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.prompt_page_edit_mode_injection),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = injection.name,
                    onValueChange = { onEdit(injection.copy(name = it)) },
                    label = { Text(stringResource(R.string.prompt_page_name)) },
                    modifier = Modifier.fillMaxWidth()
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_enabled)) },
                    tail = {
                        Switch(
                            checked = injection.enabled,
                            onCheckedChange = { onEdit(injection.copy(enabled = it)) }
                        )
                    }
                )

                IntTextField(
                    value = injection.priority,
                    onValueChange = { onEdit(injection.copy(priority = it)) },
                    label = { Text(stringResource(R.string.prompt_page_priority_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Text(
                    stringResource(R.string.prompt_page_injection_position),
                    style = MaterialTheme.typography.titleSmall
                )
                InjectionPositionSelector(
                    position = injection.position,
                    onSelect = { onEdit(injection.copy(position = it)) }
                )

                AnimatedVisibility(visible = injection.position == InjectionPosition.AT_DEPTH) {
                    IntTextField(
                        value = injection.injectDepth,
                        onValueChange = { onEdit(injection.copy(injectDepth = it)) },
                        label = { Text(stringResource(R.string.prompt_page_inject_depth)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                AnimatedVisibility(visible = injection.position.usesStandaloneMessage()) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            stringResource(R.string.prompt_page_injection_role),
                            style = MaterialTheme.typography.titleSmall
                        )
                        InjectionRoleSelector(
                            role = injection.role,
                            onSelect = { onEdit(injection.copy(role = it)) }
                        )
                    }
                }

                OutlinedTextField(
                    value = injection.content,
                    onValueChange = { onEdit(injection.copy(content = it)) },
                    label = { Text(stringResource(R.string.prompt_page_injection_content)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    minLines = 5
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.prompt_page_cancel))
                }
                Button(onClick = onConfirm) {
                    Text(stringResource(R.string.prompt_page_confirm))
                }
            }
        }
    }
}

@Composable
internal fun InjectionPositionSelector(
    position: InjectionPosition,
    onSelect: (InjectionPosition) -> Unit
) {
    Select(
        options = InjectionPosition.entries,
        selectedOption = position,
        onOptionSelected = onSelect,
        optionToString = { getPositionLabel(it) },
        modifier = Modifier.fillMaxWidth()
    )
}

internal fun InjectionPosition.usesStandaloneMessage(): Boolean = when (this) {
    InjectionPosition.BEFORE_SYSTEM_PROMPT,
    InjectionPosition.AFTER_SYSTEM_PROMPT,
    InjectionPosition.BEFORE_CHARACTER,
    InjectionPosition.AFTER_CHARACTER,
    // outlet 不注入任何位置，仅经 {{outlet::name}} 宏引用展开
    InjectionPosition.OUTLET -> false

    InjectionPosition.ANTAGONIZE,
    InjectionPosition.TOP_OF_CHAT,
    InjectionPosition.BOTTOM_OF_CHAT,
    InjectionPosition.AFTER_DIALOG,
    InjectionPosition.AT_DEPTH,
    InjectionPosition.AUTHOR_NOTE,
    InjectionPosition.BEFORE_AUTHOR_NOTE,
    InjectionPosition.AFTER_AUTHOR_NOTE,
    InjectionPosition.EM_TOP,
    InjectionPosition.EM_BOTTOM -> true
}

@Composable
private fun getPositionLabel(position: InjectionPosition): String = when (position) {
    InjectionPosition.BEFORE_SYSTEM_PROMPT -> stringResource(R.string.prompt_page_position_before_system)
    InjectionPosition.AFTER_SYSTEM_PROMPT -> stringResource(R.string.prompt_page_position_after_system)
    InjectionPosition.BEFORE_CHARACTER -> stringResource(R.string.prompt_page_position_before_character)
    InjectionPosition.AFTER_CHARACTER -> stringResource(R.string.prompt_page_position_after_character)
    InjectionPosition.ANTAGONIZE -> stringResource(R.string.prompt_page_position_antagonize)
    InjectionPosition.TOP_OF_CHAT -> stringResource(R.string.prompt_page_position_top_of_chat)
    InjectionPosition.BOTTOM_OF_CHAT -> stringResource(R.string.prompt_page_position_bottom_of_chat)
    InjectionPosition.AFTER_DIALOG -> stringResource(R.string.prompt_page_position_after_dialog)
    InjectionPosition.AT_DEPTH -> stringResource(R.string.prompt_page_position_at_depth)
    InjectionPosition.AUTHOR_NOTE -> stringResource(R.string.prompt_page_position_author_note)
    InjectionPosition.BEFORE_AUTHOR_NOTE -> stringResource(R.string.prompt_page_position_before_author_note)
    InjectionPosition.AFTER_AUTHOR_NOTE -> stringResource(R.string.prompt_page_position_after_author_note)
    InjectionPosition.EM_TOP -> stringResource(R.string.prompt_page_pos_em_top)
    InjectionPosition.EM_BOTTOM -> stringResource(R.string.prompt_page_pos_em_bottom)
    InjectionPosition.OUTLET -> stringResource(R.string.prompt_page_pos_outlet)
}

@Composable
internal fun InjectionRoleSelector(
    role: MessageRole,
    onSelect: (MessageRole) -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    Select(
        options = listOf(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT),
        selectedOption = role,
        onOptionSelected = onSelect,
        optionToString = { getRoleLabel(it) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun getRoleLabel(role: MessageRole): String = when (role) {
    MessageRole.SYSTEM -> stringResource(R.string.prompt_page_role_system)
    MessageRole.USER -> stringResource(R.string.prompt_page_role_user)
    MessageRole.ASSISTANT -> stringResource(R.string.prompt_page_role_assistant)
    else -> role.name
}

// ==================== Lorebook Tab ====================

@Composable
private fun LorebookTab(
    lorebooks: List<Lorebook>,
    onUpdate: (List<Lorebook>) -> Unit,
    settings: Settings,
    onSettingsUpdate: ((Settings) -> Settings) -> Unit,
    onCommitBatch: suspend (LorebookBatchRequest) -> Lorebook,
    copyState: LorebookCopyState,
) {
    val pendingCopy by copyState.pending.collectAsStateWithLifecycle()
    val copyBusy by copyState.busy.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(true) }
    val lazyListState = rememberLazyListState()
    val toaster = LocalToaster.current
    val currentLorebooks by rememberUpdatedState(lorebooks)
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // 用最新列表而非首次组合的快照，拖拽中列表变化不再越界
        val current = currentLorebooks
        if (from.index in current.indices && to.index in 0..current.size) {
            val newList = current.toMutableList()
            val item = newList.removeAt(from.index)
            newList.add(to.index, item)
            onUpdate(newList)
        }
    }
    fun saveLorebook(edited: Lorebook) {
        val latest = currentLorebooks
        val index = latest.indexOfFirst { it.id == edited.id }
        if (index >= 0) {
            onUpdate(latest.toMutableList().apply { set(index, edited) })
        } else {
            onUpdate(latest + edited)
        }
    }
    val editState = useEditState<Lorebook>(::saveLorebook)
    val importSuccessMsg = stringResource(R.string.export_import_success)
    val importFailedMsg = stringResource(R.string.export_import_failed)
    val presetFolderName = stringResource(R.string.tavern_regex_preset_folder)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 自定义导入流程：世界书/预设（prompts）→ 世界书，预设里的正则脚本 → 全局正则分组。
    // 走 LorebookSerializer 的通用导入拿不到正则，故这里读一次 JSON 两处都用。
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: error(importFailedMsg)
                    val fileName = LorebookSerializer.getUriFileName(context, uri)?.removeSuffix(".json")
                    val book = LorebookSerializer.importFromJson(json, fileName)
                    // 酒馆预设把脚本挂在 extensions.regex_scripts，导入时按预设名收进一个分组
                    val regexes = SillyTavernRegexImporter.parseFromPreset(json)
                    val groupName = fileName?.takeIf { it.isNotBlank() } ?: book.name
                    book to (groupName to regexes)
                }
            }
            result.onSuccess { (book, groupAndRegexes) ->
                onUpdate(currentLorebooks + book)
                val (rawName, regexes) = groupAndRegexes
                if (regexes.isNotEmpty()) {
                    val name = rawName.ifBlank { presetFolderName }
                    // 同名预设视为「同一个文件夹」：重新导入即刷新该文件夹里的脚本，不再堆叠重复分组
                    val existing = settings.regexGroups.firstOrNull {
                        it.source == RegexGroupSource.PRESET && it.name == name
                    }
                    val groups = if (existing != null) {
                        settings.regexGroups.map { if (it.id == existing.id) it.copy(regexes = regexes) else it }
                    } else {
                        settings.regexGroups + RegexGroup(name = name, source = RegexGroupSource.PRESET, regexes = regexes)
                    }
                    onSettingsUpdate { it.copy(regexGroups = groups) }
                }
                toaster.show(importSuccessMsg)
            }.onFailure { error ->
                toaster.show(importFailedMsg.format(error.message))
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 全局世界书设置：放在 LazyColumn 外面。LazyColumn item 内的高度动画不会逐帧
        // 传播给列表布局（动画结束瞬间高度突变，下方条目会弹一下）；移出来用普通 Column
        // 布局，AnimatedVisibility 标准高度动画逐帧生效，列表只随视口高度平滑变化。
        var worldInfoSettingsExpanded by rememberSaveable { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!editState.isEditing) pendingCopy?.let { task ->
                LorebookCopyShelf(task, copyBusy, copyState::remove, copyState::cancel)
            }
            TextButton(
                onClick = { worldInfoSettingsExpanded = !worldInfoSettingsExpanded },
            ) {
                Icon(
                    imageVector = HugeIcons.Setting07,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    text = stringResource(R.string.prompt_page_world_info_settings_button),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            AnimatedVisibility(
                visible = worldInfoSettingsExpanded,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
            // 限高 + 可滚动：设置项多（尤其向量存储展开后）会超出视口，
            // 不限高会把下方列表挤没且自身无法滚动，表现为"卡死"
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
            CardGroup(
                modifier = Modifier.animateContentSize(animationSpec = tween(durationMillis = 200)),
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.prompt_page_world_info_global_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(R.string.prompt_page_world_info_global_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.prompt_page_world_info_depth_title), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.prompt_page_world_info_depth_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IntTextField(
                            value = settings.worldInfoDepth,
                            onValueChange = { v -> onSettingsUpdate { it.copy(worldInfoDepth = v.coerceIn(0, 1000)) } },
                            modifier = Modifier.width(84.dp),
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.prompt_page_world_info_budget_title), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.prompt_page_world_info_budget_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IntTextField(
                            value = settings.worldInfoBudget,
                            onValueChange = { v -> onSettingsUpdate { it.copy(worldInfoBudget = v.coerceIn(0, 100)) } },
                            modifier = Modifier.width(84.dp),
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.prompt_page_world_info_budget_cap_title), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.prompt_page_world_info_budget_cap_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IntTextField(
                            value = settings.worldInfoBudgetCap,
                            onValueChange = { v -> onSettingsUpdate { it.copy(worldInfoBudgetCap = v.coerceIn(0, 100000)) } },
                            modifier = Modifier.width(84.dp),
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.prompt_page_world_info_min_activations_title), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.prompt_page_world_info_min_activations_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IntTextField(
                            value = settings.worldInfoMinActivations,
                            onValueChange = { v -> onSettingsUpdate { it.copy(worldInfoMinActivations = v.coerceIn(0, 50)) } },
                            modifier = Modifier.width(84.dp),
                        )
                    }
                }
                if (settings.worldInfoMinActivations > 0) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.prompt_page_world_info_min_activations_depth_title), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(R.string.prompt_page_world_info_min_activations_depth_desc),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IntTextField(
                                value = settings.worldInfoMinActivationsDepthMax,
                                onValueChange = { v -> onSettingsUpdate { it.copy(worldInfoMinActivationsDepthMax = v.coerceIn(0, 1000)) } },
                                modifier = Modifier.width(84.dp),
                            )
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.prompt_page_world_info_recursive_title), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.prompt_page_world_info_recursive_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.worldInfoRecursive,
                            onCheckedChange = { v -> onSettingsUpdate { it.copy(worldInfoRecursive = v) } },
                        )
                    }
                }
                if (settings.worldInfoRecursive) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.prompt_page_world_info_max_recursion_title), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(R.string.prompt_page_world_info_max_recursion_desc),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IntTextField(
                                value = settings.worldInfoMaxRecursionSteps,
                                onValueChange = { v -> onSettingsUpdate { it.copy(worldInfoMaxRecursionSteps = v.coerceIn(0, 20)) } },
                                modifier = Modifier.width(84.dp),
                            )
                        }
                    }
                }
                item {
                    // 插入策略选项较多，放到整行选择区，避免在行尾被挤压换行
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.prompt_page_world_info_strategy_title),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.prompt_page_world_info_strategy_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        InsertionStrategySelector(
                            selected = settings.worldInfoCharacterStrategy,
                            onSelect = { v -> onSettingsUpdate { it.copy(worldInfoCharacterStrategy = v) } },
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.prompt_page_world_info_overflow_title), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.prompt_page_world_info_overflow_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.worldInfoOverflowAlert,
                            onCheckedChange = { v -> onSettingsUpdate { it.copy(worldInfoOverflowAlert = v) } },
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.prompt_page_world_info_group_scoring_title), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.prompt_page_world_info_group_scoring_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.worldInfoUseGroupScoring,
                            onCheckedChange = { v -> onSettingsUpdate { it.copy(worldInfoUseGroupScoring = v) } },
                        )
                    }
                }
                item {
                    // 向量检索（Vector Storage）：vectorized 条目按语义相似度激活
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.prompt_page_vector_storage_title), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.prompt_page_vector_storage_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.vectorStorageEnabled,
                            onCheckedChange = { v -> onSettingsUpdate { it.copy(vectorStorageEnabled = v) } },
                        )
                    }
                }
                if (settings.vectorStorageEnabled) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.prompt_page_vector_storage_model),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = stringResource(R.string.prompt_page_vector_storage_model_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            ModelSelector(
                                modelId = settings.vectorStorageModelId,
                                providers = settings.providers,
                                type = ModelType.EMBEDDING,
                                onSelect = { v -> onSettingsUpdate { it.copy(vectorStorageModelId = v.id) } },
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.prompt_page_vector_storage_scan_depth), style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        stringResource(R.string.prompt_page_vector_storage_scan_depth_desc),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IntTextField(
                                    value = settings.vectorStorageScanDepth,
                                    onValueChange = { v -> onSettingsUpdate { it.copy(vectorStorageScanDepth = v.coerceIn(1, 100)) } },
                                    modifier = Modifier.width(84.dp),
                                )
                            }
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = stringResource(R.string.prompt_page_vector_storage_threshold, (settings.vectorStorageThreshold * 100).roundToInt()),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = stringResource(R.string.prompt_page_vector_storage_threshold_desc),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Slider(
                                    value = settings.vectorStorageThreshold,
                                    onValueChange = { v -> onSettingsUpdate { it.copy(vectorStorageThreshold = (v * 100).roundToInt() / 100f) } },
                                    valueRange = 0.05f..0.95f,
                                    steps = 17,
                                )
                            }
                        }
                    }
                }
            }
            }
            }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .floatingToolbarVerticalNestedScroll(
                    expanded = expanded,
                    onExpand = { expanded = true },
                    onCollapse = { expanded = false }
                ),
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 128.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            state = lazyListState
        ) {
            if (lorebooks.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillParentMaxHeight(0.8f)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.prompt_page_lorebook_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.prompt_page_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                items(lorebooks, key = { it.id }) { book ->
                    ReorderableItem(
                        state = reorderableState,
                        key = book.id
                    ) { isDragging ->
                        LorebookCard(
                            book = book,
                            modifier = Modifier
                                .longPressDraggableHandle()
                                .promptReorderLift(isDragging),
                            onEdit = { editState.open(book) },
                            onDelete = { onUpdate(lorebooks - book) }
                        )
                    }
                }
            }
        }
        }

        HorizontalFloatingToolbar(
            expanded = expanded,
            colors = CustomColors.floatingToolbarColors,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -ScreenOffset),
            leadingContent = {
                IconButton(onClick = { importer.launch(arrayOf("*/*")) }) {
                    Icon(HugeIcons.FileImport, null)
                }
            },
        ) {
            Button(onClick = { editState.open(Lorebook()) }) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(HugeIcons.Add01, null)
                    AnimatedVisibility(expanded) {
                        Row {
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(stringResource(R.string.prompt_page_add_lorebook))
                        }
                    }
                }
            }
        }
    }

    if (editState.isEditing) {
        editState.currentState?.let { state ->
            LorebookEditSheet(
                book = state,
                onDismiss = { editState.dismiss() },
                onConfirm = { editState.confirm() },
                onSave = ::saveLorebook,
                persistedBook = currentLorebooks.firstOrNull { it.id == state.id },
                onCommitBatch = onCommitBatch,
                copyTask = pendingCopy,
                copyBusy = copyBusy,
                onStageCopy = { entries ->
                    copyState.stage(state.id, entries)
                    editState.dismiss()
                },
                onRemoveCopyEntry = copyState::remove,
                onCancelCopy = copyState::cancel,
                onEdit = { editState.currentState = it }
            )
        }
    }
}

@Composable
private fun LorebookCard(
    book: Lorebook,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showExportDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val exporter = rememberExporter(book, LorebookSerializer)

    Card(
        onClick = onEdit,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 停用的条目图标褪成中性色
            CardGroupIcon(
                icon = HugeIcons.Book01,
                containerColor = if (book.enabled) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                contentColor = if (book.enabled) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = book.name.ifEmpty { stringResource(R.string.prompt_page_unnamed_lorebook) },
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (book.description.isNotEmpty()) {
                    Text(
                        text = book.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Tag(type = TagType.INFO) {
                        Text(
                            stringResource(
                                R.string.prompt_page_entries_count_format,
                                book.entries.size
                            )
                        )
                    }
                    if (!book.enabled) {
                        Tag(type = TagType.WARNING) {
                            Text(stringResource(R.string.prompt_page_disabled))
                        }
                    }
                }
            }
            ItemActionMenu(
                actions = listOf(
                    ItemAction(
                        text = stringResource(R.string.export_title),
                        icon = HugeIcons.Share03,
                        onClick = { showExportDialog = true },
                    ),
                    ItemAction(
                        text = stringResource(R.string.prompt_page_delete),
                        icon = HugeIcons.Delete01,
                        destructive = true,
                        onClick = { showDeleteDialog = true },
                    ),
                )
            )
        }
    }

    RikkaConfirmDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showDeleteDialog = false
            onDelete()
        },
        onDismiss = { showDeleteDialog = false },
    ) {
        Text(stringResource(R.string.common_delete_confirm_message, book.name))
    }

    if (showExportDialog) {
        ExportDialog(
            exporter = exporter,
            onDismiss = { showExportDialog = false }
        )
    }
}

@Composable
internal fun LorebookEditSheet(
    book: Lorebook,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onSave: (Lorebook) -> Unit,
    persistedBook: Lorebook?,
    onCommitBatch: suspend (LorebookBatchRequest) -> Lorebook,
    copyTask: LorebookCopyTask? = null,
    copyBusy: Boolean = false,
    onStageCopy: ((List<PromptInjection.RegexInjection>) -> Unit)? = null,
    onRemoveCopyEntry: (Uuid) -> Unit = {},
    onCancelCopy: () -> Unit = {},
    onEdit: (Lorebook) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var pendingEntryJson by rememberSaveable(book.id.toString()) { mutableStateOf<String?>(null) }
    val pendingEntry = remember(pendingEntryJson) {
        pendingEntryJson?.let {
            JsonInstant.decodeFromString<PromptInjection.RegexInjection>(it)
        }
    }
    var selecting by rememberSaveable(book.id.toString()) { mutableStateOf(false) }
    var selectionMode by rememberSaveable(book.id.toString()) { mutableStateOf(LorebookBatchMode.MOVE.name) }
    var showGroupManagement by remember { mutableStateOf(false) }
    var showCategoryManagement by remember { mutableStateOf(false) }
    var groupMoveConfirmation by remember { mutableStateOf<LorebookBatchRequest?>(null) }
    var showBulkSettings by remember { mutableStateOf(false) }
    var deleteConfirmation by remember { mutableStateOf<LorebookBatchRequest?>(null) }
    var copyConfirmation by remember { mutableStateOf<LorebookBatchRequest?>(null) }
    var selectedStrings by rememberSaveable(book.id.toString()) { mutableStateOf(emptyList<String>()) }
    var movingStrings by rememberSaveable(book.id.toString()) { mutableStateOf(emptyList<String>()) }
    var batchToken by remember { mutableStateOf(Uuid.random()) }
    var menuExpanded by remember { mutableStateOf(false) }
    var savingBatch by remember { mutableStateOf(false) }
    var batchError by remember { mutableStateOf<String?>(null) }
    var baselineJson by rememberSaveable(book.id.toString()) {
        mutableStateOf(persistedBook?.let { JsonInstant.encodeToString(it) })
    }
    val baseline = remember(baselineJson) { baselineJson?.let { JsonInstant.decodeFromString<Lorebook>(it) } }
    val selectedIds = selectedStrings.map { Uuid.parse(it) }.toSet()
    val movingIds = movingStrings.map { Uuid.parse(it) }.toSet()
    val moving = movingIds.takeIf { it.isNotEmpty() }?.let {
        LorebookBatchPreview(batchToken, LorebookBatchMode.MOVE, orderedLorebookSelection(book.entries, it))
    }
    val activeBatch = copyTask?.preview() ?: moving
    val canCopyHere = copyTask == null || copyTask.sourceBookId != book.id
    LaunchedEffect(copyTask?.id) { copyConfirmation = null; batchError = null }
    LaunchedEffect(persistedBook, book) {
        if (persistedBook == book) baselineJson = JsonInstant.encodeToString(book)
    }
    fun cancelBatch() {
        if (!savingBatch) {
            selecting = false
            selectedStrings = emptyList()
            movingStrings = emptyList()
            batchError = null
        }
    }
    fun executeBatch(request: LorebookBatchRequest) {
        if (savingBatch) return
        savingBatch = true
        batchError = null
        scope.launch {
            try {
                val saved = onCommitBatch(request)
                onEdit(saved)
                baselineJson = JsonInstant.encodeToString(saved)
                val action = request.action
                if (action is LorebookBatchAction.Move) {
                    movingStrings = movingStrings.filterNot { Uuid.parse(it) in action.ids }
                    selecting = false
                    selectedStrings = emptyList()
                }
                if (action is LorebookBatchAction.Delete || action is LorebookBatchAction.Settings) {
                    selectedStrings = emptyList()
                    selecting = false
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                batchError = "保存失败：${failure.message ?: "请重试"}；${if (request.action is LorebookBatchAction.Delete) "选择仍保留" else "暂存条目仍保留"}"
            } finally {
                savingBatch = false
            }
        }
    }
    fun commitBatch(placement: LorebookEntryPlacement?, atEnd: Boolean?, singleId: Uuid? = null) {
        if (savingBatch || copyBusy) return
        val ids = singleId?.let { setOf(it) }
        val action = copyTask?.let { LorebookBatchAction.Copy(it, ids ?: it.entries.map { entry -> entry.id }.toSet()) }
            ?: LorebookBatchAction.Move(ids ?: movingIds)
        val request = LorebookBatchRequest(book, baseline, action, placement, atEnd)
        if (action is LorebookBatchAction.Copy) {
            if (canCopyHere) copyConfirmation = request
            else batchError = "请选择另一份世界书作为复制目标；待复制选择仍保留"
        } else executeBatch(request)
    }
    val dismiss = { if (!savingBatch && !copyBusy) onDismiss() }
    val entryEditState = useEditState<PromptInjection.RegexInjection> { edited ->
        val index = book.entries.indexOfFirst { it.id == edited.id }
        if (index >= 0) {
            onEdit(book.withLorebookEntries(book.entries.toMutableList().apply { set(index, if (edited.group.isNotBlank()) edited.copy(categoryId = null) else edited) }))
        } else {
            pendingEntryJson = JsonInstant.encodeToString(edited)
        }
    }
    val groupEditState = useEditState<Pair<String, List<PromptInjection.RegexInjection>>> { pair ->
        val (newGroupName, updatedEntries) = pair
        val oldGroupName = book.entries.firstOrNull { it.id == updatedEntries.firstOrNull()?.id }?.group ?: return@useEditState
        val newEntries = book.entries.map { entry ->
            if (entry.group == oldGroupName) {
                val base = updatedEntries.first()
                entry.copy(
                    name = base.name,
                    enabled = base.enabled,
                    priority = base.priority,
                    position = base.position,
                    content = base.content,
                    injectDepth = base.injectDepth,
                    role = base.role,
                    keywords = base.keywords,
                    secondaryKeys = base.secondaryKeys,
                    useRegex = base.useRegex,
                    caseSensitive = base.caseSensitive,
                    scanDepth = base.scanDepth,
                    constantActive = base.constantActive,
                    selective = base.selective,
                    selectiveLogic = base.selectiveLogic,
                    probability = base.probability,
                    sticky = base.sticky,
                    cooldown = base.cooldown,
                    group = newGroupName,
                    groupWeight = base.groupWeight,
                    groupOverride = base.groupOverride,
                )
            } else entry
        }
        val renamed = lorebookEntryGroupNames(book).map { if (it == oldGroupName) newGroupName else it }
        onEdit(book.copy(entryGroups = renamed.filter { it.isNotBlank() }.distinct()).copy(entries = newEntries))
    }

    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = {
            IconButton(enabled = !savingBatch && !copyBusy, onClick = {
                scope.launch {
                    sheetState.hide()
                    dismiss()
                }
            }) {
                Icon(HugeIcons.ArrowDown01, null)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .testTag("lorebook-editor-sheet")
                .fillMaxWidth()
                .fillMaxHeight(0.95f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.prompt_page_edit_lorebook),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            if (pendingEntry == null && activeBatch == null && !selecting) Box(Modifier.fillMaxWidth()) {
                TextButton(onClick = { menuExpanded = true }, enabled = pendingEntry == null && activeBatch == null && !selecting && !savingBatch && !copyBusy) {
                    Text("批量管理条目")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("批量设置") }, onClick = { menuExpanded = false; selectionMode = LorebookBatchMode.SETTINGS.name; selecting = true })
                    DropdownMenuItem(text = { Text("删除条目") }, onClick = { menuExpanded = false; selectionMode = LorebookBatchMode.DELETE.name; selecting = true })
                    DropdownMenuItem(text = { Text("移动到本书其他位置") }, onClick = { menuExpanded = false; selectionMode = LorebookBatchMode.MOVE.name; selecting = true })
                    DropdownMenuItem(
                        text = { Text("复制到其他世界书") },
                        enabled = persistedBook != null && onStageCopy != null,
                        onClick = { menuExpanded = false; selectionMode = LorebookBatchMode.COPY.name; selecting = true },
                        trailingIcon = { if (persistedBook == null) Text("请先保存本书", style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
            if (selecting) LorebookSelectionControls(
                book.entries, selectedIds, LorebookBatchMode.valueOf(selectionMode),
                onSelect = { selectedStrings = it.map(Uuid::toString) },
                onDone = {
                    val ordered = orderedLorebookSelection(book.orderedEntries(), selectedIds)
                    try {
                        if (selectionMode == LorebookBatchMode.SETTINGS.name) {
                            showBulkSettings = true
                            return@LorebookSelectionControls
                        } else if (selectionMode == LorebookBatchMode.DELETE.name) {
                            deleteConfirmation = LorebookBatchRequest(book, baseline, LorebookBatchAction.Delete(ordered.map { it.id }.toSet()))
                            return@LorebookSelectionControls
                        } else if (selectionMode == LorebookBatchMode.COPY.name) checkNotNull(onStageCopy).invoke(ordered)
                        else {
                            movingStrings = ordered.map { it.id.toString() }
                            batchToken = Uuid.random()
                        }
                        selectedStrings = emptyList()
                        selecting = false
                    } catch (failure: Exception) {
                        batchError = failure.message ?: "暂存失败，请重试"
                    }
                },
                onCancel = ::cancelBatch,
                groups = lorebookEntryGroupNames(book),
                enabled = !savingBatch && !copyBusy,
                book = book,
                onMoveToPlacement = { placement ->
                    if (selectedIds.isNotEmpty()) groupMoveConfirmation = LorebookBatchRequest(book, baseline,
                        LorebookBatchAction.Move(selectedIds), placement)
                },
            )
            if (savingBatch) Text("正在保存，请稍候…", style = MaterialTheme.typography.bodySmall)
            batchError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            LorebookEntryList(
                book = book,
                pendingEntry = pendingEntry,
                batch = activeBatch,
                selectedIds = if (selecting) selectedIds else null,
                onSelectEntry = { id ->
                    // Multiple taps can arrive before recomposition; read the live selection.
                    val latestIds = selectedStrings.map { Uuid.parse(it) }.toSet()
                    val entry = book.entries.firstOrNull { it.id == id }
                    if (!savingBatch && !copyBusy && entry != null) {
                        selectedStrings = (if (id in latestIds) latestIds - id else latestIds + id).map(Uuid::toString)
                    }
                },
                onRemoveBatchEntry = { id ->
                    if (!savingBatch && !copyBusy) {
                        if (copyTask != null) onRemoveCopyEntry(id)
                        else {
                            movingStrings = movingStrings - id.toString()
                            if (movingStrings.isEmpty()) batchError = null
                        }
                    }
                },
                onCommitBatch = { placement, end -> commitBatch(placement, end) },
                onCommitSingle = { id, placement -> commitBatch(placement, null, id) },
                batchEnabled = !savingBatch && !copyBusy && canCopyHere,
                batchBusy = savingBatch || copyBusy,
                batchHint = if (copyTask != null) {
                    if (canCopyHere) "长按整组手柄选择落点；复制前会确认分组与位置"
                    else "这是来源世界书，请返回列表选择另一份；暂存选择会保留"
                } else null,
                onUpdatePending = { pendingEntryJson = JsonInstant.encodeToString(it) },
                onCancelPending = {
                    if (copyTask != null) { onCancelCopy(); batchError = null }
                    else if (moving != null) cancelBatch() else pendingEntryJson = null
                },
                onInsertPending = { placement ->
                    pendingEntry?.let { pending ->
                        val entries = insertLorebookEntry(book.entries, pending, placement)
                        if (entries.any { it.id == pending.id }) {
                            val inserted = book.withPlacedEntries(entries, setOf(pending.id), placement)
                            onEdit(inserted)
                            if (placement?.targetId == null) onSave(inserted)
                            pendingEntryJson = null
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                header = {
                    OutlinedTextField(
                        value = book.name,
                        onValueChange = { onEdit(book.copy(name = it)) },
                        label = { Text(stringResource(R.string.prompt_page_name)) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = book.description,
                        onValueChange = { onEdit(book.copy(description = it)) },
                        label = { Text(stringResource(R.string.prompt_page_description)) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    FormItem(
                        label = { Text(stringResource(R.string.prompt_page_enabled)) },
                        tail = {
                            Switch(
                                checked = book.enabled,
                                onCheckedChange = { onEdit(book.copy(enabled = it)) }
                            )
                        }
                    )

                },
                onEntriesChange = { if (!savingBatch) onEdit(book.withLorebookEntries(it)) },
                onEditEntry = { if (!savingBatch) entryEditState.open(it) },
                onManageGroups = { showGroupManagement = true },
                onManageCategories = { showCategoryManagement = true },
                onAddCategoryEntry = { category -> entryEditState.open(PromptInjection.RegexInjection(categoryId = category)) },
                onGroupsChange = { if (!savingBatch && !copyBusy) onEdit(it) },
                onAddEntry = { group -> entryEditState.open(PromptInjection.RegexInjection(group = group)) },
                onGroupSettings = { group, entries -> groupEditState.open(group to entries) },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = dismiss, enabled = !savingBatch && !copyBusy) {
                    Text(stringResource(R.string.prompt_page_cancel))
                }
                Button(onClick = onConfirm, enabled = pendingEntry == null && activeBatch == null && !selecting && !savingBatch && !copyBusy) {
                    Text(stringResource(R.string.prompt_page_confirm))
                }
            }
        }
    }

    copyConfirmation?.let { request ->
        AlertDialog(
            onDismissRequest = { copyConfirmation = null },
            title = { Text("确认跨书复制") },
            text = { Text(lorebookCopyConfirmation(request), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = { copyConfirmation = null; executeBatch(request) }) { Text("确认复制") }
            },
            dismissButton = {
                TextButton(onClick = { copyConfirmation = null }) { Text("返回选择落点") }
            },
        )
    }

    if (showBulkSettings) LorebookBulkSettingsDialog(orderedLorebookSelection(book.orderedEntries(), selectedIds),
        onDismiss = { showBulkSettings = false }, onApply = { patch ->
            showBulkSettings = false
            executeBatch(LorebookBatchRequest(book, baseline, LorebookBatchAction.Settings(selectedIds, patch)))
        })
    deleteConfirmation?.let { request ->
        val count = (request.action as LorebookBatchAction.Delete).ids.size
        AlertDialog(onDismissRequest = { deleteConfirmation = null }, title = { Text("确认删除所选") },
            text = { Text("将删除 $count 条条目，未选中的条目和独立分组将保留。") },
            confirmButton = { TextButton(onClick = { deleteConfirmation = null; executeBatch(request) }) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { deleteConfirmation = null }) { Text("取消删除") } })
    }
    if (showCategoryManagement) LorebookCategoryManagement(book, onEdit, { showCategoryManagement = false })
    if (showGroupManagement) LorebookGroupManagement(book, onEdit, { showGroupManagement = false })
    groupMoveConfirmation?.let { request ->
        val count = (request.action as LorebookBatchAction.Move).ids.size
        AlertDialog(onDismissRequest = { groupMoveConfirmation = null }, title = { Text("确认移入分组") },
            text = { Text("将 $count 条移动到「${if (request.placement?.categoryTarget == true) book.categories.firstOrNull { it.id.toString() == request.placement.group }?.name ?: "未分类" else groupLabel(request.placement?.group.orEmpty())}」末尾。将更新所选条目的归属；移入普通分类或未分类会解除互斥归属，移入互斥组会移出普通区。其他触发配置与条目 ID 保留。") },
            confirmButton = { TextButton(onClick = { groupMoveConfirmation = null; executeBatch(request) }) { Text("确认移动") } },
            dismissButton = { TextButton(onClick = { groupMoveConfirmation = null }) { Text("取消移动") } })
    }
    if (entryEditState.isEditing) {
        entryEditState.currentState?.let { state ->
            RegexInjectionEditDialog(
                entry = state,
                onDismiss = { entryEditState.dismiss() },
                onConfirm = { entryEditState.confirm() },
                onEdit = { entryEditState.currentState = it }
            )
        }
    }
    if (groupEditState.isEditing) {
        groupEditState.currentState?.let { (groupName, entries) ->
            GroupSettingsDialog(
                groupName = groupName,
                entries = entries,
                onDismiss = { groupEditState.dismiss() },
                onConfirm = { newGroupName, template ->
                    groupEditState.currentState = Pair(newGroupName, entries.map { it.copy(
                        name = template.name,
                        enabled = template.enabled,
                        priority = template.priority,
                        position = template.position,
                        content = template.content,
                        injectDepth = template.injectDepth,
                        role = template.role,
                        useRegex = template.useRegex,
                        caseSensitive = template.caseSensitive,
                        scanDepth = template.scanDepth,
                        constantActive = template.constantActive,
                        selective = template.selective,
                        selectiveLogic = template.selectiveLogic,
                        probability = template.probability,
                        sticky = template.sticky,
                        cooldown = template.cooldown,
                        delay = template.delay,
                        groupWeight = template.groupWeight,
                        groupOverride = template.groupOverride,
                        matchWholeWords = template.matchWholeWords,
                        excludeRecursion = template.excludeRecursion,
                        preventRecursion = template.preventRecursion,
                        delayUntilRecursion = template.delayUntilRecursion,
                        useProbability = template.useProbability,
                        inclusionGroup = template.inclusionGroup,
                        useGroupScoring = template.useGroupScoring,
                        groupPriority = template.groupPriority,
                        automationId = template.automationId,
                        displayIndex = template.displayIndex,
                        displayPosition = template.displayPosition,
                        triggers = template.triggers,
                        matchPersonaDescription = template.matchPersonaDescription,
                        matchCharacterDescription = template.matchCharacterDescription,
                        matchCharacterPersonality = template.matchCharacterPersonality,
                        matchCharacterDepthPrompt = template.matchCharacterDepthPrompt,
                        matchScenario = template.matchScenario,
                        matchCreatorNotes = template.matchCreatorNotes,
                        ignoreBudget = template.ignoreBudget,
                    ) })
                    groupEditState.confirm()
                },
            )
        }
    }
}

@Composable
internal fun RegexInjectionEntryCard(
    entry: PromptInjection.RegexInjection,
    collapsed: Boolean = false,
    dragHandle: @Composable () -> Unit = {},
    selection: @Composable () -> Unit = {},
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (PromptInjection.RegexInjection) -> Unit = {},
) {
    var editingName by remember { mutableStateOf(false) }
    var editNameValue by remember { mutableStateOf(entry.name) }

    val viewConfiguration = androidx.compose.ui.platform.LocalViewConfiguration.current
    val controlsConfiguration = remember(viewConfiguration) {
        object : androidx.compose.ui.platform.ViewConfiguration by viewConfiguration {
            override val minimumTouchTargetSize = androidx.compose.ui.unit.DpSize(32.dp, 32.dp)
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalViewConfiguration provides controlsConfiguration,
        androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 32.dp,
    ) {
    Card(
        modifier = if (!entry.enabled) Modifier.fillMaxWidth().alpha(0.5f) else Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = CustomColors.listItemColors.containerColor
        ),
    ) {
        val stripeColor = MaterialTheme.colorScheme.surfaceVariant
        // Draw inside the measured card. A fillMaxHeight child would expand a bounded safety area.
        LorebookEntryCardLayout(dragHandle = dragHandle) {
            Row(modifier = Modifier.fillMaxWidth().drawBehind {
                drawRect(stripeColor, size = Size(4.dp.toPx(), size.height))
            }) {
                selection()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp, top = 6.dp, end = 8.dp, bottom = 6.dp)
                ) {
                val weightLabel = stringResource(R.string.prompt_page_group_weight_preview, entry.groupWeight)
                val constantLabel = stringResource(R.string.prompt_page_constant_preview)
                val metadata = listOfNotNull(
                    "P${entry.probability}%",
                    (entry.keywords + entry.secondaryKeys).joinToString(", ").takeIf { it.isNotBlank() },
                    entry.group.takeIf { it.isNotBlank() },
                    weightLabel.takeIf { !collapsed && entry.group.isNotBlank() },
                    constantLabel.takeIf { entry.constantActive },
                ).joinToString(" · ")
                // The title owns the main column; actions no longer consume its width.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 条目名称（可点击编辑）
                    if (editingName) {
                        OutlinedTextField(
                            value = editNameValue,
                            onValueChange = { editNameValue = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodyMedium,
                        )
                        IconButton(
                            onClick = {
                                onUpdate(entry.copy(name = editNameValue))
                                editingName = false
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(HugeIcons.Add01, null, modifier = Modifier.size(16.dp))
                        }
                    } else {
                        Text(
                            text = entry.name.ifEmpty { stringResource(R.string.prompt_page_unnamed_entry) },
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { editingName = true; editNameValue = entry.name },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Switch(
                        checked = entry.enabled,
                        onCheckedChange = { onUpdate(entry.copy(enabled = it)) },
                        modifier = Modifier.size(width = 52.dp, height = 32.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        metadata, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(HugeIcons.Tools, stringResource(R.string.prompt_page_edit), modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(HugeIcons.Delete01, stringResource(R.string.prompt_page_delete), modifier = Modifier.size(18.dp))
                    }
                }

                Column {
                if (collapsed) {
                    Text(
                        text = entry.content.replace('\n', ' '),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClick = onEdit),
                    )
                } else {
                // 注入内容预览（可展开编辑）
                var contentExpanded by remember { mutableStateOf(false) }
                var editContent by remember(entry.content) { mutableStateOf(entry.content) }
                Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    if (contentExpanded) {
                        OutlinedTextField(
                            value = editContent,
                            onValueChange = { editContent = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 80.dp, max = 200.dp),
                            textStyle = MaterialTheme.typography.bodySmall,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            ),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = {
                                    contentExpanded = false
                                    editContent = entry.content
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Text(stringResource(R.string.prompt_page_cancel), style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.width(4.dp))
                            TextButton(
                                onClick = {
                                    onUpdate(entry.copy(content = editContent))
                                    contentExpanded = false
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Text(stringResource(R.string.prompt_page_confirm), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    } else if (entry.content.isNotBlank()) {
                        Surface(
                            onClick = { contentExpanded = true; editContent = entry.content },
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = entry.content.lines().take(3).joinToString("\n")
                                    .let { if (it.length < entry.content.length) "$it…" else it },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    } else {
                        Surface(
                            onClick = { contentExpanded = true },
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.prompt_page_add_content),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp),
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

}
}

@Composable
internal fun LorebookGroupSection(
    groupName: String,
    entries: List<PromptInjection.RegexInjection>,
    onGroupSettings: () -> Unit,
    onAddEntry: () -> Unit,
    canAddEntry: Boolean = true,
    canEditGroup: Boolean = true,
    groupIndex: Int = 0,
    expanded: Boolean,
    onExpandedChange: () -> Unit,
    headerGesture: Modifier? = null,
    exclusive: Boolean = false,
) {
    val rotationAngle by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(200),
    )

    val folderColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
    )
    val folderColor = folderColors[groupIndex % folderColors.size]

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 组头
        Card(
            colors = CardDefaults.cardColors(
                containerColor = CustomColors.listItemColors.containerColor,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .then(headerGesture ?: Modifier.clickable { onExpandedChange() }),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = rotationAngle },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    if (exclusive) HugeIcons.Link01 else HugeIcons.Folder01,
                    contentDescription = if (exclusive) "互斥组" else "分类组",
                    modifier = Modifier.size(20.dp),
                    tint = folderColor,
                )
                Text(
                    text = groupName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        "${entries.size}",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                IconButton(onClick = onGroupSettings, enabled = canEditGroup, modifier = Modifier.size(28.dp)) {
                    Icon(HugeIcons.Tools, null, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onAddEntry, enabled = canAddEntry, modifier = Modifier.size(28.dp)) {
                    Icon(HugeIcons.Add01, "在 $groupName 中新增条目", modifier = Modifier.size(16.dp))
                }
            }
        }

    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GroupSettingsDialog(
    groupName: String,
    entries: List<PromptInjection.RegexInjection>,
    onDismiss: () -> Unit,
    onConfirm: (String, PromptInjection.RegexInjection) -> Unit,
) {
    val template = remember(entries) { entries.firstOrNull() ?: PromptInjection.RegexInjection() }
    var edited by remember { mutableStateOf(template) }
    var editGroupName by remember { mutableStateOf(groupName) }
    var newKeyword by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.prompt_page_group_settings, groupName))
                IconButton(onClick = onDismiss) {
                    Icon(HugeIcons.Cancel01, null, modifier = Modifier.size(20.dp))
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 组名称
                OutlinedTextField(
                    value = editGroupName,
                    onValueChange = { editGroupName = it },
                    label = { Text(stringResource(R.string.prompt_page_group)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // 启用
                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_enabled)) },
                    tail = {
                        Switch(
                            checked = edited.enabled,
                            onCheckedChange = { edited = edited.copy(enabled = it) }
                        )
                    }
                )

                // 优先级
                IntTextField(
                    value = edited.priority,
                    onValueChange = { edited = edited.copy(priority = it) },
                    label = { Text(stringResource(R.string.prompt_page_priority_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                // 注入位置
                Text(stringResource(R.string.prompt_page_injection_position), style = MaterialTheme.typography.titleSmall)
                InjectionPositionSelector(
                    position = edited.position,
                    onSelect = { edited = edited.copy(position = it) }
                )

                // 深度 (depth) — 始终显示
                IntTextField(
                    value = edited.injectDepth,
                    onValueChange = { edited = edited.copy(injectDepth = it) },
                    label = { Text(stringResource(R.string.prompt_page_inject_depth)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_use_group_scoring)) },
                    tail = {
                        Switch(
                            checked = edited.useGroupScoring,
                            onCheckedChange = { edited = edited.copy(useGroupScoring = it) }
                        )
                    }
                )
                OutlinedTextField(
                    value = edited.automationId,
                    onValueChange = { edited = edited.copy(automationId = it) },
                    label = { Text(stringResource(R.string.prompt_page_automation_id)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text(stringResource(R.string.tavern_card_automation_id_desc)) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntTextField(
                        value = edited.displayIndex,
                        onValueChange = { edited = edited.copy(displayIndex = it) },
                        label = { Text(stringResource(R.string.prompt_page_display_index)) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    IntTextField(
                        value = edited.displayPosition,
                        onValueChange = { edited = edited.copy(displayPosition = it) },
                        label = { Text(stringResource(R.string.prompt_page_display_position)) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = edited.triggers.joinToString(", "),
                    onValueChange = { text ->
                        edited = edited.copy(triggers = text.split(",").map { it.trim() }.filter { it.isNotBlank() })
                    },
                    label = { Text(stringResource(R.string.prompt_page_triggers)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text(stringResource(R.string.prompt_page_triggers_desc)) },
                )
                // 官方生成类型快捷多选
                val triggerOptions = listOf(
                    "normal" to stringResource(R.string.prompt_page_trigger_normal),
                    "continue" to stringResource(R.string.prompt_page_trigger_continue),
                    "impersonate" to stringResource(R.string.prompt_page_trigger_impersonate),
                    "swipe" to stringResource(R.string.prompt_page_trigger_swipe),
                    "regenerate" to stringResource(R.string.prompt_page_trigger_regenerate),
                    "quiet" to stringResource(R.string.prompt_page_trigger_quiet),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    triggerOptions.forEach { (value, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Checkbox(
                                checked = value in edited.triggers,
                                onCheckedChange = { checked ->
                                    val newTriggers = edited.triggers.toMutableSet().apply {
                                        if (checked) add(value) else remove(value)
                                    }
                                    edited = edited.copy(triggers = newTriggers.toList())
                                },
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                // 预算豁免（官方 ignore_budget）
                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_ignore_budget)) },
                    description = { Text(stringResource(R.string.prompt_page_ignore_budget_desc)) },
                    tail = {
                        Switch(
                            checked = edited.ignoreBudget,
                            onCheckedChange = { edited = edited.copy(ignoreBudget = it) }
                        )
                    }
                )

                // 官方 match_*：关键词扫描范围（默认只扫聊天）
                Text(
                    stringResource(R.string.prompt_page_match_scope),
                    style = MaterialTheme.typography.titleSmall,
                )
                val matchOptions = listOf(
                    "matchCharacterDescription" to stringResource(R.string.prompt_page_match_char_description),
                    "matchCharacterPersonality" to stringResource(R.string.prompt_page_match_char_personality),
                    "matchCharacterDepthPrompt" to stringResource(R.string.prompt_page_match_char_depth_prompt),
                    "matchScenario" to stringResource(R.string.prompt_page_match_scenario),
                    "matchCreatorNotes" to stringResource(R.string.prompt_page_match_creator_notes),
                    "matchPersonaDescription" to stringResource(R.string.prompt_page_match_persona),
                )
                matchOptions.forEach { (field, label) ->
                    val checked = when (field) {
                        "matchCharacterDescription" -> edited.matchCharacterDescription
                        "matchCharacterPersonality" -> edited.matchCharacterPersonality
                        "matchCharacterDepthPrompt" -> edited.matchCharacterDepthPrompt
                        "matchScenario" -> edited.matchScenario
                        "matchCreatorNotes" -> edited.matchCreatorNotes
                        else -> edited.matchPersonaDescription
                    }
                    FormItem(
                        label = { Text(label) },
                        tail = {
                            Switch(
                                checked = checked,
                                onCheckedChange = {
                                    edited = edited.copy(
                                        matchCharacterDescription = if (field == "matchCharacterDescription") it else edited.matchCharacterDescription,
                                        matchCharacterPersonality = if (field == "matchCharacterPersonality") it else edited.matchCharacterPersonality,
                                        matchCharacterDepthPrompt = if (field == "matchCharacterDepthPrompt") it else edited.matchCharacterDepthPrompt,
                                        matchScenario = if (field == "matchScenario") it else edited.matchScenario,
                                        matchCreatorNotes = if (field == "matchCreatorNotes") it else edited.matchCreatorNotes,
                                        matchPersonaDescription = if (field == "matchPersonaDescription") it else edited.matchPersonaDescription,
                                    )
                                }
                            )
                        }
                    )
                }

                // 概率
                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_probability_label)) },
                    tail = {
                        Switch(
                            checked = edited.useProbability,
                            onCheckedChange = { edited = edited.copy(useProbability = it) }
                        )
                    }
                )
                AnimatedVisibility(visible = edited.useProbability) {
                    Column {
                        Text(
                            stringResource(R.string.prompt_page_probability, edited.probability),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Slider(
                            value = edited.probability.toFloat(),
                            onValueChange = { edited = edited.copy(probability = it.toInt()) },
                            valueRange = 0f..100f,
                            steps = 99,
                        )
                    }
                }

                // 粘性 + 冷却
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntTextField(
                        value = edited.sticky,
                        onValueChange = { edited = edited.copy(sticky = it) },
                        label = { Text(stringResource(R.string.prompt_page_sticky)) },
                        supportingText = { Text(stringResource(R.string.prompt_page_sticky_desc)) },
                        suffix = { Text(stringResource(R.string.prompt_page_rounds_suffix), style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    IntTextField(
                        value = edited.cooldown,
                        onValueChange = { edited = edited.copy(cooldown = it) },
                        label = { Text(stringResource(R.string.prompt_page_cooldown)) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }

                // 注入角色
                AnimatedVisibility(visible = edited.position.usesStandaloneMessage()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.prompt_page_injection_role), style = MaterialTheme.typography.titleSmall)
                        InjectionRoleSelector(
                            role = edited.role,
                            onSelect = { edited = edited.copy(role = it) }
                        )
                    }
                }

                // 注入内容
                OutlinedTextField(
                    value = edited.content,
                    onValueChange = { edited = edited.copy(content = it) },
                    label = { Text(stringResource(R.string.prompt_page_injection_content)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    minLines = 3,
                )

                // 组权重 + 覆盖
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntTextField(
                        value = edited.groupWeight,
                        onValueChange = { edited = edited.copy(groupWeight = it) },
                        label = { Text(stringResource(R.string.prompt_page_group_weight)) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    FormItem(
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.prompt_page_group_override)) },
                        tail = {
                            Switch(
                                checked = edited.groupOverride,
                                onCheckedChange = { edited = edited.copy(groupOverride = it) }
                            )
                        }
                    )
                }

                // 常驻激活
                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_constant_active)) },
                    description = { Text(stringResource(R.string.prompt_page_constant_active_desc)) },
                    tail = {
                        Switch(
                            checked = edited.constantActive,
                            onCheckedChange = { edited = edited.copy(constantActive = it) }
                        )
                    }
                )

                // 扫描深度（留空 = 用全局默认，官方 world_info_depth=2）
                NullableIntTextField(
                    value = edited.scanDepth,
                    onValueChange = { edited = edited.copy(scanDepth = it) },
                    label = { Text(stringResource(R.string.prompt_page_scan_depth)) },
                    placeholder = { Text(stringResource(R.string.prompt_page_scan_depth_global)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(editGroupName, edited) }) {
                Text(stringResource(R.string.prompt_page_apply_to_group, entries.size))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.prompt_page_cancel))
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RegexInjectionEditDialog(
    entry: PromptInjection.RegexInjection,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: (PromptInjection.RegexInjection) -> Unit
) {

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.prompt_page_edit_entry)) },
        text = {
            RegexEntryEditorFields(entry, onEdit)
        },
        confirmButton = {
            val canSave = entry.keywords.isNotEmpty() || entry.constantActive || entry.vectorized
            TextButton(
                onClick = onConfirm,
                enabled = canSave
            ) {
                Text(stringResource(R.string.prompt_page_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.prompt_page_cancel))
            }
        }
    )
}
