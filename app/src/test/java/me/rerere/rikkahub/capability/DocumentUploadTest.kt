package me.rerere.rikkahub.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 上传文档必须真的送到模型。
 *
 * 用户报告：上传 md 文档后 AI 什么都没收到——连路径信息都没有，
 * 只有自己打的字被读到了。
 *
 * 根因不是文档读取本身（那段代码与上游一致），而是**转换器列表**：
 * ChatService 里组装请求时用 buildList 手写了一份 inputTransformers，
 * 逐个 add 了自己要的那几个，**漏掉了文件级那份基础列表**——
 * 而 DocumentAsPromptTransformer 正在其中。
 *
 * 后果不只是文档：时间提醒、占位符替换、OCR、技能触发一起失效，
 * 且完全静默（transformer 没跑，不会留下任何日志或错误）。
 */
class DocumentUploadTest {

    private fun read(rel: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        for (root in listOf(cwd, cwd.parentFile, cwd.parentFile?.parentFile).filterNotNull()) {
            for (c in listOf(File(root, rel), File(root, "app/$rel"))) {
                if (c.exists()) return c.readText()
            }
        }
        return ""
    }

    private val chatService get() = read("src/main/java/me/rerere/rikkahub/service/ChatService.kt")

    @Test
    fun `document transformer is in the base list`() {
        val base = chatService.substringAfter("private val inputTransformers by lazy {")
            .substringBefore("private val outputTransformers")
        assertTrue(
            "基础列表必须含 DocumentAsPromptTransformer",
            base.contains("DocumentAsPromptTransformer"),
        )
    }

    @Test
    fun `every request build starts from the base list`() {
        // 这是本次 bug 的核心：组装请求时必须先 addAll(inputTransformers) 打底。
        // 逐个手写 add 的写法一旦漏项就静默失效，且以后往基础列表加东西
        // 也会在这里被无声丢掉。
        val tail = chatService.substringAfter("private val inputTransformers by lazy {")
        val addAllCount = Regex("""addAll\(inputTransformers\)""").findAll(tail).count()
        assertTrue(
            "两处组装请求都该用 addAll 打底，实际 $addAllCount 处",
            addAllCount >= 2,
        )
    }

    @Test
    fun `tavern mode keeps document conversion`() {
        // 原版酒馆不发文档，但这里的取舍标准是「用户的动作有没有表达这个意图」：
        // 手动传了文件就是要发。静默丢掉只会让用户以为功能坏了。
        val tail = chatService.substringAfter("private val inputTransformers by lazy {")
        assertTrue(
            "酒馆模式不该排除文档转换",
            !tail.contains("removeAll { it === DocumentAsPromptTransformer }"),
        )
    }

    @Test
    fun `the document reader handles markdown`() {
        // md 走 else 分支的 readText()，必须有这条兜底，
        // 否则任何未列入 when 的类型都读不出来
        val tr = read("src/main/java/me/rerere/rikkahub/data/ai/transformers/DocumentAsPromptTransformer.kt")
        assertTrue("要有 else 兜底", tr.contains("else -> file.readText()"))
        assertTrue("要产出 UploadFile 提示词", tr.contains("<UploadFile"))
        // 读不到时必须留下错误说明，而不是静默为空
        assertTrue("失败要有可见的错误文本", tr.contains("[ERROR"))
    }

    @Test
    fun `an empty document part still counts as input`() {
        // 只传文件不打字时，消息不能因为是「空输入」被丢掉
        val msg = read("../ai/src/main/java/me/rerere/ai/ui/Message.kt")
            .ifBlank { read("ai/src/main/java/me/rerere/ai/ui/Message.kt") }
        if (msg.isBlank()) return
        val block = msg.substringAfter("fun List<UIMessagePart>.isEmptyInputMessage()")
            .substringBefore("fun List<UIMessagePart>.isEmptyUIMessage()")
        assertTrue(
            "有 url 的 Document 不算空输入",
            block.contains("is UIMessagePart.Document -> message.url.isBlank()"),
        )
    }
}
