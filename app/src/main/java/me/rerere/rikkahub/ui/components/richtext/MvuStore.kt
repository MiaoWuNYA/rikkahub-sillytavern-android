package me.rerere.rikkahub.ui.components.richtext

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.yaml.snakeyaml.Yaml

/**
 * MVU 变量存储 —— 酒馆前端卡（尤其带世界书初始化变量的卡）读写状态的地方。
 *
 * 前端卡靠 `Mvu.getMvuData({type:'message', message_id:N})` / `Mvu.replaceMvuData(...)`
 * 存取这一条消息关联的变量树，形如：
 *
 * ```
 * { "stat_data": { "林子熙": { "identity": {...}, "opening": "lin_zixi", ... },
 *                  "world": {...}, "sisters": {...} } }
 * ```
 *
 * 卡在确认开局时会把整棵 `stat_data` 读出来、按允许路径打 JSONPatch、
 * 再整棵写回。所以这里不做细粒度更新，只保证「读出来的东西原样改完能写回去」。
 *
 * 变量初值来自卡内世界书里那条 `[initvar]变量初始化勿开` 的 YAML —— 它是
 * 官方 MVU 的 `initvar` 约定，`{{user}}` 占位符在初始化时替换成实际用户键名。
 *
 * 用 kotlinx.serialization 而不是 org.json：后者在 JVM 单元测试里是 android.jar
 * 的桩实现，`JSONObject.put(String, Object)` 会对普通 Map 抛 NPE，导致这条链路
 * 在真机上能跑、在测试里永远测不到。变量树是卡的核心状态，不能留这种测试盲区。
 */
object MvuStore {

    /** 变量树的根包装。官方把 `stat_data` 作为唯一顶层字段。 */
    private const val STAT_DATA = "stat_data"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * 把 `[initvar]` 世界书条目的 YAML 解析成 `stat_data`。
     *
     * 解析失败返回 null —— 卡里对 MVU 缺失是软降级（只写正文、跳过变量），
     * 宁可让它降级，也不要塞一棵残缺的树进去把 `findUserKey` 带偏。
     *
     * @param userName 替换 `{{user}}` 的键名；官方用实际用户角色名。
     */
    fun parseInitVar(yamlText: String, userName: String = "user"): JsonObject? {
        val text = yamlText.trim()
        if (text.isEmpty()) return null
        val root = try {
            Yaml().load<Any?>(resolveUserPlaceholder(text, userName))
        } catch (_: Throwable) {
            return null
        } as? Map<*, *> ?: return null
        return try {
            toJson(root) as? JsonObject
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * `{{user}}` 是官方 MVU 的用户占位符，出现在**键名**里（`{{user}}:`）。
     *
     * 因为它在键里，没法靠 YAML 解析器处理，只能在解析前做文本替换。
     * 同时也兼容它出现在值里（提示词模板常见）。
     */
    private fun resolveUserPlaceholder(text: String, userName: String): String =
        text.replace("{{user}}", userName)

    /** YAML 的 Map/List 递归转成 JSON 结构，供 JS 侧直接 JSON.parse。 */
    private fun toJson(node: Any?): JsonElement = when (node) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(
            node.entries.associate { (k, v) -> k.toString() to toJson(v) }
        )
        is List<*> -> JsonArray(node.map { toJson(it) })
        is Boolean -> JsonPrimitive(node)
        is Number -> JsonPrimitive(node)
        else -> JsonPrimitive(node.toString())
    }

    /**
     * 组装成卡要的完整结构：`{ stat_data: {...} }`。
     *
     * 传 null 表示还没有变量 —— 返回 null，让卡走它自己的
     * 「未检测到可用 MVU，已只写入开场白正文」软降级分支。
     */
    fun wrap(statData: JsonObject?): JsonObject? {
        if (statData == null) return null
        return JsonObject(mapOf(STAT_DATA to statData))
    }

    /**
     * 从卡写回的整棵树里取出 `stat_data`。
     *
     * 结构不对返回 null —— 卡会把它当成「没有 MVU」继续走软降级，
     * 而不是拿到半棵坏树把 `findUserKey` 带偏。
     */
    fun unwrap(dataJson: String?): JsonObject? {
        if (dataJson.isNullOrBlank()) return null
        val obj = try {
            json.parseToJsonElement(dataJson) as? JsonObject
        } catch (_: Throwable) {
            return null
        } ?: return null
        return obj[STAT_DATA] as? JsonObject
    }

    /** 序列化成卡能 `JSON.parse` 的字符串；失败返回 null。 */
    fun encode(element: JsonElement?): String? {
        if (element == null) return null
        return try {
            json.encodeToString(JsonElement.serializer(), element)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 从世界书条目里找出官方的变量初始化条目。
     *
     * 官方 MVU 约定：条目名以 `[initvar]` 开头，内容是一段 YAML。
     * 卡作者通常还会在后面写「勿开」提醒别手动启用它 —— 它是模板，不是提示词。
     *
     * @param entries 世界书条目的 (名称, 内容) 序列
     * @return 解析好的 `stat_data`；没有该条目或解析失败返回 null
     */
    fun fromLorebook(
        entries: Sequence<Pair<String, String>>,
        userName: String = "user",
    ): JsonObject? {
        val entry = entries.firstOrNull { (name, content) ->
            name.contains(INIT_VAR_MARKER) && content.isNotBlank()
        } ?: return null
        return parseInitVar(entry.second, userName)
    }

    /** 官方约定：变量初始化条目名以 `[initvar]` 开头（大小写不敏感）。 */
    private const val INIT_VAR_MARKER = "[initvar]"
}
