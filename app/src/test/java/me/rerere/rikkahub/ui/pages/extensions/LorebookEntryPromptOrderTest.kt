package me.rerere.rikkahub.ui.pages.extensions

import kotlinx.serialization.encodeToString
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.collectInjections
import me.rerere.rikkahub.data.ai.transformers.orderOrdinaryLorebookOutput
import me.rerere.rikkahub.data.ai.transformers.transformMessages
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class LorebookEntryPromptOrderTest {
    private val expected = listOf("6", "0", "8", "5", "3", "b", "9", "m")
    private val markers = Regex("""\[\[ORDER:([^]]+)\]\]""")
    private fun fixture(): Lorebook {
        val one = LorebookCategory(name = "1")
        val two = LorebookCategory(name = "2")
        val empty = LorebookCategory(name = "Empty")
        // Deliberately disagree with the root order and category creation order.
        val entries = listOf("3", "b", "9", "m", "6", "5", "0", "8").map { name ->
            PromptInjection.RegexInjection(name = name, content = "[[ORDER:$name]]", role = MessageRole.SYSTEM,
                constantActive = true, useProbability = false,
                categoryId = when (name) { "3", "b", "9", "m" -> one.id; "0", "8" -> two.id; else -> null })
        }
        return Lorebook(entries = entries, categories = listOf(one, two, empty), rootOrder = listOf(
            LorebookOrderNode(entryId = entries.first { it.name == "6" }.id),
            LorebookOrderNode(categoryId = two.id),
            LorebookOrderNode(entryId = entries.first { it.name == "5" }.id),
            LorebookOrderNode(categoryId = empty.id), LorebookOrderNode(categoryId = one.id)))
    }
    private fun reload(book: Lorebook) = JsonInstant.decodeFromString<Lorebook>(JsonInstant.encodeToString(book))
    private fun labels(messages: List<UIMessage>) = messages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Text>()
        .flatMap { markers.findAll(it.text).map { match -> match.groupValues[1] }.toList() }
    private fun prompt(book: Lorebook) = transformMessages(listOf(UIMessage.user("hello")),
        Assistant(lorebookIds = setOf(book.id)), emptyList(), listOf(book))
    private fun entry(book: Lorebook, name: String) = book.entries.first { it.name == name }
    private fun category(book: Lorebook, name: String) = book.categories.first { it.name == name }

    @Test fun savedRootOrderControlsMixedOrdinaryPromptInsteadOfStorageOrCreationOrder() {
        val book = fixture()
        val json = JsonInstant.encodeToString(book)
        val reopened = reload(book)
        val actual = prompt(reopened)
        val evidence = java.io.File("build/reports/lorebook-prompt-order").apply { mkdirs() }
        evidence.resolve("saved-lorebook.json").writeText(json)
        evidence.resolve("serialized-messages.json").writeText(JsonInstant.encodeToString(actual))
        evidence.resolve("orders.txt").writeText("storage=${reopened.entries.map { it.name }}\neditor=${reopened.orderedOrdinaryEntries().map { it.name }}\nprompt=${labels(actual)}\n")
        assertTrue(json.contains("rootOrder"))
        assertEquals(book.rootOrder, reopened.rootOrder)
        assertEquals(listOf("3", "b", "9", "m", "6", "5", "0", "8"), reopened.entries.map { it.name })
        assertEquals(expected, reopened.orderedOrdinaryEntries().map { it.name })
        assertEquals(expected, labels(actual))
    }
    @Test fun wholeCategoryMoveChangesPromptWithoutRewritingStorageOrFields() {
        val book = fixture()
        val moved = moveLorebookCategoryToEdge(book, category(book, "1").id, false)
        assertEquals(book.entries, moved.entries)
        assertEquals(listOf("3", "b", "9", "m", "6", "0", "8", "5"), labels(prompt(reload(moved))))
    }
    @Test fun categoryDragBetweenRootsSurvivesReload() {
        val book = fixture()
        val moved = moveLorebookCategory(book, category(book, "2").id,
            LorebookEntryPlacement(null, true, rootGroup = category(book, "1").id.toString(), categoryTarget = true))
        assertEquals(book.entries, moved.entries)
        assertEquals(listOf("6", "5", "3", "b", "9", "m", "0", "8"), labels(prompt(reload(moved))))
    }
    @Test fun categoryInternalReorderChangesOnlyItsChildrenAfterReload() {
        val book = fixture()
        val p = LorebookEntryPlacement(entry(book, "0").id, false, category(book, "2").id.toString(), categoryTarget = true)
        val changed = book.withPlacedEntries(moveLorebookEntry(book.entries, entry(book, "8").id, p), setOf(entry(book, "8").id), p)
        assertEquals(book.entries.associateBy { it.id }, changed.entries.associateBy { it.id })
        assertEquals(book.rootOrder, changed.rootOrder)
        assertEquals(listOf("6", "8", "0", "5", "3", "b", "9", "m"), labels(prompt(reload(changed))))
    }
    @Test fun movingPlainEntryIntoCategoryUsesSavedInternalOrder() {
        val book = fixture()
        val p = LorebookEntryPlacement.groupEdge(category(book, "2").id.toString(), true).copy(categoryTarget = true)
        val changed = book.withPlacedEntries(moveLorebookEntry(book.entries, entry(book, "5").id, p), setOf(entry(book, "5").id), p)
        assertEquals(category(book, "2").id, entry(changed, "5").categoryId)
        assertEquals(listOf("0", "8", "5"), changed.entries.filter { it.categoryId == category(book, "2").id }.map { it.name })
        assertEquals(expected, labels(prompt(reload(changed))))
    }
    @Test fun disabledAndUntriggeredEntriesAreFilteredBeforeOrderingWithoutDuplicates() {
        val book = fixture().let { b -> b.copy(entries = b.entries.map { e -> when (e.name) {
            "0" -> e.copy(enabled = false)
            "m" -> e.copy(constantActive = false, keywords = listOf("absent-keyword"))
            else -> e
        } }) }
        assertEquals(expected.filterNot { it == "0" || it == "m" }, labels(prompt(reload(book))))
    }
    @Test fun finalOrdinaryOrderDoesNotOverwritePrioritiesOrOtherSettings() {
        val book = fixture().let { b -> b.copy(entries = b.entries.mapIndexed { i, e -> e.copy(priority = (i * 37) % 101, probability = 43, groupWeight = i + 1) }) }
        val before = JsonInstant.encodeToString(book)
        assertEquals(expected, labels(prompt(book)))
        assertEquals(before, JsonInstant.encodeToString(book))
    }
    @Test fun legacyBookWithoutOrganizationKeepsOriginalPriorityOrder() {
        val book = fixture().let { b -> b.copy(categories = emptyList(), rootOrder = emptyList(),
            entries = listOf(entry(b, "6").copy(priority = 100, categoryId = null), entry(b, "5").copy(priority = 1, categoryId = null))) }
        assertEquals(listOf("5", "6"), labels(prompt(book)))
    }
    @Test fun exclusiveWinnersScanOrderAndOutputSlotsStayUnchanged() {
        val original = fixture()
        val winner = PromptInjection.RegexInjection(name = "winner", content = "[[ORDER:exclusive]]", group = "1",
            constantActive = true, useProbability = false, groupOverride = true, priority = 0,
            categoryId = category(original, "1").id)
        val loser = winner.copy(id = Uuid.random(), name = "loser", content = "[[ORDER:loser]]", groupOverride = false)
        val book = original.copy(entries = listOf(winner) + original.entries + loser)
        val legacy = book.copy(rootOrder = emptyList(), categories = emptyList())
        val assistant = Assistant(lorebookIds = setOf(book.id))
        val messages = listOf(UIMessage.user("hello"))
        assertEquals(collectInjections(messages, assistant, emptyList(), listOf(legacy)), collectInjections(messages, assistant, emptyList(), listOf(book)))
        val baseline = labels(prompt(legacy)); val actual = labels(prompt(book))
        assertFalse(actual.contains("loser"))
        assertEquals(1, actual.count { it == "exclusive" })
        assertEquals(baseline.indexOf("exclusive"), actual.indexOf("exclusive"))
        assertEquals(expected, actual.filterNot { it == "exclusive" })
    }
    @Test fun orderingNeverMovesModeExclusiveOtherBookOrDestinationSlots() {
        val book = fixture()
        val other = PromptInjection.RegexInjection(content = "other", role = MessageRole.SYSTEM)
        val exclusive = other.copy(id = Uuid.random(), group = "exclusive")
        val mode = PromptInjection.ModeInjection(content = "mode")
        val source: List<PromptInjection> = listOf(entry(book, "3"), mode, entry(book, "0"), exclusive, entry(book, "6"), other)
        val actual = orderOrdinaryLorebookOutput(source, listOf(book))
        assertEquals(listOf("6", "0", "3"), listOf(actual[0], actual[2], actual[4]).map { it.name })
        for (index in listOf(1, 3, 5)) assertSame(source[index], actual[index])
    }
    @Test fun differentPositionsDepthsAndRolesKeepTheirOriginalMessageDestinations() {
        val book = fixture().let { b -> b.copy(entries = b.entries.map { e -> when (e.name) {
            "0", "8" -> e.copy(position = InjectionPosition.AT_DEPTH, injectDepth = 1, role = MessageRole.USER)
            "3", "b" -> e.copy(position = InjectionPosition.AT_DEPTH, injectDepth = 4, role = MessageRole.ASSISTANT)
            else -> e
        } }) }
        val messages = listOf(UIMessage.user("old"), UIMessage.assistant("reply"), UIMessage.user("hello"))
        val output = transformMessages(messages, Assistant(lorebookIds = setOf(book.id)), emptyList(), listOf(book))
        assertEquals(listOf("3", "b"), labels(output.filter { it.role == MessageRole.ASSISTANT }))
        assertEquals(listOf("0", "8"), labels(output.filter { it.role == MessageRole.USER }))
        assertEquals(listOf("6", "5", "9", "m"), labels(output.filter { it.role == MessageRole.SYSTEM }))
        assertEquals(listOf(1, 1, 4, 4), book.entries.filter { it.name in listOf("0", "8", "3", "b") }.sortedBy { it.injectDepth }.map { it.injectDepth })
    }
    @Test fun nativeExportImportRemapsIdsWithoutLosingPromptOrder() {
        val book = fixture()
        val imported = me.rerere.rikkahub.data.export.LorebookSerializer.importFromJson(
            me.rerere.rikkahub.data.export.LorebookSerializer.exportToJson(book), "order")
        assertEquals(expected, labels(prompt(imported)))
        assertTrue(imported.entries.none { e -> book.entries.any { it.id == e.id } })
    }
    @Test fun completeMessageSerializationRetainsExactlyOneMarkerInRootOrder() {
        val result = prompt(reload(fixture()))
        val json = JsonInstant.encodeToString(result)
        val restored = JsonInstant.decodeFromString<List<UIMessage>>(json)
        assertEquals(expected, labels(restored))
        assertEquals(expected.size, markers.findAll(json).count())
    }
    @Test fun frozenToolTurnPreservesOrderedOutputWithoutRescanning() {
        val book = fixture()
        val assistant = Assistant(lorebookIds = setOf(book.id))
        val messages = listOf(UIMessage.user("hello"))
        var frozen: List<PromptInjection>? = null
        val first = transformMessages(messages, assistant, emptyList(), listOf(book), onResolved = { frozen = it })
        val next = transformMessages(messages + UIMessage.assistant("tool step"), assistant, emptyList(), listOf(book),
            frozenInjections = frozen, tickState = false)
        assertEquals(expected, labels(first)); assertEquals(expected, labels(next))
    }
}
