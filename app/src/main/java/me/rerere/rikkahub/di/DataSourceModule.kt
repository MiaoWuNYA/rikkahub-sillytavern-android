package me.rerere.rikkahub.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpHeaders
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderManager
import me.rerere.common.http.AcceptLanguageBuilder
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.AIRequestInterceptor
import me.rerere.rikkahub.data.ai.RequestLoggingInterceptor
import me.rerere.rikkahub.data.ai.transformers.AssistantTemplateLoader
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.TranslationHandler
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.api.RikkaHubAPI
import me.rerere.rikkahub.data.api.SponsorAPI
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.BackupManager
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.AppDatabase
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.SimpleDictManager
import me.rerere.rikkahub.data.db.migrations.Migration_6_7
import me.rerere.rikkahub.data.db.migrations.Migration_11_12
import me.rerere.rikkahub.data.db.migrations.Migration_13_14
import me.rerere.rikkahub.data.db.migrations.Migration_14_15
import me.rerere.rikkahub.data.db.migrations.Migration_15_16
import me.rerere.rikkahub.data.db.migrations.Migration_20_21
import me.rerere.rikkahub.data.db.migrations.Migration_21_22
import me.rerere.rikkahub.data.db.migrations.Migration_22_23
import me.rerere.rikkahub.data.db.migrations.Migration_23_24
import me.rerere.rikkahub.data.db.migrations.Migration_24_25
import me.rerere.rikkahub.data.db.migrations.Migration_25_26
import me.rerere.rikkahub.data.db.migrations.Migration_26_27
import me.rerere.rikkahub.data.db.migrations.Migration_27_28
import me.rerere.rikkahub.data.db.migrations.Migration_28_29
import me.rerere.rikkahub.data.db.migrations.Migration_29_30
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.network.SettingsProxySelector
import me.rerere.rikkahub.data.network.SettingsProxyAuthenticator
import me.rerere.rikkahub.data.network.SettingsSocks5Authenticator
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.search.SearchService
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.WorkspaceBindMount
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

val dataSourceModule = module {
    single {
        SettingsStore(context = get(), scope = get())
    }

    single {
        val context: Context = get()
        Room.databaseBuilder(context, AppDatabase::class.java, "rikka_hub")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(Migration_6_7, Migration_11_12, Migration_13_14, Migration_14_15, Migration_15_16, Migration_20_21, Migration_21_22, Migration_22_23, Migration_23_24, Migration_24_25, Migration_25_26, Migration_26_27, Migration_27_28, Migration_28_29, Migration_29_30)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    val dictDir = SimpleDictManager.extractDict(context)
                    val cursor = db.query("SELECT jieba_dict(?)", arrayOf(dictDir.absolutePath))
                    cursor.use {
                        if (it.moveToFirst()) {
                            val result = it.getString(0)
                            val success = result?.trimEnd('/') == dictDir.absolutePath.trimEnd('/')
                            if (!success) {
                                android.util.Log.e(
                                    "DataSourceModule",
                                    "jieba_dict failed: $result, path=${dictDir.absolutePath}"
                                )
                            }
                        }
                    }
                    db.execSQL(
                        """
                        CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
                            text,
                            node_id UNINDEXED,
                            message_id UNINDEXED,
                            conversation_id UNINDEXED,
                            title UNINDEXED,
                            update_at UNINDEXED,
                            tokenize = 'simple'
                        )
                        """.trimIndent()
                    )
                }
            })
            .openHelperFactory(
                RequerySQLiteOpenHelperFactory(
                    listOf(
                RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
                    options.customExtensions.add(
                        SQLiteCustomExtension(
                            context.applicationInfo.nativeLibraryDir + "/libsimple",
                            null
                        )
                    )
                    options
                }
            )))
            .build()
    }

    single {
        AssistantTemplateLoader(settingsStore = get())
    }

    single {
        PebbleEngine.Builder()
            .loader(get<AssistantTemplateLoader>())
            .defaultLocale(Locale.getDefault())
            .autoEscaping(false)
            .build()
    }

    single { TemplateTransformer(engine = get(), settingsStore = get()) }

    single {
        get<AppDatabase>().conversationDao()
    }

    single {
        get<AppDatabase>().memoryDao()
    }

    single {
        get<AppDatabase>().genMediaDao()
    }

    single {
        get<AppDatabase>().messageNodeDao()
    }

    single {
        get<AppDatabase>().managedFileDao()
    }

    single {
        get<AppDatabase>().favoriteDao()
    }

    single {
        get<AppDatabase>().workspaceDao()
    }

    single {
        get<AppDatabase>().folderDao()
    }

    single {
        get<AppDatabase>().coupleDao()
    }

    single {
        val ctx: android.content.Context = get()
        me.rerere.workspace.WorkspaceManager(
            baseDir = java.io.File(ctx.filesDir, "workspaces"),
            shellRunner = ProotShellRunner(
                nativeLibraryDir = java.io.File(ctx.applicationInfo.nativeLibraryDir),
            ),
            bindMounts = listOf(
                WorkspaceBindMount(
                    source = java.io.File(ctx.filesDir, FileFolders.SKILLS).apply { mkdirs() },
                    target = "/skills",
                ),
                WorkspaceBindMount(
                    source = java.io.File(ctx.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
                    target = "/tool_outputs",
                ),
                WorkspaceBindMount(
                    source = java.io.File(ctx.filesDir, FileFolders.UPLOAD).apply { mkdirs() },
                    target = "/upload",
                ),
            ),
        )
    }

    single {
        me.rerere.workspace.RootfsInstaller(
            manager = get(),
        )
    }

    single {
        me.rerere.rikkahub.data.repository.WorkspaceRepository(
            dao = get(),
            manager = get(),
            rootfsInstaller = get(),
            settingsStore = get(),
        )
    }

    single {
        me.rerere.rikkahub.data.repository.FolderRepository(get(), get())
    }

    single {
        MessageFtsManager(get())
    }

    single { McpManager(settingsStore = get(), appScope = get(), filesManager = get()) }

    single {
        me.rerere.rikkahub.data.memory.MemoryEmbeddingService(
            repository = get(),
            providerManager = get(),
        )
    }

    single {
        me.rerere.rikkahub.data.ai.transformers.MemoryRetrievalTransformer(
            repository = get(),
            providerManager = get(),
        )
    }

    single {
        me.rerere.rikkahub.data.memory.CrossWindowMemoryStore(get())
    }

    single {
        me.rerere.rikkahub.data.ai.transformers.CrossWindowMemoryTransformer(
            store = get(),
        )
    }

    single {
        GenerationLoop(
            context = get(),
            providerManager = get(),
            json = get(),
            memoryRepo = get(),
            conversationRepo = get(),
            memoryEmbeddingService = get(),
        )
    }

    single {
        TranslationHandler(providerManager = get())
    }

    single<OkHttpClient> {
        val settingsStore: SettingsStore = get()
        val acceptLang = AcceptLanguageBuilder.fromAndroid(get())
            .build()
        java.net.Authenticator.setDefault(SettingsSocks5Authenticator(settingsStore))
        val initialNetworkSetting = settingsStore.settingsFlow.value.networkSetting
        val appliedProxySetting = AtomicReference(
            Triple(
                initialNetworkSetting.proxyUrl,
                initialNetworkSetting.proxyUsername,
                initialNetworkSetting.proxyPassword,
            )
        )
        lateinit var client: OkHttpClient
        client = OkHttpClient.Builder()
            .proxySelector(SettingsProxySelector(settingsStore))
            .proxyAuthenticator(SettingsProxyAuthenticator(settingsStore))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS) // HTTP/2 PING 保活长连接事件流（移植自 Rikkahub-Revised）
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val networkSetting = settingsStore.settingsFlow.value.networkSetting
                val currentProxySetting = Triple(
                    networkSetting.proxyUrl,
                    networkSetting.proxyUsername,
                    networkSetting.proxyPassword,
                )
                if (appliedProxySetting.getAndSet(currentProxySetting) != currentProxySetting) {
                    client.connectionPool.evictAll()
                }

                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()
                    .addHeader(HttpHeaders.AcceptLanguage, acceptLang)

                if (originalRequest.header(HttpHeaders.UserAgent) == null) {
                    val userAgent = settingsStore.settingsFlow.value.networkSetting.userAgent
                        .trim()
                        .ifEmpty { "RikkaHub-Android/${BuildConfig.VERSION_NAME}" }
                    requestBuilder.addHeader(HttpHeaders.UserAgent, userAgent)
                }

                // SSE 事件流：禁用缓存与压缩，避免代理缓冲破坏流式输出（移植自 Rikkahub-Revised）
                if (originalRequest.header(HttpHeaders.Accept)
                        ?.contains("text/event-stream", ignoreCase = true) == true
                ) {
                    requestBuilder
                        .header(HttpHeaders.CacheControl, "no-cache")
                        .header(HttpHeaders.AcceptEncoding, "identity")
                }

                chain.proceed(requestBuilder.build())
            }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val contentTypeHeader = request.header("Content-Type")
                if (
                    contentTypeHeader != null &&
                    contentTypeHeader.contains(";") &&
                    contentTypeHeader.substringBefore(";").trim().equals("application/json", ignoreCase = true)
                ) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Content-Type", contentTypeHeader.substringBefore(";").trim())
                            .build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
            .addNetworkInterceptor(RequestLoggingInterceptor())
            .addInterceptor(AIRequestInterceptor())
            .addInterceptor(HttpLoggingInterceptor().apply {
                redactHeader("Proxy-Authorization")
                level = HttpLoggingInterceptor.Level.HEADERS
            })
            .build()
        client.also { SearchService.init(it, get()) }
    }

    single {
        SponsorAPI.create(get())
    }

    single {
        ProviderManager(client = get(), context = get())
    }

    single { BackupManager(context = get(), database = get(), settingsStore = get(), json = get()) }

    single {
        WebDavSync(
            backupManager = get(),
            context = get(),
            httpClient = get()
        )
    }

    single<HttpClient> {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(20, TimeUnit.SECONDS)
                    readTimeout(10, TimeUnit.MINUTES)
                    writeTimeout(120, TimeUnit.SECONDS)
                    followSslRedirects(true)
                    followRedirects(true)
                    retryOnConnectionFailure(true)
                }
            }
        }
    }

    single {
        S3Sync(
            backupManager = get(),
            context = get(),
            httpClient = get()
        )
    }

    single<Retrofit> {
        Retrofit.Builder()
            .baseUrl("https://api.rikka-ai.com")
            .addConverterFactory(get<Json>().asConverterFactory("application/json; charset=UTF8".toMediaType()))
            .build()
    }

    single<RikkaHubAPI> {
        get<Retrofit>().create(RikkaHubAPI::class.java)
    }
}
