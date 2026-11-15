package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 老配置里的工具序列名必须一直读得出来：读不出会让整份设置解码失败，启动时进程直接退出，界面上没有任何报错。
 * 手机控制改名 Phone Use 时就是因为丢掉了 phone_control 这个序列名而闪退。
 */
class LegacyToolOptionDecodeTest {
    private val sample = Settings.dummy().assistants.first()
        .copy(localTools = listOf(LocalToolOption.PhoneUse, LocalToolOption.TimeInfo))

    @Test
    fun `phone_control 旧序列名读出后迁移到 PhoneUse`() {
        val stored = JsonInstant.encodeToString(listOf(sample))
            .replace("\"phone_use\"", "\"phone_control\"")

        val options = decodeAssistants(stored).first().localTools

        assertTrue(LocalToolOption.PhoneUse in options)
        assertTrue(LocalToolOption.TimeInfo in options)
        assertTrue(LocalToolOption.PhoneControlLegacy !in options)
    }

    @Test
    fun `认不出的序列名只丢这一个开关，其余设置照常读出`() {
        val stored = JsonInstant.encodeToString(listOf(sample))
            .replace("\"phone_use\"", "\"phone_from_the_future\"")

        val options = decodeAssistants(stored).first().localTools

        assertTrue(LocalToolOption.TimeInfo in options)
        assertTrue(LocalToolOption.PhoneUse !in options)
    }

    @Test
    fun `整个设置对象也能带旧序列名读出`() {
        val stored = JsonInstant.encodeToString(Settings.dummy())
            .replace("\"phone_use\"", "\"phone_control\"")

        val decoded = JsonInstant.decodeFromString<Settings>(stored).normalized()

        assertTrue(decoded.assistants.isNotEmpty())
    }
}
