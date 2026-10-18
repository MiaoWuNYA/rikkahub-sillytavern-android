package me.rerere.rikkahub.plugin.provider

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.provider.BalanceOption
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderResolver
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.pluginOwner
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.remote.toProxyOrNull
import me.rerere.rikkahub.plugin.crypto.PluginCrypto
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.PluginModelSpec
import me.rerere.rikkahub.plugin.model.PluginProviderSpec
import me.rerere.rikkahub.plugin.model.PluginProxySpec
import okhttp3.Authenticator as OkHttpAuthenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/** 解析插件清单里的供应商声明：内联数组、"file:" 明文引用、"enc:" 加密引用。 */
object PluginProviderSpecs {
    private const val TAG = "PluginProviderSpecs"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(plugin: PluginInfo): List<PluginProviderSpec> {
        val raw = plugin.manifest.providers ?: return emptyList()
        val text = when (raw) {
            is JsonArray -> raw.toString()
            is JsonPrimitive -> resolveRef(plugin, raw.contentOrNull ?: return emptyList())
            else -> null
        } ?: return emptyList()

        return runCatching { json.decodeFromString<List<PluginProviderSpec>>(text) }
            .onFailure { Log.w(TAG, "Plugin ${plugin.manifest.id}: 供应商声明解析失败: ${it.message}") }
            .getOrDefault(emptyList())
            .filter { it.id.isNotBlank() && it.baseUrl.isNotBlank() }
            .map { it.withConfig(plugin) }
    }

    private val configRef = Regex("""\$\{config\.([A-Za-z0-9_]+)}""")

    /** 声明里的 ${config.x} 用插件配置值替换，用户改代理、密钥不必改插件。 */
    private fun PluginProviderSpec.withConfig(plugin: PluginInfo): PluginProviderSpec {
        fun sub(value: String): String = if ("${'$'}{config." !in value) value else {
            configRef.replace(value) { match ->
                val element = plugin.getConfigValue(match.groupValues[1])
                (element as? JsonPrimitive)?.contentOrNull ?: ""
            }
        }

        return copy(
            baseUrl = sub(baseUrl),
            apiKey = sub(apiKey),
            chatCompletionsPath = sub(chatCompletionsPath),
            responsesPath = sub(responsesPath),
            balanceApiPath = sub(balanceApiPath),
            balanceResultPath = sub(balanceResultPath),
            headers = headers.map { it.copy(value = sub(it.value)) },
            proxy = proxy?.let { it.copy(url = sub(it.url), username = sub(it.username), password = sub(it.password)) },
            proxies = proxies.map { it.copy(url = sub(it.url), username = sub(it.username), password = sub(it.password)) },
        )
    }

    private fun resolveRef(plugin: PluginInfo, ref: String): String? = runCatching {
        when {
            ref.startsWith("enc:") -> File(plugin.directory, ref.removePrefix("enc:"))
                .takeIf { it.exists() }
                ?.let { PluginCrypto.decryptFile(it) }

            ref.startsWith("file:") -> File(plugin.directory, ref.removePrefix("file:"))
                .takeIf { it.exists() }
                ?.readText(Charsets.UTF_8)

            else -> ref
        }
    }.onFailure {
        Log.e(TAG, "Plugin ${plugin.manifest.id}: 供应商声明读取失败: $ref", it)
    }.getOrNull()
}

/**
 * 插件供应商的落地与隔离。
 *
 * 插件启用时把声明的供应商写进设置（与用户自建供应商并存），停用/卸载时移除；
 * 声明的请求头随供应商本身下发，声明的代理只作用在这个供应商的 OkHttp 客户端上，
 * 用户的其他供应商照旧走全局网络设置。
 */
class PluginProviderRegistry(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val baseClient: OkHttpClient,
) : ProviderResolver {

    companion object {
        private const val TAG = "PluginProviderRegistry"
    }

    private val clients = ConcurrentHashMap<String, OkHttpClient>()
    private val providers = ConcurrentHashMap<String, Provider<*>>()

    @Volatile
    private var proxiesByProvider: Map<Uuid, List<PluginProxySpec>> = emptyMap()

    /** 把当前启用的插件声明的供应商同步进设置；不在声明里的插件供应商会被清掉。 */
    suspend fun sync(plugins: List<PluginInfo>) = withContext(Dispatchers.IO) {
        val declared = plugins.filter { it.isEnabled }.flatMap { info ->
            PluginProviderSpecs.parse(info).map { info to it }
        }
        proxiesByProvider = declared.associate { (info, spec) ->
            providerUuid(info.manifest.id, spec.id) to spec.proxyList()
        }
        val desired = declared.map { (info, spec) -> info.toProviderSetting(spec) }
        settingsStore.update { settings -> settings.withPluginProviders(desired) }
        Log.i(TAG, "sync: ${declared.size} plugin provider(s) from ${plugins.count { it.isEnabled }} plugin(s)")
    }

    override fun resolve(setting: ProviderSetting): Provider<*>? {
        if (setting !is ProviderSetting.OpenAI) return null
        setting.pluginId ?: return null
        val proxies = proxiesByProvider[setting.id]?.takeIf { it.isNotEmpty() } ?: return null
        val key = proxies.joinToString(";") { it.key() }
        val client = clients.getOrPut(key) { buildClient(proxies) }
        return providers.getOrPut(key) { OpenAIProvider(client, context) }
    }

    private fun buildClient(proxies: List<PluginProxySpec>): OkHttpClient = baseClient.newBuilder()
        .proxySelector(PluginProxySelector(proxies))
        .proxyAuthenticator(PluginProxyAuthenticator(proxies))
        .build()
}

/** 按请求轮换代理池。 */
private class PluginProxySelector(private val proxies: List<PluginProxySpec>) : ProxySelector() {
    private val counter = AtomicInteger()

    override fun select(uri: URI): List<Proxy> {
        val spec = proxies[counter.getAndIncrement().mod(proxies.size)]
        return listOf(spec.url.toProxyOrNull() ?: Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
}

/** 插件代理的认证：只认自己那份代理列表里的凭据。 */
private class PluginProxyAuthenticator(private val proxies: List<PluginProxySpec>) : OkHttpAuthenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val address = route?.proxy?.address() as? InetSocketAddress ?: return null
        val spec = proxies.firstOrNull { candidate ->
            candidate.username.isNotEmpty() && candidate.url.toProxyOrNull()?.address() == address
        } ?: return null
        if (response.request.header("Proxy-Authorization") != null) return null
        return response.request.newBuilder()
            .header("Proxy-Authorization", Credentials.basic(spec.username, spec.password, Charsets.UTF_8))
            .build()
    }
}

/** 合并插件供应商：插件自己的条目整条替换，其余供应商原样保留。 */
internal fun Settings.withPluginProviders(desired: List<ProviderSetting>): Settings {
    val desiredById = desired.associateBy { it.id }
    val kept = providers.filter { it.pluginOwner() == null || it.id in desiredById }
    val merged = kept.map { existing ->
        val fresh = desiredById[existing.id] ?: return@map existing
        // 用户手动开关过的状态留着，其余（地址、密钥、请求头、模型）以插件声明为准
        (fresh as ProviderSetting.OpenAI).copy(enabled = existing.enabled)
    }
    val additions = desired.filter { candidate -> merged.none { it.id == candidate.id } }
    return copy(providers = merged + additions)
}

internal fun PluginInfo.toProviderSetting(spec: PluginProviderSpec) = ProviderSetting.OpenAI(
    id = providerUuid(manifest.id, spec.id),
    enabled = true,
    name = spec.name.ifBlank { manifest.name },
    models = spec.models.map { it.toModel(manifest.id, spec.id) },
    balanceOption = BalanceOption(
        enabled = spec.balanceApiPath.isNotBlank(),
        apiPath = spec.balanceApiPath,
        resultPath = spec.balanceResultPath.ifBlank { "data.total_usage" },
    ),
    customHeaders = spec.headers.filter { it.name.isNotBlank() }
        .map { CustomHeader(it.name, it.value) },
    apiKey = spec.apiKey,
    baseUrl = spec.baseUrl.trimEnd('/'),
    chatCompletionsPath = spec.chatCompletionsPath,
    useResponseApi = spec.useResponseApi,
    responsesPath = spec.responsesPath,
    pluginId = manifest.id,
)

internal fun PluginModelSpec.toModel(pluginId: String, specId: String) = Model(
    modelId = modelId,
    displayName = displayName.ifBlank { modelId },
    id = stableUuid("rikkahub.plugin.model", pluginId, specId, modelId),
    inputModalities = inputModalities.mapNotNull { Modality.entries.firstOrNull { m -> m.name.equals(it, true) } }
        .ifEmpty { listOf(Modality.TEXT) },
    outputModalities = outputModalities.mapNotNull { Modality.entries.firstOrNull { m -> m.name.equals(it, true) } }
        .ifEmpty { listOf(Modality.TEXT) },
    abilities = abilities.mapNotNull { ModelAbility.entries.firstOrNull { a -> a.name.equals(it, true) } },
    contextWindowTokens = contextWindowTokens,
    contextLength = contextLength ?: 1_000_000,
    customBodies = customBodies.map { CustomBody(it.key, it.value) },
)

internal fun providerUuid(pluginId: String, specId: String): Uuid =
    stableUuid("rikkahub.plugin.provider", pluginId, specId)

/** 由插件内容派生的稳定 UUID：重复导入、跨版本升级后引用都不会变。 */
internal fun stableUuid(vararg parts: String): Uuid {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8))
    val hex = digest.copyOf(16).joinToString("") { "%02x".format(it) }
    return Uuid.parse(
        "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    )
}
