package me.rerere.rikkahub.data.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 保留未启用条目之后，必须确认它们不会被注入。
 *
 * 这是 issue #5 修复的安全边界：把「丢弃」改成「保留为禁用」的前提，
 * 是注入层确实尊重 enabled。否则修完 issue 会引入更糟的问题——
 * 用户明确关掉的条目会被偷偷塞进每一次请求。
 */
class PresetDisabledNotInjectedTest {

    private val source: String by lazy {
        listOf(
            File("src/main/java/me/rerere/rikkahub/data/ai/transformers/PromptInjectionTransformer.kt"),
            File("app/src/main/java/me/rerere/rikkahub/data/ai/transformers/PromptInjectionTransformer.kt"),
        ).firstOrNull { it.exists() }?.readText().orEmpty()
    }

    @Test
    fun `injection loop skips disabled entries`() {
        assertTrue("找不到 PromptInjectionTransformer.kt", source.isNotEmpty())
        assertTrue(
            "注入循环必须在关键词匹配前跳过 !enabled 的条目，" +
                "否则「保留禁用条目」会变成「禁用条目也被注入」",
            source.contains("if (!entry.enabled) continue"),
        )
    }

    @Test
    fun `entry filtering also respects enabled`() {
        assertTrue(
            "世界书条目在进入匹配前也要按 enabled 过滤",
            source.contains(".filter { it.enabled && effectiveLorebookIds.contains(it.id) }") ||
                source.contains("it.enabled && effectiveModeInjectionIds.contains(it.id)"),
        )
    }

    @Test
    fun `disabled entries from a real preset would not pass the enabled gate`() {
        // 端到端：解析出的禁用条目，enabled 必须是 false，才可能被上面两处过滤掉
        val json = """
        {
          "prompts": [
            { "identifier": "style_a", "name": "正式版", "content": "正式版内容。", "role": "system" },
            { "identifier": "style_b", "name": "备用版", "content": "备用版内容。", "role": "system" }
          ],
          "prompt_order": [ { "character_id": 100001, "order": [
            { "identifier": "style_a", "enabled": true },
            { "identifier": "style_b", "enabled": false }
          ] } ]
        }
        """.trimIndent()
        val entries = LorebookSerializer.tryImportPresetOrdered(json)
        assertTrue("应解析出两条", entries != null && entries.size == 2)
        val backup = entries!!.first { it.name == "备用版" }
        assertFalse("被关掉的条目 enabled 必须为 false，才会被注入层跳过", backup.enabled)
        val main = entries.first { it.name == "正式版" }
        assertTrue("勾选的条目仍要注入", main.enabled)
    }
}
