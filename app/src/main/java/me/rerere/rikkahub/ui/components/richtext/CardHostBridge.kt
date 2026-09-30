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
    private val host: CardHostContext,
) {
    private val onGenerate = host.generate
    private val onWriteMessage = host.writeMessage
    private val onToast = host.toast
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

    /**
     * 卡调用 `setChatMessages(messages, options)` 把开局正文写回第 0 条消息。
     *
     * 这是开出开局后**必须**走通的一步：卡里写着
     * `if (typeof setChatMessages !== 'function') throw new Error('当前宿主没有 setChatMessages，无法写入第 0 条消息')`，
     * 缺了它，生成再成功也落不了地。
     *
     * 返回约定与 `generate` 一致：立刻返回请求 id，
     * JS 侧用同一个 [poll] 轮询结果（成功时正文为空串）。
     * 卡里是 `await setChatMessages(...)`，await 一个 Promise 即可。
     */
    @JavascriptInterface
    fun setChatMessages(payloadJson: String): String {
        val id = "msg-" + idSeed.incrementAndGet()
        val deferred = CompletableDeferred<String>()
        pending[id] = deferred

        val parsed = runCatching { JSONObject(payloadJson) }.getOrNull()
        val messageId = parsed?.optInt("message_id", 0) ?: 0
        val text = parsed?.optString("message").orEmpty()
        // 卡隐藏卡面时调 setChatMessages([{message_id:0, is_hidden:true}], {refresh:'none'})，
        // 这条**没有** message 字段。把它当空正文拒掉的话，卡面就永远留在消息里。
        val hiddenOnly = parsed?.optBoolean("is_hidden", false) == true && text.isBlank()

        if (text.isBlank() && !hiddenOnly) {
            pending.remove(id)
            deferred.completeExceptionally(IllegalArgumentException("开场白正文为空"))
            return id
        }

        scope.launch(Dispatchers.IO) {
            try {
                if (!hiddenOnly) onWriteMessage(messageId, text)
                deferred.complete("")
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
            }
        }
        return id
    }

    // ---- MVU 变量 ----
    //
    // 酒馆前端卡的整套状态（角色属性、世界状态、剧情线进度）都存在 MVU 变量树里，
    // 卡通过 `Mvu.getMvuData({type:'message', message_id:N})` 读、
    // `Mvu.replaceMvuData(data, option)` 整体写回。
    //
    // 初值来自卡内世界书那条 `[initvar]变量初始化勿开`；卡在确认开局时会读出来，
    // 按 allowed_json_patch_paths 打补丁，再整棵写回。所以宿主只需保存字符串。

    /** 当前消息的变量树（已含 `stat_data` 包装）。volatile：JS 线程直接读。 */
    @Volatile
    private var mvuData: String = ""

    /** 变量树的 JSON 文本；没有变量时返回空串，卡会走软降级分支。 */
    @JavascriptInterface
    fun getMvuData(): String = mvuData

    /**
     * 卡调用 `Mvu.replaceMvuData(data, option)` 整体写回变量树。
     *
     * 同步返回：卡里是 `await Mvu.replaceMvuData(...)`，但它紧接着就往下走，
     * 不依赖返回值内容，所以这里存完即可，不必再绕一轮轮询。
     */
    @JavascriptInterface
    fun replaceMvuData(dataJson: String) {
        if (dataJson.isBlank()) return
        mvuData = dataJson
        onMvuChanged?.invoke(dataJson)
    }

    /** 变量树变更回调，供宿主持久化。 */
    var onMvuChanged: ((String) -> Unit)? = null

    /** 宿主注入初始变量树（来自世界书 initvar）。 */
    fun seedMvuData(json: String?) {
        if (!json.isNullOrBlank()) mvuData = json
    }

    /**
     * 卡调用 `setChatMessage(text, messageId, options)` 切换开局分支。
     *
     * 这是卡里「命牌问卜」按钮的全部实现 —— 它把 alternate greeting 当作
     * 官方 swipe：
     *   const msgs = await getChatMessages(0, {include_swipe:true})
     *   await setChatMessage(msgs[0].swipes[1], 0, {swipe_id:1, ...})
     *
     * 返回请求 id + 轮询，与 [setChatMessages] 一致（卡是 await 它）。
     */
    @JavascriptInterface
    fun setChatMessage(payloadJson: String): String {
        val id = "swipe-" + idSeed.incrementAndGet()
        val deferred = CompletableDeferred<String>()
        pending[id] = deferred

        val parsed = runCatching { JSONObject(payloadJson) }.getOrNull()
        val messageId = parsed?.optInt("message_id", 0) ?: 0
        val swipeId = parsed?.optInt("swipe_id", 0) ?: 0

        scope.launch(Dispatchers.IO) {
            try {
                host.switchSwipe(messageId, swipeId)
                deferred.complete("")
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
            }
        }
        return id
    }

    /**
     * 卡调用 `getChatMessages(begin, options)` 读消息，取 `msgs[0].swipes`。
     *
     * 同步返回 JSON 字符串数组；读不到时返回 `[]`，
     * 卡里会走它自己的 `alert('没有检测到第二开局…')` 分支。
     */
    @JavascriptInterface
    fun getChatMessages(): String = chatMessagesJson

    /** 宿主在加载卡片前把消息快照交给桥。 */
    fun seedChatMessages(json: String) {
        chatMessagesJson = json
    }

    @Volatile
    private var chatMessagesJson: String = "[]"

    fun dispose() {
        pending.values.forEach { it.cancel() }
        pending.clear()
        stream = null
    }
}

/**
 * 前端卡在宿主侧需要的全部能力打包。
 *
 * 之前每个能力都是一个独立回调参数，从 ChatList 一路透传到 WebView，
 * 每加一个宿主 API 就要改 5 个文件的签名。卡需要的宿主面只会越来越多
 * （generate / setChatMessages / MVU / swipe / 变量），打包成一个上下文
 * 可以让渲染层保持稳定，新增能力不影响调用链。
 */
data class CardHostContext(
    /** 把卡拼好的 prompt 交给模型；onDelta 收累积全文，返回最终文本。 */
    val generate: suspend (prompt: String, onDelta: (String) -> Unit) -> String,
    /** 把正文写回指定序号的消息。 */
    val writeMessage: suspend (nodeIndex: Int, text: String) -> Unit = { _, _ -> },
    /** 切换到指定消息的第 N 条开局（官方 swipe）。 */
    val switchSwipe: suspend (nodeIndex: Int, swipeId: Int) -> Unit = { _, _ -> },
    /** 卡片变量树初值（世界书 `[initvar]` 的 YAML 原文）。 */
    val initVars: List<String> = emptyList(),
    /** 替换 `{{user}}` 的键名。 */
    val userName: String = "user",
    /** 透传卡的 toastr 提示。 */
    val toast: (message: String, warning: Boolean) -> Unit = { _, _ -> },
)
