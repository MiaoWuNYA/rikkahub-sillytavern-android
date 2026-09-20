package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.TITLE_PROMPT_REVISION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TitlePromptMigrationTest {

    /**
     * 迁移的整个价值就在这个字符串上：它必须和旧版本发布时用的默认提示词逐字节一致。
     * 这个测试把那份旧默认值钉死，将来谁改了它就会红——而不是让老用户静默地一直用
     * 会「数字数」的旧提示词。
     */
    private val V1_DEFAULT = """
        I will give you some dialogue content in the `<content>` block.
        You need to summarize the conversation between user and assistant into a short title.
        1. The title language should be consistent with the user's primary language
        2. Do not use punctuation or other special symbols
        3. Reply directly with the title
        4. Summarize using {locale} language
        5. The title should not exceed 10 characters

        <content>
        {content}
        </content>
    """.trimIndent()

    @Test
    fun `untouched v1 prompt is upgraded to the new default`() {
        assertEquals(
            DEFAULT_TITLE_PROMPT,
            Settings.resolveTitlePrompt(stored = V1_DEFAULT, storedRevision = null),
        )
    }

    @Test
    fun `an old revision marker that still holds the v1 text is upgraded`() {
        assertEquals(
            DEFAULT_TITLE_PROMPT,
            Settings.resolveTitlePrompt(stored = V1_DEFAULT, storedRevision = 0),
        )
    }

    @Test
    fun `already current prompt is left alone`() {
        assertEquals(
            DEFAULT_TITLE_PROMPT,
            Settings.resolveTitlePrompt(
                stored = DEFAULT_TITLE_PROMPT,
                storedRevision = TITLE_PROMPT_REVISION,
            ),
        )
    }

    @Test
    fun `user edited prompt is never overwritten`() {
        val custom = "用五个字给这段对话起个标题，只要标题本身。"
        assertEquals(
            custom,
            Settings.resolveTitlePrompt(stored = custom, storedRevision = null),
        )
    }

    @Test
    fun `missing prompt falls back to the default`() {
        assertEquals(DEFAULT_TITLE_PROMPT, Settings.resolveTitlePrompt(stored = null, storedRevision = null))
    }

    @Test
    fun `new default no longer tells the model to count characters`() {
        assertNotEquals(V1_DEFAULT, DEFAULT_TITLE_PROMPT)
        // 旧提示词的病根是「不超过 N 字」这种需要逐字数数的表述。
        assertEquals(false, DEFAULT_TITLE_PROMPT.contains("should not exceed"))
    }
}
