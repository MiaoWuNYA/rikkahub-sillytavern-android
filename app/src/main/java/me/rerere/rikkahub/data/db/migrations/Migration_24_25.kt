package me.rerere.rikkahub.data.db.migrations

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 从版本24升级到25：修复旧版 Migration_21_22 遗漏的 workspace_cwd 列
 *
 * 旧版 Migration_21_22 (919f324dc) 只加了 knowledge_sources.tags 和
 * knowledge_chunks.parent_chunk_id，但忘记为 ConversationEntity 添加
 * workspace_cwd。后续的 d7e87e3db 修复了 Migration_21_22 源码，但已迁移到
 * v24 的数据库不会再重跑 21→22 迁移。
 *
 * 此迁移检查 ConversationEntity 表是否有 workspace_cwd 列，若缺少则添加。
 */
val Migration_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 检查 workspace_cwd 列是否已存在
        if (!hasColumn(db, "ConversationEntity", "workspace_cwd")) {
            db.execSQL(
                "ALTER TABLE `ConversationEntity` ADD COLUMN `workspace_cwd` TEXT NOT NULL DEFAULT ''"
            )
        }
    }

    private fun hasColumn(db: SupportSQLiteDatabase, tableName: String, columnName: String): Boolean {
        val cursor: Cursor = db.query("PRAGMA table_info('$tableName')")
        cursor.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == columnName) {
                    return true
                }
            }
        }
        return false
    }
}
