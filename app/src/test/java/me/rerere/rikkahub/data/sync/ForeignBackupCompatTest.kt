package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 上游 rikkahub 备份里本地没有的类型必须被丢掉，否则整份 settings.json / 整个会话都解不出来。
 */
class ForeignBackupCompatTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `drops settings providers the fork cannot read`() {
        val settingsJson = """
            {
              "ttsProviders": [
                {"type": "system", "id": "$FORK_TTS_ID", "name": "System"},
                {"type": "deepseek", "id": "$UPSTREAM_TTS_ID", "name": "DeepSeek TTS"},
                {"type": "volcengine", "id": "$UPSTREAM_VOLC_ID", "name": "Volcengine"}
              ],
              "asrProviders": [
                {"type": "dashscope", "id": "$FORK_ASR_ID", "name": "DashScope"},
                {"type": "deepseek", "id": "$UPSTREAM_ASR_ID", "name": "DeepSeek ASR"}
              ],
              "assistants": []
            }
        """.trimIndent()

        val sanitized = ForeignBackupCompat.sanitizeSettingsJson(settingsJson, json)
        val settings = json.decodeFromString<Settings>(sanitized)
        assertEquals(1, settings.ttsProviders.size)
        assertEquals(1, settings.asrProviders.size)
        assertTrue(sanitized.contains("$FORK_TTS_ID"))
    }

    @Test
    fun `drops message parts the fork cannot read`() {
        val messageJson = """
            [
              {
                "id": "$MESSAGE_ID",
                "role": "assistant",
                "parts": [
                  {"type": "text", "text": "hello"},
                  {"type": "notice", "title": "done", "text": "<notice>done</notice>"}
                ],
                "annotations": []
              }
            ]
        """.trimIndent()

        val sanitized = ForeignBackupCompat.sanitizeMessagesJson(messageJson, json)
        val messages = json.decodeFromString<List<UIMessage>>(sanitized!!)
        assertEquals(1, messages.size)
        assertEquals(listOf("hello"), messages.single().parts.filterIsInstance<UIMessagePart.Text>().map { it.text })
    }

    @Test
    fun `tolerant decode still reads messages with unknown parts`() {
        val messageJson = """
            [
              {
                "id": "$MESSAGE_ID",
                "role": "assistant",
                "parts": [{"type": "notice", "title": "done", "text": "x"}],
                "annotations": []
              },
              {
                "id": "$MESSAGE_ID_2",
                "role": "user",
                "parts": [{"type": "text", "text": "hi"}],
                "annotations": []
              }
            ]
        """.trimIndent()

        val messages = ForeignBackupCompat.decodeMessages(messageJson, json)
        assertEquals(2, messages.size)
        assertTrue(messages.all { it.parts.all { part -> part !is UIMessagePart.Text || part.text != "x" } })
    }

    @Test
    fun `clean messages are left untouched`() {
        val messageJson = """[{\"id\": \"$MESSAGE_ID\", \"role\": \"user\", \"parts\": [{\"type\": \"text\", \"text\": \"hi\"}], \"annotations\": []}]"""
        assertEquals(null, ForeignBackupCompat.sanitizeMessagesJson(messageJson, json))
    }

    private companion object {
        val FORK_TTS_ID = Uuid.random()
        val FORK_ASR_ID = Uuid.random()
        val UPSTREAM_TTS_ID = Uuid.random()
        val UPSTREAM_VOLC_ID = Uuid.random()
        val UPSTREAM_ASR_ID = Uuid.random()
        val MESSAGE_ID = Uuid.random()
        val MESSAGE_ID_2 = Uuid.random()
    }
}
