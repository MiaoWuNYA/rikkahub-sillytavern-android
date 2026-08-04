package me.rerere.rikkahub.data.ai.transformers

import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.random.Random

/**
 * 宏引擎 2.0 兼容实现（对齐 SillyTavern Macro 2.0 用户可见语义）。
 *
 * 支持：
 * - 递归解析 {{...}}，参数内可嵌套宏
 * - 作用域块宏：{{if cond}}...{{/if}}、{{trim}}...{{/trim}}、{{//}}...{{//}}
 * - {{if}} 条件（行内 {{if::cond::content||else}} 与块形式）、{{else}}、! 取反、
 *   .变量/$变量 简写、裸宏名自动解析、比较运算符（== != > >= < <=）、&& ||、
 *   # 保留空白标志、延迟解析（只求值选中的分支）
 * - 变量家族：setvar/getvar/incvar/decvar/addvar/hasvar/deletevar + global 版 + 别名
 * - 官方实用宏：space/newline/noop/reverse/comment/trim/random/pick/roll/datetimeformat/timeDiff
 *   /greeting/charFirstMessage/maxResponseTokens/allChatRange/groupNotMuted/notChar
 *   /isMobile/lastGenerationType/time::UTC±N
 * - 未知宏一律原样保留（保护 Pebble 模板 {{ message }} 等）
 */
/** 宏目录条目：syntax=插入模板，description=中文说明，group=界面分组 */
data class MacroEntry(
    val syntax: String,
    val description: String,
    val group: String,
)

class MacroEngine(
    private val legacy: Map<String, PlaceholderInfo>,
    private val vars: MacroVars,
) {

    /** 解析并求值整段文本。任何解析失败都会原样返回输入，绝不吞文本。 */
    fun substitute(text: String, ctx: PlaceholderCtx): String {
        if (!text.contains("{{")) return text
        return try {
            // \{{ 转义：先换成哨兵，解析完再还原为字面 {{
            val escaped = text.replace("\\{{", ESCAPE_SENTINEL)
            val nodes = parse(escaped)
            val seedSource = escaped
            val out = evaluate(nodes, EvalState(ctx, seedSource), 0)
            out.replace(ESCAPE_SENTINEL, "{{")
        } catch (_: Exception) {
            text
        }
    }

    // ---------- 语法解析 ----------

    private sealed class Node {
        data class Text(val text: String) : Node()
        data class Macro(
            val raw: String,
            val name: String,
            val args: List<List<Node>> = emptyList(),
            val scopedContent: List<Node>? = null,
            val preserveWhitespace: Boolean = false,
            val isElse: Boolean = false,
            val isClosing: Boolean = false,
        ) : Node()
    }

    private fun parse(text: String, depth: Int = 0): List<Node> {
        if (depth > MAX_DEPTH) return listOf(Node.Text(text))
        val nodes = mutableListOf<Node>()
        var i = 0
        while (i < text.length) {
            val open = text.indexOf("{{", i)
            if (open < 0) {
                if (i < text.length) nodes.add(Node.Text(text.substring(i)))
                break
            }
            if (open > i) nodes.add(Node.Text(text.substring(i, open)))
            val parsed = tryParseMacro(text, open, depth)
            if (parsed == null) {
                // 无法闭合的 {{ —— 原样保留，跳过两个字符避免死循环
                nodes.add(Node.Text(text.substring(open, minOf(open + 2, text.length))))
                i = open + 2
                continue
            }
            val (node, nextIndex) = parsed
            nodes.add(node)
            i = nextIndex
        }
        return nodes
    }

    /** 从 open（指向 {{）开始解析一个宏，返回节点与下一扫描位置。失败返回 null。 */
    private fun tryParseMacro(text: String, open: Int, depth: Int): Pair<Node, Int>? {
        // 找匹配的闭合 }}，跟踪嵌套 {{...}}（参数里可以有宏）
        var braceDepth = 0
        var j = open + 2
        var close = -1
        while (j < text.length) {
            if (text.startsWith("{{", j)) {
                braceDepth++
                j += 2
            } else if (text.startsWith("}}", j)) {
                if (braceDepth == 0) {
                    close = j
                    break
                }
                braceDepth--
                j += 2
            } else {
                j++
            }
        }
        if (close < 0) return null

        val raw = text.substring(open, close + 2)
        val head = text.substring(open + 2, close).trim()
        if (head.isEmpty()) return null

        // {{/name}} 闭合标签
        if (head.startsWith("/")) {
            return Node.Macro(raw = raw, name = head.drop(1).trim().lowercase(), isClosing = true) to close + 2
        }
        // {{else}}
        if (head.equals("else", ignoreCase = true)) {
            return Node.Macro(raw = raw, name = "else", isElse = true) to close + 2
        }

        // 宏名：字母数字 _ - / . $（注释宏为 //，变量简写为 .var/$var）
        val nameMatch = NAME_REGEX.find(head) ?: return null
        val name = nameMatch.value.lowercase()
        var rest = head.substring(nameMatch.range.last + 1).trim()
        var preserveWhitespace = false

        // # 保留空白标志：{{if::#::cond::content}} 或 {{if # cond}} 形式
        if (rest.startsWith("#")) {
            preserveWhitespace = true
            rest = rest.drop(1).trim()
        }

        // 参数分割（:: 在嵌套宏内部时不计）
        var args = if (rest.startsWith(":") && !rest.startsWith("::")) {
            // 旧语法：{{pick:A|B|C}} 单冒号
            listOf(rest.drop(1).trim())
        } else {
            splitArgs(rest)
        }
        if (args.firstOrNull() == "#") {
            preserveWhitespace = true
            args = args.drop(1)
        }
        if (args.isEmpty() && rest.isNotBlank()) {
            // 旧语法：{{roll 1d20}} 空格
            val single = rest.trim()
            if (single.isNotEmpty()) args = listOf(single)
        }

        // 作用域块：任何宏后面紧跟 {{/name}} 时，内容成为最后一个参数（官方通用 scoped 语法）
        val block = findScopedBlock(text, close + 2, name)
        if (block != null) {
            val (content, end) = block
            val processedContent = if (preserveWhitespace) content else content.dedentAndTrim()
            val contentNodes = parse(processedContent, depth + 1)
            // if 特例：scopedContent 单独存放（分支逻辑用），不追加到参数
            val finalArgs = if (name == "if") args else args + processedContent
            return Node.Macro(
                raw = raw,
                name = name,
                args = finalArgs.map { parse(it, depth + 1) },
                scopedContent = contentNodes,
                preserveWhitespace = preserveWhitespace,
            ) to end
        }

        return Node.Macro(
            raw = raw,
            name = name,
            args = args.map { parse(it, depth + 1) },
            preserveWhitespace = preserveWhitespace,
        ) to close + 2
    }

    /** 在文本中查找 {{/name}} 并返回块内容（支持同名嵌套块配对）。 */
    private fun findScopedBlock(text: String, from: Int, name: String): Pair<String, Int>? {
        var depth = 1
        var i = from
        while (i < text.length) {
            val open = text.indexOf("{{", i)
            if (open < 0) return null
            val close = text.indexOf("}}", open)
            if (close < 0) return null
            val head = text.substring(open + 2, close).trim()
            val isClose = head.startsWith("/") && head.drop(1).trim().equals(name, ignoreCase = true)
            val isOpen = head.substringBefore(" ").substringBefore("::").trim()
                .equals(name, ignoreCase = true)
            if (isOpen) depth++
            if (isClose) {
                depth--
                if (depth == 0) {
                    return (text.substring(from, open)) to (close + 2)
                }
            }
            i = close + 2
        }
        return null
    }

    /** 按 :: 分割参数，嵌套 {{...}} 内部的 :: 不计。 */
    private fun splitArgs(rest: String): List<String> {
        if (rest.isEmpty()) return emptyList()
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var i = 0
        while (i < rest.length) {
            when {
                rest.startsWith("{{", i) -> {
                    depth++
                    current.append("{{")
                    i += 2
                }
                rest.startsWith("}}", i) -> {
                    if (depth > 0) depth--
                    current.append("}}")
                    i += 2
                }
                rest.startsWith("::", i) && depth == 0 -> {
                    parts.add(current.toString().trim())
                    current.setLength(0)
                    i += 2
                }
                else -> {
                    current.append(rest[i])
                    i++
                }
            }
        }
        parts.add(current.toString().trim())
        return parts.filterIndexed { index, s -> !(index == 0 && s.isEmpty()) }
    }

    // ---------- 求值 ----------

    private inner class EvalState(
        val ctx: PlaceholderCtx,
        seedSource: String,
    ) {
        val contentHash = seedSource.hashCode().toLong()
        var position = 0
    }

    private fun evaluate(nodes: List<Node>, state: EvalState, depth: Int): String {
        if (depth > MAX_DEPTH) {
            // sealed 分支用 when 穷尽匹配，避免 else 分支无法智能转换出 Macro.raw
            return nodes.joinToString("") { n ->
                when (n) {
                    is Node.Text -> n.text
                    is Node.Macro -> n.raw
                }
            }
        }
        val sb = StringBuilder()
        for (node in nodes) {
            sb.append(evalNode(node, state, depth))
        }
        return sb.toString()
    }

    private fun evalNode(node: Node, state: EvalState, depth: Int): String {
        return when (node) {
            is Node.Text -> node.text
            is Node.Macro -> {
                if (node.isClosing || node.isElse) return ""
                evalMacro(node, state, depth)
            }
        }
    }

    private fun evalMacro(node: Node.Macro, state: EvalState, depth: Int): String {
        val name = node.name
        // 变量简写：{{.var}} {{$var}} 及运算符（= ++ -- += -= || ?? ||= ??= == != > >= < <=）
        if (name.startsWith(".") || name.startsWith("$")) {
            return evalVarShorthand(node, state)
        }
        // 变量副作用优先执行（官方变量宏同语义）
        if (name in VARIABLE_MACROS) {
            return evalVariableMacro(node, state)
        }
        when (name) {
            "if" -> return evalIf(node, state, depth)
            "else" -> return ""
            "//", "comment" -> return ""
            "trim" -> {
                val content = node.scopedContent
                if (content != null) return evaluate(content, state, depth + 1).trim()
                return "{{trim}}" // 非作用域：交给后处理删除周围换行
            }
        }

        // 参数预求值（if/变量已特殊处理，其余宏参数先解析；
        // scoped 内容已在解析时追加为最后一个参数，这里不再重复求值）
        val inlineArgs = node.args.map { evaluate(it, state, depth + 1) }
        val content = node.scopedContent?.let { evaluate(it, state, depth + 1) }
        val args = inlineArgs

        return resolveMacroValue(name, args, content, state, depth) ?: node.raw
    }

    /** 变量简写：{{.var}} {{$var}} 及运算符（官方 Variable Shorthands 全套语义）。 */
    private fun evalVarShorthand(node: Node.Macro, state: EvalState): String {
        val global = node.name.startsWith("$")
        val varName = node.name.drop(1)
        val chatKey = if (global) null else state.ctx.conversationId?.toString()
        val raw = node.args.map { evaluate(it, state, 1) }.firstOrNull()?.trim() ?: ""
        if (raw.isEmpty()) return vars.get(chatKey, varName) ?: ""

        for (op in VAR_SHORTHAND_OPS) {
            if (raw == op) {
                return when (op) {
                    "++" -> vars.inc(chatKey, varName)
                    "--" -> vars.dec(chatKey, varName)
                    "=" -> {
                        vars.set(chatKey, varName, "")
                        ""
                    }
                    "+=" -> {
                        vars.add(chatKey, varName, "")
                        ""
                    }
                    "-=" -> {
                        // 无值时保持变量不变（不能把缺失变量错误写成 0）
                        vars.get(chatKey, varName) ?: ""
                    }
                    "||" -> {
                        val cur = vars.get(chatKey, varName)
                        if (cur != null && !isFalseValue(cur)) cur else ""
                    }
                    "??" -> vars.get(chatKey, varName) ?: ""
                    "||=" -> {
                        val cur = vars.get(chatKey, varName)
                        if (cur == null || isFalseValue(cur)) {
                            vars.set(chatKey, varName, "")
                            ""
                        } else {
                            cur
                        }
                    }
                    "??=" -> {
                        val cur = vars.get(chatKey, varName)
                        if (cur == null) {
                            vars.set(chatKey, varName, "")
                            ""
                        } else {
                            cur
                        }
                    }
                    "==" -> (vars.get(chatKey, varName).orEmpty() == "").toString()
                    "!=" -> (vars.get(chatKey, varName).orEmpty() != "").toString()
                    ">=" -> (compareValues(vars.get(chatKey, varName).orEmpty(), "") >= 0).toString()
                    "<=" -> (compareValues(vars.get(chatKey, varName).orEmpty(), "") <= 0).toString()
                    ">" -> (compareValues(vars.get(chatKey, varName).orEmpty(), "") > 0).toString()
                    "<" -> (compareValues(vars.get(chatKey, varName).orEmpty(), "") < 0).toString()
                    else -> ""
                }
            }
            if (raw.startsWith(op)) {
                val value = raw.drop(op.length).trim()
                return when (op) {
                    "++" -> vars.inc(chatKey, varName)
                    "--" -> vars.dec(chatKey, varName)
                    "=" -> {
                        vars.set(chatKey, varName, value)
                        ""
                    }
                    "+=" -> {
                        vars.add(chatKey, varName, value)
                        ""
                    }
                    "-=" -> {
                        val delta = value.toLongOrNull()
                        if (delta != null) vars.add(chatKey, varName, (-delta).toString())
                        ""
                    }
                    "||" -> {
                        val cur = vars.get(chatKey, varName)
                        if (cur != null && !isFalseValue(cur)) cur else value
                    }
                    "??" -> vars.get(chatKey, varName) ?: value
                    "||=" -> {
                        val cur = vars.get(chatKey, varName)
                        if (cur == null || isFalseValue(cur)) {
                            vars.set(chatKey, varName, value)
                            value
                        } else {
                            cur
                        }
                    }
                    "??=" -> {
                        val cur = vars.get(chatKey, varName)
                        if (cur == null) {
                            vars.set(chatKey, varName, value)
                            value
                        } else {
                            cur
                        }
                    }
                    "==" -> (vars.get(chatKey, varName).orEmpty() == value).toString()
                    "!=" -> (vars.get(chatKey, varName).orEmpty() != value).toString()
                    ">=" -> (compareValues(vars.get(chatKey, varName).orEmpty(), value) >= 0).toString()
                    "<=" -> (compareValues(vars.get(chatKey, varName).orEmpty(), value) <= 0).toString()
                    ">" -> (compareValues(vars.get(chatKey, varName).orEmpty(), value) > 0).toString()
                    "<" -> (compareValues(vars.get(chatKey, varName).orEmpty(), value) < 0).toString()
                    else -> ""
                }
            }
        }
        return vars.get(chatKey, varName) ?: ""
    }

    /** 求值已注册宏；未知宏返回 null（调用方原样保留）。 */
    private fun resolveMacroValue(
        name: String,
        args: List<String>,
        content: String?,
        state: EvalState,
        depth: Int,
    ): String? {
        // 新官方宏带参数时优先：旧宏忽略参数，避免 {{random::A::B}} / {{time::UTC}} /
        // {{charFirstMessage::n}} 被同名旧宏遮蔽后永远取不到参数语义
        if (args.isNotEmpty()) {
            when (name) {
                "random" -> return randomPick(parseListArg(args))
                "time" -> return timeMacro(args[0])
                "charFirstMessage" -> return greetingMacro(args[0], state.ctx)
            }
        }
        // 旧宏（35 个现有宏，大小写不敏感，与官方引擎一致）
        legacy.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.let { (_, info) ->
            return try {
                info.resolver(state.ctx)
            } catch (_: Exception) {
                ""
            }
        }
        return when (name) {
            "space" -> " ".repeat(args.firstOrNull()?.toIntOrNull()?.coerceIn(0, 100) ?: 1)
            "newline" -> "\n".repeat(args.firstOrNull()?.toIntOrNull()?.coerceIn(0, 100) ?: 1)
            "noop" -> ""
            "reverse" -> args.firstOrNull()?.reversed() ?: ""
            "random" -> randomPick(parseListArg(args))
            "pick" -> stablePick(parseListArg(args), state)
            "roll" -> rollDice(args.firstOrNull() ?: "") ?: ""
            "datetimeformat" -> formatDateTime(args.firstOrNull() ?: "")
            "time" -> timeMacro(args.firstOrNull())
            "timeDiff" -> timeDiff(args.getOrNull(0), args.getOrNull(1))
            "greeting", "charFirstMessage" -> greetingMacro(args.firstOrNull(), state.ctx)
            "maxResponse", "maxResponseTokens" -> state.ctx.assistant.maxTokens?.toString() ?: ""
            "allChatRange" -> if (state.ctx.messages.isEmpty()) "" else "0-${state.ctx.messages.lastIndex}"
            "groupNotMuted" -> groupNames(state.ctx, includeMuted = false)
            "notChar" -> groupNames(state.ctx, includeMuted = true, excludeSelf = true)
            "isMobile" -> "true"
            "lastGenerationType" -> state.ctx.generationType?.value ?: ""
            else -> null
        }
    }

    private fun evalIf(node: Node.Macro, state: EvalState, depth: Int): String {
        if (node.args.isEmpty()) return node.raw
        // 条件：只求值条件参数（延迟解析，分支只求值选中的）
        val rawCondition = evaluate(node.args[0], state, depth + 1)
        val (inverted, condition) = parseInversion(rawCondition)

        val resolvedCondition = when {
            condition.matches(VAR_SHORTHAND_REGEX) -> {
                val prefix = condition[0]
                val varName = condition.drop(1)
                if (prefix == '.') {
                    vars.get(state.ctx.conversationId?.toString(), varName) ?: ""
                } else {
                    vars.get(null, varName) ?: ""
                }
            }
            !COMPARE_OPS.any { condition.contains(it) } &&
                !condition.contains("&&") && !condition.contains("||") &&
                resolveMacroValue(condition, emptyList(), null, state, depth + 1) != null ->
                resolveMacroValue(condition, emptyList(), null, state, depth + 1) ?: ""
            condition.contains("&&") || condition.contains("||") -> evalLogical(condition, state)
            else -> resolveCompare(condition, state)
        }

        val isTruthy = !isFalseValue(resolvedCondition)
        val result = if (inverted) !isTruthy else isTruthy

        // 选择分支：块形式用 scopedContent（已按顶层 else 分割），行内形式参数2
        val branchNodes: List<Node>? = if (node.scopedContent != null) {
            val split = splitTopLevelElse(node.scopedContent)
            if (result) split.first else split.second
        } else {
            val contentNodes = node.args.getOrNull(1)
            if (contentNodes == null) null
            else {
                val split = splitInlineElse(contentNodes)
                if (result) split.first else split.second
            }
        }
        if (branchNodes == null || branchNodes.isEmpty()) return ""

        var out = evaluate(branchNodes, state, depth + 1)
        if (!node.preserveWhitespace) out = out.trim()
        return out
    }

    private fun parseInversion(raw: String): Pair<Boolean, String> {
        var inverted = false
        var s = raw.trim()
        while (s.startsWith("!")) {
            inverted = !inverted
            s = s.drop(1).trimStart()
        }
        return inverted to s
    }

    /** 逻辑表达式：|| 与 && 连接（比较优先）。 */
    private fun evalLogical(condition: String, state: EvalState): String {
        // 顶层 || 分割
        val orParts = splitTopLevel(condition, "||")
        if (orParts.size > 1) {
            for (part in orParts) {
                val v = evalAndChain(part.trim(), state)
                if (!isFalseValue(v)) return "true"
            }
            return "false"
        }
        return evalAndChain(condition, state)
    }

    private fun evalAndChain(expr: String, state: EvalState): String {
        val andParts = splitTopLevel(expr, "&&")
        if (andParts.size > 1) {
            for (part in andParts) {
                val v = resolveCompare(part.trim(), state)
                if (isFalseValue(v)) return "false"
            }
            return "true"
        }
        return resolveCompare(expr, state)
    }

    private fun splitTopLevel(s: String, sep: String): List<String> {
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        var i = 0
        while (i < s.length) {
            when {
                s.startsWith("{{", i) -> { depth++; cur.append("{{"); i += 2 }
                s.startsWith("}}", i) -> { if (depth > 0) depth--; cur.append("}}"); i += 2 }
                s.startsWith(sep, i) && depth == 0 -> { parts.add(cur.toString()); cur.setLength(0); i += sep.length }
                else -> { cur.append(s[i]); i++ }
            }
        }
        parts.add(cur.toString())
        return parts
    }

    /** 比较表达式求值：== != > >= < <=，数值优先，否则字符串。 */
    private fun resolveCompare(expr: String, state: EvalState): String {
        val trimmed = expr.trim()
        for (op in listOf("==", "!=", ">=", "<=", ">", "<")) {
            val idx = findTopLevelOp(trimmed, op)
            if (idx >= 0) {
                val left = trimmed.substring(0, idx).trim()
                val right = trimmed.substring(idx + op.length).trim()
                val cmp = compareValues(left, right)
                val result = when (op) {
                    "==" -> cmp == 0
                    "!=" -> cmp != 0
                    ">=" -> cmp >= 0
                    "<=" -> cmp <= 0
                    ">" -> cmp > 0
                    "<" -> cmp < 0
                    else -> false
                }
                return result.toString()
            }
        }
        return trimmed
    }

    private fun findTopLevelOp(s: String, op: String): Int {
        var depth = 0
        var i = 0
        while (i < s.length) {
            when {
                s.startsWith("{{", i) -> { depth++; i += 2 }
                s.startsWith("}}", i) -> { if (depth > 0) depth--; i += 2 }
                s.startsWith(op, i) && depth == 0 -> return i
                else -> i++
            }
        }
        return -1
    }

    private fun compareValues(left: String, right: String): Int {
        val l = left.toDoubleOrNull()
        val r = right.toDoubleOrNull()
        return if (l != null && r != null) l.compareTo(r) else left.compareTo(right)
    }

    private fun isFalseValue(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return true
        if (v.equals("false", ignoreCase = true)) return true
        if (v.equals("off", ignoreCase = true)) return true
        if (v.equals("no", ignoreCase = true)) return true
        if (v == "0") return true
        return false
    }

    /** 官方 scoped 内容处理：去首尾空白 + 移除公共缩进。 */
    private fun String.dedentAndTrim(): String {
        val lines = this.split("\n")
        val indent = lines.filter { it.isNotBlank() }
            .minOfOrNull { it.takeWhile { c -> c == ' ' || c == '\t' }.length } ?: 0
        return lines.joinToString("\n") { line ->
            if (line.isBlank()) "" else line.drop(minOf(indent, line.length))
        }.trim()
    }

    /** 在节点列表中按顶层 {{else}} 分割。 */
    private fun splitTopLevelElse(nodes: List<Node>): Pair<List<Node>, List<Node>> {
        for (i in nodes.indices) {
            val n = nodes[i]
            if (n is Node.Macro && n.isElse) {
                return nodes.subList(0, i) to nodes.subList(i + 1, nodes.size)
            }
        }
        return nodes to emptyList()
    }

    /**
     * 行内 if 分支分割：支持官方行内语法 {{if::条件::A||B}}。
     * 只处理顶层文本节点中的 ||（嵌套宏内部的 || 属于变量默认值/逻辑或，不分割）。
     */
    private fun splitInlineElse(nodes: List<Node>): Pair<List<Node>, List<Node>> {
        val first = mutableListOf<Node>()
        val second = mutableListOf<Node>()
        var found = false
        for (node in nodes) {
            if (found) {
                second.add(node)
                continue
            }
            when (node) {
                is Node.Macro -> {
                    if (node.isElse) found = true else first.add(node)
                }
                is Node.Text -> {
                    val idx = node.text.indexOf("||")
                    if (idx >= 0) {
                        val before = node.text.substring(0, idx)
                        val after = node.text.substring(idx + 2)
                        if (before.isNotEmpty()) first.add(Node.Text(before))
                        if (after.isNotEmpty()) second.add(Node.Text(after))
                        found = true
                    } else {
                        first.add(node)
                    }
                }
            }
        }
        return first to second
    }

    // ---------- 变量宏 ----------

    private fun evalVariableMacro(node: Node.Macro, state: EvalState): String {
        val args = node.args.map { evaluate(it, state, 1) }
        val name = node.name
        val global = name.contains("global")
        val varName = args.getOrNull(0) ?: ""
        val value = args.getOrNull(1) ?: ""
        val chatKey = if (global) null else state.ctx.conversationId?.toString()
        return when (name) {
            "setvar", "setglobalvar" -> {
                vars.set(chatKey, varName, value)
                ""
            }
            "getvar", "getglobalvar" -> vars.get(chatKey, varName) ?: ""
            "incvar", "incglobalvar" -> vars.inc(chatKey, varName)
            "decvar", "decglobalvar" -> vars.dec(chatKey, varName)
            "addvar", "addglobalvar" -> {
                vars.add(chatKey, varName, value)
                ""
            }
            "hasvar", "varexists", "hasglobalvar", "globalvarexists" ->
                vars.has(chatKey, varName).toString()
            "deletevar", "flushvar", "deleteglobalvar", "flushglobalvar" -> {
                vars.delete(chatKey, varName)
                ""
            }
            else -> ""
        }
    }

    // ---------- 工具实现 ----------

    /** 兼容旧语法列表：{{random:a|b|c}} / {{pick::A|B|C}} / 逗号分隔。 */
    private fun parseListArg(args: List<String>): List<String> {
        if (args.isEmpty()) return emptyList()
        if (args.size > 1) return args
        val single = args[0]
        return when {
            single.contains("|") -> single.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            single.contains("::") -> single.split("::").map { it.trim() }.filter { it.isNotEmpty() }
            single.contains(",") -> single.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            else -> listOf(single.trim())
        }
    }

    private fun randomPick(list: List<String>): String {
        if (list.isEmpty()) return ""
        return list[Random.nextInt(list.size)]
    }

    /** 官方 pick：同一聊天 + 同一位置结果稳定（种子 = chat + 重掷种子 + 内容 hash + 位置）。 */
    private fun stablePick(list: List<String>, state: EvalState): String {
        if (list.isEmpty()) return ""
        // /reroll-pick 修改本对话保留变量 __pick_reroll_seed，从而让所有 {{pick}} 换一批结果
        val rerollSeed = vars.get(state.ctx.conversationId?.toString(), "__pick_reroll_seed")?.toLongOrNull() ?: 0L
        val seed = "${state.ctx.conversationId ?: "global"}|$rerollSeed|${state.contentHash}|${state.position}"
        state.position++
        val hash = MessageDigest.getInstance("MD5").digest(seed.toByteArray())
        val longSeed = ((hash[0].toLong() and 0xff) shl 56) or
            ((hash[1].toLong() and 0xff) shl 48) or
            ((hash[2].toLong() and 0xff) shl 40) or
            ((hash[3].toLong() and 0xff) shl 32) or
            ((hash[4].toLong() and 0xff) shl 24) or
            ((hash[5].toLong() and 0xff) shl 16) or
            ((hash[6].toLong() and 0xff) shl 8) or
            (hash[7].toLong() and 0xff)
        return list[Math.floorMod(longSeed, list.size.toLong()).toInt()]
    }

    /** 骰子：NdM±K / NdM（与现有 rollDice 同语义）。 */
    private fun rollDice(expr: String): String? {
        val text = expr.trim().replace(" ", "")
        if (text.isEmpty()) return null
        val tokens = Regex("([+-]?\\d*d\\d+|[+-]?\\d+)", RegexOption.IGNORE_CASE).findAll(text).toList()
        if (tokens.isEmpty() || tokens.joinToString("") { it.value } != text) return null
        var total = 0
        for (token in tokens) {
            val raw = token.value
            val sign = if (raw.startsWith("-")) -1 else 1
            val body = raw.drop(1).takeIf { raw.startsWith("+") || raw.startsWith("-") } ?: raw
            if ('d' in body.lowercase()) {
                val parts = body.split(Regex("[dD]"))
                val count = parts.getOrNull(0)?.toIntOrNull() ?: 1
                val sides = parts.getOrNull(1)?.toIntOrNull() ?: return null
                if (count <= 0 || sides <= 0 || count > 1000 || sides > 100000) return null
                repeat(count) { total += sign * (Random.nextInt(sides) + 1) }
            } else {
                total += sign * (body.toIntOrNull() ?: return null)
            }
        }
        return total.toString()
    }

    /** moment.js 格式 → java.time 格式（常见 token 映射）。 */
    private fun formatDateTime(format: String): String {
        if (format.isEmpty()) return ""
        val mapped = format
            .replace("YYYY", "yyyy")
            .replace("YY", "yy")
            .replace("DD", "dd")
            .replace("D", "d")
            .replace("HH", "HH")
            .replace("mm", "mm")
            .replace("ss", "ss")
            .replace("A", "a")
            .replace("dddd", "EEEE")
            .replace("ddd", "EEE")
            .replace("LLLL", "yyyy年M月d日 EEEE")
            .replace("LLL", "yyyy年M月d日")
            .replace("LL", "yyyy年M月d日")
        return try {
            DateTimeFormatter.ofPattern(mapped, Locale.getDefault()).format(LocalDateTime.now())
        } catch (_: Exception) {
            ""
        }
    }

    private fun timeMacro(offsetSpec: String?): String {
        if (offsetSpec.isNullOrBlank()) {
            return java.time.LocalTime.now().truncatedTo(ChronoUnit.MINUTES)
                .format(DateTimeFormatter.ofPattern("HH:mm"))
        }
        val match = Regex("^UTC([+-]\\d+)$", RegexOption.IGNORE_CASE).find(offsetSpec.trim())
        if (match == null) return timeMacro(null)
        val offset = match.groupValues[1].toIntOrNull() ?: return timeMacro(null)
        val now = java.time.Instant.now()
        val zoned = now.atOffset(java.time.ZoneOffset.ofHours(offset))
        return zoned.format(DateTimeFormatter.ofPattern("HH:mm"))
    }

    private fun timeDiff(left: String?, right: String?): String {
        if (left.isNullOrBlank() || right.isNullOrBlank()) return ""
        val a = parseTime(left) ?: return ""
        val b = parseTime(right) ?: return ""
        val diff = Duration.between(a, b).abs()
        return when {
            diff.toDays() >= 365 -> "${diff.toDays() / 365}年"
            diff.toDays() >= 30 -> "${diff.toDays() / 30}个月"
            diff.toDays() >= 1 -> "${diff.toDays()}天"
            diff.toHours() >= 1 -> "${diff.toHours()}小时"
            diff.toMinutes() >= 1 -> "${diff.toMinutes()}分钟"
            else -> "${diff.seconds}秒"
        }
    }

    private fun parseTime(s: String): LocalDateTime? {
        val t = s.trim()
        val formats = listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        )
        for (f in formats) {
            try {
                return LocalDateTime.parse(t, f)
            } catch (_: DateTimeParseException) {
            }
        }
        try {
            return java.time.LocalDate.parse(t).atStartOfDay()
        } catch (_: DateTimeParseException) {
        }
        return null
    }

    private fun greetingMacro(indexArg: String?, ctx: PlaceholderCtx): String {
        val tav = ctx.assistant.tavernData ?: return ""
        val index = indexArg?.trim()?.toIntOrNull() ?: 0
        return when {
            index <= 0 -> tav.firstMessage
            else -> tav.alternateGreetings.getOrNull(index - 1) ?: ""
        }
    }

    private fun groupNames(ctx: PlaceholderCtx, includeMuted: Boolean, excludeSelf: Boolean = false): String {
        val settings = ctx.settings
        val group = settings.groupChats.firstOrNull { ctx.assistant.id in it.memberIds } ?: return ""
        return group.memberIds
            .filter { id -> id != ctx.assistant.id || !excludeSelf }
            .filter { id -> includeMuted || id !in group.disabledMemberIds }
            .mapNotNull { id -> settings.assistants.firstOrNull { it.id == id }?.name }
            .joinToString(", ")
    }

    companion object {
        /** 引擎 2.0 宏目录：界面宏列表与本实现共用，新增/删除宏时在此登记即可自动显示 */
        val macroCatalog: List<MacroEntry> = listOf(
            MacroEntry("setvar::变量名::值", "设置本对话变量", "变量"),
            MacroEntry("getvar::变量名", "读取本对话变量", "变量"),
            MacroEntry("incvar::变量名", "变量+1", "变量"),
            MacroEntry("decvar::变量名", "变量-1", "变量"),
            MacroEntry("addvar::变量名::值", "变量加值", "变量"),
            MacroEntry("hasvar::变量名", "判断变量是否存在", "变量"),
            MacroEntry("flushvar::变量名", "删除变量", "变量"),
            MacroEntry("setglobalvar::变量名::值", "设置全局变量", "变量"),
            MacroEntry("getglobalvar::变量名", "读取全局变量", "变量"),
            MacroEntry(".变量名", "变量简写读取", "变量"),
            MacroEntry(".变量名++", "变量简写自增", "变量"),
            MacroEntry("if::条件::内容", "条件分支（可用{{else}}）", "条件"),
            MacroEntry("// 注释", "注释（不发送）", "条件"),
            MacroEntry("pick::A::B::C", "稳定随机选一", "随机与工具"),
            MacroEntry("roll::2d6+1", "掷骰子", "随机与工具"),
            MacroEntry("random::A::B::C", "随机选一（每次不同）", "随机与工具"),
            MacroEntry("space::N", "N个空格", "随机与工具"),
            MacroEntry("newline::N", "N个换行", "随机与工具"),
            MacroEntry("noop", "空", "随机与工具"),
            MacroEntry("reverse::文本", "反转文本", "随机与工具"),
            MacroEntry("allChatRange", "消息范围", "随机与工具"),
            MacroEntry("groupNotMuted", "群聊未禁言成员", "随机与工具"),
            MacroEntry("notChar", "除自己外成员", "随机与工具"),
            MacroEntry("isMobile", "是否手机端", "随机与工具"),
            MacroEntry("lastGenerationType", "上次生成类型", "随机与工具"),
            MacroEntry("maxResponse", "最大回复token", "随机与工具"),
            MacroEntry("greeting::N", "第N条开场白", "随机与工具"),
            MacroEntry("time::UTC+8", "指定时区时间", "时间"),
            MacroEntry("datetimeformat::yyyy-MM-dd HH:mm", "自定义时间格式", "时间"),
            MacroEntry("timeDiff::时间A::时间B", "时间差", "时间"),
        )

        private const val MAX_DEPTH = 32
        private const val ESCAPE_SENTINEL = "\u0000ESCAPED_LBRACE\u0000"
        // 官方宏名/变量名规则：字母（含 Unicode，如中文）、数字、_、-（简写允许 . 和 $ 前缀）
        private val NAME_REGEX = Regex("""[\p{L}\p{N}_\-/.\$]+""")
        private val VAR_SHORTHAND_REGEX = Regex("""^[.\$][\p{L}\p{N}_\-]+$""")
        private val COMPARE_OPS = setOf("==", "!=", ">=", "<=", ">", "<")
        private val VAR_SHORTHAND_OPS = listOf(
            "||=", "??=", "++", "--", "+=", "-=", "==", "!=", ">=", "<=", "||", "??", ">", "<", "=",
        )
        private val VARIABLE_MACROS = setOf(
            "setvar", "getvar", "incvar", "decvar", "addvar", "hasvar", "deletevar", "varexists", "flushvar",
            "setglobalvar", "getglobalvar", "incglobalvar", "decglobalvar", "addglobalvar",
            "hasglobalvar", "deleteglobalvar", "globalvarexists", "flushglobalvar",
        )
    }
}

/** 变量存取接口：local 以 chatKey 为作用域，global 全局。 */
interface MacroVars {
    fun get(chatKey: String?, name: String): String?
    fun set(chatKey: String?, name: String, value: String)
    fun inc(chatKey: String?, name: String): String
    fun dec(chatKey: String?, name: String): String
    fun add(chatKey: String?, name: String, value: String)
    fun has(chatKey: String?, name: String): Boolean
    fun delete(chatKey: String?, name: String)
}
