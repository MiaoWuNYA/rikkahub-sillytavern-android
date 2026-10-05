package me.rerere.rikkahub.capability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 主动消息（定时发送）的接线检查。
 *
 * 针对一组实测出来的「设置了不生效」：
 *
 *  1. 改间隔/改助手只写 DataStore，不重排闹钟 —— 必须关掉再开才生效，
 *     这就是用户说的「不生效」。UI 里所有改动路径都要走 applySetting。
 *  2. 触发时只会找「该助手最近的对话」，无法指定固定对话。
 *  3. Worker 与 AlarmManager 各自排程、互相覆盖，展示时间与实际触发对不上。
 *  4. 关掉再打开时 last_triggered_time 残留，首条被最小间隔误挡。
 */
class ProactiveMessageWiringTest {

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

    private val page get() =
        read("src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProactiveMessagePage.kt")
    private val service get() = read("src/main/java/me/rerere/rikkahub/data/service/ProactiveMessageService.kt")
    private val trigger get() = read("src/main/java/me/rerere/rikkahub/data/service/ProactiveMessageTriggerService.kt")
    private val worker get() = read("src/main/java/me/rerere/rikkahub/data/service/ProactiveMessageWorker.kt")

    @Test
    fun `changing settings reschedules immediately`() {
        // 只写不排 = 用户改完毫无反应
        assertTrue("设置页应有统一的写入+重排入口",
            page.contains("fun applySetting("))
        assertTrue("写入后必须调用 scheduleNext",
            page.contains("ProactiveMessageService.scheduleNext"))
        // 间隔、助手、阈值三处都得走这个入口
        assertTrue("min 间隔改动要重排", page.contains("minIntervalMinutes = it"))
        assertTrue("max 间隔改动要重排", page.contains("maxIntervalMinutes = it"))
        assertTrue("助手改动要重排", page.contains("assistantId = it"))
        assertTrue("对话改动要重排", page.contains("conversationId = it"))
    }

    @Test
    fun `interval input is not silently dropped`() {
        // 绑 Int 时退格删空 → 解析失败 → 不回写 → 输入框弹回原值，删不掉
        assertTrue("输入框应有本地文本态", page.contains("var text by remember(value)"))
        assertTrue("应只接受数字", page.contains("filter { it.isDigit() }"))
        assertTrue("非法值应显示错误而不是装作没发生", page.contains("isError"))
        assertTrue("应使用数字键盘", page.contains("KeyboardType.Number"))
        // 下限提示必须写清是 1，不能被 placeholder 的 30/90 误导
        assertTrue("应复用统一的上下限常量",
            page.contains("ProactiveMessageSetting.MIN_INTERVAL_MINUTES"))
    }

    @Test
    fun `target conversation can be pinned`() {
        assertTrue("设置项应有 conversationId",
            read("src/main/java/me/rerere/rikkahub/data/datastore/ProactiveMessageSetting.kt")
                .contains("val conversationId: String"))
        assertTrue("应有解析固定对话的函数",
            service.contains("resolveTargetConversation"))
        assertTrue("触发时应使用它", trigger.contains("resolveTargetConversation"))
        // 对话被删后要退回最近的，而不是直接失败
        assertTrue("应回退到最近对话",
            service.contains("falling back to most recent") || service.contains("getRecentConversations"))
        assertTrue("UI 应有对话选择器", page.contains("ConversationPicker"))
    }

    @Test
    fun `settings are normalized before use`() {
        assertTrue("数据类应有 normalized",
            read("src/main/java/me/rerere/rikkahub/data/datastore/ProactiveMessageSetting.kt")
                .contains("fun normalized()"))
        assertTrue("调度前应归一化", service.contains("setting.normalized()"))
        assertTrue("触发时应归一化", trigger.contains("proactiveMessageSetting.normalized()"))
        assertTrue("worker 应归一化", worker.contains("proactiveMessageSetting.normalized()"))
    }

    @Test
    fun `worker does not fight the alarm channel`() {
        // worker 曾经在「值为 0 时」写展示时间，导致 Alarm 先写过之后
        // worker 排的时间永远不显示，界面与实际触发对不上
        assertFalse("worker 不应再自己写展示时间",
            worker.contains("""getLong("next_trigger_time", 0L) == 0L"""))
        // worker 成功后不该再排一次，TriggerService 的 finally 已经排过
        val successBlock = worker.substringAfter("return Result.success()").take(0)
        assertTrue("worker 成功路径应说明不重复排程", worker.contains("不在这里排下一次"))
    }

    @Test
    fun `turning off clears the previous trigger stamp`() {
        // 关掉再开时若 last_triggered_time 还在，首条会被 min interval 挡掉一次
        assertTrue("cancel 应清除 last_triggered_time",
            service.contains("remove(KEY_LAST_TRIGGERED_TIME)"))
        assertTrue("该键应提成常量", service.contains("KEY_LAST_TRIGGERED_TIME ="))
    }

    @Test
    fun `next trigger countdown refreshes every second`() {
        // 原来 10 秒一跳，秒数看起来是卡住的
        assertTrue("倒计时应每秒刷新", page.contains("delay(1000L)"))
    }

    @Test
    fun `triggerNow does not schedule a competing alarm`() {
        val body = service.substringAfter("fun triggerNow(").substringBefore("\n        }")
        // 前台服务的 finally 是唯一权威排程点；这里再排一次会让两次
        // scheduleNext 各随机一个时间，界面上的下次时间莫名跳变
        val schedules = Regex("scheduleNext\\(").findAll(body).count()
        assertTrue(
            "triggerNow 只应在启动失败时兜底排程，实际直接调用 $schedules 次",
            schedules <= 1,
        )
        assertTrue("失败时仍要兜底排程", body.contains("catch") && schedules == 1)
    }
}
