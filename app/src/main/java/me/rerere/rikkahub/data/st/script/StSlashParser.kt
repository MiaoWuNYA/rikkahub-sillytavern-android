package me.rerere.rikkahub.data.st.script

object StSlashParser {

    data class Segment(
        /** 原文（trim 后） */
        val raw: String,
        /** 命令名（小写，不含 `/`）；非命令段为空串 */
        val command: String,
        /** 命令参数原文；非命令段为整段文本 */
        val args: String,
        /** 是否为 `/` 命令段 */
        val isCommand: Boolean,
        /** `||` 标志：该段禁用"上一段输出自动注入"（对齐官方 no pipe injection；`{{pipe}}` 宏不受影响） */
        val noPipeInject: Boolean = false,
    )

    /** 切分片段（含 `||` 标志）。 */
    data class Part(val text: String, val noPipeInject: Boolean)

    /** 按 `|` 切分（引号/括号感知），返回非空片段（含 `||` 标志）。 */
    fun splitParts(text: String): List<Part> {
        val out = mutableListOf<Part>()
        val sb = StringBuilder()
        var pendingNoPipe = false
        var inDouble = false
        var inSingle = false
        var braceDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            // 转义字符：反斜杠后接引号或竖线时跳过（原样保留）
            if (c == '\\' && i + 1 < text.length &&
                (text[i + 1] == '"' || text[i + 1] == '\'' || text[i + 1] == '|')
            ) {
                sb.append(c).append(text[i + 1])
                i += 2
                continue
            }
            when {
                c == '"' && !inSingle -> inDouble = !inDouble
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '{' && !inDouble && !inSingle -> braceDepth++
                c == '}' && !inDouble && !inSingle -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                c == '(' && !inDouble && !inSingle -> parenDepth++
                c == ')' && !inDouble && !inSingle -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                c == '[' && !inDouble && !inSingle -> bracketDepth++
                c == ']' && !inDouble && !inSingle -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
            }
            if (c == '|' && !inDouble && !inSingle &&
                braceDepth == 0 && parenDepth == 0 && bracketDepth == 0
            ) {
                val piece = sb.toString()
                sb.clear()
                if (piece.isNotBlank()) {
                    out.add(Part(piece.trim(), pendingNoPipe))
                    pendingNoPipe = false
                }
                // `||`：下一段禁用管道注入（对齐官方 no pipe injection）
                if (i + 1 < text.length && text[i + 1] == '|') {
                    pendingNoPipe = true
                    i++
                }
                i++
                continue
            }
            sb.append(c)
            i++
        }
        if (sb.isNotBlank()) out.add(Part(sb.toString().trim(), pendingNoPipe))
        return out
    }

    /** 按 `|` 切分（仅文本）。 */
    fun splitPipelines(text: String): List<String> = splitParts(text).map { it.text }

    /** 解析为命令片段列表（含 `||` 标志）。 */
    fun parseParts(text: String): List<Segment> = splitParts(text).map { part ->
        val t = part.text
        if (t.startsWith("/")) {
            val body = t.substring(1).trimStart()
            val name = body.takeWhile { !it.isWhitespace() }.lowercase()
            val args = body.drop(name.length).trim()
            Segment(raw = t, command = name, args = args, isCommand = true, noPipeInject = part.noPipeInject)
        } else {
            Segment(raw = t, command = "", args = t, isCommand = false, noPipeInject = part.noPipeInject)
        }
    }

    /** 解析为命令片段列表（兼容入口）。 */
    fun parse(text: String): List<Segment> = parseParts(text)

    fun extractNamedArgs(args: String): Pair<Map<String, String>, String> {
        val named = linkedMapOf<String, String>()
        val rest = StringBuilder()
        var i = 0
        val n = args.length
        while (i < n) {
            while (i < n && args[i].isWhitespace()) i++
            if (i >= n) break
            if (i + 1 < n && args[i] == '{' && (args[i + 1] == ':' || args[i + 1] == '|')) {
                val span = StClosureSyntax.findClosureSpan(args, i)
                if (span != null && span.first == i) {
                    rest.append(args, span.first, span.second).append(' ')
                    i = span.second
                    continue
                }
            }
            val start = i
            while (i < n && !args[i].isWhitespace() && args[i] != '=') i++
            if (i < n && args[i] == '=' && i > start) {
                val key = args.substring(start, i)
                i++ // 跳过 '='
                val (value, next) = readValue(args, i)
                named[key.lowercase()] = value
                i = next
            } else {
                // 非命名参数（普通 token）落入其余文本
                rest.append(args, start, i).append(' ')
            }
        }
        return named to rest.toString().trim()
    }

    fun unwrapQuotes(text: String): String {
        val t = text.trim()
        if (t.length >= 2 && (t.first() == '"' && t.last() == '"' || t.first() == '\'' && t.last() == '\'')) {
            return t.substring(1, t.length - 1)
        }
        return t
    }

    private fun readValue(args: String, from: Int): Pair<String, Int> {
        var i = from
        if (i < args.length && (args[i] == '"' || args[i] == '\'')) {
            val quote = args[i]
            val sb = StringBuilder()
            i++
            while (i < args.length && args[i] != quote) {
                if (args[i] == '\\' && i + 1 < args.length) {
                    sb.append(args[i + 1])
                    i += 2
                } else {
                    sb.append(args[i])
                    i++
                }
            }
            if (i < args.length) i++ // 跳过闭合引号
            return sb.toString() to i
        }
        if (i + 1 < args.length && args[i] == '{' && (args[i + 1] == ':' || args[i + 1] == '|')) {
            val span = StClosureSyntax.findClosureSpan(args, i)
            if (span != null && span.first == i) {
                return args.substring(span.first, span.second) to span.second
            }
        }
        if (i < args.length && args[i] == '[') {
            val start = i
            var depth = 0
            var inQuote: Char? = null
            while (i < args.length) {
                val c = args[i]
                if (inQuote != null) {
                    if (c == '\\' && i + 1 < args.length) {
                        i += 2
                        continue
                    }
                    if (c == inQuote) inQuote = null
                    i++
                } else {
                    when (c) {
                        '"', '\'' -> {
                            inQuote = c
                            i++
                        }
                        '[' -> {
                            depth++
                            i++
                        }
                        ']' -> {
                            depth--
                            i++
                            if (depth <= 0) break
                        }
                        else -> i++
                    }
                }
            }
            return args.substring(start, i) to i
        }
        val start = i
        while (i < args.length && !args[i].isWhitespace()) i++
        return args.substring(start, i) to i
    }
}
