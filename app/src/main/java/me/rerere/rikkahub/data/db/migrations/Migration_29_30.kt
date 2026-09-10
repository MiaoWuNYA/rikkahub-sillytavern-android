package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 新增情侣空间 6 张表（relationship / post / comment / diary / diary_folder / anniversary），
 * 全部列带默认值语义，与 CoupleEntities.kt 的 @ColumnInfo 声明保持一致。
 */
object Migration_29_30 : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `couple_relationship` (" +
                "`id` TEXT NOT NULL, " +
                "`assistant_id` TEXT NOT NULL, " +
                "`started_at` INTEGER NOT NULL, " +
                "`journal_cover` TEXT NOT NULL DEFAULT 'rose_velvet', " +
                "`journal_cover_title` TEXT, " +
                "`journal_cover_date` TEXT, " +
                "`created_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_couple_relationship_assistant_id` ON `couple_relationship` (`assistant_id`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `couple_post` (" +
                "`id` TEXT NOT NULL, " +
                "`relationship_id` TEXT NOT NULL, " +
                "`author` TEXT NOT NULL, " +
                "`content` TEXT NOT NULL, " +
                "`image_uri` TEXT, " +
                "`liked` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_couple_post_relationship_id` ON `couple_post` (`relationship_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_couple_post_created_at` ON `couple_post` (`created_at`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `couple_comment` (" +
                "`id` TEXT NOT NULL, " +
                "`relationship_id` TEXT NOT NULL, " +
                "`post_id` TEXT NOT NULL, " +
                "`author` TEXT NOT NULL, " +
                "`content` TEXT NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_couple_comment_relationship_id` ON `couple_comment` (`relationship_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_couple_comment_post_id` ON `couple_comment` (`post_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_couple_comment_created_at` ON `couple_comment` (`created_at`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `couple_diary` (" +
                "`id` TEXT NOT NULL, " +
                "`relationship_id` TEXT NOT NULL, " +
                "`author` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`content` TEXT NOT NULL, " +
                "`entry_date` INTEGER NOT NULL, " +
                "`folder` TEXT, " +
                "`paper` TEXT, " +
                "`reply` TEXT, " +
                "`reply_at` INTEGER, " +
                "`reply_paper` TEXT, " +
                "`bookmarked` INTEGER NOT NULL DEFAULT 0, " +
                "`created_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_couple_diary_relationship_id` ON `couple_diary` (`relationship_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_couple_diary_entry_date` ON `couple_diary` (`entry_date`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `couple_diary_folder` (" +
                "`id` TEXT NOT NULL, " +
                "`relationship_id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`sort_order` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_couple_diary_folder_relationship_id_name` ON `couple_diary_folder` (`relationship_id`, `name`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_couple_diary_folder_sort_order` ON `couple_diary_folder` (`sort_order`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `couple_anniversary` (" +
                "`id` TEXT NOT NULL, " +
                "`relationship_id` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`event_date` INTEGER NOT NULL, " +
                "`yearly` INTEGER NOT NULL, " +
                "`category` TEXT NOT NULL DEFAULT 'love', " +
                "`note` TEXT, " +
                "`favorite` INTEGER NOT NULL DEFAULT 0, " +
                "`created_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_couple_anniversary_relationship_id` ON `couple_anniversary` (`relationship_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_couple_anniversary_event_date` ON `couple_anniversary` (`event_date`)")
    }
}
