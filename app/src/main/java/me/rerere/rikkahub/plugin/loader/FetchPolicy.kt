package me.rerere.rikkahub.plugin.loader

import java.net.URL

/**
 * 插件 fetch 域名白名单判定（纯逻辑，便于 JVM 单元测试）
 *
 * 规则（与 Tumin 沙箱一致）：
 * - 白名单为空：禁止所有网络请求
 * - 白名单包含 "*"：允许所有域名
 * - 其余情况：host 必须与白名单某项完全相等，或是其子域（host.endsWith(".allowed")）
 */
object FetchPolicy {

    /**
     * 判断目标 host 是否在白名单内
     */
    fun isHostAllowed(host: String, allowedHosts: List<String>): Boolean {
        if (allowedHosts.isEmpty()) return false
        if (allowedHosts.contains("*")) return true
        val normalized = host.trim().lowercase().trimEnd('.')
        if (normalized.isEmpty()) return false
        return allowedHosts.any { allowed ->
            val a = allowed.trim().lowercase().trimEnd('.')
            if (a.isEmpty()) return@any false
            normalized == a || normalized.endsWith(".$a")
        }
    }

    /**
     * 从 URL 中提取 host 并判定是否放行；
     * URL 非法时视为不允许（fail-closed）
     */
    fun isUrlAllowed(url: String, allowedHosts: List<String>): Boolean {
        return try {
            isHostAllowed(URL(url).host, allowedHosts)
        } catch (_: Exception) {
            false
        }
    }
}
