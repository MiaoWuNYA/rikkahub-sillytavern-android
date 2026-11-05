package me.rerere.rikkahub.ui.pages.chat

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.tavern.TavernAssetHandler
import me.rerere.rikkahub.tavern.TavernAssets
import me.rerere.rikkahub.tavern.TavernRouter
import me.rerere.rikkahub.tavern.TavernServer
import me.rerere.rikkahub.tavern.api.TavernDataApi
import me.rerere.rikkahub.tavern.api.TavernGenerateApi
import me.rerere.rikkahub.tavern.api.TavernMiscApi
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * 原生酒馆模式：消息区交给内置的 SillyTavern 前端，生成与数据请求回落到 App。
 *
 * 页面走 127.0.0.1 上的本地服务（WebView 的请求拦截读不到 POST body），
 * 服务与页面生命周期同进同出，离开页面即关，避免常驻端口。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TavernSurface(
    assistantName: String,
    appSettings: Settings,
    model: Model?,
    conversationId: Uuid,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository: ConversationRepository = koinInject()
    val providerManager: ProviderManager = koinInject()
    val settingsState by rememberUpdatedState(appSettings)
    val modelState by rememberUpdatedState(model)

    val generate: suspend (List<UIMessage>, Model) -> String = { messages, target ->
        val provider = target.findProvider(settingsState.providers)
            ?: error("模型 " + target.modelId + " 没有对应供应商配置")
        val handler = providerManager.getProviderByType(provider)
        val result = handler.generateText(
            providerSetting = provider,
            messages = messages,
            params = TextGenerationParams(
                model = target,
                customHeaders = target.customHeaders,
                customBody = target.customBodies,
                sessionId = conversationId.toString(),
            ),
        )
        result.message.toText()
    }

    val router = remember(conversationId) {
        val assets = TavernAssets(context.assets)
        TavernRouter(
            assetHandler = TavernAssetHandler(assets),
            apis = listOf(
                TavernDataApi({ settingsState }, repository, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)),
                TavernMiscApi(assets),
                TavernGenerateApi(
                    settingsProvider = { settingsState },
                    generate = generate,
                    resolveModel = { latest -> modelState ?: latest.findModelById(latest.chatModelId) },
                ),
            ),
        )
    }
    val server = remember(router) { TavernServer(router) }
    val url = remember(router) {
        runCatching { TavernRouter.url(server.start()) }.getOrDefault("")
    }
    DisposableEffect(server) {
        onDispose { server.stop() }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mediaPlaybackRequiresUserGesture = false
                settings.textZoom = 100
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                if (url.isNotEmpty()) loadUrl(url)
            }
        },
        update = { view ->
            if (url.isNotEmpty() && view.url.isNullOrEmpty()) view.loadUrl(url)
        },
    )
}
