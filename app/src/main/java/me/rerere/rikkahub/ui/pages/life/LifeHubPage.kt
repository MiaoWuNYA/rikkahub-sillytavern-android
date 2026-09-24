package me.rerere.rikkahub.ui.pages.life

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

// 分区标题/提示同时用于 UI 显示与持久化数据标识（加载时按 tag 迁移分类），
// 必须保持字面量稳定，不随语言切换。
private enum class LifeSection(val title: String, val emoji: String, val hint: String) {
    HOME("今日", "🏡", "今天的状态、安排与共同生活"),
    STATUS("周期与身体", "🌸", "记录经期、周期、身体状态、心情与精力"),
    MEMO("备忘录", "📝", "把想法、待办和两个人的小计划好好收起来"),
    CALENDAR("日历提醒", "📅", "把计划和纪念日加入系统日历"),
    MUSIC("一起听", "🎵", "导入歌曲、一起听歌并留下共同音乐记忆"),
    READING("共读书架", "📖", "记录书籍、进度、书签和共同想法"),
}

private data class LifeEntry(
    val id: Long = System.currentTimeMillis(),
    val section: LifeSection,
    val title: String,
    val detail: String,
    val tag: String,
    val createdAt: Long = System.currentTimeMillis(),
    val memoCategory: String = "life",
    val pinned: Boolean = false,
    val completed: Boolean = false,
    val reminderAt: Long? = null,
)

private data class MemoCategory(
    val id: String,
    val label: String,
    val emoji: String,
    val background: Color,
    val accent: Color,
    val soft: Color,
)

// 备忘分类名持久化在 SharedPreferences 中（memoCategory id 与 tag 迁移都依赖字面量），
// 必须保持字面量稳定，不随语言切换。
private val memoCategories = listOf(
    MemoCategory("life", "生活", "🏡", Color(0xFFFFF8EE), Color(0xFFB98653), Color(0xFFFFEBD4)),
    MemoCategory("todo", "待办", "⏰", Color(0xFFFFF0E6), Color(0xFFC77B52), Color(0xFFFFDFC9)),
    MemoCategory("idea", "灵感", "💡", Color(0xFFF4EFFB), Color(0xFF8066A5), Color(0xFFE8DCF8)),
    MemoCategory("together", "我们的", "💕", Color(0xFFFFEEF4), Color(0xFFC16683), Color(0xFFFFD8E5)),
    MemoCategory("ai", "TA 的", "🐰", Color(0xFFEDF5FC), Color(0xFF6286A3), Color(0xFFDCECF8)),
)

private fun memoCategory(id: String): MemoCategory =
    memoCategories.firstOrNull { it.id == id } ?: memoCategories.first()

private enum class MemoFilter { ALL, PINNED, TODO, DONE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifeHubPage() {
    val context = LocalContext.current
    var section by remember { mutableStateOf(LifeSection.HOME) }
    var entries by remember { mutableStateOf(loadEntries(context)) }
    var showAdd by remember { mutableStateOf(false) }
    var editingMemoId by remember { mutableStateOf<Long?>(null) }
    val deleteText = stringResource(R.string.delete)

    val saveAll: (List<LifeEntry>) -> Unit = { updated ->
        entries = updated
        saveEntries(context, updated)
    }

    val bookImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "导入的小说"
            val preview = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText().take(800) }
            }.getOrNull().orEmpty()
            saveAll(entries + LifeEntry(section = LifeSection.READING, title = name, detail = preview, tag = "刚刚导入"))
            section = LifeSection.READING
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.life_hub_title)) }, navigationIcon = { BackButton() }) },
        floatingActionButton = {
            if (section != LifeSection.HOME &&
                section != LifeSection.STATUS &&
                section != LifeSection.CALENDAR &&
                section != LifeSection.MUSIC &&
                section != LifeSection.READING
            ) {
                FloatingActionButton(onClick = { showAdd = true }) {
                    Text(if (section == LifeSection.MEMO) "✎" else "＋", style = MaterialTheme.typography.titleLarge)
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(selectedTabIndex = section.ordinal, edgePadding = 8.dp) {
                LifeSection.entries.forEach { item ->
                    Tab(
                        selected = section == item,
                        onClick = { section = item },
                        text = { Text(item.emoji + " " + item.title) },
                    )
                }
            }

            if (section == LifeSection.STATUS) {
                HealthCyclePanel()
            } else if (section == LifeSection.MEMO) {
                MemoBoard(
                    entries = entries.filter { it.section == LifeSection.MEMO },
                    onAdd = { showAdd = true },
                    onEdit = { editingMemoId = it.id },
                    onPin = { entry -> saveAll(entries.map { if (it.id == entry.id) it.copy(pinned = !it.pinned) else it }) },
                    onToggleDone = { entry -> saveAll(entries.map { if (it.id == entry.id) it.copy(completed = !it.completed) else it }) },
                    onDelete = { entry -> saveAll(entries.filterNot { it.id == entry.id }) },
                    onAddCalendar = { openMemoCalendar(context, it) },
                )
            } else if (section == LifeSection.CALENDAR) {
                LifeCalendarPanel()
            } else if (section == LifeSection.MUSIC) {
                MusicSpacePanel()
            } else if (section == LifeSection.READING) {
                ReadingSpacePanel()
            } else {
                val filtered = entries.filter { it.section == section }.sortedByDescending { it.createdAt }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 18.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(section.emoji + " " + section.title, style = MaterialTheme.typography.headlineSmall)
                                Text(section.hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (section == LifeSection.READING) item {
                        TextButton(onClick = { bookImporter.launch(arrayOf("text/plain", "text/*")) }) {
                            Text(stringResource(R.string.life_import_txt))
                        }
                    }
                    if (section == LifeSection.HOME) {
                        items(LifeSection.entries.filterNot { it == LifeSection.HOME }) { destination ->
                            val latest = entries.filter { it.section == destination }.maxByOrNull { it.createdAt }
                            Card(onClick = { section = destination }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(destination.emoji + " " + destination.title, style = MaterialTheme.typography.titleLarge)
                                    Text(latest?.title ?: destination.hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    } else if (filtered.isEmpty()) item {
                        Text(stringResource(R.string.life_hub_empty), modifier = Modifier.padding(8.dp))
                    }
                    if (section != LifeSection.HOME) items(filtered, key = { it.id }) { entry ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(entry.title, style = MaterialTheme.typography.titleLarge)
                                    if (entry.tag.isNotBlank()) Text(entry.tag, color = MaterialTheme.colorScheme.primary)
                                }
                                if (entry.detail.isNotBlank()) Text(entry.detail)
                                TextButton(onClick = { saveAll(entries.filterNot { it.id == entry.id }) }) { Text(deleteText) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        if (section == LifeSection.MEMO) {
            MemoEditorDialog(
                initial = null,
                onDismiss = { showAdd = false },
                onSave = { title, detail, category, pinned, completed, reminderAt ->
                    saveAll(
                        entries + LifeEntry(
                            section = LifeSection.MEMO,
                            title = title,
                            detail = detail,
                            tag = "",
                            memoCategory = category,
                            pinned = pinned,
                            completed = completed,
                            reminderAt = reminderAt,
                        )
                    )
                    showAdd = false
                },
            )
        } else {
            AddLifeEntryDialog(
                section = section,
                onDismiss = { showAdd = false },
                onSave = { title, detail, tag ->
                    saveAll(entries + LifeEntry(section = section, title = title, detail = detail, tag = tag))
                    showAdd = false
                },
            )
        }
    }

    editingMemoId?.let { id ->
        entries.firstOrNull { it.id == id }?.let { entry ->
            MemoEditorDialog(
                initial = entry,
                onDismiss = { editingMemoId = null },
                onSave = { title, detail, category, pinned, completed, reminderAt ->
                    saveAll(entries.map {
                        if (it.id == entry.id) it.copy(
                            title = title,
                            detail = detail,
                            memoCategory = category,
                            pinned = pinned,
                            completed = completed,
                            reminderAt = reminderAt,
                        ) else it
                    })
                    editingMemoId = null
                },
            )
        }
    }
}

@Composable
private fun MemoBoard(
    entries: List<LifeEntry>,
    onAdd: () -> Unit,
    onEdit: (LifeEntry) -> Unit,
    onPin: (LifeEntry) -> Unit,
    onToggleDone: (LifeEntry) -> Unit,
    onDelete: (LifeEntry) -> Unit,
    onAddCalendar: (LifeEntry) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(MemoFilter.ALL) }
    var categoryFilter by remember { mutableStateOf<String?>(null) }

    val filtered = entries
        .asSequence()
        .filter { query.isBlank() || it.title.contains(query, true) || it.detail.contains(query, true) }
        .filter {
            when (filter) {
                MemoFilter.ALL -> true
                MemoFilter.PINNED -> it.pinned
                MemoFilter.TODO -> !it.completed
                MemoFilter.DONE -> it.completed
            }
        }
        .filter { categoryFilter == null || it.memoCategory == categoryFilter }
        .sortedWith(compareByDescending<LifeEntry> { it.pinned }.thenBy { it.completed }.thenByDescending { it.createdAt })
        .toList()

    val todoCount = entries.count { !it.completed }
    val doneCount = entries.count { it.completed }
    val pinnedCount = entries.count { it.pinned }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp, 16.dp, 14.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            MemoBoardHeader(
                todoCount = todoCount,
                doneCount = doneCount,
                pinnedCount = pinnedCount,
                onAdd = onAdd,
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                placeholder = { Text(stringResource(R.string.life_memo_search_placeholder)) },
                singleLine = true,
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { MemoFilterChip(stringResource(R.string.life_memo_filter_all, entries.size), filter == MemoFilter.ALL) { filter = MemoFilter.ALL } }
                item { MemoFilterChip(stringResource(R.string.life_memo_filter_pinned), filter == MemoFilter.PINNED) { filter = MemoFilter.PINNED } }
                item { MemoFilterChip(stringResource(R.string.life_memo_filter_todo), filter == MemoFilter.TODO) { filter = MemoFilter.TODO } }
                item { MemoFilterChip(stringResource(R.string.life_memo_filter_done), filter == MemoFilter.DONE) { filter = MemoFilter.DONE } }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = categoryFilter == null,
                        onClick = { categoryFilter = null },
                        label = { Text(stringResource(R.string.life_memo_filter_all_categories)) },
                        shape = RoundedCornerShape(18.dp),
                    )
                }
                items(memoCategories) { category ->
                    FilterChip(
                        selected = categoryFilter == category.id,
                        onClick = { categoryFilter = if (categoryFilter == category.id) null else category.id },
                        label = { Text("${category.emoji} ${category.label}") },
                        shape = RoundedCornerShape(18.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = category.soft,
                            selectedLabelColor = category.accent,
                        ),
                    )
                }
            }
        }
        if (filtered.isEmpty()) {
            item {
                MemoEmptyState(
                    completelyEmpty = entries.isEmpty(),
                    onAdd = onAdd,
                )
            }
        }
        items(filtered, key = { it.id }) { entry ->
            MemoCard(
                entry = entry,
                onEdit = { onEdit(entry) },
                onPin = { onPin(entry) },
                onToggleDone = { onToggleDone(entry) },
                onDelete = { onDelete(entry) },
                onAddCalendar = { onAddCalendar(entry) },
            )
        }
    }
}

@Composable
private fun MemoBoardHeader(
    todoCount: Int,
    doneCount: Int,
    pinnedCount: Int,
    onAdd: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = Color(0xFFFFF3F7),
        border = BorderStroke(1.dp, Color(0xFFF3CDD9)),
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("୨୧  LIFE MEMO", color = Color(0xFFB45E7A), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.life_memo_board_title), color = Color(0xFF5A4650), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.life_memo_board_desc), color = Color(0xFF8A737D), style = MaterialTheme.typography.bodyMedium)
                }
                Surface(shape = CircleShape, color = Color(0xFFFFDFE9)) {
                    Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                        Text("📝", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MemoStatPill("☁", stringResource(R.string.life_memo_stat_todo), todoCount, Color(0xFFFFE4D8), Color(0xFFB96F54), Modifier.weight(1f))
                MemoStatPill("✓", stringResource(R.string.life_memo_stat_done), doneCount, Color(0xFFE4F1E8), Color(0xFF5E826B), Modifier.weight(1f))
                MemoStatPill("📌", stringResource(R.string.life_memo_stat_pinned), pinnedCount, Color(0xFFE9E4F7), Color(0xFF79649C), Modifier.weight(1f))
            }

            FilledTonalButton(
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color(0xFFFFDDE8),
                    contentColor = Color(0xFF9F4F6A),
                ),
            ) {
                Text(stringResource(R.string.life_memo_add))
            }
        }
    }
}

@Composable
private fun MemoStatPill(
    emoji: String,
    label: String,
    count: Int,
    background: Color,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = background) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$emoji $count", color = accent, fontWeight = FontWeight.Bold)
            Text(label, color = accent.copy(alpha = 0.78f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MemoFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = RoundedCornerShape(18.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFFFFE1EB),
            selectedLabelColor = Color(0xFFA95270),
        ),
    )
}

@Composable
private fun MemoEmptyState(completelyEmpty: Boolean, onAdd: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFFFFFAF7),
        border = BorderStroke(1.dp, Color(0xFFF0E2DD)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (completelyEmpty) "🎀📝" else "☁️", style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(if (completelyEmpty) R.string.life_memo_empty_all_title else R.string.life_memo_empty_filter_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF64535A),
            )
            Text(
                stringResource(if (completelyEmpty) R.string.life_memo_empty_all_desc else R.string.life_memo_empty_filter_desc),
                color = Color(0xFF8C7A82),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (completelyEmpty) {
                TextButton(onClick = onAdd) { Text(stringResource(R.string.life_memo_empty_add)) }
            }
        }
    }
}

@Composable
private fun MemoCard(
    entry: LifeEntry,
    onEdit: () -> Unit,
    onPin: () -> Unit,
    onToggleDone: () -> Unit,
    onDelete: () -> Unit,
    onAddCalendar: () -> Unit,
) {
    val category = memoCategory(entry.memoCategory)
    val deleteText = stringResource(R.string.delete)
    val reminderState = entry.reminderAt?.let {
        memoReminderState(
            value = it,
            passed = stringResource(R.string.life_memo_reminder_passed),
            today = stringResource(R.string.life_memo_reminder_today),
            tomorrow = stringResource(R.string.life_memo_reminder_tomorrow),
            inDays = stringResource(R.string.life_memo_reminder_in_days, TimeUnit.MILLISECONDS.toDays(it - System.currentTimeMillis())),
        )
    }
    val editText = stringResource(R.string.edit)

    Card(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.completed) category.background.copy(alpha = 0.72f) else category.background,
        ),
        border = BorderStroke(
            if (entry.pinned || reminderState?.urgent == true) 1.5.dp else 1.dp,
            when {
                reminderState?.urgent == true -> category.accent.copy(alpha = 0.72f)
                entry.pinned -> category.accent.copy(alpha = 0.50f)
                else -> category.accent.copy(alpha = 0.18f)
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = CircleShape, color = category.soft) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        Text(category.emoji)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(category.label, style = MaterialTheme.typography.labelMedium, color = category.accent)
                        if (entry.pinned) Text(stringResource(R.string.life_memo_pinned_tag), style = MaterialTheme.typography.labelSmall, color = category.accent)
                    }
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = if (entry.completed) TextDecoration.LineThrough else TextDecoration.None,
                        color = Color(0xFF574A50).copy(alpha = if (entry.completed) 0.60f else 1f),
                    )
                }
                TextButton(
                    onClick = onPin,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = category.accent),
                ) {
                    Text(if (entry.pinned) "♡" else "📌")
                }
            }

            if (entry.detail.isNotBlank()) {
                Text(
                    entry.detail,
                    color = Color(0xFF6F6167).copy(alpha = if (entry.completed) 0.52f else 0.88f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(12.dp), color = category.soft.copy(alpha = 0.78f)) {
                    Text(
                        stringResource(R.string.life_memo_posted_at, formatMemoDate(entry.createdAt)),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        color = category.accent,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                reminderState?.let { state ->
                    Surface(shape = RoundedCornerShape(12.dp), color = if (state.urgent) category.accent.copy(alpha = 0.14f) else category.soft) {
                        Text(
                            "⏰ ${state.label}",
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            color = category.accent,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (state.urgent) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }

            if (entry.completed) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(stringResource(R.string.life_memo_completed_tag), color = category.accent.copy(alpha = 0.72f), style = MaterialTheme.typography.labelMedium)
                }
            }

            HorizontalDivider(color = category.accent.copy(alpha = 0.12f))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onToggleDone,
                    colors = ButtonDefaults.textButtonColors(contentColor = category.accent),
                ) {
                    Text(if (entry.completed) stringResource(R.string.life_memo_restore) else stringResource(R.string.life_memo_mark_done))
                }
                Spacer(Modifier.weight(1f))
                if (entry.reminderAt != null) {
                    TextButton(onClick = onAddCalendar) { Text("📅") }
                }
                TextButton(onClick = onEdit) { Text(editText) }
                TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    Text(deleteText)
                }
            }
        }
    }
}

private data class MemoReminderState(val label: String, val urgent: Boolean)

private fun memoReminderState(value: Long, passed: String, today: String, tomorrow: String, inDays: String): MemoReminderState {
    val now = System.currentTimeMillis()
    val diff = value - now
    val day = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        diff < -TimeUnit.DAYS.toMillis(1) -> MemoReminderState(passed, false)
        diff <= 0L -> MemoReminderState(today, true)
        diff < TimeUnit.DAYS.toMillis(1) -> MemoReminderState(today, true)
        day == 1L -> MemoReminderState(tomorrow, true)
        day in 2L..3L -> MemoReminderState(inDays, true)
        else -> MemoReminderState(formatMemoDate(value), false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoEditorDialog(
    initial: LifeEntry?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Boolean, Boolean, Long?) -> Unit,
) {
    var title by remember(initial?.id) { mutableStateOf(initial?.title.orEmpty()) }
    var detail by remember(initial?.id) { mutableStateOf(initial?.detail.orEmpty()) }
    var category by remember(initial?.id) { mutableStateOf(initial?.memoCategory ?: "life") }
    var pinned by remember(initial?.id) { mutableStateOf(initial?.pinned ?: false) }
    var completed by remember(initial?.id) { mutableStateOf(initial?.completed ?: false) }
    var reminderAt by remember(initial?.id) { mutableStateOf(initial?.reminderAt) }
    var showDatePicker by remember { mutableStateOf(false) }
    val currentCategory = memoCategory(category)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(26.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(if (initial == null) R.string.life_memo_editor_new_title else R.string.life_memo_editor_edit_title))
                Text(
                    stringResource(if (initial == null) R.string.life_memo_editor_new_desc else R.string.life_memo_editor_edit_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(Modifier.heightIn(max = 580.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.life_memo_editor_title_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = detail,
                    onValueChange = { detail = it },
                    label = { Text(stringResource(R.string.life_memo_editor_detail_label)) },
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                )

                Text(stringResource(R.string.life_memo_editor_category_label), style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(memoCategories) { item ->
                        FilterChip(
                            selected = category == item.id,
                            onClick = { category = item.id },
                            label = { Text("${item.emoji} ${item.label}") },
                            shape = RoundedCornerShape(18.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = item.soft,
                                selectedLabelColor = item.accent,
                            ),
                        )
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = currentCategory.background,
                    border = BorderStroke(1.dp, currentCategory.accent.copy(alpha = 0.18f)),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.life_memo_editor_pin), color = currentCategory.accent, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.life_memo_editor_pin_desc), style = MaterialTheme.typography.bodySmall, color = Color(0xFF75666D))
                            }
                            Switch(checked = pinned, onCheckedChange = { pinned = it })
                        }
                        if (initial != null) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.life_memo_editor_completed), color = currentCategory.accent, fontWeight = FontWeight.SemiBold)
                                    Text(stringResource(R.string.life_memo_editor_completed_desc), style = MaterialTheme.typography.bodySmall, color = Color(0xFF75666D))
                                }
                                Switch(checked = completed, onCheckedChange = { completed = it })
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(reminderAt?.let { stringResource(R.string.life_memo_editor_reminder_set, formatMemoDate(it)) } ?: stringResource(R.string.life_memo_editor_reminder_pick))
                    }
                    if (reminderAt != null) TextButton(onClick = { reminderAt = null }) { Text(stringResource(R.string.life_memo_editor_clear)) }
                }
                Text(stringResource(R.string.life_memo_editor_reminder_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            FilledTonalButton(
                enabled = title.isNotBlank(),
                onClick = { onSave(title.trim(), detail.trim(), category, pinned, completed, reminderAt) },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color(0xFFFFDDE8),
                    contentColor = Color(0xFF9F4F6A),
                ),
            ) {
                Text(stringResource(if (initial == null) R.string.life_memo_editor_save_new else R.string.life_memo_editor_save_edit))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.life_memo_editor_dismiss)) } },
    )

    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = reminderAt ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    reminderAt = state.selectedDateMillis
                    showDatePicker = false
                }) { Text(stringResource(R.string.life_memo_editor_pick_date)) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = state, showModeToggle = false) }
    }
}

@Composable
private fun AddLifeEntryDialog(
    section: LifeSection,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var title by remember(section) { mutableStateOf("") }
    var detail by remember(section) { mutableStateOf("") }
    var tag by remember(section) { mutableStateOf("") }
    val labels = when (section) {
        LifeSection.HOME -> Triple("", "", "")
        LifeSection.STATUS -> Triple(
            stringResource(R.string.life_entry_status_title),
            stringResource(R.string.life_entry_status_detail),
            stringResource(R.string.life_entry_status_tag),
        )
        LifeSection.MEMO -> Triple(
            stringResource(R.string.life_entry_memo_title),
            stringResource(R.string.life_entry_memo_detail),
            stringResource(R.string.life_entry_memo_tag),
        )
        LifeSection.CALENDAR -> Triple(
            stringResource(R.string.life_entry_calendar_title),
            stringResource(R.string.life_entry_calendar_detail),
            stringResource(R.string.life_entry_calendar_tag),
        )
        LifeSection.MUSIC -> Triple(
            stringResource(R.string.life_entry_music_title),
            stringResource(R.string.life_entry_music_detail),
            stringResource(R.string.life_entry_music_tag),
        )
        LifeSection.READING -> Triple(
            stringResource(R.string.life_entry_reading_title),
            stringResource(R.string.life_entry_reading_detail),
            stringResource(R.string.life_entry_reading_tag),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.life_entry_add_title, section.title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(labels.first) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(detail, { detail = it }, label = { Text(labels.second) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(tag, { tag = it }, label = { Text(labels.third) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { onSave(title.trim(), detail.trim(), tag.trim()) }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun openCalendar(context: Context, entry: LifeEntry) {
    val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).apply {
        putExtra(CalendarContract.Events.TITLE, entry.title)
        putExtra(CalendarContract.Events.DESCRIPTION, entry.detail)
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, System.currentTimeMillis() + 60 * 60 * 1000)
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, System.currentTimeMillis() + 2 * 60 * 60 * 1000)
    }
    context.startActivity(intent)
}

private fun openMemoCalendar(context: Context, entry: LifeEntry) {
    val start = entry.reminderAt ?: return
    val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).apply {
        putExtra(CalendarContract.Events.TITLE, entry.title)
        putExtra(CalendarContract.Events.DESCRIPTION, entry.detail)
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 60 * 60 * 1000)
    }
    context.startActivity(intent)
}

private fun formatMemoDate(value: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(value))

private fun loadEntries(context: Context): List<LifeEntry> = runCatching {
    val raw = context.getSharedPreferences("tumin_life_hub", Context.MODE_PRIVATE).getString("entries", "[]") ?: "[]"
    val array = JSONArray(raw)
    buildList {
        repeat(array.length()) { index ->
            val item = array.getJSONObject(index)
            add(
                LifeEntry(
                    id = item.getLong("id"),
                    section = LifeSection.valueOf(item.getString("section")),
                    title = item.getString("title"),
                    detail = item.optString("detail"),
                    tag = item.optString("tag"),
                    createdAt = item.optLong("createdAt", item.getLong("id")),
                    memoCategory = item.optString("memoCategory").takeIf { it.isNotBlank() }
                        ?: when (item.optString("tag")) {
                            "AI", "TA 的" -> "ai"
                            "我们的" -> "together"
                            else -> "life"
                        },
                    pinned = item.optBoolean("pinned", false),
                    completed = item.optBoolean("completed", false),
                    reminderAt = item.optLong("reminderAt", 0L).takeIf { it > 0L },
                ),
            )
        }
    }
}.getOrDefault(emptyList())

private fun saveEntries(context: Context, entries: List<LifeEntry>) {
    val array = JSONArray()
    entries.forEach { entry ->
        array.put(JSONObject().apply {
            put("id", entry.id)
            put("section", entry.section.name)
            put("title", entry.title)
            put("detail", entry.detail)
            put("tag", entry.tag)
            put("createdAt", entry.createdAt)
            put("memoCategory", entry.memoCategory)
            put("pinned", entry.pinned)
            put("completed", entry.completed)
            entry.reminderAt?.let { put("reminderAt", it) }
        })
    }
    context.getSharedPreferences("tumin_life_hub", Context.MODE_PRIVATE).edit().putString("entries", array.toString()).apply()
}
