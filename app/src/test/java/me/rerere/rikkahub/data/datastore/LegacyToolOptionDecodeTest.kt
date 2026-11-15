package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.withNewLocalTools
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 老配置里的工具序列名必须一直读得出来：读不出会在启动时静默退出，界面上看不到任何报错。
 * 手机控制改名 Phone Use 时就是因为丢掉了 phone_control 这个序列名而闪退。
 */
class LegacyToolOptionDecodeTest {
    @Test
    fun `旧序列名仍能读出，并迁移到新选项`() {
        val assistant = Settings.dummy().assistants.first()
            .copy(localTools = listOf(LocalToolOption.PhoneUse, LocalToolOption.TimeInfo))
        val stored = JsonInstant.encodeToString(listOf(assistant))
            .replace("\"phone_use\"", "\"phone_control\"")

        val options = JsonInstant.decodeFromString<List<Assistant>>(stored)
            .map { it.withNewLocalTools() }
            .first().localTools

        assertTrue(LocalToolOption.PhoneUse in options)
        assertTrue(LocalToolOption.TimeInfo in options)
        assertTrue(LocalToolOption.PhoneControlLegacy !in options)
    }

    @Test
    fun `整个设置对象也能带旧序列名读出`() {
        val stored = JsonInstant.encodeToString(Settings.dummy())
            .replace("\"phone_use\"", "\"phone_control\"")
        val decoded = JsonInstant.decodeFromString<Settings>(stored).normalized()
        assertTrue(decoded.assistants.isNotEmpty())
    }
}
