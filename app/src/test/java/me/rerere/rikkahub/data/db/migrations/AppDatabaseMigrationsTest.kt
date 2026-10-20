package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import me.rerere.rikkahub.data.db.AppDatabaseSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迁移链完整性。
 *
 * 导入旧版本备份时报 "A migration from X to Y was required but not found"，根因永远是链上少了一段。
 * 这份测试把「任意旧版本都能走到最新版」钉在源码层面：只要有人新增版本却忘记补迁移，
 * 或者两处 builder 的链再次不同步，这里就会红。
 */
class AppDatabaseMigrationsTest {

    @Test
    fun `每个历史版本都能走到当前版本`() {
        val target = AppDatabaseSchema.VERSION
        for (from in AppDatabaseMigrations.FIRST_VERSION until target) {
            val path = AppDatabaseMigrations.path(from, target)
            assertNotNull("v$from 到 v$target 的迁移链缺失", path)
            assertPathIsContinuous(from, target, path!!)
        }
    }

    @Test
    fun `同版本不需要迁移`() {
        val version = AppDatabaseSchema.VERSION
        assertEquals(emptyList<Migration>(), AppDatabaseMigrations.path(version, version))
    }

    @Test
    fun `降级不返回迁移路径`() {
        val target = AppDatabaseSchema.VERSION
        assertNull(AppDatabaseMigrations.path(target + 1, target))
    }

    @Test
    fun `手写段与自动迁移段不重叠`() {
        val handwritten = AppDatabaseMigrations.HANDWRITTEN.map { it.startVersion }.toSet()
        val legacy = AppDatabaseLegacyMigrations.STEPS.map { it.from }
        val overlap = legacy.filter { it in handwritten }
        assertTrue("这些版本对同时有手写迁移和自动迁移段：$overlap", overlap.isEmpty())
    }

    @Test
    fun `自动迁移段每段都是一步`() {
        for (step in AppDatabaseLegacyMigrations.STEPS) {
            assertEquals("${step.from} → ${step.to} 不是相邻版本", step.from + 1, step.to)
        }
    }

    @Test
    fun `手写段与自动迁移段合起来覆盖 v1 到 v20`() {
        val covered = (
            AppDatabaseMigrations.HANDWRITTEN.map { it.startVersion } +
                AppDatabaseLegacyMigrations.STEPS.map { it.from }
            ).toSet()
        for (version in AppDatabaseMigrations.FIRST_VERSION until 20) {
            assertTrue("v$version → v${version + 1} 没有任何迁移实现", version in covered)
        }
    }

    @Test
    fun `手写段覆盖到当前版本`() {
        val target = AppDatabaseSchema.VERSION
        val covered = AppDatabaseMigrations.HANDWRITTEN.filter { it.startVersion >= 20 }
        var current = 20
        for (migration in covered.sortedBy { it.startVersion }) {
            assertEquals("手写段在 v$current 处断裂", current, migration.startVersion)
            current = migration.endVersion
        }
        assertEquals("手写段没有覆盖到当前版本", target, current)
    }

    /**
     * AppDatabaseSchema.kt 是 tools/generate_db_schema.py 从 schema JSON 生成的。
     * 改了 @Database 的版本却忘了重新生成，恢复流程就会拿旧结构去对齐，这里把它挡住。
     */
    @Test
    fun `生成物版本与 Database 声明一致`() {
        val source = readSource("src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt")
        val declared = Regex("""version\s*=\s*(\d+)""").find(source)?.groupValues?.get(1)?.toInt()
        assertNotNull("@Database 里找不到 version 声明", declared)
        assertEquals("AppDatabaseSchema.kt 没跟上 @Database 的版本", declared, AppDatabaseSchema.VERSION)
    }

    /**
     * 链尾迁移同时是「上游库收敛点」：迁移链按版本号推进，覆盖不了上游 rikkahub
     * 同版本号、不同结构的库（上游 v31 有 config 却没有本地自研的 rolling_context_summary
     * 与情侣空间表）。新增版本时忘了在链尾对齐结构，上游用户升级就会崩在 Room 的校验上。
     */
    @Test
    fun `链尾迁移做结构收敛`() {
        val target = AppDatabaseSchema.VERSION
        val source = readSource("src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_${target - 1}_$target.kt")
        assertTrue(
            "链尾迁移 Migration_${target - 1}_$target 没有调用 DatabaseSchemaReconciler.reconcile",
            source.contains("DatabaseSchemaReconciler.reconcile"),
        )
    }

    /**
     * 迁移里的裸 ADD COLUMN 会在「库里已经有该列」时 duplicate column 崩库。
     * 上游 rikkahub 同版本号却已含 config 列，就是这么炸的（Migration_30_31），
     * 所以每条 ADD COLUMN 前必须有 hasColumn / addColumnIfNotExists 守卫。
     * AppDatabaseLegacyMigrations 是 v1→v20 的结构 diff 数据，上游同版本一致，不在检查范围。
     */
    @Test
    fun `迁移里的 ADD COLUMN 都有幂等守卫`() {
        val directory = resolveSource("src/main/java/me/rerere/rikkahub/data/db/migrations")
        val offenders = mutableListOf<String>()
        val files = directory.listFiles { file -> file.name.startsWith("Migration_") && file.name.endsWith(".kt") }
        for (file in files.orEmpty()) {
            val code = file.readLines().map { line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) "" else line
            }
            code.forEachIndexed { index, line ->
                if (!line.uppercase().contains("ADD COLUMN")) return@forEachIndexed
                val window = code.subList(maxOf(0, index - GUARD_WINDOW), index + 1).joinToString("\n")
                if (window.contains("addColumnIfNotExists(") || window.contains("hasColumn(")) return@forEachIndexed
                offenders += "${file.name}:${index + 1}"
            }
        }
        assertTrue("这些 ADD COLUMN 缺少幂等守卫：$offenders", offenders.isEmpty())
    }

    private fun readSource(relative: String): String = resolveSource(relative).readText()

    private fun resolveSource(relative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val roots = listOf(cwd, cwd.parentFile, cwd.parentFile?.parentFile).filterNotNull()
        for (root in roots) {
            for (candidate in listOf(File(root, relative), File(root, "app/$relative"))) {
                if (candidate.exists()) return candidate
            }
        }
        error("找不到源码文件 $relative")
    }

    private companion object {
        /** 守卫可以写在若干行之前（先 if (!hasColumn(...)) 再 ALTER） */
        const val GUARD_WINDOW = 15
    }

    private fun assertPathIsContinuous(from: Int, to: Int, path: List<Migration>) {
        var current = from
        for (migration in path) {
            assertEquals("v$from → v$to 的链在 v$current 处断裂", current, migration.startVersion)
            current = migration.endVersion
        }
        assertEquals("v$from → v$to 的链没有走到目标版本", to, current)
    }
}
