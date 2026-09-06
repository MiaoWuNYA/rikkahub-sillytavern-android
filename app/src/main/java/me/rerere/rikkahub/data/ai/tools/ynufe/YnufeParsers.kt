package me.rerere.rikkahub.data.ai.tools.ynufe

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * 云南财经强智教务网业务页面解析器（移植自 云南财经/src/parsers 目录）。
 * 输出结构化 JSON（kotlinx.serialization），供 AI 工具返回后由模型解读。
 */
object YnufeParsers {

    /** 返回数据表表头单元格文本列表（优先 th，退化 td）。 */
    private fun headerCells(dataTable: Element): List<String> {
        val hr = dataTable.selectFirst("tr") ?: return emptyList()
        val cells = hr.select("th").ifEmpty { hr.select("td") }
        return cells.map { it.text().trim() }
    }

    /** 找出表头中首个包含任一 key 的列下标；找不到返回 fallback。 */
    private fun findCol(headers: List<String>, keys: List<String>, fallback: Int): Int {
        val i = headers.indexOfFirst { h -> keys.any { h.contains(it) } }
        return if (i >= 0) i else fallback
    }

    private fun cellAt(tds: List<Element>, idx: Int): String =
        if (idx in tds.indices) tds[idx].text().trim() else ""

    // ===================== 课表 =====================
    fun parseTimetableJson(html: String): kotlinx.serialization.json.JsonObject {
        val doc = Jsoup.parse(html)
        val semesterOptions = buildJsonArray {
            doc.select("select#xnxq01id option").forEach { opt ->
                val v = opt.attr("value").ifEmpty { opt.text() }
                if (v.isNotEmpty()) {
                    add(buildJsonObject {
                        put("value", v)
                        put("text", opt.text().trim())
                        put("selected", opt.hasAttr("selected"))
                    })
                }
            }
        }

        var currentWeek = 0
        doc.select("select#zc option").forEach { opt ->
            if (opt.hasAttr("selected")) {
                val n = opt.attr("value").toIntOrNull()
                if (n != null && n > 0) currentWeek = n
            }
        }

        val courses = buildJsonArray {
            val kbTable = doc.selectFirst("table#kbtable") ?: doc.selectFirst("table.kbtable")
            if (kbTable != null) {
                kbTable.select("div.kbcontent").forEach { div ->
                    val parts = div.id().split("_")
                    if (parts.size < 2) return@forEach
                    val slot = parts[0].toIntOrNull() ?: return@forEach
                    val day = parts[1].toIntOrNull() ?: return@forEach
                    val blocks = div.html().split(
                        Regex("<br\\s*/?>\\s*-{10,}\\s*<br\\s*/?>|<hr\\s*/?>", RegexOption.IGNORE_CASE)
                    )
                    for (b in blocks) {
                        if (b.isBlank()) continue
                        val name = extractCourseName(b, doc)
                        if (name.isEmpty() || name.length < 2) continue
                        val teacher = Regex("老师['\"]>([^<]*)").find(b)?.groupValues?.get(1)?.trim() ?: "未知教师"
                        val room = Regex("教室['\"]>([^<]*)").find(b)?.groupValues?.get(1)?.trim() ?: "未定教室"
                        val weeks = Regex("周次\\(节次\\)['\"]>([^<]*)").find(b)?.groupValues?.get(1)?.trim() ?: "全周"
                        val isAdjusted = b.contains("color=\"red\"", ignoreCase = true) || b.contains("color='red'", ignoreCase = true)
                        add(buildJsonObject {
                            put("name", name)
                            put("teacher", teacher)
                            put("room", room)
                            put("weeks", weeks)
                            put("day", day)
                            put("slot", slot)
                            put("session", (slot + 1) / 2)
                            put("isAdjusted", isAdjusted)
                        })
                    }
                }
            }
        }

        val selectedSem = doc.select("select#xnxq01id option[selected]").firstOrNull()
        val currentSemesterId = if (selectedSem != null && selectedSem.attr("value").isNotEmpty()) {
            selectedSem.attr("value")
        } else {
            doc.select("select#xnxq01id option").firstOrNull()?.attr("value").orEmpty()
        }

        return buildJsonObject {
            put("semesters", semesterOptions)
            put("currentSemesterId", currentSemesterId)
            put("currentWeek", currentWeek)
            put("courses", courses)
        }
    }

    private fun extractCourseName(blockHtml: String, doc: Document): String {
        val div = doc.createElement("div")
        div.html(blockHtml)
        val parts = ArrayList<String>()
        for (node in div.childNodes()) {
            if (node is Element) {
                val el = node
                val tag = el.tagName().lowercase()
                if (el.hasAttr("title") || el.id().startsWith("jc_") || el.id().startsWith("kc_") ||
                    tag == "input" || tag == "select"
                ) break
                if (tag == "br") continue
                val txt = el.text().trim()
                if (txt.isNotEmpty() && txt != "&nbsp;") parts.add(txt)
            } else if (node is TextNode) {
                val txt = node.text().trim()
                if (txt.isNotEmpty() && txt != "&nbsp;") parts.add(txt)
            }
        }
        if (parts.isEmpty()) return ""
        val sb = StringBuilder()
        for (p in parts) {
            if (sb.isEmpty()) {
                sb.append(p)
            } else {
                val isLatinLast = sb.last().isLetterOrDigit()
                val isLatinFirst = p.first().isLetterOrDigit()
                sb.append(if (isLatinLast && isLatinFirst) " $p" else p)
            }
        }
        return sb.toString().trim()
    }

    // ===================== 成绩 =====================
    fun parseGradesJson(html: String): kotlinx.serialization.json.JsonObject {
        val doc = Jsoup.parse(html)
        val headerText = doc.body()?.text() ?: html
        val gpa = Regex("平均学分绩点[:：]\\s*([\\d.]+)").find(headerText)?.groupValues?.get(1) ?: "0.00"
        val totalCredits = Regex("所修总学分[:：]\\s*([\\d.]+)").find(headerText)?.groupValues?.get(1) ?: "0.0"

        val gradesList = buildJsonArray {
            val dataTable = doc.selectFirst("table#dataList") ?: doc.selectFirst("table.Nsb_r_list")
            if (dataTable != null) {
                val headers = headerCells(dataTable)
                val semCol = findCol(headers, listOf("开课学期"), 1)
                val nameCol = findCol(headers, listOf("课程名称"), 3)
                val scoreCol = findCol(headers, listOf("成绩"), 5)
                val creditCol = findCol(headers, listOf("学分"), 7)
                val pointCol = findCol(headers, listOf("绩点"), 9)
                val rows = dataTable.select("tr")
                for (i in 1 until rows.size) {
                    val tds = rows[i].select("td")
                    if (tds.size < 10) continue
                    val name = cellAt(tds, nameCol)
                    if (name.isEmpty() || name == "未查询到数据") continue
                    add(buildJsonObject {
                        put("semester", cellAt(tds, semCol))
                        put("courseName", name)
                        put("score", cellAt(tds, scoreCol))
                        put("credit", cellAt(tds, creditCol))
                        put("point", cellAt(tds, pointCol))
                    })
                }
            }
        }

        val semSet = LinkedHashSet<String>()
        for (el in gradesList) {
            (el as kotlinx.serialization.json.JsonObject)["semester"]?.let {
                semSet.add(it.toString().trim('"'))
            }
        }

        return buildJsonObject {
            put("gpa", gpa)
            put("totalCredits", totalCredits)
            put("coursesCount", gradesList.size)
            put("semesters", buildJsonArray { semSet.sorted().reversed().forEach { add(JsonPrimitive(it)) } })
            put("gradesList", gradesList)
        }
    }

    // ===================== 排考 =====================
    fun parseExamsJson(html: String, term: String = "", typeLabel: String = ""): JsonArray {
        val doc = Jsoup.parse(html)
        return buildJsonArray {
            val dataTable = doc.selectFirst("table#dataList") ?: doc.selectFirst("table.Nsb_r_list")
            if (dataTable != null) {
                val headers = headerCells(dataTable)
                val nameCol = findCol(headers, listOf("课程名称"), 5)
                val dateCol = findCol(headers, listOf("考试时间"), 7)
                val roomCol = findCol(headers, listOf("考场"), 8)
                val seatCol = findCol(headers, listOf("座位号"), 9)
                val teacherCol = findCol(headers, listOf("授课教师"), 6)
                val campusCol = findCol(headers, listOf("校区"), 1)
                val sessionCol = findCol(headers, listOf("考试场次"), 3)
                val rows = dataTable.select("tr")
                for (i in 1 until rows.size) {
                    val tds = rows[i].select("td")
                    if (tds.size < 10) continue
                    val name = cellAt(tds, nameCol)
                    if (name.isEmpty() || name == "未查询到数据") continue
                    add(buildJsonObject {
                        put("term", term)
                        put("courseName", name)
                        put("date", cellAt(tds, dateCol))
                        put("room", cellAt(tds, roomCol))
                        put("seat", cellAt(tds, seatCol))
                        put("teacher", cellAt(tds, teacherCol))
                        put("campus", cellAt(tds, campusCol))
                        put("examSession", cellAt(tds, sessionCol))
                        put("examType", typeLabel.ifEmpty { "考试" })
                    })
                }
            }
        }
    }

    // ===================== 公告 =====================
    fun parseAnnouncementsJson(html: String): JsonArray {
        val doc = Jsoup.parse(html)
        val rows = ArrayList<JsonObject>()

        val dataTable = doc.selectFirst("table#dataList") ?: doc.selectFirst("table.Nsb_r_list")
        if (dataTable != null) {
            val headers = headerCells(dataTable)
            val titleCol = findCol(headers, listOf("标题"), 1)
            val dateCol = findCol(headers, listOf("发送时间", "发布时间"), 4)
            val actionCol = findCol(headers, listOf("操作"), headers.size - 1)
            val trs = dataTable.select("tr")
            for (i in 1 until trs.size) {
                val tds = trs[i].select("td")
                if (tds.size < 5) continue
                val titleCell = tds.getOrNull(titleCol) ?: continue
                val title = titleCell.selectFirst("a")?.text() ?: titleCell.text()
                if (title.isBlank() || title == "未查询到数据" || title == "暂无数据") continue
                val date = cellAt(tds, dateCol)
                var url = ""
                val actionCell = tds.getOrNull(actionCol) ?: tds.lastOrNull()
                val actionLink = actionCell?.selectFirst("a") ?: titleCell.selectFirst("a")
                if (actionLink != null) {
                    val href = actionLink.attr("href")
                    val m = Regex("openWindow\\s*\\(\\s*['\"]([^'\"]+)['\"]").find(href)
                    url = if (m != null) m.groupValues[1]
                    else if (href.isNotEmpty() && !href.lowercase().startsWith("javascript:") &&
                        !href.lowercase().startsWith("data:")
                    ) href
                    else ""
                }
                rows.add(buildJsonObject {
                    put("title", title.trim())
                    put("date", date)
                    put("url", url)
                })
            }
        }

        if (rows.isEmpty()) {
            doc.select("table tr, ul li").forEach { r ->
                val a = r.selectFirst("a") ?: return@forEach
                val title = a.text().trim()
                if (title.length > 3) {
                    val dateMatch = Regex("\\d{4}-\\d{2}-\\d{2}").find(r.text())
                    rows.add(buildJsonObject {
                        put("title", title)
                        put("date", dateMatch?.value ?: "近期")
                        put("url", a.attr("href"))
                    })
                }
            }
        }

        return buildJsonArray { rows.forEach { add(it) } }
    }
}