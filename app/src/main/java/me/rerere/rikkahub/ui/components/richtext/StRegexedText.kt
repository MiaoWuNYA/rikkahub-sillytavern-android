package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.st.macro.DynamicMacro
import me.rerere.rikkahub.data.st.macro.MacroDefinitions
import me.rerere.rikkahub.data.st.macro.MacroEngine
import me.rerere.rikkahub.data.st.macro.MacroEnv
import me.rerere.rikkahub.data.st.macro.MacroRegistry
import me.rerere.rikkahub.data.st.macro.MacroVarKeyOps
import me.rerere.rikkahub.data.st.macro.MacroVariableStore
import me.rerere.rikkahub.data.st.macro.VariableStores
import me.rerere.rikkahub.data.st.regex.RegexScript
import me.rerere.rikkahub.data.st.regex.RegexScriptEngine
import me.rerere.rikkahub.data.st.runtime.StWorldInfoOutlets
import me.rerere.rikkahub.data.st.runtime.TavernRuntimeManager
import me.rerere.rikkahub.data.st.runtime.TavernVariableStore
import java.util.Collections

internal data class StRegexRenderResult(
    val text: String,
    val isComputing: Boolean,
)

private val QUICK_STRIP_BLOCKS_RE = Regex(
    """<(style|script|UpdateVariable|Analysis|JSONPatch|thinking|TASK_CHECK_PASS|Interleaving)\b[^>]*>[\s\S]*?</\1>""",
    RegexOption.IGNORE_CASE,
)
private val QUICK_STRIP_TAGS_RE = Regex("""<[^>]+>""")
private val QUICK_HTML_COMMENT_RE = Regex("""<!--[\s\S]*?-->""")
private val RESIDUAL_THINK_STRIP_RE = Regex(
    """<(think|thinking|thought)\b[^>]*>[\s\S]*?(?:</\1>|\z)""",
    RegexOption.IGNORE_CASE,
)
private val DRAW_TAG_BLOCK_RE = Regex(
    """\[出图]\s*([\s\S]*?)\s*\[/出图]""",
    RegexOption.IGNORE_CASE,
)
private val loggedWrapperDiagAssistants: MutableSet<String> =
    Collections.synchronizedSet(HashSet<String>())

/**
 * 检测角色卡描述/世界书模板是否包含未配对的 `〔` / `〕` 响应包装符（即模板要求包裹 `〔...〕`，但激活的正则脚本中无任何匹配 `〔` 或 `〕` 的清洗规则）。
 */
internal fun detectUnmatchedCardWrapperBrackets(
    assistant: Assistant?,
    activeScripts: List<RegexScript>,
): Boolean {
    val tav = assistant?.tavernData ?: return false
    fun hasPair(s: String): Boolean =
        (s.contains('〔') && s.contains('〕')) || (s.contains('〖') && s.contains('〗'))
    val hasWrapperInCardPrompts = hasPair(tav.description) ||
        hasPair(tav.systemPrompt) ||
        hasPair(tav.postHistoryInstructions) ||
        (tav.embeddedBook?.entries?.any { hasPair(it.content) } == true)
    if (!hasWrapperInCardPrompts) return false
    val anyRegexTouchesWrapper = activeScripts.any {
        !it.disabled && (
            it.findRegex.contains('〔') || it.findRegex.contains('〕') ||
                it.findRegex.contains('〖') || it.findRegex.contains('〗')
            )
    }
    return !anyRegexTouchesWrapper
}

private val STANDALONE_WRAPPER_LINE_RE = Regex("^\\s*[〔〕〖〗]\\s*$")
private val HTML_BOUNDARY_LEADING_WRAPPER_RE = Regex("(?m)^(\\s*)[〔〖](\\s*<(?:style|div|section|article|main|script|!--|/base|/mes|/sh|/txt)\\b)", RegexOption.IGNORE_CASE)
private val HTML_BOUNDARY_TRAILING_WRAPPER_RE = Regex("(?m)(</(?:div|style|script|section|article|main|base|mes|sh|txt)>|-->)(\\s*)[〕〗](\\s*)$", RegexOption.IGNORE_CASE)

/**
 * 视觉层可选清理：剥离消息首尾、HTML 模板块首尾或独立成行的 `〔`/`〕`、`〖`/`〗` 模板包装符，
 * 严格保留句内正常使用的括号（如“她翻开〔世界史〕笔记本”）。
 */
private fun stripUnmatchedBoundaryWrappers(input: String): String {
    var out = input
    for ((openCh, closeCh) in listOf('〔' to '〕', '〖' to '〗')) {
        val openCount = out.count { it == openCh }
        val closeCount = out.count { it == closeCh }
        if (openCount == closeCount) continue
        val trimmed = out.trim()
        if (openCount == 1 && closeCount == 0 && trimmed.startsWith(openCh)) {
            val idx = out.indexOf(openCh)
            if (idx >= 0) out = out.removeRange(idx, idx + 1).trimStart('\n', '\r')
        } else if (closeCount == 1 && openCount == 0 && trimmed.endsWith(closeCh)) {
            val idx = out.lastIndexOf(closeCh)
            if (idx >= 0) out = out.removeRange(idx, idx + 1).trimEnd('\n', '\r')
        }
    }
    return out
}

internal fun stripOuterTemplateWrapperBrackets(
    text: String,
    enabled: Boolean = true,
): String {
    if (!enabled || (!text.contains('〔') && !text.contains('〕') && !text.contains('〖') && !text.contains('〗'))) {
        return text
    }
    var result = text
    for ((openCh, closeCh) in listOf('〔' to '〕', '〖' to '〗')) {
        val trimmedAll = result.trim()
        if (trimmedAll.startsWith(openCh) && trimmedAll.endsWith(closeCh) && trimmedAll.length >= 2) {
            val inner = trimmedAll.substring(1, trimmedAll.length - 1)
            if (!inner.contains(openCh) || inner.count { it == openCh } == inner.count { it == closeCh }) {
                result = inner.trim()
            }
        }
    }
    result = HTML_BOUNDARY_LEADING_WRAPPER_RE.replace(result, "$1$2")
    result = HTML_BOUNDARY_TRAILING_WRAPPER_RE.replace(result, "$1$2$3")
    result = stripUnmatchedBoundaryWrappers(result)
    val lines = result.lines()
    if (lines.any {
            val t = it.trim()
            t.startsWith('〔') || t.endsWith('〕') || t.startsWith('〖') || t.endsWith('〗')
        }
    ) {
        val cleanedLines = mutableListOf<String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (STANDALONE_WRAPPER_LINE_RE.matches(trimmed)) {
                continue
            }
            var processedLine = line
            for ((openCh, closeCh) in listOf('〔' to '〕', '〖' to '〗')) {
                val t = processedLine.trim()
                if (t.startsWith(openCh) && t.endsWith(closeCh) && t.length >= 2 &&
                    t.substring(1, t.length - 1).indexOf(openCh) < 0 &&
                    t.substring(1, t.length - 1).indexOf(closeCh) < 0
                ) {
                    val s = processedLine.replaceFirst(openCh.toString(), "")
                    val lastIdx = s.lastIndexOf(closeCh)
                    processedLine = if (lastIdx >= 0) s.removeRange(lastIdx, lastIdx + 1) else s
                }
            }
            cleanedLines += processedLine
        }
        result = cleanedLines.joinToString("\n")
    }
    return result
}

/**
 * 拦截正文内未被第三方扩展消费的 `[出图]...[/出图]` 提示词块：
 * 将原本直接倾倒在正文里的长串生图 Tag 转换为折叠式小手机生图卡片，避免污染小说正文排版。
 */
internal fun convertUnhandledDrawTagsToHtml(text: String): String {
    if (!text.contains("[出图]") || !text.contains("[/出图]")) return text
    return DRAW_TAG_BLOCK_RE.replace(text) { match ->
        val rawPrompt = match.groupValues[1].trim()
        if (rawPrompt.isEmpty()) return@replace ""
        val escapedPrompt = rawPrompt
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        """<div class="rikka-draw-card" style="margin:8px 0;padding:10px 12px;border-radius:10px;background:rgba(30,41,59,0.78);border:1px solid rgba(148,163,184,0.28);color:#e2e8f0;font-size:13px;"><div style="display:flex;align-items:center;justify-content:space-between;gap:8px;"><span style="font-weight:600;color:#93c5fd;">🎨 小手机生图指令已捕获</span><span style="font-size:11px;color:#94a3b8;">可在右侧小手机悬浮窗查看</span></div><details style="margin-top:6px;"><summary style="cursor:pointer;color:#94a3b8;font-size:12px;">展开生图参数</summary><pre style="white-space:pre-wrap;word-break:break-word;margin:6px 0 0 0;padding:8px;border-radius:6px;background:rgba(15,23,42,0.65);color:#cbd5e1;font-size:11px;">$escapedPrompt</pre></details></div>"""
    }
}

/**
 * 快速检测文本是否可能命中 JS 侧 `MessageFormatter` 扩展钩子（如 ST-iPhonie 的 `<tts>`、`<pic>`、`<bgm>`、`<sfx>`、`<phone>`、`[出图]` 或自定义测试钩子）。
 * 若当前文本不包含任何扩展多媒体/协议标签且未注入测试 evaluator，则无需跨线程等待 WebView IPC 往返，
 * 直接在 Kotlin 侧同步执行 `RegexScriptEngine`（耗时 < 2ms），彻底消除「正在应用显示正则与预设排版规则…」长等待！
 */
internal fun shouldInvokeWebViewMessageFormatter(rawText: String): Boolean {
    if (!TavernRuntimeManager.hasMessageFormatterHooks) return false
    if (TavernRuntimeManager.customMessageFormatterEvaluator != null) return true
    if (rawText.isEmpty()) return false
    if (rawText.contains("<tts", ignoreCase = true) ||
        rawText.contains("</tts>", ignoreCase = true) ||
        rawText.contains("<pic", ignoreCase = true) ||
        rawText.contains("<bgm", ignoreCase = true) ||
        rawText.contains("<sfx", ignoreCase = true) ||
        rawText.contains("<phone", ignoreCase = true) ||
        rawText.contains("[出图]") ||
        rawText.contains("[/出图]") ||
        rawText.contains("data-sttts", ignoreCase = true) ||
        rawText.contains("sttts-", ignoreCase = true)
    ) {
        return true
    }
    return false
}

/**
 * 当 >50KB 超长开场白在后台计算正则期间（或开场白选择器预览时），快速提取可读纯文字正文，杜绝返回 "" 空串导致白条或裸露 CSS。
 */
internal fun extractQuickPlainPreview(rawText: String): String {
    // 超长消息在后台正则/MessageFormatter 完成前，快速预览不能把图片一起剥掉；
    // 保留 <img> 为 Markdown 图片，<pic> 明确显示“图片生成中”占位，异步结果回来后再替换。
    val images = mutableListOf<String>()
    var text = rawText
    text = Regex("""<pic\b[^>]*>[\s\S]*?</pic>|<pic\b[^>]*/>""", RegexOption.IGNORE_CASE)
        .replace(text) { match ->
            val token = "\u0001PIC${images.size}\u0001"
            images.add("*［图片生成中…］*")
            "\n$token\n"
        }
    text = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE).replace(text) { match ->
        val tag = match.value
        val src = Regex("""\bsrc\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.getOrNull(2).orEmpty()
        val token = "\u0001IMG${images.size}\u0001"
        images.add(
            if (src.isNotBlank()) "![图片]($src)" else "*［图片加载中…］*"
        )
        "\n$token\n"
    }
    text = text
        .replace(QUICK_HTML_COMMENT_RE, "")
        .replace(QUICK_STRIP_BLOCKS_RE, "")
        .replace(QUICK_STRIP_TAGS_RE, "")
        .replace(Regex("\n{3,}"), "\n\n")
    // [P0 修复] 围栏本身也要剥掉。
    // 面板常被包在 ```html 围栏里（GFM 会渲染成带语言标签的代码块 = 用户看到的"代码"）；
    // 只剥标签、留下 ``` 的话预览依然会变成代码块（反例测试实证）。
    text = text
        .replace(Regex("""^\s*```[a-zA-Z0-9_+-]*\s*$""", RegexOption.MULTILINE), "")
        .replace(Regex("""^\s*```\s*$""", RegexOption.MULTILINE), "")
    // 4 空格/TAB 缩进在 CommonMark 里也是代码块，预览里必须压平，否则同样"显示成代码"。
    text = text.lines().joinToString("\n") { line ->
        if (line.startsWith("    ") || line.startsWith("\t")) line.trimStart() else line
    }
    images.forEachIndexed { index, replacement ->
        text = text.replace("\u0001PIC$index\u0001", replacement)
            .replace("\u0001IMG$index\u0001", replacement)
    }
    return text.trim()
}

/**
 * 进程级 LRU 缓存：避免超长消息（如 218KB 的大小姐开场白）在 LazyColumn 回收重建或 Settings 发射时
 * 反复执行耗时正则替换（实测单次可达 1.1 秒）。
 *
 * 关键优化（解决在预设消息之后中间楼层发送消息时卡死与 WebView 闪退）：
 * - 发送新消息会使 `lastMessageIndex` 递增，导致历史所有楼层的 `depth = lastMessageIndex - index` 全部变化。
 * - 若当前生效的 `scripts` 没有任何脚本配置了深度过滤（`minDepth > 0` 或 `maxDepth != null`），
 *   `keyOf` 将 `effectiveDepth` 归一化为 `null`；同时对已渲染过的同一消息保留 `lastRenderedText` 作为过渡初值，
 *   彻底杜绝每次点击发送时全会话所有楼层缓存集体失效、所有内嵌 WebView 面板被瞬间销毁并二次重建引发的卡死与崩溃。
 */
internal object StRegexTextCache {
    private const val MAX_ENTRIES = 160

    data class CacheKey(
        val textHash: Int,
        val textLen: Int,
        val scripts: List<RegexScript>,
        val assistantId: String?,
        val assistantName: String?,
        val assistantRegexes: List<AssistantRegex>,
        val scope: AssistantAffectScope,
        val placement: Int,
        val depth: Int?,
        val userName: String?,
        val conversationId: String?,
        val varVersion: Long = 0L,
        val formatterVersion: Long = 0L,
    )

    private val map = java.util.LinkedHashMap<CacheKey, Pair<String, String>>(32, 0.75f, true)

    fun scriptsUseDepthFilter(scripts: List<RegexScript>, placement: Int): Boolean {
        if (scripts.isEmpty()) return false
        return scripts.any { s ->
            !s.disabled &&
                (s.placement.isEmpty() || placement in s.placement) &&
                ((s.minDepth != null && s.minDepth > 0) || s.maxDepth != null)
        }
    }

    fun keyOf(
        text: String,
        scripts: List<RegexScript>,
        assistant: Assistant?,
        scope: AssistantAffectScope,
        placement: Int,
        depth: Int? = null,
        userName: String? = null,
        conversationId: String? = null,
        varVersion: Long = TavernVariableStore.mutationVersion,
        formatterVersion: Long = TavernRuntimeManager.messageFormatterVersion,
    ): CacheKey {
        val effectiveDepth = if (depth != null && scriptsUseDepthFilter(scripts, placement)) depth else null
        return CacheKey(
            textHash = text.hashCode(),
            textLen = text.length,
            scripts = scripts,
            assistantId = assistant?.id?.toString(),
            assistantName = assistant?.tavernData?.nickname?.takeIf { it.isNotBlank() } ?: assistant?.name,
            assistantRegexes = assistant?.regexes.orEmpty(),
            scope = scope,
            placement = placement,
            depth = effectiveDepth,
            userName = userName,
            conversationId = conversationId,
            varVersion = varVersion,
            formatterVersion = formatterVersion,
        )
    }

    fun get(key: CacheKey, rawText: String): String? = synchronized(map) {
        val hit = map[key] ?: return null
        if (hit.first == rawText) hit.second else null
    }

    fun put(key: CacheKey, rawText: String, rendered: String) {
        synchronized(map) {
            map[key] = rawText to rendered
            while (map.size > MAX_ENTRIES) {
                val it = map.keys.iterator()
                if (!it.hasNext()) break
                it.next()
                it.remove()
            }
        }
    }

    fun clear() {
        synchronized(map) {
            map.clear()
        }
    }
}

private val CHAR_MACRO_REGEX = Regex("""\{\{\s*char\s*\}\}""", RegexOption.IGNORE_CASE)
private val USER_MACRO_REGEX = Regex("""\{\{\s*user\s*\}\}""", RegexOption.IGNORE_CASE)
private val GETVAR_MACRO_REGEX = Regex("""\{\{\s*getvar::([^}]+)\}\}""", RegexOption.IGNORE_CASE)
private val GETGLOBALVAR_MACRO_REGEX = Regex("""\{\{\s*getglobalvar::([^}]+)\}\}""", RegexOption.IGNORE_CASE)

private val macroDefsInitialized: Boolean by lazy {
    MacroDefinitions.registerAll()
    true
}

private class TavernVarMacroStoreAdapter(
    private val conversationId: String?,
) : MacroVariableStore {
    override fun get(name: String): String? = TavernVariableStore.macroGet(conversationId, name)
    override fun has(name: String): Boolean = TavernVariableStore.macroGet(conversationId, name) != null
    override fun set(name: String, value: String) {
        TavernVariableStore.macroSet(conversationId, name, value)
    }
    override fun del(name: String) {
        TavernVariableStore.macroDelete(conversationId, name)
    }
    override fun inc(name: String): String {
        val next = (TavernVariableStore.macroGet(conversationId, name)?.toDoubleOrNull() ?: 0.0) + 1.0
        val formatted = if (next % 1.0 == 0.0) next.toLong().toString() else next.toString()
        TavernVariableStore.macroSet(conversationId, name, formatted)
        return formatted
    }
    override fun dec(name: String): String {
        val next = (TavernVariableStore.macroGet(conversationId, name)?.toDoubleOrNull() ?: 0.0) - 1.0
        val formatted = if (next % 1.0 == 0.0) next.toLong().toString() else next.toString()
        TavernVariableStore.macroSet(conversationId, name, formatted)
        return formatted
    }
    override fun add(name: String, value: String) {
        val cur = TavernVariableStore.macroGet(conversationId, name)
        val curNum = cur?.toDoubleOrNull()
        val addNum = value.toDoubleOrNull()
        val next = if (curNum != null && addNum != null) {
            val s = curNum + addNum
            if (s % 1.0 == 0.0) s.toLong().toString() else s.toString()
        } else {
            (cur ?: "") + value
        }
        TavernVariableStore.macroSet(conversationId, name, next)
    }
    override fun getAtKey(name: String, key: String): String? =
        MacroVarKeyOps.getAtKey(TavernVariableStore.macroGet(conversationId, name), key)
    override fun setAtKey(name: String, key: String, value: String) {
        val updated = MacroVarKeyOps.setAtKey(TavernVariableStore.macroGet(conversationId, name), key, value)
        TavernVariableStore.macroSet(conversationId, name, updated)
    }
}

private fun buildMacroSubstitutor(
    charName: String,
    effectiveUser: String,
    conversationId: String?,
    assistant: Assistant? = null,
): (String) -> String = { s ->
    if (!s.contains("{{") && !s.contains("<USER>", ignoreCase = true) && !s.contains("<BOT>", ignoreCase = true) && !s.contains("<CHAR>", ignoreCase = true)) {
        s
    } else {
        var out = s
        if (charName.isNotEmpty()) {
            out = CHAR_MACRO_REGEX.replace(out, charName)
        }
        out = USER_MACRO_REGEX.replace(out, effectiveUser)
        out = GETVAR_MACRO_REGEX.replace(out) { m ->
            val varKey = m.groupValues[1].trim()
            TavernVariableStore.macroGet(conversationId, varKey).orEmpty()
        }
        out = GETGLOBALVAR_MACRO_REGEX.replace(out) { m ->
            val varKey = m.groupValues[1].trim()
            TavernVariableStore.macroGet(null, varKey).orEmpty()
        }
        if (out.contains("{{") || out.contains("<USER>", ignoreCase = true) || out.contains("<BOT>", ignoreCase = true) || out.contains("<CHAR>", ignoreCase = true)) {
            runCatching {
                @Suppress("UNUSED_EXPRESSION") macroDefsInitialized
                val dyn = LinkedHashMap<String, DynamicMacro>()
                for ((name, entry) in TavernRuntimeManager.macrosRegistry.get()) {
                    val key = name.lowercase()
                    if (key !in dyn && !MacroRegistry.hasMacro(key) && entry.first != "function") {
                        dyn[key] = DynamicMacro.Value(entry.second)
                    }
                }
                val tav = assistant?.tavernData
                val env = MacroEnv(
                    names = MacroEnv.Names(
                        user = effectiveUser,
                        char = charName.ifEmpty { "Assistant" },
                        group = charName.ifEmpty { "Assistant" },
                        groupNotMuted = charName.ifEmpty { "Assistant" },
                        notChar = effectiveUser,
                    ),
                    character = MacroEnv.CharacterInfo(
                        charPrompt = tav?.systemPrompt?.ifBlank { assistant?.systemPrompt.orEmpty() }.orEmpty(),
                        charInstruction = tav?.postHistoryInstructions.orEmpty(),
                        description = tav?.description.orEmpty(),
                        personality = tav?.personality.orEmpty(),
                        scenario = tav?.scenario.orEmpty(),
                        mesExamplesRaw = tav?.mesExample.orEmpty(),
                        version = tav?.characterVersion.orEmpty(),
                        charDepthPrompt = tav?.depthPrompt.orEmpty(),
                        creatorNotes = tav?.creatorNotes.orEmpty(),
                        firstMessage = tav?.firstMessage.orEmpty(),
                        alternateGreetings = tav?.alternateGreetings.orEmpty(),
                    ),
                    variables = VariableStores(
                        local = TavernVarMacroStoreAdapter(conversationId),
                        global = TavernVarMacroStoreAdapter(null),
                    ),
                    dynamicMacros = dyn,
                    outlets = StWorldInfoOutlets.snapshot(conversationId),
                )
                out = MacroEngine.evaluate(out, env)
            }
        }
        out
    }
}

private fun postProcessAssistantRenderedText(
    regexed: String,
    assistant: Assistant?,
    scripts: List<RegexScript>,
    scope: AssistantAffectScope,
): String {
    val trimmedRegexed = regexed.trim()
    val shouldCleanWrapper = scope == AssistantAffectScope.ASSISTANT &&
        (detectUnmatchedCardWrapperBrackets(assistant, scripts) ||
            (trimmedRegexed.startsWith('〔') && trimmedRegexed.endsWith('〕')) ||
            (trimmedRegexed.startsWith('〖') && trimmedRegexed.endsWith('〗')) ||
            ((trimmedRegexed.startsWith('〔') || trimmedRegexed.startsWith('〖') ||
                trimmedRegexed.endsWith('〕') || trimmedRegexed.endsWith('〗')) &&
                (trimmedRegexed.contains("<style", ignoreCase = true) || trimmedRegexed.contains("<div", ignoreCase = true))))
    if (shouldCleanWrapper && assistant != null && loggedWrapperDiagAssistants.add(assistant.id.toString())) {
        TavernRuntimeManager.appendLog(
            "info",
            "[render-diag] card='${assistant.name}' prompt contains unmatched 〔/〕 wrapper symbols; applied visual-only outer wrapper cleanup",
        )
    }
    val wrapperCleaned = if (shouldCleanWrapper) {
        stripOuterTemplateWrapperBrackets(regexed, enabled = true)
    } else {
        regexed
    }
    return convertUnhandledPicTagsToHtml(
        convertUnhandledDrawTagsToHtml(stripOrphanScaffoldTags(wrapperCleaned))
    )
}

/**
 * 显示级脚手架清洗：把「不是合法 HTML 标签名」的卡内中间格式标记从**可见文本**里去掉。
 *
 * 背景（真机日志 tavern-runtime-10-14-14.log:23-89 证实）：酒馆卡在回复开头会先吐
 * `<TASK_CHECK_PASS>` / `<TASK_FINAL>` 这类脚手架标记。它们是**流式中间态**，
 * 卡的显示正则是按「成对开闭标签」写的，流式期间拿不到 `</TASK_FINAL>`，天然匹配不上，
 * 于是这些尖括号串会被 GFM 当成普通文本 **转义后原样显示**（实测可见文本就是
 * `<TASK_CHECK_PASS>`），用户看到的就是「点发送后界面变成一堆代码」。
 *
 * 为什么放在这里：`postProcessAssistantRenderedText` 是 ASSISTANT 侧唯一的显示收口，
 * 同步快路 / 异步 / WebView formatter 三条路径全经过它，结果还会进 [StRegexTextCache]
 * 按 text 缓存 —— 不是每 token 重算。同目录已经在做同性质的 `<pic>` / `[出图]` 视觉转换，
 * 契约一致：**只影响显示，绝不写回存储，也不进 prompt**。
 *
 * 规则（严格白名单，避免砍到真内容）：
 * - 只删**标签标记本身**，永不删内文；
 * - 名字必须是 `<已知脚手架前缀>_<全大写尾段>`，前缀只放真机日志里实际出现过的
 *   `TASK` / `SCENE` / `INTERLEAVE` / `CTX`（见 tavern-runtime-10-14-14.log:23-89）。
 *   合法 HTML 标签名不含下划线，所以这些不可能是结构标签；
 *   而「前缀不在白名单」的 `<A_B>` 这类模型自定义泛型/占位符（反例测试证明会被误伤）保持原样。
 * - `TASK_FINAL`（只有一段后缀）与 `TASK_CHECK_PASS`（多段）都必须覆盖 ——
 *   两者都是日志里出现频率最高的脚手架标记。
 * - `scene` / `details` 等合法标签**不在此列**：MarkdownNew 会正常渲染它们，误删会毁掉卡的折叠面板。
 */
private val ORPHAN_SCAFFOLD_TAG_RE = Regex(
    """</?(?:TASK|SCENE|INTERLEAVE|INTERLEAVING|CTX)_[A-Z0-9]+(?:_[A-Z0-9]+)*\s*>""",
)

internal fun stripOrphanScaffoldTags(text: String): String {
    if (text.isEmpty() || !text.contains('_')) return text
    if (!ORPHAN_SCAFFOLD_TAG_RE.containsMatchIn(text)) return text
    return ORPHAN_SCAFFOLD_TAG_RE.replace(text, "")
}

private val UNHANDLED_PIC_RE = Regex(
    """<pic\b([^>]*)>([\s\S]*?)</pic>|<pic\b([^>]*)/>""",
    RegexOption.IGNORE_CASE,
)

/**
 * 扩展未加载或 MessageFormatter 超时时的兜底：<pic> 不能直接被 Markdown 当未知标签丢掉。
 * 有 src 就渲染真实图片；没有 src 就显示“图片生成中/失败重试”的占位卡片。
 */
private fun convertUnhandledPicTagsToHtml(input: String): String {
    if (!input.contains("<pic", ignoreCase = true)) return input
    return UNHANDLED_PIC_RE.replace(input) { match ->
        val attrs = match.groupValues[1].ifBlank { match.groupValues[3] }
        val body = match.groupValues[2].trim()
        val src = Regex("""\b(?:src|url|href)\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
            .find(attrs)?.groupValues?.getOrNull(2).orEmpty()
        if (src.isNotBlank()) {
            """<img src="${src.replace("\"", "&quot;")}" alt="pic" />"""
        } else {
            val prompt = body.ifBlank { "图片生成中…" }
                .replace("\r", " ")
                .replace("\n", " ")
                .replace("<", "＜")
                .replace(">", "＞")
                .take(160)
            "\n\n🖼 图片生成/加载中… $prompt\n（长时间未显示请重发或点击消息重试）\n\n"
        }
    }
}

/**
 * 纯 Kotlin 侧同步执行助手正则与酒馆 RegexScriptEngine（不阻塞等待 WebView IPC）：
 * 耗时通常 < 2ms，可在首帧直接提供排版后 HTML/Markdown，避免出现「正在应用显示正则与预设排版规则…」等待条。
 */
internal fun computePureKotlinRegexedText(
    rawInputText: String,
    scripts: List<RegexScript>,
    assistant: Assistant?,
    scope: AssistantAffectScope,
    placement: Int,
    depth: Int? = null,
    userName: String? = null,
    conversationId: String? = null,
): String {
    val text = if (scope == AssistantAffectScope.ASSISTANT &&
        (rawInputText.contains("<think", ignoreCase = true) || rawInputText.contains("<thought", ignoreCase = true))
    ) {
        RESIDUAL_THINK_STRIP_RE.replace(rawInputText, "").trimStart('\r', '\n')
    } else {
        rawInputText
    }
    val charName = assistant?.tavernData?.nickname?.takeIf { it.isNotBlank() }
        ?: assistant?.name?.takeIf { it.isNotBlank() }
        ?: ""
    val effectiveUser = userName?.takeIf { it.isNotBlank() } ?: "User"
    val sub = buildMacroSubstitutor(charName, effectiveUser, conversationId, assistant)
    val afterAssistantRegex = sub(text.replaceRegexes(assistant = assistant, scope = scope, visual = true))
    val regexed = RegexScriptEngine.getRegexedString(
        rawString = afterAssistantRegex,
        scripts = scripts,
        placement = placement,
        isMarkdown = true,
        isPrompt = false,
        depth = depth,
        substitute = sub,
    )
    return postProcessAssistantRenderedText(regexed, assistant, scripts, scope)
}

internal fun computeStRegexedText(
    rawInputText: String,
    scripts: List<RegexScript>,
    assistant: Assistant?,
    scope: AssistantAffectScope,
    placement: Int,
    depth: Int? = null,
    userName: String? = null,
    conversationId: String? = null,
): String {
    val text = if (scope == AssistantAffectScope.ASSISTANT &&
        (rawInputText.contains("<think", ignoreCase = true) || rawInputText.contains("<thought", ignoreCase = true))
    ) {
        RESIDUAL_THINK_STRIP_RE.replace(rawInputText, "").trimStart('\r', '\n')
    } else {
        rawInputText
    }
    val cacheKey = StRegexTextCache.keyOf(
        text = text,
        scripts = scripts,
        assistant = assistant,
        scope = scope,
        placement = placement,
        depth = depth,
        userName = userName,
        conversationId = conversationId,
    )
    StRegexTextCache.get(cacheKey, text)?.let { return it }
    val charName = assistant?.tavernData?.nickname?.takeIf { it.isNotBlank() }
        ?: assistant?.name?.takeIf { it.isNotBlank() }
        ?: ""
    val effectiveUser = userName?.takeIf { it.isNotBlank() } ?: "User"
    val sub = buildMacroSubstitutor(charName, effectiveUser, conversationId, assistant)
    val isUserMsg = (placement == 1)
    val invokeFormatter = shouldInvokeWebViewMessageFormatter(text)
    val beforeRegexText = if (invokeFormatter) {
        TavernRuntimeManager.runMessageFormatterStageBlocking("beforeRegex", text, charName, isUserMsg)
    } else {
        text
    }
    val afterAssistantRegex = sub(beforeRegexText.replaceRegexes(assistant = assistant, scope = scope, visual = true))
    val regexed = RegexScriptEngine.getRegexedString(
        rawString = afterAssistantRegex,
        scripts = scripts,
        placement = placement,
        isMarkdown = true,
        isPrompt = false,
        depth = depth,
        substitute = sub,
    )
    val afterFormatter = if (invokeFormatter || shouldInvokeWebViewMessageFormatter(regexed)) {
        TavernRuntimeManager.runMessageFormatterStageBlocking("afterRegex", regexed, charName, isUserMsg)
    } else {
        regexed
    }
    val rendered = postProcessAssistantRenderedText(afterFormatter, assistant, scripts, scope)
    StRegexTextCache.put(cacheKey, text, rendered)
    return rendered
}

/**
 * 挂起式（非阻塞）消息正则与 ST 1.19.0 MessageFormatter 管线：
 * 供 Compose `produceState`（后台 `Dispatchers.Default`）或协程直接调用，
 * 通过 `runMessageFormatterStageAsync` 挂起等待 WebView JS 钩子完成，
 * `afterMarkdown` 阶段统一在 `MessageHtmlBlock.rememberAfterMarkdownHtml` 执行，避免重复执行两次。
 */
internal suspend fun computeStRegexedTextAsync(
    text: String,
    scripts: List<RegexScript>,
    assistant: Assistant?,
    scope: AssistantAffectScope,
    placement: Int,
    depth: Int? = null,
    userName: String? = null,
    conversationId: String? = null,
): String {
    val rawInputText = text
    val cleanedText = if (scope == AssistantAffectScope.ASSISTANT &&
        (rawInputText.contains("<think", ignoreCase = true) || rawInputText.contains("<thought", ignoreCase = true))
    ) {
        RESIDUAL_THINK_STRIP_RE.replace(rawInputText, "").trimStart('\r', '\n')
    } else {
        rawInputText
    }
    val cacheKey = StRegexTextCache.keyOf(
        text = cleanedText,
        scripts = scripts,
        assistant = assistant,
        scope = scope,
        placement = placement,
        depth = depth,
        userName = userName,
        conversationId = conversationId,
    )
    StRegexTextCache.get(cacheKey, cleanedText)?.let { return it }
    val charName = assistant?.tavernData?.nickname?.takeIf { it.isNotBlank() }
        ?: assistant?.name?.takeIf { it.isNotBlank() }
        ?: ""
    val effectiveUser = userName?.takeIf { it.isNotBlank() } ?: "User"
    val sub = buildMacroSubstitutor(charName, effectiveUser, conversationId, assistant)
    val isUserMsg = (placement == 1)
    val invokeFormatter = shouldInvokeWebViewMessageFormatter(cleanedText)
    val beforeRegexText = if (invokeFormatter) {
        TavernRuntimeManager.runMessageFormatterStageAsync("beforeRegex", cleanedText, charName, isUserMsg)
    } else {
        cleanedText
    }
    val afterAssistantRegex = sub(beforeRegexText.replaceRegexes(assistant = assistant, scope = scope, visual = true))
    val regexed = RegexScriptEngine.getRegexedString(
        rawString = afterAssistantRegex,
        scripts = scripts,
        placement = placement,
        isMarkdown = true,
        isPrompt = false,
        depth = depth,
        substitute = sub,
    )
    val afterFormatter = if (invokeFormatter || shouldInvokeWebViewMessageFormatter(regexed)) {
        TavernRuntimeManager.runMessageFormatterStageAsync("afterRegex", regexed, charName, isUserMsg)
    } else {
        regexed
    }
    val rendered = postProcessAssistantRenderedText(afterFormatter, assistant, scripts, scope)
    StRegexTextCache.put(cacheKey, cleanedText, rendered)
    return rendered
}

/**
 * 显示通道非阻塞渐进式正则与 MessageFormatter 管线（带首帧同步计算加速与稳定过渡保持）：
 * - 对于常规长度（<= 50KB）消息：
 *   1. 若无需触发 WebView `MessageFormatter` 钩子（例如未包含 `<tts>` / `<pic>` / `<bgm>` 等扩展标签）：
 *      直接在首帧同步执行 Kotlin `RegexScriptEngine` 并写入缓存返回（`isComputing = false`，耗时 < 2ms），0 延迟秒出！
 *   2. 若需要触发 WebView `MessageFormatter` 钩子：首帧先同步给出 Kotlin `RegexScriptEngine` 排版结果（`isComputing = false`），
 *      同时在后台协程通过 `computeStRegexedTextAsync` 执行 WebView `MessageFormatter` 钩子并无感更新。
 * - 仅当遇到 > 50KB 的超巨型原始消息且尚未命中缓存时，才展示短暂的「正在应用显示正则与预设排版规则…」提示条。
 */
@Composable
internal fun rememberStRegexedTextWithStatus(
    text: String,
    scripts: List<RegexScript>,
    assistant: Assistant?,
    scope: AssistantAffectScope,
    placement: Int,
    depth: Int? = null,
    userName: String? = null,
    conversationId: String? = null,
): StRegexRenderResult {
    val formatterVersion by TavernRuntimeManager.messageFormatterVersionFlow.collectAsState()
    val hasFormatterHooks = TavernRuntimeManager.hasMessageFormatterHooks
    if (scripts.isEmpty() && assistant?.regexes.isNullOrEmpty() && !hasFormatterHooks &&
        !text.contains("{{") && !text.contains("〔") && !text.contains("[出图]")
    ) {
        return StRegexRenderResult(text = text, isComputing = false)
    }
    val lastRenderedHolder = remember(text) { mutableStateOf<String?>(null) }
    val varVersion = TavernVariableStore.mutationVersion
    val cacheKey = remember(
        text,
        scripts,
        assistant,
        scope,
        placement,
        depth,
        userName,
        conversationId,
        varVersion,
        formatterVersion,
        hasFormatterHooks,
    ) {
        StRegexTextCache.keyOf(
            text = text,
            scripts = scripts,
            assistant = assistant,
            scope = scope,
            placement = placement,
            depth = depth,
            userName = userName,
            conversationId = conversationId,
            varVersion = varVersion,
            formatterVersion = formatterVersion,
        )
    }
    val cached = remember(cacheKey, text) {
        StRegexTextCache.get(cacheKey, text)
    }
    if (cached != null) {
        lastRenderedHolder.value = cached
        return StRegexRenderResult(text = cached, isComputing = false)
    }

    val needsWebViewFormatter = remember(text, hasFormatterHooks, formatterVersion) {
        shouldInvokeWebViewMessageFormatter(text)
    }

    // 快速通道 1：<= 50KB 且无需等待 WebView MessageFormatter 钩子 → 首帧同步计算并返回，0ms 等待
    if (!needsWebViewFormatter && text.length <= 50_000) {
        val syncResult = remember(cacheKey, text, scripts, assistant, scope, placement, depth, userName, conversationId) {
            computeStRegexedText(text, scripts, assistant, scope, placement, depth, userName, conversationId)
        }
        lastRenderedHolder.value = syncResult
        return StRegexRenderResult(text = syncResult, isComputing = false)
    }

    // 快速通道 2：<= 50KB 但包含 <tts>/<pic> 等需 WebView MessageFormatter 处理的标签 →
    // 首帧先同步执行纯 Kotlin 正则（isComputing = false，立刻呈现排版界面），后台异步追加 MessageFormatter 钩子结果
    val initialPreview = remember(cacheKey, text) {
        StRegexTextCache.get(cacheKey, text)?.let {
            lastRenderedHolder.value = it
            StRegexRenderResult(text = it, isComputing = false)
        } ?: lastRenderedHolder.value?.let { prev ->
            StRegexRenderResult(text = prev, isComputing = false)
        } ?: if (text.length <= 50_000) {
            val fastKotlin = computePureKotlinRegexedText(
                rawInputText = text,
                scripts = scripts,
                assistant = assistant,
                scope = scope,
                placement = placement,
                depth = depth,
                userName = userName,
                conversationId = conversationId,
            )
            lastRenderedHolder.value = fastKotlin
            StRegexRenderResult(text = fastKotlin, isComputing = false)
        } else {
            StRegexRenderResult(
                text = extractQuickPlainPreview(text).ifBlank { text.take(1000) },
                isComputing = true,
            )
        }
    }
    val asyncResult by produceState(
        initialValue = initialPreview,
        cacheKey,
        text,
    ) {
        val hit = StRegexTextCache.get(cacheKey, text)
        value = if (hit != null) {
            lastRenderedHolder.value = hit
            StRegexRenderResult(text = hit, isComputing = false)
        } else {
            val computed = withContext(Dispatchers.Default) {
                computeStRegexedTextAsync(
                    text = text,
                    scripts = scripts,
                    assistant = assistant,
                    scope = scope,
                    placement = placement,
                    depth = depth,
                    userName = userName,
                    conversationId = conversationId,
                )
            }
            lastRenderedHolder.value = computed
            StRegexRenderResult(text = computed, isComputing = false)
        }
    }
    return asyncResult
}

@Composable
internal fun rememberStRegexedText(
    text: String,
    scripts: List<RegexScript>,
    assistant: Assistant?,
    scope: AssistantAffectScope,
    placement: Int,
    depth: Int? = null,
    userName: String? = null,
    conversationId: String? = null,
): String = rememberStRegexedTextWithStatus(
    text = text,
    scripts = scripts,
    assistant = assistant,
    scope = scope,
    placement = placement,
    depth = depth,
    userName = userName,
    conversationId = conversationId,
).text
