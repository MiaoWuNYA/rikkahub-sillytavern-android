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
import androidx.compose.ui.res.stringResource
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
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import me.rerere.rikkahub.R

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

// 这些选项会原样持久化到 SharedPreferences（flow/mood/energy/symptoms），
// 必须保持字面量稳定，不随语言切换。
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
    val todayText = stringResource(R.string.life_common_today)
    val settingsText = stringResource(R.string.settings)
    val recordText = stringResource(R.string.life_common_record)
    val editText = stringResource(R.string.edit)
    val cancelText = stringResource(R.string.cancel)
    val saveText = stringResource(R.string.common_save)

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
                            Text(stringResource(R.string.life_health_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color(0xFF57454B))
                            Text(
                                when {
                                    dayInPeriod != null && currentPeriod?.end != null -> stringResource(R.string.life_health_status_period_recorded, dayInPeriod)
                                    dayInPeriod != null -> stringResource(R.string.life_health_status_period_estimated, dayInPeriod, effectivePeriod)
                                    openPeriod != null -> stringResource(R.string.life_health_status_period_open)
                                    daysUntil != null && daysUntil >= 0 -> stringResource(R.string.life_health_status_days_until, daysUntil)
                                    predictedStart != null -> stringResource(R.string.life_health_status_passed)
                                    else -> stringResource(R.string.life_health_status_empty)
                                },
                                color = Color(0xFF806B72),
                            )
                        }
                        FilledTonalButton(onClick = { showSettings = true }, shape = RoundedCornerShape(16.dp)) { Text(settingsText) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HealthStat(stringResource(if (learnedCycle != null) R.string.life_health_stat_avg_cycle else R.string.life_health_stat_pred_cycle), stringResource(R.string.life_health_days_value, effectiveCycle), Modifier.weight(1f))
                        HealthStat(stringResource(if (learnedPeriod != null) R.string.life_health_stat_avg_period else R.string.life_health_stat_pred_period), stringResource(R.string.life_health_days_value, effectivePeriod), Modifier.weight(1f))
                        HealthStat(stringResource(R.string.life_health_stat_record_count), stringResource(R.string.life_health_times_value, periods.size), Modifier.weight(1f))
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
                        ) { Text(if (openPeriod == null) stringResource(R.string.life_health_btn_period_start) else stringResource(R.string.life_health_btn_period_started)) }
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
                        ) { Text(stringResource(R.string.life_health_btn_period_end)) }
                    }
                    if (periods.isNotEmpty()) {
                        Text(
                            when {
                                learnedCycle == null && learnedPeriod == null -> stringResource(R.string.life_health_learning_cold)
                                learnedCycle != null && learnedPeriod != null -> stringResource(R.string.life_health_learning_done)
                                else -> stringResource(R.string.life_health_learning_partial)
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
                        Text(stringResource(R.string.life_health_month_title, month.year, month.monthValue), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹") }
                        TextButton(onClick = { month = YearMonth.now(); selectedDate = today }) { Text(todayText) }
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
                            Text(
                                stringResource(
                                    R.string.life_health_date_title,
                                    selectedDate.monthValue,
                                    selectedDate.dayOfMonth,
                                    selectedDate.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()),
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(stringResource(if (log == null) R.string.life_health_no_log else R.string.life_health_has_log), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FilledTonalButton(onClick = { showLogEditor = true }, shape = RoundedCornerShape(16.dp)) { Text(if (log == null) recordText else editText) }
                    }
                    log?.let {
                        if (it.flow.isNotBlank()) Text(stringResource(R.string.life_health_log_flow, it.flow))
                        if (it.symptoms.isNotEmpty()) Text(stringResource(R.string.life_health_log_symptoms, it.symptoms.joinToString("、")))
                        if (it.mood.isNotBlank()) Text(stringResource(R.string.life_health_log_mood, it.mood))
                        if (it.energy.isNotBlank()) Text(stringResource(R.string.life_health_log_energy, it.energy))
                        if (it.note.isNotBlank()) Text(it.note, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Color(0xFFF4F0F8)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.life_health_ai_title), fontWeight = FontWeight.Bold, color = Color(0xFF735F82))
                    Text(
                        stringResource(if (aiAllowed) R.string.life_health_ai_on else R.string.life_health_ai_off),
                        color = Color(0xFF766E7B),
                    )
                    Text(stringResource(R.string.life_health_ai_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Text(stringResource(R.string.life_health_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
            val weekdays = listOf(
                stringResource(R.string.life_weekday_mon),
                stringResource(R.string.life_weekday_tue),
                stringResource(R.string.life_weekday_wed),
                stringResource(R.string.life_weekday_thu),
                stringResource(R.string.life_weekday_fri),
                stringResource(R.string.life_weekday_sat),
                stringResource(R.string.life_weekday_sun),
            )
            weekdays.forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall) }
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
            Text(stringResource(R.string.life_health_legend_recorded), color = Color(0xFFC9637D), style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.life_health_legend_predicted), color = Color(0xFFD99AAF), style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.life_health_legend_body_log), color = Color(0xFF8B75A0), style = MaterialTheme.typography.labelSmall)
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
    val cancelText = stringResource(R.string.cancel)
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text(stringResource(R.string.life_health_log_dialog_title, date.monthValue, date.dayOfMonth)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(stringResource(R.string.life_health_flow), fontWeight = FontWeight.Bold) }
                item { ChoiceRow(flowOptions, flow) { flow = if (flow == it) "" else it } }
                item { Text(stringResource(R.string.life_health_body_feeling), fontWeight = FontWeight.Bold) }
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) { items(symptomOptions) { symptom -> FilterChip(selected = symptom in selectedSymptoms, onClick = { selectedSymptoms = if (symptom in selectedSymptoms) selectedSymptoms - symptom else selectedSymptoms + symptom }, label = { Text(symptom) }) } } }
                item { Text(stringResource(R.string.life_health_mood), fontWeight = FontWeight.Bold) }
                item { ChoiceRow(moodOptions, mood) { mood = if (mood == it) "" else it } }
                item { Text(stringResource(R.string.life_health_energy), fontWeight = FontWeight.Bold) }
                item { ChoiceRow(energyOptions, energy) { energy = if (energy == it) "" else it } }
                item { OutlinedTextField(note, { note = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text(stringResource(R.string.life_health_note_label)) }, shape = RoundedCornerShape(18.dp)) }
            }
        },
        confirmButton = { FilledTonalButton(onClick = { onSave(DailyBodyLog(date, flow, selectedSymptoms, mood, energy, note.trim())) }) { Text(stringResource(R.string.life_health_save_today)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(cancelText) } },
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
    val saveText = stringResource(R.string.common_save)
    val cancelText = stringResource(R.string.cancel)
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text(stringResource(R.string.life_health_settings_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.life_health_settings_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.life_health_settings_cycle, cycle)); Slider(cycle.toFloat(), { cycle = it.toInt() }, valueRange = 20f..45f, steps = 24)
                Text(stringResource(R.string.life_health_settings_period, period)); Slider(period.toFloat(), { period = it.toInt() }, valueRange = 2f..10f, steps = 7)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(stringResource(R.string.life_health_settings_reminder), fontWeight = FontWeight.Bold); Text(stringResource(R.string.life_health_settings_reminder_desc), style = MaterialTheme.typography.bodySmall) }; Switch(reminders, { reminders = it }) }
                if (reminders) { Text(stringResource(R.string.life_health_settings_days_before, days)); Slider(days.toFloat(), { days = it.toInt() }, valueRange = 1f..7f, steps = 5) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(stringResource(R.string.life_health_settings_ai), fontWeight = FontWeight.Bold); Text(stringResource(R.string.life_health_settings_ai_desc), style = MaterialTheme.typography.bodySmall) }; Switch(ai, { ai = it }) }
            }
        },
        confirmButton = { FilledTonalButton(onClick = { onSave(cycle, period, reminders, days, ai) }) { Text(saveText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(cancelText) } },
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.createNotificationChannel(NotificationChannel(PERIOD_CHANNEL_ID, applicationContext.getString(R.string.life_health_notif_channel), NotificationManager.IMPORTANCE_DEFAULT).apply { description = applicationContext.getString(R.string.life_health_notif_channel_desc) })
        val text = if (days == 0) applicationContext.getString(R.string.life_health_notif_today) else applicationContext.getString(R.string.life_health_notif_soon, days)
        val notification = NotificationCompat.Builder(applicationContext, PERIOD_CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(applicationContext.getString(R.string.life_health_notif_title)).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).build()
        NotificationManagerCompat.from(applicationContext).notify(46321, notification)
        prefs.edit().putString(LAST_NOTIFICATION_KEY, marker).apply()
        return Result.success()
    }
}
