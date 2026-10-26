package me.rerere.rikkahub.service.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 动作执行：所有坐标只在本进程内使用，永不进 prompt */
class PhoneActionEngine(
    private val service: AccessibilityService,
    private val reader: PhoneScreenReader,
    private val shouldAbort: () -> Boolean = { false },
) {
    suspend fun tap(snapshot: PhoneScreenSnapshot, index: Int, longPress: Boolean = false): String {
        val target = snapshot.nodes.firstOrNull { it.index == index }
            ?: throw PhoneControlException(PhoneError.STALE, "序号 $index 不在当前屏幕，需要重新读屏")
        val node = target.node
        if (node != null && !longPress) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return target.text
            val ancestor = clickableAncestor(node)
            if (ancestor != null && ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return target.text
        }
        val rect = node?.let { boundsOf(it) } ?: target.bounds
        if (rect.width() <= 0 || rect.height() <= 0) {
            throw PhoneControlException(PhoneError.ACTION_FAILED, "目标「${target.text}」没有可点击区域")
        }
        dispatchGesture(rect.centerX().toFloat(), rect.centerY().toFloat(), rect.centerX().toFloat(), rect.centerY().toFloat(), if (longPress) 650 else 60)
        return target.text
    }

    suspend fun input(snapshot: PhoneScreenSnapshot, index: Int, text: String): String {
        val target = snapshot.nodes.firstOrNull { it.index == index }
            ?: throw PhoneControlException(PhoneError.STALE, "序号 $index 不在当前屏幕，需要重新读屏")
        val editable = findEditable(target.node)
            ?: throw PhoneControlException(PhoneError.ACTION_FAILED, "「${target.text}」不是输入框")
        editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (setText(editable, text)) return if (editable.isPassword) "****" else text
        setText(editable, "")
        if (setText(editable, text)) return if (editable.isPassword) "****" else text
        if (pasteText(editable, text)) return if (editable.isPassword) "****" else text
        throw PhoneControlException(PhoneError.ACTION_FAILED, "输入失败，可能被应用拦截")
    }

    suspend fun scroll(snapshot: PhoneScreenSnapshot, index: Int?, forward: Boolean): String {
        val scrollNode = snapshot.nodes.firstOrNull { it.index == index }?.node
            ?: snapshot.nodes.firstOrNull { it.node?.isScrollable == true }?.node
        if (scrollNode != null) {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (scrollNode.performAction(action)) return if (forward) "向下滚动" else "向上滚动"
        }
        val width = snapshot.width.toFloat()
        val height = snapshot.height.toFloat()
        val x = width / 2
        val from = if (forward) height * 0.72f else height * 0.32f
        val to = if (forward) height * 0.32f else height * 0.72f
        dispatchGesture(x, from, x, to, 320)
        return if (forward) "向下滚动" else "向上滚动"
    }

    suspend fun back(): String {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        return "返回"
    }

    /** 等界面静默：连续两次指纹一致且间隔 ≥150ms 视为稳定，总上限 1200ms */
    suspend fun waitIdle(previousFingerprint: String?): Pair<PhoneScreenSnapshot, Boolean> {
        val start = System.currentTimeMillis()
        var last = reader.capture()
        var stableSince = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < IDLE_LIMIT_MS) {
            if (shouldAbort()) break
            delay(IDLE_POLL_MS)
            val next = runCatching { reader.capture() }.getOrNull() ?: break
            if (next.fingerprint == last.fingerprint) {
                if (System.currentTimeMillis() - stableSince >= IDLE_STABLE_MS) {
                    return next to (previousFingerprint != null && next.fingerprint != previousFingerprint)
                }
            } else {
                last = next
                stableSince = System.currentTimeMillis()
            }
        }
        return last to (previousFingerprint != null && last.fingerprint != previousFingerprint)
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun pasteText(node: AccessibilityNodeInfo, text: String): Boolean {
        val manager = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val backup = runCatching { manager.primaryClip }.getOrNull()
        return try {
            manager.setPrimaryClip(ClipData.newPlainText("rikkahub", text))
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (backup != null && backup.itemCount > 0) {
                runCatching { manager.setPrimaryClip(backup) }
            }
            ok
        } catch (_: Exception) {
            false
        }
    }

    /** 目标行常常是包着输入框的可点容器，所以先自身、再向下找子节点、最后才往父链上找 */
    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isEditable) return node
        findEditableChild(node, depth = 0)?.let { return it }
        var current = node.parent
        var guard = 0
        while (current != null && guard++ < 6) {
            if (current.isEditable) return current
            current = current.parent
        }
        return null
    }

    private fun findEditableChild(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 3) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isEditable) return child
            findEditableChild(child, depth + 1)?.let { return it }
        }
        return null
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent
        var guard = 0
        while (current != null && guard++ < 6) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect = Rect().also { node.getBoundsInScreen(it) }

    private suspend fun dispatchGesture(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long,
    ) = suspendCancellableCoroutine { continuation ->
        val path = Path().apply {
            moveTo(fromX, fromY)
            if (fromX != toX || fromY != toY) lineTo(toX, toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (continuation.isActive) continuation.resume(Unit)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
        val dispatched = runCatching { service.dispatchGesture(gesture, callback, null) }.getOrDefault(false)
        if (!dispatched && continuation.isActive) continuation.resume(Unit)
    }

    companion object {
        private const val IDLE_POLL_MS = 120L
        private const val IDLE_STABLE_MS = 150L
        private const val IDLE_LIMIT_MS = 1200L
    }
}
