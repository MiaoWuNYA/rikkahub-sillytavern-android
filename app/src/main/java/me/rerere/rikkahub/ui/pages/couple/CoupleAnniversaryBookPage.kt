package me.rerere.rikkahub.ui.pages.couple

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit
import me.rerere.rikkahub.data.db.entity.CoupleAnniversaryEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.androidx.compose.koinViewModel

private data class AnniversaryCategory(
    val id: String,
    val label: String,
    val emoji: String,
    val container: Color,
    val accent: Color,
)

private val anniversaryCategories = listOf(
    AnniversaryCategory("love", "恋爱", "♡", Color(0xFFFFEEF3), Color(0xFFC65D79)),
    AnniversaryCategory("birthday", "生日", "🎂", Color(0xFFFFF3DF), Color(0xFFB87A27)),
    AnniversaryCategory("travel", "旅行", "✈", Color(0xFFEAF5FA), Color(0xFF4D829C)),
    AnniversaryCategory("promise", "约定", "✦", Color(0xFFF1ECFA), Color(0xFF7E66A3)),
    AnniversaryCategory("memory", "纪念", "❦", Color(0xFFEDF4EA), Color(0xFF657E5D)),
)

private fun categoryFor(id: String): AnniversaryCategory =
    anniversaryCategories.firstOrNull { it.id == id } ?: anniversaryCategories.first()

private enum class AnniversaryFilter { ALL, FAVORITE, UPCOMING }

@Composable
fun CoupleAnniversaryBookPage(vm: CoupleVM = koinViewModel()) {
    val entries by vm.anniversaries.collectAsStateWithLifecycle()
    val relationship by vm.relationship.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val partner = settings.assistants.firstOrNull { it.id.toString() == relationship?.assistantId }
    val partnerName = partner?.name?.ifBlank { "TA" } ?: "TA"
    val userName = settings.displaySetting.userNickname.ifBlank { "我" }

    var filter by remember { mutableStateOf(AnniversaryFilter.ALL) }
    var showEditor by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var viewingId by remember { mutableStateOf<String?>(null) }

    val now = System.currentTimeMillis()
    val nextEntry = entries
        .filter { daysUntil(it, now) >= 0L }
        .minByOrNull { nextOccurrence(it, now) }
    val visible = when (filter) {
        AnniversaryFilter.ALL -> entries.sortedBy { nextOccurrence(it, now) }
        AnniversaryFilter.FAVORITE -> entries.filter { it.favorite }.sortedBy { nextOccurrence(it, now) }
        AnniversaryFilter.UPCOMING -> entries.filter { daysUntil(it, now) in 0L..30L }.sortedBy { nextOccurrence(it, now) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("纪念册") }, navigationIcon = { BackButton() }) },
        floatingActionButton = { FloatingActionButton(onClick = { showEditor = true }) { Text("＋") } },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AnniversaryHero(
                    userName = userName,
                    partnerName = partnerName,
                    startedAt = relationship?.startedAt,
                    entryCount = entries.size,
                    nextEntry = nextEntry,
                )
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { FilterChip(selected = filter == AnniversaryFilter.ALL, onClick = { filter = AnniversaryFilter.ALL }, label = { Text("全部 ${entries.size}") }) }
                    item { FilterChip(selected = filter == AnniversaryFilter.UPCOMING, onClick = { filter = AnniversaryFilter.UPCOMING }, label = { Text("30天内") }) }
                    item { FilterChip(selected = filter == AnniversaryFilter.FAVORITE, onClick = { filter = AnniversaryFilter.FAVORITE }, label = { Text("珍藏 ${entries.count { it.favorite }}") }) }
                }
            }
            if (visible.isEmpty()) {
                item {
                    Text(
                        when (filter) {
                            AnniversaryFilter.ALL -> "还没有纪念日。把第一个重要日子收藏进来吧。"
                            AnniversaryFilter.FAVORITE -> "还没有珍藏的纪念日。"
                            AnniversaryFilter.UPCOMING -> "未来 30 天没有即将到来的纪念日。"
                        },
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(visible, key = { it.id }) { entry ->
                AnniversaryCard(
                    entry = entry,
                    now = now,
                    onClick = { viewingId = entry.id },
                    onFavorite = { vm.toggleAnniversaryFavorite(entry) },
                )
            }
        }
    }

    if (showEditor) {
        AnniversaryEditorDialog(
            initial = null,
            onDismiss = { showEditor = false },
            onSave = { title, date, yearly, category, note ->
                vm.addAnniversary(title, date, yearly, category, note)
                showEditor = false
            },
        )
    }

    editingId?.let { id ->
        entries.firstOrNull { it.id == id }?.let { entry ->
            AnniversaryEditorDialog(
                initial = entry,
                onDismiss = { editingId = null },
                onSave = { title, date, yearly, category, note ->
                    vm.updateAnniversary(entry, title, date, yearly, category, note)
                    editingId = null
                    viewingId = entry.id
                },
            )
        }
    }

    viewingId?.let { id ->
        entries.firstOrNull { it.id == id }?.let { entry ->
            AnniversaryDetailDialog(
                entry = entry,
                partnerName = partnerName,
                now = now,
                onDismiss = { viewingId = null },
                onFavorite = { vm.toggleAnniversaryFavorite(entry) },
                onEdit = {
                    viewingId = null
                    editingId = entry.id
                },
                onDelete = {
                    vm.deleteAnniversary(entry)
                    viewingId = null
                },
            )
        }
    }
}

@Composable
private fun AnniversaryHero(
    userName: String,
    partnerName: String,
    startedAt: Long?,
    entryCount: Int,
    nextEntry: CoupleAnniversaryEntity?,
) {
    val daysTogether = startedAt?.let {
        TimeUnit.MILLISECONDS.toDays((System.currentTimeMillis() - it).coerceAtLeast(0)) + 1
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        shape = RoundedCornerShape(26.dp),
        color = Color(0xFF6F4051),
        border = BorderStroke(1.dp, Color(0xFFE8B9C5).copy(alpha = 0.35f)),
        shadowElevation = 5.dp,
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("❦  OUR ANNIVERSARY BOOK", color = Color(0xFFE8B9C5), style = MaterialTheme.typography.labelLarge)
            Text("值得记住的日子", color = Color(0xFFFFF7F8), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("$userName × $partnerName", color = Color(0xFFFFF7F8).copy(alpha = 0.86f), style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(color = Color(0xFFE8B9C5).copy(alpha = 0.25f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(daysTogether?.let { "相伴第 $it 天" } ?: "属于你们的纪念册", color = Color.White)
                    Text("已经收藏 $entryCount 个日子", color = Color.White.copy(alpha = 0.66f), style = MaterialTheme.typography.bodySmall)
                }
                nextEntry?.let {
                    val days = daysUntil(it, System.currentTimeMillis())
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (days == 0L) "就是今天 ♡" else "还有 $days 天", color = Color(0xFFFFD8E2), fontWeight = FontWeight.Bold)
                        Text(it.title, color = Color.White.copy(alpha = 0.74f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnniversaryCard(
    entry: CoupleAnniversaryEntity,
    now: Long,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    val category = categoryFor(entry.category)
    val days = daysUntil(entry, now)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        colors = CardDefaults.cardColors(containerColor = category.container),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = CircleShape, color = category.accent.copy(alpha = 0.14f)) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    Text(category.emoji, style = MaterialTheme.typography.titleLarge, color = category.accent)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${category.label} · ${formatAnniversaryDate(entry.eventDate)}${if (entry.yearly) " · 每年" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                entry.note?.takeIf { it.isNotBlank() }?.let {
                    Text(it.replace("\n", " ").take(70) + if (it.length > 70) "…" else "", style = MaterialTheme.typography.bodySmall)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (days == 0L) "今天" else if (days > 0) "$days 天" else "已过去", color = category.accent, fontWeight = FontWeight.Bold)
                TextButton(onClick = onFavorite, colors = ButtonDefaults.textButtonColors(contentColor = category.accent)) {
                    Text(if (entry.favorite) "♥" else "♡")
                }
            }
        }
    }
}

@Composable
private fun AnniversaryEditorDialog(
    initial: CoupleAnniversaryEntity?,
    onDismiss: () -> Unit,
    onSave: (String, Long, Boolean, String, String?) -> Unit,
) {
    var title by remember(initial?.id) { mutableStateOf(initial?.title.orEmpty()) }
    var date by remember(initial?.id) { mutableLongStateOf(initial?.eventDate ?: System.currentTimeMillis()) }
    var yearly by remember(initial?.id) { mutableStateOf(initial?.yearly ?: true) }
    var category by remember(initial?.id) { mutableStateOf(initial?.category ?: "love") }
    var note by remember(initial?.id) { mutableStateOf(initial?.note.orEmpty()) }
    var showDatePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "收藏一个重要日子" else "编辑纪念日") },
        text = {
            Column(Modifier.heightIn(max = 540.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, modifier = Modifier.fillMaxWidth(), label = { Text("这一天叫什么？") }, singleLine = true)
                Text("类型", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(anniversaryCategories) { item ->
                        FilterChip(selected = category == item.id, onClick = { category = item.id }, label = { Text("${item.emoji} ${item.label}") })
                    }
                }
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("日期 · ${formatAnniversaryDate(date)}")
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("每年纪念")
                        Text("生日、恋爱纪念日等会自动计算下一次", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = yearly, onCheckedChange = { yearly = it })
                }
                OutlinedTextField(note, { note = it }, modifier = Modifier.fillMaxWidth(), label = { Text("这一天的小故事 / 备注") }, minLines = 4)
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { onSave(title.trim(), date, yearly, category, note.trim().takeIf { it.isNotBlank() }) }) {
                Text(if (initial == null) "收藏这一天" else "保存修改")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    date = state.selectedDateMillis ?: date
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) { DatePicker(state = state, showModeToggle = false) }
    }
}

@Composable
private fun AnniversaryDetailDialog(
    entry: CoupleAnniversaryEntity,
    partnerName: String,
    now: Long,
    onDismiss: () -> Unit,
    onFavorite: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val category = categoryFor(entry.category)
    val days = daysUntil(entry, now)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${category.emoji} ${entry.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(18.dp), color = category.container, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(category.label, color = category.accent, style = MaterialTheme.typography.labelLarge)
                        Text(formatAnniversaryDate(entry.eventDate), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                days == 0L -> "今天就是这一天 ♡"
                                days > 0L -> "距离下一次还有 $days 天"
                                else -> "这是一个已经发生、值得留下来的日子"
                            },
                            color = category.accent,
                        )
                        if (entry.yearly) Text("每年都会重新来到你和 $partnerName 身边。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                entry.note?.let {
                    Text("关于这一天", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(it)
                }
            }
        },
        confirmButton = { TextButton(onClick = onEdit) { Text("编辑") } },
        dismissButton = {
            Row {
                TextButton(onClick = onFavorite) { Text(if (entry.favorite) "取消珍藏" else "♡ 珍藏") }
                TextButton(onClick = onDelete) { Text("删除") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

private fun nextOccurrence(entry: CoupleAnniversaryEntity, now: Long): Long {
    if (!entry.yearly) return entry.eventDate
    val source = Calendar.getInstance().apply { timeInMillis = entry.eventDate }
    val candidate = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.MONTH, source.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, source.get(Calendar.DAY_OF_MONTH))
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val today = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (candidate.before(today)) candidate.add(Calendar.YEAR, 1)
    return candidate.timeInMillis
}

private fun daysUntil(entry: CoupleAnniversaryEntity, now: Long): Long {
    val target = nextOccurrence(entry, now)
    val today = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return TimeUnit.MILLISECONDS.toDays(target - today)
}

private fun formatAnniversaryDate(value: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(value))
