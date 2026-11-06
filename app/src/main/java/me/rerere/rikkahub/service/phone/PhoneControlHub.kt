package me.rerere.rikkahub.service.phone

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 手机控制中枢：抓取缓存、元素指纹复核、步数/时间预算、急停。
 * 同一时刻只允许一个动作，任何暂停立即生效。
 */
object PhoneControlHub {
    // 模型一轮思考可能十几秒，太短的空闲窗口会把任务误判结束
    private const val IDLE_FINISH_MS = 20_000L
    private const val SETTLE_MS = 320L
    private const val LOOP_LIMIT = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val actionMutex = Mutex()

    private var service: PhoneAccessibilityService? = null
    private var actuator: PhoneActuator? = null

    private val _state = MutableStateFlow(PhoneTaskState())
    val state: StateFlow<PhoneTaskState> = _state.asStateFlow()

    private var lastScreen: PhoneFormatted? = null
    private var taskStartedAt = 0L
    private var lastSignature: String? = null
    private var repeated = 0
    private var finishJob: Job? = null

    val attached: Boolean get() = service != null && actuator != null

    fun attach(service: PhoneAccessibilityService) {
        this.service = service
        this.actuator = PhoneActuator(service)
        PhoneControlNotifier.ensureChannel(service)
    }

    fun detach() {
        service?.hideOverlay()
        service = null
        actuator = null
        lastScreen = null
        _state.value = PhoneTaskState()
    }

    fun pause(reason: PhonePauseReason) {
        val current = _state.value
        if (current.phase != PhoneTaskPhase.RUNNING) return
        _state.value = current.copy(phase = PhoneTaskPhase.PAUSED, pauseReason = reason)
        service?.updateOverlay(_state.value)
    }

    fun resume() {
        val current = _state.value
        if (current.phase != PhoneTaskPhase.PAUSED) return
        _state.value = current.copy(phase = PhoneTaskPhase.RUNNING, pauseReason = PhonePauseReason.NONE)
        lastScreen = null
        service?.updateOverlay(_state.value)
    }

    fun abort() {
        finishJob?.cancel()
        lastScreen = null
        _state.value = PhoneTaskState()
        service?.hideOverlay()
        service?.let { PhoneControlNotifier.cancel(it) }
    }

    suspend fun screen(detail: Boolean = false): PhoneFormatted = actionMutex.withLock {
        assertRunnable()
        val cap = capture()
        val formatted = PhoneScreenFormat.format(cap, appLabelOf(cap.packageName.orEmpty()), detail = detail)
        lastScreen = formatted
        touchTask(formatted)
        formatted
    }

    suspend fun tap(id: Int, long: Boolean = false): PhoneActionResult =
        withElement(id, if (long) "长按" else "点击") { cap, element ->
            if (element.sensitiveWord != null) {
                throw PhoneControlException(
                    PhoneError.NEED_CONFIRM,
                    "「${element.label}」含敏感词 ${element.sensitiveWord}，先让用户确认"
                )
            }
            val node = cap.native(element.ref) ?: throw PhoneControlException(PhoneError.STALE, "元素已失效，重新读屏")
            val via = actuator!!.click(node, element.bounds, cap.screen, long)
                ?: throw PhoneControlException(PhoneError.ACTION_FAILED, "「${element.label}」点不动")
            "已点击「${element.label}」via=${via.name.lowercase()}"
        }

    suspend fun type(id: Int, text: String, submit: Boolean = false): PhoneActionResult =
        withElement(id, "输入") { cap, element ->
            if (element.password || element.secret) {
                throw PhoneControlException(PhoneError.SECRET, "「${element.label}」是密码/验证码框，不代填，交给用户")
            }
            val node = cap.native(element.ref) ?: throw PhoneControlException(PhoneError.STALE, "输入框已失效，重新读屏")
            if (!actuator!!.setText(node, text)) throw PhoneControlException(PhoneError.ACTION_FAILED, "输入被应用拦截")
            if (submit && !actuator!!.imeEnter(node)) {
                throw PhoneControlException(PhoneError.ACTION_FAILED, "键盘没有发送键，改点发送按钮")
            }
            "已输入「${text.take(24)}」${if (submit) "并提交" else ""}"
        }

    suspend fun scroll(direction: String, id: Int? = null): PhoneActionResult =
        withElement(id, "滚动") { cap, element ->
            val bits = element?.scroll ?: (PhoneScroll.FORWARD or PhoneScroll.BACKWARD)
            val bounds = element?.bounds ?: cap.screen
            val horizontal = element != null && element.scroll and (PhoneScroll.LEFT or PhoneScroll.RIGHT) != 0
            val node = element?.let { cap.native(it.ref) }
            if (!actuator!!.scroll(node, bits, horizontal, bounds, cap.screen, direction)) {
                throw PhoneControlException(PhoneError.ACTION_FAILED, "这个方向滚不动")
            }
            "已向 $direction 滚动"
        }

    suspend fun key(key: String): PhoneActionResult = withElement(null, "按键") { _, _ ->
        if (!actuator!!.global(key)) throw PhoneControlException(PhoneError.BAD_ARGS, "未知按键 $key")
        "已按 $key"
    }

    suspend fun tapAt(x: Int, y: Int): PhoneActionResult = withElement(null, "坐标点击") { _, _ ->
        if (!actuator!!.tap(x, y)) throw PhoneControlException(PhoneError.ACTION_FAILED, "坐标点击没有落地")
        "已点击 $x,$y"
    }

    /** 只读：连续翻页把长列表读全，按文字去重 */
    suspend fun collect(id: Int, maxPages: Int, until: String? = null): String = actionMutex.withLock {
        assertRunnable()
        val active = actuator ?: throw noService()
        val pages = maxPages.coerceIn(1, 30)
        val seen = LinkedHashSet<String>()
        var pagesRead = 0
        while (pagesRead < pages) {
            val cap = capture()
            val formatted = PhoneScreenFormat.format(cap, appLabelOf(cap.packageName.orEmpty()))
            lastScreen = formatted
            val list = formatted.element(id)
                ?: throw PhoneControlException(PhoneError.STALE, "列表 $id 不在当前屏幕，先 phone_screen")
            formatted.elements.filter { it.listId == list.id }.forEach {
                if (it.fullText.isNotBlank()) {
                    seen +=
                        it.fullText
                }
            }
            pagesRead++
            if (until != null && seen.any { it.contains(until, ignoreCase = true) }) break
            val node = cap.native(list.ref)
            val moved = active.scroll(node, list.scroll, list.horizontal, list.bounds, cap.screen, "down")
            if (!moved) break
            delay(SETTLE_MS * 2)
        }
        "ok=true pages=$pagesRead count=${seen.size}\n" + seen.joinToString("\n")
    }

    // 打开应用/链接不依赖读屏：不要求本机不在前台，也不要求先 phone_screen
    suspend fun launch(query: String): PhoneActionResult = withGlobalAction("打开应用") { svc ->
        val pkg = resolvePackage(query) ?: throw PhoneControlException(PhoneError.NOT_FOUND, "没找到应用「$query」")
        val intent = svc.packageManager.getLaunchIntentForPackage(pkg)
            ?: throw PhoneControlException(PhoneError.NOT_FOUND, "「$query」没有可启动的界面")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { svc.startActivity(intent) }
            .onFailure { throw PhoneControlException(PhoneError.ACTION_FAILED, "启动失败：${it.message}") }
        "已打开 ${appLabelOf(pkg)}"
    }

    suspend fun openUrl(url: String): PhoneActionResult = withGlobalAction("打开链接") { svc ->
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { svc.startActivity(intent) }
            .onFailure { throw PhoneControlException(PhoneError.ACTION_FAILED, "打不开：${it.message}") }
        "已打开 $url"
    }

    fun listApps(query: String?): String {
        val svc = service ?: throw noService()
        val pm = svc.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val items = pm.queryIntentActivities(intent, 0).mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = runCatching {
                pm.getApplicationLabel(
                    pm.getApplicationInfo(pkg, 0)
                ).toString()
            }.getOrDefault(pkg)
            label to pkg
        }.distinctBy { it.second }
        val filtered = if (query.isNullOrBlank()) {
            items
        } else {
            items.filter {
                it.first.contains(query, ignoreCase = true) || it.second.contains(query, ignoreCase = true)
            }
        }
        if (filtered.isEmpty()) return "ok=true count=0"
        return "ok=true count=${filtered.size}\n" +
            filtered.take(40).joinToString("\n") { "${it.first} · ${it.second}" }
    }

    suspend fun screenshot(): File {
        val svc = service ?: throw noService()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw PhoneControlException(PhoneError.UNSUPPORTED, "系统低于 Android 11，截不了图")
        }
        val bitmap = takeScreenshot(svc)
        val scaled = downscale(bitmap, 900)
        val dir = File(svc.filesDir, "phone").apply { mkdirs() }
        val file = File(dir, "shot.png")
        file.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 90, it) }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return file
    }

    // 全局动作：不读屏不验元素，动作后读一次屏，读不到（例如本机还在前台）也不报错
    private suspend fun withGlobalAction(
        label: String,
        block: suspend (PhoneAccessibilityService) -> String,
    ): PhoneActionResult = actionMutex.withLock {
        assertRunnable()
        val svc = service ?: throw noService()
        bumpStep(label)
        val message = block(svc)
        delay(SETTLE_MS)
        val screenAfter = runCatching {
            val after = capture()
            PhoneScreenFormat.format(after, appLabelOf(after.packageName.orEmpty()))
        }.getOrNull()
        if (screenAfter != null) gainScreen(screenAfter)
        PhoneActionResult(message, screenAfter != null, screenAfter)
    }

    private fun gainScreen(screenAfter: PhoneFormatted) {
        lastScreen = screenAfter
        touchTask(screenAfter)
    }
    private suspend fun withElement(
        id: Int?,
        label: String,
        block: suspend (PhoneCapture, PhoneElement) -> String,
    ): PhoneActionResult = actionMutex.withLock {
        assertRunnable()
        val previous = lastScreen ?: throw PhoneControlException(PhoneError.STALE, "先调用 phone_screen 读屏")
        val cap = capture()
        checkBlocked(cap)
        val formatted = PhoneScreenFormat.format(cap, appLabelOf(cap.packageName.orEmpty()))
        val element = if (id == null) {
            syntheticElement(cap)
        } else {
            val shown = previous.element(id)
                ?: throw PhoneControlException(PhoneError.STALE, "编号 $id 不在上一次读屏里，重新读屏")
            formatted.elements.firstOrNull { it.fingerprint == shown.fingerprint }
                ?: throw PhoneControlException(PhoneError.STALE, "界面已变化，编号失效，请重新读屏")
        }
        guardLoop(cap, label, element)
        bumpStep("$label${if (id != null) "「${element.label.take(20)}」" else ""}")
        val message = block(cap, element)
        delay(SETTLE_MS)
        val after = capture()
        val screenAfter = PhoneScreenFormat.format(after, appLabelOf(after.packageName.orEmpty()))
        lastScreen = screenAfter
        touchTask(screenAfter)
        PhoneActionResult(message, formatted.text != screenAfter.text, screenAfter)
    }

    /** 没有元素可指时给动作一个占位目标，只为记录与循环判定 */
    private fun syntheticElement(cap: PhoneCapture): PhoneElement = PhoneElement(
        id = 0,
        windowId = cap.windows.firstOrNull()?.id ?: 0,
        ref = -1,
        kind = PhoneElementKind.ITEM,
        role = "",
        label = cap.packageName.orEmpty(),
        fullText = "",
        bounds = cap.screen,
        clickable = false,
        longClickable = false,
        editable = false,
        password = false,
        secret = false,
        checkable = false,
        scroll = 0,
        horizontal = false,
        listId = null,
        parentId = null,
        sensitiveWord = null,
        fingerprint = "w0|${cap.screen}|${cap.packageName}",
    )

    private fun capture(): PhoneCapture {
        val svc = service ?: throw noService()
        val metrics = svc.resources.displayMetrics
        val screen = PhoneBox(0, 0, metrics.widthPixels, metrics.heightPixels)
        val cap = PhoneTreeCapture.capture(svc, screen, svc.packageName)
        if (cap.windows.isEmpty()) throw PhoneControlException(PhoneError.NO_WINDOW, "当前没有可读窗口（锁屏或息屏）")
        if (cap.ownAppInFront) throw PhoneControlException(PhoneError.NO_WINDOW, "RikkaHub 自己在最前面，先切到目标应用")
        return cap
    }

    private fun appLabelOf(pkg: String): String {
        if (pkg.isBlank()) return "未知应用"
        return runCatching {
            val pm = service!!.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
    }

    private fun resolvePackage(query: String): String? {
        val svc = service ?: return null
        val pm = svc.packageManager
        if (runCatching { pm.getPackageInfo(query, 0) }.isSuccess) return query
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = runCatching {
                pm.getApplicationLabel(
                    pm.getApplicationInfo(pkg, 0)
                ).toString()
            }.getOrDefault(pkg)
            label to pkg
        }.firstOrNull {
            it.first.equals(
                query,
                ignoreCase = true
            ) || it.first.contains(query, ignoreCase = true)
        }?.second
    }

    private fun checkBlocked(cap: PhoneCapture) {
        val pkg = cap.packageName.orEmpty()
        if (PhonePackageGuard.isBlocked(pkg)) {
            throw PhoneControlException(PhoneError.BLOCKED_PKG, "「${appLabelOf(pkg)}」在禁用名单内，不执行任何操作")
        }
    }

    private fun guardLoop(cap: PhoneCapture, label: String, element: PhoneElement) {
        val signature = "${cap.packageName}|${element.fingerprint}|$label"
        if (signature == lastSignature) {
            repeated++
        } else {
            repeated = 0
            lastSignature = signature
        }
        if (repeated >= LOOP_LIMIT) {
            throw PhoneControlException(PhoneError.LOOP, "同样动作连续 $LOOP_LIMIT 次且界面没变，换个思路")
        }
    }

    private fun bumpStep(label: String) {
        val current = _state.value
        val step = current.step + 1
        if (step > current.stepLimit) throw PhoneControlException(PhoneError.BUDGET, "已达步数上限 ${current.stepLimit}")
        if (taskStartedAt > 0 && System.currentTimeMillis() - taskStartedAt > PhoneTaskState.DEFAULT_TIME_LIMIT_MS) {
            throw PhoneControlException(PhoneError.BUDGET, "已达时间上限 10 分钟")
        }
        _state.value = current.copy(
            step = step,
            phase = if (current.phase == PhoneTaskPhase.PAUSED) PhoneTaskPhase.PAUSED else PhoneTaskPhase.RUNNING,
            currentAction = label,
            history = (current.history + "$step. $label").takeLast(5),
        )
        service?.updateOverlay(_state.value)
        scheduleFinish()
    }

    private fun touchTask(formatted: PhoneFormatted) {
        val current = _state.value
        if (current.phase == PhoneTaskPhase.IDLE || taskStartedAt == 0L) taskStartedAt = System.currentTimeMillis()
        _state.value = current.copy(
            phase = if (current.phase == PhoneTaskPhase.PAUSED) PhoneTaskPhase.PAUSED else PhoneTaskPhase.RUNNING,
            appLabel = formatted.appLabel,
        )
        service?.updateOverlay(_state.value)
        scheduleFinish()
    }

    private fun assertRunnable() {
        if (_state.value.phase == PhoneTaskPhase.PAUSED) {
            throw PhoneControlException(PhoneError.PAUSED, "任务已被${_state.value.pauseReason.label}，等用户点继续")
        }
    }

    private fun noService() = PhoneControlException(PhoneError.NO_SERVICE, "无障碍服务未开启：系统设置-无障碍 打开「手机控制」")

    private fun scheduleFinish() {
        finishJob?.cancel()
        finishJob = scope.launch {
            delay(IDLE_FINISH_MS)
            if (_state.value.phase == PhoneTaskPhase.RUNNING) {
                val finished = _state.value
                _state.value = PhoneTaskState()
                lastScreen = null
                taskStartedAt = 0L
                service?.hideOverlay()
                service?.let { PhoneControlNotifier.notifyDone(it, finished.step) }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeScreenshot(svc: AccessibilityService): Bitmap = suspendCancellableCoroutine { cont ->
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                try {
                    val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    if (bitmap == null) {
                        if (cont.isActive) {
                            cont.resumeWithException(
                                PhoneControlException(PhoneError.ACTION_FAILED, "截图解码失败")
                            )
                        }
                    } else if (cont.isActive) {
                        cont.resume(bitmap)
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                } finally {
                    runCatching { result.hardwareBuffer.close() }
                }
            }

            override fun onFailure(errorCode: Int) {
                if (cont.isActive) {
                    cont.resumeWithException(
                        PhoneControlException(PhoneError.ACTION_FAILED, "截图失败 code=$errorCode")
                    )
                }
            }
        }
        val sent = runCatching {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, ContextCompat.getMainExecutor(svc), callback)
        }.isSuccess
        if (!sent && cont.isActive) cont.resumeWithException(PhoneControlException(PhoneError.ACTION_FAILED, "系统拒绝了截图"))
    }

    private fun downscale(bitmap: Bitmap, maxWidth: Int): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val ratio = maxWidth.toFloat() / bitmap.width
        return Bitmap.createScaledBitmap(bitmap, maxWidth, (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
    }
}
