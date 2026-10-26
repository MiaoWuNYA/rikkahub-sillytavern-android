package me.rerere.rikkahub.service.phone

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.core.content.ContextCompat

/**
 * 读屏 / 手势的宿主服务。
 * 急停三层：ACTION_SCREEN_OFF（主）、POWER 键不消费（辅）、Keyguard 出现（辅）。
 */
class PhoneAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var floatingBar: PhoneFloatingBar? = null

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                PhoneControlHub.pause(PhonePauseReason.SCREEN_OFF)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 不显式请求按键过滤，onKeyEvent 永远收不到电源键，辅层急停形同虚设
        serviceInfo?.let { info ->
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = info
        }
        PhoneControlHub.attach(this)
        floatingBar = PhoneFloatingBar(
            service = this,
            onPause = { PhoneControlHub.pause(PhonePauseReason.USER) },
            onResume = { PhoneControlHub.resume() },
            onStop = { PhoneControlHub.abort() },
        )
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (PhoneControlHub.state.value.phase != PhoneTaskPhase.RUNNING) return
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            PhoneControlHub.pause(PhonePauseReason.KEYGUARD)
        }
    }

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_POWER && event.action == KeyEvent.ACTION_DOWN) {
            PhoneControlHub.pause(PhonePauseReason.POWER_KEY)
        }
        return false // 不消费，否则屏幕无法熄灭
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        PhoneControlHub.detach()
        floatingBar = null
    }

    fun updateOverlay(state: PhoneTaskState) {
        mainHandler.post {
            val bar = floatingBar ?: return@post
            when (state.phase) {
                PhoneTaskPhase.IDLE -> bar.hide()
                else -> bar.show(state)
            }
            if (state.phase == PhoneTaskPhase.PAUSED) {
                PhoneControlNotifier.showPaused(this, state.step, state.stepLimit)
            } else if (state.phase == PhoneTaskPhase.RUNNING) {
                PhoneControlNotifier.showRunning(this, state.appLabel, state.step, state.stepLimit)
            }
        }
    }

    fun hideOverlay() {
        mainHandler.post { floatingBar?.hide() }
    }

    /** 每任务的现场日志：出问题时唯一的复盘依据 */
    fun appendLog(line: String) {
        runCatching {
            val dir = java.io.File(filesDir, "phone_control").apply { mkdirs() }
            val file = java.io.File(dir, "task.log")
            if (file.length() > 256 * 1024) file.delete()
            file.appendText(line + "\n")
        }
    }
}
