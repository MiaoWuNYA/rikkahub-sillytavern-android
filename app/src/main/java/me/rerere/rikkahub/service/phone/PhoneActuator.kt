package me.rerere.rikkahub.service.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 动作执行：优先节点动作，其次可点祖先，最后才手势；坐标只在进程内使用 */
class PhoneActuator(private val service: AccessibilityService) {

    enum class Via { NODE, ANCESTOR, GESTURE }

    suspend fun click(node: AccessibilityNodeInfo, bounds: PhoneBox, screen: PhoneBox, long: Boolean): Via? {
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        fun able(n: AccessibilityNodeInfo) = n.isEnabled && if (long) n.isLongClickable else n.isClickable
        if (able(node) && runCatching { node.performAction(action) }.getOrDefault(false)) return Via.NODE
        val rect = Rect()
        var parent: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (parent != null && depth < 4) {
            parent.getBoundsInScreen(rect)
            if (able(parent) && PhoneBox.of(rect).contains(bounds.centerX, bounds.centerY)) {
                if (runCatching { parent.performAction(action) }.getOrDefault(false)) return Via.ANCESTOR
                break
            }
            parent = parent.parent
            depth++
        }
        val point = visiblePoint(bounds, screen) ?: return null
        return if (tap(point.first, point.second, long)) Via.GESTURE else null
    }

    private fun visiblePoint(bounds: PhoneBox, screen: PhoneBox): Pair<Int, Int>? {
        val clipped = bounds.intersect(screen)
        if (clipped.isEmpty) return null
        return clipped.centerX to clipped.centerY
    }

    suspend fun tap(x: Int, y: Int, long: Boolean = false): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val duration = if (long) ViewConfiguration.getLongPressTimeout().toLong() + 300 else 60L
        return gesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        )
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 350): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return gesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        )
    }

    private suspend fun gesture(description: GestureDescription): Boolean = suspendCancellableCoroutine { cont ->
        var completed = false
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed = true
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(completed)
            }
        }
        val sent = runCatching { service.dispatchGesture(description, callback, null) }.getOrDefault(false)
        if (!sent && cont.isActive) cont.resume(false)
    }

    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.isFocused) runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        if (runCatching {
                node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    args
                )
            }.getOrDefault(false)) {
            return true
        }
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        runCatching { node.refresh() }
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
    }

    /** 键盘动作键（搜索 / 发送 / 完成）：API 30 起 */
    fun imeEnter(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val id = AccessibilityAction.ACTION_IME_ENTER.id
        runCatching { node.refresh() }
        if (node.actionList.none { it.id == id }) return false
        return runCatching { node.performAction(id) }.getOrDefault(false)
    }

    suspend fun scroll(
        node: AccessibilityNodeInfo?,
        bits: Int,
        horizontal: Boolean,
        bounds: PhoneBox,
        screen: PhoneBox,
        direction: String,
    ): Boolean {
        if (node != null) {
            scrollAction(bits, horizontal, direction)?.let { action ->
                if (runCatching { node.performAction(action) }.getOrDefault(false)) return true
            }
        }
        val area = bounds.intersect(PhoneBox(
            screen.left,
            screen.top + screen.height / 20,
            screen.right,
            screen.bottom - screen.height * 6 / 100
        ))
        if (area.isEmpty || area.height < 40 || area.width < 40) return false
        val cx = area.centerX
        val cy = area.centerY
        val dy = area.height * 3 / 10
        val dx = area.width * 3 / 10
        return when (direction) {
            "down" -> swipe(cx, cy + dy, cx, cy - dy)
            "up" -> swipe(cx, cy - dy, cx, cy + dy)
            "right" -> swipe(cx + dx, cy, cx - dx, cy)
            "left" -> swipe(cx - dx, cy, cx + dx, cy)
            else -> false
        }
    }

    private fun scrollAction(bits: Int, horizontal: Boolean, direction: String): Int? = when (direction) {
        "down" -> when {
            bits and PhoneScroll.DOWN != 0 -> AccessibilityAction.ACTION_SCROLL_DOWN.id
            !horizontal && bits and PhoneScroll.FORWARD != 0 -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else -> null
        }
        "up" -> when {
            bits and PhoneScroll.UP != 0 -> AccessibilityAction.ACTION_SCROLL_UP.id
            !horizontal && bits and PhoneScroll.BACKWARD != 0 -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> null
        }
        "right" -> when {
            bits and PhoneScroll.RIGHT != 0 -> AccessibilityAction.ACTION_SCROLL_RIGHT.id
            horizontal && bits and PhoneScroll.FORWARD != 0 -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else -> null
        }
        "left" -> when {
            bits and PhoneScroll.LEFT != 0 -> AccessibilityAction.ACTION_SCROLL_LEFT.id
            horizontal && bits and PhoneScroll.BACKWARD != 0 -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> null
        }
        else -> null
    }

    fun global(key: String): Boolean = service.performGlobalAction(
        when (key) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> return false
        },
    )
}
