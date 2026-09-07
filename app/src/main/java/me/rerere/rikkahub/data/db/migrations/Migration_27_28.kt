package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 从版本27升级到28：ConversationEntity 新增滚动压缩摘要列（移植自 Rikkahub-Revised）
 * - rolling_context_summary: RollingContextSummary 的 JSON 序列化，空串表示无摘要
 */
val Migration_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `ConversationEntity` ADD COLUMN `rolling_context_summary` TEXT NOT NULL DEFAULT ''")
    }
}
