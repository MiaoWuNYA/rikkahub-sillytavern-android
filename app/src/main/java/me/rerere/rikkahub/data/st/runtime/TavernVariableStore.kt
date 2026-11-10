package me.rerere.rikkahub.data.st.runtime

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.st.macro.JsNumberNormalizer

/**
 * 酒馆变量存储（ST / JS-Slash-Runner / MVU 语义）。
 *
 * 作用域对齐 SillyTavern：
 * - global → `extension_settings.variables.global`
 * - chat   → `chat_metadata.variables`（键为 conversationId）
 * - message 级（`chat[i].variables[swipe]`）由消息桥在 JS 侧按只读视图提供。
 *
 * 形态为 JSON 树（支持嵌套对象/数组，JSON Patch 可作用于任意路径），
 * 内存同步读写（宏引擎/输出变压器直接调用）+ 去抖异步落盘
 * （`filesDir/st_runtime/vars.json`）。
 */
object TavernVariableStore {
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_KEYS_PER_SCOPE = 5000
    private const val FLUSH_DEBOUNCE_MS = 800L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile private var appContext: Context? = null
    @Volatile private var loaded = false
    @Volatile private var dirty = false
    private var flushJob: Job? = null

    /** conversationId → 变量树 */
    private val chats = linkedMapOf<String, LinkedHashMap<String, JsonElement>>()
    private val global = linkedMapOf<String, JsonElement>()

    /** 记录已在运行时同步/初始化过的会话与全局标志，以及显式删除墓碑，防止从旧 Settings 快照复活已删除变量 */
    private val initializedChats = linkedSetOf<String>()
    @Volatile private var globalInitialized = false
    private val deletedChatKeys = linkedMapOf<String, LinkedHashSet<String>>()
    private val deletedGlobalKeys = linkedSetOf<String>()

    /** 变量变更原子递增版本号（供 StRegexTextCache 等显示层感知变量变化自动刷新缓存） */
    private val _mutationVersion = java.util.concurrent.atomic.AtomicLong(0L)
    val mutationVersion: Long
        get() = _mutationVersion.get()

    /** 扩展作用域变量树（`char:<id>` / `preset:<name>` / `script:<id>` 等独立隔离存储）。 */
    private val scoped = linkedMapOf<String, LinkedHashMap<String, JsonElement>>()

    /** conversationId → 消息级变量（`chat[i].variables[swipe]` 数组套数组，JSON 原样透传）。 */
    private val messageVars = linkedMapOf<String, JsonElement>()

    private fun varsFile(context: Context): File =
        File(context.filesDir, "st_runtime/vars.json")

    // ==================== 生命周期 ====================

    fun ensureInitialized(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            appContext = context.applicationContext
            runCatching { loadLocked(context.applicationContext) }
                .onFailure { dirty = false }
            loaded = true
        }
    }

    private fun loadLocked(context: Context) {
        val f = varsFile(context)
        if (!f.exists()) return
        val root = json.parseToJsonElement(f.readText()) as? JsonObject ?: return
        (root["global"] as? JsonObject)?.forEach { (k, v) -> global[k] = v }
        (root["chats"] as? JsonObject)?.forEach { (chatKey, tree) ->
            val obj = tree as? JsonObject ?: return@forEach
            val map = linkedMapOf<String, JsonElement>()
            obj.forEach { (k, v) -> map[k] = v }
            chats[chatKey] = map
        }
        (root["scoped"] as? JsonObject)?.forEach { (scopeKey, tree) ->
            val obj = tree as? JsonObject ?: return@forEach
            val map = linkedMapOf<String, JsonElement>()
            obj.forEach { (k, v) -> map[k] = v }
            scoped[scopeKey] = map
        }
        (root["messageVars"] as? JsonObject)?.forEach { (chatKey, tree) ->
            messageVars[chatKey] = tree
        }
    }

    /** App 启动迁移：把旧宏变量（Settings.macroGlobalVariables/macroChatVariables）合并进树。 */
    fun importLegacy(legacyGlobal: Map<String, String>, legacyChats: Map<String, Map<String, String>>) {
        synchronized(lock) {
            legacyGlobal.forEach { (k, v) ->
                if (!global.containsKey(k)) global[k] = JsonPrimitive(v)
            }
            legacyChats.forEach { (chatKey, vars) ->
                val map = chats.getOrPut(chatKey) { linkedMapOf() }
                vars.forEach { (k, v) ->
                    if (!map.containsKey(k)) map[k] = JsonPrimitive(v)
                }
            }
            if (legacyGlobal.isNotEmpty() || legacyChats.isNotEmpty()) markDirtyLocked()
        }
    }

    // ==================== 读（返回副本，树只读语义） ====================

    fun getGlobal(): JsonObject = synchronized(lock) { JsonObject(global.toMap()) }

    fun getChat(chatKey: String): JsonObject = synchronized(lock) {
        JsonObject(chats[chatKey]?.toMap() ?: emptyMap())
    }

    /** 读取独立作用域变量（`char:<id>` / `preset:<name>` / `script:<id>`）。 */
    fun getScoped(scopeKey: String): JsonObject = synchronized(lock) {
        val normKey = scopeKey.trim()
        if (normKey.isEmpty()) return@synchronized JsonObject(emptyMap())
        JsonObject(scoped[normKey]?.toMap() ?: emptyMap())
    }

    /** 消息级变量（JSR `chat[i].variables[swipe]` 原样 JSON，数组套数组）。 */
    fun getMessageVars(chatKey: String): JsonElement = synchronized(lock) {
        messageVars[chatKey] ?: JsonArray(emptyList())
    }

    fun replaceMessageVars(chatKey: String, element: JsonElement) = synchronized(lock) {
        messageVars[chatKey] = element
        markDirtyLocked()
    }

    /** 供提示词渲染等场景使用：树 → 字符串化视图（顶层键）。 */
    fun getChatAsStringMap(chatKey: String): Map<String, String> = synchronized(lock) {
        chats[chatKey]?.mapValues { (_, v) -> stringify(v) } ?: emptyMap()
    }

    fun isGlobalInitialized(): Boolean = synchronized(lock) { globalInitialized }

    fun isChatInitialized(chatKey: String): Boolean = synchronized(lock) { chatKey in initializedChats }

    fun isDeleted(chatKey: String?, name: String): Boolean = synchronized(lock) {
        if (chatKey == null) name in deletedGlobalKeys else deletedChatKeys[chatKey]?.contains(name) == true
    }

    fun clearForTest() = synchronized(lock) {
        chats.clear()
        global.clear()
        scoped.clear()
        messageVars.clear()
        initializedChats.clear()
        globalInitialized = false
        deletedChatKeys.clear()
        deletedGlobalKeys.clear()
        _mutationVersion.incrementAndGet()
    }

    // ==================== 写 ====================

    fun replaceGlobal(obj: JsonObject) = synchronized(lock) {
        globalInitialized = true
        global.clear()
        obj.forEach { (k, v) ->
            if (global.size < MAX_KEYS_PER_SCOPE) {
                global[k] = v
                deletedGlobalKeys.remove(k)
            }
        }
        _mutationVersion.incrementAndGet()
        markDirtyLocked()
    }

    fun replaceChat(chatKey: String, obj: JsonObject) = synchronized(lock) {
        initializedChats.add(chatKey)
        val map = linkedMapOf<String, JsonElement>()
        obj.forEach { (k, v) -> if (map.size < MAX_KEYS_PER_SCOPE) map[k] = v }
        chats[chatKey] = map
        deletedChatKeys[chatKey]?.removeAll(map.keys)
        _mutationVersion.incrementAndGet()
        markDirtyLocked()
    }

    /** 替换独立作用域变量（`char:<id>` / `preset:<name>` / `script:<id>`）。 */
    fun replaceScoped(scopeKey: String, obj: JsonObject) = synchronized(lock) {
        val normKey = scopeKey.trim()
        if (normKey.isEmpty()) return@synchronized
        val map = linkedMapOf<String, JsonElement>()
        obj.forEach { (k, v) -> if (map.size < MAX_KEYS_PER_SCOPE) map[k] = v }
        scoped[normKey] = map
        _mutationVersion.incrementAndGet()
        markDirtyLocked()
    }

    fun mutateChat(chatKey: String, block: (MutableMap<String, JsonElement>) -> Unit) = synchronized(lock) {
        initializedChats.add(chatKey)
        val map = chats.getOrPut(chatKey) { linkedMapOf() }
        block(map)
        while (map.size > MAX_KEYS_PER_SCOPE) {
            map.remove(map.keys.first())
        }
        _mutationVersion.incrementAndGet()
        markDirtyLocked()
    }

    fun removeChat(chatKey: String) = synchronized(lock) {
        initializedChats.remove(chatKey)
        deletedChatKeys.remove(chatKey)
        if (chats.remove(chatKey) != null) markDirtyLocked()
        messageVars.remove(chatKey)
        _mutationVersion.incrementAndGet()
    }

    // ==================== 宏适配（String 语义，对齐 ST variables.js） ====================

    fun macroGet(chatKey: String?, name: String): String? = synchronized(lock) {
        val element = if (chatKey == null) global[name] else chats[chatKey]?.get(name) ?: return null
        when (element) {
            null, is JsonNull -> null
            is JsonPrimitive -> {
                val raw = element.contentOrNull ?: return null
                // ST get 语义：数字字符串归一化（"3.0" → "3"）
                JsNumberNormalizer.getNormalized(raw)
            }
            is JsonObject, is JsonArray -> element.toString()
        }
    }

    fun macroHas(chatKey: String?, name: String): Boolean = synchronized(lock) {
        val map = if (chatKey == null) global else chats[chatKey] ?: return false
        map.containsKey(name)
    }

    fun macroSet(chatKey: String?, name: String, value: String) = synchronized(lock) {
        if (chatKey == null) {
            deletedGlobalKeys.remove(name)
        } else {
            deletedChatKeys[chatKey]?.remove(name)
        }
        val map = if (chatKey == null) global else chats.getOrPut(chatKey) { linkedMapOf() }
        map[name] = JsonPrimitive(value)
        _mutationVersion.incrementAndGet()
        markDirtyLocked()
    }

    fun macroDelete(chatKey: String?, name: String) = synchronized(lock) {
        if (chatKey == null) {
            deletedGlobalKeys.add(name)
            global.remove(name)
        } else {
            deletedChatKeys.getOrPut(chatKey) { linkedSetOf() }.add(name)
            chats[chatKey]?.remove(name)
        }
        _mutationVersion.incrementAndGet()
        markDirtyLocked()
    }

    fun macroInc(chatKey: String?, name: String): String = macroAdd(chatKey, name, "1")

    fun macroDec(chatKey: String?, name: String): String = macroAdd(chatKey, name, "-1")

    /** 对齐 ST add（与 InMemoryMacroVariableStore.addImpl 完全一致）：数组 push；纯数字相加；否则字符串拼接。 */
    fun macroAdd(chatKey: String?, name: String, value: String): String = synchronized(lock) {
        if (chatKey == null) {
            deletedGlobalKeys.remove(name)
        } else {
            deletedChatKeys[chatKey]?.remove(name)
        }
        val map = if (chatKey == null) global else chats.getOrPut(chatKey) { linkedMapOf() }
        val result = computeAdd(map[name], value)
        map[name] = result
        _mutationVersion.incrementAndGet()
        markDirtyLocked()
        stringify(result)
    }

    /** 与 InMemoryMacroVariableStore.addImpl 相同的计算逻辑（输入输出为树元素）。 */
    private fun computeAdd(current: JsonElement?, value: String): JsonElement {
        if (current is JsonArray) return JsonArray(current + JsonPrimitive(value))
        val rawCurrent = when (current) {
            null, is JsonNull -> null
            is JsonPrimitive -> current.contentOrNull
            else -> current.toString()
        }
        // const currentValue = getLocalVariable(name) || 0;
        val currentValue: String = run {
            val normalized = rawCurrent?.let { JsNumberNormalizer.getNormalized(it) }
            if (normalized.isNullOrEmpty()) "0" else normalized
        }
        // try { const parsedValue = JSON.parse(currentValue); if (Array.isArray(...)) push }
        val parsedArray = runCatching {
            json.parseToJsonElement(currentValue) as? JsonArray
        }.getOrNull()
        if (parsedArray != null) return JsonArray(parsedArray + JsonPrimitive(value))
        val increment = JsNumberNormalizer.parse(value)
        if (increment == null || JsNumberNormalizer.parse(currentValue) == null) {
            // 字符串拼接分支：String(currentValue || '') + value
            return JsonPrimitive(falsyBase(currentValue) + value)
        }
        val newValue = JsNumberNormalizer.parse(currentValue)!! + increment
        return JsonPrimitive(JsNumberNormalizer.format(newValue))
    }

    /** 模拟 JS `currentValue || ''` 的基值处理。 */
    private fun falsyBase(currentValue: String): String {
        if (currentValue.isEmpty()) return ""
        val n = JsNumberNormalizer.parse(currentValue)
        return when {
            n != null -> if (n == 0.0) "" else JsNumberNormalizer.format(n)
            else -> currentValue
        }
    }

    // ==================== 工具 ====================

    private fun stringify(element: JsonElement): String = when (element) {
        is JsonNull -> ""
        is JsonPrimitive -> element.contentOrNull ?: element.toString()
        else -> element.toString()
    }

    // ==================== 持久化 ====================

    private fun markDirtyLocked() {
        dirty = true
        val ctx = appContext ?: return
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DEBOUNCE_MS)
            runCatching { writeNow(ctx) }
        }
    }

    /** 立即落盘（生成结束等关键节点可主动调用）。 */
    fun flushNow() {
        val ctx = appContext ?: return
        scope.launch { runCatching { writeNow(ctx) } }
    }

    private fun writeNow(context: Context) {
        val payload: String
        synchronized(lock) {
            if (!dirty) return
            val root = JsonObject(
                mapOf(
                    "version" to JsonPrimitive(1),
                    "global" to JsonObject(global.toMap()),
                    "chats" to JsonObject(chats.mapValues { (_, v) -> JsonObject(v.toMap()) }),
                    "scoped" to JsonObject(scoped.mapValues { (_, v) -> JsonObject(v.toMap()) }),
                    "messageVars" to JsonObject(messageVars.toMap()),
                )
            )
            payload = json.encodeToString(JsonObject.serializer(), root)
            dirty = false
        }
        val f = varsFile(context)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.writeText(payload)
        if (!tmp.renameTo(f)) {
            f.writeText(payload)
            tmp.delete()
        }
    }
}
