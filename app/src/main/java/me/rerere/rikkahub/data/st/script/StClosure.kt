package me.rerere.rikkahub.data.st.script

import java.util.concurrent.ConcurrentHashMap

data class StClosure(
    /** 闭包体脚本（不含 `{: :}` 或 `{| |}`，已剥离头部命名参数声明） */
    val body: String,
    /** 头部命名参数声明（官方 closure.argumentList） */
    val argumentList: List<StClosureArg> = emptyList(),
    /** 原始字面量（含定界符，日志/调试用） */
    val raw: String = body,
)

/** 闭包命名参数声明（`key=value`，官方 SlashCommandNamedArgumentAssignment 子集） */
data class StClosureArg(val name: String, val value: String)

object StClosureSyntax {

    const val OPEN = "{:"
    const val CLOSE = ":}"
    const val ALT_OPEN = "{|"
    const val ALT_CLOSE = "|}"

    /** 头部命名参数声明的匹配（官方 testNamedArgument：标识符 + `=` + 单 token/引号值） */
    private val HEAD_NAMED_ARG = Regex("""^\s*([A-Za-z0-9_]+)\s*=\s*("[^"]*"|'[^']*'|[^\s]+)""")

    /** `{{arg::key}}`（官方宏引擎动态宏；大小写不敏感） */
    private val ARG_MACRO = Regex("""\{\{\s*arg::([^}]*?)\s*\}\}""", RegexOption.IGNORE_CASE)

    /** 解析整段 `{: ... :}` 或 `{| ... |}`（前后允许空白）；不是合法同种闭合字面量返回 null */
    fun parse(text: String): StClosure? {
        val trimmed = text.trim()
        val (openTok, closeTok) = when {
            trimmed.startsWith(OPEN) && trimmed.endsWith(CLOSE) -> OPEN to CLOSE
            trimmed.startsWith(ALT_OPEN) && trimmed.endsWith(ALT_CLOSE) -> ALT_OPEN to ALT_CLOSE
            else -> return null
        }
        if (trimmed.length < openTok.length + closeTok.length) return null
        // 确保外层定界符真正闭合于末尾（拒绝如 "{: a :} {: b :}" 或定界符错配）
        if (findClosureEnd(trimmed, 0) != trimmed.length) return null
        val inner = trimmed.substring(openTok.length, trimmed.length - closeTok.length)
        val (args, body) = splitLeadingNamedArgs(inner)
        return StClosure(body = body, argumentList = args, raw = trimmed)
    }

    /** 判断单个 token 是否为合法闭合的闭包字面量 */
    fun isClosureLiteral(token: String): Boolean = parse(token) != null

    fun findClosureSpan(text: String, from: Int = 0): Pair<Int, Int>? {
        var i = from.coerceAtLeast(0)
        while (i < text.length - 1) {
            if (text.startsWith(OPEN, i) || text.startsWith(ALT_OPEN, i)) {
                val end = findClosureEnd(text, i)
                if (end > i) {
                    return i to end
                }
                i += 2
                continue
            }
            i++
        }
        return null
    }

    fun takeFirstClosure(text: String): Pair<StClosure, String>? {
        val span = findClosureSpan(text, 0) ?: return null
        val closure = parse(text.substring(span.first, span.second)) ?: return null
        val rest = text.substring(0, span.first) + text.substring(span.second)
        return closure to rest
    }

    fun findClosureEnd(text: String, start: Int): Int {
        if (start < 0 || start >= text.length - 1) return -1
        if (!text.startsWith(OPEN, start) && !text.startsWith(ALT_OPEN, start)) return -1
        val stack = ArrayDeque<String>()
        var i = start
        while (i < text.length) {
            if (text[i] == '\\' && i + 1 < text.length) {
                i += 2
                continue
            }
            if (i < text.length - 1) {
                when {
                    text.startsWith(OPEN, i) -> {
                        stack.addLast(CLOSE)
                        i += OPEN.length
                        continue
                    }
                    text.startsWith(ALT_OPEN, i) -> {
                        stack.addLast(ALT_CLOSE)
                        i += ALT_OPEN.length
                        continue
                    }
                    text.startsWith(CLOSE, i) -> {
                        if (stack.isEmpty() || stack.last() != CLOSE) return -1
                        stack.removeLast()
                        i += CLOSE.length
                        if (stack.isEmpty()) return i
                        continue
                    }
                    text.startsWith(ALT_CLOSE, i) -> {
                        if (stack.isEmpty() || stack.last() != ALT_CLOSE) return -1
                        stack.removeLast()
                        i += ALT_CLOSE.length
                        if (stack.isEmpty()) return i
                        continue
                    }
                }
            }
            i++
        }
        return -1
    }

    fun tokenizeArgs(args: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < args.length) {
            val c = args[i]
            when {
                c.isWhitespace() -> {
                    if (sb.isNotEmpty()) {
                        out += sb.toString()
                        sb.clear()
                    }
                    i++
                }
                c == '{' && i + 1 < args.length && (args[i + 1] == ':' || args[i + 1] == '|') -> {
                    val end = findClosureEnd(args, i)
                    if (end > i) {
                        sb.append(args, i, end)
                        i = end
                    } else {
                        sb.append(c)
                        i++
                    }
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    sb.append(c)
                    i++
                    while (i < args.length && args[i] != quote) {
                        if (args[i] == '\\' && i + 1 < args.length) {
                            sb.append(args[i]).append(args[i + 1])
                            i += 2
                        } else {
                            sb.append(args[i])
                            i++
                        }
                    }
                    if (i < args.length) {
                        sb.append(args[i])
                        i++
                    }
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    fun substituteArgs(text: String, args: Map<String, String>): String {
        if (!text.contains("{{")) return text
        val lowerArgs = args.entries.associate { it.key.lowercase() to it.value }
        return ARG_MACRO.replace(text) { match ->
            val key = match.groupValues[1].trim()
            args[key]
                ?: lowerArgs[key.lowercase()]
                ?: args["*"]
                ?: lowerArgs["*"]
                ?: ""
        }
    }

    /** 官方 parseClosure 的头部命名参数循环：消费开头的 `key=value` 声明。 */
    private fun splitLeadingNamedArgs(inner: String): Pair<List<StClosureArg>, String> {
        val args = mutableListOf<StClosureArg>()
        var rest = inner
        while (true) {
            val match = HEAD_NAMED_ARG.find(rest) ?: break
            if (match.range.first != 0) break
            val rawValue = match.groupValues[2]
            val value = if (rawValue.length >= 2 && (
                    (rawValue.startsWith("\"") && rawValue.endsWith("\"")) ||
                        (rawValue.startsWith("'") && rawValue.endsWith("'"))
                    )
            ) {
                rawValue.substring(1, rawValue.length - 1)
            } else {
                rawValue
            }
            args += StClosureArg(name = match.groupValues[1], value = value)
            rest = rest.substring(match.range.last + 1)
        }
        return args to rest.trim()
    }
}

object StClosureStore {

    const val GLOBAL_SCOPE = "__st_closure_global__"

    private val scopes = ConcurrentHashMap<String, ConcurrentHashMap<String, StClosure>>()

    private fun scopeOf(scopeKey: String?): ConcurrentHashMap<String, StClosure> =
        scopes.computeIfAbsent(scopeKey ?: GLOBAL_SCOPE) { ConcurrentHashMap() }

    fun get(scopeKey: String?, name: String): StClosure? = scopeOf(scopeKey)[name]

    fun put(scopeKey: String?, name: String, closure: StClosure) {
        scopeOf(scopeKey)[name] = closure
    }

    fun remove(scopeKey: String?, name: String) {
        scopeOf(scopeKey).remove(name)
    }

    fun clearScope(scopeKey: String?) {
        scopeOf(scopeKey).clear()
    }

    /** 测试/清理用 */
    fun clearAll() {
        scopes.clear()
    }
}
