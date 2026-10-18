package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.model.MemoryFile

/**
 * 版本 30 -> 31（合并上游记忆库 / 媒体创作）：
 * - ConversationEntity 增加 config 快照列
 * - MemoryEntity 逐条记忆迁到 memory_file 后删表
 * - 新增 memory_consolidation / media_creation 三表
 */
val Migration_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN config TEXT NOT NULL DEFAULT ''")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memory_file` (`assistant_id` TEXT NOT NULL, `path` TEXT NOT NULL, " +
                "`content` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`assistant_id`, `path`))"
        )

        val memories = linkedMapOf<String, MutableList<String>>()
        // 恢复上游 rikkahub 的备份时这张表可能已经不存在，缺表就跳过数据迁移
        if (db.hasTable("MemoryEntity")) {
            db.query("SELECT assistant_id, content FROM MemoryEntity ORDER BY id").use { cursor ->
                while (cursor.moveToNext()) {
                    memories.getOrPut(cursor.getString(0)) { mutableListOf() }.add(cursor.getString(1))
                }
            }
        }
        val now = System.currentTimeMillis()
        memories.forEach { (assistantId, contents) ->
            val profile = legacyMemoriesToProfile(contents) ?: return@forEach
            db.execSQL(
                "INSERT INTO memory_file (assistant_id, path, content, updated_at) VALUES (?, ?, ?, ?)",
                arrayOf<Any?>(assistantId, MemoryFile.PROFILE_PATH, profile, now)
            )
        }
        db.execSQL("DROP TABLE IF EXISTS MemoryEntity")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memory_consolidation` (`conversation_id` TEXT NOT NULL, " +
                "`consolidated_at` INTEGER NOT NULL, PRIMARY KEY(`conversation_id`), " +
                "FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "INSERT OR IGNORE INTO memory_consolidation (conversation_id, consolidated_at) " +
                "SELECT id, update_at FROM ConversationEntity"
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `media_creation_session` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`draft` TEXT NOT NULL, `create_at` INTEGER NOT NULL, `update_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `media_creation_node` (`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, " +
                "`selected_record_id` TEXT NOT NULL, `create_at` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`session_id`) REFERENCES `media_creation_session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_media_creation_node_session_id` ON `media_creation_node` (`session_id`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `media_creation_record` (`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, " +
                "`node_id` TEXT NOT NULL, `provider_id` TEXT NOT NULL, `provider_name` TEXT NOT NULL, " +
                "`model_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `prompt` TEXT NOT NULL, `params` TEXT NOT NULL, " +
                "`inputs` TEXT NOT NULL, `status` TEXT NOT NULL, `task_id` TEXT, `error` TEXT, `outputs` TEXT NOT NULL, " +
                "`create_at` INTEGER NOT NULL, `update_at` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`session_id`) REFERENCES `media_creation_session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`node_id`) REFERENCES `media_creation_node`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_media_creation_record_session_id` ON `media_creation_record` (`session_id`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_media_creation_record_node_id` ON `media_creation_record` (`node_id`)"
        )
    }
}

/** 把旧的逐条记忆拼成一份 profile 文件，没有有效内容时返回 null */
internal fun legacyMemoriesToProfile(contents: List<String>): String? {
    val lines = contents
        .map { it.lines().joinToString(" ") { line -> line.trim() }.trim() }
        .filter { it.isNotEmpty() }
    if (lines.isEmpty()) return null
    return buildString {
        appendLine("---")
        appendLine("name: profile")
        appendLine("description: Who the user is")
        appendLine("---")
        appendLine()
        lines.forEach { appendLine("- [stated] $it") }
    }
}
