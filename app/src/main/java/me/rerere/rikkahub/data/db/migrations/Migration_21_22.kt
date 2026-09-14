package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 从版本21升级到22：知识库加 tags 和 parent_chunk_id 字段
 */
val Migration_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addColumnIfNotExists("knowledge_sources", "tags", "TEXT NOT NULL DEFAULT ''")
        db.addColumnIfNotExists("knowledge_chunks", "parent_chunk_id", "TEXT")
        // upstream 版本 v22 通过 AutoMigration(from=21, to=22) 自动添加了 workspace_cwd
        db.addColumnIfNotExists("ConversationEntity", "workspace_cwd", "TEXT NOT NULL DEFAULT ''")
    }
}
