package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 从版本25升级到26：删除知识库系统遗留表（知识库功能已整体移除）
 */
val Migration_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `knowledge_source_assistants`")
        db.execSQL("DROP TABLE IF EXISTS `knowledge_fts`")
        db.execSQL("DROP TABLE IF EXISTS `knowledge_chunks`")
        db.execSQL("DROP TABLE IF EXISTS `knowledge_sources`")
    }
}
