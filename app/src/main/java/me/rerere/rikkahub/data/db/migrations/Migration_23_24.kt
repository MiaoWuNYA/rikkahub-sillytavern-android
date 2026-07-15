package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 从版本23升级到24：添加workspaces表和folders表（workspace沙箱 + 会话文件夹）
 */
val Migration_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // workspaces 表（上游 v2.3.0+）
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `workspaces` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `root` TEXT NOT NULL,
                `shell_status` TEXT NOT NULL DEFAULT 'DISABLED',
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `last_access_at` INTEGER,
                `tool_approvals` TEXT NOT NULL DEFAULT '{}',
                PRIMARY KEY(`id`)
            )
        """)
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_workspaces_root` ON `workspaces`(`root`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workspaces_updated_at` ON `workspaces`(`updated_at`)")

        // folders 表（会话文件夹分组，上游 v2.4.0+）
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `folders` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `conversation_ids` TEXT NOT NULL DEFAULT '[]',
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """)

        // upstream 版本 v24 通过 AutoMigration(from=23, to=24) 自动添加了 folder_id
        db.execSQL("ALTER TABLE `ConversationEntity` ADD COLUMN `folder_id` TEXT NOT NULL DEFAULT ''")
    }
}
