package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertFalse
import org.junit.Test

class DefaultProvidersTest {
    @Test
    fun `removed gateway presets no longer ship and are marked for cleanup`() {
        val names = DEFAULT_PROVIDERS.map { it.name }
        // 非官方网关预设全部下架，不再作为广告出现在默认列表
        assertFalse(names.contains("Vercel AI Gateway"))
        assertFalse(names.contains("OrcaRouter"))
        assertFalse(names.contains("302.AI"))
        assertFalse(names.contains("TokenPony"))
        assertFalse(names.contains("OpenRouter"))
        // 下架的 id 都登记在清理清单里，存量空 Key 条目会被规整逻辑移除
        assertFalse(REMOVED_PROVIDER_IDS.isEmpty())
    }
}
