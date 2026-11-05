package me.rerere.rikkahub.tavern.api

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.tavern.TavernAssets

/**
 * 酒馆其余面板（世界书、预设、扩展、头像、用户等）的最小应答。
 *
 * 这些面板打开时前端会先拉数据，拿不到就弹错并卡住面板，所以宁可返回
 * 结构正确的空值：面板能开、能看，写操作等到真正映射时再补持久化。
 */
class TavernMiscApi(private val assets: TavernAssets) : TavernApi {

    override suspend fun handle(request: TavernRequest): TavernReply? {
        val path = request.path
        if (!path.startsWith("api/")) return null
        return when (path) {
            "api/extensions/discover" -> discover()
            "api/users/me" -> TavernReply.Json(
                """{"handle":"user","name":"$USER_NAME","created":0,"admin":true,"password":false,"avatar":"user-default.png"}""",
            )
            "api/users/list" -> TavernReply.Json("""["$USER_NAME"]""")
            "api/extensions/version" -> TavernReply.Json("{}")
            "api/stats/get" -> TavernReply.Json("{}")
            "api/settings/get-boot" -> TavernReply.Json("{}")
            else -> when {
                path.startsWith("api/worldinfo/") -> TavernReply.Json(worldInfoBody(path))
                path.startsWith("api/presets/") -> TavernReply.Json("{}")
                path.startsWith("api/quick-replies") -> TavernReply.Json(if (path.endsWith("all")) "[]" else "{}")
                path.startsWith("api/themes") -> TavernReply.Json("[]")
                path.startsWith("api/backgrounds/") -> TavernReply.Json("[]")
                path.startsWith("api/groups/") -> TavernReply.Json("[]")
                path.startsWith("api/secrets/") -> TavernReply.Json("{}")
                path.startsWith("api/extensions/") -> TavernReply.Json("{}")
                path.startsWith("api/tokenizers") -> TavernReply.Json("[]")
                path.startsWith("api/avatars/") -> TavernReply.Json("{}")
                path.startsWith("api/assets/") -> TavernReply.Json("{}")
                path.startsWith("api/image-metadata/") -> TavernReply.Json("{}")
                path.startsWith("api/images/") -> TavernReply.Json("[]")
                else -> null
            }
        }
    }

    // TauriTavern 的内核要的是数组 [{name:"third-party/X", type:"local"}]，不是上游 ST 的
    // {third-party:[...]}；给错格式它会在 map 上报错，所有第三方扩展静默不加载
    private fun discover(): TavernReply {
        val installed = assets.list("scripts/extensions/third-party").filter { it.isNotBlank() }
        val body = buildJsonArray {
            installed.forEach { dir ->
                add(
                    buildJsonObject {
                        put("name", "third-party/" + dir)
                        put("type", "local")
                    },
                )
            }
        }
        return TavernReply.Json(body.toString())
    }

    private fun worldInfoBody(path: String): String = when {
        path.endsWith("/all") -> "{}"
        path.endsWith("/get") -> buildJsonObject { put("entries", buildJsonArray { }) }.toString()
        else -> "{}"
    }

    private companion object {
        const val USER_NAME = "user"
    }
}
