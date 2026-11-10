package me.rerere.rikkahub.data.st.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Message
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-completion wrapper around [ValueCallback] so WebView never crashes with
 * `IllegalStateException: Duplicate showFileChooser result` and never leaks an uncompleted callback.
 */
internal class SingleShotValueCallback<T>(
    private val delegate: ValueCallback<T>,
) : ValueCallback<T> {
    private val completed = AtomicBoolean(false)

    override fun onReceiveValue(value: T?) {
        if (completed.compareAndSet(false, true)) {
            runCatching { delegate.onReceiveValue(value) }
        }
    }
}

/**
 * 统一 WebView Chrome 客户端（主运行时 WebView、悬浮窗 Stage 与消息内嵌 HTML 面板共用）：
 * 1. 处理 `<input type="file">`（如小手机自定义头像上传、壁纸上传、配置 JSON 导入），通过 [SingleShotValueCallback] 保证单次完成；
 * 2. 处理 `alert()` / `confirm()` / `prompt()`（如小手机昵称修改、重置确认弹窗），防止静默失败、挂起或重复确认；
 * 3. 受控处理 `window.open` / `target="_blank"` 二级页面跳转，显式拦截 `javascript:` / `file:` / `content:` 等高危 scheme；
 * 4. 采集控制台错误至 `TavernRuntimeManager` 诊断日志。
 */
class RikkaWebChromeClient(
    private val context: Context,
    private val sourceTag: String = "webview",
    private val onConsoleError: ((String) -> Unit)? = null,
) : WebChromeClient() {

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?,
    ): Boolean {
        if (filePathCallback == null) return false
        val safeCallback = SingleShotValueCallback(filePathCallback)
        val acceptedTypes = fileChooserParams?.acceptTypes
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.ifEmpty { listOf("*/*") }
            ?: listOf("*/*")
        val handled = TavernRuntimeManager.requestWebViewFileChooser(
            callback = safeCallback,
            mimeTypes = acceptedTypes.toTypedArray(),
            allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE,
        )
        if (!handled) {
            safeCallback.onReceiveValue(null)
        }
        return true
    }

    override fun onJsAlert(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?,
    ): Boolean {
        val msg = message.orEmpty()
        TavernRuntimeManager.appendLog("info", "[$sourceTag] js-alert: $msg")
        val handled = TavernRuntimeManager.dispatchWebViewJsAlert(msg, result)
        if (!handled) {
            runCatching { result?.confirm() }
        }
        return true
    }

    override fun onJsConfirm(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?,
    ): Boolean {
        val msg = message.orEmpty()
        TavernRuntimeManager.appendLog("info", "[$sourceTag] js-confirm: $msg")
        val handled = TavernRuntimeManager.dispatchWebViewJsConfirm(msg, result)
        if (!handled) {
            runCatching { result?.confirm() }
        }
        return true
    }

    override fun onJsPrompt(
        view: WebView?,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult?,
    ): Boolean {
        val msg = message.orEmpty()
        val def = defaultValue.orEmpty()
        TavernRuntimeManager.appendLog("info", "[$sourceTag] js-prompt: $msg (default=$def)")
        val handled = TavernRuntimeManager.dispatchWebViewJsPrompt(msg, def, result)
        if (!handled) {
            runCatching { result?.confirm(def) }
        }
        return true
    }

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?,
    ): Boolean {
        val parentWebView = view ?: return false
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
        val bridgeView = WebView(parentWebView.context)
        bridgeView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                subView: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val targetUrl = request?.url?.toString()?.trim().orEmpty()
                if (targetUrl.isNotEmpty()) {
                    handleTargetUrl(parentWebView, targetUrl)
                }
                runCatching { subView?.destroy() }
                return true
            }
        }
        transport.webView = bridgeView
        resultMsg.sendToTarget()
        return true
    }

    private fun handleTargetUrl(parentWebView: WebView, targetUrl: String) {
        if (targetUrl.startsWith("https://appassets.androidplatform.net", ignoreCase = true) ||
            targetUrl.equals("about:blank", ignoreCase = true)
        ) {
            parentWebView.loadUrl(targetUrl)
            return
        }
        if (targetUrl.startsWith("http://", ignoreCase = true) ||
            targetUrl.startsWith("https://", ignoreCase = true)
        ) {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            return
        }
        TavernRuntimeManager.appendLog("warn", "[$sourceTag] blocked disallowed popup scheme: $targetUrl")
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        val cm = consoleMessage ?: return super.onConsoleMessage(null)
        val level = cm.messageLevel()
        if (level == ConsoleMessage.MessageLevel.ERROR) {
            val text = "[console:$sourceTag] ${cm.message()} (${cm.sourceId()}:${cm.lineNumber()})"
            TavernRuntimeManager.appendLog("error", text)
            onConsoleError?.invoke(cm.message().orEmpty())
        }
        return super.onConsoleMessage(cm)
    }
}
