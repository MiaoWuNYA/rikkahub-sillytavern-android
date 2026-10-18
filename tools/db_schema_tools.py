"""Room schema JSON 的共享工具：加载、版本比较、结构 diff。

generate_db_schema.py 用它生成 AppDatabaseSchema.kt / AppDatabaseLegacyMigrations.kt，
verify_db_migrations.py 用同一份 diff 逻辑在本地跑完整迁移链做校验。
"""
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCHEMA_DIR = os.path.join(ROOT, "app/schemas/me.rerere.rikkahub.data.db.AppDatabase")
PACKAGE_DIR = os.path.join(ROOT, "app/src/main/java/me/rerere/rikkahub/data/db")
SCHEMA_OUTPUT = os.path.join(PACKAGE_DIR, "AppDatabaseSchema.kt")
LEGACY_OUTPUT = os.path.join(PACKAGE_DIR, "migrations/AppDatabaseLegacyMigrations.kt")

# 手写迁移段（Kotlin 里带数据语义的那部分），legacy 段要避开这些版本对
HANDWRITTEN_PAIRS = {
    (6, 7), (11, 12), (13, 14), (14, 15), (15, 16),
    (20, 21), (21, 22), (22, 23), (23, 24), (24, 25),
    (25, 26), (26, 27), (27, 28), (28, 29), (29, 30), (30, 31), (31, 32),
}

# legacy 段覆盖的范围：@Database 的 autoMigrations 声明的区间
LEGACY_END = 20


def schema_versions():
    return sorted(int(f[:-5]) for f in os.listdir(SCHEMA_DIR) if f.endswith(".json"))


def latest_version():
    return schema_versions()[-1]


def load(version):
    with open(os.path.join(SCHEMA_DIR, f"{version}.json")) as f:
        return json.load(f)["database"]


def column_definition(field):
    parts = [field["affinity"]]
    if field.get("notNull"):
        parts.append("NOT NULL")
    if field.get("defaultValue") is not None:
        parts.append(f'DEFAULT {field["defaultValue"]}')
    return " ".join(parts)


def create_sql(entity):
    return entity["createSql"].replace("${TABLE_NAME}", entity["tableName"])


def index_sql(entity, index):
    return index["createSql"].replace("${TABLE_NAME}", entity["tableName"])


def column_changes(old_entity, new_entity):
    """返回 (added, removed, changed)：列级差异，changed 是定义有变的列名。"""
    old_cols = {f["columnName"]: column_definition(f) for f in old_entity["fields"]}
    new_cols = {f["columnName"]: column_definition(f) for f in new_entity["fields"]}
    added = {name: definition for name, definition in new_cols.items() if name not in old_cols}
    removed = [name for name in old_cols if name not in new_cols]
    changed = [
        name for name in new_cols
        if name in old_cols and old_cols[name] != new_cols[name]
    ]
    return added, removed, changed


def diff_statements(cur, nxt):
    """cur → nxt 的结构差异，按「先删索引/列，再加列/表/索引」的安全顺序返回。"""
    statements = []
    cur_tables = {e["tableName"]: e for e in cur["entities"]}
    nxt_tables = {e["tableName"]: e for e in nxt["entities"]}

    for name in cur_tables:
        if name not in nxt_tables:
            statements.append(f"DROP TABLE IF EXISTS `{name}`")

    for name, entity in nxt_tables.items():
        old = cur_tables.get(name)
        if old is None:
            continue
        old_indices = {i["name"]: i for i in old.get("indices", [])}
        new_indices = {i["name"]: i for i in entity.get("indices", [])}
        added, removed, _ = column_changes(old, entity)
        for index_name in old_indices:
            if index_name not in new_indices:
                statements.append(f"DROP INDEX IF EXISTS `{index_name}`")
        for column in removed:
            statements.append(f"ALTER TABLE `{name}` DROP COLUMN `{column}`")
        for column, definition in added.items():
            statements.append(f"ALTER TABLE `{name}` ADD COLUMN `{column}` {definition}")
        for index_name, index in new_indices.items():
            if index_name not in old_indices:
                statements.append(index_sql(entity, index))

    for name, entity in nxt_tables.items():
        if name in cur_tables:
            continue
        statements.append(create_sql(entity))
        for index in entity.get("indices", []):
            statements.append(index_sql(entity, index))

    return statements


def legacy_pairs():
    """需要 legacy 段补齐的版本对（1→20 里不属于手写链的那些）。"""
    pairs = []
    for version in range(1, LEGACY_END):
        pair = (version, version + 1)
        if pair in HANDWRITTEN_PAIRS:
            continue
        pairs.append(pair)
    return pairs


def legacy_steps():
    return [(frm, to, diff_statements(load(frm), load(to))) for frm, to in legacy_pairs()]


def changed_columns_report():
    """列出所有「列定义发生变化」的版本对——ALTER 补不回来，需要人工确认。"""
    report = []
    for version in schema_versions():
        if version >= LEGACY_END:
            break
        cur, nxt = load(version), load(version + 1)
        cur_tables = {e["tableName"]: e for e in cur["entities"]}
        for entity in nxt["entities"]:
            old = cur_tables.get(entity["tableName"])
            if old is None:
                continue
            _, _, changed = column_changes(old, entity)
            if changed:
                report.append((version, version + 1, entity["tableName"], changed))
    return report
