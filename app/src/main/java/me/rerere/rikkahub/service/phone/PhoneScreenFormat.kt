package me.rerere.rikkahub.service.phone

/**
 * 无障碍树 → 模型可读的分区文本。
 *
 *   屏幕 高德地图 · 竖屏 · 键盘收起 · 共 12 项
 *   顶部
 *     [1] 按钮 返回
 *   列表 [4] 可下滑
 *     [5] 瑞幸咖啡(科技园店) · 4.6分 · 320m
 *   底部
 *     [11] 标签 综合排序 · 已选
 *
 * 一行一个「可交互或有文字」的元素，布局节点折叠，可点行收编子文本；
 * 编号只在本次快照有效，动作前按指纹复核。
 */
enum class PhoneElementKind { ITEM, INPUT, LIST }

class PhoneElement(
    val id: Int,
    val windowId: Int,
    val ref: Int,
    val kind: PhoneElementKind,
    val role: String,
    val label: String,
    val fullText: String,
    val bounds: PhoneBox,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val password: Boolean,
    val secret: Boolean,
    val checkable: Boolean,
    val scroll: Int,
    val horizontal: Boolean,
    val listId: Int?,
    val parentId: Int?,
    val sensitiveWord: String?,
    val fingerprint: String,
) {
    val itemTexts: List<String> get() = if (fullText.isBlank()) emptyList() else listOf(fullText)
}

class PhoneFormatted(
    val text: String,
    val elements: List<PhoneElement>,
    val packageName: String?,
    val appLabel: String,
    val keyboardShown: Boolean?,
    val poorTree: Boolean,
    val hidden: Int,
) {
    fun element(id: Int): PhoneElement? = elements.firstOrNull { it.id == id }

    fun allByFingerprint(fingerprint: String): PhoneElement? = elements.firstOrNull { it.fingerprint == fingerprint }
}

object PhoneScreenFormat {
    const val LABEL_CAP = 60
    const val FULL_CAP = 300
    private const val POOR_TREE = 5
    private const val ROW_CAP = 64
    private const val MERGE_LABEL = 16
    private const val CHAR_BUDGET = 3000

    private const val POOR_TREE_NOTE = "提示：本页文字很少（游戏、自绘界面常见）。能看图的模型可以用 phone_shot 截图；否则请用户协助。"

    fun format(
        capture: PhoneCapture,
        appLabel: String,
        detail: Boolean = false,
        notes: List<String> = emptyList(),
    ): PhoneFormatted {
        val textNodes = capture.windows.sumOf { w -> w.root?.let(::countText) ?: 0 }
        val lines = mutableListOf<Line>()
        val windows = capture.windows.filter { it.root != null }
        for ((index, window) in windows.withIndex()) {
            val items = flatten(
                window.root!!,
                window,
                null
            ).sortedWith(compareBy({ it.node.bounds.top }, { it.node.bounds.left }))
            if (index < windows.size - 1) {
                if (items.isEmpty()) continue
                lines +=
                    Line.Header(if (window.title.isNullOrBlank()) {
                        "弹窗"
                    } else {
                        "弹窗 · " +
                            clip(clean(window.title) ?: "", 20)
                    })
                emit(items, 2, lines, reserved = false, list = null, parent = null)
            } else {
                zones(items, capture.screen.height, lines)
            }
        }

        val noteLines = notes.toMutableList()
        val poor = textNodes < POOR_TREE && windows.isNotEmpty()
        if (poor && capture.unreadableWindowTitle == null) noteLines += POOR_TREE_NOTE
        capture.unreadableWindowTitle?.let { noteLines += "提示：这个页面可能限制了无障碍读取（敏感视图策略），内容缺失。" }

        val headerLen = 40 + appLabel.length + noteLines.sumOf { it.length + 1 }
        val lengths = lines.map { it.estimate(detail) }
        var kept: List<Line> = lines
        var hidden = 0
        if (headerLen + lengths.sum() > CHAR_BUDGET) {
            val reservedLen = lines.indices.filter { lines[it].reserved }.sumOf { lengths[it] }
            val keepReserved = headerLen + reservedLen + 60 < CHAR_BUDGET * 2 / 3
            var available = CHAR_BUDGET - headerLen - 40 - (if (keepReserved) reservedLen else 0)
            val out = mutableListOf<Line>()
            var cut = false
            for ((i, line) in lines.withIndex()) {
                if (keepReserved && line.reserved) {
                    out += line
                    continue
                }
                if (!cut && lengths[i] <= available) {
                    out += line
                    available -= lengths[i]
                    continue
                }
                if (!cut) {
                    cut = true
                    out += Line.More(line.indent)
                }
                hidden += line.elementCount()
            }
            if (hidden > 0) out.filterIsInstance<Line.More>().forEach { it.count = hidden }
            kept = out
        }

        val ids = HashMap<Item, Int>()
        var next = 1
        for (line in kept) for (item in line.items()) ids[item] = next++
        val count = next - 1

        val orientation = if (capture.screen.width > capture.screen.height) "横屏" else "竖屏"
        val keyboard = when (capture.keyboardShown) {
            true -> "键盘弹出"
            false -> "键盘收起"
            null -> "键盘状态未知"
        }
        val sb = StringBuilder()
        sb.append("屏幕 ").append(appLabel).append(" · ").append(orientation).append(" · ").append(keyboard)
            .append(" · 共 ").append(count).append(" 项")
        if (capture.truncated) sb.append(" · 树已截断")
        noteLines.forEach { sb.append('\n').append(it) }
        if (count == 0) sb.append('\n').append("（没有可读的元素）")
        for (line in kept) {
            sb.append('\n')
            sb.append(line.render(ids, detail))
        }

        val elements = ids.entries.sortedBy { it.value }.map { (item, id) ->
            item.toElement(id, item.list?.let { ids[it] }, item.parent?.let { ids[it] })
        }
        return PhoneFormatted(
            text = sb.toString(),
            elements = elements,
            packageName = capture.packageName,
            appLabel = appLabel,
            keyboardShown = capture.keyboardShown,
            poorTree = poor,
            hidden = hidden,
        )
    }

    private class Item(
        val node: PhoneUiNode,
        val window: PhoneUiWindow,
        val kind: PhoneElementKind,
        val role: String,
        val label: String,
        val states: List<String>,
        val content: String?,
        val subs: List<Item> = emptyList(),
        val checkedState: Boolean? = null,
        val secret: Boolean = false,
        val horizontal: Boolean = false,
    ) {
        var list: Item? = null
        var parent: Item? = null

        val sensitive: String? = if (kind == PhoneElementKind.ITEM &&
            (node.clickable || node.longClickable || node.checkable) &&
            role != "文本") {
            PhoneWords.confirmHit(label, node.viewId)
        } else {
            null
        }

        fun fingerprint(): String {
            val identity = when (kind) {
                PhoneElementKind.LIST -> node.className + "|" + node.viewId + "|" + label
                PhoneElementKind.INPUT -> label + "|" + node.viewId + "|" + node.password
                PhoneElementKind.ITEM -> label
            }
            return "w" + window.id + "|" + node.bounds + "|" + role + "|" + identity +
                (checkedState?.let { "|c=" + it } ?: "")
        }

        fun fullText(): String {
            val parts = mutableListOf<String>()
            if (label.isNotEmpty()) parts += label
            if (kind == PhoneElementKind.INPUT && content != null) parts += "\"" + content + "\""
            if (kind != PhoneElementKind.LIST) {
                for (sub in subs) {
                    if (sub.kind == PhoneElementKind.LIST) {
                        sub.subs.forEach { if (it.label.isNotEmpty()) parts += it.label }
                    } else if (sub.role == "文本" || sub.role == "图片" || sub.role == "标题" || sub.role.isEmpty()) {
                        if (sub.label.isNotEmpty()) parts += sub.label
                    }
                }
            }
            return clip(parts.distinct().joinToString(" · "), FULL_CAP)
        }

        fun toElement(id: Int, listId: Int?, parentId: Int?) = PhoneElement(
            id = id,
            windowId = window.id,
            ref = node.ref,
            kind = kind,
            role = role,
            label = clip(label, LABEL_CAP),
            fullText = fullText(),
            bounds = node.bounds,
            clickable = node.clickable,
            longClickable = node.longClickable,
            editable = kind == PhoneElementKind.INPUT,
            password = node.password,
            secret = secret,
            checkable = node.checkable,
            scroll = node.scroll,
            horizontal = horizontal,
            listId = listId,
            parentId = parentId,
            sensitiveWord = sensitive,
            fingerprint = fingerprint(),
        )
    }

    private fun actionable(n: PhoneUiNode) = n.clickable || n.longClickable || n.checkable

    private fun isList(n: PhoneUiNode) = n.scrollable && n.children.isNotEmpty() && !n.editable

    private fun flatten(node: PhoneUiNode, window: PhoneUiWindow, parentClass: String?): List<Item> = when {
        node.editable -> listOf(input(node, window))
        isList(node) -> listOf(list(node, window))
        actionable(node) -> action(node, window, parentClass)
        else -> {
            val own = clean(node.text) ?: clean(node.desc)
            val children = node.children.flatMap { flatten(it, window, node.className) }
            if (own == null) {
                children
            } else {
                listOf(text(node, window, own, parentClass)) +
                    children.filterNot {
                        it.kind == PhoneElementKind.ITEM && it.subs.isEmpty() && it.role == "文本" &&
                            it.label == own
                    }
            }
        }
    }

    private fun text(node: PhoneUiNode, window: PhoneUiWindow, label: String, parentClass: String?): Item {
        val role = when {
            node.heading -> "标题"
            role(node, true, parentClass) == "标签" -> "标签"
            node.text.isNullOrBlank() && isImage(node) -> "图片"
            else -> "文本"
        }
        val states = if (node.selected) listOf("已选") else emptyList()
        return Item(node, window, PhoneElementKind.ITEM, role, label, states, null)
    }

    private fun input(node: PhoneUiNode, window: PhoneUiWindow): Item {
        val hint = clean(node.hint) ?: if (node.showingHint) clean(node.text) else null
        val secretWord = PhoneWords.secretHit(hint, node.desc, node.viewId)
        val name = clean(node.desc) ?: hint ?: idTail(node.viewId) ?: ""
        val secret = secretWord != null && !node.password
        val role = when {
            node.password -> "输入框(密码，不可代填)"
            secret -> "输入框(" + secretLabel(secretWord!!) + "，不可代填)"
            else -> "输入框"
        }
        val content = if (node.password || secret || node.showingHint) null else clean(node.text)
        val states = mutableListOf<String>()
        if (content == null && !node.password && !secret) states += "空"
        if (node.focused) states += "已聚焦"
        if (!node.enabled) states += "不可用"
        return Item(node, window, PhoneElementKind.INPUT, role, name, states, content, secret = secret)
    }

    private fun list(node: PhoneUiNode, window: PhoneUiWindow): Item {
        val subs = node.children.flatMap { flatten(it, window, node.className) }
        val horizontal = node.scroll and (PhoneScroll.LEFT or PhoneScroll.RIGHT) != 0 ||
            node.className.contains("Horizontal") || node.className.contains("ViewPager") ||
            (node.children.size >= 2 && node.children[0].bounds.rowOverlap(node.children[1].bounds) > 0.5 &&
                node.children[1].bounds.left >= node.children[0].bounds.right - 2)
        val forward = node.scroll and PhoneScroll.FORWARD != 0
        val backward = node.scroll and PhoneScroll.BACKWARD != 0
        val states = if (horizontal) {
            val right = node.scroll and PhoneScroll.RIGHT != 0 || forward
            val left = node.scroll and PhoneScroll.LEFT != 0 || backward
            listOfNotNull(
                when {
                    left && right -> "可左右滑"
                    right -> "可右滑"
                    left -> "可左滑"
                    else -> null
                }
            )
        } else {
            val down = node.scroll and PhoneScroll.DOWN != 0 || forward
            val up = node.scroll and PhoneScroll.UP != 0 || backward
            listOfNotNull(
                when {
                    up && down -> "可上下滑"
                    down -> "可下滑"
                    up -> "可上滑"
                    else -> null
                }
            )
        }
        val label = clean(node.desc) ?: ""
        return Item(node, window, PhoneElementKind.LIST, "列表", label, states, null, subs, horizontal = horizontal)
    }

    private fun action(node: PhoneUiNode, window: PhoneUiWindow, parentClass: String?): List<Item> {
        val texts = mutableListOf<String>()
        val subs = mutableListOf<Item>()
        fun gather(n: PhoneUiNode) {
            for (c in n.children) {
                when {
                    c.editable -> subs += input(c, window)
                    isList(c) -> subs += list(c, window)
                    actionable(c) -> subs += action(c, window, n.className)
                    else -> {
                        (clean(c.text) ?: clean(c.desc))?.let { texts += it }
                        gather(c)
                    }
                }
            }
        }
        gather(node)

        var checkNode: PhoneUiNode? = if (node.checkable) node else null
        var checkRole: String? = null
        val checks = subs.filter {
            it.kind == PhoneElementKind.ITEM && it.node.checkable && !it.node.clickable &&
                it.subs.isEmpty()
        }
        if (checks.size == 1) {
            val check = checks[0]
            subs.remove(check)
            if (checkNode == null) checkNode = check.node
            checkRole = check.role
            if (check.label.isNotEmpty() && check.label !in texts) texts += check.label
        }

        val own = clean(node.text) ?: clean(node.desc)
        val merged = texts.distinct().filter { it != own }
        val readable = own ?: merged.joinToString(" · ").ifEmpty { null } ?: clean(node.hint)
        if (readable == null && subs.isNotEmpty() && checkNode == null) return subs
        val label = readable ?: idTail(node.viewId)

        var role = role(node, own != null, parentClass)
        if (checkRole != null && (role.isEmpty() || role == "按钮" || role == "勾选框")) role = checkRole
        if (label == null && role.isEmpty()) role = "按钮"
        val states = mutableListOf<String>()
        val check = checkNode
        when {
            check == null -> Unit
            role == "开关" -> states += if (check.checked) "开" else "关"
            role == "勾选框" || role == "单选" -> states += if (check.checked) "✓" else "空"
            check.checked -> states += "已勾选"
        }
        if (node.selected) states += "已选"
        if (role == "滑块" && node.rangeCurrent != null) {
            val max = node.rangeMax
            states += if (max != null && max > 0f && max <= 1000f && max != 100f) {
                fmt(node.rangeCurrent) + "/" + fmt(max)
            } else {
                fmt(node.rangeCurrent)
            }
        }
        clean(node.stateDesc)?.let { sd ->
            if (role != "开关" && role != "勾选框" && role != "单选" && sd.length <= 10 && sd != label &&
                sd !in states) {
                states += sd
            }
        }
        if (!node.enabled) states += "不可用"
        return listOf(
            Item(node, window, PhoneElementKind.ITEM, role, label ?: "", states, null, subs, check?.checked, false)
        )
    }

    private fun role(node: PhoneUiNode, ownText: Boolean, parentClass: String?): String {
        val cls = node.className.substringAfterLast('.')
        val hint = node.roleHint
        return when {
            cls.contains("Switch") || cls == "ToggleButton" || hint == "switch" || hint == "开关" -> "开关"
            cls == "CheckBox" || cls == "CheckedTextView" || hint == "checkbox" || hint == "复选框" -> "勾选框"
            cls == "RadioButton" || hint == "radio" || hint == "radiobutton" || hint == "单选按钮" -> "单选"
            cls == "SeekBar" || cls == "RatingBar" || cls.contains("Slider") || hint == "slider" || hint == "滑块" -> "滑块"
            cls == "Spinner" || hint == "combobox" || hint == "popupbutton" || hint == "下拉列表" -> "下拉框"
            hint == "link" || hint == "链接" -> "链接"
            cls.contains("TabView") || cls.endsWith("\u0024Tab") || cls == "Tab" || hint == "tab" || hint == "标签" ||
                cls.contains("NavigationItemView") || cls.contains("NavigationBarItemView") ||
                (parentClass != null &&
                    (parentClass.contains("TabWidget") || parentClass.contains("TabLayout") ||
                        parentClass.contains("BottomNavigation"))) -> "标签"
            node.checkable -> "勾选框"
            cls == "Button" || cls == "ImageButton" || cls.endsWith(
                "Button"
            ) || hint == "button" || hint == "按钮" -> "按钮"
            isImage(node) -> "按钮"
            ownText && node.children.isEmpty() -> "按钮"
            else -> ""
        }
    }

    private sealed class Line(val indent: Int, val reserved: Boolean) {
        abstract fun items(): List<Item>
        open fun elementCount() = items().size
        abstract fun estimate(detail: Boolean): Int
        abstract fun render(ids: Map<Item, Int>, detail: Boolean): String

        class Header(val text: String, reserved: Boolean = false) : Line(0, reserved) {
            override fun items() = emptyList<Item>()
            override fun estimate(detail: Boolean) = text.length + 1
            override fun render(ids: Map<Item, Int>, detail: Boolean) = text
        }

        class More(indent: Int) : Line(indent, false) {
            var count = 0
            override fun items() = emptyList<Item>()
            override fun estimate(detail: Boolean) = 30
            override fun render(ids: Map<Item, Int>, detail: Boolean) =
                " ".repeat(indent) + "…另有 " + count + " 项，用 phone_collect 或 phone_scroll"
        }

        class Row(indent: Int, reserved: Boolean, val row: List<Item>) : Line(indent, reserved) {
            override fun items() = row
            override fun estimate(detail: Boolean) = indent + row.sumOf { body(it, 99, detail).length + 3 }
            override fun render(ids: Map<Item, Int>, detail: Boolean) =
                " ".repeat(indent) + row.joinToString("   ") { body(it, ids[it] ?: 0, detail) }
        }

        class ListHead(indent: Int, reserved: Boolean, val list: Item) : Line(indent, reserved) {
            override fun items() = listOf(list)
            override fun estimate(detail: Boolean) = indent + body(list, 99, detail).length + 1
            override fun render(ids: Map<Item, Int>, detail: Boolean) =
                " ".repeat(indent) + body(list, ids[list] ?: 0, detail)
        }
    }

    private enum class Zone(val title: String) { TOP("顶部"), MIDDLE("中部"), BOTTOM("底部") }

    private fun zones(items: List<Item>, screenHeight: Int, out: MutableList<Line>) {
        val h = screenHeight.coerceAtLeast(1)
        fun zone(item: Item): Zone {
            val cy = item.node.bounds.centerY
            return when {
                cy < h * 0.2 -> Zone.TOP
                cy > h * 0.86 -> Zone.BOTTOM
                else -> Zone.MIDDLE
            }
        }
        val byZone = items.groupBy(::zone)
        byZone[Zone.TOP]?.let {
            out += Line.Header(Zone.TOP.title)
            emit(it, 2, out, false, null, null)
        }
        byZone[Zone.MIDDLE]?.let { middle ->
            var needHeader = true
            var run = mutableListOf<Item>()
            fun flush() {
                if (run.isNotEmpty()) {
                    if (needHeader) out += Line.Header(Zone.MIDDLE.title)
                    needHeader = false
                    emit(run, 2, out, false, null, null)
                    run = mutableListOf()
                }
            }
            for (item in middle) {
                if (item.kind == PhoneElementKind.LIST) {
                    flush()
                    emit(listOf(item), 0, out, false, null, null)
                    needHeader = true
                } else {
                    run += item
                }
            }
            flush()
        }
        byZone[Zone.BOTTOM]?.let {
            out += Line.Header(Zone.BOTTOM.title, reserved = true)
            emit(it, 2, out, true, null, null)
        }
    }

    private fun emit(
        items: List<Item>,
        indent: Int,
        out: MutableList<Line>,
        reserved: Boolean,
        list: Item?,
        parent: Item?,
    ) {
        var i = 0
        while (i < items.size) {
            val item = items[i]
            item.list = list
            item.parent = parent
            if (item.kind == PhoneElementKind.LIST) {
                out += Line.ListHead(indent, reserved, item)
                emit(item.subs, indent + 2, out, reserved, item, null)
                i++
                continue
            }
            val row = mutableListOf(item)
            var length = body(item, 99, false).length
            while (i + 1 < items.size && mergeable(row.last(), items[i + 1])) {
                val nextItem = items[i + 1]
                val add = body(nextItem, 99, false).length + 3
                if (indent + length + add > ROW_CAP) break
                nextItem.list = list
                nextItem.parent = parent
                row += nextItem
                length += add
                i++
            }
            out += Line.Row(indent, reserved, row)
            for (member in row) {
                if (member.subs.isNotEmpty()) emit(member.subs, indent + 4, out, reserved, list, member)
            }
            i++
        }
    }

    private fun mergeable(a: Item, b: Item) =
        a.kind == PhoneElementKind.ITEM && b.kind == PhoneElementKind.ITEM && a.subs.isEmpty() && b.subs.isEmpty() &&
            a.role.isNotEmpty() && a.role == b.role &&
            a.label.length <= MERGE_LABEL && b.label.length <= MERGE_LABEL &&
            a.node.bounds.rowOverlap(b.node.bounds) >= 0.5 && b.node.bounds.left >= a.node.bounds.left

    private fun body(item: Item, id: Int, detail: Boolean): String {
        val sb = StringBuilder()
        if (item.kind == PhoneElementKind.LIST) {
            sb.append("列表 [").append(id).append(']')
            if (item.label.isNotEmpty()) sb.append(' ').append(clip(item.label, LABEL_CAP))
            if (item.states.isNotEmpty()) {
                sb.append(if (item.label.isNotEmpty()) " · " else " ").append(item.states.joinToString(" · "))
            }
        } else {
            if (item.sensitive != null) sb.append('⚠')
            sb.append('[').append(id).append(']')
            if (item.role.isNotEmpty()) sb.append(' ').append(item.role)
            val label = clip(item.label, LABEL_CAP)
            if (label.isNotEmpty()) sb.append(' ').append(label)
            if (item.kind == PhoneElementKind.INPUT && item.content != null) {
                sb.append(" \"").append(clip(item.content, LABEL_CAP)).append('"')
                if (item.states.isNotEmpty()) sb.append(' ').append(item.states.joinToString(" · "))
            } else if (item.states.isNotEmpty()) {
                sb.append(if (label.isNotEmpty() ||
                    item.role.isNotEmpty()) {
                    " · "
                } else {
                    " "
                }).append(item.states.joinToString(" · "))
            }
            if (label.isEmpty() && item.role.isNotEmpty() && item.kind == PhoneElementKind.ITEM &&
                item.states.isEmpty()) {
                sb.append(" (无文字)")
            }
        }
        if (detail) sb.append(" @").append(item.node.bounds.centerX).append(',').append(item.node.bounds.centerY)
        return sb.toString()
    }

    private val space = Regex("\\s+")
    private val invisible = Regex("[\\u200B-\\u200F\\u2060\\uFEFF]")

    fun clean(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val flat = space.replace(invisible.replace(value, ""), " ").trim()
        return if (flat.isEmpty()) null else PhoneMask.mask(flat)
    }

    fun clip(value: String, cap: Int) = if (value.length <= cap) value else value.take(cap - 1) + "…"

    fun idTail(viewId: String?): String? = viewId?.substringAfterLast(
        ":id/"
    )?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    private fun isImage(node: PhoneUiNode) =
        node.className.endsWith("ImageView") || node.className.endsWith("Image") || node.roleHint == "image" ||
            node.roleHint == "img"

    private fun secretLabel(word: String) = when (word) {
        "支付密码" -> "支付密码"
        "密码", "password", "passcode", "passwd", "pwd" -> "密码"
        "安全码", "cvv", "cvc" -> "安全码"
        else -> "验证码"
    }

    private fun fmt(value: Float) = if (value ==
        value.toLong().toFloat()) {
        value.toLong().toString()
    } else {
        "%.1f".format(value)
    }

    private fun countText(node: PhoneUiNode): Int =
        (if (!node.text.isNullOrBlank() || !node.desc.isNullOrBlank()) 1 else 0) + node.children.sumOf(::countText)
}
