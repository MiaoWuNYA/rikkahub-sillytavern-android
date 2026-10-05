package me.rerere.rikkahub.data.service

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主动消息的执行日志。
 *
 * 这个功能全程在后台跑，出问题时用户在界面上看不到任何东西——
 * 「定时到了却没反应」过去只能靠 adb logcat 追。这里把每次触发的
 * 结果与原因落盘，设置页可以直接查看和复制。
 *
 * 存成环形缓冲：只留最近 [MAX_ENTRIES] 条，避免无限增长。
 * 用 SharedPreferences 而不是数据库，是因为它要被前台服务/Worker/
 * 设置页三处并发访问，SharedPreferences 的进程内一致性足够且没有
 * 迁移负担。
 */
object ProactiveMessageLog {

    private const val PREFS_NAME = "proactive_message_log"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_ENTRY_COUNT = "entry_count"
    private const val SEPARATOR = "\u001F"   // ASCII 单元分隔符，正常日志里不会出现

    /** 保留条数。够看最近几天的规律，也不会把 prefs 撑大。 */
    const val MAX_ENTRIES = 60

    /** 一次执行的结果。 */
    enum class Outcome(val label: String) {
        /** 真的生成并发出了消息 */
        SENT("已发送"),

        /** AI 自己选择沉默（[PASS]），算正常结束 */
        PASSED("AI 选择不发"),

        /** 跑到了，但被主动跳过（未启用、已达最小间隔、有并发生成等） */
        SKIPPED("已跳过"),

        /** 出错了 */
        FAILED("失败"),
    }

    data class Entry(
        val timestamp: Long,
        val outcome: Outcome,
        /** 触发来源：闹钟 / WorkManager / 手动 */
        val source: String,
        /** 人类可读的原因 */
        val detail: String,
    ) {
        fun format(): String {
            val sdf = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
            return "${sdf.format(Date(timestamp))}  [${outcome.label}] $source\n    $detail"
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 记一条。写入失败不能影响主流程，所以整体吞异常。 */
    fun log(
        context: Context,
        outcome: Outcome,
        source: String,
        detail: String,
    ) {
        runCatching {
            val p = prefs(context)
            val raw = p.getString(KEY_ENTRIES, "") ?: ""
            val old = if (raw.isBlank()) emptyList() else raw.split(SEPARATOR)

            val entry = listOf(
                System.currentTimeMillis().toString(),
                outcome.name,
                source.replace(SEPARATOR, " "),
                detail.replace(SEPARATOR, " ").replace("\n", " "),
            ).joinToString("|")

            val updated = (old + entry).takeLast(MAX_ENTRIES)
            p.edit()
                .putString(KEY_ENTRIES, updated.joinToString(SEPARATOR))
                .putInt(KEY_ENTRY_COUNT, (p.getInt(KEY_ENTRY_COUNT, 0) + 1))
                .apply()
        }
    }

    /** 读全部（新的在前）。 */
    fun read(context: Context): List<Entry> {
        return runCatching {
            val raw = prefs(context).getString(KEY_ENTRIES, "") ?: ""
            if (raw.isBlank()) return emptyList()
            raw.split(SEPARATOR).mapNotNull { line ->
                val f = line.split("|")
                if (f.size < 4) return@mapNotNull null
                val outcome = runCatching { Outcome.valueOf(f[1]) }.getOrNull()
                    ?: return@mapNotNull null
                Entry(
                    timestamp = f[0].toLongOrNull() ?: 0L,
                    outcome = outcome,
                    source = f[2],
                    detail = f[3],
                )
            }.reversed()
        }.getOrDefault(emptyList())
    }

    /** 累计触发次数（含被清掉的旧记录）。 */
    fun totalCount(context: Context): Int =
        runCatching { prefs(context).getInt(KEY_ENTRY_COUNT, 0) }.getOrDefault(0)

    /**
     * 导出为可复制的纯文本。
     *
     * 带上下次触发时间和上次实际触发时间——排查「为什么没发」时
     * 这两个值和日志本身一样有用。
     */
    fun exportAsText(context: Context): String {
        val sb = StringBuilder()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        sb.appendLine("=== 主动消息诊断 ===")
        val next = ProactiveMessageService.getNextTriggerTime(context)
        sb.appendLine("下次触发: " + (next?.let { sdf.format(Date(it)) } ?: "未排程"))
        val last = runCatching {
            context.getSharedPreferences(
                ProactiveMessageService.PREFS_NAME, Context.MODE_PRIVATE
            ).getLong(ProactiveMessageService.KEY_LAST_TRIGGERED_TIME, 0L)
        }.getOrDefault(0L)
        sb.appendLine("上次实际触发: " + (if (last > 0) sdf.format(Date(last)) else "从未"))
        sb.appendLine("累计执行: ${totalCount(context)} 次")
        sb.appendLine("保留最近 ${MAX_ENTRIES} 条")
        sb.appendLine()

        val entries = read(context)
        if (entries.isEmpty()) {
            sb.appendLine("（还没有任何记录。开启开关并等到触发一次后就会出现在这里。）")
        } else {
            entries.forEach { sb.appendLine(it.format()); sb.appendLine() }
        }
        return sb.toString().trimEnd()
    }

    fun clear(context: Context) {
        runCatching {
            prefs(context).edit().remove(KEY_ENTRIES).apply()
        }
    }
}
