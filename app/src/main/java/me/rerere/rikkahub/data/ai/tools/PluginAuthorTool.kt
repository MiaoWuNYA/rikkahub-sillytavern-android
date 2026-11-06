package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Icon
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder
import coil3.toBitmap
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import me.rerere.rikkahub.EXTRA_MINI_APP_ID
import me.rerere.rikkahub.EXTRA_MINI_APP_PLUGIN_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.ACTION_MINI_APP
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
fun buildPluginAuthorTool(context: Context, pluginManager: PluginManager): Tool = Tool(
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
                            listOf("guide", "list", "read", "create", "write", "delete", "export", "import", "enable", "reload", "shortcut", "unshortcut")
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
                    put("description", "图标：create 用 emoji；shortcut 还可写 url:https://…/a.png 或 file:icons/a.png（插件目录内相对路径），支持 png/jpg/webp/svg，不带前缀时按内容自动判断")
                })
                put("iconBg", buildJsonObject {
                    put("type", "string")
                    put("description", "shortcut 图标底色 #RRGGBB，不传为透明")
                })
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("preview"); add("pin") })
                    put("description", "shortcut 用：preview 只渲染图标给你看（推荐先做这步），pin 才真正请求加到桌面")
                })
                put("miniApp", buildJsonObject {
                    put("type", "string")
                    put("description", "action=shortcut/unshortcut 的插件小程序 id（manifest miniApps[].id）")
                })
                put("label", buildJsonObject {
                    put("type", "string")
                    put("description", "action=shortcut 的桌面图标名称，缺省用小程序名")
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
        var previewIcon: File? = null
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
            "shortcut" -> runCatching {
                val pluginId = obj.requireStr("id")
                val miniAppId = obj.requireStr("miniApp")
                val mode = obj.str("mode") ?: "pin"
                val (message, iconFile) = pinMiniAppShortcut(
                    context = context,
                    pluginManager = pluginManager,
                    pluginId = pluginId,
                    miniAppId = miniAppId,
                    labelOverride = obj.str("label"),
                    iconOverride = obj.str("icon"),
                    iconBg = obj.str("iconBg"),
                    mode = mode,
                )
                previewIcon = iconFile
                buildJsonObject {
                    put("ok", true)
                    put("mode", mode)
                    put("message", message)
                    if (mode == "preview") {
                        put("next", "确认图标没错再调 action=shortcut&mode=pin")
                    } else {
                        put("hint", "请在系统弹窗里确认添加到桌面")
                    }
                }.toString()
            }.fold({ it }, { errorText(it) })
            "unshortcut" -> runCatching {
                removeMiniAppShortcut(context, obj.requireStr("id"), obj.requireStr("miniApp"))
                buildJsonObject { put("ok", true) }.toString()
            }.fold({ it }, { errorText(it) })
            else -> errorText(IllegalArgumentException("unknown action: $action"))
        }
        val image = previewIcon
        if (image != null) {
            listOf(UIMessagePart.Image(url = image.absolutePath), UIMessagePart.Text(text))
        } else {
            listOf(UIMessagePart.Text(text))
        }
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

// 图标先落成 PNG 让模型自己看一眼：emoji 缺字形会渲染成豆腐块，必须由模型把关
private suspend fun pinMiniAppShortcut(
    context: Context,
    pluginManager: PluginManager,
    pluginId: String,
    miniAppId: String,
    labelOverride: String?,
    iconOverride: String?,
    iconBg: String?,
    mode: String,
): Pair<String, File?> {
    pluginManager.refreshPlugins()
    val info = pluginManager.plugins.value
        .find { it.manifest.id == pluginId && it.isEnabled } ?: error("插件不存在或未启用: $pluginId")
    val miniApp = info.manifest.miniApps.find { it.id == miniAppId } ?: error("插件「$pluginId」没有小程序「$miniAppId」")
    val label = labelOverride?.takeIf { it.isNotBlank() } ?: miniApp.name.ifBlank { info.manifest.name }
    val icon = renderShortcutIcon(context, info.directory, iconOverride ?: miniApp.icon, iconBg)
    if (mode == "preview") {
        return "图标已渲染，先看这张图：emoji 渲染成方块说明系统没这个字形，换一个或改用 url:/file: 图片" to icon.bitmapFile
    }
    val sm = context.getSystemService(Context.SHORTCUT_SERVICE) as ShortcutManager
    val shortcut = ShortcutInfo.Builder(context, "miniapp_${pluginId}_$miniAppId")
        .setShortLabel(label)
        .setLongLabel(label)
        .setIcon(Icon.createWithBitmap(icon.bitmap))
        .setIntent(Intent(Intent.ACTION_VIEW).apply {
            component = android.content.ComponentName(context, RouteActivity::class.java)
            action = ACTION_MINI_APP
            putExtra(EXTRA_MINI_APP_PLUGIN_ID, pluginId)
            putExtra(EXTRA_MINI_APP_ID, miniAppId)
        })
        .build()
    if (!sm.isRequestPinShortcutSupported) error("启动器不支持创建桌面快捷图标")
    sm.requestPinShortcut(shortcut, null)
    return "已请求添加到桌面" to icon.bitmapFile
}

private class ShortcutIcon(val bitmap: Bitmap, val bitmapFile: File?)

private suspend fun renderShortcutIcon(
    context: Context,
    pluginDir: File,
    spec: String?,
    bg: String?,
): ShortcutIcon {
    val source = spec?.trim().orEmpty()
    val bitmap = when {
        source.isBlank() -> null
        source.startsWith("url:") -> loadIconImage(context, source.removePrefix("url:"))
        source.startsWith("file:") -> loadIconImage(context, pluginFile(pluginDir, source.removePrefix("file:")).toURI().toString())
        source.contains("://") -> loadIconImage(context, source)
        source.length <= 4 -> drawEmoji(source)
        else -> runCatching { loadIconImage(context, pluginFile(pluginDir, source).toURI().toString()) }.getOrNull()
            ?: drawEmoji(source)
    }
    val finalBitmap = bitmap?.let { applyBackground(it, bg) }
    if (finalBitmap == null) {
        val fallback = androidx.core.content.ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
        val drawn = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        fallback?.setBounds(0, 0, 512, 512)
        fallback?.draw(Canvas(drawn))
        val file = File(context.cacheDir, "shortcut_icon_preview.png")
        file.outputStream().use { drawn.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return ShortcutIcon(drawn, file)
    }
    val file = File(context.cacheDir, "shortcut_icon_preview.png")
    file.outputStream().use { finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return ShortcutIcon(finalBitmap, file)
}

private fun pluginFile(pluginDir: File, relative: String): File {
    val base = pluginDir.canonicalFile
    val file = File(base, relative).canonicalFile
    require(file.path.startsWith(base.path + File.separator)) { "图标必须放在插件目录内: $relative" }
    return file
}

private suspend fun loadIconImage(context: Context, model: String): Bitmap = withContext(Dispatchers.IO) {
    val loader = ImageLoader.Builder(context).components { add(SvgDecoder.Factory()) }.build()
    val request = ImageRequest.Builder(context).data(model).size(512).build()
    val result = loader.execute(request)
    result.image?.toBitmap() ?: error("图标读不出来: $model")
}

private fun drawEmoji(emoji: String): Bitmap {
    val size = 512
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size * 0.72f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    Canvas(bitmap).drawText(emoji.take(2), size / 2f, size * 0.75f, paint)
    return bitmap
}

private fun applyBackground(source: Bitmap, bg: String?): Bitmap {
    val color = bg?.trim()?.takeIf { it.isNotBlank() }?.let {
        runCatching { Color.parseColor(if (it.startsWith("#")) it else "#$it") }.getOrNull()
    } ?: return if (source.width >= 192) source else scaleIcon(source)
    val size = 512
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(color)
    val scaled = scaleIcon(source, (size * 0.78f).toInt())
    val left = (size - scaled.width) / 2f
    val top = (size - scaled.height) / 2f
    canvas.drawBitmap(scaled, left, top, Paint(Paint.FILTER_BITMAP_FLAG))
    return out
}

private fun scaleIcon(source: Bitmap, target: Int = 512): Bitmap {
    if (source.width == target && source.height == target) return source
    val ratio = target.toFloat() / maxOf(source.width, source.height).coerceAtLeast(1)
    return Bitmap.createScaledBitmap(
        source,
        (source.width * ratio).toInt().coerceAtLeast(1),
        (source.height * ratio).toInt().coerceAtLeast(1),
        true,
    )
}
private fun removeMiniAppShortcut(context: Context, pluginId: String, miniAppId: String) {
    val sm = context.getSystemService(Context.SHORTCUT_SERVICE) as ShortcutManager
    sm.disableShortcuts(listOf("miniapp_${pluginId}_$miniAppId"), "插件小程序已删除")
}

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
- settings：插件声明要写进应用设置的值（主题、displaySetting、huadengSettings、providers 等，路径同 settings_control 工具）。适合“美化主题包”。
  注意：settings 补丁**在应用重启后的首次启动才应用**，导入或启用后当前会话不会立刻变化，这是刻意的（避免热改设置把界面弄坏）；删除插件后同样在下次启动回滚。
- injections：按世界书方式插入提示词，启用后每次请求生效；position 取 AFTER_SYSTEM_PROMPT / BEFORE_PROMPT / IN_CHAT。
- regexes：作用于消息的正则替换，promptOnly=true 只改发给模型的内容。
- messageActions：在对话末尾渲染按钮，action 支持 regenerate（重新生成本条）与 slash（发送斜杠命令，payload 为命令文本）。
- miniApps：侧边栏入口 + 纯 WebView 页面（html/css/js 三件套），插件停用或删除后入口消失。
  页面里用 `window.rikkaData.get(key)` / `set(key, value)` / `remove(key)` / `keys()` / `all()` 读写与 index.js 的 dataStore 同一份数据；
  页面不需要也不会加载聊天，可轮询 setInterval 显示实时数据（如今日摄入）。

生成流程：create → write manifest.json → write index.js → reload → list 确认 tools 数量与加载错误。
- shortcut：给某个 miniApp 建桌面快捷图标（id=插件id, miniApp=小程序 id, label=图标名）。
  icon 支持三种：emoji；file:icons/a.png（插件目录内相对路径）；url:https://…/a.png。png/jpg/webp/svg 都行。iconBg=#RRGGBB 可加底色。
  **先 mode=preview 再 mode=pin**：preview 只把渲染好的图标作为图片返回给你看，不建快捷方式。
  看一眼那张图——emoji 变成方块/豆腐块就是系统缺字形，必须换一个 emoji 或改用图片文件；确认没问题再 mode=pin 真正钉到桌面。
  unshortcut 用 id+miniApp 移除。
""".trimIndent()
}