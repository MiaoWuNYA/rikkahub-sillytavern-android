package me.rerere.rikkahub.data.st.macro


/**
 * ST 宏引擎 —— 核心实现（Kotlin 原生移植）。
 *
 * 移植自 SillyTavern 1.18.0 `public/scripts/macros/engine/`：
 * MacroEngine（谓语编排）+ MacroLexer/Parser（语法）+ MacroCstWalker（求值）。
 *
 * 评估流程（对齐 ST MacroEngine.evaluate）：
 * 1. 预处理：legacy 语法重写（`{{time_UTC±n}}`、`<USER>` 等）
 * 2. 扫描解析：识别 `{{...}}` 宏节点（含嵌套、flags、参数分隔、变量简写）
 * 3. 作用域配对：同名开/闭宏配对，内容成为最后一个参数
 * 4. 文档级左到右求值：未知宏保留原文（内部嵌套已解析）
 * 5. 后处理：`\{`/`\}` 反转义、`{{trim}}` 清理、else 标记清理
 *
 * 简化点（PORT NOTE）：
 * - 依赖 chevrotain 的错误恢复机制不移植；语法非法片段按普通文本保留。
 * - filter（`>` flag 与 `|` 管道）未实现，与 ST 当前状态一致。
 */
object MacroEngine {

    /** else 分支标记（对应 ST ELSE_MARKER，用于 if 宏内部分割） */
    const val ELSE_MARKER: String = "\u0000\u001FELSE\u001F\u0000"

    private const val MAX_DEPTH = 32

    // ==================== 公共 API ====================

    /** 求值入口。input 为 null / 空串时返回空串。 */
    fun evaluate(input: String?, env: MacroEnv): String {
        if (input.isNullOrEmpty()) return ""
        // 对齐 ST substituteParams：以本次输入作为顶层原文（env.content / env.contentHash，
        // 供 {{pick}} 等宏构建确定性种子）
        env.content = input
        env.contentHash = MacroHash.getStringHash(input)
        return evaluateInternal(input, env, 0, 0)
    }

    // ==================== 主流程 ====================

    private fun evaluateInternal(input: String?, env: MacroEnv, contextOffset: Int, depth: Int): String {
        if (input.isNullOrEmpty()) return ""
        if (depth > MAX_DEPTH) return input

        val preProcessed = runPreProcessors(input)
        val macros = scanMacros(preProcessed)
        val evaluated = if (macros.isEmpty()) {
            preProcessed
        } else {
            evaluateDocument(preProcessed, macros, env, contextOffset, depth)
        }
        return runPostProcessors(evaluated)
    }

    // ==================== 预处理 / 后处理 ====================

    private val RE_LEGACY_TIME = Regex("\\{\\{time_(UTC[+-]\\d+)\\}\\}", RegexOption.IGNORE_CASE)
    private val RE_LT_USER = Regex("<USER>", RegexOption.IGNORE_CASE)
    private val RE_LT_BOT = Regex("<BOT>", RegexOption.IGNORE_CASE)
    private val RE_LT_CHAR = Regex("<CHAR>", RegexOption.IGNORE_CASE)
    private val RE_LT_GROUP = Regex("<GROUP>", RegexOption.IGNORE_CASE)
    private val RE_LT_CHAR_IF_NOT_GROUP = Regex("<CHARIFNOTGROUP>", RegexOption.IGNORE_CASE)
    private val RE_UNESCAPE_BRACES = Regex("\\\\([{}])")
    private val RE_STANDALONE_TRIM = Regex("(?:\\r?\\n)*\\{\\{trim\\}\\}(?:\\r?\\n)*", RegexOption.IGNORE_CASE)

    private fun runPreProcessors(text: String): String {
        var t = text
        // {{time_UTC-10}} => {{time::UTC-10}}
        t = RE_LEGACY_TIME.replace(t) { "{{time::${it.groupValues[1]}}}" }
        // legacy 尖括号标记重写为宏形式
        t = RE_LT_USER.replace(t, "{{user}}")
        t = RE_LT_BOT.replace(t, "{{char}}")
        t = RE_LT_CHAR.replace(t, "{{char}}")
        t = RE_LT_GROUP.replace(t, "{{group}}")
        t = RE_LT_CHAR_IF_NOT_GROUP.replace(t, "{{charIfNotGroup}}")
        return t
    }

    private fun runPostProcessors(text: String): String {
        var t = text
        // \{ → { 和 \} → }
        t = RE_UNESCAPE_BRACES.replace(t) { it.groupValues[1] }
        // {{trim}} 连带周围换行清除（legacy 行为）
        t = RE_STANDALONE_TRIM.replace(t, "")
        // 错误位置的 else 标记清理
        t = t.replace(ELSE_MARKER, "")
        return t
    }

    // ==================== 扫描解析 ====================

    /** 参数片段：在原文中的绝对区间 [startAbs, endAbsExclusive) 与（首尾已去空白的）文本 */
    private class ArgSpan(val startAbs: Int, val endAbsExclusive: Int, val text: String)

    /** 扫描出的宏节点 */
    private class ScannedMacro(
        val startOffset: Int,
        /** '}}' 中最后一个 '}' 的下标（inclusive） */
        val endOffset: Int,
        val inner: String,
        val flags: MacroFlags,
        val isVariable: Boolean,
        val varIsGlobal: Boolean = false,
        val varName: String = "",
        val varOperator: String? = null,
        val varValueRaw: String = "",
        val varValueAbs: Int = 0,
        val name: String = "",
        val args: List<ArgSpan> = emptyList(),
        /** canAccept 判定失败 / 无配对 close 时按原文保留 */
        var keepRaw: Boolean = false,
    )

    /** 变量简写操作符（对齐 ST lexer token 顺序） */
    private val VAR_OPERATORS = listOf(
        "++", "--", "??=", "??", "||=", "||", "-=", "==", "!=", ">=", ">", "<=", "<", "+=", "=",
    )

    private fun scanMacros(text: String): List<ScannedMacro> {
        val result = mutableListOf<ScannedMacro>()
        var i = 0
        val n = text.length
        while (i < n - 1) {
            val c = text[i]
            // 转义 \{ \} 不视为宏起始
            if (c == '\\' && (text[i + 1] == '{' || text[i + 1] == '}')) {
                i += 2
                continue
            }
            if (c == '{' && text[i + 1] == '{') {
                val end = findMacroEnd(text, i)
                if (end == -1) {
                    i += 2
                    continue
                }
                val inner = text.substring(i + 2, end)
                // '}}' 中最后一个 '}' 的下标（inclusive），与 ScannedMacro.endOffset 约定一致
                val parsed = parseMacroInner(inner, i + 2, i, end + 1)
                if (parsed != null) {
                    result += parsed
                }
                i = end + 2
            } else {
                i++
            }
        }
        return result
    }

    /** 从 start（指向 '{{'）起，按嵌套深度找到匹配的 '}}' 起始下标；找不到返回 -1 */
    private fun findMacroEnd(text: String, start: Int): Int {
        var depth = 1
        var i = start + 2
        val n = text.length
        while (i < n - 1) {
            val c = text[i]
            if (c == '\\' && (text[i + 1] == '{' || text[i + 1] == '}')) {
                i += 2
                continue
            }
            if (c == '{' && text[i + 1] == '{') {
                depth++
                i += 2
                continue
            }
            if (c == '}' && text[i + 1] == '}') {
                depth--
                if (depth == 0) return i
                i += 2
                continue
            }
            i++
        }
        return -1
    }

    /** 解析宏括号内部文本；无效返回 null（按普通文本保留） */
    private fun parseMacroInner(inner: String, innerAbsStart: Int, macroStart: Int, macroEnd: Int): ScannedMacro? {
        var p = 0
        val flagSymbols = mutableListOf<String>()
        // flags 与空白交错（`{{ ! ?macro}}`）；`//` 优先于 `/` flag
        while (p < inner.length) {
            val c = inner[p]
            when {
                c == '/' && p + 1 < inner.length && inner[p + 1] == '/' -> break
                c.isWhitespace() -> p++
                c in "!?~#/>" -> {
                    flagSymbols += c.toString()
                    p++
                }
                else -> break
            }
        }
        val flags = if (flagSymbols.isEmpty()) MacroFlags.EMPTY else MacroFlags.parse(flagSymbols)

        var q = p
        while (q < inner.length && inner[q].isWhitespace()) q++
        if (q >= inner.length) return null

        // 注释宏 {{// ...}}；对齐 ST 1.19.0 MacroCstWalker.js:194-206：
        // 若首参以 '/' 开头（如 {{///}} 或 {{////}}），标记为 closingBlock 以支持 {{//}}...{{///}} 多行作用域注释。
        if (inner.startsWith("//", q)) {
            val args = parseArgs(inner, (q + 2).coerceAtMost(inner.length), innerAbsStart)
            val isClosingComment = !flags.closingBlock && args.firstOrNull()?.text?.startsWith("/") == true
            val effectiveFlags = if (isClosingComment) flags.copy(closingBlock = true) else flags
            return ScannedMacro(
                startOffset = macroStart,
                endOffset = macroEnd,
                inner = inner,
                flags = effectiveFlags,
                isVariable = false,
                name = "//",
                args = args,
            )
        }

        // 变量简写 .name / $name
        if (inner[q] == '.' || inner[q] == '$') {
            val isGlobal = inner[q] == '$'
            var nameEnd = q + 1
            if (nameEnd >= inner.length || !inner[nameEnd].isLetter()) return null
            nameEnd++
            while (nameEnd < inner.length && (inner[nameEnd].isLetterOrDigit() || inner[nameEnd] == '_' || inner[nameEnd] == '-')) {
                nameEnd++
            }
            // 尾部连字符回退（避免吞掉 -- 操作符）
            while (nameEnd > q + 2 && inner[nameEnd - 1] == '-') nameEnd--

            val varName = inner.substring(q + 1, nameEnd)

            var opStart = nameEnd
            while (opStart < inner.length && inner[opStart].isWhitespace()) opStart++
            var op: String? = null
            var valueStart = opStart
            for (candidate in VAR_OPERATORS) {
                if (inner.startsWith(candidate, opStart)) {
                    op = candidate
                    valueStart = opStart + candidate.length
                    break
                }
            }

            if (op == null) {
                // 无操作符：剩余必须全为空白
                if (inner.substring(nameEnd).isNotBlank()) return null
                return ScannedMacro(
                    startOffset = macroStart,
                    endOffset = macroEnd,
                    inner = inner,
                    flags = flags,
                    isVariable = true,
                    varIsGlobal = isGlobal,
                    varName = varName,
                )
            }

            // 值：操作符后余下文本（trim 后），记录其起始绝对位置
            var vs = valueStart
            while (vs < inner.length && inner[vs].isWhitespace()) vs++
            var ve = inner.length
            while (ve > vs && inner[ve - 1].isWhitespace()) ve--
            val valueRaw = inner.substring(vs, ve)

            return ScannedMacro(
                startOffset = macroStart,
                endOffset = macroEnd,
                inner = inner,
                flags = flags,
                isVariable = true,
                varIsGlobal = isGlobal,
                varName = varName,
                varOperator = op,
                varValueRaw = valueRaw,
                varValueAbs = innerAbsStart + vs,
            )
        }

        // 宏名
        if (!inner[q].isLetter()) return null
        var nameEnd = q + 1
        while (nameEnd < inner.length && (inner[nameEnd].isLetterOrDigit() || inner[nameEnd] == '_' || inner[nameEnd] == '-')) {
            nameEnd++
        }
        val name = inner.substring(q, nameEnd)
        val args = parseArgs(inner, nameEnd, innerAbsStart)
        return ScannedMacro(
            startOffset = macroStart,
            endOffset = macroEnd,
            inner = inner,
            flags = flags,
            isVariable = false,
            name = name,
            args = args,
        )
    }

    /** 解析参数：`::` 列表 / `:` 单参 / 空白单参 */
    private fun parseArgs(inner: String, from: Int, innerAbsStart: Int): List<ArgSpan> {
        var q = from
        while (q < inner.length && inner[q].isWhitespace()) q++
        if (q >= inner.length) return emptyList()

        if (inner.startsWith("::", q)) {
            return splitByDoubleColon(inner, q + 2, innerAbsStart)
        }

        val s = if (inner[q] == ':') q + 1 else q
        return listOfNotNull(makeArg(inner, s, inner.length, innerAbsStart))
    }

    private fun splitByDoubleColon(inner: String, startInInner: Int, innerAbsStart: Int): List<ArgSpan> {
        val spans = mutableListOf<ArgSpan>()
        var depth = 0
        var segStart = startInInner
        var i = startInInner
        val n = inner.length
        while (i < n) {
            val c = inner[i]
            if (c == '\\' && i + 1 < n && (inner[i + 1] == '{' || inner[i + 1] == '}')) {
                i += 2
                continue
            }
            if (c == '{' && i + 1 < n && inner[i + 1] == '{') {
                depth++
                i += 2
                continue
            }
            if (c == '}' && i + 1 < n && inner[i + 1] == '}') {
                depth--
                i += 2
                continue
            }
            if (depth <= 0 && c == ':' && i + 1 < n && inner[i + 1] == ':') {
                makeArg(inner, segStart, i, innerAbsStart)?.let { spans += it }
                i += 2
                segStart = i
                continue
            }
            i++
        }
        makeArg(inner, segStart, n, innerAbsStart)?.let { spans += it }
        return spans
    }

    /** 截取 [from, toExclusive) 并去除首尾空白；空则返回 null */
    private fun makeArg(inner: String, from: Int, toExclusive: Int, innerAbsStart: Int): ArgSpan? {
        var s = from
        var e = toExclusive
        while (s < e && inner[s].isWhitespace()) s++
        while (e > s && inner[e - 1].isWhitespace()) e--
        if (s >= e) {
            // 空参数：保留一个空 span（位置退化），语义等价空串
            return ArgSpan(innerAbsStart + s, innerAbsStart + s, "")
        }
        return ArgSpan(innerAbsStart + s, innerAbsStart + e, inner.substring(s, e))
    }

    // ==================== 作用域配对 ====================

    /**
     * 配对 scoped 宏（对齐 ST MacroCstWalker.#processScopedMacros）。
     *
     * 算法（从左到右遍历宏列表；仅对未匹配、非 closing、不在 scope 内的 open 处理）：
     * 1. [findMatchingClosing] 查找配对 closing（同名、大小写不敏感；跳过已匹配项；
     *    深度仅对「可接受 scoped 内容」的 open 递增）
     * 2. 未找到配对 → 该 open 保持独立求值（继续处理）
     * 3. 找到配对但 [canAcceptScoped] 失败 → 两者 keepRaw（保留原文）
     * 4. 配对成功 → 记录 pair；中间项标记「已在 scope 内」（跳过后续 open 检查）
     * 最后：未被配对的 closing 全部 keepRaw。
     *
     * 与旧「纯名称栈配对」的差异：旧算法会先把最内层同名 open 与 close 配对，
     * 导致相邻同名宏（如 `{{user}}A{{user}}B{{/user}}`）的配对结果与 ST 不一致。
     */
    private fun matchScopes(macros: List<ScannedMacro>): Map<Int, Int> {
        val matched = BooleanArray(macros.size)
        val insideScope = BooleanArray(macros.size)
        val pairs = mutableMapOf<Int, Int>()

        for (i in macros.indices) {
            val m = macros[i]
            if (m.isVariable || m.flags.closingBlock) continue
            if (matched[i] || insideScope[i]) continue

            val closeIdx = findMatchingClosing(macros, matched, i)
            if (closeIdx == -1) continue

            if (!canAcceptScoped(m)) {
                // 无法接受 scoped 内容：两者保留原文（对应 ST keepRaw 分支）
                m.keepRaw = true
                macros[closeIdx].keepRaw = true
                matched[i] = true
                matched[closeIdx] = true
                continue
            }

            matched[i] = true
            matched[closeIdx] = true
            pairs[i] = closeIdx
            // 中间项已被本 scope 覆盖，将在 scoped 内容重解析时处理
            for (j in i + 1 until closeIdx) {
                insideScope[j] = true
            }
        }

        // 未配对的 closing → 保留原文（但未配对的 {{///}} 仍作为普通注释宏求值为空串）
        for (i in macros.indices) {
            val m = macros[i]
            if (!m.isVariable && m.flags.closingBlock && !matched[i] && m.name != "//") {
                m.keepRaw = true
            }
        }
        return pairs
    }

    /** 对齐 ST #findMatchingClosingMacro（变量表达式不参与匹配） */
    private fun findMatchingClosing(macros: List<ScannedMacro>, matched: BooleanArray, openingIdx: Int): Int {
        val target = macros[openingIdx].name.lowercase()
        var depth = 1
        for (i in openingIdx + 1 until macros.size) {
            val info = macros[i]
            if (info.isVariable) continue
            if (info.name.lowercase() != target) continue
            if (matched[i]) continue
            if (info.flags.closingBlock) {
                depth--
                if (depth == 0) return i
            } else {
                // 仅对可接受 scoped 内容的 open 递增深度（inline 宏不需要闭包）
                if (canAcceptScoped(info)) depth++
            }
        }
        return -1
    }

    /** 对齐 ST #canAcceptScopedContent */
    private fun canAcceptScoped(m: ScannedMacro): Boolean {
        if (m.isVariable) return false
        if (m.name == "//") {
            return m.args.isEmpty() || (m.args.size == 1 && m.args[0].text.isEmpty())
        }
        val def = MacroRegistry.getPrimaryMacro(m.name) ?: return true // 未知宏允许
        if (def.list) return false
        val newCount = m.args.size + 1
        return newCount in def.minArgs..def.maxArgs
    }

    // ==================== 文档求值 ====================

    private fun evaluateDocument(
        text: String,
        macros: List<ScannedMacro>,
        env: MacroEnv,
        contextOffset: Int,
        depth: Int,
    ): String {
        val pairs = matchScopes(macros)
        val sb = StringBuilder()
        var cursor = 0
        var i = 0
        while (i < macros.size) {
            val m = macros[i]
            val start = m.startOffset
            if (start < cursor) {
                i++
                continue
            }
            if (cursor < start) {
                sb.append(text, cursor, start)
            }
            if (m.keepRaw) {
                sb.append(text, start, m.endOffset + 1)
                cursor = m.endOffset + 1
                i++
                continue
            }
            if (!m.isVariable && m.flags.closingBlock && m.name != "//") {
                // 未配对的 close（keepRaw 已覆盖此情形，这里为防御）
                sb.append(text, start, m.endOffset + 1)
                cursor = m.endOffset + 1
                i++
                continue
            }
            val closeIdx = pairs[i]
            if (closeIdx != null) {
                val close = macros[closeIdx]
                val result = evalMacroNode(
                    text, m, env, contextOffset, depth,
                    scopedStart = m.endOffset + 1,
                    scopedEndExclusive = close.startOffset,
                )
                sb.append(result)
                cursor = close.endOffset + 1
                i = closeIdx + 1
                continue
            }
            val result = evalMacroNode(
                text, m, env, contextOffset, depth,
                scopedStart = -1,
                scopedEndExclusive = -1,
            )
            sb.append(result)
            cursor = m.endOffset + 1
            i++
        }
        if (cursor < text.length) {
            sb.append(text, cursor, text.length)
        }
        return sb.toString()
    }

    /** 求值单个宏节点 */
    private fun evalMacroNode(
        text: String,
        m: ScannedMacro,
        env: MacroEnv,
        contextOffset: Int,
        depth: Int,
        scopedStart: Int,
        scopedEndExclusive: Int,
    ): String {
        if (m.isVariable) {
            return evalVariableExpr(m, env, contextOffset, depth)
        }

        // 动态宏覆盖注册宏（对应 ST #resolveMacro 的 dynamicMacros 分支，查找大小写不敏感）
        val def = resolveDynamicDefinition(m.name, env) ?: MacroRegistry.getMacro(m.name)
        val delay = def?.delayArgResolution == true

        val evaluatedArgs = mutableListOf<String>()
        val rawArgs = mutableListOf<String>()
        for (span in m.args) {
            val raw = span.text
            rawArgs += raw
            evaluatedArgs += if (delay) {
                raw
            } else {
                evaluateInternal(text.substring(span.startAbs, span.endAbsExclusive), env, contextOffset + span.startAbs, depth + 1)
            }
        }

        if (scopedStart in 0..scopedEndExclusive) {
            val rawScoped = text.substring(scopedStart, scopedEndExclusive)
            rawArgs += rawScoped
            val value = if (delay) {
                rawScoped
            } else {
                val evaluated = evaluateInternal(rawScoped, env, contextOffset + scopedStart, depth + 1)
                if (!m.flags.preserveWhitespace) trimScopedContent(evaluated) else evaluated
            }
            evaluatedArgs += value
        }

        // 重建宏括号内文本（嵌套宏已求值；未知宏/校验失败/异常时按此保留原文，对齐 ST raw = {{rawInner}}）
        val rebuilt = rebuildRawInner(text, m, evaluatedArgs, scopedStart, scopedEndExclusive)

        // 未知宏：保留语法，内部嵌套已解析
        if (def == null) {
            return "{{$rebuilt}}"
        }

        // 无名参数 = 前 maxArgs 个（截断切片，缺失位不存在；对齐 ST slice(0, min(len, maxArgs))）
        val unnamed = evaluatedArgs.take(def.maxArgs)
        val listPart = if (def.list) evaluatedArgs.drop(def.maxArgs) else emptyList()

        val ctx = MacroExecutionContext(
            name = def.name,
            args = evaluatedArgs,
            unnamedArgs = unnamed,
            list = listPart,
            flags = m.flags,
            isScoped = scopedStart >= 0,
            raw = m.inner,
            rawOriginal = "{{" + m.inner + "}}",
            rawArgs = rawArgs,
            globalOffset = contextOffset + m.startOffset,
            env = env,
            resolve = { t ->
                evaluateInternal(t, env, contextOffset + m.startOffset, depth + 1)
            },
            normalize = { normalizeMacroResult(it) },
            trimContent = { trimScopedContent(it) },
            warn = { msg -> env.warn(msg) },
        )

        // arity 校验（对应 ST executeMacro 的 isArgsValid；strictArgs=true 时保留原文）
        if (!isArgsValid(def, evaluatedArgs.size)) {
            if (def.strictArgs) return "{{$rebuilt}}"
            ctx.warn(buildArityWarning(def, evaluatedArgs.size))
        }

        // 类型校验（对应 ST validateArgTypes；strictArgs=true 时保留原文）
        if (!validateArgTypes(def, ctx)) {
            return "{{$rebuilt}}"
        }

        val result = try {
            def.handler(ctx)
        } catch (e: Exception) {
            // 内部错误：保留原文（对齐 ST #resolveMacro catch → raw）
            return "{{$rebuilt}}"
        }
        return env.postProcess(result)
    }

    /** 动态宏 → 定义（对应 ST #resolveMacro 的 dynamicMacros 三形态；未命中返回 null） */
    private fun resolveDynamicDefinition(name: String, env: MacroEnv): MacroDefinition? {
        val impl = env.dynamicMacros[name.lowercase()] ?: return null
        return when (impl) {
            is DynamicMacro.Value -> MacroDefinition(
                name = name,
                minArgs = 0,
                maxArgs = 0,
                handler = { _ -> impl.value },
            )
            is DynamicMacro.Handler -> MacroDefinition(
                name = name,
                minArgs = 0,
                maxArgs = 0,
                handler = impl.handler,
            )
            is DynamicMacro.Definition -> impl.definition.withName(name)
        }
    }

    /** 对齐 ST isArgsValid（list 宏无上限；list.min 恒为 0） */
    private fun isArgsValid(def: MacroDefinition, argCount: Int): Boolean {
        if (!def.list) {
            return argCount in def.minArgs..def.maxArgs
        }
        // list 宏：至少 minArgs 个（list.min = 0）；超出的部分全部进入 list（无上限）
        return argCount >= def.minArgs
    }

    /** 生成 arity 警告消息（对齐 ST executeMacro 错误文案） */
    private fun buildArityWarning(def: MacroDefinition, argCount: Int): String {
        val expectedMin = def.minArgs
        val expectedMax: Int? = if (def.list) null else def.maxArgs
        val expectation = when {
            expectedMax != null && expectedMax != expectedMin -> "between $expectedMin and $expectedMax"
            expectedMax != null -> "$expectedMin"
            else -> "at least $expectedMin"
        }
        return "Macro \"${def.name}\" called with $argCount unnamed arguments but expects $expectation."
    }

    /**
     * 对齐 ST validateArgTypes：逐位校验无名参数类型。
     * strictArgs=true 且类型不符 → 返回 false（调用方保留原文）；否则警告并继续。
     */
    private fun validateArgTypes(def: MacroDefinition, ctx: MacroExecutionContext): Boolean {
        if (def.argDefs.isEmpty()) return true
        val count = minOf(def.argDefs.size, ctx.unnamedArgs.size)
        for (i in 0 until count) {
            val argDef = def.argDefs[i]
            val value = ctx.unnamedArgs[i] ?: continue
            if (argDef.types.any { isValueOfType(value, it) }) continue
            val optionalLabel = if (argDef.optional) " (optional)" else ""
            val message = "Macro \"${def.name}\" (position ${i + 1}$optionalLabel) argument \"${argDef.name}\" " +
                "expected type ${argDef.types.joinToString("/")} but got value \"$value\"."
            if (def.strictArgs) return false
            ctx.warn(message)
        }
        return true
    }

    /** 对齐 ST isValueOfType */
    private fun isValueOfType(value: String, type: String): Boolean {
        val trimmed = value.trim()
        return when (type) {
            "string" -> true
            "integer" -> Regex("^-?\\d+$").matches(trimmed)
            "number" -> JsNumberNormalizer.parse(trimmed)?.isFinite() == true
            "boolean" -> isTrueBoolean(trimmed) || isFalseBoolean(trimmed)
            else -> false
        }
    }

    /** 重建宏括号内文本（flags/name/分隔符原样，参数替换为已求值结果） */
    private fun rebuildRawInner(
        text: String,
        m: ScannedMacro,
        evaluatedArgs: List<String>,
        scopedStart: Int,
        scopedEndExclusive: Int,
    ): String {
        val innerStart = m.startOffset + 2
        val innerEndExclusive = m.endOffset - 1
        val entries = mutableListOf<Pair<IntRange, String>>()
        var idx = 0
        for (span in m.args) {
            val v = evaluatedArgs.getOrNull(idx) ?: span.text
            entries += (span.startAbs until span.endAbsExclusive) to v
            idx++
        }
        // scoped 内容（若存在）是参数中的最后一个，替换区间为 open 后到 close 前
        if (scopedStart in 0..scopedEndExclusive) {
            val v = evaluatedArgs.getOrNull(idx) ?: ""
            entries += (scopedStart until scopedEndExclusive) to v
        }
        entries.sortBy { it.first.first }
        val sb = StringBuilder()
        var cur = innerStart
        for ((range, v) in entries) {
            if (range.first > cur) sb.append(text, cur, range.first)
            sb.append(v)
            cur = range.last + 1
            if (cur < range.first) cur = range.first
        }
        if (cur < innerEndExclusive) {
            sb.append(text, cur, innerEndExclusive)
        }
        return sb.toString()
    }

    // ==================== 变量简写求值 ====================

    private fun evalVariableExpr(
        m: ScannedMacro,
        env: MacroEnv,
        contextOffset: Int,
        depth: Int,
    ): String {
        val vars = if (m.varIsGlobal) env.variables.global else env.variables.local
        val lazyValue: () -> String = {
            if (m.varValueRaw.isEmpty()) {
                ""
            } else {
                evaluateInternal(m.varValueRaw, env, contextOffset + m.varValueAbs, depth + 1)
            }
        }

        return when (m.varOperator) {
            null -> vars.get(m.varName) ?: ""
            "=" -> {
                vars.set(m.varName, lazyValue())
                ""
            }
            "++" -> vars.inc(m.varName)
            "--" -> vars.dec(m.varName)
            "+=" -> {
                vars.add(m.varName, lazyValue())
                ""
            }
            "-=" -> {
                // 对齐 ST：Number(lazyValue()) 语义（空串 → 0）；非数值 → 警告并静默返回空串
                val raw = lazyValue()
                val num = JsNumberNormalizer.parse(raw)
                if (num == null) {
                    env.warn("Variable shorthand \"-=\" operator requires a numeric value, got: \"$raw\"")
                } else {
                    vars.add(m.varName, JsNumberNormalizer.format(-num))
                }
                ""
            }
            "||" -> {
                val cur = vars.get(m.varName)
                if (isFalsyVariable(cur)) lazyValue() else cur ?: ""
            }
            "??" -> {
                if (vars.has(m.varName)) vars.get(m.varName) ?: "" else lazyValue()
            }
            "||=" -> {
                val cur = vars.get(m.varName)
                if (isFalsyVariable(cur)) {
                    val v = lazyValue()
                    vars.set(m.varName, v)
                    v
                } else {
                    cur ?: ""
                }
            }
            "??=" -> {
                if (vars.has(m.varName)) {
                    vars.get(m.varName) ?: ""
                } else {
                    val v = lazyValue()
                    vars.set(m.varName, v)
                    v
                }
            }
            "==" -> if ((vars.get(m.varName) ?: "") == lazyValue()) "true" else "false"
            "!=" -> if ((vars.get(m.varName) ?: "") != lazyValue()) "true" else "false"
            ">" -> compareNumbers(vars.get(m.varName), lazyValue()) { a, b -> a > b }
            ">=" -> compareNumbers(vars.get(m.varName), lazyValue()) { a, b -> a >= b }
            "<" -> compareNumbers(vars.get(m.varName), lazyValue()) { a, b -> a < b }
            "<=" -> compareNumbers(vars.get(m.varName), lazyValue()) { a, b -> a <= b }
            else -> ""
        }
    }

    private fun compareNumbers(left: String?, right: String, op: (Double, Double) -> Boolean): String {
        // 对齐 ST：Number() 语义（空串 → 0；不存在 → NaN → 'false'）
        val a = left?.let { JsNumberNormalizer.parse(it) } ?: return "false"
        val b = JsNumberNormalizer.parse(right) ?: return "false"
        if (a.isNaN() || b.isNaN()) return "false"
        return if (op(a, b)) "true" else "false"
    }

    /** 变量 falsy（对齐 ST：#executeVariableOperation 内 isFalsy = !val || isFalseBoolean(normalize(val))） */
    private fun isFalsyVariable(value: String?): Boolean {
        if (value.isNullOrEmpty()) return true
        return isFalseBoolean(value)
    }

    // ==================== 公共工具 ====================

    /** 结果归一化（对应 MacroEngine.normalizeMacroResult） */
    fun normalizeMacroResult(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is Number -> JsNumberNormalizer.format(value.toDouble())
        is Boolean -> value.toString()
        else -> value.toString()
    }

    /** scoped 内容 trim + 基准缩进 dedent（对应 MacroEngine.trimScopedContent） */
    fun trimScopedContent(content: String, trimIndent: Boolean = true): String {
        if (content.isEmpty()) return ""
        if (!trimIndent) return content.trim()

        val lines = content.split('\n')
        var baseIndent = 0
        for (line in lines) {
            if (line.trim().isNotEmpty()) {
                baseIndent = line.takeWhile { it == ' ' || it == '\t' }.length
                break
            }
        }
        if (baseIndent == 0) return content.trim()

        val dedented = lines.map { line ->
            val lineIndent = line.takeWhile { it == ' ' || it == '\t' }.length
            if (lineIndent >= baseIndent) line.substring(baseIndent) else line.trimStart()
        }
        return dedented.joinToString("\n").trim()
    }

    /** isTrueBoolean（对齐 ST utils.js：trim + 小写后匹配 ['on','true','1']） */
    fun isTrueBoolean(value: String): Boolean {
        return value.trim().lowercase() in TRUE_BOOLEAN_VALUES
    }

    /** isFalseBoolean（对齐 ST utils.js：trim + 小写后匹配 ['off','false','0']；空串不在列表内） */
    fun isFalseBoolean(value: String): Boolean {
        return value.trim().lowercase() in FALSE_BOOLEAN_VALUES
    }

    private val TRUE_BOOLEAN_VALUES = setOf("on", "true", "1")
    private val FALSE_BOOLEAN_VALUES = setOf("off", "false", "0")

    /**
     * 在顶层（不计入嵌套 if 深度）寻找 {{else}} 并分割。
     * 对应 ST splitOnTopLevelElse；找不到 else 时 elseBranch 为 null。
     */
    fun splitOnTopLevelElse(content: String): Pair<String, String?> {
        val macros = scanMacros(content)
        var depth = 0
        for (m in macros) {
            if (m.isVariable) continue
            val isClosing = m.flags.closingBlock
            when {
                m.name.equals("if", ignoreCase = true) && isClosing -> depth--
                m.name.equals("if", ignoreCase = true) && !isClosing && m.args.size == 1 -> depth++
                m.name.equals("else", ignoreCase = true) && depth == 0 -> {
                    return content.substring(0, m.startOffset) to content.substring(m.endOffset + 1)
                }
            }
        }
        return content to null
    }
}