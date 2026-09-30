package me.rerere.rikkahub.ui.components.richtext

import android.webkit.JavascriptInterface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 前端角色卡的宿主桥。
 *
 * 酒馆的前端卡（一张 PNG 里塞完整互动网页）会调用一组宿主 API 来干活，
 * 最常见的是 `window.generate(request)` —— 卡自己拼好 prompt，交给宿主
 * 送给模型，拿回纯文本再渲染。宿主要是没注入，卡就报
 * 「宿主未注入 generate 接口，无法生成」，整个开局面板直接不可用。
 *
 * 这里实现的是这类卡真正跑起来所需的最小完整集：
 *
 * - `generate(request)` → Promise&lt;string&gt;：一次性生成，结果不写入聊天；
 * - `eventOn(topic, cb)` + `window.iframe_events`：流式增量，回调收到累积全文；
 * - `toastr.error/warning`：提示，透传到应用内提示组件。
 *
 * 设计要点：JS 与 Kotlin 的边界只传字符串。JS 侧把 Promise 的 resolve/reject
 * 存进一张表，调用时把「请求 id + 参数 JSON」交给 Kotlin；Kotlin 干完活再调
 * `rikkaHost.resolve(id, text)` 把结果送回去。这样 JS 侧拿到的是标准 Promise，
 * 卡里 `g.then(done, fail)` 那套写法不用改一个字。
 */
class CardHostBridge(
    private val scope: CoroutineScope,
    /** 把一段 prompt 交给模型生成，流式回调累积全文；返回最终文本，失败抛异常 */
    private val onGenerate: suspend (prompt: String, onDelta: (String) -> Unit) -> String,
    /** 透传卡的 toastr 提示；simple=true 表示警告级 */
    private val onToast: (message: String, warning: Boolean) -> Unit = { _, _ -> },
) {
    /** 未完成请求：id → 等待结果。JS 侧的 Promise 与之对应。 */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    /** 当前正在流式推送的请求 id。卡用 generationId 过滤，这里同一时刻只允许一个。 */
    @Volatile
    private var streamingId: String? = null

    private val idSeed = AtomicLong(0)

    /**
     * 卡调用 `window.generate(request)`。
     *
     * 入参是 JSON 字符串（JS 侧 `JSON.stringify` 过），字段沿用官方约定：
     * `user_input` 是要送的 prompt，`generation_id` 供流式事件做来源过滤。
     *
     * 返回请求 id，JS 侧据此挂起 Promise。
     */
    @JavascriptInterface
    fun generate(requestJson: String): String {
        val id = "gen-" + idSeed.incrementAndGet()
        val request = runCatching { JSONObject(requestJson) }.getOrNull()

        // user_input 是官方字段；个别卡会写成 prompt，两个都认。
        val prompt = request?.optString("user_input")?.takeIf { it.isNotBlank() }
            ?: request?.optString("prompt").orEmpty()
        // 卡自己生成的 generation_id，用于流式事件里过滤掉别的生成
        val genId = request?.optString("generation_id")?.takeIf { it.isNotBlank() } ?: id

        val deferred = CompletableDeferred<String>()
        pending[id] = deferred

        if (prompt.isBlank()) {
            // 空 prompt 直接失败，别让卡一直转圈等一个永远不会来的结果
            pending.remove(id)
            return "-"
        }

        scope.launch(Dispatchers.IO) {
            try {
                val text = onGenerate(prompt) { full ->
                    // 流式回调带着 generationId 一起送回去，
                    // 卡的监听里有 `generationId !== activeGenerationId` 过滤，必须给对。
                    evaluateStream(genId, full)
                }
                deferred.complete(text)
            } catch (t: Throwable) {
                // 卡只认 Error.message，这里把异常收敛成可读文案
                deferred.completeExceptionally(t)
            }
        }
        return id
    }

    /**
     * JS 侧轮询用：取走已完成的结果。
     *
     * 之所以用轮询而不是 Kotlin 主动回调 JS，是因为 `addJavascriptInterface`
     * 只支持 JS→Kotlin 单向调用；Kotlin 要回传结果得靠 `evaluateJavascript`。
     * 但那必须在 WebView 所在线程执行、还要拿到 WebView 引用，
     * 轮询写法更简单也更不容易踩线程问题。
     *
     * 返回：空串=还在跑；`"ok:\u0000<文本>"`=成功；`"err:\u0000<消息>"`=失败。
     * 用 \u0000 做分隔符，避免正文里出现的冒号把结果切坏。
     */
    @JavascriptInterface
    fun poll(id: String): String {
        val d = pending[id] ?: return "err:\u0000请求不存在"
        if (!d.isCompleted) return ""
        pending.remove(id)
        // 必须区分成功与失败：失败时 getCompleted() 会抛异常，
        // 直接调用会让 JS 侧永远拿不到 err 分支，卡就卡在转圈上。
        val failure = d.getCompletionExceptionOrNull()
        return if (failure != null) {
            "err:\u0000${failure.message ?: failure::class.java.simpleName}"
        } else {
            "ok:\u0000${d.getCompleted()}"
        }
    }

    /**
     * 取流式进度：`{"generation_id":"...","text":"..."}`。
     *
     * 一行里同时给出 id 与文本，避免 JS 分两次读时拿到错配的一对
     * （第一次读到新 id、第二次读到旧文本，卡会把别的生成内容显示出来）。
     * 没有正在进行的生成时返回空串。
     */
    @JavascriptInterface
    fun pollStream(): String {
        val s = stream ?: return ""
        return "{\"generation_id\":${JSONObject.quote(s.first)},\"text\":${JSONObject.quote(s.second)}}"
    }

    /** 当前流式全文；用 volatile 保证 JS 线程能立刻看到更新。 */
    @Volatile
    private var stream: Pair<String, String>? = null

    private fun evaluateStream(generationId: String, full: String) {
        stream = generationId to full
    }

    /** 卡里的 toastr 提示。 */
    @JavascriptInterface
    fun toast(message: String, warning: Boolean) {
        onToast(message, warning)
    }

    fun dispose() {
        pending.values.forEach { it.cancel() }
        pending.clear()
        stream = null
    }
}
