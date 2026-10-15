package me.rerere.rikkahub.data.export

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.allRegexesOf
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.RegexGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 酒馆预设导入时把 extensions.regex_scripts 收进全局正则的回归。
 *
 * 预设里的脚本以前会被静默丢掉，现在导入预设应同时落到 Settings.globalRegexes，
 * 并对所有角色卡生效（排在角色卡自己的正则之后）。
 */
class PresetRegexImportTest {
    private val preset = """
    {
      "prompts": [{"identifier":"main","name":"Main","content":"main","role":"system"}],
      "extensions": {
        "regex_scripts": [
          {"id":"r1","scriptName":"状态栏","findRegex":"/<StatusPlaceHolderImpl\\/>/gsi","replaceString":"html","placement":[2],"markdownOnly":true,"promptOnly":false,"disabled":false},
          {"id":"r2","scriptName":"隐藏","findRegex":"/<StatusPlaceHolderImpl\\/>/gsi","replaceString":"","placement":[2],"markdownOnly":false,"promptOnly":true,"disabled":false}
        ]
      }
    }
    """.trimIndent()

    @Test
    fun `regex scripts are extracted from preset extensions`() {
        val regexes = SillyTavernRegexImporter.parseFromPreset(preset)
        assertEquals(2, regexes.size)
        assertTrue(regexes.any { it.name == "状态栏" && it.visualOnly })
        assertTrue(regexes.any { it.name == "隐藏" && it.promptOnly })
    }

    @Test
    fun `preset without extensions yields empty list`() {
        assertTrue(SillyTavernRegexImporter.parseFromPreset("""{"prompts":[]}""").isEmpty())
    }

    @Test
    fun `allRegexesOf puts assistant regexes before global ones`() {
        val assistantRegex = AssistantRegex(id = Uuid.random())
        val globalRegex = AssistantRegex(id = Uuid.random())
        val assistant = Assistant(id = Uuid.random(), regexes = listOf(assistantRegex))
        val settings = Settings(regexGroups = listOf(RegexGroup(regexes = listOf(globalRegex))))

        assertEquals(listOf(assistantRegex, globalRegex), settings.allRegexesOf(assistant))
        assertEquals(listOf(globalRegex), settings.allRegexesOf(null))
    }
}
