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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

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
        topBar = { TopAppBar(title = { Text("生活空间") }, navigationIcon = { BackButton() }) },
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
                            Text("＋ 导入 TXT 小说")
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
                        Text("这里还没有记录，点击右下角开始。", modifier = Modifier.padding(8.dp))
                    }
                    if (section != LifeSection.HOME) items(filtered, key = { it.id }) { entry ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(entry.title, style = MaterialTheme.typography.titleLarge)
                                    if (entry.tag.isNotBlank()) Text(entry.tag, color = MaterialTheme.colorScheme.primary)
                                }
                                if (entry.detail.isNotBlank()) Text(entry.detail)
                                TextButton(onClick = { saveAll(entries.filterNot { it.id == entry.id }) }) { Text("删除") }
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
                placeholder = { Text("🔎 搜索一张小便签……") },
                singleLine = true,
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { MemoFilterChip("全部 ${entries.size}", filter == MemoFilter.ALL) { filter = MemoFilter.ALL } }
                item { MemoFilterChip("📌 置顶", filter == MemoFilter.PINNED) { filter = MemoFilter.PINNED } }
                item { MemoFilterChip("☐ 待办", filter == MemoFilter.TODO) { filter = MemoFilter.TODO } }
                item { MemoFilterChip("✓ 完成", filter == MemoFilter.DONE) { filter = MemoFilter.DONE } }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = categoryFilter == null,
                        onClick = { categoryFilter = null },
                        label = { Text("🎀 所有分类") },
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
                    Text("生活备忘板", color = Color(0xFF5A4650), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("把想记住的事情贴在这里。", color = Color(0xFF8A737D), style = MaterialTheme.typography.bodyMedium)
                }
                Surface(shape = CircleShape, color = Color(0xFFFFDFE9)) {
                    Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                        Text("📝", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MemoStatPill("☁", "待处理", todoCount, Color(0xFFFFE4D8), Color(0xFFB96F54), Modifier.weight(1f))
                MemoStatPill("✓", "完成啦", doneCount, Color(0xFFE4F1E8), Color(0xFF5E826B), Modifier.weight(1f))
                MemoStatPill("📌", "置顶", pinnedCount, Color(0xFFE9E4F7), Color(0xFF79649C), Modifier.weight(1f))
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
                Text("＋ 写一张小便签")
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
                if (completelyEmpty) "这里还没有小便签哦" else "没有找到这张小便签",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF64535A),
            )
            Text(
                if (completelyEmpty) "把想记住的事情贴上来吧。" else "换个关键词或筛选条件试试看～",
                color = Color(0xFF8C7A82),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (completelyEmpty) {
                TextButton(onClick = onAdd) { Text("贴第一张便签 ♡") }
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
    val reminderState = entry.reminderAt?.let { memoReminderState(it) }

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
                        if (entry.pinned) Text("📌 置顶", style = MaterialTheme.typography.labelSmall, color = category.accent)
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
                        "贴于 ${formatMemoDate(entry.createdAt)}",
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
                    Text("完成啦 ✓", color = category.accent.copy(alpha = 0.72f), style = MaterialTheme.typography.labelMedium)
                }
            }

            HorizontalDivider(color = category.accent.copy(alpha = 0.12f))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onToggleDone,
                    colors = ButtonDefaults.textButtonColors(contentColor = category.accent),
                ) {
                    Text(if (entry.completed) "↩ 恢复" else "✓ 完成")
                }
                Spacer(Modifier.weight(1f))
                if (entry.reminderAt != null) {
                    TextButton(onClick = onAddCalendar) { Text("📅") }
                }
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    Text("拿下")
                }
            }
        }
    }
}

private data class MemoReminderState(val label: String, val urgent: Boolean)

private fun memoReminderState(value: Long): MemoReminderState {
    val now = System.currentTimeMillis()
    val diff = value - now
    val day = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        diff < -TimeUnit.DAYS.toMillis(1) -> MemoReminderState("已过提醒日", false)
        diff <= 0L -> MemoReminderState("今天", true)
        diff < TimeUnit.DAYS.toMillis(1) -> MemoReminderState("今天", true)
        day == 1L -> MemoReminderState("明天", true)
        day in 2L..3L -> MemoReminderState("还有 $day 天", true)
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
                Text(if (initial == null) "🎀 写一张小便签" else "📝 修改这张便签")
                Text(
                    if (initial == null) "想到什么就先贴上来。" else "慢慢改，不着急。",
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
                    label = { Text("这张便签想记什么？") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = detail,
                    onValueChange = { detail = it },
                    label = { Text("写下一点内容……") },
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                )

                Text("放进哪个小抽屉？", style = MaterialTheme.typography.labelLarge)
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
                                Text("📌 贴到最上面", color = currentCategory.accent, fontWeight = FontWeight.SemiBold)
                                Text("重要的小事会一直排在前面", style = MaterialTheme.typography.bodySmall, color = Color(0xFF75666D))
                            }
                            Switch(checked = pinned, onCheckedChange = { pinned = it })
                        }
                        if (initial != null) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("✓ 已经完成啦", color = currentCategory.accent, fontWeight = FontWeight.SemiBold)
                                    Text("完成后便签会轻轻淡下来", style = MaterialTheme.typography.bodySmall, color = Color(0xFF75666D))
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
                        Text(reminderAt?.let { "⏰ ${formatMemoDate(it)}" } ?: "⏰ 选择提醒日期")
                    }
                    if (reminderAt != null) TextButton(onClick = { reminderAt = null }) { Text("清除") }
                }
                Text("提醒日期会留在便签上，也可以一键放进系统日历。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text(if (initial == null) "收进备忘板 ♡" else "保存这张便签")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("先不写") } },
    )

    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = reminderAt ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    reminderAt = state.selectedDateMillis
                    showDatePicker = false
                }) { Text("贴上这个日期") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
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
        LifeSection.STATUS -> Triple("今天感觉怎么样", "身体感受或想让 AI 知道的事", "心情 / 精力")
        LifeSection.MEMO -> Triple("备忘标题", "计划或想法", "分类")
        LifeSection.CALENDAR -> Triple("安排或提醒标题", "时间、地点和需要 AI 提醒的事情", "今天 / 本周 / 纪念日")
        LifeSection.MUSIC -> Triple("歌曲名", "歌手、故事或一起听歌的回忆", "想念 / 开心 / 安慰")
        LifeSection.READING -> Triple("书名或章节", "阅读进度、原文、你的批注以及想问 AI 的问题", "普通书签 / 情绪书签 / 猜想书签 / 记忆书签")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加${section.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(labels.first) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(detail, { detail = it }, label = { Text(labels.second) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(tag, { tag = it }, label = { Text(labels.third) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { onSave(title.trim(), detail.trim(), tag.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
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
