package me.rerere.rikkahub.data.st.preset

import android.content.Context
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 最小等价实现（等待正式 runtime 交付替换）：ST 全局变量作用域落 vars.json。
 * StSettingsImport 只用到 ensureInitialized + replaceGlobal。
 */
internal object TavernVariableStoreCompat {
    const val MAX_KEYS_PER_SCOPE = 5000

    private const val RUNTIME_DIR = "st_runtime"
    private const val VARS_FILE = "vars.json"
    private const val GLOBAL_SCOPE = "global"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val lock = Any()

    private var file: File? = null
    private var global: JsonObject = JsonObject(emptyMap())

    fun ensureInitialized(context: Context) {
        synchronized(lock) {
            if (file != null) return
            val dir = File(context.filesDir, RUNTIME_DIR).apply { mkdirs() }
            val target = File(dir, VARS_FILE)
            file = target
            global = readRoot(target)[GLOBAL_SCOPE] as? JsonObject ?: JsonObject(emptyMap())
        }
    }

    fun replaceGlobal(values: JsonObject) {
        val capped = if (values.size > MAX_KEYS_PER_SCOPE) {
            JsonObject(values.entries.take(MAX_KEYS_PER_SCOPE).associate { it.toPair() })
        } else {
            values
        }
        synchronized(lock) {
            global = capped
            val target = file ?: return
            runCatching {
                val root = readRoot(target).toMutableMap()
                root[GLOBAL_SCOPE] = capped
                target.writeText(JsonObject(root).toString())
            }
        }
    }

    fun global(): JsonObject = synchronized(lock) { global }

    private fun readRoot(target: File): JsonObject {
        if (!target.isFile) return JsonObject(emptyMap())
        return runCatching {
            json.parseToJsonElement(target.readText()) as? JsonObject
        }.getOrNull() ?: JsonObject(emptyMap())
    }
}
