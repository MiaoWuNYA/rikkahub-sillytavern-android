package me.rerere.rikkahub.service.phone

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** 上屏角色单字符：文本 / 可点 / 输入 / 可选 / 可滚动 / 未知可交互 */
enum class PhoneNodeRole(val code: Char) {
    TEXT('t'),
    BUTTON('b'),
    INPUT('i'),
    CHECK('c'),
    SCROLL('s'),
    UNKNOWN('x'),
}

data class PhoneScreenNode(
    val index: Int,
    val role: PhoneNodeRole,
    val text: String,
    val bounds: Rect,
    val node: AccessibilityNodeInfo?,
    val editable: Boolean = false,
)

data class PhoneScreenSnapshot(
    val packageName: String,
    val appLabel: String,
    val width: Int,
    val height: Int,
    val nodes: List<PhoneScreenNode>,
    val lines: List<String>,
    val fingerprint: String,
    val truncated: Boolean = false,
    val split: Boolean = false,
) {
    /** 上屏文本：header + 序号行，字段序固定 */
    fun render(): String = buildString {
        append("pkg=").append(appLabel)
        append(" scr=").append(width).append('x').append(height)
        append(" n=").append(nodes.size)
        if (split) append(" split=true")
        nodes.forEach {
            append('\n')
            append(it.index).append('|').append(it.role.code).append('|').append(it.text)
        }
        if (truncated) append("\n…还有更多行，可 scroll")
    }
}

enum class PhonePauseReason(val label: String) {
    NONE(""),
    USER("用户暂停"),
    SCREEN_OFF("熄屏暂停"),
    POWER_KEY("电源键暂停"),
    KEYGUARD("锁屏暂停"),
}

enum class PhoneTaskPhase { IDLE, RUNNING, PAUSED }

data class PhoneTaskState(
    val phase: PhoneTaskPhase = PhoneTaskPhase.IDLE,
    val appLabel: String = "",
    val step: Int = 0,
    val stepLimit: Int = DEFAULT_STEP_LIMIT,
    val currentAction: String = "",
    val pauseReason: PhonePauseReason = PhonePauseReason.NONE,
    val history: List<String> = emptyList(),
) {
    val paused: Boolean get() = phase == PhoneTaskPhase.PAUSED

    companion object {
        const val DEFAULT_STEP_LIMIT = 12
        const val DEFAULT_TIME_LIMIT_MS = 120_000L
    }
}

/** 工具结果错误码，模型按码决策：见设计文档 5.4 */
enum class PhoneError(val code: String) {
    NO_SERVICE("NO_SERVICE"),
    NO_WINDOW("NO_WINDOW"),
    NO_TEXT("NO_TEXT"),
    NOT_FOUND("NOT_FOUND"),
    STALE("STALE"),
    BLOCKED_PKG("BLOCKED_PKG"),
    TIMEOUT("TIMEOUT"),
    BUDGET("BUDGET"),
    PAUSED("PAUSED"),
    DUPLICATE("DUPLICATE"),
    BAD_ARGS("BAD_ARGS"),
    ACTION_FAILED("ACTION_FAILED"),
}

class PhoneControlException(val error: PhoneError, message: String) : Exception(message)

/** 包名黑名单：支付 / 银行 / 证券 / 输入法与系统账户安全页不做任何手势 */
object PhonePackageGuard {
    // 支付 / 银行 / 证券 / 输入法设置整包禁用；系统设置不整包禁用（关通知这类正常任务要用），
    // 危险页面靠动作关键词审批拦截。
    private val blockedPrefixes = listOf(
        "com.eg.android.AlipayGphone",
        "com.unionpay",
        "com.icbc",
        "com.ccb",
        "com.cmbchina",
        "com.chinamworld.main",
        "com.antfortune.wealth",
        "com.android.inputmethod",
        "com.sohu.inputmethod",
        "com.baidu.input",
        "com.iflytek.inputmethod",
        "com.tencent.qqpinyin",
    )

    fun isBlocked(pkg: String): Boolean = blockedPrefixes.any { pkg.startsWith(it, ignoreCase = true) }
}
