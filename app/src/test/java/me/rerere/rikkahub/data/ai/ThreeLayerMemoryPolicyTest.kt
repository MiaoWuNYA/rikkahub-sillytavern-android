package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeLayerMemoryPolicyTest {

    private fun memory(id: Int, content: String) = AssistantMemory(id = id, content = content)

    @Test
    fun `empty query or empty memories returns nothing`() {
        val memories = listOf(memory(1, "喜欢咖啡"))
        assertTrue(ThreeLayerMemoryPolicy.selectLongTermMemories(memories, "", 6, 3000).isEmpty())
        assertTrue(ThreeLayerMemoryPolicy.selectLongTermMemories(emptyList(), "咖啡", 6, 3000).isEmpty())
    }

    @Test
    fun `latin term overlap ranks matching memories first`() {
        val memories = listOf(
            memory(1, "The user works with Kotlin on Android projects."),
            memory(2, "Prefers tea over coffee."),
            memory(3, "Kotlin conferences are fun."),
        )
        val selected = ThreeLayerMemoryPolicy.selectLongTermMemories(
            memories = memories,
            query = "What Kotlin projects am I working on?",
            limit = 6,
            maxChars = 3000,
        )
        assertTrue(selected.map { it.id }.containsAll(listOf(1, 3)))
        assertTrue(selected.first().id == 3 || selected.first().id == 1)
        assertTrue(selected.none { it.id == 2 })
    }

    @Test
    fun `cjk bigram overlap selects related memories`() {
        val memories = listOf(
            memory(1, "用户住在上海，喜欢喝咖啡。"),
            memory(2, "用户养了一只叫年糕的猫。"),
        )
        val selected = ThreeLayerMemoryPolicy.selectLongTermMemories(
            memories = memories,
            query = "我住在上海的哪里来着？",
            limit = 6,
            maxChars = 3000,
        )
        assertEquals(listOf(1), selected.map { it.id })
    }

    @Test
    fun `full phrase match gets bonus over term overlap`() {
        val phrase = memory(1, "用户的生日是 3 月 5 日")
        val scattered = memory(2, "生日蛋糕 3 人份，5 点取")
        val selected = ThreeLayerMemoryPolicy.selectLongTermMemories(
            memories = listOf(scattered, phrase),
            query = "用户的生日是 3 月 5 日吗",
            limit = 6,
            maxChars = 3000,
        )
        assertEquals(1, selected.first().id)
    }

    @Test
    fun `limit and max chars truncate recall`() {
        val memories = (1..10).map { memory(it, "memory $it about kotlin") }
        val limited = ThreeLayerMemoryPolicy.selectLongTermMemories(memories, "kotlin", 3, 10000)
        assertEquals(3, limited.size)

        val big = listOf(
            memory(1, "apple ".repeat(10)),
            memory(2, "banana ".repeat(10)),
            memory(3, "cherry ".repeat(10)),
        )
        val charsCapped = ThreeLayerMemoryPolicy.selectLongTermMemories(big, "apple banana cherry", 10, 150)
        assertEquals(2, charsCapped.size)
    }

    @Test
    fun `single oversized memory is truncated to max chars`() {
        val longToken = "hello".repeat(20) // 100 chars, single latin token
        val memories = listOf(memory(1, longToken))
        val selected = ThreeLayerMemoryPolicy.selectLongTermMemories(memories, longToken, 5, 30)
        assertEquals(1, selected.size)
        assertEquals(30, selected.first().content.length)
    }
}
