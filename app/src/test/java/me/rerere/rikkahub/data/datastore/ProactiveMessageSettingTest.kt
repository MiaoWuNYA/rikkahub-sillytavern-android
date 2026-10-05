package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主动消息配置的归一化与兼容性。
 *
 * 这些断言对应一组「设置了但没反应」的真实现象：
 *  - 间隔下限被当成 0/负数 → 闹钟连环触发
 *  - max < min → 随机区间反向，nextInt 抛异常，整个调度静默失败
 *  - 老配置没有 conversationId 字段 → 反序列化失败会让整份设置回退默认值
 */
class ProactiveMessageSettingTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `zero or negative intervals are clamped to at least one`() {
        val s = ProactiveMessageSetting(minIntervalMinutes = 0, maxIntervalMinutes = -5).normalized()
        assertEquals(ProactiveMessageSetting.MIN_INTERVAL_MINUTES, s.minIntervalMinutes)
        assertTrue("max 必须 >= min", s.maxIntervalMinutes >= s.minIntervalMinutes)
    }

    @Test
    fun `min greater than max is swapped instead of producing an empty range`() {
        // 不交换的话 random.nextInt(min, max+1) 会抛 IllegalArgumentException，
        // 调度直接失败——用户看到的就是「开了但永远不发」
        val s = ProactiveMessageSetting(minIntervalMinutes = 90, maxIntervalMinutes = 30).normalized()
        assertEquals(30, s.minIntervalMinutes)
        assertEquals(90, s.maxIntervalMinutes)
    }

    @Test
    fun `absurdly large intervals are capped`() {
        val s = ProactiveMessageSetting(
            minIntervalMinutes = Int.MAX_VALUE,
            maxIntervalMinutes = Int.MAX_VALUE,
        ).normalized()
        assertTrue(s.minIntervalMinutes <= ProactiveMessageSetting.MAX_INTERVAL_MINUTES)
        assertTrue(s.maxIntervalMinutes <= ProactiveMessageSetting.MAX_INTERVAL_MINUTES)
    }

    @Test
    fun `one minute is a legal interval`() {
        // 用户明确要求下限是 1
        val s = ProactiveMessageSetting(minIntervalMinutes = 1, maxIntervalMinutes = 1).normalized()
        assertEquals(1, s.minIntervalMinutes)
        assertEquals(1, s.maxIntervalMinutes)
    }

    @Test
    fun `normalization is idempotent`() {
        val once = ProactiveMessageSetting(minIntervalMinutes = 0, maxIntervalMinutes = 0).normalized()
        assertEquals(once, once.normalized())
    }

    @Test
    fun `legacy config without conversationId still decodes`() {
        // 老版本存下来的 JSON 没有 conversationId。字段必须带默认值，
        // 否则解析抛异常 → 整份 Settings 回退默认 → 用户所有设置被重置。
        val legacy = """{"enabled":true,"minIntervalMinutes":30,"maxIntervalMinutes":90,
            "assistantId":"","allowForceJump":false,"jumpIdleThresholdMinutes":120}"""
        val decoded = json.decodeFromString<ProactiveMessageSetting>(legacy)
        assertTrue(decoded.enabled)
        assertEquals("", decoded.conversationId)
        assertEquals(30, decoded.minIntervalMinutes)
    }

    @Test
    fun `conversationId survives a round trip`() {
        val original = ProactiveMessageSetting(
            enabled = true,
            conversationId = "01924f3a-0000-7000-8000-000000000000",
        )
        val decoded = json.decodeFromString<ProactiveMessageSetting>(
            json.encodeToString(original)
        )
        assertEquals(original.conversationId, decoded.conversationId)
    }

    @Test
    fun `defaults keep the feature off and the range sane`() {
        val d = ProactiveMessageSetting()
        assertFalse("默认必须是关的，不能替用户打开", d.enabled)
        assertTrue(d.minIntervalMinutes >= ProactiveMessageSetting.MIN_INTERVAL_MINUTES)
        assertTrue(d.maxIntervalMinutes >= d.minIntervalMinutes)
    }
}
