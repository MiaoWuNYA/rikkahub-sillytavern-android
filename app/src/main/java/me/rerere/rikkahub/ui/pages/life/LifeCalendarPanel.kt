package me.rerere.rikkahub.ui.pages.life

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
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
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class LifeCalendarEvent(
    val id: Long = System.currentTimeMillis(),
    val title: String,
    val detail: String = "",
    val eventAt: Long,
    val allDay: Boolean = false,
    val category: String = "life",
    val createdAt: Long = System.currentTimeMillis(),
)

private data class CalendarCategory(
    val id: String,
    val label: String,
    val emoji: String,
    val dot: Color,
    val soft: Color,
)

private val calendarCategories = listOf(
    CalendarCategory("life", "生活", "🌷", Color(0xFFE97994), Color(0xFFFFEAF0)),
    CalendarCategory("date", "约会", "💕", Color(0xFFD76A98), Color(0xFFFFE8F3)),
    CalendarCategory("todo", "安排", "☁", Color(0xFF6F98C8), Color(0xFFEAF3FF)),
    CalendarCategory("health", "身体", "🌿", Color(0xFF79A985), Color(0xFFEAF5EC)),
    CalendarCategory("memory", "纪念", "🎀", Color(0xFF9A7BC2), Color(0xFFF2EBFA)),
)

private fun calendarCategory(id: String): CalendarCategory =
    calendarCategories.firstOrNull { it.id == id } ?: calendarCategories.first()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifeCalendarPanel() {
    val context = LocalContext.current
    var events by remember { mutableStateOf(loadCalendarEvents(context)) }
    var month by remember { mutableStateOf(startOfMonth(System.currentTimeMillis())) }
    var selectedDay by remember { mutableStateOf(startOfDay(System.currentTimeMillis())) }
    var showEditor by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<Long?>(null) }

    val saveAll: (List<LifeCalendarEvent>) -> Unit = { updated ->
        events = updated
        saveCalendarEvents(context, updated)
    }

    val selectedEvents = events
        .filter { sameDay(it.eventAt, selectedDay) }
        .sortedWith(compareBy<LifeCalendarEvent> { !it.allDay }.thenBy { it.eventAt })

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp, 14.dp, 14.dp, 104.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            CalendarHeader(
                month = month,
                onPrevious = {
                    month = shiftMonth(month, -1)
                    selectedDay = month
                },
                onNext = {
                    month = shiftMonth(month, 1)
                    selectedDay = month
                },
                onToday = {
                    month = startOfMonth(System.currentTimeMillis())
                    selectedDay = startOfDay(System.currentTimeMillis())
                },
            )
        }
        item {
            CalendarMonthGrid(
                month = month,
                selectedDay = selectedDay,
                events = events,
                onSelect = { selectedDay = it },
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(formatSelectedDate(selectedDay), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        if (selectedEvents.isEmpty()) "今天留一点空白也很好。" else "${selectedEvents.size} 个小安排",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                FilledTonalButton(
                    onClick = { showEditor = true },
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFFFFE2EB),
                        contentColor = Color(0xFFA94F6B),
                    ),
                ) { Text("＋ 新安排") }
            }
        }
        if (selectedEvents.isEmpty()) {
            item { CalendarEmptyState(onAdd = { showEditor = true }) }
        } else {
            items(selectedEvents, key = { it.id }) { event ->
                CalendarEventCard(
                    event = event,
                    onEdit = { editingId = event.id },
                    onSystemCalendar = { openSystemCalendar(context, event) },
                    onDelete = { saveAll(events.filterNot { it.id == event.id }) },
                )
            }
        }
    }

    if (showEditor) {
        CalendarEventEditor(
            initial = null,
            initialDate = selectedDay,
            onDismiss = { showEditor = false },
            onSave = { title, detail, at, allDay, category ->
                saveAll(events + LifeCalendarEvent(title = title, detail = detail, eventAt = at, allDay = allDay, category = category))
                selectedDay = startOfDay(at)
                month = startOfMonth(at)
                showEditor = false
            },
        )
    }

    editingId?.let { id ->
        events.firstOrNull { it.id == id }?.let { event ->
            CalendarEventEditor(
                initial = event,
                initialDate = event.eventAt,
                onDismiss = { editingId = null },
                onSave = { title, detail, at, allDay, category ->
                    saveAll(events.map {
                        if (it.id == event.id) it.copy(title = title, detail = detail, eventAt = at, allDay = allDay, category = category) else it
                    })
                    selectedDay = startOfDay(at)
                    month = startOfMonth(at)
                    editingId = null
                },
            )
        }
    }
}

@Composable
private fun CalendarHeader(
    month: Long,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = Color(0xFFFFF7FA),
        border = BorderStroke(1.dp, Color(0xFFF3D7E0)),
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("୨୧  MY CALENDAR", color = Color(0xFFB55D79), style = MaterialTheme.typography.labelLarge)
                    Text(formatMonthTitle(month), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color(0xFF53464C))
                    Text("把日子排得清楚，也留一点可爱的空白。", color = Color(0xFF8A737D), style = MaterialTheme.typography.bodySmall)
                }
                Surface(shape = CircleShape, color = Color(0xFFFFE4EC)) {
                    Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) { Text("📅", style = MaterialTheme.typography.titleLarge) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onPrevious, shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(42.dp)) { Text("‹", style = MaterialTheme.typography.titleLarge) }
                    OutlinedButton(onClick = onNext, shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(42.dp)) { Text("›", style = MaterialTheme.typography.titleLarge) }
                }
                TextButton(onClick = onToday) { Text("今天", color = Color(0xFFB45E7A), fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun CalendarMonthGrid(
    month: Long,
    selectedDay: Long,
    events: List<LifeCalendarEvent>,
    onSelect: (Long) -> Unit,
) {
    val days = buildMonthCells(month)
    val weekLabels = listOf("日", "一", "二", "三", "四", "五", "六")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth()) {
                weekLabels.forEach { label ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            days.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { day ->
                        CalendarDayCell(
                            day = day,
                            inMonth = sameMonth(day, month),
                            selected = sameDay(day, selectedDay),
                            today = sameDay(day, System.currentTimeMillis()),
                            eventColors = events.filter { sameDay(it.eventAt, day) }.take(3).map { calendarCategory(it.category).dot },
                            onClick = { onSelect(day) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    day: Long,
    inMonth: Boolean,
    selected: Boolean,
    today: Boolean,
    eventColors: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val number = Calendar.getInstance().apply { timeInMillis = day }.get(Calendar.DAY_OF_MONTH)
    Box(modifier.height(54.dp), contentAlignment = Alignment.TopCenter) {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(38.dp),
            shape = CircleShape,
            color = when {
                selected -> Color(0xFFE96886)
                today -> Color(0xFFFFE2EA)
                else -> Color.Transparent
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    number.toString(),
                    color = when {
                        selected -> Color.White
                        !inMonth -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                        today -> Color(0xFFAD4C68)
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    fontWeight = if (selected || today) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        if (eventColors.isNotEmpty()) {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                eventColors.forEach { color -> Surface(modifier = Modifier.size(4.dp), shape = CircleShape, color = color) {} }
            }
        }
    }
}

@Composable
private fun CalendarEventCard(
    event: LifeCalendarEvent,
    onEdit: () -> Unit,
    onSystemCalendar: () -> Unit,
    onDelete: () -> Unit,
) {
    val category = calendarCategory(event.category)
    Card(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = category.soft.copy(alpha = 0.72f)),
        border = BorderStroke(1.dp, category.dot.copy(alpha = 0.18f)),
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Surface(modifier = Modifier.width(4.dp).height(62.dp), shape = RoundedCornerShape(99.dp), color = category.dot) {}
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (event.allDay) "全天" else formatEventTime(event.eventAt), color = category.dot, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(event.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (event.detail.isNotBlank()) Text(event.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                Text("${category.emoji} ${category.label}", color = category.dot, style = MaterialTheme.typography.labelSmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onSystemCalendar, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("同步") }
                TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) { Text("删除") }
            }
        }
    }
}

@Composable
private fun CalendarEmptyState(onAdd: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFFFFFBF8),
        border = BorderStroke(1.dp, Color(0xFFF1E5DE)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text("☁️🌷", style = MaterialTheme.typography.headlineMedium)
            Text("这一天还空空的", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("留白也很好，或者悄悄放一个小安排进来。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onAdd) { Text("＋ 放一个安排") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarEventEditor(
    initial: LifeCalendarEvent?,
    initialDate: Long,
    onDismiss: () -> Unit,
    onSave: (String, String, Long, Boolean, String) -> Unit,
) {
    var title by remember(initial?.id) { mutableStateOf(initial?.title.orEmpty()) }
    var detail by remember(initial?.id) { mutableStateOf(initial?.detail.orEmpty()) }
    var date by remember(initial?.id, initialDate) { mutableLongStateOf(startOfDay(initial?.eventAt ?: initialDate)) }
    var hour by remember(initial?.id) { mutableIntStateOf(Calendar.getInstance().apply { timeInMillis = initial?.eventAt ?: System.currentTimeMillis() }.get(Calendar.HOUR_OF_DAY)) }
    var minute by remember(initial?.id) { mutableIntStateOf(Calendar.getInstance().apply { timeInMillis = initial?.eventAt ?: System.currentTimeMillis() }.get(Calendar.MINUTE)) }
    var allDay by remember(initial?.id) { mutableStateOf(initial?.allDay ?: false) }
    var category by remember(initial?.id) { mutableStateOf(initial?.category ?: "life") }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(26.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (initial == null) "🌷 新建日程" else "🎀 编辑日程")
                Text("像苹果日历一样清爽，但再软一点。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        },
        text = {
            Column(Modifier.heightIn(max = 600.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), label = { Text("安排标题") }, singleLine = true)
                OutlinedTextField(detail, { detail = it }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), label = { Text("地点、备注或想说的话") }, minLines = 3)
                Text("颜色分类", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(calendarCategories) { item ->
                        FilterChip(
                            selected = category == item.id,
                            onClick = { category = item.id },
                            label = { Text("${item.emoji} ${item.label}") },
                            shape = RoundedCornerShape(18.dp),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = item.soft, selectedLabelColor = item.dot),
                        )
                    }
                }
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("📅 ${formatSelectedDate(date)}") }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("全天")
                        Text("不需要具体时间的小日子", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = allDay, onCheckedChange = { allDay = it })
                }
                if (!allDay) {
                    OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        Text("🕰 ${String.format(Locale.getDefault(), "%02d:%02d", hour, minute)}")
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                enabled = title.isNotBlank(),
                onClick = {
                    val at = Calendar.getInstance().apply {
                        timeInMillis = date
                        if (allDay) set(Calendar.HOUR_OF_DAY, 0) else set(Calendar.HOUR_OF_DAY, hour)
                        if (allDay) set(Calendar.MINUTE, 0) else set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    onSave(title.trim(), detail.trim(), at, allDay, category)
                },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFFFFDDE7), contentColor = Color(0xFFA64E69)),
            ) { Text(if (initial == null) "放进日历 ♡" else "保存修改") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = { TextButton(onClick = { date = state.selectedDateMillis ?: date; showDatePicker = false }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) { DatePicker(state = state, showModeToggle = false) }
    }

    if (showTimePicker) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("选择时间") },
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = state) } },
            confirmButton = { TextButton(onClick = { hour = state.hour; minute = state.minute; showTimePicker = false }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("取消") } },
        )
    }
}

private fun loadCalendarEvents(context: Context): List<LifeCalendarEvent> = runCatching {
    val prefs = context.getSharedPreferences("tumin_life_calendar", Context.MODE_PRIVATE)
    val raw = prefs.getString("events", null)
    if (raw != null) {
        val array = JSONArray(raw)
        return@runCatching buildList {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                add(
                    LifeCalendarEvent(
                        id = item.getLong("id"),
                        title = item.getString("title"),
                        detail = item.optString("detail"),
                        eventAt = item.optLong("eventAt", item.optLong("createdAt", item.getLong("id"))),
                        allDay = item.optBoolean("allDay", false),
                        category = item.optString("category", "life"),
                        createdAt = item.optLong("createdAt", item.getLong("id")),
                    )
                )
            }
        }
    }

    // First launch migration: read the old generic Life Hub calendar rows.
    val oldRaw = context.getSharedPreferences("tumin_life_hub", Context.MODE_PRIVATE).getString("entries", "[]") ?: "[]"
    val oldArray = JSONArray(oldRaw)
    val migrated = buildList {
        repeat(oldArray.length()) { index ->
            val item = oldArray.getJSONObject(index)
            if (item.optString("section") == "CALENDAR") {
                val created = item.optLong("createdAt", item.optLong("id", System.currentTimeMillis()))
                add(
                    LifeCalendarEvent(
                        id = item.optLong("id", created),
                        title = item.optString("title", "日程"),
                        detail = item.optString("detail"),
                        eventAt = created,
                        category = when (item.optString("tag")) {
                            "纪念日" -> "memory"
                            "今天", "本周" -> "todo"
                            else -> "life"
                        },
                        createdAt = created,
                    )
                )
            }
        }
    }
    saveCalendarEvents(context, migrated)
    migrated
}.getOrDefault(emptyList())

private fun saveCalendarEvents(context: Context, events: List<LifeCalendarEvent>) {
    val array = JSONArray()
    events.forEach { event ->
        array.put(JSONObject().apply {
            put("id", event.id)
            put("title", event.title)
            put("detail", event.detail)
            put("eventAt", event.eventAt)
            put("allDay", event.allDay)
            put("category", event.category)
            put("createdAt", event.createdAt)
        })
    }
    context.getSharedPreferences("tumin_life_calendar", Context.MODE_PRIVATE).edit().putString("events", array.toString()).apply()
}

private fun openSystemCalendar(context: Context, event: LifeCalendarEvent) {
    val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).apply {
        putExtra(CalendarContract.Events.TITLE, event.title)
        putExtra(CalendarContract.Events.DESCRIPTION, event.detail)
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.eventAt)
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.eventAt + if (event.allDay) 24 * 60 * 60 * 1000L else 60 * 60 * 1000L)
        putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, event.allDay)
    }
    context.startActivity(intent)
}

private fun startOfDay(value: Long): Long = Calendar.getInstance().apply {
    timeInMillis = value
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun startOfMonth(value: Long): Long = Calendar.getInstance().apply {
    timeInMillis = startOfDay(value)
    set(Calendar.DAY_OF_MONTH, 1)
}.timeInMillis

private fun shiftMonth(value: Long, delta: Int): Long = Calendar.getInstance().apply {
    timeInMillis = value
    add(Calendar.MONTH, delta)
    set(Calendar.DAY_OF_MONTH, 1)
}.timeInMillis

private fun buildMonthCells(month: Long): List<Long> {
    val cal = Calendar.getInstance().apply {
        timeInMillis = startOfMonth(month)
        add(Calendar.DAY_OF_MONTH, -(get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY))
    }
    return List(42) {
        val value = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        value
    }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

private fun sameMonth(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.MONTH) == cb.get(Calendar.MONTH)
}

private fun formatMonthTitle(value: Long): String = SimpleDateFormat("yyyy年 M月", Locale.getDefault()).format(Date(value))
private fun formatSelectedDate(value: Long): String = SimpleDateFormat("M月d日 EEEE", Locale.getDefault()).format(Date(value))
private fun formatEventTime(value: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(value))
