package me.rerere.rikkahub.service.phone

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PhoneActionRequest(
    val action: String,
    val target: String? = null,
    val text: String? = null,
    val direction: String? = null,
)

data class PhoneActionResult(
    val message: String,
    val changed: Boolean,
    val screen: PhoneScreenSnapshot?,
)

/**
 * 手机控制中枢：状态机、步数/时间预算、互斥、急停、快照缓存。
 * 只允许一个任务持有锁，任何时候 pause 都立刻生效。
 */
object PhoneControlHub {
    // 模型一轮思考就可能十几秒，太短的空闲窗口会把任务误判结束、步数归零
    private const val IDLE_FINISH_MS = 20_000L
    private const val DUPLICATE_WINDOW_MS = 3_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val actionMutex = Mutex()

    private var service: PhoneAccessibilityService? = null
    private var reader: PhoneScreenReader? = null
    private var engine: PhoneActionEngine? = null

    private val _state = MutableStateFlow(PhoneTaskState())
    val state: StateFlow<PhoneTaskState> = _state.asStateFlow()

    private var snapshot: PhoneScreenSnapshot? = null
    private var taskStartedAt = 0L
    private var lastActionKey: String? = null
    private var lastActionAt = 0L
    private var finishJob: Job? = null

    val attached: Boolean get() = service != null && reader != null

    fun attach(service: PhoneAccessibilityService) {
        this.service = service
        this.reader = PhoneScreenReader(service, service.packageName)
        this.engine = PhoneActionEngine(service, this.reader!!) {
            _state.value.phase == PhoneTaskPhase.PAUSED
        }
        PhoneControlNotifier.ensureChannel(service)
    }

    fun detach() {
        service?.hideOverlay()
        service = null
        reader = null
        engine = null
        snapshot = null
        _state.value = PhoneTaskState()
    }

    fun pause(reason: PhonePauseReason) {
        val current = _state.value
        if (current.phase != PhoneTaskPhase.RUNNING) return
        _state.value = current.copy(phase = PhoneTaskPhase.PAUSED, pauseReason = reason)
        // 暂停不丢快照：解锁后必须看到暂停那一刻的屏再继续
        service?.updateOverlay(_state.value)
    }

    fun resume() {
        val current = _state.value
        if (current.phase != PhoneTaskPhase.PAUSED) return
        _state.value = current.copy(phase = PhoneTaskPhase.RUNNING, pauseReason = PhonePauseReason.NONE)
        // 恢复时必须重新抓「暂停那一刻之后」的屏，不能凭缓存续跑
        snapshot = null
        service?.updateOverlay(_state.value)
    }

    fun abort() {
        finishJob?.cancel()
        snapshot = null
        _state.value = PhoneTaskState()
        service?.hideOverlay()
        service?.let { PhoneControlNotifier.cancel(it) }
    }

    suspend fun readScreen(force: Boolean = false): PhoneScreenSnapshot {
        if (engine == null) throw PhoneControlException(PhoneError.NO_SERVICE, "无障碍服务未开启：请到 系统设置-无障碍 打开「手机控制」")
        assertRunnable()
        val cached = snapshot
        if (!force && cached != null) {
            val fresh = runCatching { reader!!.capture() }.getOrNull()
            if (fresh != null && fresh.fingerprint == cached.fingerprint) return cached
            if (fresh != null) {
                snapshot = fresh
                return fresh
            }
        }
        val fresh = reader!!.capture()
        if (PhonePackageGuard.isBlocked(fresh.packageName)) {
            throw PhoneControlException(PhoneError.BLOCKED_PKG, "「${fresh.appLabel}」在禁用名单内，不执行任何操作")
        }
        snapshot = fresh
        touchTask(fresh.appLabel)
        return fresh
    }

    suspend fun perform(request: PhoneActionRequest): PhoneActionResult = actionMutex.withLock {
        val active = engine ?: throw PhoneControlException(PhoneError.NO_SERVICE, "无障碍服务未开启：请到 系统设置-无障碍 打开「手机控制」")
        assertRunnable()
        val current = snapshot ?: readScreen(force = true)
        if (PhonePackageGuard.isBlocked(current.packageName)) {
            throw PhoneControlException(PhoneError.BLOCKED_PKG, "「${current.appLabel}」在禁用名单内，不执行任何操作")
        }
        guardDuplicate(request)
        val step = _state.value.step + 1
        if (step > _state.value.stepLimit) {
            throw PhoneControlException(PhoneError.BUDGET, "已达步数上限 ${_state.value.stepLimit}，任务结束")
        }
        if (taskStartedAt > 0 && System.currentTimeMillis() - taskStartedAt > PhoneTaskState.DEFAULT_TIME_LIMIT_MS) {
            throw PhoneControlException(PhoneError.BUDGET, "已达时间上限 120 秒，任务结束")
        }
        val label = when (val action = request.action.lowercase()) {
            "tap" -> "正在点击"
            "long_press" -> "正在长按"
            "input" -> "正在输入"
            "scroll" -> "正在滚动"
            "back" -> "正在返回"
            "wait" -> "正在等待"
            else -> throw PhoneControlException(PhoneError.BAD_ARGS, "未知动作：$action")
        }
        val targetText = request.target?.takeIf { it.isNotBlank() } ?: request.text.orEmpty()
        _state.value = _state.value.copy(
            step = step,
            appLabel = current.appLabel,
            currentAction = if (targetText.isBlank()) label else "$label「${targetText.take(24)}」",
        )
        service?.updateOverlay(_state.value)

        service?.appendLog("${System.currentTimeMillis()} pkg=${current.packageName} step=$step action=${request.action} target=${request.target.orEmpty().take(40)}")
        val executed = when (request.action.lowercase()) {
            "tap", "long_press" -> {
                val node = resolveNode(current, request.target)
                active.tap(current, node.index, longPress = request.action.equals("long_press", true))
                "点击「${node.text}」"
            }
            "input" -> {
                val text = request.text ?: throw PhoneControlException(PhoneError.BAD_ARGS, "缺少 text 参数")
                val node = resolveNode(current, request.target)
                active.input(current, node.index, text)
                "已输入「${text.take(24)}」"
            }
            "scroll" -> {
                val forward = !request.direction.equals("up", ignoreCase = true)
                val index = request.target?.toIntOrNull()
                active.scroll(current, index, forward)
                if (forward) "向下滚动" else "向上滚动"
            }
            "back" -> active.back()
            "wait" -> "等待"
            else -> throw PhoneControlException(PhoneError.BAD_ARGS, "未知动作：${request.action}")
        }
        if (request.action.equals("back", true) || request.action.equals("wait", true)) {
            delay(220)
        }
        val (fresh, changed) = runCatching { active.waitIdle(current.fingerprint) }
            .getOrElse { return@withLock PhoneActionResult("$executed（界面读取超时）", false, null) }
        snapshot = fresh
        val history = (_state.value.history + "${_state.value.step}. $executed").takeLast(5)
        // 动作执行期间可能被熄屏/用户暂停，别把 PAUSED 覆盖回 RUNNING
        val stillPaused = _state.value.phase == PhoneTaskPhase.PAUSED
        _state.value = _state.value.copy(
            appLabel = fresh.appLabel,
            phase = if (stillPaused) PhoneTaskPhase.PAUSED else PhoneTaskPhase.RUNNING,
            history = history,
        )
        service?.updateOverlay(_state.value)
        scheduleFinish()
        service?.appendLog("${System.currentTimeMillis()} done changed=$changed pkg=${fresh.packageName} step=$step")
        PhoneActionResult(
            message = executed + if (stillPaused) "（已暂停）" else if (changed) "，界面已变化" else "，界面无变化",
            changed = changed,
            screen = fresh,
        )
    }

    private fun resolveNode(current: PhoneScreenSnapshot, target: String?): PhoneScreenNode {
        if (target.isNullOrBlank()) throw PhoneControlException(PhoneError.BAD_ARGS, "缺少 target（序号或文本）")
        target.trim().toIntOrNull()?.let { index ->
            return current.nodes.firstOrNull { it.index == index }
                ?: throw PhoneControlException(PhoneError.STALE, "序号 $index 已失效，请重新读屏")
        }
        val exact = current.nodes.firstOrNull { it.text.equals(target.trim(), ignoreCase = true) }
        if (exact != null) return exact
        val contains = current.nodes.filter { it.text.contains(target.trim(), ignoreCase = true) }
        if (contains.size == 1) return contains.first()
        if (contains.size > 1) {
            val candidates = contains.take(5).joinToString("；") { "${it.index}|${it.text.take(20)}" }
            throw PhoneControlException(PhoneError.NOT_FOUND, "「$target」有多处匹配，请用序号重试：$candidates")
        }
        val fuzzy = current.nodes.filter { target.trim().contains(it.text, ignoreCase = true) && it.text.isNotBlank() }
        if (fuzzy.size == 1) return fuzzy.first()
        val hint = current.nodes.take(5).joinToString("；") { "${it.index}|${it.text.take(16)}" }
        throw PhoneControlException(PhoneError.NOT_FOUND, "屏幕上没有「$target」，当前前几项：$hint")
    }

    private fun assertRunnable() {
        when (_state.value.phase) {
            PhoneTaskPhase.PAUSED -> throw PhoneControlException(
                PhoneError.PAUSED,
                "任务已被${_state.value.pauseReason.label}，等待用户点继续"
            )
            else -> Unit
        }
    }

    private fun guardDuplicate(request: PhoneActionRequest) {
        if (request.action.equals("scroll", true) || request.action.equals("wait", true)) return
        val key = listOf(request.action.lowercase(), request.target.orEmpty(), request.text.orEmpty()).joinToString("\u0001")
        val now = System.currentTimeMillis()
        if (key == lastActionKey && now - lastActionAt < DUPLICATE_WINDOW_MS) {
            throw PhoneControlException(PhoneError.DUPLICATE, "同一动作 3 秒内重复，已拒绝")
        }
        lastActionKey = key
        lastActionAt = now
    }

    private fun touchTask(appLabel: String) {
        val current = _state.value
        val fresh = current.phase == PhoneTaskPhase.IDLE || taskStartedAt == 0L
        if (fresh) taskStartedAt = System.currentTimeMillis()
        _state.value = current.copy(
            phase = if (current.phase == PhoneTaskPhase.PAUSED) PhoneTaskPhase.PAUSED else PhoneTaskPhase.RUNNING,
            appLabel = appLabel,
        )
        service?.updateOverlay(_state.value)
        scheduleFinish()
    }

    private fun scheduleFinish() {
        finishJob?.cancel()
        finishJob = scope.launch {
            delay(IDLE_FINISH_MS)
            if (_state.value.phase == PhoneTaskPhase.RUNNING) {
                val finished = _state.value
                _state.value = PhoneTaskState()
                snapshot = null
                taskStartedAt = 0L
                service?.hideOverlay()
                service?.let { PhoneControlNotifier.notifyDone(it, finished.step) }
            }
        }
    }
}
