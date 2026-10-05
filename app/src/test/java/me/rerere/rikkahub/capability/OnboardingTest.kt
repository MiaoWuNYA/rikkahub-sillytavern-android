package me.rerere.rikkahub.capability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 首次引导。
 *
 * 这份测试盯的是**设计约束**，不是像素：引导最容易在迭代中被"优化"成
 * 一个必须走完的流程，那正是它最该避免的形态。
 */
class OnboardingTest {

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
        read("src/main/java/me/rerere/rikkahub/ui/pages/onboarding/OnboardingPage.kt")
    private val vm get() =
        read("src/main/java/me/rerere/rikkahub/ui/pages/onboarding/OnboardingVM.kt")
    private val state get() =
        read("src/main/java/me/rerere/rikkahub/ui/pages/onboarding/OnboardingState.kt")
    private val route get() =
        read("src/main/java/me/rerere/rikkahub/RouteActivity.kt")
    private val setting get() =
        read("src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPage.kt")

    @Test
    fun `onboarding only shows on first launch`() {
        assertTrue("要有完成标记", state.contains("onboarding_completed"))
        assertTrue("启动时要判定", route.contains("!OnboardingState.isCompleted(this)"))
        assertTrue("首次启动进引导", route.contains("Screen.Onboarding"))
        assertTrue("完成后要落标记", page.contains("OnboardingState.markCompleted"))
    }

    @Test
    fun `the guide never traps the user`() {
        // 这是整个设计的底线：每一步都能走掉。
        //
        // 卡着用户学完导入角色卡的引导只会被卸载——他没配完也不会再来。
        // 所以右下角的出口必须无条件存在，而不是满足某个条件才出现。
        assertTrue("要有通用的出口外壳", page.contains("private fun StepScaffold("))
        assertTrue("出口文案", page.contains("skipLabel"))
        // 首屏必须有「自己配」的直通出口
        assertTrue(
            "首屏要允许直接自己配",
            page.contains("我自己配，别烦我"),
        )
        // 可选项那屏必须强调不必须
        assertTrue(
            "可选项要明说不必须",
            page.contains("都不是必须的"),
        )
    }

    @Test
    fun `provider setup is the only required step`() {
        // 必做的只有配模型这一件事。角色卡、世界书、联网搜索全在可选项里。
        assertTrue("有配模型这一步", state.contains("PROVIDER"))
        assertTrue("有可选项这一步", state.contains("EXTRAS"))
        // 可选项列表里的三样都得出现
        listOf("导入角色卡", "导入世界书", "开启联网搜索").forEach {
            assertTrue("可选项应包含 $it", page.contains(it))
        }
    }

    @Test
    fun `connection is verified before moving on`() {
        // 用户以为配好了、一聊报错就走，是这类应用最高频的流失点。
        // 所以填完 Key 必须真的验一次。
        assertTrue("要能测试连接", vm.contains("listModels"))
        assertTrue("有失败态", vm.contains("TestResult.Failed"))
        // 每种常见失败都要给出可操作的建议，不能只说「失败了」
        listOf("401", "404", "429").forEach {
            assertTrue("要区分 $it", vm.contains(it))
        }
        assertTrue("失败要带怎么做", vm.contains("hint"))
    }

    @Test
    fun `a working model is selected automatically`() {
        // 不能让新人自己去「左下角选模型」——那一步经常找不到。
        // 先把他丢进对话里爽一下，手动选模型降级成可选。
        assertTrue("要自动选模型", vm.contains("autoSelectModel"))
        assertTrue("要设成默认", vm.contains("chatModelId = model.id"))
    }

    @Test
    fun `restore warns that a restart is required`() {
        // 恢复走 PendingRestore：暂存后在下次启动、数据库初始化**之前**
        // 原子替换。所以恢复完成时数据还没生效，必须重启。
        // 不说清楚用户会以为卡住了。
        assertTrue("要有重启提示", page.contains("RestartRequiredDialog"))
        assertTrue("说明为什么重启", page.contains("整体替换"))
        assertTrue("真的会重启", page.contains("exitProcess(0)"))
    }

    @Test
    fun `the guide reuses existing features instead of adding new ones`() {
        // 引导不新增能力，只把已有功能串起来。
        assertTrue("服务商列表复用现成的", page.contains("RECOMMENDED_PROVIDERS"))
        assertTrue("恢复复用现有实现", vm.contains("restoreFromLocalFile"))
        // 且不内置任何内容资源
        assertFalse("不得内置预设", page.contains("PRESET_CONTENT"))
    }

    @Test
    fun `guide can be replayed from settings`() {
        // 「我自己配，别烦我」是必要的出口，但手滑点了之后得能找回来。
        assertTrue("设置里要有入口", setting.contains("Screen.Onboarding"))
        assertTrue("入口要写清楚", setting.contains("新手引导"))
    }

    @Test
    fun `progress is not persisted mid-way`() {
        // 存一半的状态会让用户回来时看到莫名其妙的中间页。
        val session = state.substringAfter("class Session")
            .substringBefore("enum class Step")
        assertFalse("会话状态不应落盘", session.contains("writeBooleanPreference"))
        assertFalse("会话状态不应落盘", session.contains("putString"))
    }
}
