package me.rerere.rikkahub.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetImportDisabledContentTest {
    private val presetJson = """
    {
      "prompts": [
        {"identifier":"main","name":"Main","content":"main content","role":"system"},
        {"identifier":"unused_with_content","name":"Unused","content":"hidden lore","role":"system"},
        {"identifier":"blank_disabled","name":"BlankDisabled","content":"","role":"system"},
        {"identifier":"array_off","name":"ArrayOff","content":"array disabled","enabled":false,"role":"system"},
        {"identifier":"active","name":"Active","content":"active content","role":"system"}
      ],
      "prompt_order": [
        {
          "character_id": 100001,
          "order": [
            {"identifier":"main","enabled":true},
            {"identifier":"unused_with_content","enabled":false},
            {"identifier":"blank_disabled","enabled":false},
            {"identifier":"array_off","enabled":true},
            {"identifier":"active","enabled":true}
          ]
        }
      ]
    }
    """.trimIndent()

    @Test
    fun `disabled entry with content is preserved`() {
        val entries = LorebookSerializer.tryImportPresetOrdered(presetJson)!!
        val unused = entries.first { it.name == "Unused" }
        assertFalse(unused.enabled)
        assertEquals("hidden lore", unused.content)
    }

    @Test
    fun `disabled entry with blank content is preserved`() {
        val entries = LorebookSerializer.tryImportPresetOrdered(presetJson)!!
        val blank = entries.first { it.name == "BlankDisabled" }
        assertFalse(blank.enabled)
    }

    @Test
    fun `enabled flag on prompts array also disables import`() {
        val entries = LorebookSerializer.tryImportPresetOrdered(presetJson)!!
        val off = entries.first { it.name == "ArrayOff" }
        assertFalse(off.enabled)
        assertEquals("array disabled", off.content)
    }

    @Test
    fun `active entries still import enabled`() {
        val entries = LorebookSerializer.tryImportPresetOrdered(presetJson)!!
        assertTrue(entries.first { it.name == "Main" }.enabled)
        assertTrue(entries.first { it.name == "Active" }.enabled)
    }

    @Test
    fun `prompts missing from prompt_order are still imported as disabled`() {
        val json = """
        {
          "prompts": [
            {"identifier":"main","name":"Main","content":"main content","role":"system"},
            {"identifier":"orphan_off","name":"OrphanOff","content":"orphan lore","enabled":false,"role":"system"},
            {"identifier":"orphan_on","name":"OrphanOn","content":"orphan active","role":"system"},
            {"identifier":"orphan_blank","name":"OrphanBlank","content":"","enabled":false,"role":"system"}
          ],
          "prompt_order": [
            {"character_id": 100001, "order": [{"identifier":"main","enabled":true}]}
          ]
        }
        """.trimIndent()
        val entries = LorebookSerializer.tryImportPresetOrdered(json)!!
        // 不在顺序里的条目（酒馆"未加入顺序"区）必须保留，否则二创预设的大批备选条目整批消失
        assertEquals(4, entries.size)
        assertFalse(entries.first { it.name == "OrphanOff" }.enabled)
        assertTrue(entries.first { it.name == "OrphanOn" }.enabled)
        assertFalse(entries.first { it.name == "OrphanBlank" }.enabled)
    }
}