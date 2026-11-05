package me.rerere.rikkahub.tavern.api

/** 一条来自内置酒馆前端的请求。path 已去掉查询串，去掉前导 "/"。 */
data class TavernRequest(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
) {
    /** 路径分段，例如 "api/chats/get" -> ["api","chats","get"]。 */
    val segments: List<String> get() = path.split('/').filter { it.isNotEmpty() }

    fun bodyText(): String = body?.toString(Charsets.UTF_8).orEmpty()

    fun queryValue(key: String): String? = query[key]

    /** 从 query 或 JSON body 里取一个整数字段，酒馆前端两种都会用。 */
    fun intArg(key: String): Int? = query[key]?.toIntOrNull()

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** 回给前端的一份响应。 */
sealed interface TavernReply {
    data class Json(val status: Int = 200, val body: String) : TavernReply {
        constructor(body: String) : this(200, body)
    }

    data class Binary(
        val status: Int = 200,
        val contentType: String = "application/octet-stream",
        val bytes: ByteArray,
    ) : TavernReply

    data class Text(val status: Int = 200, val body: String) : TavernReply {
        constructor(body: String) : this(200, body)
    }

    data object Empty : TavernReply

    companion object {
        fun ok(body: String) = Json(200, body)
        fun okJson(body: String) = Json(200, body)
        fun notFound() = Text(404, "not found")
        fun badRequest(reason: String) = Text(400, reason)
        fun error(reason: String) = Text(500, reason)
    }
}

/** 酒馆 API 的一个实现片段；返回 null 表示"这条路径不归我管"。 */
interface TavernApi {
    suspend fun handle(request: TavernRequest): TavernReply?
}
