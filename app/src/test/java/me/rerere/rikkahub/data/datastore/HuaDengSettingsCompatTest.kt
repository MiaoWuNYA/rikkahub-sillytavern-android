package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 华灯/酒馆新增字段的兼容性：老 JSON 缺字段必须回落默认值，新 JSON 必须 round-trip 无损。
 * HuaDengSettings 整类作为一个 JSON blob 落盘（SettingsFields.kt 的 jsonSafe），所以这里直接
 * 按类做序列化，不经过 Settings 顶层。
 */
class HuaDengSettingsCompatTest {
    private val json = JsonInstant

    private fun decodeHuaDeng(raw: String) =
        json.decodeFromString(HuaDengSettings.serializer(), raw)

    @Test
    fun legacyJsonWithoutNewFieldsFallsBackToDefaults() {
        // 老配置：有既有字段、没有任何新增字段
        val settings = decodeHuaDeng("""{"enableTavernMode":true,"loadedPresetName":"老预设"}""")

        assertEquals("[]", settings.tavernGlobalScripts)
        assertEquals(true, settings.tavernGlobalScriptsEnabled)
        assertEquals("{}", settings.tavernPresetScripts)
        assertEquals(emptySet<String>(), settings.tavernThirdPartyDisabled)
        assertEquals("{}", settings.tavernExtensionSettings)
        // 既有字段不受影响
        assertEquals(true, settings.enableTavernMode)
        assertEquals("老预设", settings.loadedPresetName)
    }

    @Test
    fun newJsonRoundTripsNewFields() {
        val source = HuaDengSettings(
            tavernGlobalScripts = """[{"id":"g1","name":"全局"}]""",
            tavernGlobalScriptsEnabled = false,
            tavernPresetScripts = """{"叶.yml":[{"id":"p1"}]}""",
            tavernThirdPartyDisabled = setOf("ext-a", "ext-b"),
            tavernExtensionSettings = """{"ext-a":{"enabled":false}}""",
        )

        val restored = decodeHuaDeng(json.encodeToString(HuaDengSettings.serializer(), source))

        assertEquals(source.tavernGlobalScripts, restored.tavernGlobalScripts)
        assertEquals(false, restored.tavernGlobalScriptsEnabled)
        assertEquals(source.tavernPresetScripts, restored.tavernPresetScripts)
        assertEquals(setOf("ext-a", "ext-b"), restored.tavernThirdPartyDisabled)
        assertEquals(source.tavernExtensionSettings, restored.tavernExtensionSettings)
    }

    @Test
    fun legacyModeInjectionWithoutPresetGroupDecodesToNull() {
        val injection = json.decodeFromString(
            PromptInjection.ModeInjection.serializer(),
            """{"name":"老注入","content":"hi"}""",
        )
        assertNull(injection.presetGroup)
        assertEquals("老注入", injection.name)
    }

    @Test
    fun modeInjectionPresetGroupRoundTrips() {
        val source = PromptInjection.ModeInjection(name = "新注入", presetGroup = "叶.yml")
        val restored = json.decodeFromString(
            PromptInjection.ModeInjection.serializer(),
            json.encodeToString(PromptInjection.ModeInjection.serializer(), source),
        )
        assertEquals("叶.yml", restored.presetGroup)
        assertEquals(source.id, restored.id)
    }
}
