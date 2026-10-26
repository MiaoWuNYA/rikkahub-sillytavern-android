package me.rerere.rikkahub.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.utils.l10n

private const val TAG = "CrashHandler"
private const val PREFS_NAME = "crash_handler"
private const val KEY_CRASHED = "crashed"
private const val KEY_STACKTRACE = "stacktrace"
private const val MAX_STACKTRACE_LENGTH = 16000
private const val MAX_LOG_FILES = 30

object CrashHandler {
    fun install(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            // StackOverflowError 时崩溃线程的栈已耗尽，handler 里再调任何方法都会二次溢出：
            // 写日志放到独立线程跑，全部包 runCatching，保证崩溃现场尽量落盘
            val writer = Thread {
                runCatching { markCrashed(appContext, thread, throwable) }
            }.apply { priority = Thread.MAX_PRIORITY }
            runCatching { writer.start(); writer.join(5000) }
            runCatching {
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        Toast.makeText(appContext, l10n("崩溃: ${throwable.javaClass.simpleName}: ${throwable.message?.take(100)}", "Crash: ${throwable.javaClass.simpleName}: ${throwable.message?.take(100)}"), Toast.LENGTH_LONG).show()
                    }
                }
            }
            runCatching { Thread.sleep(3000) }
            runCatching { defaultHandler?.uncaughtException(thread, throwable) }
        }
    }

    // 上一次进程退出的系统级记录：Java handler 抓不到的 native crash / ANR / 低内存查杀
    // 都在这里留下原因与 trace（API 31+ 有 trace）。应用启动时调用。
    fun recordLastExitInfo(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val appContext = context.applicationContext
        runCatching {
            val am = appContext.getSystemService(ActivityManager::class.java) ?: return
            val exits = am.getHistoricalProcessExitReasons(appContext.packageName, 0, 6)
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val seen = prefs.getStringSet(KEY_EXIT_SEEN, emptySet())?.toMutableSet() ?: mutableSetOf()
            exits.forEach { info ->
                if (info.reason !in INTERESTING_EXIT_REASONS) return@forEach
                val key = info.timestamp.toString()
                if (key in seen) return@forEach
                // trace 是二进制 protobuf tombstone：按文本读会把非 UTF-8 字节全变成
                // U+FFFD 永久损坏。这里读原始字节，提取内嵌可读字符串做摘要 + base64 存原文
                val traceBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching { info.traceInputStream?.readBytes() }.getOrNull()
                } else null
                writeCrashFile(
                    appContext,
                    "exit-$key",
                    buildString {
                        appendLine("Exit reason: ${exitReasonName(info.reason)} (${info.reason})")
                        info.description?.takeIf { it.isNotBlank() }?.let { appendLine("Description: $it") }
                        if (traceBytes != null) {
                            appendLine()
                            appendLine("Trace summary (strings inside protobuf tombstone):")
                            appendLine(extractTombstoneStrings(traceBytes))
                            appendLine()
                            appendLine("Trace (protobuf, base64):")
                            appendLine(android.util.Base64.encodeToString(traceBytes, android.util.Base64.NO_WRAP))
                        }
                    },
                )
                seen += key
            }
            if (seen.size > 64) {
                prefs.edit { putStringSet(KEY_EXIT_SEEN, seen.sorted().takeLast(64).toSet()) }
            } else {
                prefs.edit { putStringSet(KEY_EXIT_SEEN, seen) }
            }
        }.onFailure { Log.w(TAG, "recordLastExitInfo failed", it) }
    }

    fun hasCrashed(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CRASHED, false)
    }

    fun getStackTrace(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STACKTRACE, null)
    }

    fun clearCrashed(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_CRASHED).remove(KEY_STACKTRACE) }
    }

    fun getCrashLogDir(context: Context): File = File(context.filesDir, "crash_logs")

    fun listCrashLogs(context: Context): List<File> =
        getCrashLogDir(context).listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun readCrashLog(file: File): String = runCatching { file.readText() }.getOrDefault("")

    fun clearCrashLogs(context: Context) {
        getCrashLogDir(context).listFiles()?.forEach { it.delete() }
    }

    // WebView 渲染进程崩溃不走 UncaughtExceptionHandler，单独落日志方便对照闪退现场
    fun logRendererCrash(context: Context, source: String, detail: String) {
        runCatching {
            writeCrashFile(
                context,
                "renderer-${System.currentTimeMillis()}",
                buildString {
                    appendLine("Source: $source")
                    appendLine("Detail: $detail")
                },
            )
        }
    }

    private fun markCrashed(context: Context, thread: Thread, throwable: Throwable) {
        val stackTrace = buildString {
            appendLine("Thread: ${thread.name}")
            appendLine(throwable.stackTraceToString())
        }.take(MAX_STACKTRACE_LENGTH)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putBoolean(KEY_CRASHED, true)
                putString(KEY_STACKTRACE, stackTrace)
            }
        writeCrashFile(
            context,
            "java-${System.currentTimeMillis()}",
            buildString {
                appendLine("Thread: ${thread.name}")
                appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message}")
                var cause: Throwable? = throwable.cause
                while (cause != null) {
                    appendLine("Caused by: ${cause.javaClass.name}: ${cause.message}")
                    cause = cause.cause
                }
                appendLine()
                appendLine(throwable.stackTraceToString())
            },
        )
    }

    // 崩溃路径上只能同步写；头部带版本/设备信息便于离线排查
    private fun writeCrashFile(context: Context, stem: String, body: String) {
        val dir = getCrashLogDir(context)
        if (!dir.exists()) dir.mkdirs()
        val content = buildString {
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine()
            appendLine(body)
        }
        File(dir, "$stem.log").writeText(content)
        dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?.sortedBy { it.name }
            ?.dropLast(MAX_LOG_FILES)
            ?.forEach { it.delete() }
    }

    private val INTERESTING_EXIT_REASONS = setOf(
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
    )

    private const val KEY_EXIT_SEEN = "exit_seen"

    // tombstone proto 的字段号在 ROM 间有变体，这里不按 schema 解析：
    // 通用 wire-format 遍历，收集所有可打印文本段（线程名/函数名/库名/cause 等）
    private fun extractTombstoneStrings(bytes: ByteArray, depth: Int = 0): String {
        if (depth > 8) return ""
        val out = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val key = readVarint(bytes, i) ?: return out.toString()
            i = key.second
            when (val wireType = (key.first and 0x7).toInt()) {
                0 -> { val v = readVarint(bytes, i) ?: return out.toString(); i = v.second }
                1 -> i += 8
                5 -> i += 4
                2 -> {
                    val len = readVarint(bytes, i) ?: return out.toString()
                    i = len.second
                    val end = i + len.first.toInt()
                    if (end > bytes.size) return out.toString()
                    if (end > i) {
                        val chunk = bytes.copyOfRange(i, end)
                        if (isPrintableText(chunk)) {
                            out.appendLine(chunk.decodeToString())
                        } else if (depth < 8) {
                            out.append(extractTombstoneStrings(chunk, depth + 1))
                        }
                    }
                    i = end
                }
                else -> return out.toString()
            }
        }
        return out.toString()
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = start
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) return result to i
            shift += 7
            if (shift > 63) return null
        }
        return null
    }

    private fun isPrintableText(chunk: ByteArray): Boolean {
        if (chunk.isEmpty() || chunk.size > 512) return false
        if (chunk.any { it.toInt() == 0 }) return false
        val printable = chunk.count { it.toInt() in 32..126 || it.toInt() < 0 }
        return printable >= chunk.size * 0.9
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED(异常信号)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY(低内存查杀)"
        ApplicationExitInfo.REASON_CRASH -> "CRASH(Java 崩溃)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE(native 崩溃)"
        ApplicationExitInfo.REASON_ANR -> "ANR(主线程无响应)"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE(资源滥用查杀)"
        else -> "UNKNOWN"
    }
}
