package me.rerere.rikkahub.tavern

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import me.rerere.rikkahub.tavern.api.TavernReply
import java.net.URLDecoder

/**
 * 酒馆前端专用的本地 HTTP 服务。
 *
 * 不用 WebView 的 shouldInterceptRequest：它读不到 POST body，而 ST 的角色、对话、
 * 生成全部走 POST。这里挂在 127.0.0.1 上，路径保持 ST 原样（/scripts/... /api/...）。
 */
class TavernServer(private val router: TavernRouter) {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start(port: Int = TavernRouter.DEFAULT_PORT): Int {
        stop()
        val engine = embeddedServer(CIO, port = port, host = TavernRouter.HOST) {
            tavernModule(router)
        }
        engine.start(wait = false)
        server = engine
        return port
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
        server = null
    }

    val running: Boolean get() = server != null
}

private fun Application.tavernModule(router: TavernRouter) {
    routing {
        get("/{path...}") { respond(router, call) }
        post("/{path...}") { respond(router, call) }
    }
}

private suspend fun respond(router: TavernRouter, call: ApplicationCall) {
    val path = call.parameters.getAll("path")?.joinToString("/").orEmpty()
    val method: HttpMethod = call.request.httpMethod
    val query = parseQuery(call.request.uri)
    val bodyText = if (method == HttpMethod.Post || method == HttpMethod.Put) {
        runCatching { call.receiveText() }.getOrDefault("")
    } else {
        ""
    }
    val reply = router.dispatch(method.value, path, query, bodyText.takeIf { it.isNotEmpty() }?.toByteArray())
    val status = HttpStatusCode.fromValue(replyStatus(reply))

    when (reply) {
        is TavernReply.Binary -> call.respondBytes(reply.bytes, ContentType.parse(reply.contentType), status)
        is TavernReply.Json -> call.respondText(reply.body, ContentType.Application.Json, status)
        is TavernReply.Text -> call.respondText(reply.body, ContentType.Text.Plain, status)
        TavernReply.Empty -> call.respondText("", ContentType.Text.Plain, HttpStatusCode.NoContent)
    }
}

private fun replyStatus(reply: TavernReply): Int = when (reply) {
    is TavernReply.Binary -> reply.status
    is TavernReply.Json -> reply.status
    is TavernReply.Text -> reply.status
    TavernReply.Empty -> 204
}

private fun parseQuery(uri: String): Map<String, String> {
    val raw = uri.substringAfter('?', "")
    if (raw.isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (pair in raw.split('&')) {
        if (pair.isEmpty()) continue
        val key = decode(pair.substringBefore('='))
        val value = decode(pair.substringAfter('=', ""))
        out[key] = value
    }
    return out
}

private fun decode(raw: String): String = try {
    URLDecoder.decode(raw, "UTF-8")
} catch (_: Exception) {
    raw
}
