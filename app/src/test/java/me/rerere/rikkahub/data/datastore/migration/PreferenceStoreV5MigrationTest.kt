package me.rerere.rikkahub.data.datastore.migration

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceStoreV5MigrationTest {

    private fun lorebook(id: String, isCharacterBook: Boolean): JsonObject = JsonInstant.parseToJsonElement(
        """{"id":"$id","name":"book","enabled":true,"entries":[],"isCharacterBook":$isCharacterBook}"""
    ).jsonObject

    private fun assistant(id: String, lorebookIds: List<String>): JsonObject = JsonInstant.parseToJsonElement(
        """{"id":"$id","name":"a","lorebookIds":${lorebookIds.joinToString(",", "[", "]") { "\"$it\"" }}}"""
    ).jsonObject

    private fun encode(vararg elements: JsonObject): String =
        JsonInstant.encodeToString(JsonArray(elements.toList()))

    @Test
    fun `character book ids are detected`() {
        val json = encode(
            lorebook("global-1", isCharacterBook = false),
            lorebook("char-1", isCharacterBook = true),
            lorebook("char-2", isCharacterBook = true),
        )
        assertEquals(setOf("char-1", "char-2"), characterBookIds(json))
    }

    @Test
    fun `strip character books keeps global books`() {
        val json = encode(
            lorebook("global-1", isCharacterBook = false),
            lorebook("char-1", isCharacterBook = true),
        )
        val kept = JsonInstant.parseToJsonElement(stripCharacterBooks(json)).jsonArray
        assertEquals(1, kept.size)
        assertEquals("global-1", kept[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `strip lorebook ids removes only materialized bindings`() {
        val json = encode(
            assistant("a-1", listOf("global-1", "char-1")),
            assistant("a-2", listOf("char-2")),
        )
        val migrated = JsonInstant.parseToJsonElement(
            stripLorebookIds(json, setOf("char-1", "char-2"))
        ).jsonArray

        val a1 = migrated[0].jsonObject["lorebookIds"]!!.jsonArray.map { it.jsonPrimitive.content }
        val a2 = migrated[1].jsonObject["lorebookIds"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("global-1"), a1)
        assertTrue(a2.isEmpty())
    }

    @Test
    fun `malformed json is returned unchanged`() {
        assertEquals("not json", stripCharacterBooks("not json"))
        assertEquals("[]", stripLorebookIds("[]", emptySet()))
        assertTrue(characterBookIds("{").isEmpty())
    }

    @Test
    fun `assistant without lorebookIds is untouched`() {
        val json = encode(assistant("a-1", emptyList()))
        val migrated = JsonInstant.parseToJsonElement(stripLorebookIds(json, setOf("char-1"))).jsonArray
        assertEquals(1, migrated.size)
        assertFalse(migrated[0].jsonObject.containsKey("lorebookIds") && migrated[0].jsonObject["lorebookIds"]!!.jsonArray.isNotEmpty())
    }
}
