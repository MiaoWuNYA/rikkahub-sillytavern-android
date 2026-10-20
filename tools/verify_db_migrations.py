#!/usr/bin/env python3
"""本地验证「任意旧版本 → 最新版」的迁移链。

用 sqlite3 按 schema JSON 建出 vN 的库，跑与 Kotlin 侧一致的迁移链
（手写段 + AppDatabaseLegacyMigrations 的结构变更），每一步都跟对应版本的
schema JSON 对比结构，最后确认能对齐到当前版本。

用法：
    python3 tools/verify_db_migrations.py
"""
import argparse
import os
import sqlite3
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import db_schema_tools as tools  # noqa: E402

TARGET = tools.latest_version()


# ---------------------------------------------------------------- 建库与对比

def entity_of(version, table):
    for entity in tools.load(version)["entities"]:
        if entity["tableName"] == table:
            return entity
    return None


# 知识库表是 raw SQL 建的，不出现在 Room schema JSON 里，但真实库的 v21..v25 都有它们
KNOWLEDGE_TABLES = [
    "CREATE TABLE IF NOT EXISTS `knowledge_sources` (`id` TEXT NOT NULL PRIMARY KEY, "
    "`name` TEXT NOT NULL DEFAULT '', `type` TEXT NOT NULL DEFAULT 'FILE', `assistant_id` TEXT, "
    "`file_path` TEXT, `file_size` INTEGER NOT NULL DEFAULT 0, `chunk_count` INTEGER NOT NULL DEFAULT 0, "
    "`created_at` INTEGER NOT NULL DEFAULT 0)",
    "CREATE TABLE IF NOT EXISTS `knowledge_chunks` (`id` TEXT NOT NULL PRIMARY KEY, `source_id` TEXT NOT NULL, "
    "`chunk_index` INTEGER NOT NULL DEFAULT 0, `text` TEXT NOT NULL DEFAULT '', "
    "`sentence_start` INTEGER NOT NULL DEFAULT 0, `sentence_end` INTEGER NOT NULL DEFAULT 0, "
    "`embedding` BLOB, `embedding_dim` INTEGER NOT NULL DEFAULT 0)",
    "CREATE VIRTUAL TABLE IF NOT EXISTS `knowledge_fts` USING fts5(`text`, `chunk_id` UNINDEXED, "
    "`source_id` UNINDEXED, tokenize='unicode61')",
    "CREATE INDEX IF NOT EXISTS `idx_knowledge_chunks_source_id` ON `knowledge_chunks`(`source_id`)",
    "CREATE INDEX IF NOT EXISTS `idx_knowledge_chunks_chunk_index` ON `knowledge_chunks`(`chunk_index`)",
    "CREATE INDEX IF NOT EXISTS `idx_knowledge_sources_assistant_id` ON `knowledge_sources`(`assistant_id`)",
]


def create_base(conn, version):
    for entity in tools.load(version)["entities"]:
        conn.execute(tools.create_sql(entity))
        for index in entity.get("indices", []):
            conn.execute(tools.index_sql(entity, index))
    if 21 <= version <= 25:
        for statement in KNOWLEDGE_TABLES:
            conn.execute(statement)
    conn.execute("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
    conn.execute(f"PRAGMA user_version = {version}")
    conn.commit()


def affinity(declaration):
    if declaration is None:
        return "BLOB"
    upper = declaration.upper()
    if "INT" in upper:
        return "INTEGER"
    if any(token in upper for token in ("CHAR", "CLOB", "TEXT")):
        return "TEXT"
    if "BLOB" in upper or upper == "":
        return "BLOB"
    if any(token in upper for token in ("REAL", "FLOA", "DOUB")):
        return "REAL"
    return "NUMERIC"


def actual_columns(conn, table):
    return {
        row[1]: {"affinity": affinity(row[2]), "notNull": bool(row[3]), "default": row[4]}
        for row in conn.execute(f"PRAGMA table_info(`{table}`)")
    }


def actual_indices(conn, table):
    result = {}
    for row in conn.execute(f"PRAGMA index_list(`{table}`)"):
        name = row[1]
        if name.startswith("sqlite_autoindex"):
            continue
        columns = [r[2] for r in conn.execute(f"PRAGMA index_info(`{name}`)")]
        result[name] = {"unique": bool(row[2]), "columns": columns}
    return result


def actual_foreign_keys(conn, table):
    return {
        (row[2], row[3], row[4], row[5], row[6])
        for row in conn.execute(f"PRAGMA foreign_key_list(`{table}`)")
    }


def compare(conn, version):
    """库结构 vs version.json 的期望结构，返回问题列表。"""
    expected = tools.load(version)
    problems = []
    tables = {row[0] for row in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    for entity in expected["entities"]:
        table = entity["tableName"]
        if table not in tables:
            problems.append(f"缺表 {table}")
            continue
        columns = actual_columns(conn, table)
        for field in entity["fields"]:
            name = field["columnName"]
            if name not in columns:
                problems.append(f"{table}.{name} 缺失")
                continue
            got = columns[name]
            if got["affinity"] != field["affinity"]:
                problems.append(f"{table}.{name} 类型 {got['affinity']} != {field['affinity']}")
            want_not_null = field.get("notNull", False)
            if got["notNull"] != want_not_null:
                problems.append(f"{table}.{name} notNull {got['notNull']} != {want_not_null}")
            want_default = field.get("defaultValue")
            if want_default is not None and (got["default"] or "") != want_default:
                problems.append(f"{table}.{name} default {got['default']!r} != {want_default!r}")
        expected_columns = {f["columnName"] for f in entity["fields"]}
        for name in columns:
            if name not in expected_columns:
                problems.append(f"{table}.{name} 是当前版本没有的列")
        indices = actual_indices(conn, table)
        for index in entity.get("indices", []):
            name = index["name"]
            if name not in indices:
                problems.append(f"{table} 缺索引 {name}")
                continue
            if indices[name]["columns"] != index["columnNames"]:
                problems.append(f"{table}.{name} 列 {indices[name]['columns']} != {index['columnNames']}")
            if indices[name]["unique"] != index["unique"]:
                problems.append(f"{table}.{name} unique 不匹配")
        expected_indices = {i["name"] for i in entity.get("indices", [])}
        for name in indices:
            if name not in expected_indices:
                problems.append(f"{table} 多出索引 {name}")
        want_fks = {
            (fk["table"], fk["columns"][0], fk["referencedColumns"][0], fk["onUpdate"], fk["onDelete"])
            for fk in entity.get("foreignKeys", [])
        }
        if want_fks != actual_foreign_keys(conn, table):
            problems.append(f"{table} 外键 {actual_foreign_keys(conn, table)} != {want_fks}")
    return problems


# ---------------------------------------------------------------- 迁移链实现

def add_column(conn, table, column, definition):
    columns = [row[1] for row in conn.execute(f"PRAGMA table_info(`{table}`)")]
    if column in columns:
        return
    conn.execute(f"ALTER TABLE `{table}` ADD COLUMN `{column}` {definition}")


def drop_column(conn, table, column):
    tables = {row[0] for row in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    if table not in tables:
        return
    columns = [row[1] for row in conn.execute(f"PRAGMA table_info(`{table}`)")]
    if column not in columns:
        return
    try:
        conn.execute(f"ALTER TABLE `{table}` DROP COLUMN `{column}`")
    except sqlite3.OperationalError:
        pass


def rebuild_table(conn, table, version):
    """按目标版本的结构重建表，数据搬交集列（对应手写迁移里的建新表 + 搬数据）。"""
    target = entity_of(version, table)
    old_columns = [row[1] for row in conn.execute(f"PRAGMA table_info(`{table}`)")]
    new_columns = [f["columnName"] for f in target["fields"]]
    shared = [name for name in new_columns if name in old_columns]
    conn.execute(f"ALTER TABLE `{table}` RENAME TO `{table}_old`")
    conn.execute(tools.create_sql(target))
    if shared:
        columns = ", ".join(f"`{name}`" for name in shared)
        conn.execute(f"INSERT INTO `{table}` ({columns}) SELECT {columns} FROM `{table}_old`")
    conn.execute(f"DROP TABLE `{table}_old`")
    for index in target.get("indices", []):
        conn.execute(tools.index_sql(target, index))


def hw_6_7(conn):
    rebuild_table(conn, "ConversationEntity", 7)


def hw_11_12(conn):
    conn.execute(
        "CREATE TABLE IF NOT EXISTS message_node (id TEXT NOT NULL PRIMARY KEY, conversation_id TEXT NOT NULL, "
        "node_index INTEGER NOT NULL, messages TEXT NOT NULL, select_index INTEGER NOT NULL, "
        "FOREIGN KEY (conversation_id) REFERENCES ConversationEntity(id) ON DELETE CASCADE)"
    )
    conn.execute("CREATE INDEX IF NOT EXISTS index_message_node_conversation_id ON message_node(conversation_id)")


def hw_13_14(conn):
    pass


def hw_14_15(conn):
    conn.execute(
        "CREATE TABLE IF NOT EXISTS favorites (id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, "
        "ref_key TEXT NOT NULL, ref_json TEXT NOT NULL, snapshot_json TEXT NOT NULL, meta_json TEXT, "
        "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)"
    )
    conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS index_favorites_ref_key ON favorites(ref_key)")
    conn.execute("CREATE INDEX IF NOT EXISTS index_favorites_type ON favorites(type)")
    conn.execute("CREATE INDEX IF NOT EXISTS index_favorites_created_at ON favorites(created_at)")


def hw_15_16(conn):
    pass


def hw_20_21(conn):
    conn.execute(
        "CREATE TABLE IF NOT EXISTS `workspaces` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `root` TEXT NOT NULL, "
        "`shell_enabled` INTEGER NOT NULL, `shell_status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, "
        "`updated_at` INTEGER NOT NULL, `last_access_at` INTEGER, `tool_approvals` TEXT NOT NULL DEFAULT '{}', "
        "PRIMARY KEY(`id`))"
    )
    conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS `index_workspaces_root` ON `workspaces`(`root`)")
    conn.execute("CREATE INDEX IF NOT EXISTS `index_workspaces_updated_at` ON `workspaces`(`updated_at`)")
    conn.execute(
        "CREATE TABLE IF NOT EXISTS `knowledge_sources` (`id` TEXT NOT NULL PRIMARY KEY, "
        "`name` TEXT NOT NULL DEFAULT '', `type` TEXT NOT NULL DEFAULT 'FILE', `assistant_id` TEXT, "
        "`file_path` TEXT, `file_size` INTEGER NOT NULL DEFAULT 0, `chunk_count` INTEGER NOT NULL DEFAULT 0, "
        "`created_at` INTEGER NOT NULL DEFAULT 0)"
    )
    conn.execute(
        "CREATE TABLE IF NOT EXISTS `knowledge_chunks` (`id` TEXT NOT NULL PRIMARY KEY, `source_id` TEXT NOT NULL, "
        "`chunk_index` INTEGER NOT NULL DEFAULT 0, `text` TEXT NOT NULL DEFAULT '', "
        "`sentence_start` INTEGER NOT NULL DEFAULT 0, `sentence_end` INTEGER NOT NULL DEFAULT 0, "
        "`embedding` BLOB, `embedding_dim` INTEGER NOT NULL DEFAULT 0)"
    )
    conn.execute(
        "CREATE VIRTUAL TABLE IF NOT EXISTS `knowledge_fts` USING fts5(`text`, `chunk_id` UNINDEXED, "
        "`source_id` UNINDEXED, tokenize='unicode61')"
    )
    conn.execute("CREATE INDEX IF NOT EXISTS `idx_knowledge_chunks_source_id` ON `knowledge_chunks`(`source_id`)")
    conn.execute("CREATE INDEX IF NOT EXISTS `idx_knowledge_chunks_chunk_index` ON `knowledge_chunks`(`chunk_index`)")
    conn.execute("CREATE INDEX IF NOT EXISTS `idx_knowledge_sources_assistant_id` ON `knowledge_sources`(`assistant_id`)")


def hw_21_22(conn):
    add_column(conn, "knowledge_sources", "tags", "TEXT NOT NULL DEFAULT ''")
    add_column(conn, "knowledge_chunks", "parent_chunk_id", "TEXT")
    add_column(conn, "ConversationEntity", "workspace_cwd", "TEXT NOT NULL DEFAULT ''")


def hw_22_23(conn):
    drop_column(conn, "workspaces", "shell_enabled")
    conn.execute(
        "CREATE TABLE IF NOT EXISTS `knowledge_source_assistants` (`source_id` TEXT NOT NULL, "
        "`assistant_id` TEXT NOT NULL, PRIMARY KEY(`source_id`, `assistant_id`), "
        "FOREIGN KEY(`source_id`) REFERENCES `knowledge_sources`(`id`) ON DELETE CASCADE)"
    )
    conn.execute("CREATE INDEX IF NOT EXISTS `idx_ksa_source_id` ON `knowledge_source_assistants`(`source_id`)")
    conn.execute("CREATE INDEX IF NOT EXISTS `idx_ksa_assistant_id` ON `knowledge_source_assistants`(`assistant_id`)")


def hw_23_24(conn):
    conn.execute(
        "CREATE TABLE IF NOT EXISTS `workspaces` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `root` TEXT NOT NULL, "
        "`shell_status` TEXT NOT NULL DEFAULT 'DISABLED', `created_at` INTEGER NOT NULL, "
        "`updated_at` INTEGER NOT NULL, `last_access_at` INTEGER, "
        "`tool_approvals` TEXT NOT NULL DEFAULT '{}', PRIMARY KEY(`id`))"
    )
    conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS `index_workspaces_root` ON `workspaces`(`root`)")
    conn.execute("CREATE INDEX IF NOT EXISTS `index_workspaces_updated_at` ON `workspaces`(`updated_at`)")
    conn.execute(
        "CREATE TABLE IF NOT EXISTS `conversation_folder` (`id` TEXT NOT NULL, `assistant_id` TEXT NOT NULL, "
        "`name` TEXT NOT NULL, `sort_index` INTEGER NOT NULL DEFAULT 0, `create_at` INTEGER NOT NULL, "
        "PRIMARY KEY(`id`))"
    )
    conn.execute(
        "CREATE INDEX IF NOT EXISTS `index_conversation_folder_assistant_id` "
        "ON `conversation_folder`(`assistant_id`)"
    )
    add_column(conn, "ConversationEntity", "folder_id", "TEXT NOT NULL DEFAULT ''")


def hw_24_25(conn):
    add_column(conn, "ConversationEntity", "workspace_cwd", "TEXT NOT NULL DEFAULT ''")
    tables = {row[0] for row in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    if "conversation_folder" not in tables:
        conn.execute(
            "CREATE TABLE IF NOT EXISTS `conversation_folder` (`id` TEXT NOT NULL, `assistant_id` TEXT NOT NULL, "
            "`name` TEXT NOT NULL, `sort_index` INTEGER NOT NULL DEFAULT 0, `create_at` INTEGER NOT NULL, "
            "PRIMARY KEY(`id`))"
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS `index_conversation_folder_assistant_id` "
            "ON `conversation_folder`(`assistant_id`)"
        )


def hw_25_26(conn):
    for table in ("knowledge_source_assistants", "knowledge_fts", "knowledge_chunks", "knowledge_sources"):
        conn.execute(f"DROP TABLE IF EXISTS `{table}`")


def hw_26_27(conn):
    add_column(conn, "MemoryEntity", "memory_type", "TEXT NOT NULL DEFAULT 'fact'")
    add_column(conn, "MemoryEntity", "created_at", "INTEGER NOT NULL DEFAULT 0")
    add_column(conn, "MemoryEntity", "source_conversation_id", "TEXT")
    add_column(conn, "MemoryEntity", "embedding", "BLOB")
    add_column(conn, "MemoryEntity", "embedding_model_id", "TEXT")
    add_column(conn, "MemoryEntity", "embedding_dimension", "INTEGER")
    conn.execute("CREATE INDEX IF NOT EXISTS `index_MemoryEntity_assistant_id` ON `MemoryEntity` (`assistant_id`)")


def hw_27_28(conn):
    add_column(conn, "ConversationEntity", "rolling_context_summary", "TEXT NOT NULL DEFAULT ''")


def hw_28_29(conn):
    add_column(conn, "workspaces", "shell_compatibility_mode", "INTEGER NOT NULL DEFAULT 0")


def hw_29_30(conn):
    conn.execute(
        "CREATE TABLE IF NOT EXISTS `couple_relationship` (`id` TEXT NOT NULL, `assistant_id` TEXT NOT NULL, "
        "`started_at` INTEGER NOT NULL, `journal_cover` TEXT NOT NULL DEFAULT 'rose_velvet', "
        "`journal_cover_title` TEXT, `journal_cover_date` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
    )
    conn.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_couple_relationship_assistant_id` "
        "ON `couple_relationship` (`assistant_id`)"
    )
    conn.execute(
        "CREATE TABLE IF NOT EXISTS `couple_post` (`id` TEXT NOT NULL, `relationship_id` TEXT NOT NULL, "
        "`author` TEXT NOT NULL, `content` TEXT NOT NULL, `image_uri` TEXT, `liked` INTEGER NOT NULL, "
        "`created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
    )
    conn.execute("CREATE INDEX IF NOT EXISTS `index_couple_post_relationship_id` ON `couple_post` (`relationship_id`)")
    conn.execute("CREATE INDEX IF NOT EXISTS `index_couple_post_created_at` ON `couple_post` (`created_at`)")
    conn.execute(
        "CREATE TABLE IF NOT EXISTS `couple_comment` (`id` TEXT NOT NULL, `relationship_id` TEXT NOT NULL, "
        "`post_id` TEXT NOT NULL, `author` TEXT NOT NULL, `content` TEXT NOT NULL, `created_at` INTEGER NOT NULL, "
        "PRIMARY KEY(`id`))"
    )
    conn.execute(
        "CREATE INDEX IF NOT EXISTS `index_couple_comment_relationship_id` ON `couple_comment` (`relationship_id`)"
    )
    conn.execute("CREATE INDEX IF NOT EXISTS `index_couple_comment_post_id` ON `couple_comment` (`post_id`)")
    conn.execute("CREATE INDEX IF NOT EXISTS `index_couple_comment_created_at` ON `couple_comment` (`created_at`)")
    conn.execute(
        "CREATE TABLE IF NOT EXISTS `couple_diary` (`id` TEXT NOT NULL, `relationship_id` TEXT NOT NULL, "
        "`author` TEXT NOT NULL, `title` TEXT NOT NULL, `content` TEXT NOT NULL, `entry_date` INTEGER NOT NULL, "
        "`folder` TEXT, `paper` TEXT, `reply` TEXT, `reply_at` INTEGER, `reply_paper` TEXT, "
        "`bookmarked` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
    )
    conn.execute("CREATE INDEX IF NOT EXISTS `index_couple_diary_relationship_id` ON `couple_diary` (`relationship_id`)")
    conn.execute("CREATE INDEX IF NOT EXISTS `index_couple_diary_entry_date` ON `couple_diary` (`entry_date`)")
    conn.execute(
        "CREATE TABLE IF NOT EXISTS `couple_diary_folder` (`id` TEXT NOT NULL, `relationship_id` TEXT NOT NULL, "
        "`name` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
    )
    conn.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_couple_diary_folder_relationship_id_name` "
        "ON `couple_diary_folder` (`relationship_id`, `name`)"
    )
    conn.execute(
        "CREATE INDEX IF NOT EXISTS `index_couple_diary_folder_sort_order` ON `couple_diary_folder` (`sort_order`)"
    )
    conn.execute(
        "CREATE TABLE IF NOT EXISTS `couple_anniversary` (`id` TEXT NOT NULL, `relationship_id` TEXT NOT NULL, "
        "`title` TEXT NOT NULL, `event_date` INTEGER NOT NULL, `yearly` INTEGER NOT NULL, "
        "`category` TEXT NOT NULL DEFAULT 'love', `note` TEXT, `favorite` INTEGER NOT NULL DEFAULT 0, "
        "`created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
    )
    conn.execute(
        "CREATE INDEX IF NOT EXISTS `index_couple_anniversary_relationship_id` "
        "ON `couple_anniversary` (`relationship_id`)"
    )
    conn.execute(
        "CREATE INDEX IF NOT EXISTS `index_couple_anniversary_event_date` ON `couple_anniversary` (`event_date`)"
    )


def hw_30_31(conn):
    add_column(conn, "ConversationEntity", "config", "TEXT NOT NULL DEFAULT ''")
    add_column(conn, "ConversationEntity", "rolling_context_summary", "TEXT NOT NULL DEFAULT ''")
    conn.execute(
        "CREATE TABLE IF NOT EXISTS `memory_file` (`assistant_id` TEXT NOT NULL, `path` TEXT NOT NULL, "
        "`content` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`assistant_id`, `path`))"
    )
    conn.execute("DROP TABLE IF EXISTS MemoryEntity")
    conn.execute(
        "CREATE TABLE IF NOT EXISTS `memory_consolidation` (`conversation_id` TEXT NOT NULL, "
        "`consolidated_at` INTEGER NOT NULL, PRIMARY KEY(`conversation_id`), "
        "FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
    )
    conn.execute(
        "INSERT OR IGNORE INTO memory_consolidation (conversation_id, consolidated_at) "
        "SELECT id, update_at FROM ConversationEntity"
    )
    conn.execute(
        "CREATE TABLE IF NOT EXISTS `media_creation_session` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, "
        "`draft` TEXT NOT NULL, `create_at` INTEGER NOT NULL, `update_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
    )
    conn.execute(
        "CREATE TABLE IF NOT EXISTS `media_creation_node` (`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, "
        "`selected_record_id` TEXT NOT NULL, `create_at` INTEGER NOT NULL, PRIMARY KEY(`id`), "
        "FOREIGN KEY(`session_id`) REFERENCES `media_creation_session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
    )
    conn.execute(
        "CREATE INDEX IF NOT EXISTS `index_media_creation_node_session_id` ON `media_creation_node` (`session_id`)"
    )
    conn.execute(
        "CREATE TABLE IF NOT EXISTS `media_creation_record` (`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, "
        "`node_id` TEXT NOT NULL, `provider_id` TEXT NOT NULL, `provider_name` TEXT NOT NULL, "
        "`model_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `prompt` TEXT NOT NULL, `params` TEXT NOT NULL, "
        "`inputs` TEXT NOT NULL, `status` TEXT NOT NULL, `task_id` TEXT, `error` TEXT, `outputs` TEXT NOT NULL, "
        "`create_at` INTEGER NOT NULL, `update_at` INTEGER NOT NULL, PRIMARY KEY(`id`), "
        "FOREIGN KEY(`session_id`) REFERENCES `media_creation_session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , "
        "FOREIGN KEY(`node_id`) REFERENCES `media_creation_node`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
    )
    conn.execute(
        "CREATE INDEX IF NOT EXISTS `index_media_creation_record_session_id` ON `media_creation_record` (`session_id`)"
    )
    conn.execute(
        "CREATE INDEX IF NOT EXISTS `index_media_creation_record_node_id` ON `media_creation_record` (`node_id`)"
    )


def align_to_target(conn):
    """对应 Kotlin 的 DatabaseSchemaReconciler.reconcile：把结构对齐到当前 schema。

    链尾迁移会调用它，用来收拢「上游同版本号、不同结构」的库：缺表补表、缺列补列、
    多余的列删掉。
    """
    entities = {entity["tableName"]: entity for entity in tools.load(TARGET)["entities"]}
    tables = {row[0] for row in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    for name, entity in entities.items():
        if name in tables:
            rebuild_table(conn, name, TARGET)
        else:
            conn.execute(tools.create_sql(entity))
            for index in entity.get("indices", []):
                conn.execute(tools.index_sql(entity, index))


def hw_31_32(conn):
    conn.execute(
        "CREATE TABLE IF NOT EXISTS `translation_history` (`id` TEXT NOT NULL, `source_text` TEXT NOT NULL, "
        "`translated_text` TEXT NOT NULL, `target_language` TEXT NOT NULL, `model_id` TEXT NOT NULL, "
        "`create_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
    )
    conn.execute(
        "CREATE INDEX IF NOT EXISTS `index_translation_history_create_at` ON `translation_history` (`create_at`)"
    )
    align_to_target(conn)


def hw_32_33(conn):
    """对应 Kotlin 的 Migration_32_33：上游的 usage_record 用量流水表 + 结构收敛。"""
    conn.execute(
        "CREATE TABLE IF NOT EXISTS `usage_record` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
        "`created_at` INTEGER NOT NULL, `source` TEXT NOT NULL, `provider_id` TEXT, `model_id` TEXT, "
        "`model_name` TEXT, `assistant_id` TEXT, `conversation_id` TEXT, "
        "`prompt_tokens` INTEGER NOT NULL, `completion_tokens` INTEGER NOT NULL, "
        "`cached_tokens` INTEGER NOT NULL)"
    )
    conn.execute("CREATE INDEX IF NOT EXISTS `index_usage_record_created_at` ON `usage_record` (`created_at`)")
    align_to_target(conn)


HANDWRITTEN = {
    (6, 7): hw_6_7,
    (11, 12): hw_11_12,
    (13, 14): hw_13_14,
    (14, 15): hw_14_15,
    (15, 16): hw_15_16,
    (20, 21): hw_20_21,
    (21, 22): hw_21_22,
    (22, 23): hw_22_23,
    (23, 24): hw_23_24,
    (24, 25): hw_24_25,
    (25, 26): hw_25_26,
    (26, 27): hw_26_27,
    (27, 28): hw_27_28,
    (28, 29): hw_28_29,
    (29, 30): hw_29_30,
    (30, 31): hw_30_31,
    (31, 32): hw_31_32,
    (32, 33): hw_32_33,
}


# ------------------------------------------------- 上游库（rikkahub 官方版）升级路径
#
# fork 合并造成「同版本号、不同结构」：上游 rikkahub 的 v26~v30 库与本仓库同名版本结构
# 并不一致（上游有 config / media_creation_* / memory_file / memory_consolidation /
# translation_history，但没有本地自研的 rolling_context_summary）。用户从上游 APK 覆盖
# 安装到本仓库时，迁移链必须把这些缺口补齐，否则要么 duplicate column 崩库，要么
# Room 校验失败。下面的分叉点由逐版本比对上游 schema JSON 与本地 schema JSON 得到。
UPSTREAM_START = 26

UPSTREAM_EXTRA_TABLES = {
    26: ("media_creation_session", "media_creation_node", "media_creation_record"),
    28: ("memory_file",),
    29: ("memory_consolidation",),
    30: ("translation_history",),
}


def create_upstream_base(conn, version):
    """按上游 rikkahub 的结构建 vN 库（表定义与本地一致，只调整分叉点）。"""
    create_base(conn, version)
    entities = {entity["tableName"]: entity for entity in tools.load(TARGET)["entities"]}
    for start, tables in UPSTREAM_EXTRA_TABLES.items():
        if version < start:
            continue
        for table in tables:
            entity = entities[table]
            conn.execute(tools.create_sql(entity))
            for index in entity.get("indices", []):
                conn.execute(tools.index_sql(entity, index))
    if version >= 26:
        add_column(conn, "workspaces", "shell_compatibility_mode", "INTEGER NOT NULL DEFAULT 0")
    if version >= 27:
        add_column(conn, "ConversationEntity", "config", "TEXT NOT NULL DEFAULT ''")
        conn.execute("DROP TABLE IF EXISTS `MemoryEntity`")
    if version >= 28:
        # 滚动压缩摘要列是本地自研的，上游任何版本都没有
        drop_column(conn, "ConversationEntity", "rolling_context_summary")
    conn.commit()


def run_upstream(version):
    conn = sqlite3.connect(":memory:")
    create_upstream_base(conn, version)
    for _, to, step, _ in chain(version):
        if callable(step):
            step(conn)
        else:
            for statement in step:
                conn.execute(statement)
        conn.execute(f"PRAGMA user_version = {to}")
        conn.commit()
    return compare(conn, TARGET)


def check_upstream_paths():
    failures = 0
    for version in range(UPSTREAM_START, TARGET):
        problems = run_upstream(version)
        if problems:
            failures += 1
            print(f"[FAIL] 上游 v{version} → v{TARGET}")
            for problem in problems[:12]:
                print("   -", problem)
        else:
            print(f"[ OK ] 上游 v{version} → v{TARGET}")
    return failures


# ---------------------------------------------------------------- Kotlin 侧静态检查

MIGRATIONS_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "java", "me", "rerere", "rikkahub", "data", "db", "migrations",
)

# 守卫可以写在若干行之前（比如先 if (!hasColumn(...)) 再 ALTER）
GUARD_WINDOW = 15


def check_raw_add_column():
    """迁移里的裸 ADD COLUMN 会在「库里已有该列」时 duplicate column 崩库。

    上游库同版本号却已含该列是最常见的触发场景（config 就这么崩过），所以每条
    ADD COLUMN 之前必须有 hasColumn / addColumnIfNotExists 守卫。
    AppDatabaseLegacyMigrations 是 v1→v20 的结构 diff 数据，上游同版本一致，不在此列。
    """
    offenders = []
    for name in sorted(os.listdir(MIGRATIONS_DIR)):
        if not (name.startswith("Migration_") and name.endswith(".kt")):
            continue
        with open(os.path.join(MIGRATIONS_DIR, name), encoding="utf-8") as handle:
            lines = handle.readlines()
        code = [
            "" if line.strip().startswith(("//", "*", "/*")) else line
            for line in lines
        ]
        for index, line in enumerate(code):
            if "ADD COLUMN" not in line.upper():
                continue
            window = "".join(code[max(0, index - GUARD_WINDOW):index + 1])
            if "addColumnIfNotExists(" in window or "hasColumn(" in window:
                continue
            offenders.append(f"{name}:{index + 1}: {lines[index].strip()}")
    if offenders:
        print("[FAIL] 迁移里的 ADD COLUMN 缺少幂等守卫：")
        for offender in offenders:
            print("   -", offender)
        return len(offenders)
    print("[ OK ] 迁移里的 ADD COLUMN 都有幂等守卫")
    return 0


def chain(from_version):
    """与 Kotlin 的 AppDatabaseMigrations.path 等价：手写段优先，其余用结构 diff。"""
    steps = []
    current = from_version
    while current < TARGET:
        pair = (current, current + 1)
        if pair in HANDWRITTEN:
            steps.append((current, current + 1, HANDWRITTEN[pair], "handwritten"))
            current += 1
            continue
        statements = tools.diff_statements(tools.load(current), tools.load(current + 1))
        steps.append((current, current + 1, statements, "legacy"))
        current += 1
    return steps


def run(from_version, stepwise):
    conn = sqlite3.connect(":memory:")
    create_base(conn, from_version)
    drift = []
    for frm, to, step, kind in chain(from_version):
        if callable(step):
            step(conn)
        else:
            for statement in step:
                conn.execute(statement)
        conn.execute(f"PRAGMA user_version = {to}")
        conn.commit()
        if stepwise:
            problems = compare(conn, to)
            if problems:
                drift.append((to, kind, problems))
    return drift, compare(conn, TARGET)


def main():
    parser = argparse.ArgumentParser(description="验证「任意旧版本 → 最新版」的迁移链")
    parser.add_argument(
        "--stepwise",
        action="store_true",
        help="打印每一步与对应 schema JSON 的差异（含上游合并遗留的历史漂移，不计入失败）",
    )
    args = parser.parse_args()

    failures = 0
    for version in range(1, TARGET):
        drift, problems = run(version, stepwise=args.stepwise)
        if problems:
            failures += 1
            print(f"[FAIL] v{version} → v{TARGET}")
            for problem in problems[:12]:
                print("   -", problem)
        else:
            print(f"[ OK ] v{version} → v{TARGET}")
        for to, kind, items in drift:
            print(f"   [drift] v{to}（{kind}）：{'; '.join(items[:4])}")
    print()

    upstream_failures = check_upstream_paths()
    print()
    raw_failures = check_raw_add_column()
    print()

    if failures or upstream_failures or raw_failures:
        if failures:
            print(f"{failures}/{TARGET - 1} 个起点失败")
        if upstream_failures:
            print(f"{upstream_failures}/{TARGET - UPSTREAM_START} 个上游起点失败")
        if raw_failures:
            print(f"{raw_failures} 处 ADD COLUMN 缺少幂等守卫")
        return 1
    print(f"全部 {TARGET - 1} 个起点与 {TARGET - UPSTREAM_START} 个上游起点都能升到 v{TARGET}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
