package me.rerere.rikkahub.plugin.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 插件声明的供应商。
 *
 * 插件启用时它会被写进「供应商」设置（用户可正常选模型使用），插件停用或卸载时连同
 * 模型一起移除；它带自己的请求头与代理，不碰用户的任何其他供应商。
 */
@Serializable
data class PluginProviderSpec(
    /** 插件内唯一标识，与插件 ID 一起派生稳定的供应商/模型 UUID */
    val id: String,
    val name: String,
    val icon: String = "",
    val baseUrl: String,
    val apiKey: String = "",
    val chatCompletionsPath: String = "/chat/completions",
    val useResponseApi: Boolean = false,
    val responsesPath: String = "/responses",
    val balanceApiPath: String = "",
    val balanceResultPath: String = "",
    /** 附加请求头，只发给这个供应商 */
    val headers: List<PluginHeaderSpec> = emptyList(),
    /** 单个代理，等价于只含一项的 [proxies] */
    val proxy: PluginProxySpec? = null,
    /** 代理池，按请求轮换；只用于这个供应商 */
    val proxies: List<PluginProxySpec> = emptyList(),
    val models: List<PluginModelSpec> = emptyList(),
) {
    /** 去重后的代理列表。 */
    fun proxyList(): List<PluginProxySpec> =
        (proxies + listOfNotNull(proxy)).filter { it.url.isNotBlank() }.distinctBy { it.key() }
}

@Serializable
data class PluginHeaderSpec(
    val name: String,
    val value: String,
)

/** 代理配置：url 形如 http://host:port、socks5://host:port，可带认证。 */
@Serializable
data class PluginProxySpec(
    val url: String,
    val username: String = "",
    val password: String = "",
) {
    fun key(): String = "$url|$username|$password"
}

@Serializable
data class PluginModelSpec(
    val modelId: String,
    val displayName: String = "",
    val contextLength: Int? = null,
    val contextWindowTokens: Int? = null,
    /** "tool" / "reasoning" */
    val abilities: List<String> = emptyList(),
    /** "text" / "image" */
    val inputModalities: List<String> = emptyList(),
    val outputModalities: List<String> = emptyList(),
    /** 固定写进请求体的字段（同名键覆盖原值），用于上游要求的协议形状 */
    val customBodies: Map<String, JsonElement> = emptyMap(),
)
