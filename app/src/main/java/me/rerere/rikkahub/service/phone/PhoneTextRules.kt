package me.rerere.rikkahub.service.phone

/** 敏感动作 / 秘密输入词表：读屏打标与动作拦截共用一份 */
object PhoneWords {
    val confirm: List<String> = listOf(
        "发送", "发布", "提交", "支付", "付款", "购买", "下单", "转账", "红包",
        "删除", "清空", "卸载", "注销", "退出登录", "授权", "同意", "拨打",
    )

    val confirmIdWords: Set<String> = setOf(
        "send", "publish", "submit", "pay", "payment", "checkout", "purchase", "buy",
        "transfer", "redpacket", "hongbao", "delete", "uninstall", "logout", "signout",
        "unregister", "authorize", "agree", "dial",
    )

    private val confirmEnglish = (confirmIdWords + setOf("post", "clear", "call", "log out", "sign out"))
        .map { word -> word to wholeWords(word) }

    private fun wholeWords(word: String): Regex = Regex(
        "(?<![\\p{L}\\p{N}_])" + word.split(' ').joinToString("\\s+") { Regex.escape(it) } +
            "(?![\\p{L}\\p{N}_])",
        RegexOption.IGNORE_CASE,
    )

    private val otpHint = wholeWords("otp")
    private val oneTimeHint = wholeWords("one-time")

    val secret: List<String> = listOf(
        "支付密码", "密码", "验证码", "校验码", "动态码", "安全码", "cvv", "cvc",
        "password", "passcode", "verification code",
    )

    val secretIdWords: Set<String> = setOf(
        "password", "passwd", "pwd", "passcode", "otp", "cvv", "cvc", "smscode", "verifycode", "authcode",
    )

    /** 超过这个长度的标签当内容看，只在首尾命中才判敏感 */
    const val SHORT_LABEL = 24

    fun confirmHit(label: String?, viewId: String?): String? {
        val text = label?.trim().orEmpty()
        if (text.isNotEmpty()) {
            for (word in confirm) {
                if (!text.contains(word)) continue
                if (text.length <= SHORT_LABEL || text.startsWith(word) || text.endsWith(word)) return word
            }
            for ((word, pattern) in confirmEnglish) {
                for (match in pattern.findAll(text)) {
                    if (text.length <= SHORT_LABEL || match.range.first == 0 ||
                        match.range.last == text.lastIndex) {
                        return word
                    }
                }
            }
        }
        return idWords(viewId).firstOrNull { it in confirmIdWords }
    }

    fun secretHit(hint: String?, description: String?, viewId: String?): String? {
        for (value in listOf(hint, description)) {
            val text = value?.lowercase() ?: continue
            secret.firstOrNull { text.contains(it) }?.let { return it }
            if (otpHint.containsMatchIn(text)) return "otp"
            if (oneTimeHint.containsMatchIn(text)) return "one-time"
        }
        return idWords(viewId).firstOrNull { it in secretIdWords }
    }

    fun idWords(viewId: String?): List<String> {
        if (viewId.isNullOrBlank()) return emptyList()
        val tail = viewId.substringAfterLast(":id/").substringAfterLast('/')
        val words = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                words.add(current.toString().lowercase())
                current.setLength(0)
            }
        }
        for ((index, char) in tail.withIndex()) {
            when {
                !char.isLetterOrDigit() -> flush()
                char.isUpperCase() && index > 0 && tail[index - 1].isLowerCase() -> {
                    flush()
                    current.append(char)
                }
                else -> current.append(char)
            }
        }
        flush()
        return words + words.zipWithNext { a, b -> a + b }
    }
}

/** 银行卡 / 身份证在离开手机前就被掩码 */
object PhoneMask {
    private val idNumber =
        Regex(
            "(?<![0-9A-Za-z])[1-9][0-9]{5}(?:19|20)[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9Xx](?![0-9A-Za-z])"
        )
    private val cardNumber = Regex("(?<![0-9A-Za-z])[0-9](?:[ -]?[0-9]){14,18}(?![0-9A-Za-z])")
    private val notCardContext = listOf("订单", "单号", "运单", "物流", "快递", "编号", "流水", "交易号", "条码", "序列号", "No.", "NO.")

    fun mask(text: String): String {
        if (text.length < 15 || text.count { it.isDigit() } < 15) return text
        val ids = idNumber.replace(text) { m ->
            if (idChecksumOk(m.value)) "«身份证 ****${m.value.takeLast(4).uppercase()}»" else m.value
        }
        return cardNumber.replace(ids) { m ->
            val digits = m.value.filter { it.isDigit() }
            val before = ids.substring(maxOf(0, m.range.first - 8), m.range.first)
            if (digits.length in 15..19 && luhnOk(digits) && notCardContext.none { before.contains(it) }) {
                "«银行卡 ****${digits.takeLast(4)}»"
            } else {
                m.value
            }
        }
    }

    fun luhnOk(digits: String): Boolean {
        var sum = 0
        for ((i, c) in digits.reversed().withIndex()) {
            var d = c - '0'
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }

    fun idChecksumOk(id: String): Boolean {
        if (id.length != 18) return false
        val weights = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
        var sum = 0
        for (i in 0 until 17) sum += (id[i] - '0') * weights[i]
        return id[17].uppercaseChar() == "10X98765432"[sum % 11]
    }
}
