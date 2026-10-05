package me.rerere.rikkahub.ui.pages.onboarding

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import me.rerere.rikkahub.ui.hooks.readBooleanPreference
import me.rerere.rikkahub.ui.hooks.writeBooleanPreference

/**
 * 首次引导的持久化状态。
 *
 * 用 SharedPreferences 而不是 DataStore：这份标记必须在**数据库和设置
 * 初始化之前**就能读到——引导页要在 `startScreen` 里决定跳哪儿，那时
 * DataStore 还没起来。而且它只是「看过没有」这一位，不需要 DataStore
 * 的事务与响应式能力。
 */
object OnboardingState {

    private const val KEY_COMPLETED = "onboarding_completed"

    /** 用户类型，只影响引导里推荐什么，不构成任何功能上的墙。 */
    enum class Persona(val storageKey: String) {
        /** 就是聊聊天。 */
        CHAT("chat"),

        /** 角色扮演。 */
        ROLEPLAY("roleplay"),

        /** 两种都玩。 */
        BOTH("both"),

        /** 我先随便看看。 */
        JUST_LOOKING("looking"),
    }

    fun isCompleted(context: Context): Boolean =
        context.readBooleanPreference(KEY_COMPLETED, false)

    fun markCompleted(context: Context) {
        context.writeBooleanPreference(KEY_COMPLETED, true)
    }

    fun reset(context: Context) {
        context.getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE)
            .edit { remove(KEY_COMPLETED) }
    }

    /**
     * 引导过程中的临时状态。
     *
     * 刻意**不持久化**：用户中途退出 App，下次重新走一遍引导即可。
     * 存一半的状态反而会让人回来时看到莫名其妙的中间页。
     */
    class Session {
        var step by mutableStateOf(Step.WELCOME)
        var persona by mutableStateOf<Persona?>(null)
    }

    /**
     * 引导只有四步，而且**每一步都可以是最后一步**。
     *
     * 配好模型能聊天了，用户随时可以走；不会因为「还没学完导入角色卡」
     * 被扣在引导里。卡着人学完的引导只会被卸载。
     */
    enum class Step {
        /** 一大问：从哪儿来。 */
        WELCOME,

        /** 恢复备份（选文件 → 恢复 → 重启）。 */
        RESTORE,

        /** 选一个模型提供商，填 Key，测通。 */
        PROVIDER,

        /** 可选：角色卡、世界书、联网搜索。随时可退。 */
        EXTRAS,
    }
}
