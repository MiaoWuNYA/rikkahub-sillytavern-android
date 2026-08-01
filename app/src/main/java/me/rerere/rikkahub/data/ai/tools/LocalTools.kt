package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import java.security.SecureRandom
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.ai.tools.local.buildCalendarCreateTool
import me.rerere.rikkahub.data.ai.tools.local.buildCalendarQueryTool
import me.rerere.rikkahub.data.ai.tools.local.buildScreenTimeTool
import me.rerere.rikkahub.data.ai.tools.local.buildAskUserTool
import me.rerere.rikkahub.data.ai.tools.local.buildClipboardTool
import me.rerere.rikkahub.data.ai.tools.local.buildTextToSpeechTool
import me.rerere.rikkahub.data.ai.tools.local.buildTimeInfoTool
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.tts.provider.TTSManager
import me.rerere.rikkahub.utils.readClipboardText
import me.rerere.rikkahub.utils.writeClipboardText
import java.io.File
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * 清理 present_file 在 cache 目录留下的 shared_ 前缀缓存文件
 */
private fun cleanupPresentFileCache(cacheDir: File) {
    try {
        cacheDir.listFiles()
            ?.filter { it.name.startsWith("shared_") }
            ?.forEach { it.delete() }
    } catch (_: Exception) { }
}

@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()

    @Serializable
    @SerialName("present_file")
    data object PresentFile : LocalToolOption()

    @Serializable
    @SerialName("python_engine")
    data object PythonEngine : LocalToolOption()

    @Serializable
    @SerialName("asset_generator")
    data object AssetGenerator : LocalToolOption()

    @Serializable
    @SerialName("file_tools")
    data object FileTools : LocalToolOption()

    @Serializable
    @SerialName("shell_tools")
    data object ShellTools : LocalToolOption()

    @Serializable
    @SerialName("github_tools")
    data object GitHubTools : LocalToolOption()

    @Serializable
    @SerialName("convert_file")
    data object ConvertFile : LocalToolOption()

    @Serializable
    @SerialName("database_query")
    data object DatabaseQuery : LocalToolOption()

    @Serializable
    @SerialName("task_tools")
    data object TaskTools : LocalToolOption()

    @Serializable
    @Serializable
    @SerialName("calculator")
    data object Calculator : LocalToolOption()

    @Serializable
    @SerialName("worker_tools")
    data object WorkerTools : LocalToolOption()

    @Serializable
    @SerialName("teammate_tools")
    data object TeammateTools : LocalToolOption()

    @Serializable
    @SerialName("send_message")
    data object SendMessage : LocalToolOption()

    @Serializable
    @SerialName("screen_time")
    data object ScreenTime : LocalToolOption()

    @Serializable
    @SerialName("calendar")
    data object Calendar : LocalToolOption()
}

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
) {
    // ── Persistent JS engine ──
    private var jsExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val jsContextLock = Any()
    @Volatile private var jsContext: QuickJSContext? = null
    private val loadedLibraries = mutableSetOf<String>()
    @Volatile private var contextDirty = false  // set on timeout → force reset next call

    private fun getOrCreateJSContext(): QuickJSContext {
        if (jsContext == null) {
            synchronized(jsContextLock) {
                if (jsContext == null) {
                    jsContext = QuickJSContext.create()

                    // ── Hardware true random via SecureRandom → JS bridge ──
                    val sr = SecureRandom()
                    jsContext!!.globalObject.setProperty("__hardwareRandU32", JSCallFunction {
                        val b = ByteArray(4); sr.nextBytes(b)
                        (b[0].toInt() and 0xFF) or
                        ((b[1].toInt() and 0xFF) shl 8) or
                        ((b[2].toInt() and 0xFF) shl 16) or
                        ((b[3].toInt() and 0xFF) shl 24)
                    })
                    jsContext!!.evaluate(
                        "crypto={getRandomValues:function(a){" +
                        "for(var i=0;i<a.length;i++)a[i]=__hardwareRandU32();return a}};"
                    )

                    // ── Console wired to tool logs via JS array ──
                    // After eval, __console_logs is extracted and cleared
                    jsContext!!.evaluate(
                        "var __console_logs=[];" +
                        "console={log:function(){for(var i=0;i<arguments.length;i++)__console_logs.push(String(arguments[i]))}," +
                        "error:function(){for(var i=0;i<arguments.length;i++)__console_logs.push('[ERROR] '+String(arguments[i]))}," +
                        "warn:function(){for(var i=0;i<arguments.length;i++)__console_logs.push('[WARN] '+String(arguments[i]))}," +
                        "info:function(){for(var i=0;i<arguments.length;i++)__console_logs.push('[INFO] '+String(arguments[i]))}};"
                    )

                    // ── Device timezone (IANA name) → JS global, for Intl polyfill ──
                    // E.g. "Asia/Shanghai", "America/New_York", "Europe/London"
                    // The Intl polyfill in engine entry files reads this variable to
                    // return the correct timezone from resolvedOptions().timeZone,
                    // instead of hardcoding "UTC".
                    val deviceTz = java.util.TimeZone.getDefault().getID()
                    jsContext!!.evaluate("var __device_timezone = '$deviceTz';")
                }
            }
        }
        return jsContext!!
    }

    private fun resetJSContext() {
        synchronized(jsContextLock) {
            jsContext?.destroy()
            jsContext = null
            loadedLibraries.clear()
        }
    }

    /** Abandon current JS context WITHOUT calling destroy() — safe when native code
     *  might still be running on a stuck thread (after timeout). GC cleans up later. */
    private fun abandonJSContext() {
        synchronized(jsContextLock) {
            jsContext = null
            loadedLibraries.clear()
        }
    }

    val javascriptTool by lazy {
        Tool(
            name = "eval_javascript",
            description = "Execute JavaScript code using QuickJS engine (ES2020, persistent context).\n\n" +
                "The JS context persists between calls — libraries loaded via action='load' stay available.\n" +
                "⚠️ NOTE: Eval runs in a block scope — `const`/`let` are local to each call (no redeclaration errors).\n" +
                "Use `var` for variables that need to persist across multiple calls.\n\n" +
                "Use this tool for calculations, text processing, or divination engines.\n\n" +
                "When to use:\n" +
                "- Run JavaScript for calculations, text processing, or prototyping\n" +
                "- Load a JS engine: action='load', library='qimen-engine' (loads once, cached)\n" +
                "- Call engine: action='eval', code='QimenEngine.generate({...})'\n" +
                "- Reset context: action='reset' (clears all loaded libraries)\n\n" +
                "Available JS engines (action='load', library=...):\n" +
                "  qimen-engine (QiMen) | ziwei-nihai (ZiweiNihai) | iching-shifa-engine (IchingShifa) | taixuan-engine (TaixuanLib)\n" +
                "  lunar-engine (Lunar) | astronomy-engine (Astronomy) | horoscope-engine (HoroscopeJS) | kaabalah-engine (Kaabalah)\n" +
                "  caelus-engine (Caelus: Western+Vedic astrology) | caelus-birth (CaelusBirth: timezone→UT)\n" +
                "  iztro-engine (Iztro: 紫微斗数) | natalengine-engine (NatalEngine: 人类图/基因钥匙)\n" +
                "  node-jhora-engine (NodeJhora: 印度占星深度版, DE440/Shadbala/Ashtakavarga/Jaimini/KP)\n\n" +

                "- action: 'eval' (default) | 'load' | 'reset'\n" +
                "- library: asset filename without .js (for action='load') — loads once, cached\n" +
                "- function: (optional) call a global function by name with JSON args\n" +
                "- code: JavaScript code to execute (for action='eval')\n" +
                "- timeout: (optional) seconds, default 60, max 60",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put("enum", kotlinx.serialization.json.buildJsonArray {
                                add("eval"); add("load"); add("reset")
                            })
                            put("description", "Action: eval (execute code), load (pre-load library), reset (clear context)")
                        })
                        put("library", buildJsonObject {
                            put("type", "string")
                            put("description", "Asset filename without .js (for action='load')")
                        })
                        put("function", buildJsonObject {
                            put("type", "string")
                            put("description", "Call a global function by name. Use 'args' to pass JSON array.")
                        })
                        put("args", buildJsonObject {
                            put("type", "array")
                            put("description", "JSON array of arguments for function call")
                        })
                        put("code", buildJsonObject {
                            put("type", "string")
                            put("description", "JavaScript code to execute (for action='eval')")
                        })
                        put("timeout", buildJsonObject {
                            put("type", "integer")
                            put("description", "Timeout in seconds (default 60, max 60)")
                        })
                    }
                )
            },
            execute = {
                val logs = arrayListOf<String>()
                val action = it.jsonObject["action"]?.jsonPrimitive?.contentOrNull ?: "eval"
                val library = it.jsonObject["library"]?.jsonPrimitive?.contentOrNull
                val code = it.jsonObject["code"]?.jsonPrimitive?.contentOrNull
                val funcName = it.jsonObject["function"]?.jsonPrimitive?.contentOrNull
                val rawArgs = it.jsonObject["args"]?.toString() // works for both arrays and objects
                val timeoutSec = (it.jsonObject["timeout"]?.jsonPrimitive?.contentOrNull ?: "60").toLongOrNull() ?: 60L
                val safeTimeout = minOf(timeoutSec, 60L)

                var future: java.util.concurrent.Future<String>? = null
                try {
                    future = jsExecutor.submit<String> {
                        when (action) {
                            "reset" -> {
                                resetJSContext()
                                logs.add("[INFO] JS context reset — all libraries cleared")
                                "ok"
                            }
                            "load" -> {
                                // Also check contextDirty here — if previous eval timed out, the old
                                // context was abandoned by the eval branch. Without this check, load
                                // uses the stale (or abandoned-null) context while loadedLibraries still
                                // has entries, so it skips loading. Then the next eval creates a fresh
                                // context with no libraries loaded → "'X' is not defined".
                                if (contextDirty) {
                                    abandonJSContext()
                                    contextDirty = false
                                }
                                val lib = library ?: throw IllegalArgumentException("library is required for action='load'")
                                val ctx = getOrCreateJSContext()
                                if (lib !in loadedLibraries) {
                                    // NodeJhora: inject 32MB de440s.bsp as Uint8Array before loading engine
                                    if (lib == "node-jhora-engine") {
                                        val bspBytes = context.assets.open("de440s.bsp").readBytes()
                                        ctx.globalObject.setProperty("__nodejhora_bsp", bspBytes)
                                        logs.add("[INFO] Injected de440s.bsp (${bspBytes.size} bytes) for NodeJhora")
                                    }
                                    val engineCode = context.assets.open("$lib.js").bufferedReader().readText()
                                    ctx.evaluate(engineCode)
                                    loadedLibraries.add(lib)
                                    logs.add("[INFO] Loaded: $lib.js (${engineCode.length} bytes) — cached for subsequent calls")
                                } else {
                                    logs.add("[INFO] $lib.js already loaded (cached)")
                                }
                                "loaded"
                            }
                            else -> {
                                // If previous execution timed out, nuke the stuck context
                                if (contextDirty) {
                                    abandonJSContext()  // no destroy() — native code may still run
                                    contextDirty = false
                                    logs.add("[WARN] JS context was reset — previous execution had timed out and corrupted the runtime. Libraries need reloading.")
                                }
                                val ctx = getOrCreateJSContext()
                                // Wrap code in block scope: const/let die inside, var persists globally.
                                // This prevents "redeclaration of X" errors on repeated eval_javascript calls
                                // while still allowing `var engine = ...` to survive across calls.
                                val codeResult = if (!code.isNullOrBlank()) {
                                    logs.add("[INFO] eval: ${code.take(100)}...")
                                    ctx.evaluate("{\n$code\n}")
                                } else null

                                // Call function if specified (with safe arg binding)
                                val funcResult = if (funcName != null) {
                                    val argsJson = rawArgs ?: "[]"
                                    // Bind args via temp global to avoid string escaping in eval
                                    ctx.evaluate("__js_args = $argsJson")
                                    ctx.evaluate("$funcName.apply(null, __js_args)")
                                } else null

                                val finalResult = funcResult ?: codeResult
                                val resultStr = when (finalResult) {
                                    null -> "ok"
                                    is QuickJSObject -> finalResult.stringify()
                                    else -> finalResult.toString()
                                }
                                // Extract console.log output from JS array
                                try {
                                    val jsLogs = ctx.evaluate("var _l=__console_logs;__console_logs=[];JSON.stringify(_l)")
                                    if (jsLogs is String && jsLogs != "[]") {
                                        val arr = kotlinx.serialization.json.Json.parseToJsonElement(jsLogs).jsonArray
                                        arr.forEach { logs.add("[js] ${it.jsonPrimitive.content}") }
                                    }
                                } catch (_: Exception) { /* console extraction best-effort */ }
                                resultStr
                            }
                        }
                    }
                    val resultStr = future!!.get(safeTimeout, java.util.concurrent.TimeUnit.SECONDS)
                    val payload = buildJsonObject {
                        if (logs.isNotEmpty()) put("logs", JsonPrimitive(logs.joinToString("\n")))
                        put("result", JsonPrimitive(resultStr))
                    }
                    listOf(UIMessagePart.Text(payload.toString()))
                } catch (e: java.util.concurrent.TimeoutException) {
                    future?.cancel(true)
                    // Native QuickJS evaluate() can't be interrupted — the thread is stuck.
                    // Abandon the old executor+context entirely, force a fresh start next call.
                    jsExecutor.shutdownNow()
                    jsExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
                    contextDirty = true
                    error("JavaScript execution timed out after ${safeTimeout}s — context will be reset on next call")
                } catch (e: Exception) {
                    future?.cancel(true)
                    val msg = e.message ?: e.toString()
                    error("JavaScript error: $msg")
                }
            }
        )
    }

    val timeTool by lazy { buildTimeInfoTool() }

    val clipboardTool by lazy { buildClipboardTool(context) }

    val ttsTool by lazy { buildTextToSpeechTool(eventBus, ttsManager, settingsStore) }

    val askUserTool by lazy { buildAskUserTool() }

    val presentFileTool by lazy {
        Tool(
            name = "present_file",
            description = "Show a file to the user via the system share sheet for saving, sending, or opening with another app.\n\n" +
                "Use this tool to present screenshots, documents, or exported data.\n" +
                "Opens the system share sheet so the user can choose how to handle the file.\n\n" +
                "When to use:\n" +
                "- Present a file for saving, sending, or opening with another app\n" +
                "- Share screenshots, documents, or exported data\n\n" +
                "Args:\n" +
                "- path: Absolute path to the file to present",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Absolute path to the file to present to the user")
                        })
                    },
                    required = listOf("path"),
                )
            },
            execute = {
                val path = it.jsonObject["path"]?.jsonPrimitive?.content
                    ?: error("path is required")
                val file = File(path)
                if (!file.exists()) error("File not found: $path")
                if (!file.canRead()) error("Cannot read file: $path")

                // Copy to cache dir for FileProvider sharing
                val cacheFile = File(context.cacheDir, "shared_" + file.name)
                cacheFile.parentFile?.mkdirs()
                file.copyTo(cacheFile, overwrite = true)

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    cacheFile
                )

                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = guessMimeType(file.name)
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(
                    android.content.Intent.createChooser(intent, null).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )

                // 清理旧缓存文件
                cleanupPresentFileCache(context.cacheDir)

                val payload = buildJsonObject {
                    put("success", true)
                    put("path", path)
                    put("size", kotlinx.serialization.json.JsonPrimitive(file.length()))
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }

    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }

    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }

    fun getTools(options: List<LocalToolOption>): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (options.contains(LocalToolOption.Tts)) {
            tools.add(ttsTool)
        }
        if (options.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (options.contains(LocalToolOption.PresentFile)) {
            tools.add(presentFileTool)
        }
        if (options.contains(LocalToolOption.ScreenTime)) {
            tools.add(screenTimeTool)
        }
        if (options.contains(LocalToolOption.Calendar)) {
            tools.add(calendarQueryTool)
            tools.add(calendarCreateTool)
        }
        return tools
    }
}

/**
 * 去重：同名工具保留第一个（对标 Claude Code uniqBy）。
 * Provider 不接受同名工具，用这个兜底防止 "Tool names must be unique" 错误。
 */
fun deduplicateTools(tools: List<Tool>): List<Tool> {
    val seen = LinkedHashSet<String>()
    return tools.filter { seen.add(it.name) }
}

private fun guessMimeType(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
}
