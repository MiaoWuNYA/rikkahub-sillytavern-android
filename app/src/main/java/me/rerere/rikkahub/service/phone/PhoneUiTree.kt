package me.rerere.rikkahub.service.phone

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo

data class PhoneBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val area: Int get() = width * height
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun intersect(other: PhoneBox): PhoneBox =
        PhoneBox(maxOf(left, other.left), maxOf(top, other.top), minOf(right, other.right), minOf(bottom, other.bottom))

    fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom

    fun rowOverlap(other: PhoneBox): Float {
        val h = minOf(bottom, other.bottom) - maxOf(top, other.top)
        val denom = minOf(height, other.height)
        return if (denom <= 0 || h <= 0) 0f else h.toFloat() / denom
    }

    override fun toString(): String = "$left,$top,$right,$bottom"

    companion object {
        fun of(rect: Rect): PhoneBox = PhoneBox(rect.left, rect.top, rect.right, rect.bottom)
    }
}

object PhoneScroll {
    const val FORWARD = 1
    const val BACKWARD = 2
    const val UP = 4
    const val DOWN = 8
    const val LEFT = 16
    const val RIGHT = 32
}

enum class PhoneWindowKind { APP, SYSTEM }

class PhoneUiNode(
    val ref: Int,
    val className: String,
    val text: String?,
    val desc: String?,
    val hint: String?,
    val viewId: String?,
    val bounds: PhoneBox,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val focused: Boolean,
    val enabled: Boolean,
    val password: Boolean,
    val heading: Boolean,
    val showingHint: Boolean,
    val scroll: Int,
    val roleHint: String?,
    val stateDesc: String?,
    val rangeCurrent: Float?,
    val rangeMax: Float?,
    val children: List<PhoneUiNode>,
)

class PhoneUiWindow(
    val id: Int,
    val kind: PhoneWindowKind,
    val packageName: String?,
    val title: String?,
    val bounds: PhoneBox,
    val root: PhoneUiNode?,
)

/** 一次抓取：格式化用的窗口树 + 供动作使用的原生节点表 */
class PhoneCapture(
    val windows: List<PhoneUiWindow>,
    val natives: List<AccessibilityNodeInfo>,
    val keyboardShown: Boolean?,
    val screen: PhoneBox,
    val packageName: String?,
    val ownAppInFront: Boolean,
    val systemInFront: Boolean,
    val truncated: Boolean,
    val unreadableWindowTitle: String? = null,
) {
    fun native(ref: Int): AccessibilityNodeInfo? = natives.getOrNull(ref)
}

object PhoneTreeCapture {
    private const val MAX_NODES = 2500
    private const val MAX_DEPTH = 80
    private const val MAX_WINDOWS = 4

    private val scrollIds by lazy {
        mapOf(
            AccessibilityAction.ACTION_SCROLL_FORWARD.id to PhoneScroll.FORWARD,
            AccessibilityAction.ACTION_SCROLL_BACKWARD.id to PhoneScroll.BACKWARD,
            AccessibilityAction.ACTION_SCROLL_UP.id to PhoneScroll.UP,
            AccessibilityAction.ACTION_SCROLL_DOWN.id to PhoneScroll.DOWN,
            AccessibilityAction.ACTION_SCROLL_LEFT.id to PhoneScroll.LEFT,
            AccessibilityAction.ACTION_SCROLL_RIGHT.id to PhoneScroll.RIGHT,
        )
    }

    private class State(val deadline: Long) {
        val natives = ArrayList<AccessibilityNodeInfo>(256)
        var count = 0
        var truncated = false
        val rect = Rect()
    }

    fun capture(service: AccessibilityService, screen: PhoneBox, ownPackage: String, budgetMs: Long = 600): PhoneCapture {
        val st = State(SystemClock.uptimeMillis() + budgetMs)
        val all = runCatching { service.windows }.getOrNull()
        val sorted = all.orEmpty().sortedByDescending { it.layer }
        val keyboard = all?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val chosen = mutableListOf<PhoneUiWindow>()
        var topPackage: String? = null
        var ownFront = false
        var systemFront = false
        var unreadable: String? = null
        val screenArea = screen.area.coerceAtLeast(1)
        for (w in sorted) {
            when (w.type) {
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> continue
                AccessibilityWindowInfo.TYPE_APPLICATION, AccessibilityWindowInfo.TYPE_SYSTEM -> Unit
                else -> continue
            }
            if (chosen.size >= MAX_WINDOWS) break
            w.getBoundsInScreen(st.rect)
            val bounds = PhoneBox.of(st.rect)
            val share = bounds.intersect(screen).area.toDouble() / screenArea
            if (share <= 0) continue
            if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM && share < 0.5) continue
            val root = w.root ?: activeRoot(service, w.id) ?: continue
            val pkg = root.packageName?.toString()
            if (chosen.isEmpty()) {
                topPackage = pkg
                ownFront = pkg == ownPackage
                systemFront = w.type == AccessibilityWindowInfo.TYPE_SYSTEM
            }
            val title = w.title?.toString()
            val node = convert(root, 0, st)
            val emptyFront = chosen.isEmpty() && w.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                !st.truncated && !usable(node)
            if (emptyFront) unreadable = title ?: ""
            chosen += PhoneUiWindow(
                id = w.id,
                kind = if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM) PhoneWindowKind.SYSTEM else PhoneWindowKind.APP,
                packageName = pkg,
                title = title,
                bounds = bounds,
                root = node,
            )
            if (emptyFront || share >= 0.8) break
        }
        return PhoneCapture(
            windows = chosen,
            natives = st.natives,
            keyboardShown = keyboard,
            screen = screen,
            packageName = topPackage,
            ownAppInFront = ownFront,
            systemInFront = systemFront,
            truncated = st.truncated,
            unreadableWindowTitle = unreadable,
        )
    }

    fun foregroundPackage(service: AccessibilityService, screen: PhoneBox): String? {
        val all = runCatching { service.windows }.getOrNull() ?: return null
        val rect = Rect()
        for (w in all.sortedByDescending { it.layer }) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION && w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            w.getBoundsInScreen(rect)
            val bounds = PhoneBox.of(rect)
            val share = bounds.intersect(screen).area.toDouble() / screen.area.coerceAtLeast(1)
            if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM && share < 0.5) continue
            val root = w.root ?: activeRoot(service, w.id) ?: continue
            return root.packageName?.toString()
        }
        return null
    }

    private fun activeRoot(service: AccessibilityService, windowId: Int): AccessibilityNodeInfo? {
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return null
        return if (root.windowId == windowId) root else null
    }

    /** 有一层子节点带文字或可交互就算读得到；整棵空壳通常是敏感视图策略挡的 */
    private fun usable(node: PhoneUiNode?): Boolean {
        node ?: return false
        if (!node.text.isNullOrBlank() || !node.desc.isNullOrBlank()) return true
        if (node.clickable || node.editable) return true
        return node.children.any { usable(it) }
    }

    private fun convert(node: AccessibilityNodeInfo, depth: Int, st: State): PhoneUiNode? {
        if (!node.isVisibleToUser) return null
        node.getBoundsInScreen(st.rect)
        val bounds = PhoneBox.of(st.rect)
        val ref = st.natives.size
        st.natives += node
        st.count++
        val children = ArrayList<PhoneUiNode>(node.childCount)
        if (depth < MAX_DEPTH) {
            for (i in 0 until node.childCount) {
                if (st.count >= MAX_NODES || SystemClock.uptimeMillis() > st.deadline) {
                    st.truncated = true
                    break
                }
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                convert(child, depth + 1, st)?.let(children::add)
            }
        } else if (node.childCount > 0) {
            st.truncated = true
        }
        var scroll = 0
        if (node.isScrollable) {
            for (action in node.actionList) scrollIds[action.id]?.let { scroll = scroll or it }
        }
        val extras = runCatching { node.extras }.getOrNull()
        val roleHint = (
            extras?.getCharSequence("AccessibilityNodeInfo.roleDescription")
                ?: extras?.getCharSequence("AccessibilityNodeInfo.chromeRole")
            )?.toString()?.lowercase()
        val range = node.rangeInfo
        return PhoneUiNode(
            ref = ref,
            className = node.className?.toString().orEmpty(),
            text = node.text?.toString(),
            desc = node.contentDescription?.toString(),
            hint = node.hintText?.toString(),
            viewId = node.viewIdResourceName,
            bounds = bounds,
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            checkable = node.isCheckable,
            checked = @Suppress("DEPRECATION") (node.isChecked),
            selected = node.isSelected,
            focused = node.isFocused,
            enabled = node.isEnabled,
            password = node.isPassword,
            heading = Build.VERSION.SDK_INT >= 28 && node.isHeading,
            showingHint = Build.VERSION.SDK_INT >= 26 && node.isShowingHintText,
            scroll = scroll,
            roleHint = roleHint,
            stateDesc = if (Build.VERSION.SDK_INT >= 30) node.stateDescription?.toString() else null,
            rangeCurrent = range?.current,
            rangeMax = range?.max,
            children = children,
        )
    }
}
