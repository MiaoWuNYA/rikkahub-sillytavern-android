package me.rerere.rikkahub.plugin.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.plugin.data.PluginDataStore
import me.rerere.rikkahub.plugin.manager.PluginManager
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.File

/** 插件小程序：纯前端页面，通过 rikkaData 桥读写插件自己的数据 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PluginMiniAppPage(
    pluginId: String,
    miniAppId: String,
    onNavigateBack: () -> Unit,
) {
    val pluginManager: PluginManager = koinInject()
    val plugins by pluginManager.plugins.collectAsStateWithLifecycle()
    val plugin = plugins.find { it.manifest.id == pluginId && it.isEnabled }
    val miniApp = plugin?.manifest?.miniApps?.find { it.id == miniAppId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(miniApp?.name ?: plugin?.manifest?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.plugin_action_back))
                    }
                },
            )
        }
    ) { padding ->
        if (plugin == null || miniApp == null) {
            Text(text = stringResource(R.string.plugin_detail_not_found), modifier = Modifier.padding(padding))
            return@Scaffold
        }
        val entryFile = File(plugin.directory, miniApp.entry)
        if (!entryFile.isFile) {
            Text(text = "缺少页面文件: ${miniApp.entry}", modifier = Modifier.padding(padding))
            return@Scaffold
        }
        val bridge = PluginMiniAppBridge(PluginDataStore(androidx.compose.ui.platform.LocalContext.current, pluginId))
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowFileAccessFromFileURLs = true
                    addJavascriptInterface(bridge, "rikkaData")
                    loadUrl(entryFile.toURI().toString())
                }
            },
        )
    }
}

/** 暴露给小程序页面的数据桥：与插件 index.js 共用同一份 dataStore */
class PluginMiniAppBridge(private val store: PluginDataStore) {
    @JavascriptInterface
    fun get(key: String): String? = store.getData(key)

    @JavascriptInterface
    fun set(key: String, value: String) {
        store.setData(key, value)
    }

    @JavascriptInterface
    fun remove(key: String) {
        store.deleteData(key)
    }

    @JavascriptInterface
    fun keys(): String = org.json.JSONArray(store.listData()).toString()

    @JavascriptInterface
    fun all(): String {
        val obj = org.json.JSONObject()
        store.listData().forEach { key -> obj.put(key, store.getData(key) ?: "") }
        return obj.toString()
    }
}
