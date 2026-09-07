package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 从版本26升级到27：MemoryEntity 扩展（移植自 Rikkahub-Revised 的记忆增强功能）
 * - memory_type: 记忆类别（fact / episodic）
 * - created_at: 创建时间戳
 * - source_conversation_id: 来源会话
 * - embedding / embedding_model_id / embedding_dimension: 语义检索用的嵌入向量
 */
val Migration_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `memory_type` TEXT NOT NULL DEFAULT 'fact'")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `created_at` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `source_conversation_id` TEXT")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `embedding` BLOB")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `embedding_model_id` TEXT")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `embedding_dimension` INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_MemoryEntity_assistant_id` ON `MemoryEntity` (`assistant_id`)")
    }
}
