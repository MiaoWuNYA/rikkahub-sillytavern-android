package me.rerere.rikkahub.data.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 极简 CSS 计算引擎：把酒馆主题的 custom_css 真正"算"出来，而不是正则猜。
 *
 * 官方对齐的关键：酒馆导入主题时**完全不解析** custom_css（power-user.js:1148
 * `style.innerHTML = power_user.custom_css;`），而是直接塞进浏览器的 `<style>`，
 * 由浏览器按标准级联作用到官方 DOM（`.mes` / `.mes_block` / `.avatar` / `#send_but` …）。
 * 因此主题作者可以任意使用层叠、优先级、`!important`、CSS 变量、简写属性。
 *
 * Compose 没有 CSS 引擎，要"完全对齐"就必须自己实现一个够用的子集：
 * 1. `resolveVariables` —— 展开 `--x: v` 与 `var(--x, fallback)`（含 :root/documentElement 注入）
 * 2. `cascade` —— 按 选择器特异度 + 出现顺序 + !important 定胜负，得到元素最终声明
 * 3. 把结果翻译成 Compose 能用的属性（颜色/尺寸）
 *
 * 这是把"猜"换成"算"的地基：后续任何主题特性（圆角、边框、阴影、字号、内边距）
 * 都从同一份计算声明里取值，不再各自写正则，从根上消除"映射关系都是乱的"。
 */

/** 一条已解析的声明 */
data class CssDeclaration(
    val property: String,
    val value: String,
    val important: Boolean,
)

/** 一条规则（已展开变量） */
data class CssRule(
    val selector: String,
    val declarations: List<CssDeclaration>,
    /** 出现顺序，越大越晚出现（同特异度时后者胜） */
    val order: Int,
)

/** 变量表：`--name` → 值 */
typealias CssVariables = Map<String, String>

/**
 * 解析 `--name: value` 形式的自定义属性。
 *
 * 酒馆把主题的颜色写成 `:root` 或 `documentElement` 上的变量，主题的其余规则
 * 再用 `var(--x)` 引用。不展开变量就会把 `var(--bar)` 这种字面量当颜色，
 * 解析失败后整条规则被丢弃——实测 138 处 border-radius 与大量颜色都栽在这。
 */
fun parseCssVariables(css: String): CssVariables {
    val clean = stripCssComments(css)
    val vars = LinkedHashMap<String, String>()
    for (m in CSS_RULE.findAll(clean)) {
        // 只认 :root / html / * / documentElement 上的变量定义
        val selector = m.groupValues[1].trim()
        if (!isVariableScope(selector)) continue
        for (decl in splitDeclarations(m.groupValues[2])) {
            val idx = decl.indexOf(':')
            if (idx <= 0) continue
            val name = decl.substring(0, idx).trim()
            if (!name.startsWith("--")) continue
            vars[name] = decl.substring(idx + 1).trim()
        }
    }
    return vars
}

private fun isVariableScope(selector: String): Boolean {
    val s = selector.trim().lowercase()
    if (s.isEmpty()) return false
    // :root / html / body / * 以及它们的组合（如 ":root, html"）
    return s.split(',').all { part ->
        val p = part.trim().removePrefix(":root").removePrefix("html").removePrefix("body").removePrefix("*").trim()
        p.isEmpty() || p == "," || p.startsWith(":") && p.length == 1
    } && s.split(',').any { part ->
        val p = part.trim()
        p == ":root" || p == "html" || p == "body" || p == "*" ||
            p.startsWith(":root") || p.startsWith("html") || p.startsWith("body")
    }
}

/**
 * 展开 `var(--x)` / `var(--x, fallback)`，最多递归 [depth] 层（CSS 变量可互相引用）。
 */
fun expandVariables(value: String, vars: CssVariables, depth: Int = 6): String {
    if (!value.contains("var(")) return value
    if (depth <= 0) return value
    val sb = StringBuilder()
    var i = 0
    while (i < value.length) {
        val start = value.indexOf("var(", i)
        if (start < 0) {
            sb.append(value, i, value.length)
            break
        }
        sb.append(value, i, start)
        // 找到配对右括号
        var depthCount = 1
        var j = start + 4
        while (j < value.length && depthCount > 0) {
            when (value[j]) {
                '(' -> depthCount++
                ')' -> depthCount--
            }
            j++
        }
        if (depthCount != 0) {
            // 括号不配对，原样保留剩余部分
            sb.append(value, start, value.length)
            break
        }
        val inner = value.substring(start + 4, j - 1)
        val comma = indexOfTopLevelComma(inner)
        val name = (if (comma >= 0) inner.substring(0, comma) else inner).trim()
        val fallback = if (comma >= 0) inner.substring(comma + 1).trim() else null
        val resolved = vars[name] ?: fallback
        if (resolved == null) {
            // 变量未定义且无回退 —— 整条声明在 CSS 里会失效，保留原样供上层判定
            sb.append(value, start, j)
        } else {
            sb.append(expandVariables(resolved, vars, depth - 1))
        }
        i = j
    }
    return sb.toString()
}

private fun indexOfTopLevelComma(s: String): Int {
    var depth = 0
    for (k in s.indices) {
        when (s[k]) {
            '(' -> depth++
            ')' -> depth--
            ',' -> if (depth == 0) return k
        }
    }
    return -1
}

/** 按 `;` 切声明，跳过括号内的分号（如 `url(a;b)`） */
internal fun splitDeclarations(body: String): List<String> {
    val out = mutableListOf<String>()
    var depth = 0
    val sb = StringBuilder()
    for (ch in body) {
        when (ch) {
            '(' -> { depth++; sb.append(ch) }
            ')' -> { depth--; sb.append(ch) }
            ';' -> if (depth == 0) { out += sb.toString(); sb.clear() } else sb.append(ch)
            else -> sb.append(ch)
        }
    }
    if (sb.isNotBlank()) out += sb.toString()
    return out.filter { it.isNotBlank() }
}

/**
 * 解析出所有规则，并展开其中的 CSS 变量。
 *
 * @param extraVars 主题字段注入的变量（如 `--SmartThemeBodyColor`），优先级低于
 *                  custom_css 自身在 `:root` 上的定义——与官方一致：
 *                  官方先 setProperty 再插入 custom-style，同特异度后者（custom_css）胜。
 */
fun parseCssRules(css: String, extraVars: CssVariables = emptyMap()): List<CssRule> {
    val clean = stripCssComments(css)
    // custom_css 自己的 :root 变量覆盖字段注入的变量
    val ownVars = parseCssVariables(clean)
    val vars: CssVariables = extraVars + ownVars

    val rules = mutableListOf<CssRule>()
    var order = 0
    for (m in CSS_RULE.findAll(clean)) {
        val selector = m.groupValues[1].trim().replace(Regex("""\s+"""), " ")
        if (selector.isEmpty() || selector.startsWith("@")) continue
        val decls = splitDeclarations(m.groupValues[2]).mapNotNull { decl ->
            val idx = decl.indexOf(':')
            if (idx <= 0) return@mapNotNull null
            val prop = decl.substring(0, idx).trim().lowercase()
            if (prop.startsWith("--")) return@mapNotNull null
            var v = decl.substring(idx + 1).trim()
            var important = false
            if (v.contains("!important", ignoreCase = true)) {
                important = true
                v = v.replace(Regex("""!important""", RegexOption.IGNORE_CASE), "").trim()
            }
            CssDeclaration(prop, expandVariables(v, vars).trim(), important)
        }
        if (decls.isEmpty()) continue
        rules += CssRule(selector, decls, order++)
    }
    return rules
}

/**
 * 选择器特异度（a,b,c）=（id 数, class/属性/伪类 数, 元素/伪元素 数）。
 * 用于让层叠结果与浏览器一致——这正是"主题里明明写了圆角却不生效"的根因。
 */
fun selectorSpecificity(selector: String): Int {
    var a = 0; var b = 0; var c = 0
    // 去掉伪元素后的部分不参与元素计数之外的影响，这里按标准近似
    val s = selector
    a += Regex("""#[\w-]+""").findAll(s).count()
    b += Regex("""\.[\w-]+""").findAll(s).count()
    b += Regex("""\[[^\]]*]""").findAll(s).count()
    b += Regex(""":(?!:)[\w-]+""").findAll(s).count()
    // 伪元素 ::before / ::after
    c += Regex("""::[\w-]+""").findAll(s).count()
    // 元素名：排除属性/类/id/伪之后剩下的标识符
    val stripped = s
        .replace(Regex("""#[\w-]+"""), " ")
        .replace(Regex("""\.[\w-]+"""), " ")
        .replace(Regex("""\[[^\]]*]"""), " ")
        .replace(Regex(""":{1,2}[\w-]+"""), " ")
        .replace(Regex("""[*>+~,\s]+"""), " ")
    c += stripped.split(' ').count { it.isNotBlank() }
    return a * 10000 + b * 100 + c
}

/**
 * 计算某个元素（由 [matchesSelector] 判定是否命中）的最终声明。
 *
 * 层叠顺序：`!important` > 特异度 > 出现顺序（后出现者胜）。
 * 返回已合并的 `属性 -> 值`。
 */
fun cascadeDeclarations(
    rules: List<CssRule>,
    matchesSelector: (String) -> Boolean,
): Map<String, String> {
    data class Winner(val spec: Int, val order: Int, val value: String, val important: Boolean)

    val winners = HashMap<String, Winner>()
    for (rule in rules) {
        if (!matchesSelector(rule.selector)) continue
        val spec = selectorSpecificity(rule.selector)
        for (decl in rule.declarations) {
            val prev = winners[decl.property]
            val better = prev == null ||
                (decl.important && !prev.important) ||
                (decl.important == prev.important && (
                    spec > prev.spec || (spec == prev.spec && rule.order >= prev.order)
                    ))
            if (better) {
                winners[decl.property] = Winner(spec, rule.order, decl.value, decl.important)
            }
        }
    }
    return winners.mapValues { it.value.value }
}

/**
 * 把已计算声明里的长度值解析为 Dp。
 * 支持 `12px` / `1.2rem` / `1.2em`（按 16px 基准） / `50%`（交给调用方处理）。
 */
fun cssLengthToDp(value: String?, percentBase: Dp? = null): Dp? {
    if (value.isNullOrBlank()) return null
    val v = value.trim()
    v.toFloatOrNull()?.let { return it.dp }
    Regex("""^([\d.]+)px$""").find(v)?.groupValues?.get(1)?.toFloatOrNull()?.let { return it.dp }
    Regex("""^([\d.]+)(?:rem|em)$""").find(v)?.groupValues?.get(1)?.toFloatOrNull()?.let { return (it * 16f).dp }
    if (v.endsWith("%")) {
        val pct = v.dropLast(1).toFloatOrNull() ?: return null
        return percentBase?.let { it * (pct / 100f) }
    }
    return null
}

/** 从已计算声明里取颜色（含 `var()` 已展开的 rgba/rgb/hex） */
fun cssColorFrom(declarations: Map<String, String>, vararg properties: String): Long? {
    for (p in properties) {
        val raw = declarations[p] ?: continue
        if (raw.equals("none", ignoreCase = true)) return null
        if (raw.equals("transparent", ignoreCase = true)) continue
        parseCssColor(raw)?.let { return it }
    }
    return null
}

/** 把 ARGB Long 转 Compose Color */
fun Long.toComposeColor(): Color = Color(this.toInt())
