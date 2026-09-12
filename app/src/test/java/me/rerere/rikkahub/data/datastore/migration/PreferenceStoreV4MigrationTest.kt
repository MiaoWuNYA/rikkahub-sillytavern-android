package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PreferenceStoreV4MigrationTest {

    private fun preferencesWithAssistants(json: String, version: Int? = 3): Preferences {
        val prefs = mutablePreferencesOf(SettingsStore.ASSISTANTS to json)
        if (version != null) prefs[SettingsStore.VERSION] = version
        return prefs
    }

    @Test
    fun `shouldMigrate when version missing or below 4`() = runBlocking {
        val migration = PreferenceStoreV4Migration()
        assertTrue(migration.shouldMigrate(preferencesWithAssistants("[]", version = null)))
        assertTrue(migration.shouldMigrate(preferencesWithAssistants("[]", version = 3)))
        assertFalse(migration.shouldMigrate(preferencesWithAssistants("[]", version = 4)))
    }

    @Test
    fun `migrate flips explicit false memory defaults to true`() = runBlocking {
        val migration = PreferenceStoreV4Migration()
        val migrated = migration.migrate(
            preferencesWithAssistants(
                """
                [
                  {
                    "id": "d0e6d1c3-6a0e-4d2f-9f3e-0f7b2d5f6a01",
                    "name": "旧助手",
                    "enableMemory": false,
                    "enableMemoryRag": false,
                    "enableEpisodicMemory": false,
                    "enableThreeLayerMemory": false,
                    "enableCrossWindowMemory": false,
                    "temperature": 0.7
                  }
                ]
                """.trimIndent()
            )
        )

        assertEquals(4, migrated[SettingsStore.VERSION])
        val assistant = kotlinx.serialization.json.Json.parseToJsonElement(
            migrated[SettingsStore.ASSISTANTS]!!
        ).jsonArray.single().jsonObject

        MEMORY_DEFAULT_KEYS.forEach { key ->
            assertEquals(true, (assistant[key] as JsonPrimitive).content.toBooleanStrict())
        }
        // 其他字段不受影响
        assertEquals(0.7, assistant["temperature"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun `migrate keeps already enabled assistants untouched`() = runBlocking {
        val migration = PreferenceStoreV4Migration()
        val json = """
            [
              {"id": "d0e6d1c3-6a0e-4d2f-9f3e-0f7b2d5f6a02", "name": "已开启", "enableMemory": true}
            ]
        """.trimIndent()
        val migrated = migration.migrate(preferencesWithAssistants(json))
        assertEquals(json.replace(" ", "").replace("\n", ""), migrated[SettingsStore.ASSISTANTS]!!.replace(" ", "").replace("\n", ""))
    }

    @Test
    fun `migrate tolerates empty or invalid assistant json`() = runBlocking {
        val migration = PreferenceStoreV4Migration()
        assertEquals(4, migration.migrate(preferencesWithAssistants("[]"))[SettingsStore.VERSION])
        assertEquals(4, migration.migrate(preferencesWithAssistants("not json"))[SettingsStore.VERSION])
    }

    @Test
    fun `cleanUp is a no-op`() = runBlocking {
        // cleanUp 不会抛异常即可（DataMigration 接口约定）
        PreferenceStoreV4Migration().cleanUp()
    }
}
