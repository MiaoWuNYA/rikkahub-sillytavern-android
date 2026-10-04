package me.rerere.rikkahub.data.export

import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 酒馆 Chat Completion 预设导入：未启用条目必须保留（导入为禁用状态），不能丢弃。
 *
 * issue #5：导入后「多选一」的备选项整个消失——不是变成禁用，是根本不存在，
 * 用户再也找不回来。原因是 tryImportPresetOrdered 里 `if (!entry.enabled) return null`
 * 直接过滤，外加写入时 enabled = true 写死了原状态。
 *
 * 本地注入逻辑本来就尊重 enabled（PromptInjectionTransformer 有 `if (!entry.enabled) continue`），
 * 所以保留条目是安全的：不启用就不会被注入，与酒馆行为一致。
 */
class PresetImportEnabledTest {

    private val source: String by lazy {
        listOf(
            File("src/main/java/me/rerere/rikkahub/data/export/ExportSerializer.kt"),
            File("app/src/main/java/me/rerere/rikkahub/data/export/ExportSerializer.kt"),
        ).firstOrNull { it.exists() }?.readText().orEmpty()
    }

    @Test
    fun `disabled order entries are not filtered out`() {
        if (source.isEmpty()) fail("找不到 ExportSerializer.kt")
        if (source.contains("if (!entry.enabled) return@mapIndexedNotNull null")) {
            fail("未启用条目仍被直接丢弃——这正是 issue #5：备选项会整个消失而不是变成禁用")
        }
    }

    @Test
    fun `missing enabled field defaults to enabled`() {
        if (source.isEmpty()) fail("找不到 ExportSerializer.kt")
        // 酒馆语义：order 项缺 enabled 字段视为启用。默认 false 会把正常条目当禁用
        if (!source.contains("val enabled: Boolean = true")) {
            fail("StOrderEntry.enabled 默认值应为 true——酒馆缺省视为启用，默认 false 会误伤正常预设")
        }
    }
}
