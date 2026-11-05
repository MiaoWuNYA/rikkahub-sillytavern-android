package me.rerere.rikkahub.tavern

import me.rerere.rikkahub.tavern.api.TavernApi
import me.rerere.rikkahub.tavern.api.TavernReply
import me.rerere.rikkahub.tavern.api.TavernRequest

/** 内置酒馆前端的请求分发：先问 API，再落到 assets/tavern 的静态文件。 */
class TavernRouter(
    private val assetHandler: TavernAssetHandler,
    private val apis: List<TavernApi>,
) {

    suspend fun dispatch(method: String, path: String, query: Map<String, String>, body: ByteArray?): TavernReply {
        val clean = path.trim('/')
        val request = TavernRequest(method = method.uppercase(), path = clean, query = query, body = body)

        for (api in apis) {
            val reply = api.handle(request)
            if (reply != null) return reply
        }

        if (clean.startsWith("api/")) return TavernReply.Text(404, "unknown api: " + clean)

        val response = assetHandler.handle(clean.ifEmpty { "index.html" })
        val bytes = response.bytes ?: return TavernReply.Text(response.status, "missing asset: " + clean)
        return TavernReply.Binary(response.status, response.contentType, bytes)
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val DEFAULT_PORT = 8181
        fun url(port: Int = DEFAULT_PORT): String = "http://" + HOST + ":" + port + "/index.html"
    }
}
