package me.rerere.rikkahub.service.phone

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 无障碍树 → 紧凑文本。
 * 只发叶子文本与可交互行，坐标/控件 id/时间戳一律不进结果。
 */
class PhoneScreenReader(
    private val service: AccessibilityService,
    private val selfPackage: String,
) {
    private val screenSize: Pair<Int, Int> by lazy {
        val metrics = service.resources.displayMetrics
        metrics.widthPixels to metrics.heightPixels
    }

    fun capture(): PhoneScreenSnapshot {
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        val appWindows = windows.filter {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                it.root?.packageName?.toString()?.let { pkg -> pkg != selfPackage } == true
        }
        val target = appWindows.firstOrNull { it.isActive } ?: appWindows.firstOrNull()
        val root = target?.root ?: service.rootInActiveWindow
            ?: throw PhoneControlException(PhoneError.NO_WINDOW, "当前没有可读窗口（锁屏或息屏）")
        val pkg = root.packageName?.toString().orEmpty()
        val (width, height) = screenSize
        val collector = Collector()
        collect(root, collector, depth = 0)
        val nodes = collector.build(width, height)
        if (nodes.isEmpty()) throw PhoneControlException(PhoneError.NO_TEXT, "这个界面看不到文字内容")
        val lines = nodes.map { it.text }
        val fingerprint = (pkg + lines.joinToString("\u0001")).hashCode().toUInt().toString(16)
        return PhoneScreenSnapshot(
            packageName = pkg,
            appLabel = appLabelOf(pkg),
            width = width,
            height = height,
            nodes = nodes,
            lines = lines,
            fingerprint = fingerprint,
            truncated = collector.truncated,
            split = appWindows.size > 1,
        )
    }

    private fun appLabelOf(pkg: String): String = appLabelCache.getOrPut(pkg) {
        runCatching {
            val pm = service.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
    }

    private fun collect(node: AccessibilityNodeInfo, sink: Collector, depth: Int) {
        if (depth > MAX_DEPTH || sink.count >= MAX_NODES) {
            sink.truncated = true
            return
        }
        val pkg = node.packageName?.toString()
        if (pkg == selfPackage) return // 自己的悬浮条永远不进读屏结果
        if (!node.isVisibleToUser) return
        sink.consider(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, sink, depth + 1)
        }
    }

    private inner class Collector {
        var count = 0
        var truncated = false
        private val entries = mutableListOf<Entry>()
        private val seenText = HashSet<String>()
        private val bounds = Rect()

        private inner class Entry(val node: AccessibilityNodeInfo, val role: PhoneNodeRole, val rawText: String)

        fun consider(node: AccessibilityNodeInfo) {
            val text = node.text?.toString()?.trim().orEmpty()
                .ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }
                .ifEmpty { if (node.isEditable) node.hintText?.toString()?.trim().orEmpty() else "" }
            val visibleText = if (node.isPassword) PASSWORD_MASK else text
            val interactive = node.isClickable || node.isEditable || node.isScrollable || node.isCheckable
            if (visibleText.isEmpty() && !interactive) return
            val role = when {
                node.isEditable -> PhoneNodeRole.INPUT
                node.isCheckable -> PhoneNodeRole.CHECK
                node.isClickable -> PhoneNodeRole.BUTTON
                node.isScrollable -> PhoneNodeRole.SCROLL
                visibleText.isNotEmpty() -> PhoneNodeRole.TEXT
                else -> PhoneNodeRole.UNKNOWN
            }
            node.getBoundsInScreen(bounds)
            if (bounds.width() <= 0 || bounds.height() <= 0) return
            val key = "${role.code}|$visibleText"
            if (visibleText.isNotEmpty() && !seenText.add(key)) return
            entries.add(Entry(node, role, visibleText))
            count++
        }

        fun build(width: Int, height: Int): List<PhoneScreenNode> {
            val result = ArrayList<PhoneScreenNode>(entries.size)
            var totalChars = 0
            entries.forEach { entry ->
                if (totalChars > MAX_TOTAL_CHARS) {
                    truncated = true
                    return@forEach
                }
                totalChars += entry.rawText.length
                val rect = Rect()
                entry.node.getBoundsInScreen(rect)
                val scrollHint = if (entry.role == PhoneNodeRole.SCROLL && entry.rawText.isEmpty()) {
                    "列表（可滚动）"
                } else {
                    entry.rawText
                }
                result.add(
                    PhoneScreenNode(
                        index = result.size + 1,
                        role = entry.role,
                        text = scrollHint.take(MAX_LINE_CHARS),
                        bounds = rect,
                        node = entry.node,
                        editable = entry.node.isEditable,
                    )
                )
            }
            return result
        }
    }

    companion object {
        private const val MAX_DEPTH = 40
        private const val MAX_NODES = 45
        private const val MAX_LINE_CHARS = 80
        private const val MAX_TOTAL_CHARS = 1600
        private const val PASSWORD_MASK = "****"
        private val appLabelCache = HashMap<String, String>()
    }
}
