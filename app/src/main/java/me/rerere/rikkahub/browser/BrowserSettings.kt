package me.rerere.rikkahub.browser

import kotlinx.serialization.Serializable

@Serializable
enum class SnapshotDetail {
    COMPACT,
    STANDARD,
    DETAILED;

    val maxElements: Int
        get() = when (this) {
            COMPACT -> 60
            STANDARD -> 120
            DETAILED -> 200
        }

    val textBudget: Int
        get() = when (this) {
            COMPACT -> 600
            STANDARD -> 1200
            DETAILED -> 2400
        }
}

@Serializable
data class BrowserSettings(
    val enabled: Boolean = true,
    val allowOpenClose: Boolean = true,
    val allowScreenshot: Boolean = true,
    val allowFullPageScreenshot: Boolean = false,
    val allowFormSubmit: Boolean = false,
    val allowEval: Boolean = false,
    val blockSensitiveScreenshots: Boolean = true,
    val domainAllowList: String = "",
    val domainBlockList: String = "",
    val snapshotDetail: SnapshotDetail = SnapshotDetail.STANDARD,
    val idleTimeoutMinutes: Int = 10,
    val showToolLog: Boolean = true,
    val keepScreenshots: Int = 20,
    val taobaoRulesEnabled: Boolean = false,
) {
    val allowDomains: List<String>
        get() = splitDomains(domainAllowList)

    val blockDomains: List<String>
        get() = splitDomains(domainBlockList)

    fun normalized(): BrowserSettings = copy(
        idleTimeoutMinutes = idleTimeoutMinutes.coerceIn(0, 24 * 60),
        keepScreenshots = keepScreenshots.coerceIn(0, 200),
    )

    /** null = 放行；否则返回被拒绝的原因码。 */
    fun checkUrl(url: String): String? {
        val host = hostOf(url).lowercase()
        if (host.isEmpty()) return null
        if (blockDomains.any { host.matchesDomain(it) }) return BrowserError.DISABLED
        if (allowDomains.isNotEmpty() && allowDomains.none { host.matchesDomain(it) }) return BrowserError.DISABLED
        return null
    }

    private fun splitDomains(raw: String): List<String> =
        raw.split(',', '\n', ' ', ';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
}

private fun hostOf(url: String): String = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")

private fun String.matchesDomain(rule: String): Boolean {
    val normalized = rule.removePrefix("*.").removePrefix(".")
    return this == normalized || endsWith(".$normalized")
}
