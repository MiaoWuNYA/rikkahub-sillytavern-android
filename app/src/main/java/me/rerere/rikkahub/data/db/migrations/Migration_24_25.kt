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
 * 此迁移检查 ConversationEntity 表是否有 workspace_cwd 列，以及
 * conversation_folder 表是否存在，若缺少则修复。
 */
val Migration_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 检查 workspace_cwd 列是否已存在
        if (!hasColumn(db, "ConversationEntity", "workspace_cwd")) {
            db.execSQL(
                "ALTER TABLE `ConversationEntity` ADD COLUMN `workspace_cwd` TEXT NOT NULL DEFAULT ''"
            )
        }

        // 检查 conversation_folder 表是否已存在（旧版 Migration_23_24 建的是 folders 不是 conversation_folder）
        if (!hasTable(db, "conversation_folder")) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `conversation_folder` (
                    `id` TEXT NOT NULL,
                    `assistant_id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `sort_index` INTEGER NOT NULL DEFAULT 0,
                    `create_at` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_folder_assistant_id` ON `conversation_folder`(`assistant_id`)")
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

    private fun hasTable(db: SupportSQLiteDatabase, tableName: String): Boolean {
        val cursor: Cursor = db.query(
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(tableName)
        )
        cursor.use { c ->
            if (c.moveToFirst()) {
                return c.getInt(0) > 0
            }
        }
        return false
    }
}
