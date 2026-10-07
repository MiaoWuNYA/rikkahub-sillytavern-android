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
        assertTrue("要自动选默认模型", vm.contains("importModels"))
        assertTrue("要设成默认", vm.contains("chatModelId = preferred.id"))
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
    fun `no referrer links anywhere in the provider lists`() {
        // 提供商列表前后有两版。第一版全是带返利链接的中转站——那些链接
        // 对项目有收益，但把一个「选谁家的模型」的决定替用户做了，而且是
        // 照着收益做的。已全部移除，这条测试防止它再被加回来。
        val recommended = read("src/main/java/me/rerere/rikkahub/data/datastore/RecommendedProviders.kt")
        val defaults = read("src/main/java/me/rerere/rikkahub/data/datastore/DefaultProviders.kt")
        val prefs = read("src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt")

        listOf("recommended" to recommended, "defaults" to defaults, "prefs" to prefs)
            .forEach { (label, src) ->
                // 去掉注释再查：解释「为什么删掉它们」的注释里当然会提到旧链接。
                // 块注释用 /* */ 和 kdoc 的 * 开头，行注释用 // —— 三种都要剔，
                // 只剔 // 会把 kdoc 里引用的旧域名判成违规。
                val code = src
                    .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
                    .lines()
                    .filterNot { it.trimStart().startsWith("*") }
                    .joinToString("\n") { line ->
                        val i = line.indexOf("//")
                        if (i >= 0) line.take(i) else line
                    }
                assertFalse("$label 里不应有返利参数", code.contains("aff="))
                assertFalse("$label 里不应有推广跳转", code.contains("go.apimart"))
                assertFalse("$label 里不应有推广中转站", code.contains("aihubmix.com/v1"))
                assertFalse("$label 里不应有推广中转站", code.contains("sui-xiang.com/v1"))
                assertFalse("$label 里不应有推广中转站", code.contains("api.muteki.site"))
            }
    }

    @Test
    fun `custom provider comes first`() {
        // 用开源客户端的人多半已有自己的中转站或自建服务，对这些人
        // 整份推荐列表一个都用不上。把自定义塞在最下面等于逼他先翻完
        // 一遍别人的列表。
        val customIdx = page.indexOf("CustomProviderCard(")
        val listIdx = page.indexOf("RECOMMENDED_PROVIDERS.forEach")
        assertTrue("应有自定义卡片", customIdx > 0)
        assertTrue("应有官方列表", listIdx > 0)
        assertTrue("自定义必须排在官方列表之前", customIdx < listIdx)
        // 自定义要能填地址，否则「自定义」名不副实
        assertTrue("自定义要能改地址", page.contains("onBaseUrlChange"))
    }

    @Test
    fun `providers are labelled with their barrier to entry`() {
        // 只标事实、不推荐谁。新人最常见的死法是选了个要充值 + 实名的，
        // 卡在支付页就卸载了。
        val recommended = read("src/main/java/me/rerere/rikkahub/data/datastore/RecommendedProviders.kt")
        assertTrue("要标注门槛", recommended.contains("门槛："))
        assertTrue("要说明是否需实名", recommended.contains("实名"))
    }

    @Test
    fun `guide is skipped when a model is already configured`() {
        // 从旧版本升级上来的用户本地早有配好的模型，给他看一遍
        // 「请选择提供商」纯属打扰。判断依据是「真的有可用的
        // provider + 至少一个模型」，而不是某个标记位。
        assertTrue("要检查是否已有可用模型", route.contains("hasUsableModel"))
        assertTrue(
            "判定要同时看标记和模型",
            route.contains("!OnboardingState.isCompleted(this) && !hasUsableModel"),
        )
    }

    @Test
    fun `guide waits for settings to load before deciding`() {
        // settingsFlow 的初值是 Settings.dummy()，providers 为空；
        // DataStore 读完才换成真实值。若在首帧就下结论，
        // hasUsableModel 会算出 false——刚导入完备份的用户重启后会被
        // 再弹一次引导，而他明明什么都配好了。
        //
        // 而 startScreen 只算一次（rememberNavBackStack 持有它），
        // 算错就定死了，之后再怎么重算都没用。
        assertTrue("要等设置加载完", route.contains("settingsLoaded"))
        assertTrue(
            "要等的是 raw flow（StateFlow 会立刻给初值，等不到加载完成）",
            route.contains("settingsFlowRaw.first()"),
        )
        // 等待必须发生在判定之前
        val waitIdx = route.indexOf("settingsFlowRaw.first()")
        val decideIdx = route.indexOf("val hasUsableModel")
        assertTrue("等待要在判定之前", waitIdx in 1 until decideIdx)
        // 未加载完时不得下结论
        assertTrue("加载完之前要提前返回", route.contains("if (!settingsLoaded)"))
    }

    @Test
    fun `restoring a backup marks onboarding as done`() {
        // 恢复成功后必须落标记，不能只弹重启框。
        //
        // 备份走 PendingRestore，文件在下次启动、数据库和设置初始化
        // **之前**才原子替换。光靠 hasUsableModel 不稳妥——只要那一刻
        // 设置还没读出来，用户就会被再弹一次引导。
        // 而「用户从备份来」是确定的事实：他不需要引导。
        val restoreBlock = page.substringAfter("vm.restoreBackup(context, temp)")
            .take(600)
        assertTrue(
            "恢复成功要落标记",
            restoreBlock.contains("OnboardingState.markCompleted"),
        )
    }

    @Test
    fun `the original-app row shows a conclusion instead of a check button`() {
        // 检测在进入首屏时就做完了，用户看到的是结论。
        // 把已经知道的答案再做成一个「检查一下」按钮让他点，是白费一次点击。
        assertTrue("检测要前置", page.contains("getLaunchIntentForPackage"))
        assertTrue("要传安装状态进去", page.contains("installed = originalLaunchIntent != null"))
        // 说清为什么不能自动导入，并给出两步路径
        assertTrue("要解释系统限制", page.contains("App 之间不能直接读对方的数据"))
        assertTrue("要能跳去原版", page.contains("打开原版去导出"))
    }

    @Test
    fun `no markdown asterisks leak into guide text`() {
        // 引导页的 Text 不解析 Markdown。字符串里写 ** 只会原样显示成星号，
        // 用户看到的是「**导入之后你什么都不用再配**」这种脏文本。
        // 要加粗就用 SpanStyle 做真实加粗。
        val literals = Regex("\"([^\"]*)\"")
            .findAll(page)
            .map { it.groupValues[1] }
            .filterNot { it.startsWith("http") }
            .toList()
        val withStars = literals.filter { it.contains("**") }
        assertTrue("字符串里不该有 Markdown 星号：$withStars", withStars.isEmpty())
        assertTrue("改用真实加粗", page.contains("SpanStyle(fontWeight = FontWeight.SemiBold)"))
    }

    @Test
    fun `extras are clickable and lead somewhere real`() {
        // 原来只有文字说明，用户看完得自己去找入口——而「去哪儿找」
        // 恰恰是新人最卡的地方。说到哪就要能点到哪。
        listOf("Screen.Assistant", "Screen.Extensions", "Screen.SettingSearch", "Screen.SettingMemory")
            .forEach { target ->
                assertTrue("可选项应能跳到 $target", page.contains(target))
            }
        assertTrue("要有导航回调", page.contains("onNavigate"))
    }

    @Test
    fun `the exit button sits at the top`() {
        // 原来出口在底部：配好 Key、看到「测试成功」之后，还得往下翻
        // 一屏才能找到「下一步」，而配置区展开后页面本身是长的。
        val scaffold = page.substringAfter("private fun StepScaffold(")
            .substringBefore("private fun BigChoiceButton(")
        val skipIdx = scaffold.indexOf("onSkip")
        val contentIdx = scaffold.indexOf("content()")
        assertTrue("出口要在内容之前（即位于顶部）", skipIdx in 1 until contentIdx)
    }

    @Test
    fun `all discovered models are imported with capabilities filled in`() {
        // 之前只挑一个模型存进去：探测到 50 个也只留 1 个，
        // 用户还得回设置里手动一个一个加。既然结果就在手里，没理由不全收。
        assertTrue("要全部导入", vm.contains("enriched"))
        assertTrue("要有导入方法", vm.contains("importModels"))

        // 能力默认全开，注册表查询作为补充。
        //
        // 注册表里查到的值偏保守：很多第三方中转、微调模型、新发布的模型
        // 压根不在表里，查出来是空，于是推理、工具、看图一个都用不了——
        // 而用户看到的症状是「图片发不出去」「工具调不动」，很难归因。
        //
        // 只有上下文长度不能乱填（它影响历史裁剪，填大了会超限报错），
        // 那个仍然查注册表。
        assertTrue("上下文长度仍要查注册表", vm.contains("MODEL_CONTEXT_LENGTH"))
        assertTrue("要查注册表做补充", vm.contains("ModelRegistry"))
    }

    @Test
    fun `progress survives navigating away and back`() {
        // Navigation3 切到别的页面时会销毁引导页的组合。用 remember 的话
        // 回来时状态被重置，用户从「能聊了」点进扩展页看一眼，
        // 回来又从第一屏开始。必须用 rememberSaveable 顶住销毁重建。
        assertTrue("步骤要能保存", page.contains("rememberSaveable"))
        assertTrue("存的是可序列化的名字", page.contains("OnboardingState.Step.WELCOME.name"))
    }

    @Test
    fun `success feedback appears above the list not at the bottom`() {
        // 测试成功之后用户视线就在配置卡片上，结论和下一步必须在那儿出现，
        // 而不是放在几十行之外的页面底部等着他去翻。
        val okIdx = page.indexOf("配好了，能用了！")
        val listIdx = page.indexOf("RECOMMENDED_PROVIDERS.forEach")
        assertTrue("要有成功提示", okIdx > 0)
        assertTrue("成功提示要在列表之前", okIdx < listIdx)
    }

    @Test
    fun `tavern mode can be toggled from the last step`() {
        // 角色扮演用户几乎一定要开酒馆模式，但多数人不知道设置里有这个
        // 选项。放在引导最后一步、一眼能看到的位置，省掉「用了一阵才发现」。
        assertTrue("引导要有酒馆模式开关", page.contains("开启酒馆模式"))
        assertTrue("要能真正写进设置", vm.contains("enableTavernMode"))
        // 说明为什么该开
        assertTrue("要说清作用", page.contains("减少模型的安全拦截策略"))
    }

    @Test
    fun `imported models have capabilities enabled by default`() {
        // 注册表里查到的值偏保守：很多第三方中转、微调模型、新模型压根
        // 不在表里，查出来是空，于是推理/工具/看图一个都用不了。
        // 用户看到的症状是「图片发不出去」「工具调不动」，很难归因。
        // 所以反过来：默认全开，真不支持时服务端会报错，关掉即可。
        assertTrue("工具默认开", vm.contains("ModelAbility.TOOL"))
        assertTrue("推理默认开", vm.contains("ModelAbility.REASONING"))
        assertTrue("图片默认开", vm.contains("Modality.IMAGE"))
        assertTrue("要写清为什么默认全开", vm.contains("默认全开"))
    }

    @Test
    fun `title generation falls back to the conversation model`() {
        // 刚配好模型的新用户，标题模型和快速模型**都还是空的**——他只在
        // 引导里选了一个对话模型。原来的回退链到这里就抛
        // 「快速模型未找到」，用户刚进门就撞上一个红字报错，
        // 而他做的一切都是对的。
        //
        // 现在多了第三级：用他正在聊的那个模型。他既然选了它，说明可用；
        // 标题是个极短的任务，用对话模型没有代价。
        val chat = read("src/main/java/me/rerere/rikkahub/service/ChatService.kt")
        val block = chat.substringAfter("// 标题用哪个模型，按这个顺序挑：").take(1400)
        assertTrue("要有三级回退", block.contains("titleModelId") && block.contains("fastModelId"))
        assertTrue("第三级是对话模型", block.contains("conversationModel"))
        assertTrue("助手被删要有兜底", block.contains("settings.chatModelId"))
    }

    @Test
    fun `tavern mode defaults to dropping tools`() {
        // 开酒馆模式默认不保留工具：工具定义本身占不少 token，
        // 也会触发某些模型的策略。开酒馆模式的人要的就是最干净的请求。
        val prefs = read("src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt")
        assertTrue(
            "保留工具默认应为 false",
            prefs.contains("val tavernModeKeepTools: Boolean = false"),
        )
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
