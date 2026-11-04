package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.utils.JsonInstant

/**
 * 创造模式：让模型直接创作、修改、导入、导出、删除本应用的插件。
 */
fun buildPluginAuthorTool(pluginManager: PluginManager): Tool = Tool(
    name = "plugin_author",
    description = "创造模式：用户说「创造模式」「帮我写个插件/功能」或要求安装、分享、删除、改造插件时使用。" +
        "先 action=guide 读插件编写规范，再用 create/write/reload 生成插件；也可 list/read/export/import/delete/enable。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            listOf("guide", "list", "read", "create", "write", "delete", "export", "import", "enable", "reload")
                                .forEach { add(JsonPrimitive(it)) }
                        }
                    )
                    put("description", "guide=规范说明; list=已装插件; read=读文件; create=新建; write=写文件; delete=删除; export=打包导出; import=按路径导入; enable=启停; reload=重新扫描")
                })
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "插件 id（反向域名或小写短横线）")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "插件内相对路径，如 manifest.json / index.js")
                })
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "action=write 时写入的完整文件内容")
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "插件显示名")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "插件简述")
                })
                put("icon", buildJsonObject {
                    put("type", "string")
                    put("description", "emoji 图标")
                })
                put("source", buildJsonObject {
                    put("type", "string")
                    put("description", "action=import 的 zip 绝对路径")
                })
                put("enabled", buildJsonObject {
                    put("type", "boolean")
                    put("description", "action=enable 的目标状态")
                })
            },
            required = listOf("action")
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val action = obj.str("action") ?: "guide"
        val text = when (action) {
            "guide" -> PluginAuthorGuide.TEXT
            "list" -> listPlugins(pluginManager)
            "read" -> runCatching {
                val id = obj.requireStr("id")
                val path = obj.str("path") ?: "manifest.json"
                pluginManager.readPluginFile(id, path).getOrThrow()
            }.fold({ it }, { errorText(it) })
            "create" -> runCatching {
                val info = pluginManager.createPlugin(
                    id = obj.requireStr("id"),
                    name = obj.str("name") ?: obj.requireStr("id"),
                    description = obj.str("description") ?: "",
                    icon = obj.str("icon") ?: "🧩",
                ).getOrThrow()
                buildJsonObject {
                    put("ok", true)
                    put("id", info.manifest.id)
                    put("dir", info.directory.absolutePath)
                    put("next", "action=write&id=${info.manifest.id}&path=manifest.json&content=...")
                }.toString()
            }.fold({ it }, { errorText(it) })
            "write" -> runCatching {
                val id = obj.requireStr("id")
                val path = obj.requireStr("path")
                val content = obj.requireStr("content")
                val file = pluginManager.writePluginFile(id, path, content).getOrThrow()
                buildJsonObject {
                    put("ok", true)
                    put("path", file.absolutePath)
                    put("bytes", file.length())
                }.toString()
            }.fold({ it }, { errorText(it) })
            "delete" -> runCatching {
                val id = obj.requireStr("id")
                val ok = pluginManager.deletePlugin(id)
                buildJsonObject {
                    put("ok", ok)
                    put("id", id)
                }.toString()
            }.fold({ it }, { errorText(it) })
            "export" -> runCatching {
                val id = obj.requireStr("id")
                val file = pluginManager.exportPlugin(id).getOrThrow()
                buildJsonObject {
                    put("ok", true)
                    put("zip", file.absolutePath)
                    put("bytes", file.length())
                    put("hint", "可在插件详情页用导出按钮保存到任意目录")
                }.toString()
            }.fold({ it }, { errorText(it) })
            "import" -> runCatching {
                val source = obj.requireStr("source")
                val info = pluginManager.importPluginFromPath(source).getOrThrow()
                buildJsonObject {
                    put("ok", true)
                    put("id", info.manifest.id)
                    put("name", info.manifest.name)
                }.toString()
            }.fold({ it }, { errorText(it) })
            "enable" -> runCatching {
                val id = obj.requireStr("id")
                val enabled = obj["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
                pluginManager.togglePlugin(id, enabled)
                buildJsonObject {
                    put("ok", true)
                    put("id", id)
                    put("enabled", enabled)
                }.toString()
            }.fold({ it }, { errorText(it) })
            "reload" -> runCatching {
                pluginManager.reloadAllPlugins()
                listPlugins(pluginManager)
            }.fold({ it }, { errorText(it) })
            else -> errorText(IllegalArgumentException("unknown action: $action"))
        }
        listOf(UIMessagePart.Text(text))
    }
)

private suspend fun listPlugins(pluginManager: PluginManager): String {
    pluginManager.refreshPlugins()
    val plugins = pluginManager.plugins.value
    return buildJsonObject {
        put("ok", true)
        put("count", plugins.size)
        put("plugins", buildJsonArray {
            plugins.forEach { info ->
                add(buildJsonObject {
                    put("id", info.manifest.id)
                    put("name", info.manifest.name)
                    put("version", info.manifest.version)
                    put("enabled", info.isEnabled)
                    put("tools", info.manifest.tools.size)
                    info.loadError?.let { put("error", it) }
                })
            }
        })
    }.toString()
}

private fun errorText(error: Throwable): String = buildJsonObject {
    put("ok", false)
    put("error", error.message ?: error::class.simpleName ?: "error")
}.toString()

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.requireStr(key: String): String =
    str(key) ?: error("$key required")

internal object PluginAuthorGuide {
    val TEXT: String = """
插件编写规范（本应用的 JS 插件）

目录：/storage/emulated/0/Rikkahub/plugins/<id>/
manifest.json（必须，UTF-8 JSON）：
```json
{
  "id": "com.example.food-log",
  "name": "饮食记录",
  "description": "记录每天的饮食与热量",
  "version": "1.0.0",
  "author": "you",
  "icon": "🍚",
  "entry": "index.js",
  "tools": [
    {"name": "add_meal", "description": "记录一餐", "parameters": [
      {"name": "food", "type": "string", "description": "食物", "required": true},
      {"name": "kcal", "type": "number", "description": "热量", "required": true}
    ]}
  ],
  "config": [
    {"name": "dailyTarget", "type": "number", "label": "每日目标热量", "default": 2000}
  ],
  "allowedHosts": [],
  "detailCard": "renderCard",
  "settings": [{"path": "themeId", "value": "xxx"}],
  "injections": [{"name": "语气", "content": "每句话末尾加喵", "position": "AFTER_SYSTEM_PROMPT"}],
  "regexes": [{"name": "喵化", "findRegex": "。$", "replaceString": "喵。"}],
  "messageActions": [{"label": "再吃一餐", "action": "slash", "payload": "/add_meal"}],
  "miniApps": [{"id": "kcal", "name": "卡路里", "icon": "🔥", "entry": "app.html"}]
}
```

index.js 规则：
- 用 `export function 名称(args) { ... }`，名称必须与 tools[].name 一致；`args` 是已解析的参数对象。
- 返回值会被 JSON 序列化后交给模型，尽量返回精简对象。
- 可用：fetch(url, {method,headers,body})、http.get/postJson/postForm/cookies、dataStore.set/get/del/list(prefix)。dataStore 是插件私有持久存储，适合记账、进度这类跨会话数据。
- 不可用：window、document、localStorage、require、setTimeout。
- 配置项通过 `config.dailyTarget` 读取。
- detailCard 函数返回 {"title": "...", "items":[{"label":"...","value":"..."}]}，显示在插件详情页。

能力边界：
- settings：插件启用时把这些设置路径直接写进应用设置（主题、displaySetting、huadengSettings、providers 等均可，路径同 settings_control 工具）。适合“美化主题包”。
- injections：按世界书方式插入提示词，启用后每次请求生效；position 取 AFTER_SYSTEM_PROMPT / BEFORE_PROMPT / IN_CHAT。
- regexes：作用于消息的正则替换，promptOnly=true 只改发给模型的内容。
- messageActions：在对话末尾渲染按钮，action 支持 regenerate（重新生成本条）与 slash（发送斜杠命令，payload 为命令文本）。
- miniApps：侧边栏入口 + 纯 WebView 页面（html/css/js 三件套），插件停用或删除后入口消失。
  页面里用 `window.rikkaData.get(key)` / `set(key, value)` / `remove(key)` / `keys()` / `all()` 读写与 index.js 的 dataStore 同一份数据；
  页面不需要也不会加载聊天，可轮询 setInterval 显示实时数据（如今日摄入）。

生成流程：create → write manifest.json → write index.js → reload → list 确认 tools 数量与加载错误。
""".trimIndent()
}
