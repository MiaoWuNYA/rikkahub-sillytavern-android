package me.rerere.rikkahub.data.db.migrations

/**
 * 由 tools/generate_db_schema.py 从 app/schemas 的结构差异生成，请勿手改。
 *
 * 1 → 20 这一段在 @Database 里声明为 autoMigrations，Room 只在「走 Room 迁移容器」时才用它们。
 * 备份恢复为了不依赖容器（历史上漏注册一段就让整次恢复失败），把同一段结构变更固化下来显式执行，
 * 于是「任意旧版本 → 最新版」在恢复路径上也是完整的。
 */
internal object AppDatabaseLegacyMigrations {
    class Step(val from: Int, val to: Int, val statements: List<String>)

    val STEPS: List<Step> = listOf(
        Step(1, 2, listOf(
            "CREATE TABLE IF NOT EXISTS `MemoryEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `assistant_id` TEXT NOT NULL, `content` TEXT NOT NULL)",
        )),
        Step(2, 3, emptyList()),
        Step(3, 4, listOf(
            "ALTER TABLE `ConversationEntity` ADD COLUMN `usage` TEXT",
        )),
        Step(4, 5, listOf(
            "ALTER TABLE `ConversationEntity` ADD COLUMN `assistant_id` TEXT NOT NULL DEFAULT '0950e2dc-9bd5-4801-afa3-aa887aa36b4e'",
        )),
        Step(5, 6, listOf(
            "ALTER TABLE `ConversationEntity` ADD COLUMN `truncate_index` INTEGER NOT NULL DEFAULT -1",
        )),
        Step(7, 8, listOf(
            "ALTER TABLE `ConversationEntity` ADD COLUMN `suggestions` TEXT NOT NULL DEFAULT '[]'",
        )),
        Step(8, 9, listOf(
            "ALTER TABLE `ConversationEntity` DROP COLUMN `usage`",
        )),
        Step(9, 10, listOf(
            "ALTER TABLE `ConversationEntity` ADD COLUMN `is_pinned` INTEGER NOT NULL DEFAULT 0",
        )),
        Step(10, 11, listOf(
            "CREATE TABLE IF NOT EXISTS `GenMediaEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `path` TEXT NOT NULL, `model_id` TEXT NOT NULL, `prompt` TEXT NOT NULL, `create_at` INTEGER NOT NULL)",
        )),
        Step(12, 13, listOf(
            "CREATE TABLE IF NOT EXISTS `managed_files` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `folder` TEXT NOT NULL, `relative_path` TEXT NOT NULL, `display_name` TEXT NOT NULL, `mime_type` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_managed_files_relative_path` ON `managed_files` (`relative_path`)",
            "CREATE INDEX IF NOT EXISTS `index_managed_files_folder` ON `managed_files` (`folder`)",
        )),
        Step(16, 17, listOf(
            "ALTER TABLE `ConversationEntity` DROP COLUMN `truncate_index`",
        )),
        Step(17, 18, listOf(
            "ALTER TABLE `GenMediaEntity` ADD COLUMN `type` TEXT NOT NULL DEFAULT 'image_generation'",
            "ALTER TABLE `GenMediaEntity` ADD COLUMN `source_paths` TEXT",
        )),
        Step(18, 19, listOf(
            "ALTER TABLE `ConversationEntity` ADD COLUMN `custom_system_prompt` TEXT NOT NULL DEFAULT ''",
        )),
        Step(19, 20, listOf(
            "ALTER TABLE `ConversationEntity` ADD COLUMN `mode_injection_ids` TEXT NOT NULL DEFAULT '[]'",
            "ALTER TABLE `ConversationEntity` ADD COLUMN `lorebook_ids` TEXT NOT NULL DEFAULT '[]'",
        )),
    )
}
