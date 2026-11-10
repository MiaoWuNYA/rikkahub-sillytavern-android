package me.rerere.rikkahub.data.st.script

// 最小等价实现，待 data/ai/transformers 正式版补齐后替换
object StVectorCompat {

    @Volatile
    var vectorThreshold: Double = 0.35

    @Volatile
    var vectorMaxEntries: Int = 5

    @Volatile
    var vectorWorldInfoEnabled: Boolean = true

    // 二元/词袋余弦相似度（[0.0, 1.0]），严格防止除零
    fun computeVectorSimilarity(queryText: String, docText: String): Double {
        if (queryText.isBlank() || docText.isBlank()) return 0.0
        fun tokenize(s: String): Map<String, Int> {
            val lower = s.lowercase()
            val counts = HashMap<String, Int>()
            Regex("[a-z0-9_]{2,}").findAll(lower).forEach { m ->
                val tok = m.value
                counts[tok] = (counts[tok] ?: 0) + 1
            }
            val cjk = lower.filter { it.code in 0x4E00..0x9FFF }
            for (i in cjk.indices) {
                val ch = cjk[i].toString()
                counts[ch] = (counts[ch] ?: 0) + 1
                if (i + 1 < cjk.length) {
                    val bg = cjk.substring(i, i + 2)
                    counts[bg] = (counts[bg] ?: 0) + 1
                }
            }
            return counts
        }
        val qVec = tokenize(queryText)
        val dVec = tokenize(docText)
        if (qVec.isEmpty() || dVec.isEmpty()) return 0.0
        var dot = 0.0
        var normQ = 0.0
        var normD = 0.0
        for ((k, qv) in qVec) {
            val qd = qv.toDouble()
            normQ += qd * qd
            val dv = dVec[k]
            if (dv != null) {
                dot += qd * dv.toDouble()
            }
        }
        for (dv in dVec.values) {
            val dd = dv.toDouble()
            normD += dd * dd
        }
        val denom = kotlin.math.sqrt(normQ) * kotlin.math.sqrt(normD)
        return if (denom <= 1e-12) 0.0 else (dot / denom).coerceIn(0.0, 1.0)
    }
}
