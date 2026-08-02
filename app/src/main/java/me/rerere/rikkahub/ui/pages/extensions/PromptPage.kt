package me.rerere.rikkahub.ui.pages.extensions

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.FileDownload
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.hugeicons.stroke.Share03
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Folder01
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.export.LorebookSerializer
import me.rerere.rikkahub.data.export.ModeInjectionSerializer
import me.rerere.rikkahub.data.export.rememberExporter
import me.rerere.rikkahub.data.export.rememberImporter
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.SelectiveLogic
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ExportDialog
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun PromptPage(vm: PromptVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = { BackButton() },
                title = { Text(stringResource(R.string.prompt_page_title)) },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    label = { Text(stringResource(R.string.prompt_page_mode_injection_tab)) },
                    icon = { Icon(HugeIcons.MagicWand01, null) },
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(0) }
                    }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    label = { Text(stringResource(R.string.prompt_page_lorebook_tab)) },
                    icon = { Icon(HugeIcons.Book01, null) },
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(1) }
                    }
                )
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) { page ->
            when (page) {
                0 -> ModeInjectionTab(
                    modeInjections = settings.modeInjections,
                    onUpdate = { vm.updateSettings(settings.copy(modeInjections = it)) }
                )

                1 -> LorebookTab(
                    lorebooks = settings.lorebooks,
                    onUpdate = { vm.updateSettings(settings.copy(lorebooks = it)) },
                    settings = settings,
                    onSettingsUpdate = { vm.updateSettings(it) }
                )
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
        val newList = modeInjections.toMutableList()
        val item = newList.removeAt(from.index)
        newList.add(to.index, item)
        onUpdate(newList)
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .floatingToolbarVerticalNestedScroll(
                    expanded = expanded,
                    onExpand = { expanded = true },
                    onCollapse = { expanded = false }
                ),
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 128.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            state = lazyListState
        ) {
            if (modeInjections.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillParentMaxHeight(0.8f)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.prompt_page_mode_injection_empty),
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
                items(modeInjections, key = { it.id }) { injection ->
                    ReorderableItem(
                        state = reorderableState,
                        key = injection.id
                    ) { isDragging ->
                        ModeInjectionCard(
                            injection = injection,
                            modifier = Modifier
                                .longPressDraggableHandle()
                                .graphicsLayer {
                                    if (isDragging) {
                                        scaleX = 1.05f
                                        scaleY = 1.05f
                                    }
                                },
                            onEdit = { editState.open(injection) },
                            onDelete = { onUpdate(modeInjections - injection) }
                        )
                    }
                }
            }
        }

        HorizontalFloatingToolbar(
            expanded = expanded,
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
    val swipeState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    var showExportDialog by remember { mutableStateOf(false) }
    val exporter = rememberExporter(injection, ModeInjectionSerializer)

    SwipeToDismissBox(
        state = swipeState,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { scope.launch { swipeState.reset() } }) {
                    Icon(HugeIcons.Cancel01, null)
                }
                FilledIconButton(onClick = {
                    scope.launch {
                        onDelete()
                        swipeState.reset()
                    }
                }) {
                    Icon(HugeIcons.Delete01, stringResource(R.string.prompt_page_delete))
                }
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = modifier
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = CustomColors.listItemColors.containerColor
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = injection.name.ifEmpty { stringResource(R.string.prompt_page_unnamed) },
                        style = MaterialTheme.typography.titleSmall,
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
                IconButton(onClick = { showExportDialog = true }) {
                    Icon(HugeIcons.Share03, stringResource(R.string.export_title))
                }
                IconButton(onClick = onEdit) {
                    Icon(HugeIcons.Tools, stringResource(R.string.prompt_page_edit))
                }
            }
        }
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

                OutlinedTextField(
                    value = injection.priority.toString(),
                    onValueChange = {
                        it.toIntOrNull()?.let { p -> onEdit(injection.copy(priority = p)) }
                    },
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
                    OutlinedTextField(
                        value = injection.injectDepth.toString(),
                        onValueChange = {
                            it.toIntOrNull()?.let { d -> onEdit(injection.copy(injectDepth = d)) }
                        },
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
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.prompt_page_confirm))
                }
            }
        }
    }
}

@Composable
private fun InjectionPositionSelector(
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

private fun InjectionPosition.usesStandaloneMessage(): Boolean = when (this) {
    InjectionPosition.BEFORE_SYSTEM_PROMPT,
    InjectionPosition.AFTER_SYSTEM_PROMPT,
    InjectionPosition.BEFORE_CHARACTER,
    InjectionPosition.AFTER_CHARACTER -> false

    InjectionPosition.ANTAGONIZE,
    InjectionPosition.TOP_OF_CHAT,
    InjectionPosition.BOTTOM_OF_CHAT,
    InjectionPosition.AFTER_DIALOG,
    InjectionPosition.AT_DEPTH,
    InjectionPosition.AUTHOR_NOTE,
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
    InjectionPosition.EM_TOP -> "示例消息前(EM Top)"
    InjectionPosition.EM_BOTTOM -> "示例消息后(EM Bottom)"
}

@Composable
private fun InjectionRoleSelector(
    role: MessageRole,
    onSelect: (MessageRole) -> Unit
) {
    Select(
        options = listOf(MessageRole.USER, MessageRole.ASSISTANT),
        selectedOption = role,
        onOptionSelected = onSelect,
        optionToString = { getRoleLabel(it) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun getRoleLabel(role: MessageRole): String = when (role) {
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
    onSettingsUpdate: (Settings) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val lazyListState = rememberLazyListState()
    val toaster = LocalToaster.current
    val currentLorebooks by rememberUpdatedState(lorebooks)
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val newList = lorebooks.toMutableList()
        val item = newList.removeAt(from.index)
        newList.add(to.index, item)
        onUpdate(newList)
    }
    val editState = useEditState<Lorebook> { edited ->
        val index = lorebooks.indexOfFirst { it.id == edited.id }
        if (index >= 0) {
            onUpdate(lorebooks.toMutableList().apply { set(index, edited) })
        } else {
            onUpdate(lorebooks + edited)
        }
    }
    val importSuccessMsg = stringResource(R.string.export_import_success)
    val importFailedMsg = stringResource(R.string.export_import_failed)
    val importer = rememberImporter(LorebookSerializer) { result ->
        result.onSuccess { imported ->
            onUpdate(currentLorebooks + imported)
            toaster.show(importSuccessMsg)
        }.onFailure { error ->
            toaster.show(importFailedMsg.format(error.message))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .floatingToolbarVerticalNestedScroll(
                    expanded = expanded,
                    onExpand = { expanded = true },
                    onCollapse = { expanded = false }
                ),
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 128.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            state = lazyListState
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
                        FormItem(
                            label = { Text(stringResource(R.string.prompt_page_world_info_budget_title)) },
                            description = { Text(stringResource(R.string.prompt_page_world_info_budget_desc)) }
                        ) {
                            OutlinedTextField(
                                value = settings.worldInfoBudget.toString(),
                                onValueChange = { v ->
                                    v.toIntOrNull()?.let {
                                        onSettingsUpdate(settings.copy(worldInfoBudget = it.coerceIn(0, 100000)))
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(110.dp),
                                textStyle = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        FormItem(
                            label = { Text(stringResource(R.string.prompt_page_world_info_min_activations_title)) },
                            description = { Text(stringResource(R.string.prompt_page_world_info_min_activations_desc)) }
                        ) {
                            OutlinedTextField(
                                value = settings.worldInfoMinActivations.toString(),
                                onValueChange = { v ->
                                    v.toIntOrNull()?.let {
                                        onSettingsUpdate(settings.copy(worldInfoMinActivations = it.coerceIn(0, 50)))
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(90.dp),
                                textStyle = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        FormItem(
                            label = { Text(stringResource(R.string.prompt_page_world_info_recursive_title)) },
                            description = { Text(stringResource(R.string.prompt_page_world_info_recursive_desc)) }
                        ) {
                            Switch(
                                checked = settings.worldInfoRecursive,
                                onCheckedChange = {
                                    onSettingsUpdate(settings.copy(worldInfoRecursive = it))
                                }
                            )
                        }
                        if (settings.worldInfoRecursive) {
                            FormItem(
                                label = { Text(stringResource(R.string.prompt_page_world_info_max_recursion_title)) },
                                description = { Text(stringResource(R.string.prompt_page_world_info_max_recursion_desc)) }
                            ) {
                                OutlinedTextField(
                                    value = settings.worldInfoMaxRecursionSteps.toString(),
                                    onValueChange = { v ->
                                        v.toIntOrNull()?.let {
                                            onSettingsUpdate(settings.copy(worldInfoMaxRecursionSteps = it.coerceIn(0, 20)))
                                        }
                                    },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(90.dp),
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
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
                                .graphicsLayer {
                                    if (isDragging) {
                                        scaleX = 1.05f
                                        scaleY = 1.05f
                                    }
                                },
                            onEdit = { editState.open(book) },
                            onDelete = { onUpdate(lorebooks - book) }
                        )
                    }
                }
            }
        }

        HorizontalFloatingToolbar(
            expanded = expanded,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -ScreenOffset),
            leadingContent = {
                IconButton(onClick = { importer.importFromFile() }) {
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
    val swipeState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    var showExportDialog by remember { mutableStateOf(false) }
    val exporter = rememberExporter(book, LorebookSerializer)

    SwipeToDismissBox(
        state = swipeState,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { scope.launch { swipeState.reset() } }) {
                    Icon(HugeIcons.Cancel01, null)
                }
                FilledIconButton(onClick = {
                    scope.launch {
                        onDelete()
                        swipeState.reset()
                    }
                }) {
                    Icon(HugeIcons.Delete01, stringResource(R.string.prompt_page_delete))
                }
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = modifier
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = CustomColors.listItemColors.containerColor
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = book.name.ifEmpty { stringResource(R.string.prompt_page_unnamed_lorebook) },
                        style = MaterialTheme.typography.titleSmall,
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
                IconButton(onClick = { showExportDialog = true }) {
                    Icon(HugeIcons.Share03, stringResource(R.string.export_title))
                }
                IconButton(onClick = onEdit) {
                    Icon(HugeIcons.Tools, stringResource(R.string.prompt_page_edit))
                }
            }
        }
    }

    if (showExportDialog) {
        ExportDialog(
            exporter = exporter,
            onDismiss = { showExportDialog = false }
        )
    }
}

@Composable
private fun LorebookEditSheet(
    book: Lorebook,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: (Lorebook) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val entryEditState = useEditState<PromptInjection.RegexInjection> { edited ->
        val index = book.entries.indexOfFirst { it.id == edited.id }
        if (index >= 0) {
            onEdit(book.copy(entries = book.entries.toMutableList().apply { set(index, edited) }))
        } else {
            onEdit(book.copy(entries = book.entries + edited))
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
        onEdit(book.copy(entries = newEntries))
    }

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
                .fillMaxHeight(0.95f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.prompt_page_edit_lorebook),
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

                // 条目列表（按分组）
                val groupedEntries = book.entries.groupBy { it.group }
                val namedGroups = groupedEntries.filterKeys { it.isNotBlank() }
                val ungroupedEntries = groupedEntries[""] ?: emptyList()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.prompt_page_entries_format, book.entries.size),
                        style = MaterialTheme.typography.titleSmall
                    )
                    IconButton(onClick = {
                        entryEditState.open(PromptInjection.RegexInjection())
                    }) {
                        Icon(HugeIcons.Add01, stringResource(R.string.prompt_page_add_entry))
                    }
                }

                // 有分组的组
                namedGroups.toList().forEachIndexed { idx, (groupName, groupEntries) ->
                    LorebookGroupSection(
                        groupName = groupName,
                        groupIndex = idx,
                        entries = groupEntries,
                        onGroupSettings = {
                            groupEditState.open(Pair(groupName, groupEntries))
                        },
                        onEditEntry = { entryEditState.open(it) },
                        onDeleteEntry = {
                            onEdit(book.copy(entries = book.entries - it))
                        },
                        onUpdateEntry = { edited ->
                            val idx = book.entries.indexOfFirst { it.id == edited.id }
                            if (idx >= 0) {
                                onEdit(book.copy(entries = book.entries.toMutableList().apply { set(idx, edited) }))
                            }
                        },
                        onAddEntry = {
                            entryEditState.open(PromptInjection.RegexInjection(group = groupName))
                        },
                    )
                }

                // 无分组的条目
                if (ungroupedEntries.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.prompt_page_no_group),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        ungroupedEntries.forEach { entry ->
                            RegexInjectionEntryCard(
                                entry = entry,
                                onEdit = { entryEditState.open(entry) },
                                onDelete = {
                                    onEdit(book.copy(entries = book.entries - entry))
                                },
                                onUpdate = { edited ->
                                    val idx = book.entries.indexOfFirst { it.id == edited.id }
                                    if (idx >= 0) {
                                        onEdit(book.copy(entries = book.entries.toMutableList().apply { set(idx, edited) }))
                                    }
                                },
                            )
                        }
                    }
                }

                // 没有条目时提示
                if (book.entries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.prompt_page_no_entries),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.prompt_page_cancel))
                }
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.prompt_page_confirm))
                }
            }
        }
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
                        groupWeight = template.groupWeight,
                        groupOverride = template.groupOverride,
                    ) })
                    groupEditState.confirm()
                },
            )
        }
    }
}

@Composable
private fun RegexInjectionEntryCard(
    entry: PromptInjection.RegexInjection,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (PromptInjection.RegexInjection) -> Unit = {},
) {
    var editingName by remember { mutableStateOf(false) }
    var editNameValue by remember { mutableStateOf(entry.name) }
    var newKeyword by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = CustomColors.listItemColors.containerColor
        ),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // 左侧 4dp 彩色装饰线
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(12.dp)
            ) {
            // 第一行：名称 + 启用开关 + 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { editingName = true; editNameValue = entry.name },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(HugeIcons.Tools, stringResource(R.string.prompt_page_edit), modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(HugeIcons.Delete01, stringResource(R.string.prompt_page_delete), modifier = Modifier.size(18.dp))
                }
            }

            // 触发词（紧凑显示）
            if (entry.keywords.isNotEmpty() || entry.secondaryKeys.isNotEmpty()) {
                Text(
                    text = entry.keywords.joinToString(", ").let {
                        if (it.length > 100) it.take(100) + "…" else it
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            // 状态信息行: 概率 + 分组 + 权重
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "P${entry.probability}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (entry.group.isNotBlank()) {
                    Text(
                        entry.group,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        "权重(Weight):${entry.groupWeight}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                if (entry.constantActive) {
                    Text(
                        "常驻(Const)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }

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
                            modifier = Modifier.padding(10.dp),
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
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        }
    }
}

}

@Composable
private fun LorebookGroupSection(
    groupName: String,
    entries: List<PromptInjection.RegexInjection>,
    onGroupSettings: () -> Unit,
    onEditEntry: (PromptInjection.RegexInjection) -> Unit,
    onDeleteEntry: (PromptInjection.RegexInjection) -> Unit,
    onUpdateEntry: (PromptInjection.RegexInjection) -> Unit,
    onAddEntry: () -> Unit,
    groupIndex: Int = 0,
) {
    var expanded by rememberSaveable(groupName) { mutableStateOf(false) }
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
                .clickable { expanded = !expanded },
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
                    HugeIcons.Folder01,
                    contentDescription = null,
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
                IconButton(onClick = onGroupSettings, modifier = Modifier.size(28.dp)) {
                    Icon(HugeIcons.Tools, null, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onAddEntry, modifier = Modifier.size(28.dp)) {
                    Icon(HugeIcons.Add01, null, modifier = Modifier.size(16.dp))
                }
            }
        }

        // 组内条目
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                entries.forEach { entry ->
                    RegexInjectionEntryCard(
                        entry = entry,
                        onEdit = { onEditEntry(entry) },
                        onDelete = { onDeleteEntry(entry) },
                        onUpdate = { edited -> onUpdateEntry(edited) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupSettingsDialog(
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
                OutlinedTextField(
                    value = edited.priority.toString(),
                    onValueChange = { it.toIntOrNull()?.let { p -> edited = edited.copy(priority = p) } },
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
                OutlinedTextField(
                    value = edited.injectDepth.toString(),
                    onValueChange = { it.toIntOrNull()?.let { d -> edited = edited.copy(injectDepth = d) } },
                    label = { Text("深度 (depth)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                // 酒馆官方高级字段（自动化ID/触发类型本App暂不执行，仅保留数据）
                OutlinedTextField(
                    value = entry.inclusionGroup,
                    onValueChange = { onEdit(entry.copy(inclusionGroup = it)) },
                    label = { Text("包含组(Inclusion Group)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text("逗号分隔多组，同组只取一条") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormItem(
                        modifier = Modifier.weight(1f),
                        label = { Text("使用组评分(Use Group Scoring)") },
                        tail = {
                            Switch(
                                checked = entry.useGroupScoring,
                                onCheckedChange = { onEdit(entry.copy(useGroupScoring = it)) }
                            )
                        }
                    )
                    FormItem(
                        modifier = Modifier.weight(1f),
                        label = { Text("包含优先(Group Priority)") },
                        tail = {
                            Switch(
                                checked = entry.groupPriority,
                                onCheckedChange = { onEdit(entry.copy(groupPriority = it)) }
                            )
                        }
                    )
                }
                OutlinedTextField(
                    value = entry.automationId,
                    onValueChange = { onEdit(entry.copy(automationId = it)) },
                    label = { Text("自动化ID(Automation ID)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text("仅保留数据，暂不执行") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = entry.displayIndex.toString(),
                        onValueChange = { it.toIntOrNull()?.let { v -> onEdit(entry.copy(displayIndex = v)) } },
                        label = { Text("显示序号(Display Index)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = entry.displayPosition.toString(),
                        onValueChange = { it.toIntOrNull()?.let { v -> onEdit(entry.copy(displayPosition = v)) } },
                        label = { Text("显示位置(Display Position)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = entry.triggers.joinToString(", "),
                    onValueChange = { text ->
                        onEdit(entry.copy(triggers = text.split(",").map { it.trim() }.filter { it.isNotBlank() }))
                    },
                    label = { Text("触发类型(Triggers)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text("逗号分隔，仅保留数据") },
                )

                // 概率
                FormItem(
                    label = { Text("触发概率(Probability)") },
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
                    OutlinedTextField(
                        value = edited.sticky.toString(),
                        onValueChange = { it.toIntOrNull()?.let { s -> edited = edited.copy(sticky = s) } },
                        label = { Text(stringResource(R.string.prompt_page_sticky)) },
                        supportingText = { Text(stringResource(R.string.prompt_page_sticky_desc)) },
                        suffix = { Text("轮", style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = edited.cooldown.toString(),
                        onValueChange = { it.toIntOrNull()?.let { c -> edited = edited.copy(cooldown = c) } },
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
                    OutlinedTextField(
                        value = edited.groupWeight.toString(),
                        onValueChange = { it.toIntOrNull()?.let { w -> edited = edited.copy(groupWeight = w) } },
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

                // 扫描深度
                OutlinedTextField(
                    value = edited.scanDepth.toString(),
                    onValueChange = { it.toIntOrNull()?.let { d -> edited = edited.copy(scanDepth = d) } },
                    label = { Text(stringResource(R.string.prompt_page_scan_depth)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
private fun RegexInjectionEditDialog(
    entry: PromptInjection.RegexInjection,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: (PromptInjection.RegexInjection) -> Unit
) {
    var newKeyword by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.prompt_page_edit_entry)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = entry.name,
                    onValueChange = { onEdit(entry.copy(name = it)) },
                    label = { Text(stringResource(R.string.prompt_page_name)) },
                    modifier = Modifier.fillMaxWidth()
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_enabled)) },
                    tail = {
                        Switch(
                            checked = entry.enabled,
                            onCheckedChange = { onEdit(entry.copy(enabled = it)) }
                        )
                    }
                )

                OutlinedTextField(
                    value = entry.priority.toString(),
                    onValueChange = {
                        it.toIntOrNull()?.let { p -> onEdit(entry.copy(priority = p)) }
                    },
                    label = { Text(stringResource(R.string.prompt_page_priority_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Text(
                    stringResource(R.string.prompt_page_injection_position),
                    style = MaterialTheme.typography.titleSmall
                )
                InjectionPositionSelector(
                    position = entry.position,
                    onSelect = { onEdit(entry.copy(position = it)) }
                )

                // 深度 (depth) — 始终显示
                OutlinedTextField(
                    value = entry.injectDepth.toString(),
                    onValueChange = {
                        it.toIntOrNull()?.let { d -> onEdit(entry.copy(injectDepth = d)) }
                    },
                    label = { Text("深度 (depth)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                // 关键词
                Text(stringResource(R.string.prompt_page_keywords_label), style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    entry.keywords.forEach { keyword ->
                        InputChip(
                            selected = false,
                            onClick = {},
                            label = { Text(keyword) },
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        onEdit(entry.copy(keywords = entry.keywords - keyword))
                                    },
                                    modifier = Modifier.size(16.dp)
                                ) {
                                    Icon(HugeIcons.Cancel01, null, modifier = Modifier.size(12.dp))
                                }
                            }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newKeyword,
                        onValueChange = { newKeyword = it },
                        label = { Text(stringResource(R.string.prompt_page_new_keyword)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    IconButton(
                        onClick = {
                            if (newKeyword.isNotBlank()) {
                                onEdit(entry.copy(keywords = entry.keywords + newKeyword.trim()))
                                newKeyword = ""
                            }
                        }
                    ) {
                        Icon(HugeIcons.Add01, stringResource(R.string.prompt_page_add))
                    }
                }

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_use_regex)) },
                    tail = {
                        Switch(
                            checked = entry.useRegex,
                            onCheckedChange = { onEdit(entry.copy(useRegex = it)) }
                        )
                    }
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_case_sensitive)) },
                    tail = {
                        Switch(
                            checked = entry.caseSensitive,
                            onCheckedChange = { onEdit(entry.copy(caseSensitive = it)) }
                        )
                    }
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_match_whole_words)) },
                    tail = {
                        Switch(
                            checked = entry.matchWholeWords,
                            onCheckedChange = { onEdit(entry.copy(matchWholeWords = it)) }
                        )
                    }
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_exclude_recursion)) },
                    tail = {
                        Switch(
                            checked = entry.excludeRecursion,
                            onCheckedChange = { onEdit(entry.copy(excludeRecursion = it)) }
                        )
                    }
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_prevent_recursion)) },
                    tail = {
                        Switch(
                            checked = entry.preventRecursion,
                            onCheckedChange = { onEdit(entry.copy(preventRecursion = it)) }
                        )
                    }
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_delay_until_recursion)) },
                    tail = {
                        Switch(
                            checked = entry.delayUntilRecursion,
                            onCheckedChange = { onEdit(entry.copy(delayUntilRecursion = it)) }
                        )
                    }
                )

                // 次级关键词
                var newSecKey by remember { mutableStateOf("") }
                Text(stringResource(R.string.prompt_page_secondary_keys_label), style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    entry.secondaryKeys.forEach { keyword ->
                        InputChip(
                            selected = false,
                            onClick = {},
                            label = { Text(keyword) },
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        onEdit(entry.copy(secondaryKeys = entry.secondaryKeys - keyword))
                                    },
                                    modifier = Modifier.size(16.dp)
                                ) {
                                    Icon(HugeIcons.Cancel01, null, modifier = Modifier.size(12.dp))
                                }
                            }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newSecKey,
                        onValueChange = { newSecKey = it },
                        label = { Text(stringResource(R.string.prompt_page_new_keyword)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    IconButton(
                        onClick = {
                            if (newSecKey.isNotBlank()) {
                                onEdit(entry.copy(secondaryKeys = entry.secondaryKeys + newSecKey.trim()))
                                newSecKey = ""
                            }
                        }
                    ) {
                        Icon(HugeIcons.Add01, stringResource(R.string.prompt_page_add))
                    }
                }

                // Selective mode
                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_selective)) },
                    description = { Text(stringResource(R.string.prompt_page_selective_desc)) },
                    tail = {
                        Switch(
                            checked = entry.selective,
                            onCheckedChange = { onEdit(entry.copy(selective = it)) }
                        )
                    }
                )

                // 当 selective 启用时，显示 selectiveLogic
                AnimatedVisibility(visible = entry.selective) {
                    CardGroup(title = { Text(stringResource(R.string.prompt_page_selective_logic)) }) {
                        SelectiveLogic.entries.forEach { logic ->
                            item(
                                onClick = { onEdit(entry.copy(selectiveLogic = logic)) },
                                headlineContent = {
                                    Text(
                                        when (logic) {
                                            SelectiveLogic.AND_ANY -> "任一匹配(AND_ANY)"
                                            SelectiveLogic.AND_ALL -> "全部匹配(AND_ALL)"
                                            SelectiveLogic.OR_ANY -> "任一匹配(OR_ANY)"
                                            SelectiveLogic.NOT_ANY -> "均不匹配(NOT_ANY)"
                                            SelectiveLogic.NOT_ALL -> "非全部匹配(NOT_ALL)"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                },
                                trailingContent = {
                                    RadioButton(
                                        selected = entry.selectiveLogic == logic,
                                        onClick = { onEdit(entry.copy(selectiveLogic = logic)) },
                                    )
                                },
                            )
                        }
                    }
                }

                // 分组设置
                OutlinedTextField(
                    value = entry.group,
                    onValueChange = { onEdit(entry.copy(group = it)) },
                    label = { Text(stringResource(R.string.prompt_page_group)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = entry.groupWeight.toString(),
                        onValueChange = { it.toIntOrNull()?.let { w -> onEdit(entry.copy(groupWeight = w)) } },
                        label = { Text(stringResource(R.string.prompt_page_group_weight)) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    FormItem(
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.prompt_page_group_override)) },
                        tail = {
                            Switch(
                                checked = entry.groupOverride,
                                onCheckedChange = { onEdit(entry.copy(groupOverride = it)) }
                            )
                        }
                    )
                }

                // 概率
                FormItem(
                    label = { Text("触发概率(Probability)") },
                    tail = {
                        Switch(
                            checked = entry.useProbability,
                            onCheckedChange = { onEdit(entry.copy(useProbability = it)) }
                        )
                    }
                )
                AnimatedVisibility(visible = entry.useProbability) {
                    Column {
                        Text(
                            stringResource(R.string.prompt_page_probability, entry.probability),
                            style = MaterialTheme.typography.titleSmall
                        )
                        var localProb by remember { mutableFloatStateOf(entry.probability.toFloat()) }
                        Slider(
                            value = localProb,
                            onValueChange = { localProb = it },
                            onValueChangeFinished = { onEdit(entry.copy(probability = localProb.toInt())) },
                            valueRange = 0f..100f,
                            steps = 99
                        )
                    }
                }

                // 粘性 + 冷却
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = entry.sticky.toString(),
                        onValueChange = { it.toIntOrNull()?.let { s -> onEdit(entry.copy(sticky = s)) } },
                        label = { Text(stringResource(R.string.prompt_page_sticky)) },
                        supportingText = { Text(stringResource(R.string.prompt_page_sticky_desc)) },
                        suffix = { Text("轮", style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = entry.cooldown.toString(),
                        onValueChange = { it.toIntOrNull()?.let { c -> onEdit(entry.copy(cooldown = c)) } },
                        label = { Text(stringResource(R.string.prompt_page_cooldown)) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = entry.delay.toString(),
                        onValueChange = { it.toIntOrNull()?.let { d -> onEdit(entry.copy(delay = d)) } },
                        label = { Text("延迟(Delay)") },
                        supportingText = { Text("N轮后才开始检测触发") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    Spacer(Modifier.weight(1f))
                }

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_constant_active)) },
                    description = { Text(stringResource(R.string.prompt_page_constant_active_desc)) },
                    tail = {
                        Switch(
                            checked = entry.constantActive,
                            onCheckedChange = { onEdit(entry.copy(constantActive = it)) }
                        )
                    }
                )

                OutlinedTextField(
                    value = entry.scanDepth.toString(),
                    onValueChange = {
                        it.toIntOrNull()?.let { d -> onEdit(entry.copy(scanDepth = d)) }
                    },
                    label = { Text(stringResource(R.string.prompt_page_scan_depth)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                AnimatedVisibility(visible = entry.position.usesStandaloneMessage()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(R.string.prompt_page_injection_role),
                            style = MaterialTheme.typography.titleSmall
                        )
                        InjectionRoleSelector(
                            role = entry.role,
                            onSelect = { onEdit(entry.copy(role = it)) }
                        )
                    }
                }

                OutlinedTextField(
                    value = entry.content,
                    onValueChange = { onEdit(entry.copy(content = it)) },
                    label = { Text(stringResource(R.string.prompt_page_injection_content)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    minLines = 4
                )
            }
        },
        confirmButton = {
            val canSave = entry.keywords.isNotEmpty() || entry.constantActive
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
