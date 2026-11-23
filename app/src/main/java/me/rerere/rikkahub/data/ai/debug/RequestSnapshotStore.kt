package me.rerere.rikkahub.data.ai.debug

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

/**
 * 请求快照仓库：把「实际发出去的请求」落盘，并支持两份请求的前缀对比。
 *
 * 用途是排查上游前缀缓存（prompt cache）命中率异常：同一会话前后两次请求体量相近，
 * 命中 token 却差很多时，只有看到原文才能判断分叉点落在哪条消息、哪个工具、系统提示词的哪一段。
 *
 * 存储：私有目录 `files/debug/requests/`，文件名 `yyyyMMdd-HHmmss-<generationType>-<会话短id>.json`。
 * 保留策略：同一会话只留最近 [MAX_PER_CONVERSATION] 份，全局最多 [MAX_FILES] 份，
 * 裁剪只看文件名（不做解析），因此记录请求本身没有额外解析开销。
 * 单份超过 [MAX_BYTES] 时从 messages 尾部截断并置 `truncated=true`。
 */
object RequestSnapshotStore {

    /** 快照子目录（相对应用私有 files 目录） */
    private const val DIR_NAME = "debug/requests"

    /** 同一会话保留的份数：最小可用形态只需要「上一次」与「这一次」 */
    private const val MAX_PER_CONVERSATION = 2

    /** 全局保留份数，避免会话很多时无限增长 */
    private const val MAX_FILES = 12

    /** 单份快照上限，超出则截断 messages 尾部 */
    private const val MAX_BYTES = 5 * 1024 * 1024

    /** 文件名里的时间戳格式 */
    private const val STAMP_PATTERN = "yyyyMMdd-HHmmss"

    /** 换行符：单独定义，避免字符串模板里的转义写错 */
    private val NEWLINE = "\n"

    /** 与业务无关的宽松解析器：只用于读回自己写的快照 */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 系统提示词分段锚点：分叉落在系统提示词里时，用它指出落进哪一段。
     * 每组第一个命中位置最靠后的那个段即答案。
     */
    private val SYSTEM_ANCHORS: List<Pair<String, List<String>>> = listOf(
        "人设" to listOf("<persona", "## 人设", "角色设定", "Persona"),
        "记忆块" to listOf("<memory", "长期记忆", "memory_block", "Memory"),
        "日期" to listOf("Current date", "当前时间", "今天的日期", "time_reminder"),
        "Recent Chats" to listOf("Recent Chats", "<recent_chats"),
        "RAG/知识库" to listOf("<rag", "知识库", "Knowledge Base", "<knowledge"),
        "工具说明" to listOf("<tools", "工具说明", "可用工具", "Tools"),
    )

    /** 开关存放位置：用独立 SharedPreferences，避免为一个调试开关动 DataStore 迁移 */
    private const val PREFS_NAME = "debug_request_snapshot"

    private const val KEY_ENABLED = "enabled"

    /**
     * 是否记录请求快照，默认关闭。
     *
     * 关闭时调用方必须在序列化之前就返回——整份请求序列化约几十毫秒、
     * 瞬时内存几十 KB，挂在每次生成的热路径上会直接影响出字手感。
     */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    /** 切换记录开关 */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    /** 快照目录，不存在时创建 */
    fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /** 目录下全部快照，按文件名倒序（最新的在最前） */
    fun files(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedByDescending { it.name }
            .orEmpty()

    /**
     * 记录一份请求快照。
     *
     * @param conversationId 会话 id，用于按会话保留最近两份
     * @param generationType 生成类型（NORMAL / PROACTIVE / REGENERATE 等）
     * @param modelId 模型 id 字符串（便于人工辨认）
     * @param baseUrl 供应商 baseUrl
     * @param headers 实际会带上的自定义头，敏感值由调用方掩码
     * @param tools 工具清单（名称 + 完整 schema）的 JSON
     * @param messages 完整消息列表的 JSON
     */
    fun record(
        context: Context,
        conversationId: String?,
        generationType: String,
        modelId: String,
        baseUrl: String,
        headers: List<Pair<String, String>>,
        tools: JsonArray,
        messages: JsonArray,
    ) {
        val now = System.currentTimeMillis()
        val bodyText = canonical(
            buildJsonObject {
                put("tools", tools)
                put("messages", messages)
            }
        )
        val meta = buildJsonObject {
            put("time", isoTime(now))
            put("conversationId", conversationId.orEmpty())
            put("generationType", generationType)
            put("modelId", modelId)
            put("baseUrl", baseUrl)
            put("approxTokens", estimateTokens(bodyText))
            putJsonArray("headers") {
                headers.forEach { (name, value) ->
                    addJsonObject {
                        put("name", name)
                        put("value", value)
                    }
                }
            }
        }

        // 超限时从尾部丢消息，直到落回 5MB 以内；至少保留一条，避免产出空壳
        val kept = messages.toMutableList()
        var truncated = false
        var document: JsonObject
        while (true) {
            document = buildJsonObject {
                put("meta", meta)
                put("tools", tools)
                put("messages", JsonArray(kept))
                put("truncated", truncated)
            }
            val size = document.toString().toByteArray(Charsets.UTF_8).size
            if (size <= MAX_BYTES || kept.size <= 1) break
            truncated = true
            val dropCount = maxOf(1, kept.size / 10)
            repeat(dropCount) { if (kept.isNotEmpty()) kept.removeAt(kept.size - 1) }
        }

        val name = "${stamp(now)}-${generationType}-${shortId(conversationId)}.json"
        runCatching { File(dir(context), name).writeText(document.toString(), Charsets.UTF_8) }
        prune(context)
    }

    /** 删除全部快照 */
    fun clear(context: Context) {
        files(context).forEach { it.delete() }
    }

    /**
     * 对比最近两份快照，返回人类可读的诊断文本。
     * 不足两份时返回提示，不做任何假设。
     */
    fun diff(context: Context): String {
        val list = files(context)
        if (list.size < 2) {
            return "快照不足两份（当前 ${list.size} 份）。先在同一会话里正常聊一轮、再触发一次主动消息，然后回来对比。"
        }
        return diffFiles(list[1], list[0])
    }

    /**
     * 对比两份快照文件。[older] 视为 A、[newer] 视为 B。
     *
     * 输出覆盖：最长公共前缀（字符与估算 token）、分叉消息下标与 id/role/parts、
     * 工具差异（缺失/新增名称或仅顺序变化）、系统提示词落段、以及「分叉前是否完全一致」布尔值。
     *
     * 口径上只比较真正会发给上游的内容：meta 整块排除，消息里的客户端专有字段
     * （随机 id、时间戳、用量统计等）一律剥离——否则每次请求都会在第一条消息处「假分叉」。
     */
    fun diffFiles(older: File, newer: File): String {
        val a = readDocument(older) ?: return "读取失败：${older.name}"
        val b = readDocument(newer) ?: return "读取失败：${newer.name}"

        val aText = stableText(a)
        val bText = stableText(b)
        val lcp = commonPrefixLength(aText, bText)
        val shortest = min(aText.length, bText.length)
        val prefixIdentical = lcp == shortest

        val sb = StringBuilder()
        sb.appendLine("A（较早）：${older.name}")
        sb.appendLine("B（较新）：${newer.name}")
        sb.appendLine("A 总长 ${aText.length} 字符 ≈ ${estimateTokens(aText)} tokens")
        sb.appendLine("B 总长 ${bText.length} 字符 ≈ ${estimateTokens(bText)} tokens")
        sb.appendLine("最长公共前缀：$lcp 字符 ≈ ${estimateTokens(aText.substring(0, lcp))} tokens")

        sb.appendLine("口径：只对比 tools 与 messages，已剔除 meta 和客户端专有字段（id/时间/用量等）")
        val aMessages = a["messages"] as? JsonArray ?: JsonArray(emptyList())
        val bMessages = b["messages"] as? JsonArray ?: JsonArray(emptyList())
        val messageIndex = firstDiffIndex(wireMessages(a), wireMessages(b))
        if (messageIndex < 0) {
            sb.appendLine("分叉点：消息逐条完全一致（${aMessages.size} 条）")
        } else {
            sb.appendLine("分叉点：messages[$messageIndex]（A ${aMessages.size} 条 / B ${bMessages.size} 条）")
            sb.appendLine("  A：${describeMessage(aMessages.getOrNull(messageIndex))}")
            sb.appendLine("  B：${describeMessage(bMessages.getOrNull(messageIndex))}")
        }

        val aTools = a["tools"] as? JsonArray ?: JsonArray(emptyList())
        val bTools = b["tools"] as? JsonArray ?: JsonArray(emptyList())
        val aNames = aTools.mapNotNull { toolName(it) }
        val bNames = bTools.mapNotNull { toolName(it) }
        val missing = aNames.filter { it !in bNames }
        val added = bNames.filter { it !in aNames }
        when {
            aNames == bNames -> sb.appendLine("工具：${aNames.size} 个，名称与顺序完全一致")

            missing.isEmpty() && added.isEmpty() -> {
                val index = firstDiffIndex(aTools, bTools).coerceAtLeast(0)
                sb.appendLine(
                    "工具：名称集合一致、但顺序不同（A ${aNames.size} 个 / B ${bNames.size} 个），" +
                        "首个位置差异在第 $index 个：" +
                        "${toolName(aTools.getOrNull(index)) ?: "-"} → ${toolName(bTools.getOrNull(index)) ?: "-"}"
                )
            }

            else -> {
                sb.appendLine("工具：A ${aNames.size} 个 / B ${bNames.size} 个")
                if (missing.isNotEmpty()) sb.appendLine("  A 有而 B 没有：${missing.joinToString(", ")}")
                if (added.isNotEmpty()) sb.appendLine("  B 有而 A 没有：${added.joinToString(", ")}")
            }
        }

        val aSystem = systemText(aMessages)
        val bSystem = systemText(bMessages)
        when {
            aSystem == null || bSystem == null -> sb.appendLine("系统提示词：本次对比有一侧没有 SYSTEM 消息")
            aSystem == bSystem -> sb.appendLine("系统提示词：完全一致（${aSystem.length} 字符）")
            else -> {
                val systemLcp = commonPrefixLength(aSystem, bSystem)
                val anchor = systemAnchor(aSystem, systemLcp)
                sb.appendLine("系统提示词：在第 $systemLcp 字符处分叉" + (anchor?.let { "，$it" } ?: ""))
            }
        }

        sb.appendLine(
            if (prefixIdentical) "分叉前是否完全一致：true（公共前缀即较短的那份全长，仅长度不同）"
            else "分叉前是否完全一致：false（上面列出的位置就是第一处真实差异）"
        )
        return sb.toString().trimEnd()
    }

    // ---------------------------------------------------------------- 内部实现

    /** 读取快照文件为 JsonObject，失败返回 null */
    private fun readDocument(file: File): JsonObject? = runCatching {
        json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject
    }.getOrNull()

    /** 客户端专有字段：不会出现在上游请求体里，参与对比只会制造假分叉 */
    private val CLIENT_ONLY_KEYS = setOf(
        "id", "createdAt", "finishedAt", "modelId", "modelSnapshot",
        "usage", "lastUsage", "generationTimeMs", "stepCount",
        "translation", "mvuData", "isContextCheckpoint", "isSynthetic",
        "annotations",
    )

    /** 递归剥离客户端专有字段，只留下真正会发给上游的内容 */
    private fun stripClientOnly(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .filter { it.key !in CLIENT_ONLY_KEYS }
                .associate { (key, value) -> key to stripClientOnly(value) }
        )

        is JsonArray -> JsonArray(element.map { stripClientOnly(it) })
        else -> element
    }

    /** 剥离客户端字段后的消息数组，用于逐条对比 */
    private fun wireMessages(doc: JsonObject): JsonArray {
        val raw = doc["messages"] as? JsonArray ?: JsonArray(emptyList())
        return JsonArray(raw.map { stripClientOnly(it) })
    }

    /**
     * 参与对比的稳定文本：只取请求体真正会带上的 tools 与 messages。
     *
     * meta 整体排除——里面的时间、token 估算、headers 每份都不同，放进来会让
     * 「最长公共前缀」永远停在开头几十个字符，读数毫无意义。
     */
    private fun stableText(doc: JsonObject): String {
        val stable = buildJsonObject {
            doc["tools"]?.let { put("tools", stripClientOnly(it)) }
            put("messages", wireMessages(doc))
        }
        return canonical(stable)
    }

    /** 递归规范化：对象键排序、去掉所有空白，避免键序/空白造成假分叉 */
    private fun canonical(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries
            .sortedBy { it.key }
            .joinToString(",", "{", "}") { (key, value) -> "\"$key\":${canonical(value)}" }

        is JsonArray -> element.joinToString(",", "[", "]") { canonical(it) }
        else -> element.toString()
    }

    /** 最长公共前缀长度（字符数） */
    private fun commonPrefixLength(a: String, b: String): Int {
        val limit = min(a.length, b.length)
        var i = 0
        while (i < limit && a[i] == b[i]) i++
        return i
    }

    /** 数组逐条对比，返回第一个不同元素的下标；完全一致返回 -1，仅长度不同返回较短长度 */
    private fun firstDiffIndex(a: JsonArray, b: JsonArray): Int {
        val limit = min(a.size, b.size)
        for (i in 0 until limit) {
            if (canonical(a[i]) != canonical(b[i])) return i
        }
        return if (a.size == b.size) -1 else limit
    }

    /** 单条消息的摘要：id / role / parts 类型 */
    private fun describeMessage(element: JsonElement?): String {
        val obj = element as? JsonObject ?: return "（该侧已不存在这条消息）"
        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: "-"
        val role = (obj["role"] as? JsonPrimitive)?.contentOrNull ?: "-"
        val parts = (obj["parts"] as? JsonArray)?.mapNotNull { part ->
            val partObj = part as? JsonObject ?: return@mapNotNull null
            (partObj["type"] as? JsonPrimitive)?.contentOrNull ?: partObj.keys.firstOrNull()
        }?.joinToString("+").orEmpty()
        return "id=$id role=$role parts=${parts.ifBlank { "-" }}"
    }

    /** 工具名 */
    private fun toolName(element: JsonElement?): String? =
        ((element as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull

    /** 取出第一条 SYSTEM 消息的纯文本，没有则返回 null */
    private fun systemText(messages: JsonArray): String? {
        for (message in messages) {
            val obj = message as? JsonObject ?: continue
            val role = (obj["role"] as? JsonPrimitive)?.contentOrNull ?: continue
            if (!role.equals("system", ignoreCase = true)) continue
            val parts = obj["parts"] as? JsonArray ?: return ""
            return parts.joinToString(NEWLINE) { part ->
                val partObj = part as? JsonObject
                (partObj?.get("text") as? JsonPrimitive)?.contentOrNull ?: ""
            }
        }
        return null
    }

    /** 在系统提示词里定位分叉点落在哪一段 */
    private fun systemAnchor(text: String, offset: Int): String? {
        var bestLabel: String? = null
        var bestOffset = -1
        for ((label, keywords) in SYSTEM_ANCHORS) {
            for (keyword in keywords) {
                var index = text.indexOf(keyword, ignoreCase = true)
                while (index >= 0) {
                    if (index <= offset && index > bestOffset) {
                        bestOffset = index
                        bestLabel = label
                    }
                    index = text.indexOf(keyword, index + 1, ignoreCase = true)
                }
            }
        }
        val label = bestLabel ?: return null
        return "落在「$label」段（段首在全文 $bestOffset 字符处，分叉点距段首 ${offset - bestOffset} 字符）"
    }

    /** 粗略 token 估算：CJK 按 1.6 字符/token，其余按 4 字符/token */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (isCjk(ch)) cjk++ else other++
        }
        return ceil(cjk / 1.6 + other / 4.0).toInt()
    }

    /** 是否 CJK 及全角字符（中文、日文、韩文、全角标点） */
    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF ||
            code in 0x3400..0x4DBF ||
            code in 0x3040..0x30FF ||
            code in 0xAC00..0xD7AF ||
            code in 0x3000..0x303F ||
            code in 0xFF00..0xFFEF
    }

    /** 时间戳文本 */
    private fun stamp(millis: Long): String =
        SimpleDateFormat(STAMP_PATTERN, Locale.US).format(Date(millis))

    /** ISO8601 时间，写进 meta 供人工辨认 */
    private fun isoTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(millis))

    /** 会话短 id：文件名末尾用它分组，裁剪时无需解析文件内容 */
    private fun shortId(conversationId: String?): String {
        val normalized = conversationId?.replace("-", "").orEmpty()
        return if (normalized.isBlank()) "nocid" else normalized.take(8)
    }

    /** 文件名里的会话分组键 */
    private fun conversationKey(fileName: String): String =
        fileName.removeSuffix(".json").substringAfterLast('-')

    /**
     * 保留策略：同会话最多 [MAX_PER_CONVERSATION] 份，全局最多 [MAX_FILES] 份。
     * 只依据文件名排序，不解析内容。
     */
    private fun prune(context: Context) {
        val all = files(context)
        if (all.isEmpty()) return
        val seen = HashMap<String, Int>()
        for (file in all) {
            val key = conversationKey(file.name)
            val count = (seen[key] ?: 0) + 1
            seen[key] = count
            if (count > MAX_PER_CONVERSATION) runCatching { file.delete() }
        }
        files(context).drop(MAX_FILES).forEach { runCatching { it.delete() } }
    }
}
