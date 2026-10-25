package me.rerere.rikkahub.utils

import android.content.Context
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
            markCrashed(appContext, thread, throwable)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(appContext, l10n("崩溃: ${throwable.javaClass.simpleName}: ${throwable.message?.take(100)}", "Crash: ${throwable.javaClass.simpleName}: ${throwable.message?.take(100)}"), Toast.LENGTH_LONG).show()
            }
            Thread.sleep(3000)
            defaultHandler?.uncaughtException(thread, throwable)
        }
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
        runCatching {
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
}
