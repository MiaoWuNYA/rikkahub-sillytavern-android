package me.rerere.rikkahub.ui.pages.life

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private const val HEALTH_PREFS = "tumin_health_cycle"
private const val PERIODS_KEY = "periods"
private const val DAILY_KEY = "daily_logs"
private const val CYCLE_LENGTH_KEY = "cycle_length"
private const val PERIOD_LENGTH_KEY = "period_length"
private const val REMINDER_ENABLED_KEY = "reminder_enabled"
private const val REMINDER_DAYS_KEY = "reminder_days_before"
private const val AI_ALLOWED_KEY = "ai_allowed"
private const val LAST_NOTIFICATION_KEY = "last_period_notification"
private const val PERIOD_WORK_NAME = "tumin_period_reminder"
private const val PERIOD_CHANNEL_ID = "tumin_period_cycle"
private const val DEFAULT_CYCLE_DAYS = 30
private const val DEFAULT_PERIOD_DAYS = 7

private data class PeriodRecord(val start: LocalDate, val end: LocalDate? = null)
private data class DailyBodyLog(
    val date: LocalDate,
    val flow: String = "",
    val symptoms: Set<String> = emptySet(),
    val mood: String = "",
    val energy: String = "",
    val note: String = "",
)

private val symptomOptions = listOf("腹痛", "腰酸", "头痛", "胸胀", "疲惫", "失眠", "食欲变化", "皮肤状态")
private val moodOptions = listOf("开心", "平静", "敏感", "烦躁", "低落", "焦虑")
private val energyOptions = listOf("高", "中", "低")
private val flowOptions = listOf("少量", "中等", "较多")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthCyclePanel() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE) }
    var periods by remember { mutableStateOf(loadPeriods(context)) }
    var logs by remember { mutableStateOf(loadDailyLogs(context)) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var showLogEditor by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var cycleLength by remember { mutableIntStateOf(prefs.getInt(CYCLE_LENGTH_KEY, DEFAULT_CYCLE_DAYS)) }
    var periodLength by remember { mutableIntStateOf(prefs.getInt(PERIOD_LENGTH_KEY, DEFAULT_PERIOD_DAYS)) }
    var reminderEnabled by remember { mutableStateOf(prefs.getBoolean(REMINDER_ENABLED_KEY, false)) }
    var reminderDays by remember { mutableIntStateOf(prefs.getInt(REMINDER_DAYS_KEY, 3)) }
    var aiAllowed by remember { mutableStateOf(prefs.getBoolean(AI_ALLOWED_KEY, true)) }
    var pendingReminderEnable by remember { mutableStateOf(false) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        reminderEnabled = granted && pendingReminderEnable
        pendingReminderEnable = false
        saveSettings(context, cycleLength, periodLength, reminderEnabled, reminderDays, aiAllowed)
        if (reminderEnabled) schedulePeriodReminder(context) else cancelPeriodReminder(context)
    }

    val learnedCycle = averageCycleLength(periods)
    val learnedPeriod = averagePeriodLength(periods)
    val effectiveCycle = learnedCycle ?: cycleLength
    val effectivePeriod = learnedPeriod ?: periodLength
    val latestPeriod = periods.maxByOrNull { it.start }
    val predictedStart = latestPeriod?.start?.plusDays(effectiveCycle.toLong())
    val today = LocalDate.now()
    val openPeriod = periods.filter { it.end == null }.maxByOrNull { it.start }
    val currentPeriod = periods.firstOrNull { record ->
        val displayEnd = record.end ?: record.start.plusDays((effectivePeriod - 1).coerceAtLeast(0).toLong())
        !today.isBefore(record.start) && !today.isAfter(displayEnd)
    }
    val dayInPeriod = currentPeriod?.let { ChronoUnit.DAYS.between(it.start, today).toInt() + 1 }
    val daysUntil = predictedStart?.let { ChronoUnit.DAYS.between(today, it).toInt() }

    LaunchedEffect(Unit) {
        // Migrate the short-lived first implementation's untouched defaults to the product defaults.
        // Custom user values are preserved.
        if (prefs.getInt(CYCLE_LENGTH_KEY, DEFAULT_CYCLE_DAYS) == 28 &&
            prefs.getInt(PERIOD_LENGTH_KEY, DEFAULT_PERIOD_DAYS) == 5 &&
            periods.size < 2
        ) {
            cycleLength = DEFAULT_CYCLE_DAYS
            periodLength = DEFAULT_PERIOD_DAYS
        }
    }

    LaunchedEffect(reminderEnabled, reminderDays, cycleLength, periodLength, aiAllowed) {
        saveSettings(context, cycleLength, periodLength, reminderEnabled, reminderDays, aiAllowed)
        if (reminderEnabled) schedulePeriodReminder(context) else cancelPeriodReminder(context)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp, 16.dp, 14.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(30.dp),
                color = Color(0xFFFFF1F5),
                border = BorderStroke(1.dp, Color(0xFFF0CBD7)),
            ) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("🌷 PERIOD & BODY", color = Color(0xFFB85F78), style = MaterialTheme.typography.labelLarge)
                            Text("生理周期", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color(0xFF57454B))
                            Text(
                                when {
                                    dayInPeriod != null && currentPeriod?.end != null -> "经期第 $dayInPeriod 天，已按实际开始和结束记录。"
                                    dayInPeriod != null -> "经期第 $dayInPeriod 天，结束日暂按 $effectivePeriod 天经期预测。"
                                    openPeriod != null -> "这次经期还没有记录结束日，预测区间已结束，可以按实际日期补记。"
                                    daysUntil != null && daysUntil >= 0 -> "预计还有 $daysUntil 天到下次经期。"
                                    predictedStart != null -> "预计日期已过，可以按实际情况记录新的开始日。"
                                    else -> "记录第一次经期后，先按 30 天周期、7 天经期帮你预测。"
                                },
                                color = Color(0xFF806B72),
                            )
                        }
                        FilledTonalButton(onClick = { showSettings = true }, shape = RoundedCornerShape(16.dp)) { Text("设置") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HealthStat(if (learnedCycle != null) "平均周期" else "预测周期", "${effectiveCycle}天", Modifier.weight(1f))
                        HealthStat(if (learnedPeriod != null) "平均经期" else "预测经期", "${effectivePeriod}天", Modifier.weight(1f))
                        HealthStat("记录", "${periods.size}次", Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (openPeriod == null) {
                                    periods = (periods + PeriodRecord(today)).sortedBy { it.start }
                                    savePeriods(context, periods)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = openPeriod == null,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC96882)),
                            shape = RoundedCornerShape(18.dp),
                        ) { Text(if (openPeriod == null) "今天来了" else "本轮已开始") }
                        OutlinedButton(
                            onClick = {
                                openPeriod?.let { active ->
                                    periods = periods.map { if (it.start == active.start) it.copy(end = today) else it }
                                    savePeriods(context, periods)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = openPeriod != null,
                            shape = RoundedCornerShape(18.dp),
                        ) { Text("今天结束") }
                    }
                    if (periods.isNotEmpty()) {
                        Text(
                            when {
                                learnedCycle == null && learnedPeriod == null -> "数据还少：先用 30 天周期 / 7 天经期预测；记录多起来后会自动学习你的节奏。"
                                learnedCycle != null && learnedPeriod != null -> "已根据最近记录自动调整周期与经期长度。"
                                else -> "已经开始学习你的记录；数据再多一些，预测会继续按你的实际周期调整。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${month.year}年 ${month.monthValue}月", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹") }
                        TextButton(onClick = { month = YearMonth.now(); selectedDate = today }) { Text("今天") }
                        TextButton(onClick = { month = month.plusMonths(1) }) { Text("›") }
                    }
                    HealthMonthCalendar(month, selectedDate, periods, logs, predictedStart, effectivePeriod) { selectedDate = it }
                }
            }
        }

        item {
            val log = logs.firstOrNull { it.date == selectedDate }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFFFFFAF7),
                border = BorderStroke(1.dp, Color(0xFFEADDD7)),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(selectedDate.format(DateTimeFormatter.ofPattern("M月d日 EEE")), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(if (log == null) "这一天还没有身体记录" else "已经记下今天的身体状态", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FilledTonalButton(onClick = { showLogEditor = true }, shape = RoundedCornerShape(16.dp)) { Text(if (log == null) "记录" else "编辑") }
                    }
                    log?.let {
                        if (it.flow.isNotBlank()) Text("🩸 流量：${it.flow}")
                        if (it.symptoms.isNotEmpty()) Text("🌿 身体：${it.symptoms.joinToString("、")}")
                        if (it.mood.isNotBlank()) Text("💭 心情：${it.mood}")
                        if (it.energy.isNotBlank()) Text("☁️ 精力：${it.energy}")
                        if (it.note.isNotBlank()) Text(it.note, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Color(0xFFF4F0F8)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("♡ TA 能知道什么", fontWeight = FontWeight.Bold, color = Color(0xFF735F82))
                    Text(
                        if (aiAllowed) "当前允许 AI 读取最近的周期阶段与身体记录，用于更自然地关心你。" else "当前已关闭，聊天 AI 不会收到周期与身体记录。",
                        color = Color(0xFF766E7B),
                    )
                    Text("只提供当前状态和最近记录，不会每次发送完整周期历史。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Text("周期预测只根据你记录的历史日期做简单估算，不用于诊断、避孕或替代医疗建议。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

    if (showLogEditor) {
        BodyLogDialog(selectedDate, logs.firstOrNull { it.date == selectedDate }, onDismiss = { showLogEditor = false }) { updated ->
            logs = (logs.filterNot { it.date == updated.date } + updated).sortedBy { it.date }
            saveDailyLogs(context, logs)
            showLogEditor = false
        }
    }

    if (showSettings) {
        HealthSettingsDialog(cycleLength, periodLength, reminderEnabled, reminderDays, aiAllowed, onDismiss = { showSettings = false }) { c, p, enabled, days, ai ->
            cycleLength = c
            periodLength = p
            reminderDays = days
            aiAllowed = ai
            if (enabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingReminderEnable = true
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                reminderEnabled = enabled
                saveSettings(context, c, p, enabled, days, ai)
                if (enabled) schedulePeriodReminder(context) else cancelPeriodReminder(context)
            }
            showSettings = false
        }
    }
}

@Composable
private fun HealthStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = Color(0xFFFFDFE8)) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Bold, color = Color(0xFFA85670))
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF936B78))
        }
    }
}

@Composable
private fun HealthMonthCalendar(
    month: YearMonth,
    selected: LocalDate,
    periods: List<PeriodRecord>,
    logs: List<DailyBodyLog>,
    predictedStart: LocalDate?,
    predictedLength: Int,
    onSelect: (LocalDate) -> Unit,
) {
    val first = month.atDay(1)
    val start = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val dates = (0 until 42).map { start.plusDays(it.toLong()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall) }
        }
        dates.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    val actual = periods.any { record ->
                        if (record.end != null) {
                            !date.isBefore(record.start) && !date.isAfter(record.end)
                        } else {
                            date == record.start
                        }
                    }
                    val predictedCurrent = periods.any { record ->
                        record.end == null && date.isAfter(record.start) &&
                            !date.isAfter(record.start.plusDays((predictedLength - 1).coerceAtLeast(0).toLong()))
                    }
                    val predictedNext = predictedStart?.let { startDate ->
                        !date.isBefore(startDate) && date.isBefore(startDate.plusDays(predictedLength.toLong()))
                    } == true
                    val predicted = predictedCurrent || predictedNext
                    val hasLog = logs.any { it.date == date }
                    val isToday = date == LocalDate.now()
                    val isSelected = date == selected
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp).clickable { onSelect(date) }, contentAlignment = Alignment.Center) {
                        Surface(
                            modifier = Modifier.size(36.dp),
                            shape = CircleShape,
                            color = when { isSelected -> Color(0xFFC9637D); actual -> Color(0xFFFFD5DF); predicted -> Color(0xFFFFEDF2); else -> Color.Transparent },
                            border = if (isToday && !isSelected) BorderStroke(1.5.dp, Color(0xFFC9637D)) else null,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(date.dayOfMonth.toString(), color = when { isSelected -> Color.White; date.month != month.month -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f); else -> MaterialTheme.colorScheme.onSurface }, style = MaterialTheme.typography.bodySmall)
                                if (hasLog) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(4.dp)) { Surface(Modifier.fillMaxSize(), shape = CircleShape, color = if (isSelected) Color.White else Color(0xFF8B75A0)) {} }
                            }
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("● 已记录经期", color = Color(0xFFC9637D), style = MaterialTheme.typography.labelSmall)
            Text("○ 预测经期", color = Color(0xFFD99AAF), style = MaterialTheme.typography.labelSmall)
            Text("• 身体记录", color = Color(0xFF8B75A0), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun BodyLogDialog(date: LocalDate, initial: DailyBodyLog?, onDismiss: () -> Unit, onSave: (DailyBodyLog) -> Unit) {
    var flow by remember(date) { mutableStateOf(initial?.flow.orEmpty()) }
    var selectedSymptoms by remember(date) { mutableStateOf(initial?.symptoms ?: emptySet()) }
    var mood by remember(date) { mutableStateOf(initial?.mood.orEmpty()) }
    var energy by remember(date) { mutableStateOf(initial?.energy.orEmpty()) }
    var note by remember(date) { mutableStateOf(initial?.note.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text("🌷 ${date.monthValue}月${date.dayOfMonth}日") },
        text = {
            LazyColumn(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("经量", fontWeight = FontWeight.Bold) }
                item { ChoiceRow(flowOptions, flow) { flow = if (flow == it) "" else it } }
                item { Text("身体感受", fontWeight = FontWeight.Bold) }
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) { items(symptomOptions) { symptom -> FilterChip(selected = symptom in selectedSymptoms, onClick = { selectedSymptoms = if (symptom in selectedSymptoms) selectedSymptoms - symptom else selectedSymptoms + symptom }, label = { Text(symptom) }) } } }
                item { Text("心情", fontWeight = FontWeight.Bold) }
                item { ChoiceRow(moodOptions, mood) { mood = if (mood == it) "" else it } }
                item { Text("精力", fontWeight = FontWeight.Bold) }
                item { ChoiceRow(energyOptions, energy) { energy = if (energy == it) "" else it } }
                item { OutlinedTextField(note, { note = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("今天还想记一点什么") }, shape = RoundedCornerShape(18.dp)) }
            }
        },
        confirmButton = { FilledTonalButton(onClick = { onSave(DailyBodyLog(date, flow, selectedSymptoms, mood, energy, note.trim())) }) { Text("保存今天") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ChoiceRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) { items(options) { item -> FilterChip(selected = selected == item, onClick = { onSelect(item) }, label = { Text(item) }) } }
}

@Composable
private fun HealthSettingsDialog(
    cycleLength: Int,
    periodLength: Int,
    reminderEnabled: Boolean,
    reminderDays: Int,
    aiAllowed: Boolean,
    onDismiss: () -> Unit,
    onSave: (Int, Int, Boolean, Int, Boolean) -> Unit,
) {
    var cycle by remember { mutableIntStateOf(cycleLength) }
    var period by remember { mutableIntStateOf(periodLength) }
    var reminders by remember { mutableStateOf(reminderEnabled) }
    var days by remember { mutableIntStateOf(reminderDays) }
    var ai by remember { mutableStateOf(aiAllowed) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text("周期设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("历史记录不足时，先按约 30 天周期、7 天经期预测；记录多起来后会自动按你的实际节奏调整。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("初始预测周期：$cycle 天"); Slider(cycle.toFloat(), { cycle = it.toInt() }, valueRange = 20f..45f, steps = 24)
                Text("初始预测经期：$period 天"); Slider(period.toFloat(), { period = it.toInt() }, valueRange = 2f..10f, steps = 7)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("通知栏提醒", fontWeight = FontWeight.Bold); Text("退出橘瓣后也可以收到", style = MaterialTheme.typography.bodySmall) }; Switch(reminders, { reminders = it }) }
                if (reminders) { Text("提前 $days 天提醒"); Slider(days.toFloat(), { days = it.toInt() }, valueRange = 1f..7f, steps = 5) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("允许 AI 读取", fontWeight = FontWeight.Bold); Text("只提供当前状态与最近记录", style = MaterialTheme.typography.bodySmall) }; Switch(ai, { ai = it }) }
            }
        },
        confirmButton = { FilledTonalButton(onClick = { onSave(cycle, period, reminders, days, ai) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun averageCycleLength(periods: List<PeriodRecord>): Int? {
    val starts = periods.map { it.start }.sorted()
    if (starts.size < 3) return null
    val intervals = starts.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }
        .filter { it in 15..60 }
        .takeLast(6)
    if (intervals.size < 2) return null
    return intervals.average().roundToInt().coerceIn(20, 45)
}

private fun averagePeriodLength(periods: List<PeriodRecord>): Int? {
    val lengths = periods.mapNotNull { record ->
        record.end?.let { ChronoUnit.DAYS.between(record.start, it).toInt() + 1 }
    }.filter { it in 1..12 }.takeLast(6)
    if (lengths.size < 2) return null
    return lengths.average().roundToInt().coerceIn(2, 10)
}

private fun loadPeriods(context: Context): List<PeriodRecord> = runCatching {
    val array = JSONArray(context.getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE).getString(PERIODS_KEY, "[]") ?: "[]")
    buildList { for (i in 0 until array.length()) { val obj = array.getJSONObject(i); add(PeriodRecord(LocalDate.parse(obj.getString("start")), obj.optString("end").takeIf { it.isNotBlank() }?.let(LocalDate::parse))) } }
}.getOrDefault(emptyList())

private fun savePeriods(context: Context, periods: List<PeriodRecord>) {
    val array = JSONArray(); periods.forEach { record -> array.put(JSONObject().apply { put("start", record.start.toString()); record.end?.let { put("end", it.toString()) } }) }
    context.getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE).edit().putString(PERIODS_KEY, array.toString()).apply()
}

private fun loadDailyLogs(context: Context): List<DailyBodyLog> = runCatching {
    val array = JSONArray(context.getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE).getString(DAILY_KEY, "[]") ?: "[]")
    buildList {
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i); val symptoms = obj.optJSONArray("symptoms") ?: JSONArray(); val set = buildSet { for (j in 0 until symptoms.length()) add(symptoms.getString(j)) }
            add(DailyBodyLog(LocalDate.parse(obj.getString("date")), obj.optString("flow"), set, obj.optString("mood"), obj.optString("energy"), obj.optString("note")))
        }
    }
}.getOrDefault(emptyList())

private fun saveDailyLogs(context: Context, logs: List<DailyBodyLog>) {
    val array = JSONArray(); logs.forEach { log -> array.put(JSONObject().apply { put("date", log.date.toString()); put("flow", log.flow); put("symptoms", JSONArray(log.symptoms.toList())); put("mood", log.mood); put("energy", log.energy); put("note", log.note) }) }
    context.getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE).edit().putString(DAILY_KEY, array.toString()).apply()
}

private fun saveSettings(context: Context, cycle: Int, period: Int, reminder: Boolean, reminderDays: Int, ai: Boolean) {
    context.getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE).edit().putInt(CYCLE_LENGTH_KEY, cycle).putInt(PERIOD_LENGTH_KEY, period).putBoolean(REMINDER_ENABLED_KEY, reminder).putInt(REMINDER_DAYS_KEY, reminderDays).putBoolean(AI_ALLOWED_KEY, ai).apply()
}

private fun schedulePeriodReminder(context: Context) {
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIOD_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<PeriodReminderWorker>(24, TimeUnit.HOURS).build())
}
private fun cancelPeriodReminder(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(PERIOD_WORK_NAME) }

class PeriodReminderWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(REMINDER_ENABLED_KEY, false)) return Result.success()
        val periods = loadPeriods(applicationContext)
        val last = periods.maxByOrNull { it.start } ?: return Result.success()
        val cycle = averageCycleLength(periods) ?: prefs.getInt(CYCLE_LENGTH_KEY, DEFAULT_CYCLE_DAYS)
        val before = prefs.getInt(REMINDER_DAYS_KEY, 3)
        val predicted = last.start.plusDays(cycle.toLong())
        val today = LocalDate.now()
        val days = ChronoUnit.DAYS.between(today, predicted).toInt()
        if (days != before && days != 0) return Result.success()
        val marker = "$today:$days"
        if (prefs.getString(LAST_NOTIFICATION_KEY, "") == marker) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.createNotificationChannel(NotificationChannel(PERIOD_CHANNEL_ID, "周期提醒", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "经期预测与周期提醒" })
        val text = if (days == 0) "按记录估算，今天可能接近经期开始日。记得按实际情况记录哦。" else "按记录估算，大约还有 $days 天可能进入经期。要不要提前准备一下？"
        val notification = NotificationCompat.Builder(applicationContext, PERIOD_CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("🌷 周期小提醒").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).build()
        NotificationManagerCompat.from(applicationContext).notify(46321, notification)
        prefs.edit().putString(LAST_NOTIFICATION_KEY, marker).apply()
        return Result.success()
    }
}
