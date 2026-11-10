package me.rerere.rikkahub.data.st.regex

object RegexScriptEngine {

    private const val CACHE_MAX_SIZE = 1000

    /** 编译缓存（LRU，对齐 ST RegexProvider；编译失败缓存 null，避免反复抛异常） */
    private val cache = object : LinkedHashMap<String, Regex?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Regex?>): Boolean =
            size > CACHE_MAX_SIZE
    }
    private val cacheLock = Any()

    /** 正则引擎诊断 logcat tag。 */
    private const val TAG = "RegexScriptEngine"

    internal const val BOUNDED_LOOKBEHIND_MAX = 256

    @Volatile var onCompileError: ((String) -> Unit)? = null

    /** [v214] 不定长 lookbehind 降级成功诊断钩子（tavern-runtime.log 可见降级后 pattern）。 */
    @Volatile var onCompileDegraded: ((String) -> Unit)? = null

    internal const val MIN_CATASTROPHIC_INPUT_LENGTH = 16
    internal const val MAX_CATASTROPHIC_INPUT_LENGTH = 4096
    internal const val MAX_REGEX_CHAR_ACCESS_STEPS = 250_000
    internal const val MAX_REGEX_EXECUTION_NANOS = 50_000_000L // 50ms per script invocation

    internal class RegexExecutionBudgetExceededException(message: String) : RuntimeException(message)

    internal class BoundedRegexCharSequence(
        private val delegate: CharSequence,
        private val maxSteps: Int = MAX_REGEX_CHAR_ACCESS_STEPS,
        private val timeoutNanos: Long = MAX_REGEX_EXECUTION_NANOS,
        /** 与父序列共享的计时起点：subSequence 不再重置超时。 */
        private val startNanos: Long = System.nanoTime(),
    ) : CharSequence {
        private var steps = 0

        override val length: Int
            get() = delegate.length

        override fun get(index: Int): Char {
            val s = ++steps
            if (s > maxSteps) {
                throw RegexExecutionBudgetExceededException("Regex step budget exceeded ($maxSteps steps)")
            }
            if ((s and 0x3FF) == 0 && (System.nanoTime() - startNanos) > timeoutNanos) {
                throw RegexExecutionBudgetExceededException("Regex time budget exceeded (${timeoutNanos / 1_000_000}ms)")
            }
            return delegate[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            // [P0 修复 2026-10-28] 子序列必须**继承**父序列的预算，不能重置。
            BoundedRegexCharSequence(
                delegate.subSequence(startIndex, endIndex),
                (maxSteps - steps).coerceAtLeast(1),
                timeoutNanos,
                startNanos,
            )

        override fun toString(): String = delegate.toString()
    }

    private fun hasRepeatableQuantifierAt(str: String, idx: Int): Boolean {
        if (idx >= str.length) return false
        return when (str[idx]) {
            '+', '*' -> true
            '{' -> {
                val close = str.indexOf('}', idx + 1)
                if (close <= idx + 1) return false
                val inner = str.substring(idx + 1, close)
                val parts = inner.split(',')
                when (parts.size) {
                    1 -> (parts[0].toIntOrNull() ?: 0) >= 2
                    2 -> parts[1].isEmpty() || (parts[1].toIntOrNull() ?: 0) >= 2
                    else -> false
                }
            }
            else -> false
        }
    }

    private fun hasInnerRepeatingToken(groupBody: String): Boolean {
        var i = 0
        while (i < groupBody.length) {
            when (groupBody[i]) {
                '\\' -> i += 2
                '+', '*' -> return true
                '{' -> {
                    val close = groupBody.indexOf('}', i + 1)
                    if (close > i + 1) {
                        val inner = groupBody.substring(i + 1, close)
                        val parts = inner.split(',')
                        if (parts.size == 2) {
                            val minVal = parts[0].toIntOrNull() ?: 0
                            if (parts[1].isEmpty()) return true
                            val maxVal = parts[1].toIntOrNull() ?: 0
                            if (maxVal >= 2 && (minVal != maxVal || maxVal >= 10)) return true
                        }
                        if (parts.size == 1 && (parts[0].toIntOrNull() ?: 0) >= 10) return true
                    }
                    i++
                }
                else -> i++
            }
        }
        return false
    }

    private fun splitTopLevelAlternation(groupBody: String): List<String> {
        val branches = mutableListOf<String>()
        var depth = 0
        var inCharClass = false
        var start = 0
        var i = 0
        while (i < groupBody.length) {
            val c = groupBody[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (inCharClass) {
                if (c == ']') inCharClass = false
                i++
                continue
            }
            when (c) {
                '[' -> inCharClass = true
                '(' -> depth++
                ')' -> if (depth > 0) depth--
                '|' -> if (depth == 0) {
                    branches += groupBody.substring(start, i)
                    start = i + 1
                }
            }
            i++
        }
        branches += groupBody.substring(start)
        return branches
    }

    private fun firstAtomSignature(branch: String): String {
        val b = branch.replace(Regex("^\\(\\?:|^\\(\\?<[^>]+>|^\\(\\?[imsug]+:", RegexOption.IGNORE_CASE), "").trim()
        if (b.isEmpty()) return "*"
        if (b.startsWith(".") || b.startsWith("\\w") || b.startsWith("\\S") || b.startsWith("[\\s\\S]")) return "ANY_WORD"
        if (b.startsWith("\\d")) return "DIGIT"
        if (b.startsWith("[")) {
            val close = b.indexOf(']')
            if (close > 1) {
                val cls = b.substring(1, close)
                if (cls.contains("a-z") || cls.contains("A-Z") || cls.contains("\\w")) return "ALPHA_OR_WORD"
                if (cls.contains("0-9") || cls.contains("\\d")) return "DIGIT"
                return "CLASS:${cls[0]}"
            }
        }
        return "LIT:${b[0]}"
    }

    private val ESCAPED_ATOM_RE = Regex("\\\\.")
    private val CHAR_CLASS_ATOM_RE = Regex("\\[(?:\\\\.|[^\\]\\\\])*\\]")
    private val BRANCH_INNER_QUANTIFIER_RE = Regex("[+*?]")

    private fun hasOverlappingAlternation(groupBody: String): Boolean {
        val branches = splitTopLevelAlternation(groupBody)
        if (branches.size < 2) return false
        for (rawBranch in branches) {
            val strippedBranch = CHAR_CLASS_ATOM_RE.replace(ESCAPED_ATOM_RE.replace(rawBranch, "X"), "C")
            if (BRANCH_INNER_QUANTIFIER_RE.containsMatchIn(strippedBranch)) return true
        }
        val sigs = branches.map(::firstAtomSignature)
        for (i in sigs.indices) {
            for (j in i + 1 until sigs.size) {
                val a = sigs[i]
                val b = sigs[j]
                if (a == b || a == "*" || b == "*") return true
                if ((a == "ANY_WORD" || a == "ALPHA_OR_WORD") && (b == "ANY_WORD" || b == "ALPHA_OR_WORD" || b == "DIGIT")) {
                    return true
                }
            }
        }
        return false
    }

    internal fun isCatastrophicRegexPattern(pattern: String): Boolean = isPotentiallyCatastrophicRegex(pattern)

    internal fun isDangerousVariableLookbehind(pattern: String): Boolean {
        val body = SLASH_FORM.matchEntire(pattern)?.groupValues?.getOrNull(1) ?: pattern
        // 1) 找所有变长后顾，记录区间
        val lookbehindRanges = mutableListOf<IntRange>()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\') { i += 2; continue }
            if (c == '[') { i = skipCharClass(body, i); continue }
            if (c == '(' && i + 3 < body.length && body[i + 1] == '?' && body[i + 2] == '<' &&
                (body[i + 3] == '=' || body[i + 3] == '!')
            ) {
                val end = findMatchingParen(body, i)
                if (end < 0) break
                lookbehindRanges.add(i..end)
                i = end + 1
                continue
            }
            i++
        }
        if (lookbehindRanges.isEmpty()) return false

        // 2) 后顾内部是否含量词
        val hasQuantifierInside = lookbehindRanges.any { r ->
            val inner = body.substring(r.first + 4, r.last)
            var k = 0
            var found = false
            while (k < inner.length) {
                val ch = inner[k]
                if (ch == '\\') { k += 2; continue }
                if (ch == '[') { k = skipCharClass(inner, k); continue }
                if (ch == '*' || ch == '+' || (ch == '{' && inner.indexOf('}', k + 1) > k + 1)) { found = true; break }
                k++
            }
            found
        }
        if (!hasQuantifierInside) return false

        // 3) 后顾外面是否还有可跨全文的扫描量词
        val outside = StringBuilder(body.length)
        var cursor = 0
        for (r in lookbehindRanges) {
            if (r.first > cursor) outside.append(body, cursor, r.first)
            cursor = r.last + 1
        }
        if (cursor < body.length) outside.append(body, cursor, body.length)
        val outStr = outside.toString()
        var j = 0
        while (j < outStr.length) {
            val ch = outStr[j]
            if (ch == '\\') { j += 2; continue }
            if (ch == '[') { j = skipCharClass(outStr, j); continue }
            if (ch == '*' || ch == '+') return true
            if (ch == '{') {
                val close = outStr.indexOf('}', j + 1)
                if (close > j + 1) {
                    val inner = outStr.substring(j + 1, close)
                    val parts = inner.split(',')
                    if (parts.size == 2 && parts[1].isEmpty()) return true // {m,} 无上界
                }
            }
            j++
        }
        return false
    }

    internal fun isPotentiallyCatastrophicRegex(pattern: String): Boolean {
        if (pattern.length < 4) return false
        val body = SLASH_FORM.matchEntire(pattern)?.groupValues?.getOrNull(1) ?: pattern
        val stack = ArrayDeque<Int>()
        var inCharClass = false
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (inCharClass) {
                if (c == ']') inCharClass = false
                i++
                continue
            }
            when (c) {
                '[' -> inCharClass = true
                '(' -> stack.addLast(i)
                ')' -> if (stack.isNotEmpty()) {
                    val openIdx = stack.removeLast()
                    var afterClose = i + 1
                    while (afterClose < body.length && body[afterClose] == ' ') afterClose++
                    if (hasRepeatableQuantifierAt(body, afterClose)) {
                        var inner = body.substring(openIdx + 1, i)
                        if (inner.startsWith("?:")) inner = inner.substring(2)
                        else if (inner.startsWith("?<") && inner.indexOf('>') > 0) inner = inner.substring(inner.indexOf('>') + 1)
                        if (hasInnerRepeatingToken(inner) || hasOverlappingAlternation(inner)) {
                            return true
                        }
                    }
                }
            }
            i++
        }
        return false
    }

    private val LEADING_INLINE_FLAGS_RE = Regex("^\\(\\?([imsug]+)\\)", RegexOption.IGNORE_CASE)

    internal fun extractInlineRegexFlags(rawBody: String): Pair<String, String> {
        var body = rawBody
        val flags = StringBuilder()
        while (true) {
            val m = LEADING_INLINE_FLAGS_RE.find(body) ?: break
            body = body.substring(m.value.length)
            flags.append(m.groupValues[1].lowercase())
        }
        return body to flags.toString()
    }

    fun stripUnmatchedBoundaryWrappers(input: String): String {
        var out = input
        for ((openCh, closeCh) in listOf('〔' to '〕', '〖' to '〗')) {
            val openCount = out.count { it == openCh }
            val closeCount = out.count { it == closeCh }
            if (openCount == closeCount) continue
            val trimmed = out.trim()
            if (openCount == 1 && closeCount == 0 && trimmed.startsWith(openCh)) {
                val idx = out.indexOf(openCh)
                if (idx >= 0) out = out.removeRange(idx, idx + 1).trimStart('\r', '\n')
            } else if (closeCount == 1 && openCount == 0 && trimmed.endsWith(closeCh)) {
                val idx = out.lastIndexOf(closeCh)
                if (idx >= 0) out = out.removeRange(idx, idx + 1).trimEnd('\r', '\n')
            }
        }
        return out
    }

    /** 编译正则（支持 `/pattern/flags` 内联形式与前导 `(?ims)` 标志，缓存键包含 pattern + flags + scriptOptions） */
    fun compileRegex(pattern: String, flags: String = "", scriptOptions: String = ""): Regex? {
        val cacheKey = if (flags.isEmpty() && scriptOptions.isEmpty()) {
            pattern
        } else {
            "$pattern\u0000$flags\u0000$scriptOptions"
        }
        synchronized(cacheLock) {
            if (cache.containsKey(cacheKey)) return cache[cacheKey]
        }
        var compiled: Regex? = null
        var failure: Exception? = null
        try {
            compiled = compileRegexUncached(pattern, flags)
        } catch (e: Exception) {
            failure = e
            val degraded = try {
                degradeUnboundedLookbehind(pattern)
            } catch (_: Exception) {
                null
            }
            if (degraded != null && degraded != pattern) {
                try {
                    compiled = compileRegexUncached(degraded, flags)
                    val msg = "[regex] degraded unbounded lookbehind: pattern=$pattern -> $degraded"
                    warn(msg)
                    onCompileDegraded?.invoke(msg)
                } catch (e2: Exception) {
                    failure = e2
                    compiled = null
                }
            }
        }

        if (compiled == null) {
            val msg = "[regex] compile failed: pattern=$pattern error=${failure?.message}"
            warn(msg)
            onCompileError?.invoke(msg)
        }
        synchronized(cacheLock) { cache[cacheKey] = compiled }
        return compiled
    }

    /** logcat 诊断；单测 JVM 上 android.util.Log 未 mock 会抛异常，故 runCatching 包裹。 */
    private fun warn(message: String) {
        runCatching { android.util.Log.w(TAG, message) }
    }

    private val JS_REGEX_OPTIONS = setOf(RegexOption.UNIX_LINES)

    private val SLASH_FORM = Regex("^/(.*)/([a-zA-Z]*)$", RegexOption.DOT_MATCHES_ALL)

    private val THINK_BLOCK_SHIELD_REGEX = Regex(
        "<(think|thinking|thought)\\b[^>]*>[\\s\\S]*?(?:</\\1>|$)",
        RegexOption.IGNORE_CASE,
    )

    private fun parseRegexOptions(flagChars: String): Set<RegexOption> {
        var options = JS_REGEX_OPTIONS
        for (c in flagChars.lowercase()) {
            options = when (c) {
                'i' -> options + RegexOption.IGNORE_CASE
                'm' -> options + RegexOption.MULTILINE
                's' -> options + RegexOption.DOT_MATCHES_ALL
                else -> options
            }
        }
        return options
    }

    private fun compileRegexUncached(pattern: String, flags: String = ""): Regex {
        val slashForm = SLASH_FORM.matchEntire(pattern)
        if (slashForm == null) {
            val (cleanBody, inlineFlags) = extractInlineRegexFlags(pattern)
            return Regex(cleanBody, parseRegexOptions(flags + inlineFlags))
        }
        val (cleanBody, inlineFlags) = extractInlineRegexFlags(slashForm.groupValues[1])
        val mergedFlags = slashForm.groupValues[2] + flags + inlineFlags
        return Regex(cleanBody, parseRegexOptions(mergedFlags))
    }

    internal fun degradeUnboundedLookbehind(pattern: String): String? {
        val out = StringBuilder(pattern.length + 16)
        var i = 0
        var changed = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> {
                    out.append(c)
                    if (i + 1 < pattern.length) out.append(pattern[i + 1])
                    i += 2
                }
                c == '[' -> {
                    val end = skipCharClass(pattern, i)
                    out.append(pattern, i, end)
                    i = end
                }
                c == '(' && i + 3 < pattern.length &&
                    pattern[i + 1] == '?' && pattern[i + 2] == '<' &&
                    (pattern[i + 3] == '=' || pattern[i + 3] == '!') -> {
                    val end = findMatchingParen(pattern, i)
                    if (end < 0) {
                        out.append(pattern, i, pattern.length)
                        i = pattern.length
                    } else {
                        val body = pattern.substring(i + 4, end)
                        val rewritten = rewriteUnboundedQuantifiers(body)
                        if (rewritten != body) changed = true
                        out.append(pattern, i, i + 4).append(rewritten).append(')')
                        i = end + 1
                    }
                }
                else -> {
                    out.append(c)
                    i += 1
                }
            }
        }
        return if (changed) out.toString() else null
    }

    private fun rewriteUnboundedQuantifiers(body: String): String {
        val out = StringBuilder(body.length + 16)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' -> {
                    out.append(c)
                    if (i + 1 < body.length) out.append(body[i + 1])
                    i += 2
                }
                c == '[' -> {
                    val end = skipCharClass(body, i)
                    out.append(body, i, end)
                    i = end
                }
                c == '*' -> {
                    when (body.getOrNull(i + 1)) {
                        '?' -> {
                            out.append("{0,$BOUNDED_LOOKBEHIND_MAX}?")
                            i += 2
                        }
                        '+' -> {
                            out.append("{0,$BOUNDED_LOOKBEHIND_MAX}+")
                            i += 2
                        }
                        else -> {
                            out.append("{0,$BOUNDED_LOOKBEHIND_MAX}")
                            i += 1
                        }
                    }
                }
                c == '+' -> {
                    when (body.getOrNull(i + 1)) {
                        '?' -> {
                            out.append("{1,$BOUNDED_LOOKBEHIND_MAX}?")
                            i += 2
                        }
                        '+' -> {
                            out.append("{1,$BOUNDED_LOOKBEHIND_MAX}+")
                            i += 2
                        }
                        else -> {
                            out.append("{1,$BOUNDED_LOOKBEHIND_MAX}")
                            i += 1
                        }
                    }
                }
                c == '{' -> {
                    val close = body.indexOf('}', i + 1)
                    val inner = if (close > i) body.substring(i + 1, close) else null
                    if (inner != null && UNBOUNDED_QUANTIFIER.matches(inner)) {
                        out.append('{').append(inner.dropLast(1))
                            .append(',').append(BOUNDED_LOOKBEHIND_MAX).append('}')
                        i = close + 1
                        val suffix = body.getOrNull(i)
                        if (suffix == '?' || suffix == '+') {
                            out.append(suffix)
                            i += 1
                        }
                    } else {
                        out.append(c)
                        i += 1
                    }
                }
                else -> {
                    out.append(c)
                    i += 1
                }
            }
        }
        return out.toString()
    }

    private val UNBOUNDED_QUANTIFIER = Regex("^\\d+,$")

    private fun findMatchingParen(pattern: String, openIndex: Int): Int {
        var depth = 0
        var i = openIndex
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> i += 2
                c == '[' -> i = skipCharClass(pattern, i)
                c == '(' -> {
                    depth++
                    i++
                }
                c == ')' -> {
                    depth--
                    if (depth == 0) return i
                    i++
                }
                else -> i++
            }
        }
        return -1
    }

    private fun skipCharClass(pattern: String, start: Int): Int {
        var i = start + 1
        if (i < pattern.length && pattern[i] == '^') i++
        if (i < pattern.length && pattern[i] == ']') i++
        while (i < pattern.length) {
            when (pattern[i]) {
                '\\' -> i += 2
                ']' -> return i + 1
                else -> i++
            }
        }
        return pattern.length
    }

    private fun isGlobalPattern(pattern: String): Boolean {
        val slashForm = SLASH_FORM.matchEntire(pattern)
            ?: return true
        return slashForm.groupValues[2].contains('g', ignoreCase = true)
    }

    private val REPLACEMENT_TOKEN = Regex("\\{\\{match\\}\\}|\\$\\$|\\$&|\\$(\\d+)|\\$<([^>]+)>", RegexOption.IGNORE_CASE)

    fun runRegexScript(
        script: RegexScript,
        rawString: String,
        substitute: (String) -> String = { it },
    ): String {
        if (script.disabled) return rawString
        if (script.findRegex.isEmpty()) return rawString
        if (rawString.isEmpty()) return rawString
        val normalizedRaw = if (rawString.indexOf('\r') >= 0) {
            rawString.replace("\r\n", "\n").replace('\r', '\n')
        } else {
            rawString
        }

        val pattern = when (script.substituteRegex) {
            SubstituteRegex.RAW -> substitute(script.findRegex)
            SubstituteRegex.ESCAPED -> Regex("\\{\\{[^{}]*\\}\\}").replace(script.findRegex) { m ->
                sanitizeRegexMacro(substitute(m.value))
            }
            else -> script.findRegex
        }
        if (isPotentiallyCatastrophicRegex(pattern) && normalizedRaw.length >= MIN_CATASTROPHIC_INPUT_LENGTH) {
            val msg = "[regex] skipped potentially catastrophic regex on input (${normalizedRaw.length} chars): pattern=$pattern"
            warn(msg)
            onCompileDegraded?.invoke(msg)
            return normalizedRaw
        }
        // [P0 根治 2026-10-28] 变长后顾 + 全文扫描量词 = 隐性 ReDoS。
        if (normalizedRaw.length >= MIN_CATASTROPHIC_INPUT_LENGTH && isDangerousVariableLookbehind(pattern)) {
            val msg = "[regex] skipped variable-lookbehind ReDoS risk (${normalizedRaw.length} chars): pattern=$pattern"
            warn(msg)
            onCompileDegraded?.invoke(msg)
            return normalizedRaw
        }
        val scriptOptionsKey = "${script.substituteRegex}:${script.markdownOnly}:${script.promptOnly}"
        val regex = compileRegex(pattern, flags = "", scriptOptions = scriptOptionsKey) ?: return rawString
        val global = isGlobalPattern(pattern)

        // 若该脚本并非专门处理 <think>/<thinking>/<thought> 标签，但输入文本中含有未剥离的思维链块，
        val scriptTargetsThinking = pattern.contains("think", ignoreCase = true) ||
            pattern.contains("thought", ignoreCase = true) ||
            (RegexPlacement.REASONING in script.placement && script.placement.size == 1)
        val shieldedBlocks = mutableListOf<String>()
        val workingInput = if (!scriptTargetsThinking &&
            (normalizedRaw.contains("<think", ignoreCase = true) || normalizedRaw.contains("<thought", ignoreCase = true))
        ) {
            THINK_BLOCK_SHIELD_REGEX.replace(normalizedRaw) { m ->
                val idx = shieldedBlocks.size
                shieldedBlocks.add(m.value)
                "\u0000__RIKKA_THINK_SHIELD_${idx}__\u0000"
            }
        } else {
            normalizedRaw
        }

        val sb = StringBuilder()
        var cursor = 0
        var matched = false
        try {
            val boundedInput = BoundedRegexCharSequence(workingInput)
            for (match in regex.findAll(boundedInput)) {
                sb.append(workingInput, cursor, match.range.first)
                val mVal = match.value
                // 防御 1：若 match 跨过了思维链保护占位符（\u0000__RIKKA_THINK_SHIELD_N__\u0000），重锚定到最后一个占位符之后
                if (mVal.contains("\u0000__RIKKA_THINK_SHIELD_")) {
                    val lastShieldEnd = mVal.lastIndexOf("__\u0000")
                    if (lastShieldEnd >= 0) {
                        val afterShieldIdx = lastShieldEnd + 3
                        val tailAfterShield = mVal.substring(afterShieldIdx)
                        val subMatch = regex.find(BoundedRegexCharSequence(tailAfterShield))
                        if (subMatch != null) {
                            sb.append(mVal, 0, afterShieldIdx + subMatch.range.first)
                            sb.append(buildReplacement(script, subMatch, substitute))
                            sb.append(tailAfterShield, subMatch.range.last + 1, tailAfterShield.length)
                            cursor = match.range.last + 1
                            matched = true
                            if (!global) break
                            continue
                        }
                    }
                }
                // 防御 2：当贪婪正则把同一消息内的多对反引号吞入同一个 match 时，逐个扫描内部独立的反引号对
                if (mVal.length > 3 && mVal.first() == '`' && mVal.last() == '`' && mVal.indexOf('`', 1) < mVal.lastIndex) {
                    val pairRegex = Regex("`[^`\\r\\n]+`")
                    val pairMatches = pairRegex.findAll(mVal).toList()
                    if (pairMatches.any { pm -> regex.matchEntire(BoundedRegexCharSequence(pm.value)) != null }) {
                        var localCur = 0
                        for (pm in pairMatches) {
                            sb.append(mVal, localCur, pm.range.first)
                            val exactPairMatch = regex.matchEntire(BoundedRegexCharSequence(pm.value))
                            if (exactPairMatch != null) {
                                sb.append(buildReplacement(script, exactPairMatch, substitute))
                            } else {
                                sb.append(pm.value)
                            }
                            localCur = pm.range.last + 1
                        }
                        sb.append(mVal, localCur, mVal.length)
                        cursor = match.range.last + 1
                        matched = true
                        if (!global) break
                        continue
                    }
                }
                sb.append(buildReplacement(script, match, substitute))
                cursor = match.range.last + 1
                matched = true
                if (!global) break
            }
        } catch (budgetErr: RegexExecutionBudgetExceededException) {
            val msg = "[regex] aborted excessive backtracking (${budgetErr.message}): pattern=$pattern"
            warn(msg)
            onCompileDegraded?.invoke(msg)
            return normalizedRaw
        }
        if (!matched) return normalizedRaw
        sb.append(workingInput, cursor, workingInput.length)
        var out = sb.toString()
        if (shieldedBlocks.isNotEmpty()) {
            shieldedBlocks.forEachIndexed { idx, originalBlock ->
                out = out.replace("\u0000__RIKKA_THINK_SHIELD_${idx}__\u0000", originalBlock)
            }
        }
        return out
    }

    /** 组装单个匹配的替换文本（对齐 ST replace 回调 + filterString + 末尾宏替换） */
    private fun buildReplacement(
        script: RegexScript,
        match: MatchResult,
        substitute: (String) -> String,
    ): String {
        fun filtered(value: String?): String {
            if (value.isNullOrEmpty()) return ""
            var out: String = value
            for (trim in script.trimStrings) {
                val sub = substitute(trim)
                if (sub.isNotEmpty()) out = out.replace(sub, "")
            }
            return out
        }

        val built = REPLACEMENT_TOKEN.replace(script.replaceString) { token ->
            val num = token.groupValues[1]
            val name = token.groupValues[2]
            when {
                token.value == "$$" -> "$"
                token.value == "$&" || token.value.equals("{{match}}", ignoreCase = true) -> filtered(match.value)
                num.isNotEmpty() -> filtered(match.groupValues.getOrNull(num.toIntOrNull() ?: -1))
                name.isNotEmpty() -> filtered((match.groups as? MatchNamedGroupCollection)?.get(name)?.value)
                else -> filtered(match.value)
            }
        }
        return substitute(built)
    }

    fun getRegexedString(
        rawString: String,
        scripts: List<RegexScript>,
        placement: Int,
        isMarkdown: Boolean = false,
        isPrompt: Boolean = false,
        isEdit: Boolean = false,
        depth: Int? = null,
        substitute: (String) -> String = { it },
    ): String {
        if (rawString.isEmpty()) return rawString
        var finalString = rawString
        for (script in scripts) {
            val applies = (script.markdownOnly && isMarkdown) ||
                (script.promptOnly && isPrompt) ||
                (!script.markdownOnly && !script.promptOnly)
            if (!applies) continue
            if (isEdit && !script.runOnEdit) continue
            if (depth != null) {
                val min = script.minDepth
                if (min != null && min >= -1 && depth < min) continue
                val max = script.maxDepth
                if (max != null && max >= 0 && depth > max) continue
            }
            if (placement !in script.placement) continue
            finalString = runRegexScript(script, finalString, substitute)
        }
        return finalString
    }

    fun sanitizeRegexMacro(value: String): String {
        if (value.isEmpty()) return value
        val sb = StringBuilder(value.length * 2)
        for (c in value) {
            when (c) {
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u000B' -> sb.append("\\v")
                '\u000C' -> sb.append("\\f")
                '\u0000' -> sb.append("\\0")
                '.', '^', '$', '*', '+', '?', '{', '}', '[', ']', '\\', '/', '|', '(', ')' -> {
                    sb.append('\\')
                    sb.append(c)
                }
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}

fun String.getStRegexed(
    scripts: List<RegexScript>,
    placement: Int,
    isMarkdown: Boolean = false,
    isPrompt: Boolean = false,
    depth: Int? = null,
): String {
    if (scripts.isEmpty()) return this
    return RegexScriptEngine.getRegexedString(
        rawString = this,
        scripts = scripts,
        placement = placement,
        isMarkdown = isMarkdown,
        isPrompt = isPrompt,
        depth = depth,
    )
}
