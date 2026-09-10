package me.rerere.rikkahub.plugin

import me.rerere.rikkahub.plugin.loader.FetchPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FetchPolicyTest {

    @Test
    fun `empty allowlist blocks everything`() {
        assertFalse(FetchPolicy.isHostAllowed("api.example.com", emptyList()))
        assertFalse(FetchPolicy.isUrlAllowed("https://api.example.com/v1", emptyList()))
    }

    @Test
    fun `wildcard allows everything`() {
        assertTrue(FetchPolicy.isHostAllowed("anything.example.com", listOf("*")))
        assertTrue(FetchPolicy.isUrlAllowed("http://evil.example.net/", listOf("*")))
    }

    @Test
    fun `exact host match`() {
        assertTrue(FetchPolicy.isHostAllowed("wttr.in", listOf("wttr.in")))
        assertTrue(FetchPolicy.isUrlAllowed("https://wttr.in/Beijing?format=j1", listOf("wttr.in")))
    }

    @Test
    fun `subdomain of allowed host is allowed`() {
        assertTrue(FetchPolicy.isHostAllowed("api.wttr.in", listOf("wttr.in")))
        assertTrue(FetchPolicy.isUrlAllowed("https://api.wttr.in/v1", listOf("wttr.in")))
    }

    @Test
    fun `unrelated host is blocked`() {
        assertFalse(FetchPolicy.isHostAllowed("evil.com", listOf("wttr.in")))
        // 不能通过伪造后缀绕过：notwttr.in 不属于 wttr.in 的子域
        assertFalse(FetchPolicy.isHostAllowed("notwttr.in", listOf("wttr.in")))
        assertFalse(FetchPolicy.isUrlAllowed("https://evil.com/x", listOf("wttr.in")))
    }

    @Test
    fun `case and trailing dot are normalized`() {
        assertTrue(FetchPolicy.isHostAllowed("WTTR.IN.", listOf("Wttr.In")))
    }

    @Test
    fun `multiple allowlist entries`() {
        val hosts = listOf("wttr.in", "api.openai.com")
        assertTrue(FetchPolicy.isHostAllowed("wttr.in", hosts))
        assertTrue(FetchPolicy.isHostAllowed("api.openai.com", hosts))
        assertFalse(FetchPolicy.isHostAllowed("evil.com", hosts))
    }

    @Test
    fun `invalid url fails closed`() {
        assertFalse(FetchPolicy.isUrlAllowed("not a url", listOf("*").let { listOf("wttr.in") }))
        assertFalse(FetchPolicy.isUrlAllowed("ht!tp://x", listOf("x")))
    }
}
