package me.rerere.rikkahub.plugin

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.pluginOwner
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.PluginManifest
import me.rerere.rikkahub.plugin.provider.PluginProviderSpecs
import me.rerere.rikkahub.plugin.provider.toProviderSetting
import me.rerere.rikkahub.plugin.provider.withPluginProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

class PluginProviderSpecTest {

    private fun manifest(providers: kotlinx.serialization.json.JsonElement) = PluginManifest(
        id = "com.test.free",
        name = "免费模型",
        description = "",
        version = "1.0.0",
        author = "tester",
        icon = "🆓",
        providers = providers,
    )

    private fun plugin(
        providers: kotlinx.serialization.json.JsonElement,
        config: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    ) = PluginInfo(
        manifest = manifest(providers),
        directory = File("/tmp/does-not-exist"),
        isEnabled = true,
        config = config,
    )

    private fun inlineProviders() = buildJsonArray {
        add(
            buildJsonObject {
                put("id", "zen")
                put("name", "Zen 免费")
                put("baseUrl", "https://opencode.ai/zen/v1")
                put("apiKey", "public")
                put("headers", buildJsonArray {
                    add(buildJsonObject { put("name", "user-agent"); put("value", "opencode/1.0") })
                })
                put("proxies", buildJsonArray {
                    add(buildJsonObject { put("url", "\${config.proxy_url}") })
                })
                put("models", buildJsonArray {
                    add(
                        buildJsonObject {
                            put("modelId", "big-pickle")
                            put("displayName", "Big Pickle")
                            put("abilities", buildJsonArray { add(JsonPrimitive("tool")) })
                            put("inputModalities", buildJsonArray { add(JsonPrimitive("text")); add(JsonPrimitive("image")) })
                        }
                    )
                })
            }
        )
    }

    @Test
    fun `inline providers are parsed with headers and models`() {
        val specs = PluginProviderSpecs.parse(plugin(inlineProviders()))

        assertEquals(1, specs.size)
        assertEquals("zen", specs[0].id)
        assertEquals("https://opencode.ai/zen/v1", specs[0].baseUrl)
        assertEquals(1, specs[0].headers.size)
        assertEquals("opencode/1.0", specs[0].headers[0].value)
        assertEquals(listOf("big-pickle"), specs[0].models.map { it.modelId })
    }

    @Test
    fun `config placeholders are substituted`() {
        val config = mapOf("proxy_url" to JsonPrimitive("socks5://127.0.0.1:1080"))

        val spec = PluginProviderSpecs.parse(plugin(inlineProviders(), config)).single()

        assertEquals(listOf("socks5://127.0.0.1:1080"), spec.proxyList().map { it.url })
    }

    @Test
    fun `blank proxy url drops the proxy entirely`() {
        val spec = PluginProviderSpecs.parse(plugin(inlineProviders())).single()

        assertTrue(spec.proxyList().isEmpty())
    }

    @Test
    fun `config placeholder without a value becomes empty`() {
        val spec = PluginProviderSpecs.parse(plugin(inlineProviders())).single()

        assertEquals(listOf(""), spec.proxies.map { it.url })
        assertTrue(spec.proxyList().isEmpty())
    }

    @Test
    fun `unknown config keys resolve to empty and the rest of the string survives`() {
        val providers = buildJsonArray {
            add(
                buildJsonObject {
                    put("id", "zen")
                    put("name", "Zen")
                    put("baseUrl", "https://\${config.host}/\${config.missing}/v1")
                    put("headers", buildJsonArray {
                        add(buildJsonObject { put("name", "x-a"); put("value", "pre-\${config.token}-post") })
                    })
                }
            )
        }
        val config = mapOf(
            "host" to JsonPrimitive("opencode.ai"),
            "token" to JsonPrimitive("abc"),
        )

        val spec = PluginProviderSpecs.parse(plugin(providers, config)).single()

        assertEquals("https://opencode.ai//v1", spec.baseUrl)
        assertEquals("pre-abc-post", spec.headers.single().value)
    }

    @Test
    fun `unterminated placeholder is left untouched`() {
        val providers = buildJsonArray {
            add(
                buildJsonObject {
                    put("id", "zen")
                    put("name", "Zen")
                    put("baseUrl", "https://opencode.ai/\${config.host")
                }
            )
        }

        val spec = PluginProviderSpecs.parse(plugin(providers)).single()

        assertEquals("https://opencode.ai/\${config.host", spec.baseUrl)
    }

    @Test
    fun `provider and model ids stay stable across parses`() {
        val first = plugin(inlineProviders()).toProviderSetting(PluginProviderSpecs.parse(plugin(inlineProviders())).single())
        val second = plugin(inlineProviders()).toProviderSetting(PluginProviderSpecs.parse(plugin(inlineProviders())).single())

        assertEquals(first.id, second.id)
        assertEquals(first.models.single().id, second.models.single().id)
    }

    @Test
    fun `another plugin never collides with the first one`() {
        val other = plugin(inlineProviders()).let { it.copy(manifest = it.manifest.copy(id = "com.test.other")) }
        val mine = plugin(inlineProviders()).toProviderSetting(PluginProviderSpecs.parse(plugin(inlineProviders())).single())
        val theirs = other.toProviderSetting(PluginProviderSpecs.parse(other).single())

        assertTrue(mine.id != theirs.id)
        assertTrue(mine.models.single().id != theirs.models.single().id)
    }

    @Test
    fun `model modalities and abilities map to enums`() {
        val spec = PluginProviderSpecs.parse(plugin(inlineProviders())).single()
        val model = plugin(inlineProviders()).toProviderSetting(spec).models.single()

        assertEquals("Big Pickle", model.displayName)
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), model.inputModalities)
        assertTrue(model.abilities.isNotEmpty())
    }

    @Test
    fun `plugin provider is marked as plugin owned`() {
        val spec = PluginProviderSpecs.parse(plugin(inlineProviders())).single()
        val provider = plugin(inlineProviders()).toProviderSetting(spec)

        assertEquals("com.test.free", provider.pluginOwner())
    }

    @Test
    fun `merge adds plugin provider next to user providers`() {
        val user = ProviderSetting.OpenAI(id = Uuid.random(), name = "我的中转", baseUrl = "https://api.example.com/v1")
        val spec = PluginProviderSpecs.parse(plugin(inlineProviders())).single()
        val pluginProvider = plugin(inlineProviders()).toProviderSetting(spec)

        val merged = Settings(providers = listOf(user)).withPluginProviders(listOf(pluginProvider))

        assertEquals(listOf(user, pluginProvider), merged.providers)
    }

    @Test
    fun `merge removes plugin providers once the plugin is gone`() {
        val user = ProviderSetting.OpenAI(id = Uuid.random(), name = "我的中转", baseUrl = "https://api.example.com/v1")
        val spec = PluginProviderSpecs.parse(plugin(inlineProviders())).single()
        val pluginProvider = plugin(inlineProviders()).toProviderSetting(spec)

        val merged = Settings(providers = listOf(user, pluginProvider)).withPluginProviders(emptyList())

        assertEquals(listOf(user), merged.providers)
        assertNull(merged.providers.single().pluginOwner())
    }

    @Test
    fun `merge keeps the enabled switch the user toggled`() {
        val spec = PluginProviderSpecs.parse(plugin(inlineProviders())).single()
        val disabled = plugin(inlineProviders()).toProviderSetting(spec).copy(enabled = false)

        val merged = Settings(providers = listOf(disabled)).withPluginProviders(listOf(disabled.copy(enabled = true)))

        assertEquals(false, merged.providers.single().enabled)
    }
}
