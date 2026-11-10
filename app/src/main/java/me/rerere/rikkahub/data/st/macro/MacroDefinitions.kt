package me.rerere.rikkahub.data.st.macro

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

/**
 * ST 内置宏定义（处理器实现体）—— 移植自 SillyTavern 1.18.0
 * `public/scripts/macros/definitions/` 下的 core / time / chat / env / state / variable 六个文件。
 *
 * 暂缓（待在后续批次实现）：
 * - instruct-macros.js 全系列（instruct*、systemPrompt、defaultSystemPrompt、
 *   exampleSeparator、chatStart）—— 依赖 instruct 模块与 power_user 设置。
 *
 * PORT NOTE（行为近似与差异项）：
 * - pick：种子链与 ARC4 完全对齐（bit-exact，见 [StSeedrandom]）；
 * - datetimeformat：moment token → java.time pattern 的简短映射（支持 LT/LTS/L/LL/LLL/LLLL
 *   与常见 token；无法映射的字母按字面量处理）；
 * - idleDuration / timeDiff：en 风格 humanize 近似（a few seconds / 2 minutes / an hour / ...）；
 * - isMobile 恒为 "true"（客户端恒为移动环境）；
 * - maxContext / maxResponse 来自 env.system（未知 → 空串）；maxPrompt = maxContext - maxResponse；
 * - hasExtension 基于 env.system.enabledExtensions（忽略大小写，支持 third-party/ 前缀）；
 * - input / outlet / banned 返回空串（无对应子系统；与 ST 在“未配置 / 非 textgen 模式”下一致）；
 * - original 依赖 env.originalFn（未配置时抛错 → 引擎保留原文，对齐 ST 的报错兜底）；
 * - mesExamples 按 chat-completion（openai）模式以 `'<START>\n'` 为块标题。
 */
object MacroDefinitions {

    /** 注册全部内置宏（重复调用为幂等覆盖）。批次 C 接入时由 StMacroTransformer 初始化时调用。 */
    fun registerAll() {
        registerCore()
        registerTime()
        registerChat()
        registerEnv()
        registerState()
        registerVariable()
        registerInstruct()
    }

    // ==================== core-macros.js ====================

    private fun registerCore() {
        // {{space}} / {{space::4}} -> ' '
        reg(
            "space", minArgs = 0, maxArgs = 1,
            argDefs = listOf(MacroArgDef("count", optional = true, types = listOf("integer"))),
        ) { ctx -> " ".repeat(countArg(ctx) ?: 1) }

        // {{newline}} / {{newline::2}} -> '\n'
        reg(
            "newline", minArgs = 0, maxArgs = 1,
            argDefs = listOf(MacroArgDef("count", optional = true, types = listOf("integer"))),
        ) { ctx -> "\n".repeat(countArg(ctx) ?: 1) }

        // {{noop}} -> ''
        reg("noop", aliases = listOf("blank")) { "" }

        // {{trim}}：非 scoped → 交由后处理清除标记；scoped → 返回内容（引擎已自动 trim）
        reg("trim", minArgs = 0, maxArgs = 1, argDefs = listOf(MacroArgDef("content", optional = true))) { ctx ->
            if (ctx.isScoped) ctx.unnamedArgs.getOrNull(0) ?: "" else "{{trim}}"
        }

        // {{if condition}}content{{/if}} / {{if condition::content}} / {{if condition}}then{{else}}other{{/if}}
        reg(
            "if", minArgs = 2, maxArgs = 2, delayArgResolution = true,
            argDefs = listOf(MacroArgDef("condition"), MacroArgDef("content")),
        ) { ctx -> handleIf(ctx) }

        // {{else}} -> 内部标记（仅在有作用域的 {{if}} 内被消费；游离时由后处理清除）
        reg("else") { MacroEngine.ELSE_MARKER }

        // {{input}} -> ''（PORT NOTE：无 send_textarea 访问；生成期输入框为空）
        reg("input") { ctx -> ctx.env.system.input }

        reg("maxPrompt", minArgs = 0, maxArgs = 0, aliases = listOf("maxPromptTokens")) { ctx ->
            val context = ctx.env.system.maxContextTokens
            val response = ctx.env.system.maxResponseTokens
            if (context != null && response != null) (context - response).toString() else ""
        }
        reg("maxContext", minArgs = 0, maxArgs = 0, aliases = listOf("maxContextTokens")) { ctx ->
            ctx.env.system.maxContextTokens?.toString() ?: ""
        }
        reg("maxResponse", minArgs = 0, maxArgs = 0, aliases = listOf("maxResponseTokens")) { ctx ->
            ctx.env.system.maxResponseTokens?.toString() ?: ""
        }

        // {{reverse::I am Lana}} -> 按码点反转（对齐 Array.from 的代理对语义）
        reg("reverse", minArgs = 1, maxArgs = 1, argDefs = listOf(MacroArgDef("value"))) { ctx ->
            reverseByCodePoints(ctx.unnamedArgs.getOrNull(0) ?: "")
        }

        // {{// ...}} -> ''。注：实测 ST 中 {{///}} 是“注释宏 + 参数 '/'”而非闭合标签，
        // 故 scoped 配对不会作用于注释宏（与 ST 行为一致）。
        reg(
            "//", minArgs = 0, maxArgs = 1, list = true, strictArgs = false, delayArgResolution = true,
            aliases = listOf("comment"), argDefs = listOf(MacroArgDef("comment")),
        ) { "" }

        // {{roll::1d20}} / {{roll::6}} / {{roll::3d6+4}}
        reg("roll", minArgs = 1, maxArgs = 1, argDefs = listOf(MacroArgDef("formula"))) { ctx ->
            var formula = ctx.unnamedArgs.getOrNull(0) ?: ""
            if (Regex("^\\d+$").matches(formula)) {
                formula = "1d$formula"
            }
            if (!StDroll.validate(formula)) {
                ctx.warn("Invalid roll formula: $formula")
                ""
            } else {
                StDroll.roll(formula)?.toString() ?: ""
            }
        }

        // {{random::a::b}} / {{random a,b}} —— 每次求值都重掷
        reg("random", minArgs = 0, maxArgs = 0, list = true) { ctx ->
            var list = ctx.list
            if (list.size == 1) list = readSingleArgsRandomList(list[0])
            if (list.isEmpty()) {
                ""
            } else {
                val index = floor(Random.Default.nextDouble() * list.size).toInt()
                list[index]
            }
        }

        // {{pick::a::b}} —— 同一聊天 + 同一内容 + 同一位置 → 稳定选择（ARC4 精确移植）
        reg("pick", minArgs = 0, maxArgs = 0, list = true) { ctx ->
            var list = ctx.list
            if (list.size == 1) list = readSingleArgsRandomList(list[0])
            if (list.isEmpty()) {
                ""
            } else {
                val rerollSeed = ctx.env.system.pickRerollSeed?.takeIf { it.isNotEmpty() }
                val seedParts = buildList {
                    ctx.env.system.chatIdHash?.let { add(it.toString()) }
                    add(ctx.env.contentHash.toString())
                    add(ctx.globalOffset.toString())
                    rerollSeed?.let { add(it) }
                }
                val finalSeed = MacroHash.getStringHash(seedParts.joinToString("-"))
                val rng = StSeedrandom.create(finalSeed.toString())
                val index = floor(rng() * list.size).toInt()
                list[index]
            }
        }

        // {{banned::word}} -> ''（PORT NOTE：无 textgen 后端；对齐 chat-completion 模式下的空行为）
        reg("banned", minArgs = 1, maxArgs = 1, argDefs = listOf(MacroArgDef("word"))) { ctx ->
            val word = ctx.unnamedArgs.getOrNull(0)?.trim().orEmpty()
            if (word.isNotEmpty()) ctx.env.system.bannedWords.add(word)
            ""
        }

        // {{outlet::key}} -> outlet 内容。官方 getOutletPrompt：读取扩展注入槽 CUSTOM_WI_OUTLET(key)，
        // 未激活/未配置时为空串。key 先原文匹配、失败再 trim 兜底（对齐酒馆宽松读取）。
        reg("summary") { ctx ->
            ctx.env.variables.local.get("__st_memory_summary")?.takeIf { it.isNotEmpty() }
                ?: ctx.env.variables.local.get("summary").orEmpty()
        }

        reg("outlet", minArgs = 1, maxArgs = 1, argDefs = listOf(MacroArgDef("key"))) { ctx ->
            val key = ctx.unnamedArgs.getOrNull(0) ?: ""
            ctx.env.outlets[key] ?: ctx.env.outlets[key.trim()] ?: ""
        }
    }

    /** {{if}} 处理器（delayArgResolution：只解析被选中的分支） */
    private fun handleIf(ctx: MacroExecutionContext): String {
        val rawCondition = ctx.unnamedArgs.getOrNull(0) ?: ""
        val rawContent = ctx.unnamedArgs.getOrNull(1) ?: ""

        var inverted = false
        var condition = rawCondition
        if (Regex("^\\s*!").containsMatchIn(rawCondition)) {
            inverted = true
            condition = Regex("^\\s*!\\s*").replace(rawCondition, "")
        }

        condition = ctx.resolve(condition)

        val varMatch = VAR_SHORTHAND_REGEX.matchEntire(condition)
        if (varMatch != null) {
            val prefix = varMatch.groupValues[1]
            val varName = varMatch.groupValues[2]
            val varMacro = if (prefix == ".") "getvar" else "getglobalvar"
            condition = ctx.resolve("{{$varMacro::$varName}}")
        } else {
            val macroDef = MacroRegistry.getPrimaryMacro(condition)
            if (macroDef != null && macroDef.minArgs == 0) {
                condition = ctx.resolve("{{$condition}}")
            }
        }

        var isFalsy = condition.isEmpty() || MacroEngine.isFalseBoolean(condition)
        if (inverted) isFalsy = !isFalsy

        val split = MacroEngine.splitOnTopLevelElse(rawContent)
        val chosen = if (!isFalsy) split.first else split.second ?: return ""

        var result = ctx.resolve(chosen)
        if (!ctx.flags.preserveWhitespace) {
            result = ctx.trimContent(result)
        }
        return result
    }

    // ==================== time-macros.js ====================

    private fun registerTime() {
        // {{time}} / {{time::UTC+2}}
        reg(
            "time", minArgs = 0, maxArgs = 1,
            argDefs = listOf(MacroArgDef("offset", optional = true, types = listOf("string"))),
        ) { ctx ->
            formatLt(timeAtOffset(nowZdt(ctx.env), ctx.unnamedArgs.getOrNull(0)), ctx.env.locale)
        }

        // {{date}} -> moment LL（本地化长日期）
        reg("date") { ctx ->
            DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
                .withLocale(ctx.env.locale)
                .format(nowZdt(ctx.env))
        }

        // {{weekday}} -> 本地化星期全名
        reg("weekday") { ctx ->
            nowZdt(ctx.env).dayOfWeek.getDisplayName(TextStyle.FULL, ctx.env.locale)
        }

        reg("isotime") { ctx -> nowZdt(ctx.env).format(DateTimeFormatter.ofPattern("HH:mm")) }
        reg("isodate") { ctx -> nowZdt(ctx.env).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) }

        // {{datetimeformat::YYYY-MM-DD HH:mm:ss}}
        reg("datetimeformat", minArgs = 1, maxArgs = 1, argDefs = listOf(MacroArgDef("format"))) { ctx ->
            formatMomentLike(ctx.unnamedArgs.getOrNull(0) ?: "", nowZdt(ctx.env), ctx.env.locale)
        }

        // {{idleDuration}} -> 距最后一条用户消息的可读时长
        reg("idleDuration", minArgs = 0, maxArgs = 0, aliases = listOf("idle_duration")) { ctx ->
            timeSinceLastMessage(ctx.env)
        }

        // {{timeDiff::left::right}} -> 可读差值（带 in/ago 后缀）
        reg("timeDiff", minArgs = 2, maxArgs = 2, argDefs = listOf(MacroArgDef("left"), MacroArgDef("right"))) { ctx ->
            val left = parseMomentLike(ctx.unnamedArgs.getOrNull(0) ?: "", ctx.env.zone)
            val right = parseMomentLike(ctx.unnamedArgs.getOrNull(1) ?: "", ctx.env.zone)
            if (left == null || right == null) {
                "Invalid date"
            } else {
                humanizeDuration(left.toEpochMilli() - right.toEpochMilli(), withSuffix = true)
            }
        }
    }

    private fun nowZdt(env: MacroEnv): ZonedDateTime =
        ZonedDateTime.now(env.clock).withZoneSameInstant(env.zone)

    /** {{time::UTC±n}} 的时区偏移计算（不受 ZoneOffset ±18h 限制） */
    private fun timeAtOffset(now: ZonedDateTime, offsetSpec: String?): ZonedDateTime {
        if (offsetSpec.isNullOrEmpty()) return now
        val match = Regex("^UTC([+-]\\d+)$").find(offsetSpec) ?: return now
        val hours = match.groupValues[1].toIntOrNull() ?: return now
        return now.withZoneSameInstant(ZoneOffset.UTC).plusHours(hours.toLong())
    }

    /** moment 的 LT（本地化时间）近似：en → h:mm A；ja → H:mm；其他 → HH:mm */
    private fun formatLt(zdt: ZonedDateTime, locale: Locale): String {
        val time = zdt.toLocalTime()
        return when (locale.language) {
            "en" -> {
                val hour12 = if (time.hour % 12 == 0) 12 else time.hour % 12
                val suffix = if (time.hour < 12) "AM" else "PM"
                "%d:%02d %s".format(hour12, time.minute, suffix)
            }
            "ja" -> "${time.hour}:%02d".format(time.minute)
            else -> "%02d:%02d".format(time.hour, time.minute)
        }
    }

    /** 距最后一条用户消息的时长（对齐 ST getTimeSinceLastMessage） */
    private fun timeSinceLastMessage(env: MacroEnv): String {
        val now = nowZdt(env).toInstant()
        if (env.chat.isNotEmpty()) {
            var lastMessage: MacroChatMessage? = null
            var takeNext = false
            for (i in env.chat.indices.reversed()) {
                val message = env.chat[i]
                if (message.isSystem) continue
                if (message.isUser && takeNext) {
                    lastMessage = message
                    break
                }
                takeNext = true
            }
            val sentAt = lastMessage?.sentAt
            if (sentAt != null) {
                return humanizeDuration(Duration.between(sentAt, now).toMillis(), withSuffix = false)
            }
        }
        return "just now"
    }

    /** moment 解析近似：ISO（带偏移 / Z）与常见本地格式；失败返回 null */
    private fun parseMomentLike(text: String, zone: ZoneId): Instant? {
        val t = text.trim()
        if (t.isEmpty()) return null
        runCatching { OffsetDateTime.parse(t) }.getOrNull()?.let { return it.toInstant() }
        runCatching { Instant.parse(t) }.getOrNull()?.let { return it }
        for (pattern in LOCAL_DATE_TIME_FORMATS) {
            val formatter = DateTimeFormatter.ofPattern(pattern)
            runCatching { LocalDateTime.parse(t, formatter) }.getOrNull()?.let { return it.atZone(zone).toInstant() }
            runCatching { LocalDate.parse(t, formatter) }.getOrNull()?.let { return it.atStartOfDay(zone).toInstant() }
        }
        return null
    }

    private val LOCAL_DATE_TIME_FORMATS = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd",
        "yyyy-M-d",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd HH:mm",
        "yyyy/MM/dd",
        "yyyy/M/d",
        "MM/dd/yyyy HH:mm:ss",
        "MM/dd/yyyy",
    )

    /**
     * moment 的 humanize 近似（en）：
     * a few seconds / N seconds / a minute / N minutes / an hour / N hours / a day / N days /
     * a month / N months / a year / N years；withSuffix 时附加 in / ago。
     */
    private fun humanizeDuration(millis: Long, withSuffix: Boolean): String {
        val ms = abs(millis.toDouble())
        val seconds = Math.round(ms / 1000.0)
        val minutes = Math.round(ms / 60000.0)
        val hours = Math.round(ms / 3600000.0)
        val days = Math.round(ms / 86400000.0)
        val monthsRaw = daysToMonths(ms / 86400000.0)
        val months = Math.round(monthsRaw)
        val years = Math.round(monthsRaw / 12.0)

        val key: String
        val number: Long
        when {
            seconds <= 44L -> { key = "s"; number = seconds }
            seconds < 45L -> { key = "ss"; number = seconds }
            minutes <= 1L -> { key = "m"; number = 1L }
            minutes < 45L -> { key = "mm"; number = minutes }
            hours <= 1L -> { key = "h"; number = 1L }
            hours < 22L -> { key = "hh"; number = hours }
            days <= 1L -> { key = "d"; number = 1L }
            days < 26L -> { key = "dd"; number = days }
            months <= 1L -> { key = "M"; number = 1L }
            months < 11L -> { key = "MM"; number = months }
            years <= 1L -> { key = "y"; number = 1L }
            else -> { key = "yy"; number = years }
        }
        val n = if (number == 0L) 1L else number
        val text = when (key) {
            "s" -> "a few seconds"
            "ss" -> "$n seconds"
            "m" -> "a minute"
            "mm" -> "$n minutes"
            "h" -> "an hour"
            "hh" -> "$n hours"
            "d" -> "a day"
            "dd" -> "$n days"
            "M" -> "a month"
            "MM" -> "$n months"
            "y" -> "a year"
            else -> "$n years"
        }
        if (!withSuffix) return text
        return if (millis > 0) "in $text" else "$text ago"
    }

    private fun daysToMonths(days: Double): Double = days * 4800.0 / 146097.0

    // ==================== chat-macros.js ====================

    private fun registerChat() {
        reg("lastMessage", aliases = listOf("lastChatMessage")) { ctx -> lastMessageId(ctx.env)?.let { ctx.env.chat[it].text } ?: "" }
        reg("lastMessageId") { ctx -> lastMessageId(ctx.env)?.toString() ?: "" }
        reg("lastUserMessage") { ctx ->
            lastMessageId(ctx.env) { it.isUser && !it.isSystem }?.let { ctx.env.chat[it].text } ?: ""
        }
        reg("lastCharMessage") { ctx ->
            lastMessageId(ctx.env) { !it.isUser && !it.isSystem }?.let { ctx.env.chat[it].text } ?: ""
        }
        reg("firstIncludedMessageId") { ctx ->
            ctx.env.system.lastInContextMessageId?.toString() ?: ""
        }
        reg("firstDisplayedMessageId") { ctx ->
            // 对齐 ST：读取视图中第一条消息；客户端无 DOM 时以“chat 非空 → 0”兜底
            val id = ctx.env.system.firstDisplayedMessageId ?: if (ctx.env.chat.isNotEmpty()) 0 else null
            id?.toString() ?: ""
        }
        reg("lastSwipeId") { ctx ->
            val mid = lastMessageId(ctx.env, excludeSwipeInProgress = false) ?: return@reg ""
            ctx.env.chat[mid].swipeCount?.toString() ?: ""
        }
        reg("currentSwipeId") { ctx ->
            val mid = lastMessageId(ctx.env, excludeSwipeInProgress = false) ?: return@reg ""
            ctx.env.chat[mid].currentSwipeId?.toString() ?: ""
        }
        reg("allChatRange") { ctx ->
            if (ctx.env.chat.isEmpty()) "" else "0-${ctx.env.chat.lastIndex}"
        }
    }

    /**
     * 最后一条消息索引（对齐 ST getLastMessageId）。
     *
     * @param excludeSwipeInProgress true 时跳过“swipe 生成中”的消息
     *        （JS 判据：swipe_id >= swipes.length；本模型中 currentSwipeId 为 1-based → currentSwipeId > swipeCount）
     */
    private fun lastMessageId(
        env: MacroEnv,
        excludeSwipeInProgress: Boolean = true,
        filter: ((MacroChatMessage) -> Boolean)? = null,
    ): Int? {
        if (env.chat.isEmpty()) return null
        for (i in env.chat.indices.reversed()) {
            val m = env.chat[i]
            if (excludeSwipeInProgress && m.swipeCount != null && m.currentSwipeId != null &&
                m.currentSwipeId > m.swipeCount
            ) {
                continue
            }
            if (filter == null || filter(m)) return i
        }
        return null
    }

    // ==================== env-macros.js ====================

    private fun registerEnv() {
        reg("user") { it.env.names.user }
        reg("char") { it.env.names.char }
        reg("group", minArgs = 0, maxArgs = 0, aliases = listOf("charIfNotGroup")) { it.env.names.group }
        reg("groupNotMuted") { it.env.names.groupNotMuted }
        reg("notChar") { it.env.names.notChar }

        reg("charPrompt", aliases = listOf("char_prompt", "system_prompt")) { it.env.character.charPrompt }
        reg("charInstruction", aliases = listOf("charJailbreak", "post_history_instructions", "jailbreak")) { it.env.character.charInstruction }
        reg("charDescription", aliases = listOf("description")) { it.env.character.description }
        reg("charPersonality", aliases = listOf("personality")) { it.env.character.personality }
        reg("charScenario", aliases = listOf("scenario")) { it.env.character.scenario }
        reg("persona") { it.env.character.persona }
        reg("mesExamplesRaw", aliases = listOf("mes_example_raw")) { it.env.character.mesExamplesRaw }
        reg("mesExamples", aliases = listOf("mes_example")) { ctx -> buildMesExamples(ctx.env) }
        reg("charDepthPrompt", aliases = listOf("depth_prompt", "char_depth_prompt")) { it.env.character.charDepthPrompt }
        reg("charCreatorNotes", aliases = listOf("creatorNotes", "creator_notes")) { it.env.character.creatorNotes }

        reg(
            "charFirstMessage", minArgs = 0, maxArgs = 1, aliases = listOf("greeting", "first_mes", "firstMessage"),
            argDefs = listOf(MacroArgDef("index", optional = true, types = listOf("integer"))),
        ) { ctx -> firstMessageAt(ctx.env, ctx.unnamedArgs.getOrNull(0)) }

        reg("charVersion", aliases = listOf("version", "char_version")) { it.env.character.version }

        reg("model") { it.env.system.model }

        // 导演备注三宏（对齐 ST authors-note.js:604-614；该模块被 world-info.js import，属核心宏）。
        // 官方来源：authorsNote=chat_metadata（按对话）、charAuthorsNote=extension_settings.note.chara（按角色卡）、
        // defaultAuthorsNote=extension_settings.note.default。rikkaST 只有全局 Settings.authorNote，
        // 映射见 [MacroEnv.AuthorNotes]（charAuthorsNote 无数据源恒空串，已登记语义差异）。
        reg("authorsNote") { it.env.authorNotes.current }
        reg("charAuthorsNote") { it.env.authorNotes.character }
        reg("defaultAuthorsNote") { it.env.authorNotes.default }

        // {{original}}：单次替换（对齐 ST env.functions.original 的闭包语义）
        reg("original") { it.env.takeOriginal() }

        reg("isMobile") { "true" }
    }

    /** {{charFirstMessage::index}}：0（默认）主问候；1+ 为备选问候 */
    private fun firstMessageAt(env: MacroEnv, indexArg: String?): String {
        if (indexArg == null) return env.character.firstMessage
        val i = indexArg.trim().toLongOrNull() ?: return ""
        if (i == 0L) return env.character.firstMessage
        val idx = i - 1L
        val alt = env.character.alternateGreetings
        return if (idx in 0L until alt.size.toLong()) alt[idx.toInt()] else ""
    }

    /** {{mesExamples}}：对话示例（chat-completion 模式：块标题 `<START>\n`） */
    private fun buildMesExamples(env: MacroEnv): String {
        val raw = env.character.mesExamplesRaw
        if (raw.isEmpty()) return ""
        return parseMesExamplesForClient(raw).joinToString("")
    }

    private val START_SPLIT_REGEX = Regex("<START>", RegexOption.IGNORE_CASE)

    /**
     * 移植自 script.js parseMesExamples（isInstruct=false、main_api=openai 分支）：
     * 非 `<START>` 开头时前置 `<START>\n`；按 `/<START>/gi` 分割取 slice(1)，
     * 每块加块标题 + trim + '\n'。
     */
    private fun parseMesExamplesForClient(examplesStr: String): List<String> {
        var s = examplesStr
        if (s.isEmpty() || s == "<START>") return emptyList()
        if (!s.startsWith("<START>")) s = "<START>\n" + s.trim()
        val heading = "<START>\n"
        return s.split(START_SPLIT_REGEX).drop(1).map { block -> "$heading${block.trim()}\n" }
    }

    // ==================== state-macros.js ====================

    private fun registerState() {
        reg("lastGenerationType") { it.env.system.generationType }
        reg("hasExtension", minArgs = 1, maxArgs = 1, argDefs = listOf(MacroArgDef("extensionName"))) { ctx ->
            hasExtension(ctx.env, ctx.unnamedArgs.getOrNull(0) ?: "").toString()
        }
    }

    /** 对齐 findExtension：名称精确（忽略大小写）或 `third-party/` 前缀；未启用 → false */
    private fun hasExtension(env: MacroEnv, name: String): Boolean {
        return env.system.enabledExtensions.any { candidate ->
            candidate.equals(name, ignoreCase = true) ||
                candidate.equals("third-party/$name", ignoreCase = true)
        }
    }

    // ==================== variable-macros.js ====================

    private fun registerVariable() {
        val stringType = listOf("string")
        val valueTypes = listOf("string", "number")

        reg("setvar", minArgs = 2, maxArgs = 2, argDefs = listOf(MacroArgDef("name", types = stringType), MacroArgDef("value", types = valueTypes))) { ctx ->
            ctx.env.variables.local.set(ctx.unnamedArgs.getOrNull(0) ?: "", ctx.unnamedArgs.getOrNull(1) ?: "")
            ""
        }
        reg("addvar", minArgs = 2, maxArgs = 2, argDefs = listOf(MacroArgDef("name", types = stringType), MacroArgDef("value", types = valueTypes))) { ctx ->
            ctx.env.variables.local.add(ctx.unnamedArgs.getOrNull(0) ?: "", ctx.unnamedArgs.getOrNull(1) ?: "")
            ""
        }
        reg("incvar", minArgs = 1, maxArgs = 1, argDefs = listOf(MacroArgDef("name", types = stringType))) { ctx ->
            ctx.normalize(ctx.env.variables.local.inc(ctx.unnamedArgs.getOrNull(0) ?: ""))
        }
        reg("decvar", minArgs = 1, maxArgs = 1, argDefs = listOf(MacroArgDef("name", types = stringType))) { ctx ->
            ctx.normalize(ctx.env.variables.local.dec(ctx.unnamedArgs.getOrNull(0) ?: ""))
        }
        reg("getvar", minArgs = 1, maxArgs = 1, argDefs = listOf(MacroArgDef("name", types = stringType))) { ctx ->
            ctx.normalize(ctx.env.variables.local.get(ctx.unnamedArgs.getOrNull(0) ?: ""))
        }
        reg("hasvar", minArgs = 1, maxArgs = 1, aliases = listOf("varexists"), argDefs = listOf(MacroArgDef("name", types = stringType))) { ctx ->
            if (ctx.env.variables.local.has(ctx.unnamedArgs.getOrNull(0) ?: "")) "true" else "false"
        }
        reg("deletevar", minArgs = 1, maxArgs = 1, aliases = listOf("flushvar"), argDefs = listOf(MacroArgDef("name", types = stringType))) { ctx ->
            ctx.env.variables.local.del(ctx.unnamedArgs.getOrNull(0) ?: "")
            ""
        }

        reg("setglobalvar", minArgs = 2, maxArgs = 2, argDefs = listOf(MacroArgDef("name", types = stringType), MacroArgDef("value", types = valueTypes))) { ctx ->
            ctx.env.variables.global.set(ctx.unnamedArgs.getOrNull(0) ?: "", ctx.unnamedArgs.getOrNull(1) ?: "")
            ""
        }
        reg("addglobalvar", minArgs = 2, maxArgs = 2, argDefs = listOf(MacroArgDef("name", types = stringType), MacroArgDef("value", types = valueTypes))) { ctx ->
            ctx.env.variables.global.add(ctx.unnamedArgs.getOrNull(0) ?: "", ctx.unnamedArgs.getOrNull(1) ?: "")
            ""
        }
        reg("incglobalvar", minArgs = 1, maxArgs = 1, argDefs = listOf(MacroArgDef("name", types = stringType))) { ctx ->
            ctx.normalize(ctx.env.variables.global.inc(ctx.unnamedArgs.getOrNull(0) ?: ""))
        }
        reg("decglobalvar", minArgs = 1, maxArgs = 1, argDefs = listOf(MacroArgDef("name", types = stringType))) { ctx ->
            ctx.normalize(ctx.env.variables.global.dec(ctx.unnamedArgs.getOrNull(0) ?: ""))
        }
        reg("getglobalvar", minArgs = 1, maxArgs = 1, argDefs = listOf(MacroArgDef("name", types = stringType))) { ctx ->
            ctx.normalize(ctx.env.variables.global.get(ctx.unnamedArgs.getOrNull(0) ?: ""))
        }
        reg("hasglobalvar", minArgs = 1, maxArgs = 1, aliases = listOf("globalvarexists"), argDefs = listOf(MacroArgDef("name", types = stringType))) { ctx ->
            if (ctx.env.variables.global.has(ctx.unnamedArgs.getOrNull(0) ?: "")) "true" else "false"
        }
        reg("deleteglobalvar", minArgs = 1, maxArgs = 1, aliases = listOf("flushglobalvar"), argDefs = listOf(MacroArgDef("name", types = stringType))) { ctx ->
            ctx.env.variables.global.del(ctx.unnamedArgs.getOrNull(0) ?: "")
            ""
        }

        // [v234 S1] varkey 系（ST variable-macros.js）：对象/数组的键级读写
        reg(
            "setvarkey", minArgs = 3, maxArgs = 3, aliases = listOf("setvarindex"),
            argDefs = listOf(
                MacroArgDef("name", types = stringType),
                MacroArgDef("key", types = valueTypes),
                MacroArgDef("value", types = valueTypes),
            ),
        ) { ctx ->
            ctx.env.variables.local.setAtKey(
                ctx.unnamedArgs.getOrNull(0) ?: "",
                ctx.unnamedArgs.getOrNull(1) ?: "",
                ctx.unnamedArgs.getOrNull(2) ?: "",
            )
            ""
        }
        reg(
            "getvarkey", minArgs = 2, maxArgs = 2, aliases = listOf("getvarindex"),
            argDefs = listOf(MacroArgDef("name", types = stringType), MacroArgDef("key", types = valueTypes)),
        ) { ctx ->
            ctx.normalize(ctx.env.variables.local.getAtKey(ctx.unnamedArgs.getOrNull(0) ?: "", ctx.unnamedArgs.getOrNull(1) ?: ""))
        }
        reg(
            "setglobalvarkey", minArgs = 3, maxArgs = 3, aliases = listOf("setglobalvarindex"),
            argDefs = listOf(
                MacroArgDef("name", types = stringType),
                MacroArgDef("key", types = valueTypes),
                MacroArgDef("value", types = valueTypes),
            ),
        ) { ctx ->
            ctx.env.variables.global.setAtKey(
                ctx.unnamedArgs.getOrNull(0) ?: "",
                ctx.unnamedArgs.getOrNull(1) ?: "",
                ctx.unnamedArgs.getOrNull(2) ?: "",
            )
            ""
        }
        reg(
            "getglobalvarkey", minArgs = 2, maxArgs = 2, aliases = listOf("getglobalvarindex"),
            argDefs = listOf(MacroArgDef("name", types = stringType), MacroArgDef("key", types = valueTypes)),
        ) { ctx ->
            ctx.normalize(ctx.env.variables.global.getAtKey(ctx.unnamedArgs.getOrNull(0) ?: "", ctx.unnamedArgs.getOrNull(1) ?: ""))
        }
    }

    // ==================== instruct-macros.js（v234 S1 补齐） ====================

    /**
     * ST instruct 系列宏（registerSimple 语义：instruct 未启用时返回空串）。
     * 数据源为 env.instruct（StMacroSupport 从助手 InstructTemplate 构建）；
     * 宿主未覆盖的模板字段（stop_sequence / user_alignment_message / last_system_sequence）恒为空串。
     * exampleSeparator / chatStart 不受 enabled 门控（对齐 ST 注册处的 `() => true`）。
     */
    private fun registerInstruct() {
        fun simple(names: List<String>, get: (MacroEnv.InstructInfo) -> String) {
            reg(
                names[0], minArgs = 0, maxArgs = 0,
                aliases = names.drop(1),
            ) { ctx -> if (ctx.env.instruct.enabled) get(ctx.env.instruct) else "" }
        }
        simple(listOf("instructStoryStringPrefix")) { it.storyStringPrefix }
        simple(listOf("instructStoryStringSuffix")) { it.storyStringSuffix }
        simple(listOf("instructUserPrefix", "instructInput")) { it.userPrefix }
        simple(listOf("instructUserSuffix")) { it.userSuffix }
        simple(listOf("instructAssistantPrefix", "instructOutput")) { it.assistantPrefix }
        simple(listOf("instructAssistantSuffix", "instructSeparator")) { it.assistantSuffix }
        simple(listOf("instructSystemPrefix")) { it.systemPrefix }
        simple(listOf("instructSystemSuffix")) { it.systemSuffix }
        simple(listOf("instructFirstAssistantPrefix", "instructFirstOutputPrefix")) { it.firstAssistantPrefix }
        simple(listOf("instructLastAssistantPrefix", "instructLastOutputPrefix")) { it.lastAssistantPrefix }
        simple(listOf("instructStop")) { it.stopSequence }
        simple(listOf("instructUserFiller")) { it.userFiller }
        simple(listOf("instructSystemInstructionPrefix")) { it.systemInstructionPrefix }
        simple(listOf("instructFirstUserPrefix", "instructFirstInput")) { it.firstUserPrefix }
        simple(listOf("instructLastUserPrefix", "instructLastInput")) { it.lastUserPrefix }
        // 上下文模板宏（不受 instruct.enabled 门控，对齐 ST）
        reg("exampleSeparator", minArgs = 0, maxArgs = 0, aliases = listOf("chatSeparator")) { ctx -> ctx.env.instruct.exampleSeparator }
        reg("chatStart", minArgs = 0, maxArgs = 0) { ctx -> ctx.env.instruct.chatStart }
        // systemPrompt：sysprompt 关闭返回空串；prefer_character_prompt（ST 默认 true）时卡片 charPrompt 非空优先
        reg("systemPrompt", minArgs = 0, maxArgs = 0) { ctx ->
            if (!ctx.env.system.syspromptEnabled) ""
            else ctx.env.character.charPrompt.ifBlank { ctx.env.system.syspromptContent }
        }
        reg("defaultSystemPrompt", minArgs = 0, maxArgs = 0, aliases = listOf("default_system_prompt")) { ctx ->
            ctx.env.system.syspromptContent
        }
    }

    // ==================== 工具 ====================

    // ==================== 工具 ====================

    private fun reg(
        name: String,
        minArgs: Int = 0,
        maxArgs: Int = 0,
        list: Boolean = false,
        strictArgs: Boolean = true,
        delayArgResolution: Boolean = false,
        aliases: List<String> = emptyList(),
        argDefs: List<MacroArgDef> = emptyList(),
        handler: (MacroExecutionContext) -> String,
    ) {
        MacroRegistry.register(
            MacroDefinition(
                name = name,
                minArgs = minArgs,
                maxArgs = maxArgs,
                list = list,
                strictArgs = strictArgs,
                delayArgResolution = delayArgResolution,
                aliases = aliases,
                argDefs = argDefs,
                handler = handler,
            ),
        )
    }

    /** 可选整数参数（缺省 → null；存在但溢出 → 抛错，由引擎保留原文，对齐 JS RangeError→raw） */
    private fun countArg(ctx: MacroExecutionContext): Int? {
        val raw = ctx.unnamedArgs.getOrNull(0) ?: return null
        return raw.trim().toInt()
    }

    /** 按 Unicode 码点反转（对齐 JS Array.from(str).reverse()，代理对不被拆开） */
    private fun reverseByCodePoints(value: String): String {
        val codePoints = value.codePoints().toArray()
        val sb = StringBuilder(value.length)
        for (i in codePoints.indices.reversed()) {
            sb.appendCodePoint(codePoints[i])
        }
        return sb.toString()
    }

    /** ST readSingleArgsRandomList：`::` 优先；否则按 `,` 分割（`\,` 还原为 `,`） */
    private fun readSingleArgsRandomList(listString: String): List<String> {
        if (listString.contains("::")) {
            return listString.split("::").map { it.trim() }
        }
        val placeholder = "##\uFFFDCOMMA\uFFFD##"
        return listString.replace("\\,", placeholder)
            .split(",")
            .map { it.trim().replace(placeholder, ",") }
    }

    /** 变量简写正则（用 . 或 $ 前缀的完整匹配），对齐 ST `^([.$])(VAR_PATTERN)$` */
    private val VAR_SHORTHAND_REGEX = Regex("^([.\$])(${MACRO_VARIABLE_SHORTHAND_PATTERN})\$")

    private const val MACRO_VARIABLE_SHORTHAND_PATTERN = "[a-zA-Z](?:[\\w\\-_]*[\\w])?"

    // ==================== datetimeformat（moment → java.time） ====================

    private val MOMENT_PRESETS = listOf("LLLL", "LLL", "LTS", "LL", "LT", "L", "llll", "lll", "ll", "l")

    private val MOMENT_TOKENS: List<Pair<String, String>> = listOf(
        "YYYY" to "yyyy",
        "YY" to "yy",
        "MMMM" to "MMMM",
        "MMM" to "MMM",
        "MM" to "MM",
        "M" to "M",
        "DDD" to "DDD",
        "DD" to "dd",
        "D" to "d",
        "dddd" to "EEEE",
        "ddd" to "EEE",
        "dd" to "EE",
        "HH" to "HH",
        "H" to "H",
        "hh" to "hh",
        "h" to "h",
        "mm" to "mm",
        "m" to "m",
        "ss" to "ss",
        "s" to "s",
        "SSS" to "SSS",
        "A" to "a",
        "a" to "a",
        "ZZ" to "xx",
        "Z" to "XXX",
    )

    /** 格式化 moment 风格格式串（近似映射；无法格式化时返回空串） */
    private fun formatMomentLike(format: String, zdt: ZonedDateTime, locale: Locale): String {
        val pattern = buildString {
            var i = 0
            while (i < format.length) {
                val c = format[i]

                // [字面量]
                if (c == '[') {
                    val end = format.indexOf(']', i + 1)
                    if (end != -1) {
                        appendJavaLiteral(format.substring(i + 1, end))
                        i = end + 1
                        continue
                    }
                }

                // '字面量'（'' 表示单个 '）
                if (c == '\'') {
                    val sb = StringBuilder()
                    i++
                    while (i < format.length) {
                        if (format[i] == '\'') {
                            if (i + 1 < format.length && format[i + 1] == '\'') {
                                sb.append('\'')
                                i += 2
                                continue
                            }
                            i++
                            break
                        }
                        sb.append(format[i])
                        i++
                    }
                    appendJavaLiteral(sb.toString())
                    continue
                }

                val preset = matchMomentPreset(format, i, locale)
                if (preset != null) {
                    append(preset.second)
                    i += preset.first
                    continue
                }

                val token = matchMomentToken(format, i)
                if (token != null) {
                    append(token.second)
                    i += token.first
                    continue
                }

                if (c.isLetter()) {
                    appendJavaLiteral(c.toString())
                } else {
                    append(c)
                }
                i++
            }
        }
        return try {
            DateTimeFormatter.ofPattern(pattern, locale).format(zdt)
        } catch (e: IllegalArgumentException) {
            ""
        }
    }

    private fun matchMomentToken(format: String, index: Int): Pair<Int, String>? {
        for ((token, replacement) in MOMENT_TOKENS) {
            if (format.startsWith(token, index)) return token.length to replacement
        }
        return null
    }

    private fun matchMomentPreset(format: String, index: Int, locale: Locale): Pair<Int, String>? {
        for (tag in MOMENT_PRESETS) {
            if (format.startsWith(tag, index)) return tag.length to localizedPattern(tag, locale)
        }
        return null
    }

    private fun localizedPattern(tag: String, locale: Locale): String {
        val lang = locale.language
        return when (tag) {
            "LT" -> when (lang) {
                "en" -> "h:mm a"
                "ja" -> "H:mm"
                else -> "HH:mm"
            }
            "LTS" -> if (lang == "en") "h:mm:ss a" else "HH:mm:ss"
            "L" -> when (lang) {
                "en" -> "M/d/yyyy"
                "zh" -> "yyyy/M/d"
                else -> "yyyy-MM-dd"
            }
            "LL" -> when (lang) {
                "en" -> "MMMM d, yyyy"
                "zh", "ja" -> "yyyy年M月d日"
                else -> "d MMMM yyyy"
            }
            "LLL" -> localizedPattern("LL", locale) + " " + localizedPattern("LT", locale)
            "LLLL" -> if (lang == "en") {
                "EEEE, MMMM d, yyyy h:mm a"
            } else {
                localizedPattern("LL", locale) + " " + localizedPattern("LT", locale)
            }
            "l" -> localizedPattern("L", locale)
            "ll" -> when (lang) {
                "en" -> "MMM d, yyyy"
                "zh", "ja" -> "yyyy年M月d日"
                else -> "d MMM yyyy"
            }
            "lll" -> localizedPattern("ll", locale) + " " + localizedPattern("LT", locale)
            "llll" -> if (lang == "en") {
                "EEE, MMM d, yyyy h:mm a"
            } else {
                localizedPattern("ll", locale) + " " + localizedPattern("LT", locale)
            }
            else -> tag
        }
    }

    /** 向 java.time pattern 追加单引号包裹的字面量 */
    private fun StringBuilder.appendJavaLiteral(text: String) {
        if (text.isEmpty()) return
        append('\'')
        for (ch in text) {
            if (ch == '\'') append("''") else append(ch)
        }
        append('\'')
    }
}