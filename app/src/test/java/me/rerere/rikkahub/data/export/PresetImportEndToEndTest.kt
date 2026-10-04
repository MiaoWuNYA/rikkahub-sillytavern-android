package me.rerere.rikkahub.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * issue #5 端到端回归：拿真实格式的酒馆预设跑完整解析，验证未启用条目被保留。
 *
 * 场景来自 issue 正文：预设里有一组「多选一」的同位条目，用户只勾了其中一条，
 * 其余是关着的。导入后关掉的那些必须还在（只是禁用），不能消失。
 * 条目名用中性的测试数据，与具体预设内容无关——这里验证的是 enabled 状态
 * 是否被透传，而不是任何特定条目。
 */
class PresetImportEndToEndTest {

    private val presetJson: String = """
    {
      "name": "测试预设",
      "prompts": [
        { "identifier": "main", "name": "主要提示", "content": "你是{{char}}。", "role": "system" },
        { "identifier": "worldInfoBefore", "name": "世界书-前", "marker": true },
        { "identifier": "chatHistory", "name": "聊天历史", "marker": true },
        { "identifier": "style_a", "name": "风格-正式版", "content": "正式版的写作风格。", "role": "system" },
        { "identifier": "style_b", "name": "风格-备用版", "content": "备用版的写作风格。", "role": "system" },
        { "identifier": "extra", "name": "附加设定", "content": "一些附加设定。", "role": "system" },
        { "identifier": "custom_deep", "name": "深度注入", "content": "深度内容。", "role": "user", "injection_position": 0, "injection_depth": 2 }
      ],
      "prompt_order": [
        {
          "character_id": 100001,
          "order": [
            { "identifier": "main", "enabled": true },
            { "identifier": "worldInfoBefore", "enabled": true },
            { "identifier": "chatHistory", "enabled": true },
            { "identifier": "style_a", "enabled": true },
            { "identifier": "style_b", "enabled": false },
            { "identifier": "extra", "enabled": false },
            { "identifier": "custom_deep", "enabled": true }
          ]
        }
      ]
    }
    """.trimIndent()

    @Test
    fun `disabled entries survive import and keep their state`() {
        val entries = LorebookSerializer.tryImportPresetOrdered(presetJson)
        assertNotNull("预设应能解析", entries)
        val list = entries!!

        val names = list.map { it.name }
        // marker 条目（worldInfoBefore / chatHistory）没有内容、本就不该成为注入条目
        assertTrue("勾选的条目应在", names.any { it == "风格-正式版" })
        assertTrue(
            "★ issue #5：被关掉的同位条目被丢弃了，用户在界面上再也找不回来",
            names.any { it == "风格-备用版" },
        )
        assertTrue("★ issue #5：被关掉的其它条目被丢弃了", names.any { it == "附加设定" })

        val enabledNames = list.filter { it.enabled }.map { it.name }
        val disabledNames = list.filter { !it.enabled }.map { it.name }

        assertEquals("启用的应恰好是这几条", setOf("主要提示", "风格-正式版", "深度注入"), enabledNames.toSet())
        assertEquals(
            "被预设关掉的条目应导入为禁用状态",
            setOf("风格-备用版", "附加设定"),
            disabledNames.toSet(),
        )
    }

    @Test
    fun `missing enabled field is treated as enabled`() {
        // 酒馆语义：order 项不写 enabled 视为启用
        val json = """
        {
          "prompts": [ { "identifier": "main", "name": "主要提示", "content": "内容", "role": "system" } ],
          "prompt_order": [ { "character_id": 100001, "order": [ { "identifier": "main" } ] } ]
        }
        """.trimIndent()
        val entries = LorebookSerializer.tryImportPresetOrdered(json)
        assertNotNull(entries)
        assertEquals(1, entries!!.size)
        assertTrue(
            "缺 enabled 字段的条目应视为启用，否则正常预设会被整条判为禁用",
            entries[0].enabled,
        )
    }

    @Test
    fun `real preset file if present`() {
        val f = File("/tmp/stpreset/preset.json")
        if (!f.exists()) return
        val entries = LorebookSerializer.tryImportPresetOrdered(f.readText())
        assertNotNull(entries)
        val names = entries!!.map { it.name }
        println("导入条目: $names")
        assertTrue("关掉的条目必须保留", names.size >= 5)
        assertTrue("应至少有一条禁用条目", entries.any { !it.enabled })
    }
}
