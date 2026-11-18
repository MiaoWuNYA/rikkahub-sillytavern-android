package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import me.rerere.rikkahub.data.model.*

/** One title row; members are lazy siblings owned by LorebookEntryList, never a second view. */
@Composable
internal fun LorebookExclusiveGroupsTitle(onManage: () -> Unit, enabled: Boolean = true) {
    var showInfo by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("互斥组", style = MaterialTheme.typography.titleMedium)
        Box {
            TextButton(onClick = { showInfo = !showInfo }) { Text("ⓘ") }
            if (showInfo) LorebookReadOnlyPopup("互斥组说明", "lorebook-exclusive-help", "关闭说明", { showInfo = false }) {
                Text("互斥组用于控制条目之间的竞争触发，并非普通文件夹。符合条件的组内条目会按现有互斥规则选择参与注入。组内条目不会在普通条目区重复展示。")
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onManage, enabled = enabled) { Text("管理互斥组") }
    }
}

@Composable
internal fun LorebookExclusiveGroupHeader(
    group: String, entries: List<PromptInjection.RegexInjection>, index: Int, expanded: Boolean,
    onExpandedChange: () -> Unit, onSettings: () -> Unit, onAdd: () -> Unit,
    allowEdit: Boolean, insertion: LorebookInsertionState, feedback: () -> LorebookDragFeedback,
    onMove: (LorebookEntryPlacement) -> Unit, onMeasure: (LorebookDropTarget?) -> Unit,
) {
    val key = "exclusive:$group"
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    DisposableEffect(key) { onDispose { onMeasure(null) } }
    Box(Modifier.testTag("lorebook-group:$group").onGloballyPositioned { c ->
        coordinates = c
        val top = c.localToRoot(Offset.Zero).y
        onMeasure(LorebookDropTarget(null, key, top, top + c.size.height))
    }) {
        LorebookGroupSection(group, entries, onSettings, onAdd,
            canAddEntry = allowEdit, canEditGroup = allowEdit && entries.isNotEmpty(), groupIndex = index,
            expanded = expanded, onExpandedChange = onExpandedChange, exclusive = true,
            headerGesture = if (!allowEdit) null else lorebookInsertionHandle(insertion, true, true, feedback,
                onConfirm = { _, placement -> onMove(placement) }, onTap = onExpandedChange,
                source = LorebookDragSource.GROUP, groupName = key,
                origin = { coordinates?.takeIf { it.isAttached }?.let { Rect(it.localToRoot(Offset.Zero), Size(it.size.width.toFloat(), it.size.height.toFloat())) } })
                .semantics(mergeDescendants = true) { contentDescription = "拖动分组 $group" })
        val placement = feedback().placement
        if (placement?.categoryTarget == false && (placement.group == group || placement.group == key))
            HorizontalDivider(Modifier.align(Alignment.BottomCenter), thickness = 3.dp, color = MaterialTheme.colorScheme.primary)
    }
}
