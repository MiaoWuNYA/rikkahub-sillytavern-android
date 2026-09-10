package me.rerere.rikkahub.plugin.di

import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.plugin.provider.PluginToolProvider
import me.rerere.rikkahub.plugin.repository.PluginRepository
import me.rerere.rikkahub.plugin.scanner.PluginScanner
import me.rerere.rikkahub.plugin.ui.PluginViewModel
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * 插件模块依赖注入
 */
val pluginModule = module {
    // Scanner
    single { PluginScanner(androidContext()) }

    // Repository
    single { PluginRepository(androidContext()) }

    // Loader - 需要 OkHttpClient 和 SettingsStore（用于解析 model 类型配置）
    single { PluginLoader(androidContext(), get<OkHttpClient>(), get<SettingsStore>()) }

    // Manager
    single { PluginManager(androidContext(), get(), get(), get()) }

    // Provider - 需要 PluginManager 来确保插件已初始化
    single { PluginToolProvider(get(), get()) }

    // ViewModel
    viewModel { PluginViewModel(get()) }
}
