package me.rerere.ai.provider

import android.content.Context
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import okhttp3.OkHttpClient

/**
 * 宿主为特定供应商提供专用实例的钩子（例如插件供应商要带自己的代理）。
 * 返回 null 表示交给默认实现。
 */
fun interface ProviderResolver {
    fun resolve(setting: ProviderSetting): Provider<*>?
}

/**
 * Provider管理器，负责注册和获取Provider实例
 */
class ProviderManager(client: OkHttpClient, context: Context) {
    // 存储已注册的Provider实例
    private val providers = mutableMapOf<String, Provider<*>>()

    /**
     * 宿主为特定供应商提供专用实例的钩子（插件供应商要带自己的代理），
     * 返回 null 表示交给默认实现。由宿主在启动装配时注入。
     */
    @Volatile
    var resolver: ProviderResolver? = null

    init {
        // 注册默认Provider
        registerProvider("openai", OpenAIProvider(client, context))
        registerProvider("google", GoogleProvider(client, context))
        registerProvider("claude", ClaudeProvider(client, context))
    }

    /**
     * 注册Provider实例
     *
     * @param name Provider名称
     * @param provider Provider实例
     */
    fun registerProvider(name: String, provider: Provider<*>) {
        providers[name] = provider
    }

    /**
     * 获取Provider实例
     *
     * @param name Provider名称
     * @return Provider实例，如果不存在则返回null
     */
    fun getProvider(name: String): Provider<*> {
        return providers[name] ?: throw IllegalArgumentException("Provider not found: $name")
    }

    /**
     * 根据ProviderSetting获取对应的Provider实例
     *
     * @param setting Provider设置
     * @return Provider实例，如果不存在则返回null
     */
    fun <T : ProviderSetting> getProviderByType(setting: T): Provider<T> {
        resolver?.resolve(setting)?.let { resolved ->
            @Suppress("UNCHECKED_CAST")
            return resolved as Provider<T>
        }
        @Suppress("UNCHECKED_CAST")
        return when (setting) {
            is ProviderSetting.OpenAI -> getProvider("openai")
            is ProviderSetting.Google -> getProvider("google")
            is ProviderSetting.Claude -> getProvider("claude")
        } as Provider<T>
    }
}
