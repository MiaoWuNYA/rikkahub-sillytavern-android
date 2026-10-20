package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseSchemaReconciler

/**
 * 版本 31 -> 32（合并上游翻译历史）：
 * 新增 translation_history 表，列与 TranslationHistoryEntity 的声明保持一致。
 *
 * 这段是当前链尾，所以顺带做一次结构收敛。迁移链按版本号推进，覆盖不了「上游 rikkahub
 * 同版本号、不同结构」的库：上游 v31 有 config 却没有本仓库自研的 rolling_context_summary
 * 和情侣空间表，直接交给 Room 校验只会崩在启动。对齐只补缺表缺列、删当前 schema 里多余的
 * 列，不重建已有表。
 */
object Migration_31_32 : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `translation_history` (" +
                "`id` TEXT NOT NULL, " +
                "`source_text` TEXT NOT NULL, " +
                "`translated_text` TEXT NOT NULL, " +
                "`target_language` TEXT NOT NULL, " +
                "`model_id` TEXT NOT NULL, " +
                "`create_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_translation_history_create_at` " +
                "ON `translation_history` (`create_at`)"
        )
        DatabaseSchemaReconciler.reconcile(db)
    }
}
