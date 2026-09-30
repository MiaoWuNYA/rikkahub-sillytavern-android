package me.rerere.rikkahub.ui

import me.rerere.rikkahub.ui.components.richtext.buildCardDocumentPage
import me.rerere.rikkahub.ui.components.richtext.buildFragmentPage
import me.rerere.rikkahub.ui.components.richtext.cardHostShim
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前端卡宿主桥（`window.generate` / `eventOn` / `toastr`）的契约回归测试。
 *
 * 背景：酒馆前端卡（PNG 里塞完整互动网页）靠一组宿主全局 API 干活，
 * 其中 `window.generate` 是硬依赖 —— 缺了卡就直接显示
 * 「宿主未注入 generate 接口，无法生成」，整个开局面板不可用。
 *
 * 这里守住三件容易静默出错的事：
 *
 * 1. 垫片必须真的被注入到卡片页面里，且早于卡片自己的脚本；
 * 2. `poll` 结果的分隔协议必须自洽 —— 类型前缀自带尾冒号（`ok:` / `err:`），
 *    JS 侧比较时若漏掉冒号会永远走 reject 分支，卡会报
 *    「生成失败：<正文>」这种把人带偏的错误；
 * 3. `generate` 必须返回真正的 Promise（卡里写的是 `g.then(done, fail)`），
 *    并且要把 `generation_id` 原样带回流式事件，卡的来源过滤才生效。
 */
class CardHostBridgeTest {

    private val card = """
        <!DOCTYPE html>
        <html>
        <head><style>body { color: #eee; }</style></head>
        <body><div id="opening-app"></div><script>window.x=1</script></body>
        </html>
    """.trimIndent()

    private fun docPage() = buildCardDocumentPage(card, Color.White, Color.Black, cardHostShim())
    private fun fragPage() =
        buildFragmentPage("<div id=\"a\">hi</div>", Color.White, "", Color.Black, cardHostShim())

    // ---- 注入位置 ----

    @Test
    fun `host shim is injected into a full card document`() {
        val html = docPage()
        assertTrue("卡片文档页缺少宿主垫片", html.contains("__RIKKA_HOST_SHIM__"))
        assertTrue("垫片应暴露 window.generate", html.contains("window.generate = function"))
    }

    @Test
    fun `host shim is injected into a fragment page too`() {
        val html = fragPage()
        assertTrue("片段页缺少宿主垫片", html.contains("__RIKKA_HOST_SHIM__"))
    }

    @Test
    fun `shim runs before the card's own scripts`() {
        val html = docPage()
        val shimAt = html.indexOf("__RIKKA_HOST_SHIM__")
        // 卡片正文脚本在 body 里；垫片必须在 head，否则卡一执行就找不到 generate
        val headEnd = html.indexOf("</head>")
        assertTrue("垫片必须在 </head> 之前注入", shimAt in 1 until headEnd)
    }

    @Test
    fun `no shim is injected when generation is unavailable`() {
        // 不传垫片 = 宿主没有生成能力，卡应走它自己的「未注入」分支并给出明确提示，
        // 而不是拿到一个永远 reject 的假接口
        val html = buildCardDocumentPage(card, Color.White, Color.Black, "")
        assertFalse("无生成能力时不应注入垫片", html.contains("__RIKKA_HOST_SHIM__"))
    }

    // ---- 桥对象名不能和高度上报撞车 ----

    @Test
    fun `bridge uses its own object name and does not hijack the height bridge`() {
        val shim = cardHostShim()
        assertTrue("垫片应使用 rikkaHostGen", shim.contains("window.rikkaHostGen"))
        // rikkaHost 已被 HeightBridge 占用；同名只能挂一个对象，
        // 覆盖掉会让卡片高度再也报不上来（表现为卡片塌陷/裁切）
        assertFalse("垫片不得覆盖 rikkaHost", shim.contains("window.rikkaHost ="))
        assertFalse("垫片不得覆盖 rikkaHost", shim.contains("window.rikkaHost="))
    }

    // ---- 结果协议 ----

    @Test
    fun `result kind prefix is compared with its trailing colon`() {
        val shim = cardHostShim()
        // Kotlin 侧拼的是 "ok:\u0000<text>"，substring 出来的 kind 是 "ok:"。
        // 若 JS 写成 === 'ok'（漏冒号），成功结果会被当成失败，
        // 卡显示「生成失败：<整段正文>」—— 正是这个错误最难排查的地方。
        assertTrue("成功分支必须比较 'ok:'", shim.contains("kind === 'ok:'"))
        assertFalse("不得比较无冒号的 'ok'", shim.contains("kind === 'ok'"))
    }

    @Test
    fun `polling treats the empty string as still running`() {
        val shim = cardHostShim()
        // 空串 = 还在跑；误判成完成会让卡拿到空文本
        assertTrue("空串应视为未完成", shim.contains("if (raw === '') return;"))
    }

    @Test
    fun `malformed host payload is rejected rather than silently resolved`() {
        val shim = cardHostShim()
        // 分隔符缺失时必须 reject，不能把整串当正文 resolve
        assertTrue("缺分隔符应报格式异常", shim.contains("sep < 0"))
    }

    // ---- generate 契约 ----

    @Test
    fun `generate returns a real promise`() {
        val shim = cardHostShim()
        // 卡里是 g = window.generate(req); g.then(done, fail) —— 必须返回 Promise
        assertTrue("generate 必须返回 Promise", shim.contains("return new Promise("))
        assertTrue("应 resolve 正文", shim.contains("resolve(body)"))
        assertTrue("应 reject 错误", shim.contains("reject(new Error("))
    }

    @Test
    fun `generation id from the card is passed back with stream chunks`() {
        val shim = cardHostShim()
        // 卡的流式监听里有 generationId !== activeGenerationId 过滤，
        // 不带 id 会让别的生成内容串进这张卡的面板
        assertTrue("流式事件应带 generation_id", shim.contains("info.generation_id"))
        assertTrue(
            "应推送 STREAM_TOKEN_RECEIVED_FULLY",
            shim.contains("STREAM_TOKEN_RECEIVED_FULLY"),
        )
    }

    @Test
    fun `stream chunks are only emitted when the text actually changed`() {
        val shim = cardHostShim()
        // 每 120ms 无脑推送会让卡不断重排，长正文时明显掉帧
        assertTrue("应做增量去重", shim.contains("if (!info || info.text === lastStreamText) return;"))
    }

    @Test
    fun `empty prompt is rejected instead of hanging forever`() {
        val shim = cardHostShim()
        // 空 prompt 若直接挂起，卡会永远停在转圈状态
        assertTrue("空 prompt 应 reject", shim.contains("生成请求无效"))
    }

    // ---- eventOn / toastr ----

    @Test
    fun `eventOn family is provided for card listeners`() {
        val shim = cardHostShim()
        assertTrue("应提供 eventOn", shim.contains("window.eventOn = function"))
        assertTrue("应提供 eventRemoveListener", shim.contains("window.eventRemoveListener = function"))
        assertTrue("应提供 iframe_events", shim.contains("window.iframe_events"))
    }

    @Test
    fun `toastr error and warning are forwarded to the host`() {
        val shim = cardHostShim()
        assertTrue("应提供 toastr.error", shim.contains("window.toastr.error = function"))
        assertTrue("应提供 toastr.warning", shim.contains("window.toastr.warning = function"))
        assertTrue("错误应透传宿主", shim.contains("rikkaHostGen.toast("))
    }

    // ---- setChatMessages：卡写回开局正文的唯一通道 ----

    @Test
    fun `setChatMessages is provided and returns a promise`() {
        val shim = cardHostShim()
        // 卡里对缺失是硬失败：
        // if (typeof setChatMessages !== 'function') throw new Error('当前宿主没有 setChatMessages...')
        // 所以它必须真的存在，而不是靠 typeof 守卫降级
        assertTrue("应提供 setChatMessages", shim.contains("window.setChatMessages = function"))
        assertTrue("应返回 Promise", shim.contains("return awaitHost(window.rikkaHostGen.setChatMessages("))
    }

    @Test
    fun `setChatMessages normalizes the card's message shape`() {
        val shim = cardHostShim()
        // 卡传的是 [{message_id, is_hidden, message}] + {refresh}
        assertTrue("应读取 message_id", shim.contains("msg.message_id"))
        assertTrue("应读取 message", shim.contains("msg.message"))
        assertTrue("应读取 is_hidden", shim.contains("msg.is_hidden"))
        assertTrue("应读取 refresh", shim.contains("options.refresh"))
    }

    @Test
    fun `hidden-only writes do not fail on a missing message field`() {
        val shim = cardHostShim()
        // 卡会调 setChatMessages([{message_id:0, is_hidden:true}], {refresh:'none'})
        // 这条没有 message 字段；若把它当成空正文直接 reject，
        // 卡的 hideOpeningUiFromAI() 会静默失败，卡面就一直留在消息里
        assertTrue("message 缺失应归一为空串", shim.contains("(msg.message == null) ? ''"))
    }

    @Test
    fun `shared await helper is used by every host round-trip`() {
        val shim = cardHostShim()
        // generate 与 setChatMessages 共用同一套轮询协议；
        // 各写一份迟早会在某一侧漏掉冒号比较之类的细节
        assertTrue("应抽出 awaitHost", shim.contains("function awaitHost(id)"))
        assertTrue("generate 应复用它", shim.contains("return awaitHost(id);"))
    }

    @Test
    fun `card code never sees a host call that would throw synchronously`() {
        val shim = cardHostShim()
        // 所有宿主调用点都要包 try/catch：桥对象在页面早期可能尚未就绪，
        // 抛出的异常会中断卡自己的初始化脚本
        val calls = Regex("""rikkaHostGen\.\w+\(""").findAll(shim).count()
        val guarded = Regex("""try \{[^}]*rikkaHostGen\.\w+\(""").findAll(shim).count()
        assertTrue("存在未做异常保护的宿主调用（$guarded/$calls）", guarded == calls)
    }
}
