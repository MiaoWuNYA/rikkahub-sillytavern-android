package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 版本 31 -> 32（合并上游翻译历史）：
 * 新增 translation_history 表，列与 TranslationHistoryEntity 的声明保持一致。
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
    }
}
