package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.export.SillyTavernRegexImporter
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 卡片正则替换的回归测试。
 *
 * 一张 iPhone UI 卡把 alternate_greetings 做成 <mainbody>…</phone> 骨架，
 * 靠 extensions.regex_scripts 的「背景now」替换成 157KB 完整 HTML 文档。
 * 这条替换此前静默失败，标签原样显示成纯文本。两个串联的缺陷：
 *
 *   1. Kotlin 的 Regex.replace 把替换串里的 ${i + 4} 当组引用，
 *      抛 IllegalArgumentException: Illegal group reference。
 *      那是 JS 模板字符串（该替换串里有 110 处），不是组引用。
 *   2. 加了降级路径后仍然失败，因为降级实现里的
 *      m.groups[name] 对不存在的名字同样抛 IllegalArgumentException
 *      （No group with name <i + 4>），而不是返回 null。
 *
 * 两处异常都被 catch 吞掉后返回原文，所以现象是「正则解析成功、
 * 也能匹配到，但什么都没替换」。官方酒馆跑在 JS 引擎上，
 * String.replace 对不存在的组替换为空串，因此同一张卡在酒馆里正常。
 */
class CardRegexRealCardTest {

    private val cardFile = File("/tmp/card.json")

    private fun loadCard(): Pair<List<AssistantRegex>, List<String>>? {
        if (!cardFile.exists()) return null
        val root = kotlinx.serialization.json.Json.parseToJsonElement(cardFile.readText())
            .let { it as kotlinx.serialization.json.JsonObject }
        val data = root["data"]!!.let { it as kotlinx.serialization.json.JsonObject }
        val ext = data["extensions"]!!.let { it as kotlinx.serialization.json.JsonObject }
        val regexes = SillyTavernRegexImporter.parse(ext["regex_scripts"].toString())
        val greetings = data["alternate_greetings"].toString()
            .let { kotlinx.serialization.json.Json.parseToJsonElement(it) as kotlinx.serialization.json.JsonArray }
            .map { (it as kotlinx.serialization.json.JsonPrimitive).content }
        return regexes to greetings
    }

    @Test
    fun `alternate greetings are expanded into full html documents`() {
        val (regexes, greetings) = loadCard() ?: return
        val assistant = Assistant(name = "card", regexes = regexes)
        val report = StringBuilder()
        var expanded = 0
        greetings.forEachIndexed { i, g ->
            val out = g.replaceRegexes(assistant, AssistantAffectScope.ASSISTANT, visual = true)
            val ok = out.contains("<html") && out.contains("<style")
            if (ok) expanded++
            report.append("  [$i] ${g.length} → ${out.length}  ${if (ok) "已展开" else "未展开"}\n")
        }
        assertTrue(
            "全部 ${greetings.size} 条备用开场白都应展开成完整 HTML 文档，实际 $expanded 条\n$report",
            expanded == greetings.size,
        )
    }

    /**
     * 展开结果里出现原始文本是**设计如此**，不能断言它必须消失。
     *
     * 替换串的末尾是：
     *     window.updateContent(`
     *     $2
     *     `);
     * 也就是把捕获组 2（原始开场白）作为数据注入 JS 模板字符串，
     * 由卡片自己的脚本在运行时填进界面。所以骨架标签确实会出现在
     * 输出里——它在 <script> 内部，不会被当成可见文本渲染。
     *
     * 真正要保证的是：文档外壳完整、内容被注入到 script 里，
     * 而不是停留在「整段骨架原样显示」的状态。
     */
    @Test
    fun `skeleton is injected into the script rather than rendered as text`() {
        val (regexes, greetings) = loadCard() ?: return
        val assistant = Assistant(name = "card", regexes = regexes)
        val out = greetings[0].replaceRegexes(assistant, AssistantAffectScope.ASSISTANT, visual = true)
        val scriptStart = out.indexOf("<script")
        assertTrue("应生成完整文档外壳（含 <script>）", scriptStart > 0)
        val injected = out.indexOf("updateContent")
        assertTrue("原始内容应被注入到脚本里", injected > scriptStart)
        // 注入点在 script 内、且位于 </body> 之前，属运行期数据而非可见文本
        assertTrue("内容注入应发生在文档闭合之前", injected < out.indexOf("</body>"))
    }

    @Test
    fun `replacement containing js template literals is applied not skipped`() {
        val (regexes, greetings) = loadCard() ?: return
        val assistant = Assistant(name = "card", regexes = regexes)
        val out = greetings[0].replaceRegexes(assistant, AssistantAffectScope.ASSISTANT, visual = true)
        assertTrue(
            "替换必须真的执行（输出应远大于骨架），实际 ${greetings[0].length} → ${out.length}",
            out.length > greetings[0].length * 10,
        )
    }

    @Test
    fun `prompt layer does not get the visual only expansion`() {
        val (regexes, greetings) = loadCard() ?: return
        val assistant = Assistant(name = "card", regexes = regexes)
        val prompt = greetings[0].replaceRegexes(assistant, AssistantAffectScope.ASSISTANT, visual = false)
        assertTrue("显示层专用脚本不应作用于提示词层", !prompt.contains("<html"))
    }
}
