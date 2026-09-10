package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 从版本28升级到29：WorkspaceEntity 新增 shell 兼容模式列（合并上游 2.5.1）
 * - shell_compatibility_mode: Boolean，Proot/Shell 兼容模式开关，默认关闭
 */
val Migration_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `shell_compatibility_mode` INTEGER NOT NULL DEFAULT 0")
    }
}
