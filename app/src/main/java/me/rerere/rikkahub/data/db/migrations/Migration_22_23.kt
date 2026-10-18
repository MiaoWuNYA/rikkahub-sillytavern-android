package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 从版本22升级到23：添加知识库-助理多对多关联表，删掉 workspaces.shell_enabled
 */
val Migration_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 上游 v23 通过 AutoMigration(22,23) 删掉了 workspaces.shell_enabled，手写迁移漏了这一步：
        // 留着这个 NOT NULL 且无默认值的列，之后新建工作区会因为插入缺列直接失败
        db.dropColumnIfNotExists("workspaces", "shell_enabled")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `knowledge_source_assistants` (
                `source_id` TEXT NOT NULL,
                `assistant_id` TEXT NOT NULL,
                PRIMARY KEY(`source_id`, `assistant_id`),
                FOREIGN KEY(`source_id`) REFERENCES `knowledge_sources`(`id`) ON DELETE CASCADE
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_ksa_source_id` ON `knowledge_source_assistants`(`source_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_ksa_assistant_id` ON `knowledge_source_assistants`(`assistant_id`)")
    }
}
