package me.rerere.rikkahub.data.st.script

import me.rerere.ai.core.MessageRole

interface StSlashHost {
    /** 当前对话键（变量作用域）；为空表示无对话上下文。 */
    val chatKey: String?

    /** 变量族命令（setvar/getvar/addvar/incvar/decvar/flushvar/listvar）。 */
    fun varOp(cmd: String, name: String, value: String): String?

    /** 消息插入（send/sendas/sys）。role 已映射为 用户/助手/系统。 */
    fun insertMessage(role: MessageRole, text: String, name: String?, at: Int?): String?

    fun trigger(): String?
    fun continueGen(prompt: String): String?
    fun impersonate(prompt: String): String?
    fun sysgen(prompt: String, name: String?, at: Int?, trim: Boolean): String?
    fun gen(prompt: String, asRole: String, lock: Boolean, length: Int, name: String?, trim: Boolean): String?
    fun genRaw(prompt: String, systemPrompt: String = "", length: Int = 0, trim: Boolean = false): String? =
        gen(prompt = prompt, asRole = "system", lock = false, length = length, name = systemPrompt.takeIf { it.isNotBlank() }, trim = trim)
    fun persona(name: String, mode: String): String?
    fun renameChar(name: String): String?
    fun js(code: String): String?
    fun tavern(sub: String): String?
    fun echo(text: String, title: String?): String?

    /** /popup：弹出模态提示/确认框；返回 null 时回退非阻塞默认行为。 */
    fun showPopup(
        text: String,
        title: String? = null,
        expectResult: Boolean = false,
        okButton: String? = null,
        cancelButton: String? = null,
    ): String? = null

    /** /input：弹出文本输入对话框，返回用户输入内容；返回 null 时回退默认值。 */
    fun showInput(
        prompt: String,
        title: String? = null,
        defaultValue: String = "",
    ): String? = null

    /** /buttons：弹出多按钮选择对话框，返回选中按钮标签（或 multiple=true 时 JSON 数组）；返回 null 时回退首个标签。 */
    fun showButtons(
        prompt: String,
        labels: List<String>,
        multiple: Boolean = false,
    ): String? = null

    fun hideMessages(value: String, unhide: Boolean, nameFilter: String?): String?

    fun swipe(direction: String, await: Boolean): String?

    fun expressionFallback(label: String): String? = null

    // ---- ST Checkpoints（书签，对齐 bookmarks.js）----

    fun checkpointCreate(mesId: Int?, name: String?): String? = null

    /** /checkpoint-go：打开 [mesId]（null = 最后一条）消息上挂的检查点会话。 */
    fun checkpointGo(mesId: Int?): String? = null

    /** /checkpoint-exit：从当前检查点返回父会话。 */
    fun checkpointExit(): String? = null

    /** /checkpoint-parent：返回当前检查点的父会话名字。 */
    fun checkpointParent(): String? = null

    /** /checkpoint-get：返回 [mesId]（null = 最后一条）消息上的检查点链接名。 */
    fun checkpointGet(mesId: Int?): String? = null

    /** /checkpoint-list：[links] true 返回链接名 JSON 数组；false 返回消息索引数组。 */
    fun checkpointList(links: Boolean): String? = null

    /** /branch-create：在 [mesId]（null = 最后一条）处创建分支并打开（对齐 ST `createBranch`）。 */
    fun branchCreate(mesId: Int?): String? = null

    /** /del | /delete | /cut：按数量或 start-end 范围删除（或剪切返回）消息。 */
    fun deleteMessages(spec: String, cut: Boolean = false): String? = null

    /** /inject：向当前会话写入临时提示词注入（对接 InjectedPromptStore）。 */
    fun injectPrompt(
        id: String,
        content: String,
        position: String = "in_chat",
        depth: Int = 4,
        role: String = "system",
        scan: Boolean = false,
        once: Boolean = false,
    ): String? = null

    /** /listinjects：列出当前会话的所有临时注入。 */
    fun listInjects(): String? = null

    /** /flushinject | /flushinjects：清理指定 id 或全部临时注入。 */
    fun flushInjects(id: String = ""): String? = null

    /** /dupe | /char-duplicate：复制当前角色卡。 */
    fun dupeChar(): String? = null

    /** /world：绑定/解绑世界书。 */
    fun toggleWorld(name: String, state: Boolean? = null): String? = null

    /** /getentryfield：读取世界书条目字段（默认 content）。 */
    fun getEntryField(file: String, uid: String, field: String = "content"): String? = null

    /** /setentryfield：写入世界书条目字段。 */
    fun setEntryField(file: String, uid: String, field: String = "content", value: String = ""): String? = null

    /** /createentry：在指定世界书中创建新条目并返回 uid。 */
    fun createEntry(file: String, key: String, content: String, comment: String = ""): String? = null

    /** /regex | /regex-toggle：切换指定名称或 ID 的正则脚本开关。 */
    fun toggleRegex(nameOrId: String, state: Boolean? = null): String? = null

    /** /setinput | /fill：设置聊天输入框文本。 */
    fun setInput(text: String): String? = null

    /** /messages：读取指定楼层或范围的消息文本（支持 names=on|off）。 */
    fun getMessages(spec: String, includeNames: Boolean = true): String? = null

    /** /char-find：按名称或标签查找角色卡名称。 */
    fun charFind(name: String): String? = null

    /** /note：读取或设置当前会话/全局作者备注（Author's Note）正文。 */
    fun authorNote(text: String?): String? = null

    /** /note-depth | /depth：读取或设置作者备注注入深度。 */
    fun authorNoteDepth(depth: Int?): String? = null

    /** /note-frequency | /note-interval | /freq：读取或设置作者备注注入间隔频率。 */
    fun authorNoteFrequency(freq: Int?): String? = null

    /** /note-position | /note-pos | /pos：读取或设置作者备注注入位置。 */
    fun authorNotePosition(pos: String?): String? = null

    /** /note-role | /role：读取或设置作者备注注入角色（system/user/assistant）。 */
    fun authorNoteRole(role: String?): String? = null

    /** /qr：按标题触发快速回复（Quick Reply）。 */
    fun quickReply(label: String): String? = null

    /** /qr-arg：设置快速回复执行上下文参数。 */
    fun quickReplyArg(name: String, value: String): String? = null

    /** /translate：调用宿主翻译能力翻译指定文本。 */
    fun translateText(text: String, targetLang: String = ""): String? = null

    /** /summarize：调用宿主摘要能力总结当前对话。 */
    fun summarizeChat(prompt: String = ""): String? = null

    /** /caption：调用宿主识图能力描述图片。 */
    fun captionImage(prompt: String = ""): String? = null

    /** 回退调用通过 JS `SlashCommandParser.addCommandObject` 注册的扩展自定义斜杠命令。 */
    fun executeJsSlashCommand(
        name: String,
        namedArgs: Map<String, String>,
        unnamedArgs: String,
        pipe: String = "",
    ): String? = null

    /** 文本宏展开钩子（可选；缺省原样返回，由调用方决定是否接宏引擎）。 */
    fun expandMacros(text: String): String = text

    fun executeExtendedCommand(
        cmd: String,
        named: Map<String, String>,
        unnamed: String,
        pipe: String = "",
    ): String? = null
}

object StSlashExecutor {
    private const val UNKNOWN_CMD = "未知命令：/%s"
    private const val UNAVAILABLE_CMD = "命令不可用：/%s"
    internal val UNSUPPORTED_MEDIA_COMMANDS = setOf(
        "speak",
        "narrate",
        "tts",
        "imagine",
        "imagine-comfy-workflow",
        "imagine-source",
        "imagine-style",
        "sd",
    )

    /** [W4] 闭包递归深度上限（防无限递归栈溢出；官方无显式上限，靠 abortController 停止） */
    internal const val MAX_CLOSURE_DEPTH = 32

    /** [W4] `/delay` 单次最大等待（毫秒）：防止脚本把执行线程挂死 */
    internal const val MAX_DELAY_MS = 30_000L

    @Volatile
    var debugLogger: ((String) -> Unit)? = null

    /** 执行一行 STscript，返回最终管道值。 */
    fun execute(script: String, host: StSlashHost? = null): String =
        execute(script, host, StSlashScope(), initialPipe = "")

    /** 循环迭代上限（对齐官方 SillyTavern variables.js:20 MAX_LOOPS = 100） */
    internal const val MAX_LOOP_ITERATIONS = 100

    private val VAR_MACRO_REGEX = Regex("""\{\{var::([^}]+)\}\}""", RegexOption.IGNORE_CASE)

    /** 内联写变量宏 `{{setvar::key::value}}`（key/value 两侧允许空白）：写入作用域变量并原地展开为空串。 */
    private val INLINE_SETVAR_MACRO_REGEX = Regex("""\{\{\s*setvar::\s*([^:}]+?)\s*::\s*([^}]*?)\s*\}\}""", RegexOption.IGNORE_CASE)

    /** 内联读变量宏 `{{getvar::key}}`（key 两侧允许空白）：优先闭包参数栈 → 作用域变量 → 宿主变量。 */
    private val INLINE_GETVAR_MACRO_REGEX = Regex("""\{\{\s*getvar::\s*([^}]+?)\s*\}\}""", RegexOption.IGNORE_CASE)

    /** 在顶层（跳过嵌套闭包字面量 `{: ... :}` / `{| ... |}`）替换 `{{pipe}}`、作用域宏（如 `{{timesIndex}}`）与 `{{var::name}}`。 */
    private fun expandSegmentMacrosOutsideClosures(
        argsText: String,
        pipe: String,
        host: StSlashHost?,
        scope: StSlashScope,
    ): String {
        if (!argsText.contains("{{")) return argsText
        fun expandPlain(part: String): String {
            var withPipe = part.replace("{{pipe}}", pipe, ignoreCase = true)
            for ((macroKey, macroVal) in scope.currentMacros()) {
                withPipe = withPipe.replace("{{$macroKey}}", macroVal)
            }
            // 内联写变量宏 {{setvar::key::value}}：写入作用域并原地展开为空串；
            val afterInlineSet = INLINE_SETVAR_MACRO_REGEX.replace(withPipe) { m ->
                val key = m.groupValues[1].trim()
                val value = m.groupValues[2].trim()
                if (key.isNotEmpty()) {
                    scope.scopedVars[key] = value
                }
                ""
            }
            val afterInlineGet = INLINE_GETVAR_MACRO_REGEX.replace(afterInlineSet) { m ->
                val key = m.groupValues[1].trim()
                scope.currentArgs()[key]
                    ?: scope.scopedVars[key]
                    ?: host?.varOp("getvar", key, "")
                    ?: ""
            }
            val afterVar = VAR_MACRO_REGEX.replace(afterInlineGet) { m ->
                val rawKey = m.groupValues[1].trim()
                // 支持 {{var::name::index}} 数组/对象下标或单纯变量名（优先读取闭包参数栈，再读局部作用域变量）
                val key = rawKey.substringBefore("::").trim()
                scope.currentArgs()[key]
                    ?: scope.scopedVars[key]
                    ?: host?.varOp("getvar", key, "")
                    ?: ""
            }
            return if (afterVar.contains("{{")) {
                host?.expandMacros(afterVar) ?: afterVar
            } else {
                afterVar
            }
        }
        val sb = StringBuilder()
        var cursor = 0
        while (cursor < argsText.length) {
            val span = StClosureSyntax.findClosureSpan(argsText, cursor)
            if (span == null) {
                sb.append(expandPlain(argsText.substring(cursor)))
                break
            }
            if (span.first > cursor) {
                sb.append(expandPlain(argsText.substring(cursor, span.first)))
            }
            sb.append(argsText.substring(span.first, span.second))
            cursor = span.second
        }
        return sb.toString()
    }

    internal fun execute(
        script: String,
        host: StSlashHost?,
        scope: StSlashScope,
        initialPipe: String = "",
    ): String {
        val segments = StSlashParser.parseParts(script)
        var pipe = initialPipe
        var cmdIndex = 0
        for (seg in segments) {
            if (scope.abort || scope.broken) break // /abort 或 /break：停止当前块
            if (!seg.isCommand || seg.command.isBlank()) continue // 对齐官方：游离文本丢弃
            val isFirst = cmdIndex == 0
            // 官方：参数中的 {{pipe}} 与 {{var::name}} 宏在命令执行时替换（保留子闭包内的宏待子闭包执行时求值）
            val rawArgs = expandSegmentMacrosOutsideClosures(seg.args, pipe, host, scope)
            val output = try {
                runCommand(seg, rawArgs, host, pipe, isFirst, scope)
            } catch (e: Exception) {
                "命令执行失败：/${seg.command}（${e.message ?: e.javaClass.simpleName}）"
            }
            debugLogger?.invoke("[slash] /${seg.command} -> ${output.take(180)}")
            pipe = output
            cmdIndex++
        }
        return pipe
    }

    private fun runCommand(
        seg: StSlashParser.Segment,
        rawArgs: String,
        host: StSlashHost?,
        pipe: String,
        isFirst: Boolean,
        scope: StSlashScope,
    ): String = when (seg.command) {
        // ———— 注释与管道工具 ————
        "/" -> pipe // `//` 注释命令：保持当前管道值不变

        "pass", "return" -> resolveText(rawArgs, host, pipe, isFirst, seg.noPipeInject).first

        "break" -> {
            val (txt, _) = resolveText(rawArgs, host, pipe, isFirst, seg.noPipeInject)
            scope.broken = true
            txt.ifEmpty { pipe }
        }

        "echo", "toast" -> {
            val (txt, named) = resolveText(rawArgs, host, pipe, isFirst, seg.noPipeInject)
            host?.echo(txt, named["title"]) ?: txt
        }

        "popup" -> {
            val (txt, named) = resolveText(rawArgs, host, pipe, isFirst, seg.noPipeInject)
            val title = named["title"] ?: named["header"]
            val expectRes = isTrueBoolean(named["result"] ?: "")
            val modalOut = host?.showPopup(
                text = txt,
                title = title,
                expectResult = expectRes,
                okButton = named["okbutton"],
                cancelButton = named["cancelbutton"],
            )
            if (modalOut != null) {
                modalOut
            } else {
                host?.echo(txt, title)
                if (expectRes) "1" else txt
            }
        }

        "input" -> {
            val (txt, named) = resolveText(rawArgs, host, pipe, isFirst, seg.noPipeInject)
            val defVal = named["default"]?.let(StSlashParser::unwrapQuotes)
            val modalOut = host?.showInput(
                prompt = txt,
                title = named["title"] ?: named["header"],
                defaultValue = defVal.orEmpty(),
            )
            if (modalOut != null) {
                modalOut
            } else {
                if (txt.isNotBlank()) host?.echo(txt, null)
                defVal ?: txt
            }
        }

        "buttons" -> {
            val (txt, named) = resolveText(rawArgs, host, pipe, isFirst, seg.noPipeInject)
            val labelsRaw = named["labels"]?.trim().orEmpty()
            val parsedLabels = labelsRaw.removeSurrounding("[", "]")
                .split(',')
                .map { StSlashParser.unwrapQuotes(it.trim()) }
                .filter { it.isNotEmpty() }
            val multiple = isTrueBoolean(named["multiple"] ?: "")
            val modalOut = if (parsedLabels.isNotEmpty()) {
                host?.showButtons(prompt = txt, labels = parsedLabels, multiple = multiple)
            } else null
            if (modalOut != null) {
                modalOut
            } else {
                if (txt.isNotBlank()) host?.echo(txt, null)
                parsedLabels.firstOrNull() ?: ""
            }
        }

        // ———— 作用域变量（/let | /var）————
        "let", "var" -> {
            val closureAssignment = parseClosureAssignment(rawArgs)
            if (closureAssignment != null) {
                scope.localClosures[closureAssignment.first] = closureAssignment.second
                StClosureStore.put(host?.chatKey, closureAssignment.first, closureAssignment.second)
                ""
            } else {
                val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
                val key: String
                val rawValue: String
                val hasValue: Boolean
                if (named.containsKey("key")) {
                    key = named["key"]!!.trim()
                    rawValue = resolveRest(rest.trim(), pipe, isFirst, seg.noPipeInject)
                    hasValue = rest.isNotBlank() || (!isFirst && !seg.noPipeInject && pipe.isNotEmpty())
                } else {
                    val trimmed = rest.trim()
                    val sp = trimmed.indexOfFirst { it.isWhitespace() }
                    key = StSlashParser.unwrapQuotes(if (sp == -1) trimmed else trimmed.substring(0, sp))
                    if (sp == -1) {
                        rawValue = if (!isFirst && !seg.noPipeInject && pipe.isNotEmpty()) pipe else ""
                        hasValue = !isFirst && !seg.noPipeInject && pipe.isNotEmpty()
                    } else {
                        rawValue = StSlashParser.unwrapQuotes(trimmed.substring(sp).trim())
                        hasValue = true
                    }
                }
                if (key.isEmpty()) {
                    ""
                } else if (hasValue) {
                    scope.scopedVars[key] = rawValue
                    if (seg.command == "var") rawValue else ""
                } else {
                    scope.scopedVars[key] ?: ""
                }
            }
        }

        // ———— 变量族（会话级 + 全局级）————
        "setvar", "getvar", "addvar", "incvar", "decvar", "flushvar", "listvar",
        "setglobalvar", "getglobalvar", "addglobalvar", "incglobalvar", "decglobalvar", "flushglobalvar", "listglobalvar" -> {
            // [W4] 闭包作为值：/setvar fn={: ... :}（官方 scope 变量可存闭包；现有变量只存字符串，
            val closureAssignment = if (seg.command == "setvar") parseClosureAssignment(rawArgs) else null
            if (closureAssignment != null) {
                scope.localClosures[closureAssignment.first] = closureAssignment.second
                StClosureStore.put(host?.chatKey, closureAssignment.first, closureAssignment.second)
                ""
            } else {
                val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
                val key: String
                val value: String
                if (named.containsKey("key")) {
                    key = named["key"]!!.trim()
                    value = resolveRest(rest.trim(), pipe, isFirst, seg.noPipeInject).let(StSlashParser::unwrapQuotes)
                } else {
                    val trimmed = rest.trim()
                    val sp = trimmed.indexOfFirst { it.isWhitespace() }
                    key = StSlashParser.unwrapQuotes(if (sp == -1) trimmed else trimmed.substring(0, sp))
                    value = if (sp == -1) {
                        if ((seg.command == "setvar" || seg.command == "addvar" || seg.command == "setglobalvar" || seg.command == "addglobalvar") &&
                            !isFirst && !seg.noPipeInject && pipe.isNotEmpty()
                        ) {
                            pipe
                        } else {
                            ""
                        }
                    } else {
                        StSlashParser.unwrapQuotes(trimmed.substring(sp).trim())
                    }
                }
                if (seg.command == "flushvar") {
                    scope.localClosures.remove(key)
                    StClosureStore.remove(host?.chatKey, key)
                }
                host?.varOp(seg.command, key, value) ?: unavailable(seg.command)
            }
        }

        // ———— 消息插入 ————
        "send" -> insertCmd(seg, rawArgs, host, pipe, isFirst) { h, text, name, at ->
            h.insertMessage(MessageRole.USER, text, name, at)
        }

        "sendas" -> insertCmd(seg, rawArgs, host, pipe, isFirst) { h, text, name, at ->
            h.insertMessage(MessageRole.ASSISTANT, text, name, at)
        }

        "sys", "nar", "comment" -> insertCmd(seg, rawArgs, host, pipe, isFirst) { h, text, name, at ->
            h.insertMessage(MessageRole.SYSTEM, text, name, at)
        }

        "sysgen" -> {
            val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val text = resolveRest(rest, pipe, isFirst, seg.noPipeInject).let(StSlashParser::unwrapQuotes)
            if (text.isBlank()) {
                "命令缺少文本：/sysgen"
            } else {
                host?.sysgen(
                    prompt = text,
                    name = named["name"],
                    at = named["at"]?.toIntOrNull(),
                    trim = isTrueBoolean(named["trim"] ?: ""),
                ) ?: unavailable(seg.command)
            }
        }

        // ———— 生成类 ————
        "trigger" -> host?.trigger() ?: unavailable(seg.command)

        "continue" -> {
            val text = StSlashParser.unwrapQuotes(resolveRest(rawArgs, pipe, isFirst, seg.noPipeInject))
            host?.continueGen(text) ?: unavailable(seg.command)
        }

        "impersonate" -> {
            val text = StSlashParser.unwrapQuotes(resolveRest(rawArgs, pipe, isFirst, seg.noPipeInject))
            host?.impersonate(text) ?: unavailable(seg.command)
        }

        "gen" -> {
            val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val prompt = resolveRest(rest, pipe, isFirst, seg.noPipeInject).let(StSlashParser::unwrapQuotes)
            if (prompt.isBlank()) {
                "命令缺少文本：/gen"
            } else {
                host?.gen(
                    prompt = prompt,
                    asRole = named["as"] ?: "system",
                    lock = isTrueBoolean(named["lock"] ?: ""),
                    length = named["length"]?.toIntOrNull() ?: 0,
                    name = named["name"],
                    trim = isTrueBoolean(named["trim"] ?: ""),
                ) ?: unavailable(seg.command)
            }
        }

        "genraw" -> {
            val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val prompt = resolveRest(rest, pipe, isFirst, seg.noPipeInject).let(StSlashParser::unwrapQuotes)
            if (prompt.isBlank()) {
                "命令缺少文本：/genraw"
            } else {
                host?.genRaw(
                    prompt = prompt,
                    systemPrompt = (named["system"] ?: named["sys"] ?: "").let(StSlashParser::unwrapQuotes),
                    length = named["length"]?.toIntOrNull() ?: 0,
                    trim = isTrueBoolean(named["trim"] ?: ""),
                ) ?: unavailable(seg.command)
            }
        }

        // ———— 人设 / 角色 ————
        "persona", "persona-set" -> {
            val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
            host?.persona(rest.trim(), (named["mode"] ?: "all").lowercase()) ?: unavailable(seg.command)
        }

        "rename-char" -> {
            val name = StSlashParser.unwrapQuotes(resolveRest(rawArgs, pipe, isFirst, seg.noPipeInject))
            if (name.isBlank()) {
                "命令缺少名称：/rename-char"
            } else {
                host?.renameChar(name) ?: unavailable(seg.command)
            }
        }

        // ———— 运行时 ————
        "js" -> host?.js(rawArgs.trim()) ?: unavailable(seg.command)

        "tavern" -> host?.tavern(rawArgs.trim().lowercase()) ?: unavailable(seg.command)

        // ———— [W4] 控制流：闭包 / /run ————
        "run", "call", "exec" -> runClosureCommand(rawArgs, host, pipe, isFirst, seg, scope)

        // ———— [W4] /abort（官方 slash-commands.js:2363-2380 abortCallback）————
        "abort" -> {
            val (_, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val reason = StSlashParser.unwrapQuotes(resolveRest(rest, pipe, isFirst, seg.noPipeInject)).trim()
            scope.abort = true
            scope.abortReason = reason
            ""
        }

        // ———— [W4] /delay（官方别名 wait/sleep，参数为毫秒）————
        "delay", "wait", "sleep" -> {
            val (_, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val amount = resolveRest(rest, pipe, isFirst, seg.noPipeInject).trim().toLongOrNull() ?: 0L
            delayBestEffort(amount)
            ""
        }

        // ———— [W4] /switch（官方 1.18 无此命令；本地闭包分派扩展，见 DIVERGENCE.md）————
        "switch" -> runSwitchCommand(rawArgs, host, pipe, isFirst, seg, scope)

        // ———— 消息隐藏 / swipe ————
        "hide", "unhide" -> {
            val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val value = StSlashParser.unwrapQuotes(resolveRest(rest, pipe, isFirst, seg.noPipeInject)).trim()
            host?.hideMessages(
                value = value,
                unhide = seg.command == "unhide",
                nameFilter = named["name"]?.trim()?.takeIf { it.isNotBlank() },
            ) ?: unavailable(seg.command)
        }

        "swipe" -> {
            val (named, _) = StSlashParser.extractNamedArgs(rawArgs)
            // 对齐 ST：direction 仅 left 视为左向，其余（含缺省）为右向
            val direction = if (named["direction"].equals("left", ignoreCase = true)) "left" else "right"
            host?.swipe(direction, isTrueBoolean(named["await"] ?: "")) ?: unavailable(seg.command)
        }

        // ———— 表情立绘（W3，对齐 ST /expression-fallback）————
        "expression-fallback" -> {
            val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val label = StSlashParser.unwrapQuotes(resolveRest(rest, pipe, isFirst, seg.noPipeInject)).trim()
            host?.expressionFallback(label) ?: unavailable(seg.command)
        }

        // ———— ST Checkpoints（书签，对齐 bookmarks.js）————
        "checkpoint-create" -> {
            val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val name = StSlashParser.unwrapQuotes(resolveRest(rest, pipe, isFirst, seg.noPipeInject)).trim()
            host?.checkpointCreate(named["mesid"]?.toIntOrNull(), name) ?: unavailable(seg.command)
        }

        "checkpoint-go" -> {
            val (named, _) = StSlashParser.extractNamedArgs(rawArgs)
            host?.checkpointGo(named["mesid"]?.toIntOrNull()) ?: unavailable(seg.command)
        }

        "checkpoint-exit" -> host?.checkpointExit() ?: unavailable(seg.command)

        "checkpoint-parent" -> host?.checkpointParent() ?: unavailable(seg.command)

        "checkpoint-get" -> {
            val (named, _) = StSlashParser.extractNamedArgs(rawArgs)
            host?.checkpointGet(named["mesid"]?.toIntOrNull()) ?: unavailable(seg.command)
        }

        "checkpoint-list" -> {
            val (named, _) = StSlashParser.extractNamedArgs(rawArgs)
            host?.checkpointList(isTrueBoolean(named["links"] ?: "")) ?: unavailable(seg.command)
        }

        "branch-create" -> {
            val (named, _) = StSlashParser.extractNamedArgs(rawArgs)
            host?.branchCreate(named["mesid"]?.toIntOrNull()) ?: unavailable(seg.command)
        }

        // ———— 条件与循环流控（/if | /else | /while | /times）————
        "if" -> runIfCommand(rawArgs, host, pipe, isFirst, seg, scope)

        "else" -> runElseCommand(rawArgs, host, pipe, isFirst, seg, scope)

        "while" -> runWhileCommand(rawArgs, host, pipe, seg, scope)

        "times" -> runTimesCommand(rawArgs, host, pipe, isFirst, seg, scope)

        // ———— 临时提示词注入（/inject | /listinjects | /flushinject）————
        "inject" -> {
            val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val text = StSlashParser.unwrapQuotes(resolveRest(rest, pipe, isFirst, seg.noPipeInject)).trim()
            val id = named["id"]?.trim()?.takeIf { it.isNotEmpty() } ?: "slash_inject_${System.currentTimeMillis()}"
            val pos = named["position"]?.trim()?.lowercase() ?: "chat"
            val depth = named["depth"]?.toIntOrNull() ?: 4
            val role = named["role"]?.trim()?.lowercase() ?: "system"
            val scan = isTrueBoolean(named["scan"] ?: "")
            val once = isTrueBoolean(named["ephemeral"] ?: named["once"] ?: "")
            host?.injectPrompt(id, text, pos, depth, role, scan, once) ?: unavailable(seg.command)
        }

        "listinjects" -> host?.listInjects() ?: unavailable(seg.command)

        "flushinject", "flushinjects" -> {
            val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val id = (named["id"] ?: resolveRest(rest, pipe, isFirst, seg.noPipeInject)).let(StSlashParser::unwrapQuotes).trim()
            host?.flushInjects(id) ?: unavailable(seg.command)
        }

        // ———— 消息删除 / 剪切（/del | /delete | /cut）————
        "del", "delete", "cut" -> {
            val (_, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val spec = StSlashParser.unwrapQuotes(resolveRest(rest, pipe, isFirst, seg.noPipeInject)).trim()
            host?.deleteMessages(spec, cut = seg.command == "cut") ?: unavailable(seg.command)
        }

        // ———— 角色卡复制（/dupe | /char-duplicate）————
        "dupe", "char-duplicate" -> host?.dupeChar() ?: unavailable(seg.command)

        // ———— 世界书与正则命令（/world | /getentryfield | /setentryfield | /createentry | /regex）————
        "world" -> {
            val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val name = StSlashParser.unwrapQuotes(resolveRest(rest, pipe, isFirst, seg.noPipeInject)).trim()
            val state = named["state"]?.let { isTrueBoolean(it) }
            host?.toggleWorld(name, state) ?: unavailable(seg.command)
        }

        "getentryfield" -> {
            val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val file = (named["file"] ?: named["world"] ?: "current").trim()
            val field = (named["field"] ?: "content").trim()
            val uid = StSlashParser.unwrapQuotes(resolveRest(rest, pipe, isFirst, seg.noPipeInject)).trim()
            host?.getEntryField(file, uid, field) ?: unavailable(seg.command)
        }

        "setentryfield" -> {
            val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val file = (named["file"] ?: named["world"] ?: "current").trim()
            val field = (named["field"] ?: "content").trim()
            val uid = (named["uid"] ?: "").trim()
            val value: String
            val actualUid: String
            if (uid.isNotEmpty()) {
                actualUid = uid
                value = StSlashParser.unwrapQuotes(resolveRest(rest, pipe, isFirst, seg.noPipeInject))
            } else {
                val trimmed = rest.trim()
                val sp = trimmed.indexOfFirst { it.isWhitespace() }
                actualUid = if (sp == -1) trimmed else trimmed.substring(0, sp)
                value = if (sp == -1) pipe else StSlashParser.unwrapQuotes(trimmed.substring(sp).trim())
            }
            host?.setEntryField(file, actualUid, field, value) ?: unavailable(seg.command)
        }

        "createentry" -> {
            val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val file = (named["file"] ?: named["world"] ?: "current").trim()
            val key = (named["key"] ?: "").trim()
            val comment = (named["comment"] ?: named["name"] ?: key).trim()
            val content = StSlashParser.unwrapQuotes(resolveRest(rest, pipe, isFirst, seg.noPipeInject))
            host?.createEntry(file, key, content, comment) ?: unavailable(seg.command)
        }

        "regex", "regex-toggle" -> {
            val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val target = StSlashParser.unwrapQuotes(resolveRest(rest, pipe, isFirst, seg.noPipeInject)).trim()
            val state = named["state"]?.let { isTrueBoolean(it) }
            host?.toggleRegex(target, state) ?: unavailable(seg.command)
        }

        // ———— 算术与数学函数命令（/add /sub /mul /div /mod /pow /min /max /round /floor /ceil /abs /rand /sin /cos /log /sqrt /random）————
        "add", "sub", "mul", "div", "mod", "pow", "min", "max", "round", "floor", "ceil", "abs", "rand",
        "sin", "cos", "log", "sqrt" ->
            runMathCommand(seg.command, rawArgs, pipe, isFirst, seg.noPipeInject, host, scope)

        // ———— 字符串、数组与模糊匹配命令 ————
        "len", "length", "substr", "substring", "trim", "trimstart", "trimend",
        "upper", "uppercase", "lower", "lowercase", "replace", "match", "join", "split",
        "sort", "array-wrap", "array-unwrap", "fuzzy" ->
            runStringCommand(seg.command, rawArgs, pipe, isFirst, seg.noPipeInject, host)

        // ———— 作者备注命令（/note /note-depth /note-frequency /note-position /note-role）————
        "note", "author-note" -> {
            val (_, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val hasExplicit = rest.isNotBlank() || (!isFirst && !seg.noPipeInject && pipe.isNotEmpty())
            val text = if (hasExplicit) {
                StSlashParser.unwrapQuotes(resolveRest(rest, pipe, isFirst, seg.noPipeInject))
            } else null
            host?.authorNote(text) ?: unavailable(seg.command)
        }

        "note-depth", "depth" -> {
            val (_, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val resolved = StSlashParser.unwrapQuotes(resolveRest(rest, pipe, isFirst, seg.noPipeInject)).trim()
            host?.authorNoteDepth(resolved.toIntOrNull()) ?: unavailable(seg.command)
        }

        "note-frequency", "note-interval", "freq" -> {
            val (_, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val resolved = StSlashParser.unwrapQuotes(resolveRest(rest, pipe, isFirst, seg.noPipeInject)).trim()
            host?.authorNoteFrequency(resolved.toIntOrNull()) ?: unavailable(seg.command)
        }

        "note-position", "note-pos", "pos" -> {
            val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val resolved = (named["position"] ?: named["pos"] ?: resolveRest(rest, pipe, isFirst, seg.noPipeInject))
                .let(StSlashParser::unwrapQuotes).trim().takeIf { it.isNotEmpty() }
            host?.authorNotePosition(resolved) ?: unavailable(seg.command)
        }

        "note-role", "role" -> {
            val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val resolved = (named["role"] ?: resolveRest(rest, pipe, isFirst, seg.noPipeInject))
                .let(StSlashParser::unwrapQuotes).trim().takeIf { it.isNotEmpty() }
            host?.authorNoteRole(resolved) ?: unavailable(seg.command)
        }

        // ———— 输入框、消息读取、角色查找、快速回复与原生能力桥接 ————
        "setinput", "fill" -> {
            val (txt, _) = resolveText(rawArgs, host, pipe, isFirst, seg.noPipeInject)
            host?.setInput(txt) ?: unavailable(seg.command)
        }

        "messages", "chat-messages" -> {
            val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val spec = StSlashParser.unwrapQuotes(resolveRest(rest, pipe, isFirst, seg.noPipeInject)).trim()
            val includeNames = named["names"]?.let { !it.equals("off", ignoreCase = true) && !it.equals("false", ignoreCase = true) } ?: true
            host?.getMessages(spec, includeNames) ?: unavailable(seg.command)
        }

        "char-find", "findchar" -> {
            val (txt, named) = resolveText(rawArgs, host, pipe, isFirst, seg.noPipeInject)
            val query = txt.ifBlank { named["name"].orEmpty() }
            host?.charFind(query) ?: unavailable(seg.command)
        }

        "qr" -> {
            val (txt, named) = resolveText(rawArgs, host, pipe, isFirst, seg.noPipeInject)
            val label = txt.ifBlank { named["label"] ?: named["name"] ?: "" }
            host?.quickReply(label) ?: unavailable(seg.command)
        }

        "qr-arg" -> {
            val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val trimmed = rest.trim()
            val sp = trimmed.indexOfFirst { it.isWhitespace() }
            val argName = named["name"] ?: named["key"] ?: StSlashParser.unwrapQuotes(if (sp == -1) trimmed else trimmed.substring(0, sp))
            val argVal = if (named.containsKey("value")) {
                StSlashParser.unwrapQuotes(named["value"].orEmpty())
            } else if (sp == -1) {
                if (!isFirst && !seg.noPipeInject) pipe else ""
            } else {
                StSlashParser.unwrapQuotes(trimmed.substring(sp).trim())
            }
            if (argName.isNotEmpty()) {
                scope.scopedVars[argName] = argVal
                host?.quickReplyArg(argName, argVal)
            }
            ""
        }

        "translate" -> {
            val (txt, named) = resolveText(rawArgs, host, pipe, isFirst, seg.noPipeInject)
            val targetLang = named["target"] ?: named["lang"] ?: ""
            host?.translateText(txt, targetLang) ?: unavailable(seg.command)
        }

        "summarize" -> {
            val (txt, _) = resolveText(rawArgs, host, pipe, isFirst, seg.noPipeInject)
            host?.summarizeChat(txt) ?: unavailable(seg.command)
        }

        "caption" -> {
            val (txt, _) = resolveText(rawArgs, host, pipe, isFirst, seg.noPipeInject)
            host?.captionImage(txt) ?: unavailable(seg.command)
        }

        // ———— 闭包序列化、解析器标志与断点（SlashCommandParser.js / variables.js）————
        "breakpoint" -> pipe
        "parser-flag" -> {
            val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val tokens = StClosureSyntax.tokenizeArgs(rest.trim())
            val flag = (named["flag"] ?: tokens.getOrNull(0) ?: "").trim().uppercase()
            val state = (named["state"] ?: tokens.getOrNull(1) ?: "on").trim()
            val enabled = isTrueBoolean(state).toString()
            if (flag.isNotEmpty()) scope.scopedVars["__parser_flag_$flag"] = enabled
            enabled
        }
        "closure-serialize" -> {
            val (txt, named) = resolveText(rawArgs, host, pipe, isFirst, seg.noPipeInject)
            val key = (named["name"] ?: txt).trim()
            val closure = scope.localClosures[key] ?: StClosureSyntax.parse(txt)
            if (closure == null) ""
            else {
                val escaped = closure.raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                "{\"__st_closure__\":true,\"rawText\":\"$escaped\"}"
            }
        }
        "closure-deserialize" -> {
            val (txt, named) = resolveText(rawArgs, host, pipe, isFirst, seg.noPipeInject)
            val rawClosureText = runCatching {
                val m = Regex(""""rawText"\s*:\s*"((?:\\.|[^"\\])*)"""").find(txt)
                m?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"")?.replace("\\\\", "\\")
            }.getOrNull() ?: txt
            val parsed = StClosureSyntax.parse(rawClosureText)
            if (parsed != null) {
                val targetName = (named["name"] ?: "__deserialized__").trim()
                scope.localClosures[targetName] = parsed
                parsed.raw
            } else ""
        }

        // ———— 分词统计与截断命令（slash-commands.js / token-counter）————
        "tokens", "count" -> {
            val (txt, _) = resolveText(rawArgs, host, pipe, isFirst, seg.noPipeInject)
            me.rerere.rikkahub.data.st.tokenizer.countTokens(
                txt,
                me.rerere.rikkahub.data.st.tokenizer.TokenizerMode.CL100K,
            ).toString()
        }
        "trimtokens" -> {
            val (txt, named) = resolveText(rawArgs, host, pipe, isFirst, seg.noPipeInject)
            val limit = (named["limit"] ?: named["max"] ?: named["count"])?.toIntOrNull() ?: 0
            val direction = (named["direction"] ?: "end").trim().lowercase()
            if (limit <= 0 || txt.isEmpty()) ""
            else trimTextToTokenLimit(txt, limit, direction)
        }
        "tokenizer" -> {
            val (txt, named) = resolveText(rawArgs, host, pipe, isFirst, seg.noPipeInject)
            host?.executeExtendedCommand("tokenizer", named, txt, pipe) ?: txt.ifBlank { "cl100k" }
        }

        // ———— 移动端、帮助、随机抽取与测试（slash-commands.js / power-user.js）————
        "is-mobile" -> "true"
        "?", "help" -> {
            val (txt, named) = resolveText(rawArgs, host, pipe, isFirst, seg.noPipeInject)
            val q = txt.trim().removePrefix("/").lowercase()
            if (q in UNSUPPORTED_MEDIA_COMMANDS) {
                "/$q: 不支持（当前客户端暂未接入酒馆原生 TTS/SD 生图扩展命令）"
            } else {
                host?.executeExtendedCommand("?", named, txt, pipe)
                    ?: "STscript Ready (288 commands supported, excluding unsupported TTS/SD commands: ${UNSUPPORTED_MEDIA_COMMANDS.joinToString(", ") { "/$it" }}). Query: ${txt.ifBlank { "all" }}"
            }
        }
        "random" -> {
            val (txt, _) = resolveText(rawArgs, host, pipe, isFirst, seg.noPipeInject)
            val items = if (txt.contains(",")) {
                txt.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                StClosureSyntax.tokenizeArgs(txt).map(StSlashParser::unwrapQuotes).filter { it.isNotEmpty() }
            }
            if (items.isEmpty()) "" else items[kotlin.random.Random.nextInt(items.size)]
        }
        "test" -> {
            val (txt, _) = resolveText(rawArgs, host, pipe, isFirst, seg.noPipeInject)
            txt
        }

        // ———— 官方 SillyTavern 1.18.0 其余全部非 TTS / 非生图内置命令（统一解析参数并派发至宿主 executeExtendedCommand，无宿主时提供确定性兜底）————
        "findentry", "getcharbook", "getchatbook", "getglobalbooks", "getpersonabook",
        "wi-get-timed-effect", "wi-set-timed-effect",
        // 2) Quick Reply v2 全家桶（extensions/quick-reply/src/SlashCommandHandler.js）
        "import", "qr-chat-set", "qr-chat-set-off", "qr-chat-set-on",
        "qr-contextadd", "qr-contextclear", "qr-contextdel",
        "qr-create", "qr-delete", "qr-get", "qr-list",
        "qr-set", "qr-set-create", "qr-set-delete", "qr-set-list",
        "qr-set-off", "qr-set-on", "qr-set-update", "qr-update", "qrset",
        // 3) 群聊成员控制（slash-commands.js）
        "member-add", "member-count", "member-disable", "member-down",
        "member-enable", "member-get", "member-peek", "member-remove", "member-up",
        // 4) 角色、人设、聊天与 Swipe 控制（slash-commands.js / personas.js）
        "addswipe", "delswipe", "regenerate", "stop", "go",
        "char-create", "char-delete", "char-get", "char-update", "ask",
        "persona-create", "persona-delete", "persona-duplicate", "persona-get",
        "persona-lock", "persona-sync", "persona-update",
        "newchat", "tempchat", "delchat", "closechat", "getchatname", "renamechat",
        "forcesave", "chat-reload", "chat-render", "chat-jump", "chat-manager", "reroll-pick",
        "message-name", "message-role", "sysname", "delname",
        "clipboard-get", "clipboard-set",
        // 5) 预设、提示词条目、Instruct、Sysprompt 与模型连接（slash-commands.js / preset-manager.js / sysprompt.js / openai.js / connection-manager）
        "preset", "getpromptentry", "setpromptentry", "context",
        "instruct", "instruct-off", "instruct-on", "instruct-state",
        "sysprompt", "sysprompt-off", "sysprompt-on", "sysprompt-state",
        "model", "api", "api-url", "proxy", "prompt-post-processing",
        "start-reply-with", "stop-strings",
        "profile", "profile-create", "profile-genstream", "profile-get", "profile-list", "profile-update",
        // 6) 思维链 Reasoning 命令族（reasoning.js）
        "reasoning-collapse", "reasoning-expand", "reasoning-format", "reasoning-get",
        "reasoning-parse", "reasoning-set", "reasoning-template", "reasoning-toggle",
        // 7) 表情立绘命令族（extensions/expressions/index.js）
        "expression-classify", "expression-folder-override", "expression-last",
        "expression-list", "expression-set", "expression-upload",
        // 8) 正则扩展补充命令（extensions/regex/index.js）
        "regex-preset", "regex-state",
        // 9) Data Bank 附件与向量检索命令族（extensions/attachments/index.js & extensions/vectors/index.js）
        "db", "db-add", "db-delete", "db-disable", "db-enable", "db-get", "db-list", "db-update",
        "db-ingest", "db-purge", "db-search",
        "vector-chats-state", "vector-files-state", "vector-max-entries",
        "vector-query", "vector-threshold", "vector-worldinfo-state",
        // 10) 扩展管理、密钥、标签、工具调用、加载器、画廊、背景与 UI 样式（extensions-slashcommands.js / secrets.js / tags.js / tool-calling.js / action-loader / gallery / backgrounds.js / power-user.js / scrapers.js）
        "extension-disable", "extension-enable", "extension-exists", "extension-state", "extension-toggle", "reload-page",
        "secret-delete", "secret-id", "secret-read", "secret-rename", "secret-write",
        "tag-add", "tag-exists", "tag-import", "tag-list", "tag-remove",
        "tools-invoke", "tools-list", "tools-register", "tools-unregister",
        "loader-hide", "loader-show", "loader-stop", "loader-wrap",
        "list-gallery", "show-gallery",
        "bg", "autobg", "lockbg", "unlockbg", "bgcol", "css-var", "theme",
        "bubble", "flat", "single", "panels", "resetpanels", "movingui", "vn",
        "beep", "pick-icon", "pm-render", "yt-script" -> {
            val (txt, named) = resolveText(rawArgs, host, pipe, isFirst, seg.noPipeInject)
            host?.executeExtendedCommand(seg.command, named, txt, pipe)
                ?: runFallbackExtendedCommand(seg.command, named, txt, pipe, host, scope)
        }

        // 11) 已知暂不支持的酒馆原生 TTS / SD 生图命令（显式返回命令不可用，禁止伪装成功或报未知命令）
        "speak", "narrate", "tts",
        "imagine", "imagine-comfy-workflow", "imagine-source", "imagine-style", "sd" -> {
            "命令不可用：/${seg.command}（当前客户端暂不支持酒馆原生 TTS/SD 生图扩展命令）"
        }

        else -> {
            val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val unnamed = StSlashParser.unwrapQuotes(resolveRest(rest, pipe, isFirst, seg.noPipeInject))
            host?.executeJsSlashCommand(seg.command, named, unnamed, pipe)
                ?: UNKNOWN_CMD.format(seg.command)
        }
    }

    /** 解析命令参数：剥离命名参数并把文本整体去外层引号；未命名参数为空时按注入规则接收管道值。 */
    private fun resolveText(
        rawArgs: String,
        host: StSlashHost?,
        pipe: String,
        isFirst: Boolean,
        noPipeInject: Boolean,
    ): Pair<String, Map<String, String>> {
        val expanded = host?.expandMacros(rawArgs) ?: rawArgs
        val (named, rest) = StSlashParser.extractNamedArgs(expanded)
        val text = resolveRest(rest, pipe, isFirst, noPipeInject).let(StSlashParser::unwrapQuotes)
        return text to named
    }

    /** 管道注入规则：未命名参数为空 且 非首命令 且 未禁用注入 且 管道非空 → 以管道值作为文本参数。 */
    private fun resolveRest(
        rest: String,
        pipe: String,
        isFirst: Boolean,
        noPipeInject: Boolean,
    ): String {
        return if (rest.isBlank() && !isFirst && !noPipeInject && pipe.isNotEmpty()) pipe else rest
    }

    private inline fun insertCmd(
        seg: StSlashParser.Segment,
        rawArgs: String,
        host: StSlashHost?,
        pipe: String,
        isFirst: Boolean,
        invoke: (StSlashHost, String, String?, Int?) -> String?,
    ): String {
        val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
        val text = resolveRest(rest, pipe, isFirst, seg.noPipeInject).let(StSlashParser::unwrapQuotes)
        if (text.isBlank()) return "命令缺少文本：/${seg.command}"
        val h = host ?: return unavailable(seg.command)
        return invoke(h, text, named["name"], named["at"]?.toIntOrNull()) ?: unavailable(seg.command)
    }

    private fun unavailable(cmd: String): String = UNAVAILABLE_CMD.format(cmd)

    /** 官方 isTrueBoolean：true/1/on（大小写不敏感）。 */
    private fun isTrueBoolean(value: String): Boolean =
        value.equals("true", ignoreCase = true) || value == "1" || value.equals("on", ignoreCase = true)

    /** 官方 isFalseBoolean：false/0/off/no（大小写不敏感）。 */
    private fun isFalseBoolean(value: String): Boolean =
        value.equals("false", ignoreCase = true) || value == "0" || value.equals("off", ignoreCase = true) || value.equals("no", ignoreCase = true)

    // ==================== [W4] 闭包支持 ====================

    /** 解析 `/setvar name={: ... :}` / `/setvar name {: ... :}` 或 `{| ... |}`（官方变量可存闭包）。 */
    private fun parseClosureAssignment(rawArgs: String): Pair<String, StClosure>? {
        val text = rawArgs.trim()
        val span = StClosureSyntax.findClosureSpan(text, 0) ?: return null
        val start = span.first
        val end = span.second
        if (start <= 0 || end < text.length) return null
        val closure = StClosureSyntax.parse(text.substring(start, end)) ?: return null
        val head = text.substring(0, start).trim().removeSuffix("=").trim()
        val name = StSlashParser.unwrapQuotes(head)
        if (name.isBlank() || name.contains(' ')) return null
        return name to closure
    }

    /** 解析操作数（对齐 ST variables.js:440-484 parseBooleanOperands）：数值字面量 → 作用域变量 → 局部变量 → 全局变量 → 字符串字面量。 */
    private fun resolveOperand(raw: String?, host: StSlashHost?, scope: StSlashScope): String {
        if (raw == null) return ""
        val expanded = expandSegmentMacrosOutsideClosures(raw.trim(), "", host, scope)
        val unquoted = StSlashParser.unwrapQuotes(expanded)
        if (unquoted.toDoubleOrNull() != null) return unquoted
        scope.currentArgs()[unquoted]?.let { return it }
        scope.scopedVars[unquoted]?.let { return it }
        host?.varOp("getvar", unquoted, "")?.takeIf { it.isNotEmpty() }?.let { return it }
        host?.varOp("getglobalvar", unquoted, "")?.takeIf { it.isNotEmpty() }?.let { return it }
        return unquoted
    }

    /** 评估布尔条件（对齐 ST variables.js:500-561 evalBoolean）。 */
    private fun evalCondition(
        leftRaw: String?,
        rightRaw: String?,
        ruleRaw: String?,
        positionalExpr: String,
        host: StSlashHost?,
        scope: StSlashScope,
    ): Boolean {
        if (leftRaw != null || rightRaw != null || ruleRaw != null) {
            val a = resolveOperand(leftRaw, host, scope)
            if (rightRaw == null && ruleRaw == null) {
                return isTruthyValue(a)
            }
            val b = resolveOperand(rightRaw, host, scope)
            val rule = (ruleRaw ?: "eq").trim().lowercase()
            return compareValues(a, b, rule)
        }
        val expr = expandSegmentMacrosOutsideClosures(positionalExpr.trim(), "", host, scope)
            .removeSurrounding("(", ")")
            .trim()
        val opMatch = Regex("""^(.*?)\s*(==|!=|>=|<=|>|<|\bin\b|\bnin\b)\s*(.*)$""").find(expr)
        if (opMatch != null) {
            val a = resolveOperand(opMatch.groupValues[1], host, scope)
            val op = opMatch.groupValues[2]
            val b = resolveOperand(opMatch.groupValues[3], host, scope)
            val rule = when (op) {
                "==" -> "eq"
                "!=" -> "neq"
                ">" -> "gt"
                ">=" -> "gte"
                "<" -> "lt"
                "<=" -> "lte"
                "in" -> "in"
                "nin" -> "nin"
                else -> "eq"
            }
            return compareValues(a, b, rule)
        }
        if (expr.startsWith("!")) {
            val v = resolveOperand(expr.removePrefix("!").trim(), host, scope)
            return !isTruthyValue(v)
        }
        val v = resolveOperand(expr, host, scope)
        return isTruthyValue(v)
    }

    private fun isTruthyValue(v: String): Boolean {
        val s = v.trim().lowercase()
        return s.isNotEmpty() && s != "0" && s != "false" && s != "off" && s != "no" && s != "null" && s != "undefined"
    }

    private fun compareValues(a: String, b: String, rule: String): Boolean {
        val na = a.toDoubleOrNull()
        val nb = b.toDoubleOrNull()
        return when (rule) {
            "eq", "==" -> if (na != null && nb != null) na == nb else a == b
            "neq", "!=" -> if (na != null && nb != null) na != nb else a != b
            "gt", ">" -> if (na != null && nb != null) na > nb else a > b
            "gte", ">=" -> if (na != null && nb != null) na >= nb else a >= b
            "lt", "<" -> if (na != null && nb != null) na < nb else a < b
            "lte", "<=" -> if (na != null && nb != null) na <= nb else a <= b
            "in" -> a.contains(b, ignoreCase = true)
            "nin" -> !a.contains(b, ignoreCase = true)
            "not" -> !isTruthyValue(a)
            else -> a == b
        }
    }

    private fun executeBranchValue(branchRaw: String, host: StSlashHost?, pipe: String, scope: StSlashScope, label: String): String {
        val trimmed = branchRaw.trim()
        if (trimmed.isEmpty()) return pipe
        StClosureSyntax.parse(trimmed)?.let { closure ->
            return runClosure(closure, emptyMap(), host, pipe, scope, label = label)
        }
        val unquoted = StSlashParser.unwrapQuotes(trimmed)
        if (unquoted.startsWith("/")) {
            return execute(unquoted, host, scope, initialPipe = pipe)
        }
        lookupClosure(unquoted, host, scope)?.let { closure ->
            return runClosure(closure, emptyMap(), host, pipe, scope, label = unquoted)
        }
        return unquoted
    }

    private fun runIfCommand(
        rawArgs: String,
        host: StSlashHost?,
        pipe: String,
        isFirst: Boolean,
        seg: StSlashParser.Segment,
        scope: StSlashScope,
    ): String {
        // 先利用 tokenizeArgs 提取命名参数（含 else={: ... :}）与位置参数（含 {: ... :}）
        val tokens = StClosureSyntax.tokenizeArgs(rawArgs)
        val named = LinkedHashMap<String, String>()
        val posTokens = mutableListOf<String>()
        for (tok in tokens) {
            val eq = tok.indexOf('=')
            // 注意排除 ==、!=、>=、<=
            val isComparison = eq >= 0 && (
                (eq + 1 < tok.length && tok[eq + 1] == '=') ||
                    (eq > 0 && (tok[eq - 1] == '!' || tok[eq - 1] == '>' || tok[eq - 1] == '<'))
                )
            if (eq > 0 && !isComparison && !tok.startsWith("{:") && !tok.startsWith("{|")) {
                val k = tok.substring(0, eq).trim().lowercase()
                val v = tok.substring(eq + 1).trim()
                named[k] = v
            } else {
                posTokens += tok
            }
        }
        val thenBranch = named["then"] ?: posTokens.lastOrNull { StClosureSyntax.isClosureLiteral(it) || StSlashParser.unwrapQuotes(it).startsWith("/") } ?: ""
        val condTokens = if (thenBranch.isNotEmpty() && posTokens.lastOrNull() == thenBranch) {
            posTokens.dropLast(1)
        } else {
            posTokens
        }
        val condExpr = condTokens.joinToString(" ").ifBlank {
            if (!isFirst && !seg.noPipeInject) pipe else ""
        }
        val matched = evalCondition(
            leftRaw = named["left"] ?: named["a"],
            rightRaw = named["right"] ?: named["b"],
            ruleRaw = named["rule"] ?: named["op"],
            positionalExpr = condExpr,
            host = host,
            scope = scope,
        )
        scope.lastIfCondition = matched
        return if (matched) {
            executeBranchValue(thenBranch, host, pipe, scope, label = "<if-then>")
        } else {
            val elseBranch = named["else"] ?: ""
            if (elseBranch.isNotBlank()) {
                executeBranchValue(elseBranch, host, pipe, scope, label = "<if-else>")
            } else {
                pipe
            }
        }
    }

    private fun runElseCommand(
        rawArgs: String,
        host: StSlashHost?,
        pipe: String,
        isFirst: Boolean,
        seg: StSlashParser.Segment,
        scope: StSlashScope,
    ): String {
        val prev = scope.lastIfCondition
        scope.lastIfCondition = null
        if (prev != false) return pipe
        val branch = rawArgs.trim().ifBlank { if (!isFirst && !seg.noPipeInject) pipe else "" }
        return executeBranchValue(branch, host, pipe, scope, label = "<else>")
    }

    private fun runWhileCommand(
        rawArgs: String,
        host: StSlashHost?,
        pipe: String,
        seg: StSlashParser.Segment,
        scope: StSlashScope,
    ): String {
        val tokens = StClosureSyntax.tokenizeArgs(rawArgs)
        val named = LinkedHashMap<String, String>()
        val posTokens = mutableListOf<String>()
        for (tok in tokens) {
            val eq = tok.indexOf('=')
            val isComparison = eq >= 0 && (
                (eq + 1 < tok.length && tok[eq + 1] == '=') ||
                    (eq > 0 && (tok[eq - 1] == '!' || tok[eq - 1] == '>' || tok[eq - 1] == '<'))
                )
            if (eq > 0 && !isComparison && !tok.startsWith("{:") && !tok.startsWith("{|")) {
                named[tok.substring(0, eq).trim().lowercase()] = tok.substring(eq + 1).trim()
            } else {
                posTokens += tok
            }
        }
        val guardVal = named["guard"]
        if (guardVal != null && StClosureSyntax.isClosureLiteral(guardVal)) {
            throw IllegalArgumentException("argument 'guard' cannot be a closure for command /while")
        }
        val maxLoops = when {
            guardVal != null && isFalseBoolean(guardVal) -> Int.MAX_VALUE
            guardVal != null -> guardVal.toIntOrNull()?.coerceAtLeast(1) ?: MAX_LOOP_ITERATIONS
            else -> MAX_LOOP_ITERATIONS
        }
        val bodyBranch = named["command"]
            ?: posTokens.lastOrNull { StClosureSyntax.isClosureLiteral(it) || StSlashParser.unwrapQuotes(it).startsWith("/") }
            ?: return ""
        val condExpr = (if (posTokens.lastOrNull() == bodyBranch) posTokens.dropLast(1) else posTokens).joinToString(" ")
        var lastResult = ""
        var iter = 0
        while (iter < maxLoops && !scope.abort) {
            val cond = evalCondition(
                leftRaw = named["left"] ?: named["a"],
                rightRaw = named["right"] ?: named["b"],
                ruleRaw = named["rule"] ?: named["op"],
                positionalExpr = condExpr,
                host = host,
                scope = scope,
            )
            if (!cond) break
            lastResult = executeBranchValue(bodyBranch, host, pipe, scope, label = "<while>")
            if (scope.broken) {
                scope.broken = false
                break
            }
            iter++
        }
        return lastResult
    }

    private fun runTimesCommand(
        rawArgs: String,
        host: StSlashHost?,
        pipe: String,
        isFirst: Boolean,
        seg: StSlashParser.Segment,
        scope: StSlashScope,
    ): String {
        val taken = StClosureSyntax.takeFirstClosure(rawArgs)
        val closure: StClosure?
        val bodyCmd: String
        val countStr: String
        val guardVal: String?
        if (taken != null) {
            closure = taken.first
            bodyCmd = ""
            val (named, rest) = StSlashParser.extractNamedArgs(taken.second)
            guardVal = named["guard"]
            countStr = named["repeat"] ?: rest.trim().ifBlank { if (!isFirst && !seg.noPipeInject) pipe else "0" }
        } else {
            closure = null
            val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
            guardVal = named["guard"]
            val tokens = rest.trim().split(Regex("\\s+"), limit = 2)
            countStr = named["repeat"] ?: tokens.firstOrNull().orEmpty()
            bodyCmd = tokens.getOrNull(1)?.let(StSlashParser::unwrapQuotes).orEmpty()
            if (bodyCmd.isBlank()) return ""
        }
        if (guardVal != null && StClosureSyntax.isClosureLiteral(guardVal)) {
            throw IllegalArgumentException("argument 'guard' cannot be a closure for command /times")
        }
        val maxLoops = when {
            guardVal != null && isFalseBoolean(guardVal) -> Int.MAX_VALUE
            guardVal != null -> guardVal.toIntOrNull()?.coerceAtLeast(1) ?: MAX_LOOP_ITERATIONS
            else -> MAX_LOOP_ITERATIONS
        }
        val rawRepeats = StSlashParser.unwrapQuotes(countStr).toIntOrNull()?.coerceAtLeast(0) ?: 0
        val count = minOf(rawRepeats, maxLoops)
        var lastResult = ""
        for (i in 0 until count) {
            if (scope.abort) break
            lastResult = if (closure != null) {
                runClosure(
                    closure = closure,
                    provided = emptyMap(),
                    host = host,
                    parentPipe = pipe,
                    scope = scope,
                    label = "<times>",
                    injectedMacros = mapOf("timesIndex" to i.toString()),
                )
            } else {
                val subCommand = bodyCmd.replace("{{timesIndex}}", i.toString())
                execute(subCommand, host, scope, initialPipe = pipe)
            }
            if (scope.broken) {
                scope.broken = false
                break
            }
        }
        return lastResult
    }

    /** `/run`（别名 `/call` `/exec`）：执行闭包字面量，或变量里的闭包。 */
    private fun runClosureCommand(
        rawArgs: String,
        host: StSlashHost?,
        pipe: String,
        isFirst: Boolean,
        seg: StSlashParser.Segment,
        scope: StSlashScope,
    ): String {
        // 1. 直接给闭包字面量：`/run {: ... :} key=value`
        StClosureSyntax.takeFirstClosure(rawArgs)?.let { (closure, rest) ->
            val (named, _) = StSlashParser.extractNamedArgs(rest)
            return runClosure(closure, named, host, pipe, scope, label = "<literal>").also {
                scope.broken = false
            }
        }
        // 2. 变量名（本次脚本局部闭包 → 会话闭包表 → 字符串变量里的字面量）
        val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
        val target = resolveRest(rest, pipe, isFirst, seg.noPipeInject).trim()
        if (target.isBlank()) return "\"/run\" 缺少闭包名或闭包字面量"
        StClosureSyntax.parse(target)?.let { direct ->
            return runClosure(direct, named, host, pipe, scope, label = "<literal>").also {
                scope.broken = false
            }
        }
        val closure = lookupClosure(target, host, scope)
            ?: return "\"$target\" is not callable.".also {
                debugLogger?.invoke("[slash] /run $target -> not callable")
            }
        debugLogger?.invoke("[slash] /run $target (args=${named.keys})")
        return runClosure(closure, named, host, pipe, scope, label = target).also {
            scope.broken = false
        }
    }

    /** 闭包查找：本次脚本局部 → 会话闭包表 → 字符串变量里的闭包字面量（兼容路径）。 */
    private fun lookupClosure(name: String, host: StSlashHost?, scope: StSlashScope): StClosure? {
        scope.localClosures[name]?.let { return it }
        StClosureStore.get(host?.chatKey, name)?.let { return it }
        val raw = host?.varOp("getvar", name, "")?.takeIf { it.isNotBlank() } ?: return null
        return StClosureSyntax.parse(raw)
    }

    private fun runClosure(
        closure: StClosure,
        provided: Map<String, String>,
        host: StSlashHost?,
        parentPipe: String,
        scope: StSlashScope,
        label: String,
        injectedMacros: Map<String, String> = emptyMap(),
    ): String {
        if (scope.depth >= MAX_CLOSURE_DEPTH) {
            return "闭包递归深度超过上限（$MAX_CLOSURE_DEPTH）：$label"
        }
        val args = LinkedHashMap<String, String>()
        closure.argumentList.forEach { args[it.name] = it.value }
        provided.forEach { (key, value) -> args[key] = value }
        val mergedMacros = LinkedHashMap<String, String>(scope.currentMacros())
        mergedMacros.putAll(injectedMacros)
        scope.argStack.addLast(args)
        scope.macroStack.addLast(mergedMacros)
        scope.depth++
        try {
            val body = StClosureSyntax.substituteArgs(closure.body, args)
            return execute(body, host, scope, initialPipe = parentPipe)
        } finally {
            scope.depth--
            scope.macroStack.removeLast()
            scope.argStack.removeLast()
        }
    }

    private fun runSwitchCommand(
        rawArgs: String,
        host: StSlashHost?,
        pipe: String,
        isFirst: Boolean,
        seg: StSlashParser.Segment,
        scope: StSlashScope,
    ): String {
        val text = resolveRest(rawArgs, pipe, isFirst, seg.noPipeInject)
        val tokens = StClosureSyntax.tokenizeArgs(text)
        if (tokens.isEmpty()) return "用法：/switch <value> <case>={<closure>} default={<closure>}"
        val value = StSlashParser.unwrapQuotes(tokens.first())
        var fallback: String? = null
        for (token in tokens.drop(1)) {
            val eq = token.indexOf('=')
            if (eq <= 0) continue
            val key = token.substring(0, eq).trim()
            val branch = token.substring(eq + 1).trim()
            if (key.equals("default", ignoreCase = true)) fallback = branch
            if (key == value) return executeSwitchBranch(branch, host, pipe, scope)
        }
        return fallback?.let { executeSwitchBranch(it, host, pipe, scope) } ?: ""
    }

    private fun executeSwitchBranch(branch: String, host: StSlashHost?, pipe: String, scope: StSlashScope): String {
        val closure = StClosureSyntax.parse(branch)
        return if (closure != null) {
            runClosure(closure, emptyMap(), host, pipe, scope, label = "<switch>")
        } else {
            StSlashParser.unwrapQuotes(branch)
        }
    }


    // ==================== 算术与字符串扩展命令 ====================

    private fun formatSlashNumber(value: Double): String {
        if (!value.isFinite()) return "0"
        val asLong = value.toLong()
        return if (value == asLong.toDouble()) asLong.toString() else value.toString()
    }

    private fun parseNumericOperands(
        rawArgs: String,
        pipe: String,
        isFirst: Boolean,
        noPipeInject: Boolean,
        host: StSlashHost?,
        scope: StSlashScope,
    ): List<Double> {
        val (_, rest) = StSlashParser.extractNamedArgs(rawArgs)
        val tokens = StClosureSyntax.tokenizeArgs(rest.trim())
            .map { resolveOperand(it, host, scope) }
            .filter { it.isNotEmpty() }
        val nums = tokens.mapNotNull { it.toDoubleOrNull() }.toMutableList()
        if (nums.size < 2 && !isFirst && !noPipeInject && pipe.isNotBlank()) {
            pipe.trim().toDoubleOrNull()?.let { pipeNum ->
                if (nums.isEmpty()) nums.add(pipeNum) else nums.add(0, pipeNum)
            }
        }
        return nums
    }

    private fun runMathCommand(
        cmd: String,
        rawArgs: String,
        pipe: String,
        isFirst: Boolean,
        noPipeInject: Boolean,
        host: StSlashHost?,
        scope: StSlashScope,
    ): String {
        if (cmd == "rand") {
            val (named, rest) = StSlashParser.extractNamedArgs(rawArgs)
            val tokens = StClosureSyntax.tokenizeArgs(rest.trim())
                .map { resolveOperand(it, host, scope) }
                .filter { it.isNotEmpty() }
            val from = named["from"]?.toDoubleOrNull()
                ?: if (tokens.size >= 2) tokens[0].toDoubleOrNull() ?: 0.0 else 0.0
            val to = named["to"]?.toDoubleOrNull()
                ?: when {
                    tokens.size >= 2 -> tokens[1].toDoubleOrNull() ?: 1.0
                    tokens.size == 1 -> tokens[0].toDoubleOrNull() ?: 1.0
                    else -> 1.0
                }
            val lo = minOf(from, to)
            val hi = maxOf(from, to)
            val rawRand = if (hi <= lo) lo else lo + kotlin.random.Random.nextDouble() * (hi - lo)
            return when (named["round"]?.lowercase()) {
                "round" -> kotlin.math.round(rawRand).toLong().toString()
                "floor" -> kotlin.math.floor(rawRand).toLong().toString()
                "ceil" -> kotlin.math.ceil(rawRand).toLong().toString()
                else -> formatSlashNumber(rawRand)
            }
        }
        val nums = parseNumericOperands(rawArgs, pipe, isFirst, noPipeInject, host, scope)
        if (nums.isEmpty()) return "0"
        return when (cmd) {
            "add" -> formatSlashNumber(nums.sum())
            "sub" -> formatSlashNumber(if (nums.size == 1) nums[0] else nums.drop(1).fold(nums[0]) { acc, v -> acc - v })
            "mul" -> formatSlashNumber(nums.fold(1.0) { acc, v -> acc * v })
            "div" -> {
                val a = nums[0]
                val b = nums.getOrElse(1) { 1.0 }
                if (b == 0.0) "0" else formatSlashNumber(a / b)
            }
            "mod" -> {
                val a = nums[0]
                val b = nums.getOrElse(1) { 1.0 }
                if (b == 0.0) "0" else formatSlashNumber(a % b)
            }
            "pow" -> {
                val a = nums[0]
                val b = nums.getOrElse(1) { 1.0 }
                formatSlashNumber(Math.pow(a, b))
            }
            "min" -> formatSlashNumber(nums.minOrNull() ?: 0.0)
            "max" -> formatSlashNumber(nums.maxOrNull() ?: 0.0)
            "round" -> kotlin.math.round(nums[0]).toLong().toString()
            "floor" -> kotlin.math.floor(nums[0]).toLong().toString()
            "ceil" -> kotlin.math.ceil(nums[0]).toLong().toString()
            "abs" -> formatSlashNumber(kotlin.math.abs(nums[0]))
            "sin" -> formatSlashNumber(kotlin.math.sin(nums[0]))
            "cos" -> formatSlashNumber(kotlin.math.cos(nums[0]))
            "log" -> if (nums[0] <= 0.0) "0" else formatSlashNumber(kotlin.math.ln(nums[0]))
            "sqrt" -> if (nums[0] < 0.0) "0" else formatSlashNumber(kotlin.math.sqrt(nums[0]))
            else -> "0"
        }
    }

    private fun runStringCommand(
        cmd: String,
        rawArgs: String,
        pipe: String,
        isFirst: Boolean,
        noPipeInject: Boolean,
        host: StSlashHost?,
    ): String {
        val (txt, named) = resolveText(rawArgs, host, pipe, isFirst, noPipeInject)
        return when (cmd) {
            "len", "length" -> {
                val trimmed = txt.trim()
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    val inner = trimmed.substring(1, trimmed.length - 1).trim()
                    if (inner.isEmpty()) "0" else StClosureSyntax.tokenizeArgs(inner.replace(',', ' ')).size.toString()
                } else {
                    txt.length.toString()
                }
            }
            "trim" -> txt.trim()
            "trimstart" -> txt.trimStart()
            "trimend" -> txt.trimEnd()
            "upper", "uppercase" -> txt.uppercase()
            "lower", "lowercase" -> txt.lowercase()
            "substr", "substring" -> {
                val start = (named["start"]?.toIntOrNull() ?: 0).coerceIn(0, txt.length)
                val end = when {
                    named.containsKey("end") -> (named["end"]?.toIntOrNull() ?: txt.length).coerceIn(start, txt.length)
                    named.containsKey("length") -> (start + (named["length"]?.toIntOrNull() ?: txt.length)).coerceIn(start, txt.length)
                    else -> txt.length
                }
                txt.substring(start, end)
            }
            "replace" -> {
                val find = StSlashParser.unwrapQuotes(named["find"] ?: named["pattern"] ?: "")
                val repl = StSlashParser.unwrapQuotes(named["replace"] ?: named["with"] ?: "")
                if (find.isEmpty()) txt
                else if (named["mode"].equals("regex", ignoreCase = true)) {
                    runCatching { Regex(find).replace(txt, repl) }.getOrDefault(txt)
                } else {
                    txt.replace(find, repl)
                }
            }
            "match" -> {
                val pattern = StSlashParser.unwrapQuotes(named["pattern"] ?: named["find"] ?: "")
                if (pattern.isEmpty()) ""
                else runCatching { Regex(pattern).find(txt)?.value.orEmpty() }.getOrDefault("")
            }
            "split" -> {
                val sep = StSlashParser.unwrapQuotes(named["sep"] ?: named["find"] ?: ",")
                val parts = if (sep.isEmpty()) txt.map { it.toString() } else txt.split(sep)
                parts.joinToString(prefix = "[", postfix = "]", separator = ",") { part ->
                    "\"" + part.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                }
            }
            "join" -> {
                val glue = StSlashParser.unwrapQuotes(named["sep"] ?: named["glue"] ?: ", ")
                val trimmed = txt.trim()
                val items = if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    trimmed.substring(1, trimmed.length - 1)
                        .split(',')
                        .map { StSlashParser.unwrapQuotes(it.trim()) }
                        .filter { it.isNotEmpty() }
                } else {
                    StClosureSyntax.tokenizeArgs(trimmed).map(StSlashParser::unwrapQuotes)
                }
                items.joinToString(glue)
            }
            "sort" -> {
                val trimmed = txt.trim()
                val items = if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    trimmed.substring(1, trimmed.length - 1)
                        .split(',')
                        .map { StSlashParser.unwrapQuotes(it.trim()) }
                        .filter { it.isNotEmpty() }
                } else if (trimmed.contains(",")) {
                    trimmed.split(',').map { StSlashParser.unwrapQuotes(it.trim()) }.filter { it.isNotEmpty() }
                } else {
                    StClosureSyntax.tokenizeArgs(trimmed).map(StSlashParser::unwrapQuotes).filter { it.isNotEmpty() }
                }
                val desc = named["order"]?.equals("desc", ignoreCase = true) == true ||
                    named["mode"]?.equals("desc", ignoreCase = true) == true ||
                    named["direction"]?.equals("desc", ignoreCase = true) == true
                val allNumeric = items.isNotEmpty() && items.all { it.toDoubleOrNull() != null }
                val sorted = if (allNumeric) {
                    if (desc) items.sortedByDescending { it.toDouble() } else items.sortedBy { it.toDouble() }
                } else {
                    if (desc) items.sortedDescending() else items.sorted()
                }
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    sorted.joinToString(prefix = "[", postfix = "]", separator = ",") { part ->
                        if (allNumeric) part else "\"" + part.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                    }
                } else {
                    sorted.joinToString(", ")
                }
            }
            "array-wrap" -> {
                val trimmed = txt.trim()
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) trimmed
                else "[\"" + txt.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]"
            }
            "array-unwrap" -> {
                val trimmed = txt.trim()
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    val inner = trimmed.substring(1, trimmed.length - 1).trim()
                    if (inner.isEmpty()) ""
                    else StSlashParser.unwrapQuotes(inner.split(',').first().trim())
                } else txt
            }
            "fuzzy" -> {
                val listRaw = named["list"] ?: "[]"
                val candidates = if (listRaw.trim().startsWith("[") && listRaw.trim().endsWith("]")) {
                    listRaw.trim().let { it.substring(1, it.length - 1) }
                        .split(',')
                        .map { StSlashParser.unwrapQuotes(it.trim()) }
                        .filter { it.isNotEmpty() }
                } else {
                    listRaw.split(',').map { StSlashParser.unwrapQuotes(it.trim()) }.filter { it.isNotEmpty() }
                }
                val query = (named["query"] ?: txt).trim()
                if (candidates.isEmpty() || query.isEmpty()) ""
                else candidates.maxByOrNull { cand -> fuzzySimilarity(query, cand) }.orEmpty()
            }
            else -> txt
        }
    }

    private fun fuzzySimilarity(a: String, b: String): Double {
        val s1 = a.lowercase()
        val s2 = b.lowercase()
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        if (s2.contains(s1) || s1.contains(s2)) {
            return 0.85 + 0.15 * (minOf(s1.length, s2.length).toDouble() / maxOf(s1.length, s2.length).toDouble())
        }
        val dp = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..s2.length) {
                val temp = dp[j]
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = temp
            }
        }
        val maxLen = maxOf(s1.length, s2.length)
        return 1.0 - (dp[s2.length].toDouble() / maxLen.toDouble())
    }

    private fun trimTextToTokenLimit(text: String, limit: Int, direction: String): String {
        val mode = me.rerere.rikkahub.data.st.tokenizer.TokenizerMode.CL100K
        val total = me.rerere.rikkahub.data.st.tokenizer.countTokens(text, mode)
        if (total <= limit) return text
        val words = text.split(Regex("(?<=\\s)|(?=\\s)"))
        if (direction == "start" || direction == "left") {
            val acc = ArrayDeque<String>()
            for (i in words.indices.reversed()) {
                acc.addFirst(words[i])
                val candidate = acc.joinToString("")
                if (me.rerere.rikkahub.data.st.tokenizer.countTokens(candidate, mode) > limit) {
                    if (acc.size > 1) acc.removeFirst()
                    break
                }
            }
            val wordResult = acc.joinToString("").trimStart()
            // 单词粒度装不下（单个词就超限，或 CJK 无空格长句）→ 字符粒度保留尽可能多的内容
            return wordResult.ifEmpty { trimCharsToTokenLimit(text, limit, fromStart = true, mode) }
        } else {
            val sb = StringBuilder()
            for (w in words) {
                val next = sb.toString() + w
                if (me.rerere.rikkahub.data.st.tokenizer.countTokens(next, mode) > limit) break
                sb.append(w)
            }
            val wordResult = sb.toString().trimEnd()
            return wordResult.ifEmpty { trimCharsToTokenLimit(text, limit, fromStart = false, mode) }
        }
    }

    /** 字符粒度兜底：保留不超过 limit token 的最长前缀（fromStart=true）或后缀（false）。 */
    private fun trimCharsToTokenLimit(
        text: String,
        limit: Int,
        fromStart: Boolean,
        mode: me.rerere.rikkahub.data.st.tokenizer.TokenizerMode,
    ): String {
        for (len in text.length - 1 downTo 1) {
            val candidate = if (fromStart) text.substring(0, len) else text.substring(text.length - len)
            if (me.rerere.rikkahub.data.st.tokenizer.countTokens(candidate, mode) <= limit) {
                return candidate.trim()
            }
        }
        return ""
    }

    private val headlessClipboard = java.util.concurrent.atomic.AtomicReference("")
    private val headlessDataBank = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, String>>()
    private val headlessSecrets = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val headlessTools = java.util.concurrent.ConcurrentHashMap<String, StClosure>()

    fun getRegisteredSlashToolNames(): List<String> = headlessTools.keys.toList()

    private val JSON_TOOL_ARG_REGEX = Regex(""""([^"]+)"\s*:\s*(?:"((?:\\.|[^"\\])*)"|([^,}\s]+))""")

    private fun parseToolCallArgsJson(argsJson: String): Map<String, String> {
        val callArgs = LinkedHashMap<String, String>()
        JSON_TOOL_ARG_REGEX.findAll(argsJson).forEach { m ->
            val k = m.groupValues[1]
            val v = if (m.groups[2] != null) m.groupValues[2] else m.groupValues[3]
            callArgs[k] = v
        }
        return callArgs
    }

    fun invokeRegisteredSlashTool(name: String, argsJson: String, host: StSlashHost? = null): String {
        val closure = headlessTools[name.trim()] ?: return ""
        val callArgs = parseToolCallArgsJson(argsJson)
        return runClosure(
            closure = closure,
            provided = callArgs,
            host = host,
            parentPipe = "",
            scope = StSlashScope(),
            label = "tool:$name",
        )
    }
    private val headlessTags = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    private val headlessProfiles = java.util.concurrent.ConcurrentHashMap<String, String>()

    internal fun runFallbackExtendedCommand(
        cmd: String,
        named: Map<String, String>,
        unnamed: String,
        pipe: String,
        host: StSlashHost?,
        scope: StSlashScope,
    ): String {
        return when (cmd) {
            "clipboard-set" -> {
                headlessClipboard.set(unnamed)
                unnamed
            }
            "clipboard-get" -> headlessClipboard.get()
            "reasoning-parse" -> {
                val m = Regex("""<(?:think|thinking)>([\s\S]*?)</(?:think|thinking)>""", RegexOption.IGNORE_CASE).find(unnamed)
                m?.groupValues?.get(1)?.trim() ?: ""
            }
            "reasoning-get" -> scope.scopedVars["__reasoning_last__"] ?: ""
            "reasoning-set" -> {
                scope.scopedVars["__reasoning_last__"] = unnamed
                unnamed
            }
            "reasoning-template", "reasoning-format", "reasoning-toggle", "reasoning-collapse", "reasoning-expand" -> {
                if (unnamed.isNotEmpty()) scope.scopedVars["__${cmd}__"] = unnamed
                scope.scopedVars["__${cmd}__"] ?: "true"
            }
            "vector-threshold" -> {
                val v = unnamed.toDoubleOrNull()
                if (v != null && v in 0.0..1.0) {
                    StVectorCompat.vectorThreshold = v
                }
                StVectorCompat.vectorThreshold.toString()
            }
            "vector-max-entries" -> {
                val v = unnamed.toIntOrNull()
                if (v != null && v > 0) {
                    StVectorCompat.vectorMaxEntries = v
                }
                StVectorCompat.vectorMaxEntries.toString()
            }
            "vector-worldinfo-state", "vector-files-state", "vector-chats-state" -> {
                if (unnamed.isNotEmpty()) {
                    val en = isTrueBoolean(unnamed)
                    if (cmd == "vector-worldinfo-state") {
                        StVectorCompat.vectorWorldInfoEnabled = en
                    }
                    scope.scopedVars["__${cmd}__"] = en.toString()
                }
                scope.scopedVars["__${cmd}__"]
                    ?: StVectorCompat.vectorWorldInfoEnabled.toString()
            }
            "db-add" -> {
                val name = (named["name"] ?: named["title"] ?: "doc_${headlessDataBank.size + 1}").trim()
                val source = (named["source"] ?: "global").trim()
                val bucket = headlessDataBank.getOrPut(source) { java.util.concurrent.ConcurrentHashMap() }
                bucket[name] = unnamed
                name
            }
            "db-get" -> {
                val name = (named["name"] ?: unnamed).trim()
                val source = (named["source"] ?: "global").trim()
                headlessDataBank[source]?.get(name)
                    ?: headlessDataBank.values.firstNotNullOfOrNull { it[name] }
                    ?: ""
            }
            "db-update" -> {
                val name = (named["name"] ?: "").trim()
                val source = (named["source"] ?: "global").trim()
                if (name.isNotEmpty()) {
                    headlessDataBank.getOrPut(source) { java.util.concurrent.ConcurrentHashMap() }[name] = unnamed
                }
                name
            }
            "db-delete" -> {
                val name = (named["name"] ?: unnamed).trim()
                headlessDataBank.values.forEach { it.remove(name) }
                ""
            }
            "db-list", "db" -> {
                val allNames = headlessDataBank.values.flatMap { it.keys }.distinct()
                allNames.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }
            }
            "db-enable", "db-disable", "db-ingest", "db-purge" -> {
                if (cmd == "db-purge") headlessDataBank.clear()
                ""
            }
            "db-search", "vector-query" -> {
                val query = unnamed.trim()
                val topK = (named["top"] ?: named["limit"])?.toIntOrNull()
                    ?: StVectorCompat.vectorMaxEntries
                val threshold = (named["threshold"])?.toDoubleOrNull()
                    ?: StVectorCompat.vectorThreshold
                val scored = headlessDataBank.values.flatMap { it.entries }
                    .map { (k, v) ->
                        val sim = StVectorCompat.computeVectorSimilarity(query, "$k $v")
                        Triple(k, v, sim)
                    }
                    .filter { it.third >= threshold }
                    .sortedByDescending { it.third }
                    .take(topK)
                if (named["return"] == "names") {
                    scored.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"${it.first}\"" }
                } else {
                    scored.joinToString("\n") { it.second }
                }
            }
            "tag-add" -> {
                val charKey = (named["name"] ?: "default").trim()
                val tag = unnamed.trim()
                if (tag.isNotEmpty()) headlessTags.getOrPut(charKey) { mutableSetOf() }.add(tag)
                tag
            }
            "tag-remove" -> {
                val charKey = (named["name"] ?: "default").trim()
                val tag = unnamed.trim()
                headlessTags[charKey]?.remove(tag)
                tag
            }
            "tag-exists" -> {
                val charKey = (named["name"] ?: "default").trim()
                (headlessTags[charKey]?.contains(unnamed.trim()) == true).toString()
            }
            "tag-list" -> {
                val charKey = (named["name"] ?: "default").trim()
                headlessTags[charKey].orEmpty().joinToString(", ")
            }
            "tag-import" -> "true"
            "secret-write" -> {
                val key = (named["key"] ?: named["id"] ?: "default").trim()
                headlessSecrets[key] = unnamed
                key
            }
            "secret-read", "secret-id" -> {
                val key = (named["key"] ?: named["id"] ?: unnamed).trim()
                headlessSecrets[key] ?: ""
            }
            "secret-delete" -> {
                val key = (named["key"] ?: named["id"] ?: unnamed).trim()
                headlessSecrets.remove(key)
                ""
            }
            "secret-rename" -> {
                val oldKey = (named["old"] ?: named["key"] ?: "").trim()
                val newKey = (named["new"] ?: unnamed).trim()
                val v = headlessSecrets.remove(oldKey)
                if (v != null && newKey.isNotEmpty()) headlessSecrets[newKey] = v
                newKey
            }
            "tools-register" -> {
                val name = (named["name"] ?: "").trim()
                val closure = StClosureSyntax.parse(unnamed)
                if (name.isNotEmpty() && closure != null) {
                    headlessTools[name] = closure
                    scope.localClosures[name] = closure
                }
                name
            }
            "tools-unregister" -> {
                val name = (named["name"] ?: unnamed).trim()
                val existed = headlessTools.remove(name) != null
                existed.toString()
            }
            "tools-list" -> {
                headlessTools.keys.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }
            }
            "tools-invoke" -> {
                val name = (named["name"] ?: unnamed).trim()
                val closure = headlessTools[name] ?: scope.localClosures[name]
                if (closure != null) {
                    val paramsJson = named["parameters"] ?: named["args"] ?: "{}"
                    val callArgs = parseToolCallArgsJson(paramsJson)
                    runClosure(closure = closure, provided = callArgs, host = host, parentPipe = pipe, scope = scope, label = "tool:$name")
                } else ""
            }
            "profile", "profile-create", "profile-update" -> {
                val name = (named["name"] ?: unnamed).trim()
                if (name.isNotEmpty()) {
                    headlessProfiles[name] = named["api"] ?: "openai"
                    scope.scopedVars["__active_profile__"] = name
                }
                scope.scopedVars["__active_profile__"] ?: name
            }
            "profile-get" -> {
                val name = (named["name"] ?: unnamed).trim().ifEmpty { scope.scopedVars["__active_profile__"].orEmpty() }
                if (name.isEmpty()) "" else "{\"name\":\"$name\",\"api\":\"${headlessProfiles[name] ?: "openai"}\"}"
            }
            "profile-list" -> {
                headlessProfiles.keys.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }
            }
            "profile-genstream" -> "true"
            "loader-wrap" -> {
                val closure = StClosureSyntax.parse(unnamed)
                if (closure != null) runClosure(closure = closure, provided = emptyMap(), host = host, parentPipe = pipe, scope = scope, label = "<loader-wrap>") else unnamed
            }
            "loader-show", "loader-hide", "loader-stop",
            "beep", "panels", "resetpanels", "movingui", "vn", "bubble", "flat", "single",
            "pm-render", "chat-render", "chat-reload", "forcesave", "reload-page" -> ""
            else -> unnamed
        }
    }

    private fun delayBestEffort(millis: Long) {
        val ms = millis.coerceIn(0L, MAX_DELAY_MS)
        if (ms <= 0) return
        if (Thread.currentThread().name == "main") return
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

internal class StSlashScope {
    var abort: Boolean = false
    var abortReason: String = ""
    var broken: Boolean = false
    var lastIfCondition: Boolean? = null
    var depth: Int = 0
    val scopedVars: MutableMap<String, String> = mutableMapOf()
    val localClosures: MutableMap<String, StClosure> = mutableMapOf()
    val argStack: ArrayDeque<Map<String, String>> = ArrayDeque()
    val macroStack: ArrayDeque<Map<String, String>> = ArrayDeque()

    fun setLocal(key: String, value: String) {
        scopedVars[key] = value
    }

    /** 当前最内层闭包的命名参数（无闭包时为空表） */
    fun currentArgs(): Map<String, String> = argStack.lastOrNull().orEmpty()

    /** 当前最内层闭包的作用域宏（如 `/times` 注入的 `timesIndex`） */
    fun currentMacros(): Map<String, String> = macroStack.lastOrNull().orEmpty()
}
