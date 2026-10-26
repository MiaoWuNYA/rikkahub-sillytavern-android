package me.rerere.rikkahub.service.phone

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 悬浮胶囊：三行内容 + 暂停/结束按钮。
 * 用 TYPE_ACCESSIBILITY_OVERLAY（不需 SYSTEM_ALERT_WINDOW），失败再退回应用悬浮窗。
 * 窗口永远 NOT_FOCUSABLE，否则目标 App 输入框会失焦。
 */
class PhoneFloatingBar(
    private val service: AccessibilityService,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val onStop: () -> Unit,
) {
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var statusText: TextView? = null
    private var actionText: TextView? = null
    private var historyText: TextView? = null
    private var toggleButton: TextView? = null

    private val density: Float get() = service.resources.displayMetrics.density

    fun isShowing(): Boolean = root != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(state: PhoneTaskState) {
        if (root != null) {
            update(state)
            return
        }
        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.parseColor("#F0202124"), 18f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        statusText = TextView(service).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        actionText = TextView(service).apply {
            setTextColor(Color.parseColor("#CFD3D8"))
            textSize = 12f
            maxLines = 1
        }
        historyText = TextView(service).apply {
            setTextColor(Color.parseColor("#A8ADB4"))
            textSize = 11f
            visibility = View.GONE
        }
        val buttons = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        toggleButton = actionButton("暂停") { onPause() }
        val stopButton = actionButton("结束") { onStop() }
        buttons.addView(toggleButton)
        buttons.addView(stopButton)
        container.addView(statusText)
        container.addView(actionText)
        container.addView(historyText)
        container.addView(buttons)
        // 拖动只挂在文本区：按钮区保持正常点击，down 事件不被父容器吞掉
        val dragger = dragListener()
        statusText?.setOnTouchListener(dragger)
        actionText?.setOnTouchListener(dragger)

        val wm = service.getSystemService(WindowManager::class.java)
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = (service.resources.displayMetrics.heightPixels * 0.16f).toInt()
        }
        val added = runCatching { wm.addView(container, layoutParams) }.isSuccess
        if (!added) return
        root = container
        params = layoutParams
        update(state)
    }

    fun update(state: PhoneTaskState) {
        val container = root ?: return
        val paused = state.phase == PhoneTaskPhase.PAUSED
        statusText?.text = buildString {
            append(if (paused) "● 已暂停" else "● 运行中")
            if (state.appLabel.isNotBlank()) append(" · ").append(state.appLabel)
            append(" · 第 ").append(state.step).append('/').append(state.stepLimit).append(" 步")
        }
        actionText?.text = when {
            paused -> state.pauseReason.label + " · 点继续"
            state.currentAction.isNotBlank() -> state.currentAction
            else -> "准备中"
        }
        historyText?.text = state.history.takeLast(4).joinToString("\n")
        toggleButton?.text = if (paused) "继续" else "暂停"
        toggleButton?.setOnClickListener { if (paused) onResume() else onPause() }
        statusText?.setTextColor(if (paused) Color.parseColor("#FF8A80") else Color.WHITE)
        container.background = rounded(
            if (paused) Color.parseColor("#F0332325") else Color.parseColor("#F0202124"),
            18f,
        )
        val wm = service.getSystemService(WindowManager::class.java)
        runCatching { wm.updateViewLayout(container, params) }
    }

    fun hide() {
        val container = root ?: return
        val wm = service.getSystemService(WindowManager::class.java)
        runCatching { wm.removeView(container) }
        root = null
        params = null
    }

    private fun windowType(): Int = try {
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    } catch (_: Throwable) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    }

    private fun actionButton(label: String, onClick: () -> Unit): TextView = TextView(service).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 12f
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = rounded(Color.parseColor("#33FFFFFF"), 12f)
        isClickable = true
        isFocusable = false
        setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(8) }
        layoutParams = lp
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun dragListener() = View.OnTouchListener { view, event ->
        val wm = service.getSystemService(WindowManager::class.java)
        val layoutParams = params ?: return@OnTouchListener false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = layoutParams.x
                startY = layoutParams.y
                moved = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) moved = true
                if (moved) {
                    layoutParams.x = (startX + dx).toInt().coerceAtLeast(0)
                    layoutParams.y = (startY + dy).toInt().coerceAtLeast(0)
                    runCatching { wm.updateViewLayout(view, layoutParams) }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    val visible = historyText?.visibility == View.VISIBLE
                    historyText?.visibility = if (visible) View.GONE else View.VISIBLE
                    true
                } else {
                    true
                }
            }
            else -> false
        }
    }

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
