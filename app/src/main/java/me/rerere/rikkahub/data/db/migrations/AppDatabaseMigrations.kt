package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 迁移链的唯一来源，覆盖 v1 → 当前版本的全部区间。
 *
 * [HANDWRITTEN] 是带数据语义的手写迁移，Room builder 注册的就是它；1 → 20 这一段由
 * `@Database` 的 autoMigrations 生成代码负责，Room 自己会处理。
 *
 * 备份恢复不走 Room 的迁移容器：容器内容由 addMigrations 决定，只要有一处漏注册，
 * 导入旧版本备份就会整体失败（"A migration from X to Y was required but not found"，
 * 历史上 24→30、31→32 都漏过）。恢复路径关心的是「备份库能不能变成当前结构」，
 * 所以用 [path] 显式取链：手写段 + [AppDatabaseLegacyMigrations] 固化的 1→20 结构变更，
 * 任意旧版本都能升到最新版。
 */
internal object AppDatabaseMigrations {
    /** 手写迁移段，Room builder 与显式升级器共用同一份。 */
    val HANDWRITTEN: Array<Migration> = arrayOf(
        Migration_6_7,
        Migration_11_12,
        Migration_13_14,
        Migration_14_15,
        Migration_15_16,
        Migration_20_21,
        Migration_21_22,
        Migration_22_23,
        Migration_23_24,
        Migration_24_25,
        Migration_25_26,
        Migration_26_27,
        Migration_27_28,
        Migration_28_29,
        Migration_29_30,
        Migration_30_31,
        Migration_31_32,
        Migration_32_33,
    )

    /** 显式链能覆盖的最低版本。 */
    const val FIRST_VERSION: Int = 1

    private val handwritten: List<Migration> = HANDWRITTEN.toList()

    private val legacy: List<Migration> = AppDatabaseLegacyMigrations.STEPS.map { step ->
        object : Migration(step.from, step.to) {
            override fun migrate(db: SupportSQLiteDatabase) {
                step.statements.forEach(db::execSQL)
            }
        }
    }

    private val explicit: List<Migration> = handwritten + legacy

    /**
     * 从 [from] 升到 [to] 需要依次执行的迁移；链上有缺口时返回 null。
     * 同一段同时存在手写实现和 legacy 实现时优先用手写实现，数据语义更完整。
     */
    fun path(from: Int, to: Int): List<Migration>? {
        if (from == to) return emptyList()
        if (from > to) return null
        val result = mutableListOf<Migration>()
        var current = from
        while (current < to) {
            val next = explicit
                .filter { it.startVersion == current && it.endVersion <= to }
                .minWithOrNull(
                    compareBy<Migration>(
                        { it.endVersion },
                        { if (handwritten.any { candidate -> candidate === it }) 0 else 1 },
                    )
                )
                ?: return null
            result += next
            current = next.endVersion
        }
        return result
    }
}
