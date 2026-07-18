package me.rerere.rikkahub.data.ai.python

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import org.koin.java.KoinJavaComponent

/**
 * 轻量 JS 引擎桥接 — 供 Python 路由调用。
 * 
 * 不引用 Context 等复杂 Android 类型，避免 Chaquopy 17 代理 Java 对象时的
 * Path 类加载问题（见 d688bb4ae / 8f2c81034）。
 * 所有依赖通过 Koin 静态获取，不需要构造参数。
 */
class JsBridge {

    fun evalJavascript(library: String, code: String): String = runBlocking {
        try {
            val localTools = KoinJavaComponent.get<me.rerere.rikkahub.data.ai.tools.LocalTools>(
                me.rerere.rikkahub.data.ai.tools.LocalTools::class.java
            )
            val tool = localTools.javascriptTool
            val actualAction = if (code.isEmpty()) "load" else "eval"
            val args = buildJsonObject {
                put("action", JsonPrimitive(actualAction))
                put("library", JsonPrimitive(library))
                put("code", JsonPrimitive(code))
            }
            val parts = tool.execute(args)
            parts.joinToString("\n") { (it as? UIMessagePart.Text)?.text ?: it.toString() }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
