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

    /**
     * 去掉行注释与块注释。
     *
     * 断言「源码里不存在某个危险调用」时必须先剔注释——否则解释
     * 「为什么不用它」的注释本身会把断言判红，反过来逼着人删掉有价值的说明。
     */
    private fun stripComments(src: String): String =
        src.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .lines()
            .joinToString("\n") { line ->
                val i = line.indexOf("//")
                if (i >= 0) line.take(i) else line
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
        assertTrue("对话改动要重排", page.contains("conversationId = picked"))
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
        // worker 自己不再写展示时间，统一由 scheduleNext 负责
        assertTrue("worker 成功路径应说明不重复排程",
            worker.contains("Result.success()") && !worker.contains("next_trigger_time"))
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
        // runOnce 是独立的 work name，不会顶掉已排好的延迟任务；
        // 这里再调 scheduleNext 会让两次随机各产生一个时间，界面跳变
        assertFalse(
            "triggerNow 不应再直接排程（交给 Worker 的一次性任务）",
            body.contains("scheduleNext("),
        )
        assertTrue("应立即派发一次性任务", body.contains("runOnce("))
    }

    @Test
    fun `background trigger never starts a foreground service`() {
        // 这是「定时到了但毫无反应」的根因所在。
        //
        // targetSdk 31+ 禁止后台启动前台服务，闹钟/Worker 触发时系统抛
        // ForegroundServiceStartNotAllowedException，而调用方的
        // catch(Exception) 把它吞成一行日志，UI 上完全看不出来。
        // 现在整条链路改走 WorkManager，任何一处回退到 FGS 都会让这个
        // 问题重现，所以直接断言源码里不存在该调用。
        for ((name, src) in listOf(
            "ProactiveMessageService" to service,
            "ProactiveMessageWorker" to worker,
        )) {
            assertFalse(
                "$name 不应调用 startForegroundService（后台会抛 ForegroundServiceStartNotAllowedException）",
                stripComments(src).contains("startForegroundService"),
            )
        }
        // 触发链路里也不该再引用那个前台服务类
        assertFalse(
            "不应再引用 ProactiveMessageTriggerService 类",
            stripComments(service).contains("ProactiveMessageTriggerService"),
        )
    }

    @Test
    fun `worker runs generation directly instead of delegating to a service`() {
        assertTrue("Worker 应直接构造执行器",
            worker.contains("ProactiveMessageRunner("))
        assertTrue("应调用 run()", worker.contains("runner.run()"))
        // Worker 不该再在失败时排程——Runner 的 finally 已经排过，
        // 两边都排会抢同一个 unique work name
        val catchBlock = worker.substringAfter("} catch (e: Exception) {").substringBefore("} finally")
        assertFalse("失败分支不应重复排程", catchBlock.contains("scheduleNext("))
        assertTrue("应返回 failure 让 WorkManager 记账", catchBlock.contains("Result.failure()"))
    }

    @Test
    fun `trigger stamp is committed only after a successful run`() {
        // 原先在「检查是否重复」时就无条件写时间戳，于是任何失败
        // （无对话/无模型/网络错误）都会把之后 minInterval 窗口内的
        // 真实触发全判成 duplicate，失败被固化成永久静默。
        val dedup = trigger.substringAfter("val lastTriggeredTime =").substringBefore("if (System.currentTimeMillis()")
        assertFalse("去重检查不应写入时间戳", dedup.contains("putLong"))
        assertTrue("应有成功后才提交的入口", trigger.contains("commitTriggerStamp"))
        assertTrue("成功分支应调用它", trigger.contains("commitTriggerStamp(prefs)"))
    }

    @Test
    fun `conversation picker is not hidden behind the enable switch`() {
        // 用户报「能选助手、选不了对话」：选择器当时被包在
        // if (proactive.enabled) 里，而开关默认是关的，于是它根本不
        // 进 composition。这是配置项，不该依赖运行状态。
        val idx = page.indexOf("ConversationPicker(")
        assertTrue("页面应有对话选择器", idx > 0)
        // 从每个 if (proactive.enabled) 起，用括号配对找出它真正的作用域，
        // 看 ConversationPicker 是否落在其中。只数括号而不配对会在嵌套
        // 结构里算错，所以这里逐字符扫到配平为止。
        // 用缩进判断作用域：该文件里 LazyColumn 的直接子项统一缩进 12 空格，
        // if (proactive.enabled) { 也是 12 空格。若 ConversationPicker 的
        // 12 空格缩进行出现在某个 if 块闭合之前，说明它被包住了。
        //
        // 不用括号配对：这一段的字符串里含有 '{' 和 '}'（注释与文案），
        // 盲数括号会算错作用域，反而给出假的通过/失败。
        val lines = page.lines()
        val pickerLine = lines.indexOfFirst { it.trimStart().startsWith("ConversationPicker(") }
        assertTrue("页面应有对话选择器", pickerLine > 0)

        var depth = 0
        for (i in 0 until pickerLine) {
            val t = lines[i].trim()
            if (t.startsWith("if (proactive.enabled)")) depth++
            // item { ... } 与会话块结束都算一层收束
            if (depth > 0 && t == "}" && lines[i].length - lines[i].trimStart().length <= 12) depth--
        }
        assertTrue(
            "ConversationPicker 位于第 $pickerLine 行，前面还有 $depth 个未闭合的 " +
                "if (proactive.enabled)——开关默认关闭时它就不可见，" +
                "这正是用户报的「能选助手、选不了对话」",
            depth == 0,
        )
    }

    @Test
    fun `conversation list uses the light projection and surfaces failures`() {
        assertTrue("应使用轻量查询",
            page.contains("getRecentConversationTitles"))
        assertFalse("不应为了拿标题而全量加载消息节点",
            page.contains("getRecentConversations(assistantIdForList"))
        // 加载失败必须可见，不能吞成空列表——否则「坏掉」和「真的没有」长得一样
        assertTrue("应记录加载失败", page.contains("loadFailed"))
        assertTrue("应给 LazyColumn 高度约束", page.contains("heightIn(max = 320.dp)"))
        assertTrue("remember 应带 key 避免切助手时残留", page.contains("remember(assistantIdForList)"))
    }
}
