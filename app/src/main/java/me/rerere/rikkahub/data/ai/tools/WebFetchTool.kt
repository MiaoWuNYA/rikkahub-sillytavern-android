package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 通用 HTTP 工具，支持所有 HTTP 方法 + body + 自定义 Header。
 *
 * GET 请求直接返回纯文本，POST/PUT/PATCH 可带 JSON body。
 * 用于调 REST API、抓网页等一切 HTTP 交互。
 */
fun createWebFetchTool(): Tool = Tool(
    name = "web_fetch",
    description = "Send HTTP requests to any URL (call REST APIs, submit data, fetch pages).\n" +
        "Methods: GET, POST, PUT, PATCH, DELETE (default GET). Large responses are truncated.\n" +
        "url: full URL incl. https://; body: JSON string (POST/PUT/PATCH); headers: JSON object;\n" +
        "content_type: Content-Type (default: application/json for POST/PUT/PATCH).",
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "Full URL (e.g., https://api.example.com/data)")
                })
                put("method", buildJsonObject {
                    put("type", "string")
                    put("description", "HTTP method: GET, POST, PUT, PATCH, DELETE (default: GET)")
                })
                put("body", buildJsonObject {
                    put("type", "string")
                    put("description", "JSON body for POST/PUT/PATCH (e.g., {\"year\":1990,\"month\":1,\"day\":1})")
                })
                put("headers", buildJsonObject {
                    put("type", "string")
                    put("description", "JSON object of headers, e.g. {\"Authorization\":\"Bearer xxx\",\"X-API-Key\":\"yyy\"}")
                })
                put("content_type", buildJsonObject {
                    put("type", "string")
                    put("description", "Content-Type header (default: application/json for POST/PUT/PATCH)")
                })
            },
            required = listOf("url"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val urlStr = obj["url"]?.jsonPrimitive?.contentOrNull
            ?: error("url parameter is required")
        val method = obj["method"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "GET"
        val body = obj["body"]?.jsonPrimitive?.contentOrNull
        val headersJson = obj["headers"]?.jsonPrimitive?.contentOrNull
        val contentType = obj["content_type"]?.jsonPrimitive?.contentOrNull
            ?: if (body != null) "application/json" else null

        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.requestMethod = method
        conn.setRequestProperty("User-Agent", "Rikkahub/1.0")

        // Parse and set custom headers
        if (headersJson != null) {
            try {
                val parsed = kotlinx.serialization.json.Json.parseToJsonElement(headersJson).jsonObject
                parsed.forEach { (key, value) ->
                    conn.setRequestProperty(key, value.jsonPrimitive.contentOrNull ?: "")
                }
            } catch (_: Exception) { /* invalid headers json, ignore */ }
        }

        if (contentType != null) {
            conn.setRequestProperty("Content-Type", contentType)
        }

        if (body != null && method in listOf("POST", "PUT", "PATCH")) {
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
        }

        val responseCode = conn.responseCode
        val text = if (responseCode in 200..399) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            // 注意优先级：?: 必须作用于 errorStream 整体，否则 errorStream 为 null 时会把字面量 "null" 拼进错误信息
            "HTTP $responseCode: ${conn.responseMessage}\n" +
                (conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "")
        }
        // 20K 字符头尾保留：整页塞给模型 ≈ 上万 token 且历史每轮重复计费，模型可用的部分远小于此
        listOf(UIMessagePart.Text(text.truncateForToolResult()))
    },
)
