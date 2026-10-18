#!/usr/bin/env python3
"""从 app/schemas 的 Room schema JSON 生成两份 Kotlin 文件。

1. AppDatabaseSchema.kt —— 当前版本的完整结构（表 / 列 / 索引），恢复旧备份时用来对齐结构。
2. migrations/AppDatabaseLegacyMigrations.kt —— 1→20 这一段（@Database 里声明为 autoMigrations）
   的结构变更语句，让恢复路径不必依赖 Room 的迁移容器也能从任意旧版本升上来。

用法：
    python3 tools/generate_db_schema.py            # 用最新 schema 版本生成
    python3 tools/generate_db_schema.py --check     # 只校验生成物是否最新
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import db_schema_tools as tools  # noqa: E402


def kotlin_string(value):
    escaped = value.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")
    return f'"{escaped}"'


def render_schema(version):
    db = tools.load(version)
    lines = [
        "package me.rerere.rikkahub.data.db",
        "",
        "/**",
        f" * 由 tools/generate_db_schema.py 从 app/schemas/.../{version}.json 生成，请勿手改。",
        " *",
        " * 恢复旧备份时用它把暂存库的结构对齐到当前版本：迁移链只保证「能升上来的库」结构正确，",
        " * 上游备份或脏库仍可能缺表缺列，对齐之后 Room 的 schema 校验才会通过。",
        " */",
        "internal object AppDatabaseSchema {",
        f"    const val VERSION = {version}",
        "",
        "    /** Room 为这个版本生成的 identity hash，结构对齐后写回 room_master_table。 */",
        f"    const val IDENTITY_HASH = {kotlin_string(db['identityHash'])}",
        "",
        "    class Table(",
        "        val name: String,",
        "        val createSql: String,",
        "        val columns: List<Pair<String, String>>,",
        "        val indices: List<Pair<String, String>>,",
        "    )",
        "",
        "    val TABLES: List<Table> = listOf(",
    ]
    for entity in db["entities"]:
        name = entity["tableName"]
        lines.append("        Table(")
        lines.append(f"            name = {kotlin_string(name)},")
        lines.append(f"            createSql = {kotlin_string(tools.create_sql(entity))},")
        lines.append("            columns = listOf(")
        for field in entity["fields"]:
            lines.append(
                f"                {kotlin_string(field['columnName'])} to "
                f"{kotlin_string(tools.column_definition(field))},"
            )
        lines.append("            ),")
        indices = entity.get("indices", [])
        if indices:
            lines.append("            indices = listOf(")
            for index in indices:
                lines.append(
                    f"                {kotlin_string(index['name'])} to "
                    f"{kotlin_string(tools.index_sql(entity, index))},"
                )
            lines.append("            ),")
        else:
            lines.append("            indices = emptyList(),")
        lines.append("        ),")
    lines.append("    )")
    lines.append("}")
    lines.append("")
    return "\n".join(lines)


def render_legacy():
    lines = [
        "package me.rerere.rikkahub.data.db.migrations",
        "",
        "/**",
        " * 由 tools/generate_db_schema.py 从 app/schemas 的结构差异生成，请勿手改。",
        " *",
        " * 1 → 20 这一段在 @Database 里声明为 autoMigrations，Room 只在「走 Room 迁移容器」时才用它们。",
        " * 备份恢复为了不依赖容器（历史上漏注册一段就让整次恢复失败），把同一段结构变更固化下来显式执行，",
        " * 于是「任意旧版本 → 最新版」在恢复路径上也是完整的。",
        " */",
        "internal object AppDatabaseLegacyMigrations {",
        "    class Step(val from: Int, val to: Int, val statements: List<String>)",
        "",
        "    val STEPS: List<Step> = listOf(",
    ]
    for frm, to, statements in tools.legacy_steps():
        if statements:
            lines.append(f"        Step({frm}, {to}, listOf(")
            for statement in statements:
                lines.append(f"            {kotlin_string(statement)},")
            lines.append("        )),")
        else:
            lines.append(f"        Step({frm}, {to}, emptyList()),")
    lines.append("    )")
    lines.append("}")
    lines.append("")
    return "\n".join(lines)


def write_or_check(path, content, check):
    if check:
        with open(path) as f:
            current = f.read()
        if current != content:
            print(f"{os.path.relpath(path, tools.ROOT)} 与 schema JSON 不一致，请重新运行 tools/generate_db_schema.py")
            return False
        return True
    with open(path, "w") as f:
        f.write(content)
    return True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="只校验生成物是否最新")
    parser.add_argument("--version", type=int, default=None)
    args = parser.parse_args()

    version = args.version or tools.latest_version()
    ok = write_or_check(tools.SCHEMA_OUTPUT, render_schema(version), args.check)
    ok = write_or_check(tools.LEGACY_OUTPUT, render_legacy(), args.check) and ok
    if not ok:
        return 1
    if args.check:
        print(f"生成物与 schema v{version} 一致")
    else:
        print(f"已更新 AppDatabaseSchema.kt 与 AppDatabaseLegacyMigrations.kt（schema v{version}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
