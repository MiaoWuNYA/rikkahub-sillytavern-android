package me.rerere.rikkahub.data.db

/**
 * 由 tools/generate_db_schema.py 从 app/schemas/.../33.json 生成，请勿手改。
 *
 * 恢复旧备份时用它把暂存库的结构对齐到当前版本：迁移链只保证「能升上来的库」结构正确，
 * 上游备份或脏库仍可能缺表缺列，对齐之后 Room 的 schema 校验才会通过。
 */
internal object AppDatabaseSchema {
    const val VERSION = 33

    /** Room 为这个版本生成的 identity hash，结构对齐后写回 room_master_table。 */
    const val IDENTITY_HASH = "500db313414dc3e55f1da6c8c085806e"

    class Table(
        val name: String,
        val createSql: String,
        val columns: List<Pair<String, String>>,
        val indices: List<Pair<String, String>>,
    )

    val TABLES: List<Table> = listOf(
        Table(
            name = "ConversationEntity",
            createSql = "CREATE TABLE IF NOT EXISTS `ConversationEntity` (`id` TEXT NOT NULL, `assistant_id` TEXT NOT NULL DEFAULT '0950e2dc-9bd5-4801-afa3-aa887aa36b4e', `title` TEXT NOT NULL, `nodes` TEXT NOT NULL, `create_at` INTEGER NOT NULL, `update_at` INTEGER NOT NULL, `suggestions` TEXT NOT NULL DEFAULT '[]', `is_pinned` INTEGER NOT NULL DEFAULT 0, `custom_system_prompt` TEXT NOT NULL DEFAULT '', `mode_injection_ids` TEXT NOT NULL DEFAULT '[]', `lorebook_ids` TEXT NOT NULL DEFAULT '[]', `workspace_cwd` TEXT NOT NULL DEFAULT '', `folder_id` TEXT NOT NULL DEFAULT '', `rolling_context_summary` TEXT NOT NULL DEFAULT '', `config` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`))",
            columns = listOf(
                "id" to "TEXT NOT NULL",
                "assistant_id" to "TEXT NOT NULL DEFAULT '0950e2dc-9bd5-4801-afa3-aa887aa36b4e'",
                "title" to "TEXT NOT NULL",
                "nodes" to "TEXT NOT NULL",
                "create_at" to "INTEGER NOT NULL",
                "update_at" to "INTEGER NOT NULL",
                "suggestions" to "TEXT NOT NULL DEFAULT '[]'",
                "is_pinned" to "INTEGER NOT NULL DEFAULT 0",
                "custom_system_prompt" to "TEXT NOT NULL DEFAULT ''",
                "mode_injection_ids" to "TEXT NOT NULL DEFAULT '[]'",
                "lorebook_ids" to "TEXT NOT NULL DEFAULT '[]'",
                "workspace_cwd" to "TEXT NOT NULL DEFAULT ''",
                "folder_id" to "TEXT NOT NULL DEFAULT ''",
                "rolling_context_summary" to "TEXT NOT NULL DEFAULT ''",
                "config" to "TEXT NOT NULL DEFAULT ''",
            ),
            indices = emptyList(),
        ),
        Table(
            name = "memory_file",
            createSql = "CREATE TABLE IF NOT EXISTS `memory_file` (`assistant_id` TEXT NOT NULL, `path` TEXT NOT NULL, `content` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`assistant_id`, `path`))",
            columns = listOf(
                "assistant_id" to "TEXT NOT NULL",
                "path" to "TEXT NOT NULL",
                "content" to "TEXT NOT NULL",
                "updated_at" to "INTEGER NOT NULL",
            ),
            indices = emptyList(),
        ),
        Table(
            name = "memory_consolidation",
            createSql = "CREATE TABLE IF NOT EXISTS `memory_consolidation` (`conversation_id` TEXT NOT NULL, `consolidated_at` INTEGER NOT NULL, PRIMARY KEY(`conversation_id`), FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            columns = listOf(
                "conversation_id" to "TEXT NOT NULL",
                "consolidated_at" to "INTEGER NOT NULL",
            ),
            indices = emptyList(),
        ),
        Table(
            name = "GenMediaEntity",
            createSql = "CREATE TABLE IF NOT EXISTS `GenMediaEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `path` TEXT NOT NULL, `model_id` TEXT NOT NULL, `prompt` TEXT NOT NULL, `create_at` INTEGER NOT NULL, `type` TEXT NOT NULL DEFAULT 'image_generation', `source_paths` TEXT)",
            columns = listOf(
                "id" to "INTEGER NOT NULL",
                "path" to "TEXT NOT NULL",
                "model_id" to "TEXT NOT NULL",
                "prompt" to "TEXT NOT NULL",
                "create_at" to "INTEGER NOT NULL",
                "type" to "TEXT NOT NULL DEFAULT 'image_generation'",
                "source_paths" to "TEXT",
            ),
            indices = emptyList(),
        ),
        Table(
            name = "message_node",
            createSql = "CREATE TABLE IF NOT EXISTS `message_node` (`id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, `node_index` INTEGER NOT NULL, `messages` TEXT NOT NULL, `select_index` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            columns = listOf(
                "id" to "TEXT NOT NULL",
                "conversation_id" to "TEXT NOT NULL",
                "node_index" to "INTEGER NOT NULL",
                "messages" to "TEXT NOT NULL",
                "select_index" to "INTEGER NOT NULL",
            ),
            indices = listOf(
                "index_message_node_conversation_id" to "CREATE INDEX IF NOT EXISTS `index_message_node_conversation_id` ON `message_node` (`conversation_id`)",
            ),
        ),
        Table(
            name = "workspaces",
            createSql = "CREATE TABLE IF NOT EXISTS `workspaces` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `root` TEXT NOT NULL, `shell_status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `last_access_at` INTEGER, `tool_approvals` TEXT NOT NULL DEFAULT '{}', `shell_compatibility_mode` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
            columns = listOf(
                "id" to "TEXT NOT NULL",
                "name" to "TEXT NOT NULL",
                "root" to "TEXT NOT NULL",
                "shell_status" to "TEXT NOT NULL",
                "created_at" to "INTEGER NOT NULL",
                "updated_at" to "INTEGER NOT NULL",
                "last_access_at" to "INTEGER",
                "tool_approvals" to "TEXT NOT NULL DEFAULT '{}'",
                "shell_compatibility_mode" to "INTEGER NOT NULL DEFAULT 0",
            ),
            indices = listOf(
                "index_workspaces_root" to "CREATE UNIQUE INDEX IF NOT EXISTS `index_workspaces_root` ON `workspaces` (`root`)",
                "index_workspaces_updated_at" to "CREATE INDEX IF NOT EXISTS `index_workspaces_updated_at` ON `workspaces` (`updated_at`)",
            ),
        ),
        Table(
            name = "conversation_folder",
            createSql = "CREATE TABLE IF NOT EXISTS `conversation_folder` (`id` TEXT NOT NULL, `assistant_id` TEXT NOT NULL, `name` TEXT NOT NULL, `sort_index` INTEGER NOT NULL DEFAULT 0, `create_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            columns = listOf(
                "id" to "TEXT NOT NULL",
                "assistant_id" to "TEXT NOT NULL",
                "name" to "TEXT NOT NULL",
                "sort_index" to "INTEGER NOT NULL DEFAULT 0",
                "create_at" to "INTEGER NOT NULL",
            ),
            indices = listOf(
                "index_conversation_folder_assistant_id" to "CREATE INDEX IF NOT EXISTS `index_conversation_folder_assistant_id` ON `conversation_folder` (`assistant_id`)",
            ),
        ),
        Table(
            name = "managed_files",
            createSql = "CREATE TABLE IF NOT EXISTS `managed_files` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `folder` TEXT NOT NULL, `relative_path` TEXT NOT NULL, `display_name` TEXT NOT NULL, `mime_type` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)",
            columns = listOf(
                "id" to "INTEGER NOT NULL",
                "folder" to "TEXT NOT NULL",
                "relative_path" to "TEXT NOT NULL",
                "display_name" to "TEXT NOT NULL",
                "mime_type" to "TEXT NOT NULL",
                "size_bytes" to "INTEGER NOT NULL",
                "created_at" to "INTEGER NOT NULL",
                "updated_at" to "INTEGER NOT NULL",
            ),
            indices = listOf(
                "index_managed_files_relative_path" to "CREATE UNIQUE INDEX IF NOT EXISTS `index_managed_files_relative_path` ON `managed_files` (`relative_path`)",
                "index_managed_files_folder" to "CREATE INDEX IF NOT EXISTS `index_managed_files_folder` ON `managed_files` (`folder`)",
            ),
        ),
        Table(
            name = "favorites",
            createSql = "CREATE TABLE IF NOT EXISTS `favorites` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `ref_key` TEXT NOT NULL, `ref_json` TEXT NOT NULL, `snapshot_json` TEXT NOT NULL, `meta_json` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            columns = listOf(
                "id" to "TEXT NOT NULL",
                "type" to "TEXT NOT NULL",
                "ref_key" to "TEXT NOT NULL",
                "ref_json" to "TEXT NOT NULL",
                "snapshot_json" to "TEXT NOT NULL",
                "meta_json" to "TEXT",
                "created_at" to "INTEGER NOT NULL",
                "updated_at" to "INTEGER NOT NULL",
            ),
            indices = listOf(
                "index_favorites_ref_key" to "CREATE UNIQUE INDEX IF NOT EXISTS `index_favorites_ref_key` ON `favorites` (`ref_key`)",
                "index_favorites_type" to "CREATE INDEX IF NOT EXISTS `index_favorites_type` ON `favorites` (`type`)",
                "index_favorites_created_at" to "CREATE INDEX IF NOT EXISTS `index_favorites_created_at` ON `favorites` (`created_at`)",
            ),
        ),
        Table(
            name = "couple_relationship",
            createSql = "CREATE TABLE IF NOT EXISTS `couple_relationship` (`id` TEXT NOT NULL, `assistant_id` TEXT NOT NULL, `started_at` INTEGER NOT NULL, `journal_cover` TEXT NOT NULL DEFAULT 'rose_velvet', `journal_cover_title` TEXT, `journal_cover_date` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            columns = listOf(
                "id" to "TEXT NOT NULL",
                "assistant_id" to "TEXT NOT NULL",
                "started_at" to "INTEGER NOT NULL",
                "journal_cover" to "TEXT NOT NULL DEFAULT 'rose_velvet'",
                "journal_cover_title" to "TEXT",
                "journal_cover_date" to "TEXT",
                "created_at" to "INTEGER NOT NULL",
            ),
            indices = listOf(
                "index_couple_relationship_assistant_id" to "CREATE UNIQUE INDEX IF NOT EXISTS `index_couple_relationship_assistant_id` ON `couple_relationship` (`assistant_id`)",
            ),
        ),
        Table(
            name = "couple_post",
            createSql = "CREATE TABLE IF NOT EXISTS `couple_post` (`id` TEXT NOT NULL, `relationship_id` TEXT NOT NULL, `author` TEXT NOT NULL, `content` TEXT NOT NULL, `image_uri` TEXT, `liked` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            columns = listOf(
                "id" to "TEXT NOT NULL",
                "relationship_id" to "TEXT NOT NULL",
                "author" to "TEXT NOT NULL",
                "content" to "TEXT NOT NULL",
                "image_uri" to "TEXT",
                "liked" to "INTEGER NOT NULL",
                "created_at" to "INTEGER NOT NULL",
            ),
            indices = listOf(
                "index_couple_post_relationship_id" to "CREATE INDEX IF NOT EXISTS `index_couple_post_relationship_id` ON `couple_post` (`relationship_id`)",
                "index_couple_post_created_at" to "CREATE INDEX IF NOT EXISTS `index_couple_post_created_at` ON `couple_post` (`created_at`)",
            ),
        ),
        Table(
            name = "couple_comment",
            createSql = "CREATE TABLE IF NOT EXISTS `couple_comment` (`id` TEXT NOT NULL, `relationship_id` TEXT NOT NULL, `post_id` TEXT NOT NULL, `author` TEXT NOT NULL, `content` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            columns = listOf(
                "id" to "TEXT NOT NULL",
                "relationship_id" to "TEXT NOT NULL",
                "post_id" to "TEXT NOT NULL",
                "author" to "TEXT NOT NULL",
                "content" to "TEXT NOT NULL",
                "created_at" to "INTEGER NOT NULL",
            ),
            indices = listOf(
                "index_couple_comment_relationship_id" to "CREATE INDEX IF NOT EXISTS `index_couple_comment_relationship_id` ON `couple_comment` (`relationship_id`)",
                "index_couple_comment_post_id" to "CREATE INDEX IF NOT EXISTS `index_couple_comment_post_id` ON `couple_comment` (`post_id`)",
                "index_couple_comment_created_at" to "CREATE INDEX IF NOT EXISTS `index_couple_comment_created_at` ON `couple_comment` (`created_at`)",
            ),
        ),
        Table(
            name = "couple_diary",
            createSql = "CREATE TABLE IF NOT EXISTS `couple_diary` (`id` TEXT NOT NULL, `relationship_id` TEXT NOT NULL, `author` TEXT NOT NULL, `title` TEXT NOT NULL, `content` TEXT NOT NULL, `entry_date` INTEGER NOT NULL, `folder` TEXT, `paper` TEXT, `reply` TEXT, `reply_at` INTEGER, `reply_paper` TEXT, `bookmarked` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            columns = listOf(
                "id" to "TEXT NOT NULL",
                "relationship_id" to "TEXT NOT NULL",
                "author" to "TEXT NOT NULL",
                "title" to "TEXT NOT NULL",
                "content" to "TEXT NOT NULL",
                "entry_date" to "INTEGER NOT NULL",
                "folder" to "TEXT",
                "paper" to "TEXT",
                "reply" to "TEXT",
                "reply_at" to "INTEGER",
                "reply_paper" to "TEXT",
                "bookmarked" to "INTEGER NOT NULL DEFAULT 0",
                "created_at" to "INTEGER NOT NULL",
            ),
            indices = listOf(
                "index_couple_diary_relationship_id" to "CREATE INDEX IF NOT EXISTS `index_couple_diary_relationship_id` ON `couple_diary` (`relationship_id`)",
                "index_couple_diary_entry_date" to "CREATE INDEX IF NOT EXISTS `index_couple_diary_entry_date` ON `couple_diary` (`entry_date`)",
            ),
        ),
        Table(
            name = "couple_diary_folder",
            createSql = "CREATE TABLE IF NOT EXISTS `couple_diary_folder` (`id` TEXT NOT NULL, `relationship_id` TEXT NOT NULL, `name` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            columns = listOf(
                "id" to "TEXT NOT NULL",
                "relationship_id" to "TEXT NOT NULL",
                "name" to "TEXT NOT NULL",
                "sort_order" to "INTEGER NOT NULL",
                "created_at" to "INTEGER NOT NULL",
            ),
            indices = listOf(
                "index_couple_diary_folder_relationship_id_name" to "CREATE UNIQUE INDEX IF NOT EXISTS `index_couple_diary_folder_relationship_id_name` ON `couple_diary_folder` (`relationship_id`, `name`)",
                "index_couple_diary_folder_sort_order" to "CREATE INDEX IF NOT EXISTS `index_couple_diary_folder_sort_order` ON `couple_diary_folder` (`sort_order`)",
            ),
        ),
        Table(
            name = "couple_anniversary",
            createSql = "CREATE TABLE IF NOT EXISTS `couple_anniversary` (`id` TEXT NOT NULL, `relationship_id` TEXT NOT NULL, `title` TEXT NOT NULL, `event_date` INTEGER NOT NULL, `yearly` INTEGER NOT NULL, `category` TEXT NOT NULL DEFAULT 'love', `note` TEXT, `favorite` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            columns = listOf(
                "id" to "TEXT NOT NULL",
                "relationship_id" to "TEXT NOT NULL",
                "title" to "TEXT NOT NULL",
                "event_date" to "INTEGER NOT NULL",
                "yearly" to "INTEGER NOT NULL",
                "category" to "TEXT NOT NULL DEFAULT 'love'",
                "note" to "TEXT",
                "favorite" to "INTEGER NOT NULL DEFAULT 0",
                "created_at" to "INTEGER NOT NULL",
            ),
            indices = listOf(
                "index_couple_anniversary_relationship_id" to "CREATE INDEX IF NOT EXISTS `index_couple_anniversary_relationship_id` ON `couple_anniversary` (`relationship_id`)",
                "index_couple_anniversary_event_date" to "CREATE INDEX IF NOT EXISTS `index_couple_anniversary_event_date` ON `couple_anniversary` (`event_date`)",
            ),
        ),
        Table(
            name = "media_creation_session",
            createSql = "CREATE TABLE IF NOT EXISTS `media_creation_session` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `draft` TEXT NOT NULL, `create_at` INTEGER NOT NULL, `update_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            columns = listOf(
                "id" to "TEXT NOT NULL",
                "title" to "TEXT NOT NULL",
                "draft" to "TEXT NOT NULL",
                "create_at" to "INTEGER NOT NULL",
                "update_at" to "INTEGER NOT NULL",
            ),
            indices = emptyList(),
        ),
        Table(
            name = "media_creation_node",
            createSql = "CREATE TABLE IF NOT EXISTS `media_creation_node` (`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, `selected_record_id` TEXT NOT NULL, `create_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`session_id`) REFERENCES `media_creation_session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            columns = listOf(
                "id" to "TEXT NOT NULL",
                "session_id" to "TEXT NOT NULL",
                "selected_record_id" to "TEXT NOT NULL",
                "create_at" to "INTEGER NOT NULL",
            ),
            indices = listOf(
                "index_media_creation_node_session_id" to "CREATE INDEX IF NOT EXISTS `index_media_creation_node_session_id` ON `media_creation_node` (`session_id`)",
            ),
        ),
        Table(
            name = "media_creation_record",
            createSql = "CREATE TABLE IF NOT EXISTS `media_creation_record` (`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, `node_id` TEXT NOT NULL, `provider_id` TEXT NOT NULL, `provider_name` TEXT NOT NULL, `model_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `prompt` TEXT NOT NULL, `params` TEXT NOT NULL, `inputs` TEXT NOT NULL, `status` TEXT NOT NULL, `task_id` TEXT, `error` TEXT, `outputs` TEXT NOT NULL, `create_at` INTEGER NOT NULL, `update_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`session_id`) REFERENCES `media_creation_session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`node_id`) REFERENCES `media_creation_node`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            columns = listOf(
                "id" to "TEXT NOT NULL",
                "session_id" to "TEXT NOT NULL",
                "node_id" to "TEXT NOT NULL",
                "provider_id" to "TEXT NOT NULL",
                "provider_name" to "TEXT NOT NULL",
                "model_id" to "TEXT NOT NULL",
                "kind" to "TEXT NOT NULL",
                "prompt" to "TEXT NOT NULL",
                "params" to "TEXT NOT NULL",
                "inputs" to "TEXT NOT NULL",
                "status" to "TEXT NOT NULL",
                "task_id" to "TEXT",
                "error" to "TEXT",
                "outputs" to "TEXT NOT NULL",
                "create_at" to "INTEGER NOT NULL",
                "update_at" to "INTEGER NOT NULL",
            ),
            indices = listOf(
                "index_media_creation_record_session_id" to "CREATE INDEX IF NOT EXISTS `index_media_creation_record_session_id` ON `media_creation_record` (`session_id`)",
                "index_media_creation_record_node_id" to "CREATE INDEX IF NOT EXISTS `index_media_creation_record_node_id` ON `media_creation_record` (`node_id`)",
            ),
        ),
        Table(
            name = "translation_history",
            createSql = "CREATE TABLE IF NOT EXISTS `translation_history` (`id` TEXT NOT NULL, `source_text` TEXT NOT NULL, `translated_text` TEXT NOT NULL, `target_language` TEXT NOT NULL, `model_id` TEXT NOT NULL, `create_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            columns = listOf(
                "id" to "TEXT NOT NULL",
                "source_text" to "TEXT NOT NULL",
                "translated_text" to "TEXT NOT NULL",
                "target_language" to "TEXT NOT NULL",
                "model_id" to "TEXT NOT NULL",
                "create_at" to "INTEGER NOT NULL",
            ),
            indices = listOf(
                "index_translation_history_create_at" to "CREATE INDEX IF NOT EXISTS `index_translation_history_create_at` ON `translation_history` (`create_at`)",
            ),
        ),
        Table(
            name = "usage_record",
            createSql = "CREATE TABLE IF NOT EXISTS `usage_record` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `created_at` INTEGER NOT NULL, `source` TEXT NOT NULL, `provider_id` TEXT, `model_id` TEXT, `model_name` TEXT, `assistant_id` TEXT, `conversation_id` TEXT, `prompt_tokens` INTEGER NOT NULL, `completion_tokens` INTEGER NOT NULL, `cached_tokens` INTEGER NOT NULL)",
            columns = listOf(
                "id" to "INTEGER NOT NULL",
                "created_at" to "INTEGER NOT NULL",
                "source" to "TEXT NOT NULL",
                "provider_id" to "TEXT",
                "model_id" to "TEXT",
                "model_name" to "TEXT",
                "assistant_id" to "TEXT",
                "conversation_id" to "TEXT",
                "prompt_tokens" to "INTEGER NOT NULL",
                "completion_tokens" to "INTEGER NOT NULL",
                "cached_tokens" to "INTEGER NOT NULL",
            ),
            indices = listOf(
                "index_usage_record_created_at" to "CREATE INDEX IF NOT EXISTS `index_usage_record_created_at` ON `usage_record` (`created_at`)",
            ),
        ),
    )
}
