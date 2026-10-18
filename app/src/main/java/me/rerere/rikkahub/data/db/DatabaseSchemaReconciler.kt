package me.rerere.rikkahub.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 把备份暂存库的结构对齐到当前 schema。
 *
 * 迁移链只保证「从本 App 某个版本升上来的库」结构正确；上游 rikkahub 的备份、被工具改过的库
 * 或者中途失败的升级都可能缺表缺列。Room 打开库时会拿 room_master_table 里的 identity hash
 * 和当前生成代码比对，对不上就直接抛错，所以对齐必须在交给 Room 之前做完。
 *
 * 只做加法为主的对齐：缺表补表、缺列补列、多余的索引和列删掉；不重建已有表，避免误伤数据。
 */
internal object DatabaseSchemaReconciler {
    fun reconcile(db: SupportSQLiteDatabase) {
        val existingTables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            buildList { while (cursor.moveToNext()) cursor.getString(0)?.let { add(it) } }
        }
        for (table in AppDatabaseSchema.TABLES) {
            if (table.name !in existingTables) {
                db.execSQL(table.createSql)
                for ((_, sql) in table.indices) db.execSQL(sql)
                continue
            }
            dropUnexpectedIndices(db, table)
            dropUnexpectedColumns(db, table)
            addMissingColumns(db, table)
            addMissingIndices(db, table)
        }
    }

    private fun columnNames(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            buildList { while (cursor.moveToNext()) cursor.getString(nameIndex)?.let { add(it) } }
        }

    private fun indexNames(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = '$table' AND sql IS NOT NULL")
            .use { cursor ->
                buildList { while (cursor.moveToNext()) cursor.getString(0)?.let { add(it) } }
            }

    private fun addMissingColumns(db: SupportSQLiteDatabase, table: AppDatabaseSchema.Table) {
        val existing = columnNames(db, table.name)
        for ((name, definition) in table.columns) {
            if (name in existing) continue
            // SQLite 不给已有表补 NOT NULL 且无默认值的列，这种库只能留给 Room 报明确的 schema 错误
            if (definition.contains("NOT NULL") && !definition.contains("DEFAULT")) continue
            runCatching { db.execSQL("ALTER TABLE `${table.name}` ADD COLUMN `$name` $definition") }
        }
    }

    private fun dropUnexpectedColumns(db: SupportSQLiteDatabase, table: AppDatabaseSchema.Table) {
        val expected = table.columns.mapTo(mutableSetOf()) { it.first }
        for (name in columnNames(db, table.name)) {
            if (name in expected) continue
            // 主键列和有约束的列删不掉，失败就保持原样，由 Room 的校验给出结论
            runCatching { db.execSQL("ALTER TABLE `${table.name}` DROP COLUMN `$name`") }
        }
    }

    private fun addMissingIndices(db: SupportSQLiteDatabase, table: AppDatabaseSchema.Table) {
        val existing = indexNames(db, table.name)
        for ((name, sql) in table.indices) {
            if (name in existing) continue
            runCatching { db.execSQL(sql) }
        }
    }

    private fun dropUnexpectedIndices(db: SupportSQLiteDatabase, table: AppDatabaseSchema.Table) {
        val expected = table.indices.mapTo(mutableSetOf()) { it.first }
        for (name in indexNames(db, table.name)) {
            if (name in expected) continue
            runCatching { db.execSQL("DROP INDEX IF EXISTS `$name`") }
        }
    }
}
