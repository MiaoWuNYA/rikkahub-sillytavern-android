package me.rerere.rikkahub.browser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant

/** 站点适配规则：只做「结构 + 文本」启发式，class 改名不至于全盘失效。 */
internal object ExtractionRules {

    suspend fun productList(page: BrowserPage, limit: Int): String {
        val raw = page.evaluate(SnapshotScript.products(limit.coerceIn(1, 30)))
            ?: throw BrowserFailure(BrowserError.NOT_READY, "页面还没加载出商品列表")
        val obj = runCatching { JsonInstant.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: throw BrowserFailure(BrowserError.NOT_READY, "列表解析失败")
        if (obj["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            throw BrowserFailure(BrowserError.NOT_FOUND, "没有识别到商品链接，可改用 browser_snapshot 自行判断")
        }
        val items = obj["items"]?.jsonArray ?: JsonArray(emptyList())
        if (items.isEmpty()) throw BrowserFailure(BrowserError.NOT_FOUND, "当前页没有商品条目")

        val builder = StringBuilder("GOODS ").append(items.size).append('\n')
        items.forEachIndexed { index, element ->
            val item = element.jsonObject
            builder.append(index + 1).append('|')
                .append(item["title"]?.jsonPrimitive?.content.orEmpty()).append('|')
                .append(item["price"]?.jsonPrimitive?.content.orEmpty()).append('|')
                .append(item["sales"]?.jsonPrimitive?.content.orEmpty()).append('|')
                .append(item["host"]?.jsonPrimitive?.content.orEmpty())
                .append("/item.htm?id=")
                .append(item["id"]?.jsonPrimitive?.content.orEmpty())
                .append('\n')
        }
        return builder.toString().trimEnd()
    }

    suspend fun productDetail(page: BrowserPage): String {
        val raw = page.evaluate(SnapshotScript.PRODUCT_DETAIL)
            ?: throw BrowserFailure(BrowserError.NOT_READY, "详情页还没加载完成")
        val obj = runCatching { JsonInstant.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: throw BrowserFailure(BrowserError.NOT_READY, "详情解析失败")
        if (obj["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            throw BrowserFailure(BrowserError.NOT_FOUND, "详情结构未识别，可改用 browser_snapshot")
        }
        val builder = StringBuilder("DETAIL ")
        builder.append(obj["title"]?.jsonPrimitive?.content.orEmpty()).append('|')
            .append(obj["price"]?.jsonPrimitive?.content.orEmpty()).append('|')
            .append(obj["shop"]?.jsonPrimitive?.content.orEmpty())
        val sku = obj["sku"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content }?.take(8).orEmpty()
        if (sku.isNotEmpty()) builder.append("\nSKU ").append(sku.joinToString(" / "))
        val reviewCount = obj["reviewCount"]?.jsonPrimitive?.content.orEmpty()
        if (reviewCount.isNotEmpty()) builder.append("\nREVIEWS ").append(reviewCount)
        obj["reviews"]?.jsonArray?.forEach { builder.append("\n> ").append(it.jsonPrimitive.content) }
        return builder.toString()
    }

    suspend fun text(page: BrowserPage, limit: Int): String {
        val raw = page.evaluate(SnapshotScript.text(limit.coerceIn(200, 20000)))
            ?: throw BrowserFailure(BrowserError.NOT_READY, "正文读取失败")
        val obj = runCatching { JsonInstant.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: throw BrowserFailure(BrowserError.NOT_READY, "正文解析失败")
        val len = obj["len"]?.jsonPrimitive?.intOrNull ?: 0
        val body = obj["text"]?.jsonPrimitive?.content.orEmpty()
        return "TEXT ${body.length}/$len\n$body"
    }

    suspend fun tables(page: BrowserPage, limit: Int): String {
        val raw = page.evaluate(SnapshotScript.tables(limit.coerceIn(1, 10)))
            ?: throw BrowserFailure(BrowserError.NOT_FOUND, "没有表格")
        val obj = runCatching { JsonInstant.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: throw BrowserFailure(BrowserError.NOT_FOUND, "表格解析失败")
        val tables = obj["tables"]?.jsonArray
        if (tables.isNullOrEmpty()) throw BrowserFailure(BrowserError.NOT_FOUND, "当前页没有表格")
        val builder = StringBuilder()
        tables.forEachIndexed { index, table ->
            builder.append("TABLE ").append(index + 1).append('\n')
            table.jsonArray.forEach { builder.append(it.jsonPrimitive.content).append('\n') }
        }
        return builder.toString().trimEnd()
    }
}
