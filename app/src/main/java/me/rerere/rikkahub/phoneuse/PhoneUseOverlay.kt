package me.rerere.rikkahub.phoneuse

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R

private const val TAG = "PhoneUseOverlay"

/**
 * 顶部小条：AI 在别的应用里操作时一直挂着，用户随时能看到正在做什么并停下。
 * 用无障碍服务自己的覆盖层，不需要悬浮窗权限；执行手势和截图期间自动让位。
 */
class PhoneUseOverlay(private val service: AccessibilityService, private val manager: PhoneUseManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var label: TextView? = null
    private var detail: TextView? = null
    private var action: TextView? = null

    fun start() {
        scope.launch {
            // 任务期间每秒刷新一次，模型思考时间太长就当作已经结束
            ticker.collect {
                val idle = manager.activeUntil.value <= SystemClock.elapsedRealtime()
                if (manager.controller.value == null || manager.acting.value || idle) {
                    hide()
                } else {
                    show(manager.stopRequested.value)
                }
            }
        }
    }

    private val ticker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(1_000L)
        }
    }

    fun stop() {
        scope.cancel()
        hide()
    }

    private fun show(stopped: Boolean) {
        val container = root ?: build().also { root = it; attach(it) }
        label?.text = service.getString(
            if (stopped) R.string.phone_use_overlay_stopped else R.string.phone_use_overlay_running
        )
        detail?.text = service.getString(
            if (stopped) R.string.phone_use_overlay_resume_hint else R.string.phone_use_overlay_stop_hint
        )
        action?.apply {
            text = service.getString(
                if (stopped) R.string.phone_use_overlay_resume else R.string.phone_use_overlay_stop
            )
            setOnClickListener { if (stopped) manager.clearStop() else manager.requestStop() }
        }
        container.alpha = 1f
    }

    private fun build(): LinearLayout {
        val density = service.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val container = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#F0202124"))
            }
            setPadding(dp(14), dp(8), dp(8), dp(8))
        }
        val texts = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        label = TextView(service).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        detail = TextView(service).apply {
            setTextColor(Color.parseColor("#A8ADB4"))
            textSize = 10f
        }
        texts.addView(label)
        texts.addView(detail)
        action = TextView(service).apply {
            setTextColor(Color.parseColor("#FFB4AB"))
            textSize = 12f
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        container.addView(texts)
        container.addView(action)
        return container
    }

    private fun attach(view: View) {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 96 }
        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "attach: failed to add overlay", e)
        }
    }

    private fun hide() {
        val view = root ?: return
        root = null
        runCatching { windowManager.removeViewImmediate(view) }
    }
}
