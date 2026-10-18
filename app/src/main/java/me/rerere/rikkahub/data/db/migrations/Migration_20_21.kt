package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 从版本20升级到21：添加知识库表 + FTS5，以及 workspaces 表
 */
val Migration_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // workspaces 表：上游 v21 通过 AutoMigration(20,21) 建出来的，手写迁移漏了这一步，
        // 会让 v20 升上来的库一直缺表（v23 之后才被 23→24 的 CREATE TABLE IF NOT EXISTS 补上）
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `workspaces` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `root` TEXT NOT NULL,
                `shell_enabled` INTEGER NOT NULL,
                `shell_status` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `last_access_at` INTEGER,
                `tool_approvals` TEXT NOT NULL DEFAULT '{}',
                PRIMARY KEY(`id`)
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_workspaces_root` ON `workspaces`(`root`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workspaces_updated_at` ON `workspaces`(`updated_at`)")

        // 知识源
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `knowledge_sources` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `name` TEXT NOT NULL DEFAULT '',
                `type` TEXT NOT NULL DEFAULT 'FILE',
                `assistant_id` TEXT,
                `file_path` TEXT,
                `file_size` INTEGER NOT NULL DEFAULT 0,
                `chunk_count` INTEGER NOT NULL DEFAULT 0,
                `created_at` INTEGER NOT NULL DEFAULT 0
            )
        """)

        // 知识分块
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `knowledge_chunks` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `source_id` TEXT NOT NULL,
                `chunk_index` INTEGER NOT NULL DEFAULT 0,
                `text` TEXT NOT NULL DEFAULT '',
                `sentence_start` INTEGER NOT NULL DEFAULT 0,
                `sentence_end` INTEGER NOT NULL DEFAULT 0,
                `embedding` BLOB,
                `embedding_dim` INTEGER NOT NULL DEFAULT 0
            )
        """)

        // FTS5 全文索引（精确关键字搜索）
        db.execSQL("""
            CREATE VIRTUAL TABLE IF NOT EXISTS `knowledge_fts` USING fts5(
                `text`,
                `chunk_id` UNINDEXED,
                `source_id` UNINDEXED,
                tokenize='unicode61'
            )
        """)

        // 索引
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_knowledge_chunks_source_id` ON `knowledge_chunks`(`source_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_knowledge_chunks_chunk_index` ON `knowledge_chunks`(`chunk_index`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_knowledge_sources_assistant_id` ON `knowledge_sources`(`assistant_id`)")
    }
}
