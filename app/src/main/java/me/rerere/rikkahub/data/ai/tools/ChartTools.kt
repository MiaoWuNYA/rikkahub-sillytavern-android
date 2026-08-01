package me.rerere.rikkahub.data.ai.tools

import android.graphics.Color
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.BarcodeFormat
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File

/**
 * 统一创作工具 — create_asset
 * 所有"生成视觉内容"类功能：图表/二维码/配色/ASCII艺术/横幅/时间线
 */
fun createAssetTool(saveDir: String): Tool = Tool(
    name = "create_asset",
    description = "Generate visual content saved as a file on the device.\n\n" +
        "Use this tool when you need to create visual content:\n" +
        "- html_page: Full HTML page with CSS/JS — web design, slides, dashboards\n" +
        "- diagram: Flowchart/sequence diagram as Mermaid HTML\n" +
        "- chart: SVG bar/line/pie chart from numeric data\n" +
        "- qrcode: QR code from text/URL\n" +
        "- color_scheme: Color palette from base color\n" +
        "- code_screenshot: Carbon-style code screenshot\n" +
        "- timeline: SVG timeline from chronological events\n\n" +
        "When NOT to use:\n" +
        "- Editing existing files (use file tool)\n" +
        "- Data processing (use convert_file)\n\n" +
        "Args:\n" +
        "- type: Asset type (required)\n" +
        "- Other params vary by type; see per-param descriptions",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("type", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("chart"); add("qrcode"); add("color_scheme")
                        add("timeline"); add("diagram")
                        add("code_screenshot"); add("html_page")
                    })
                    put("description", "Type of asset to create")
                })

                // === chart params ===
                put("chart_type", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("bar"); add("line"); add("pie") })
                    put("description", "For type=chart only. Chart subtype: bar/line/pie")
                })
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "For type=chart/timeline. Title of the visualization")
                })
                put("labels", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "For type=chart. X-axis labels or pie slice names")
                })
                put("series", buildJsonObject {
                    put("type", "array")
                    put("description", "For type=chart. Data series: [{name, values:[num]}]")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("name", buildJsonObject { put("type", "string"); put("description", "Series name") })
                            put("values", buildJsonObject {
                                put("type", "array"); put("items", buildJsonObject { put("type", "number") })
                                put("description", "Numeric values")
                            })
                        })
                        put("required", buildJsonArray { add("name"); add("values") })
                    })
                })

                // === qrcode params ===
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "For type=qrcode. Text or URL to encode in QR code")
                })
                put("size", buildJsonObject {
                    put("type", "integer")
                    put("description", "For type=qrcode. QR code size in pixels (default: 400)")
                })

                // === color_scheme params ===
                put("base_color", buildJsonObject {
                    put("type", "string")
                    put("description", "For type=color_scheme. Base hex color, e.g. #4F46E5")
                })
                put("scheme_type", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("analogous"); add("complementary"); add("triadic"); add("monochromatic") })
                    put("description", "For type=color_scheme. Color harmony type")
                })

                // === timeline params ===
                put("events", buildJsonObject {
                    put("type", "array")
                    put("description", "For type=timeline. Events: [{date, title, description}]")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("date", buildJsonObject { put("type", "string"); put("description", "Date or time label") })
                            put("title", buildJsonObject { put("type", "string"); put("description", "Event title") })
                            put("description", buildJsonObject { put("type", "string"); put("description", "Optional description") })
                        })
                        put("required", buildJsonArray { add("date"); add("title") })
                    })
                })
                // === diagram params ===
                put("diagram_type", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("flowchart"); add("sequence") })
                    put("description", "For type=diagram. Diagram subtype: flowchart/sequence")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "For type=diagram. Text description of the diagram flow")
                })
                // === code_screenshot params ===
                put("code", buildJsonObject {
                    put("type", "string")
                    put("description", "For type=code_screenshot. The source code to render as image")
                })
                put("language", buildJsonObject {
                    put("type", "string")
                    put("description", "For type=code_screenshot. Programming language (kotlin/python/javascript/java/etc)")
                })
                put("theme", buildJsonObject {
                    put("type", "string")
                    put("description", "For type=code_screenshot/diagram. Color theme: dark/light (default: dark)")
                })
                // === html_page params ===
                put("html", buildJsonObject {
                    put("type", "string")
                    put("description", "For type=html_page. Complete HTML content for the page")
                })
            },
            required = listOf("type"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: error("type required")
        val dir = File(saveDir).also { it.mkdirs() }
        val ts = System.currentTimeMillis()

        val (filename, content) = when (type) {
            "chart" -> {
                val ct = obj["chart_type"]?.jsonPrimitive?.contentOrNull ?: "bar"
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "Chart"
                val labels = obj["labels"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                val series = obj["series"]?.jsonArray?.map { s ->
                    val so = s.jsonObject
                    Series(
                        name = so["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        values = so["values"]?.jsonArray?.map { it.jsonPrimitive.content.toDoubleOrNull() ?: 0.0 } ?: emptyList(),
                    )
                } ?: emptyList()
                "chart_$ts.svg" to generateChartSvg(ct, title, labels, series)
            }
            "qrcode" -> {
                val contentStr = obj["content"]?.jsonPrimitive?.contentOrNull ?: error("content required")
                val qrSize = obj["size"]?.jsonPrimitive?.intOrNull ?: 400
                "qrcode_$ts.svg" to generateQrSvg(contentStr, qrSize)
            }
            "color_scheme" -> {
                val base = obj["base_color"]?.jsonPrimitive?.contentOrNull ?: "#4F46E5"
                val scheme = obj["scheme_type"]?.jsonPrimitive?.contentOrNull ?: "complementary"
                "palette_$ts.svg" to generateColorSchemeSvg(base, scheme)
            }
            "timeline" -> {
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "Timeline"
                val events = obj["events"]?.jsonArray?.map { e ->
                    val eo = e.jsonObject
                    val date = eo["date"]?.jsonPrimitive?.contentOrNull ?: ""
                    val t = eo["title"]?.jsonPrimitive?.contentOrNull ?: ""
                    val d = eo["description"]?.jsonPrimitive?.contentOrNull ?: ""
                    TimelineEvent(date, t, d)
                } ?: emptyList()
                "timeline_$ts.svg" to generateTimelineSvg(title, events)
            }
            "diagram" -> {
                val dgType = obj["diagram_type"]?.jsonPrimitive?.contentOrNull ?: "flowchart"
                val desc = obj["description"]?.jsonPrimitive?.contentOrNull ?: error("description required")
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "Diagram"
                "diagram_$ts.html" to generateMermaidHtml(dgType, title, desc)
            }
            "code_screenshot" -> {
                val code = obj["code"]?.jsonPrimitive?.contentOrNull ?: error("code required")
                val lang = obj["language"]?.jsonPrimitive?.contentOrNull ?: ""
                val theme = obj["theme"]?.jsonPrimitive?.contentOrNull ?: "dark"
                "code_$ts.svg" to generateCodeScreenshotSvg(code, lang, theme)
            }
            "html_page" -> {
                val html = obj["html"]?.jsonPrimitive?.contentOrNull ?: error("html required")
                "page_$ts.html" to html
            }
            else -> error("Unknown type: $type (supported: chart/qrcode/color_scheme/timeline/diagram/code_screenshot/html_page)")
        }

        val file = File(dir, filename)
        file.writeText(content)
        listOf(UIMessagePart.Text("OK: saved to ${file.absolutePath} (${file.length()} bytes, type=$type)"))
    },
)

// ── Data classes ──

private data class Series(val name: String, val values: List<Double>)
private data class TimelineEvent(val date: String, val title: String, val description: String)

// ── SVG generators ──

private fun generateChartSvg(chartType: String, title: String, labels: List<String>, series: List<Series>): String =
    when (chartType) {
        "line" -> lineChartSvg(title, labels, series)
        "pie" -> pieChartSvg(title, labels, series)
        else -> barChartSvg(title, labels, series)
    }

private fun barChartSvg(title: String, labels: List<String>, series: List<Series>): String = buildString {
    val w = 800; val h = 500; val m = 60
    val cw = w - m * 2; val ch = h - m * 2
    val colors = listOf("#4F46E5", "#10B981", "#F59E0B", "#EF4444", "#8B5CF6", "#EC4899")
    val maxV = series.maxOfOrNull { s -> s.values.maxOrNull() ?: 1.0 }?.coerceAtLeast(1.0) ?: 1.0
    val gw = cw.toDouble() / labels.size.coerceAtLeast(1)
    val bw = (gw * 0.7 / series.size.coerceAtLeast(1)).coerceAtMost(40.0)

    appendLine("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$w\" height=\"$h\" viewBox=\"0 0 $w $h\">")
    appendLine("<rect width=\"100%\" height=\"100%\" fill=\"#1e1e2e\" rx=\"8\"/>")
    appendLine("<text x=\"${w/2}\" y=\"30\" text-anchor=\"middle\" fill=\"#cdd6f4\" font-size=\"18\" font-weight=\"bold\">${xml(title)}</text>")
    for (i in 0..5) {
        val y = (m + ch - ch * i / 5.0).toInt()
        appendLine("<text x=\"${m-8}\" y=\"${y+4}\" text-anchor=\"end\" fill=\"#6c7086\" font-size=\"12\">${fmt((maxV * i / 5))}</text>")
        if (i > 0) appendLine("<line x1=\"$m\" y1=\"$y\" x2=\"${w-m}\" y2=\"$y\" stroke=\"#313244\" stroke-width=\"1\"/>")
    }
    series.forEachIndexed { si, s ->
        s.values.forEachIndexed { li, v ->
            val x = (m + gw * li + gw * 0.15 + bw * si).toInt()
            val bh = (ch * v / maxV).toInt().coerceAtLeast(1)
            val y = m + ch - bh
            appendLine("<rect x=\"$x\" y=\"$y\" width=\"${bw.toInt()}\" height=\"$bh\" fill=\"${colors[si%colors.size]}\" rx=\"3\"/>")
        }
    }
    labels.forEachIndexed { i, l ->
        val x = (m + gw * i + gw / 2).toInt()
        appendLine("<text x=\"$x\" y=\"${h-m+18}\" text-anchor=\"middle\" fill=\"#a6adc8\" font-size=\"11\" transform=\"rotate(-20,$x,${h-m+18})\">${xml(l.take(12))}</text>")
    }
    var lx = m
    series.forEachIndexed { si, s ->
        appendLine("<rect x=\"$lx\" y=\"${h-20}\" width=\"10\" height=\"10\" fill=\"${colors[si%colors.size]}\" rx=\"2\"/>")
        appendLine("<text x=\"${lx+14}\" y=\"${h-10}\" fill=\"#cdd6f4\" font-size=\"11\">${xml(s.name)}</text>")
        lx += 14 + s.name.length * 7 + 16
    }
    appendLine("</svg>")
}

private fun lineChartSvg(title: String, labels: List<String>, series: List<Series>): String = buildString {
    val w = 800; val h = 500; val m = 60
    val cw = w - m * 2; val ch = h - m * 2
    val colors = listOf("#4F46E5", "#10B981", "#F59E0B", "#EF4444", "#8B5CF6", "#EC4899")
    val maxV = series.maxOfOrNull { s -> s.values.maxOrNull() ?: 1.0 }?.coerceAtLeast(1.0) ?: 1.0

    appendLine("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$w\" height=\"$h\" viewBox=\"0 0 $w $h\">")
    appendLine("<rect width=\"100%\" height=\"100%\" fill=\"#1e1e2e\" rx=\"8\"/>")
    appendLine("<text x=\"${w/2}\" y=\"30\" text-anchor=\"middle\" fill=\"#cdd6f4\" font-size=\"18\" font-weight=\"bold\">${xml(title)}</text>")
    for (i in 0..5) {
        val y = (m + ch - ch * i / 5.0).toInt()
        appendLine("<text x=\"${m-8}\" y=\"${y+4}\" text-anchor=\"end\" fill=\"#6c7086\" font-size=\"12\">${fmt(maxV * i / 5)}</text>")
        if (i > 0) appendLine("<line x1=\"$m\" y1=\"$y\" x2=\"${w-m}\" y2=\"$y\" stroke=\"#313244\" stroke-width=\"1\"/>")
    }
    series.forEachIndexed { si, s ->
        val pts = s.values.mapIndexed { i, v ->
            val x = m + (cw * i / (s.values.size - 1).coerceAtLeast(1))
            val y = (m + ch - ch * v / maxV).toInt()
            "$x,$y"
        }
        appendLine("<polyline points=\"${pts.joinToString(" ")}\" fill=\"none\" stroke=\"${colors[si%colors.size]}\" stroke-width=\"2.5\"/>")
        pts.forEach { p ->
            appendLine("<circle cx=\"${p.split(",")[0]}\" cy=\"${p.split(",")[1]}\" r=\"4\" fill=\"${colors[si%colors.size]}\"/>")
        }
    }
    labels.forEachIndexed { i, l ->
        val x = m + (cw * i / (labels.size - 1).coerceAtLeast(1))
        appendLine("<text x=\"$x\" y=\"${h-m+18}\" text-anchor=\"middle\" fill=\"#a6adc8\" font-size=\"11\" transform=\"rotate(-20,$x,${h-m+18})\">${xml(l.take(12))}</text>")
    }
    var lx = m
    series.forEachIndexed { si, s ->
        appendLine("<rect x=\"$lx\" y=\"${h-20}\" width=\"10\" height=\"10\" fill=\"${colors[si%colors.size]}\" rx=\"2\"/>")
        appendLine("<text x=\"${lx+14}\" y=\"${h-10}\" fill=\"#cdd6f4\" font-size=\"11\">${xml(s.name)}</text>")
        lx += 14 + s.name.length * 7 + 16
    }
    appendLine("</svg>")
}

private fun pieChartSvg(title: String, labels: List<String>, series: List<Series>): String = buildString {
    val w = 600; val h = 500; val cx = 250; val cy = 250; val r = 180
    val colors = listOf("#4F46E5", "#10B981", "#F59E0B", "#EF4444", "#8B5CF6", "#EC4899", "#06B6D4", "#84CC16")
    val data = labels.mapIndexed { i, l -> l to (series.firstOrNull()?.values?.getOrElse(i) { 0.0 } ?: 0.0) }.filter { it.second > 0 }
    val total = data.sumOf { it.second }.coerceAtLeast(1.0)

    appendLine("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$w\" height=\"$h\" viewBox=\"0 0 $w $h\">")
    appendLine("<rect width=\"100%\" height=\"100%\" fill=\"#1e1e2e\" rx=\"8\"/>")
    appendLine("<text x=\"${w/2}\" y=\"30\" text-anchor=\"middle\" fill=\"#cdd6f4\" font-size=\"18\" font-weight=\"bold\">${xml(title)}</text>")
    var sa = -90.0
    data.forEachIndexed { i, (label, value) ->
        val a = 360.0 * value / total; val ea = sa + a
        val x1 = cx + (r * kotlin.math.cos(Math.toRadians(sa))).toInt()
        val y1 = cy + (r * kotlin.math.sin(Math.toRadians(sa))).toInt()
        val x2 = cx + (r * kotlin.math.cos(Math.toRadians(ea))).toInt()
        val y2 = cy + (r * kotlin.math.sin(Math.toRadians(ea))).toInt()
        val la = if (a > 180) 1 else 0
        appendLine("<path d=\"M$cx,$cy L$x1,$y1 A$r,$r 0 $la,1 $x2,$y2 Z\" fill=\"${colors[i%colors.size]}\" stroke=\"#1e1e2e\" stroke-width=\"2\"/>")
        val ma = Math.toRadians(sa + a / 2)
        val lx = cx + (r * 0.65 * kotlin.math.cos(ma)).toInt()
        val ly = cy + (r * 0.65 * kotlin.math.sin(ma)).toInt()
        if (a > 8) appendLine("<text x=\"$lx\" y=\"$ly\" text-anchor=\"middle\" fill=\"#fff\" font-size=\"11\" font-weight=\"bold\">${"%.0f".format(value / total * 100)}%</text>")
        sa = ea
    }
    data.forEachIndexed { i, (label, _) ->
        val rx = if (i < data.size / 2) 320 else 460
        val ry = h - 40 + (if (i < data.size / 2) i else i - (data.size + 1) / 2) * 22
        appendLine("<rect x=\"$rx\" y=\"${ry-10}\" width=\"10\" height=\"10\" fill=\"${colors[i%colors.size]}\" rx=\"2\"/>")
        appendLine("<text x=\"${rx+14}\" y=\"$ry\" fill=\"#cdd6f4\" font-size=\"11\">${xml(label.take(20))}</text>")
    }
    appendLine("</svg>")
}

private fun generateQrSvg(content: String, size: Int): String {
    val writer = QRCodeWriter()
    val matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { i ->
        val x = i % size; val y = i / size
        if (matrix[x, y]) Color.BLACK else Color.WHITE
    }
    return buildString {
        appendLine("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$size\" height=\"$size\" viewBox=\"0 0 $size $size\">")
        appendLine("<rect width=\"$size\" height=\"$size\" fill=\"white\"/>")
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (pixels[y * size + x] == Color.BLACK) {
                    appendLine("<rect x=\"$x\" y=\"$y\" width=\"1\" height=\"1\" fill=\"black\"/>")
                }
            }
        }
        appendLine("</svg>")
    }
}

private fun generateColorSchemeSvg(baseHex: String, scheme: String): String {
    fun parseHex(h: String): Int {
        val cleaned = h.removePrefix("#")
        val full = when (cleaned.length) {
            3 -> cleaned.map { "$it$it" }.joinToString("") // #FFF → #FFFFFF
            6 -> cleaned
            else -> error("Invalid color: $h (must be 3 or 6 hex digits)")
        }
        return try { Integer.parseInt(full, 16) } catch (_: NumberFormatException) { error("Invalid hex color: $h") }
    }
    fun hsl(h: Float, s: Float, l: Float): String {
        val c = (1 - kotlin.math.abs(2 * l - 1)) * s
        val x = c * (1 - kotlin.math.abs((h / 60) % 2 - 1))
        val m = l - c / 2
        val (r, g, b) = when {
            h < 60 -> listOf(c, x, 0f)
            h < 120 -> listOf(x, c, 0f)
            h < 180 -> listOf(0f, c, x)
            h < 240 -> listOf(0f, x, c)
            h < 300 -> listOf(x, 0f, c)
            else -> listOf(c, 0f, x)
        }
        return "#%02x%02x%02x".format(
            ((r + m) * 255).toInt().coerceIn(0, 255),
            ((g + m) * 255).toInt().coerceIn(0, 255),
            ((b + m) * 255).toInt().coerceIn(0, 255),
        )
    }

    val base = parseHex(baseHex)
    val r = (base shr 16) and 0xFF; val g = (base shr 8) and 0xFF; val b = base and 0xFF
    val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
    val max = maxOf(rf, gf, bf); val min = minOf(rf, gf, bf)
    val l = (max + min) / 2
    val s = if (max == min) 0f else (max - min) / (1 - kotlin.math.abs(2 * l - 1))
    val hue = when {
        max == min -> 0f
        max == rf -> (60 * ((gf - bf) / (max - min)) + 360) % 360
        max == gf -> 60 * ((bf - rf) / (max - min)) + 120
        else -> 60 * ((rf - gf) / (max - min)) + 240
    }

    val palette = when (scheme) {
        "analogous" -> listOf(0f, 30f, 60f, 90f, 120f).map { hsl((hue + it) % 360, s, l) }
        "triadic" -> listOf(0f, 120f, 240f, 30f, 60f).map { hsl((hue + it) % 360, s, l) }
        "monochromatic" -> listOf(0.2f, 0.35f, 0.5f, 0.65f, 0.8f).map { hsl(hue, s, it) }
        else -> listOf(0f, 180f, 30f, 150f, 60f).map { hsl((hue + it) % 360, s, l) }
    }

    return buildString {
        appendLine("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"500\" height=\"150\" viewBox=\"0 0 500 150\">")
        appendLine("<rect width=\"500\" height=\"150\" fill=\"#1e1e2e\" rx=\"8\"/>")
        appendLine("<text x=\"250\" y=\"25\" text-anchor=\"middle\" fill=\"#cdd6f4\" font-size=\"14\" font-weight=\"bold\">Color Scheme: $scheme (base: $baseHex)</text>")
        palette.forEachIndexed { i, c ->
            val x = 20 + i * 96
            appendLine("<rect x=\"$x\" y=\"40\" width=\"80\" height=\"60\" rx=\"8\" fill=\"$c\" stroke=\"#444\" stroke-width=\"1\"/>")
            appendLine("<text x=\"${x+40}\" y=\"118\" text-anchor=\"middle\" fill=\"#a6adc8\" font-size=\"11\">$c</text>")
        }
        appendLine("</svg>")
    }
}

/** 使用 Mermaid CDN 渲染的 HTML 图表页面 */
private fun generateMermaidHtml(type: String, title: String, desc: String): String = """
<!DOCTYPE html><html lang="zh"><head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title</title>
<script src="https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js"></script>
<script>mermaid.initialize({startOnLoad:true,theme:'dark',themeVariables:{primaryColor:'#4F46E5',primaryTextColor:'#fff',primaryBorderColor:'#6366f1',lineColor:'#a6adc8',secondaryColor:'#10B981',tertiaryColor:'#F59E0B'}})</script>
<style>body{margin:20px;background:#1e1e2e;color:#cdd6f4;font-family:sans-serif}
h2{text-align:center;color:#cdd6f4}
.mermaid{display:flex;justify-content:center;margin-top:20px}
</style></head><body>
<h2>$title</h2>
<div class="mermaid">
$desc
</div>
</body></html>
""".trimIndent()

/** 代码截图 SVG（carbon.now.sh 风格） */
private fun generateCodeScreenshotSvg(code: String, language: String, theme: String): String = buildString {
    val lines = code.lines().take(50)
    val maxLineLen = lines.maxOfOrNull { it.length } ?: 40
    val charW = 8; val lineH = 22
    val pad = 24; val headerH = 38
    val w = (maxLineLen * charW + pad * 2 + 20).coerceAtLeast(200)
    val h = lines.size * lineH + pad * 2 + headerH + 10
    val bg = if (theme == "light") "#ffffff" else "#1e1e2e"
    val textColor = if (theme == "light") "#1e1e2e" else "#cdd6f4"
    val lineNumColor = if (theme == "light") "#d4d4d4" else "#6c7086"
    val headerBg = if (theme == "light") "#f0f0f0" else "#181825"

    appendLine("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$w\" height=\"$h\" viewBox=\"0 0 $w $h\">")
    // 背景
    appendLine("<rect width=\"$w\" height=\"$h\" fill=\"$bg\" rx=\"12\"/>")
    // 窗口顶栏
    appendLine("<rect x=\"0\" y=\"0\" width=\"$w\" height=\"$headerH\" fill=\"$headerBg\" rx=\"12\"/>")
    appendLine("<rect x=\"0\" y=\"$headerH\" width=\"$w\" height=\"2\" fill=\"#313244\"/>")
    // 红绿灯圆点
    listOf("#ff5f56" to 14, "#ffbd2e" to 36, "#27c93f" to 58).forEach { (color, x) ->
        appendLine("<circle cx=\"$x\" cy=\"${headerH/2}\" r=\"5\" fill=\"$color\"/>")
    }
    // 标题
    val langLabel = language.ifBlank { "code" }
    appendLine("<text x=\"${w/2}\" y=\"${headerH/2 + 4}\" text-anchor=\"middle\" fill=\"$lineNumColor\" font-size=\"12\" font-family=\"monospace\">$langLabel</text>")
    // 行号 + 代码
    lines.forEachIndexed { i, line ->
        val y = headerH + pad + i * lineH + 14
        val displayLine = line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\t", "  ")
        // 行号
        appendLine("<text x=\"${pad}\" y=\"$y\" fill=\"$lineNumColor\" font-size=\"13\" font-family=\"monospace\" text-anchor=\"end\">${i + 1}</text>")
        // 代码
        appendLine("<text x=\"${pad + 30}\" y=\"$y\" fill=\"$textColor\" font-size=\"13\" font-family=\"monospace\">$displayLine</text>")
    }
    appendLine("</svg>")
}

private fun generateTimelineSvg(title: String, events: List<TimelineEvent>): String = buildString {
    val h = events.size * 80 + 80
    val w = 700
    appendLine("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$w\" height=\"$h\" viewBox=\"0 0 $w $h\">")
    appendLine("<rect width=\"100%\" height=\"100%\" fill=\"#1e1e2e\" rx=\"8\"/>")
    appendLine("<text x=\"${w/2}\" y=\"30\" text-anchor=\"middle\" fill=\"#cdd6f4\" font-size=\"16\" font-weight=\"bold\">${xml(title)}</text>")
    events.forEachIndexed { i, e ->
        val y = 60 + i * 80
        appendLine("<line x1=\"120\" y1=\"$y\" x2=\"120\" y2=\"${y+60}\" stroke=\"#4F46E5\" stroke-width=\"3\"/>")
        appendLine("<circle cx=\"120\" cy=\"${y+10}\" r=\"8\" fill=\"#4F46E5\" stroke=\"#1e1e2e\" stroke-width=\"3\"/>")
        appendLine("<text x=\"110\" y=\"${y+14}\" text-anchor=\"end\" fill=\"#a6adc8\" font-size=\"12\" font-weight=\"bold\">${xml(e.date)}</text>")
        appendLine("<text x=\"140\" y=\"${y+10}\" fill=\"#cdd6f4\" font-size=\"13\" font-weight=\"bold\">${xml(e.title)}</text>")
        if (e.description.isNotBlank()) {
            appendLine("<text x=\"140\" y=\"${y+30}\" fill=\"#6c7086\" font-size=\"11\">${xml(e.description.take(60))}</text>")
        }
    }
    appendLine("</svg>")
}

private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
private fun fmt(v: Double) = if (v >= 1000) "${"%.0f".format(v)}" else "${"%.1f".format(v)}"
