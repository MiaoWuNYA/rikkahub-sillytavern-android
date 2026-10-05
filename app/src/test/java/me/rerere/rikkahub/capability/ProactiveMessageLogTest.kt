package me.rerere.rikkahub.capability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 主动消息的执行日志。
 *
 * 这个功能全程在后台跑，出了问题用户在界面上看不到任何东西。
 * 「定时到了却没反应」过去只能靠 logcat 追，现在要求每个决定分支
 * 都留下痕迹，且设置页能直接查看和复制。
 */
class ProactiveMessageLogTest {

    private val roots: List<File> by lazy {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        listOf(cwd, cwd.parentFile, cwd.parentFile?.parentFile)
            .filterNotNull().distinct()
    }

    private fun read(rel: String): String {
        for (root in roots) {
            for (c in listOf(File(root, rel), File(root, "app/$rel"))) {
                if (c.exists()) return c.readText()
            }
        }
        return ""
    }

    private val runner get() =
        read("src/main/java/me/rerere/rikkahub/data/service/ProactiveMessageTriggerService.kt")
    private val log get() =
        read("src/main/java/me/rerere/rikkahub/data/service/ProactiveMessageLog.kt")
    private val page get() =
        read("src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProactiveMessagePage.kt")

    @Test
    fun `every decision branch records a reason`() {
        // 六个「跳过/失败」出口 + 内部检查，少一个用户就会在某个场景下
        // 又变成「没有任何反应也不知道为什么」
        val expected = listOf(
            "总开关处于关闭状态",      // 未启用
            "小于设定的最小间隔",       // 去重
            "还没有任何对话",           // 找不到对话
            "正在进行生成",             // 并发保护
            "没有可用的模型",           // 模型缺失
            "找不到对应的供应商配置",   // provider 缺失
        )
        expected.forEach { marker ->
            assertTrue("缺少分支的日志：$marker", runner.contains(marker))
        }
    }

    @Test
    fun `success and failure are both recorded`() {
        assertTrue("正常发送要有记录", runner.contains("Outcome.SENT"))
        assertTrue("AI 沉默也要有记录（否则看起来像故障）", runner.contains("Outcome.PASSED"))
        assertTrue("异常要有记录", runner.contains("Outcome.FAILED"))
        assertTrue("被用户打断要有记录", runner.contains("用户打断"))
    }

    @Test
    fun `log storage is bounded and never throws`() {
        assertTrue("应有条数上限", log.contains("MAX_ENTRIES"))
        assertTrue("写入不得影响主流程",
            log.substringAfter("fun log(").substringBefore("fun read(").contains("runCatching"))
        // 分隔符不能是日志正文里可能出现的字符
        assertTrue("应使用控制字符做分隔符", log.contains("\\u001F"))
    }

    @Test
    fun `log records the time source and detail`() {
        assertTrue("应记录时间戳", log.contains("timestamp"))
        assertTrue("应记录触发来源（闹钟/手动等）", log.contains("val source"))
        assertTrue("应记录人类可读的原因", log.contains("val detail"))
    }

    @Test
    fun `ui can view and copy the log`() {
        assertTrue("设置页应有日志入口", page.contains("ProactiveMessageLogDialog"))
        assertTrue("应能复制全文", page.contains("writeClipboardText"))
        assertTrue("文本要可选中", page.contains("SelectionContainer"))
        assertTrue("应能清空", page.contains("ProactiveMessageLog.clear"))
        // 打开对话框时重新取内容；否则看到的是进入页面那一刻的旧快照
        assertTrue("日志内容应随对话框打开刷新",
            page.contains("remember(showLogDialog)"))
    }

    @Test
    fun `log itself never writes to the conversation database`() {
        // 这条是为了防止日志功能重蹈覆辙：它绝不能碰会话数据
        assertFalse("日志不应引用 saveConversation",
            log.contains("saveConversation"))
        assertFalse("日志不应引用 ChatService",
            log.contains("ChatService"))
    }
}
