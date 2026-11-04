package me.rerere.rikkahub.browser

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant

internal data class ElementLine(val index: Int, val raw: String) {
    val label: String get() = raw.substringAfter("] ")
}

internal data class Snapshot(
    val title: String,
    val url: String,
    val hash: String,
    val scrollY: Int,
    val scrollHeight: Int,
    val viewport: Int,
    val elements: List<ElementLine>,
    val text: String,
    val textLen: Int,
)

internal object SnapshotEngine {
    private const val MAX_DIFF_LINES = 30

    @Volatile
    private var last: Snapshot? = null

    fun previous(): Snapshot? = last

    fun reset() {
        last = null
    }

    suspend fun capture(page: BrowserPage): Snapshot {
        page.evaluate(SnapshotScript.INIT)
        val raw = page.evaluate(SnapshotScript.SNAPSHOT)
            ?: throw BrowserFailure(BrowserError.NOT_READY, "页面还没法读取结构")
        val obj = runCatching { JsonInstant.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: throw BrowserFailure(BrowserError.NOT_READY, "快照解析失败")
        val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull == true
        if (!ok) {
            throw BrowserFailure(BrowserError.NOT_READY, obj["error"]?.jsonPrimitive?.content ?: "快照失败")
        }
        val lines = obj["lines"]?.jsonArray?.mapIndexed { index, element ->
            ElementLine(index, element.jsonPrimitive.content)
        }.orEmpty()
        val snapshot = Snapshot(
            title = obj["title"]?.jsonPrimitive?.content.orEmpty(),
            url = obj["url"]?.jsonPrimitive?.content.orEmpty(),
            hash = obj["hash"]?.jsonPrimitive?.content.orEmpty(),
            scrollY = obj["scrollY"]?.jsonPrimitive?.intOrNull ?: 0,
            scrollHeight = obj["scrollHeight"]?.jsonPrimitive?.intOrNull ?: 0,
            viewport = obj["viewport"]?.jsonPrimitive?.intOrNull ?: 0,
            elements = lines,
            text = obj["text"]?.jsonPrimitive?.content.orEmpty(),
            textLen = obj["textLen"]?.jsonPrimitive?.intOrNull ?: 0,
        )
        last = snapshot
        return snapshot
    }

    /** 紧凑 DSL：每行一个元素，约 15–25 token，模型直接按索引指元素。 */
    fun format(
        snapshot: Snapshot,
        mode: String,
        offset: Int,
        limit: Int,
        detail: SnapshotDetail,
    ): String {
        val builder = StringBuilder()
        builder.append("PAGE ")
        builder.append(snapshot.title.take(60).ifEmpty { "(无标题)" })
        builder.append(" | ")
        builder.append(snapshot.url.take(120))
        builder.append(" | y=")
        builder.append(snapshot.scrollY)
        builder.append('/')
        builder.append(snapshot.scrollHeight)
        builder.append(" | h=")
        builder.append(snapshot.hash)
        if (mode != "text") {
            val cap = minOf(if (limit <= 0) detail.maxElements else limit, detail.maxElements)
            val shown = snapshot.elements.take(cap)
            builder.append('\n')
            shown.forEach { builder.append(it.raw).append('\n') }
            if (snapshot.elements.size > shown.size) {
                builder.append("... +").append(snapshot.elements.size - shown.size).append(" more (limit)\n")
            }
        }
        if (mode != "interactive") {
            val budget = if (limit in 1..20000) limit else detail.textBudget
            val slice = snapshot.text.drop(offset).take(budget)
            builder.append("TEXT ")
            builder.append(offset + slice.length).append('/').append(snapshot.textLen).append('字')
            if (offset + slice.length <
                snapshot.textLen) {
                builder.append(" (offset=").append(offset + slice.length).append(')')
            }
            builder.append('\n')
            builder.append(slice)
        }
        return builder.toString().trimEnd()
    }

    /** 动作后只看变化：页面没变就一句话，变了给 +/-/~ 三类。 */
    fun diff(previousSnapshot: Snapshot?, current: Snapshot): String {
        val prev = previousSnapshot ?: return ""
        if (prev.url != current.url) return ""
        val prevLabels = prev.elements.map { it.label }
        val curLabels = current.elements.map { it.label }
        val prevSet = prevLabels.toSet()
        val curSet = curLabels.toSet()

        val added = current.elements.filter { it.label !in prevSet }
        val removed = prev.elements.filter { it.label !in curSet }
        val changed = current.elements.filterIndexed { index, element ->
            val old = prev.elements.getOrNull(index) ?: return@filterIndexed false
            old.label != element.label && element.label in prevSet
        }

        if (added.isEmpty() && removed.isEmpty() && changed.isEmpty()) {
            return "UNCHANGED"
        }

        val builder = StringBuilder()
        builder.append("CHANGED +").append(added.size).append("/-").append(removed.size)
        if (changed.isNotEmpty()) builder.append("/~").append(changed.size)
        var budget = MAX_DIFF_LINES
        added.take(budget).forEach {
            builder.append("\n+ ").append(it.raw)
            budget--
        }
        removed.take(budget.coerceAtLeast(0)).forEach {
            builder.append("\n- ").append(it.raw)
            budget--
        }
        changed.take(budget.coerceAtLeast(0)).forEach {
            builder.append("\n~ ").append(it.raw)
        }
        return builder.toString()
    }

    fun estimateTokens(text: String): Int = (text.length / 2).coerceAtLeast(1)
}
