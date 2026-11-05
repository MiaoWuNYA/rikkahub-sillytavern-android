package me.rerere.rikkahub.service.phone

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
        const val DEFAULT_STEP_LIMIT = 40
        const val DEFAULT_TIME_LIMIT_MS = 600_000L
    }
}

/** 工具结果错误码，模型按码决策 */
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
    LOOP("LOOP"),
    UNSUPPORTED("UNSUPPORTED"),
    BAD_ARGS("BAD_ARGS"),
    NEED_CONFIRM("NEED_CONFIRM"),
    SECRET("SECRET"),
    ACTION_FAILED("ACTION_FAILED"),
}

class PhoneControlException(val error: PhoneError, message: String) : Exception(message)

class PhoneActionResult(
    val message: String,
    val changed: Boolean,
    val screen: PhoneFormatted?,
)

/** 包名黑名单：支付 / 银行 / 证券 / 输入法整包不做手势 */
object PhonePackageGuard {
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
