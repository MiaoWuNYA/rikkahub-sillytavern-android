package me.rerere.rikkahub.capability

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 应用公告。
 *
 * 设计上有一条必须守住的边界：**任何环节出问题都不能影响启动**。
 * 公告是锦上添花，读不到、格式坏了、图片缺了，都只是不显示，
 * 不该让应用起不来或者停在某个半截状态。
 */
class AnnouncementTest {

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

    private val route get() = read("src/main/java/me/rerere/rikkahub/RouteActivity.kt")

    private fun file(rel: String): File? {
        for (root in roots) {
            for (c in listOf(File(root, rel), File(root, "app/$rel"))) {
                if (c.exists()) return c
            }
        }
        return null
    }

    @Test
    fun `the shipped announcement parses`() {
        // 随包发布的公告必须真的能解析。写坏一个 json 就等于公告永远不出现，
        // 而且不会有人发现——它失败得毫无声响。
        val f = file("src/main/assets/announcement/announcement.json")
        assertTrue("公告文件应存在", f != null)

        val json = Json { ignoreUnknownKeys = true }
        val obj = json.parseToJsonElement(f!!.readText()).let {
            it as kotlinx.serialization.json.JsonObject
        }
        assertTrue("必须有 id（「不再提示」按它记）", obj["id"] != null)
        assertTrue("必须有标题或正文", obj["title"] != null || obj["body"] != null)
    }

    @Test
    fun `missing or broken announcement never breaks startup`() {
        val mgr = read("src/main/java/me/rerere/rikkahub/data/service/AnnouncementManager.kt")
        // 读取全程包在 runCatching 里，失败退化成「没有公告」
        assertTrue("读公告要容错", mgr.contains("runCatching"))
        assertTrue("失败要退化成没有公告", mgr.contains("getOrNull()"))
        // 云端失败要能退回内置，而不是直接没有公告
        assertTrue("云端失败要退回内置", mgr.contains("loadBundled"))
        // 配图缺失时也要能安全降级
        assertTrue("图片路径要能返回 null", mgr.contains("fun imageAssetPath"))
    }

    @Test
    fun `bundled copy is always available as a fallback`() {
        // 公告分两级：云端优先、内置兜底。
        //
        // 内置那一份必须在，否则「没网」就等于「永远没有公告」——
        // 而目标用户里没网或者访问不了 GitHub 是常态。
        val mgr = read("src/main/java/me/rerere/rikkahub/data/service/AnnouncementManager.kt")
        assertTrue("要有内置来源", mgr.contains("context.assets.open"))
        assertTrue("内置路径在 assets 下", mgr.contains("announcement/announcement.json"))
        // 内置那份文件也要真的存在
        assertTrue(
            "内置公告文件应存在",
            file("src/main/assets/announcement/announcement.json") != null,
        )
    }

    @Test
    fun `dismissal is per-announcement not global`() {
        // ⚠️ 这里踩过坑：原来用单个字符串存「已关闭的公告 id」，
        // 关掉任何一条就把那个值覆盖掉——用户关掉一条，
        // 以后所有公告都被静默吞掉，而他并不知道自己同意过什么。
        //
        // 必须是一组 id，一条一条地记。
        val mgr = read("src/main/java/me/rerere/rikkahub/data/service/AnnouncementManager.kt")
        assertTrue("要存集合而不是单值", mgr.contains("dismissed_ids"))
        assertFalse(
            "不该再有单值键",
            mgr.contains("putString(KEY_DISMISSED_ID,"),
        )
        // 关闭记录现在是 id@时间戳：知道了只静默 1 小时，过后再弹
        assertTrue("读取要返回记录表", mgr.contains("fun dismissedRecords(context: Context)"))
        assertTrue("要有时长常量", mgr.contains("REAPPEAR_AFTER_MS"))
        assertTrue(
            "判断要按墙钟时间（手机时间为准）",
            mgr.contains("System.currentTimeMillis()"),
        )
        assertTrue("能重置", mgr.contains("fun reset"))
    }

    @Test
    fun `cloud announcement uses domestic-friendly mirrors`() {
        // 云端公告必须考虑国内网络：直连 GitHub 常常不通，
        // 镜像这一层是必需的，少了它公告在目标用户那里等于不存在。
        val mgr = read("src/main/java/me/rerere/rikkahub/data/service/AnnouncementManager.kt")
        assertTrue("要用 jsDelivr", mgr.contains("cdn.jsdelivr.net"))
        assertTrue("要有 GitHub 代理", mgr.contains("ghproxy.net") && mgr.contains("ghfast.top"))
        // 超时必须短：公告不值得让用户多等
        assertTrue("要有超时", mgr.contains("callTimeout"))
        assertTrue("超时要短", mgr.contains("SECONDS"))
        // 云端拿不到要退回内置
        assertTrue("要有内置兜底", mgr.contains("loadBundled"))
    }

    @Test
    fun `cloud announcement supports remote images`() {
        val mgr = read("src/main/java/me/rerere/rikkahub/data/service/AnnouncementManager.kt")
        assertTrue("要能识别远端图", mgr.contains("fun remoteImageUrl"))
        // 云端图放 assets 就换不了图，失去云端的意义
        assertTrue("已完整 URL 的不走 assets", mgr.contains("startsWith(\"http\")"))
    }

    @Test
    fun `the dialog offers a dont show again option`() {
        val dlg = read("src/main/java/me/rerere/rikkahub/ui/components/ui/AnnouncementDialog.kt")
        assertTrue("要有不再提示", dlg.contains("不再提示"))
        assertTrue("要能加图", dlg.contains("BitmapFactory.decodeStream"))
        // 图片解不出来时不该留一块空白
        assertTrue("图片要容错", dlg.contains("getOrNull()"))
    }

    @Test
    fun `the dialog has exactly one decisive button`() {
        // 这里前后改过两版：先是「不再提示按钮 + 勾选框」并存，两者做同一件
        // 事；然后是「勾选框 + 稍后」，但「知道了」和「稍后」语义重叠——
        // 都是关闭，区别只在下次弹不弹，而那个区别已经由勾选框表达了。
        //
        // 一个弹窗只需要一个决定性的按钮。
        val dlg = read("src/main/java/me/rerere/rikkahub/ui/components/ui/AnnouncementDialog.kt")
        assertFalse("不该再有「稍后」这种语义重叠的按钮", dlg.contains("\"稍后\""))
        assertTrue("主按钮是知道了", dlg.contains("\"知道了\""))
        assertTrue("勾选框表达永久关闭", dlg.contains("\"不再提示\""))
    }

    @Test
    fun `announcement shows in settings not on launch`() {
        // 启动时弹会拦住只想继续聊天的人——他可能只想让 AI 说完刚才那句。
        // 公告属于「有空时想了解的信息」，不该是启动阻塞项。
        //
        // 设置页是自然的落点：用户主动进来、心态是「来看看有什么」。
        val setting = read("src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPage.kt")
        assertTrue("公告挂在设置页", setting.contains("AnnouncementHost()"))
        assertFalse(
            "启动流程不该有公告",
            route.contains("AnnouncementHost"),
        )
    }

    @Test
    fun `dismissing by tapping outside does not persist`() {
        // 点弹窗外 = 关闭但**不**记「不再提示」。
        // 想永久关掉必须显式勾选，免得用户随手点一下就再也看不到后续公告。
        val dlg = read("src/main/java/me/rerere/rikkahub/ui/components/ui/AnnouncementDialog.kt")
        val dismissHandler = dlg.substringAfter("onDismissRequest = {").take(200)
        assertTrue(
            "点外部只关闭不落记录",
            dismissHandler.contains("onDismiss(false)"),
        )
    }
}
