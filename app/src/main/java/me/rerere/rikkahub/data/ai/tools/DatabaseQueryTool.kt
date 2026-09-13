package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import androidx.sqlite.db.SimpleSQLiteQuery

fun createDatabaseQueryTool(database: AppDatabase): Tool = Tool(
    name = "database_query",
    description = "Query the Rikkahub local SQLite database (read-only access).\n\n" +
        "Use this tool to inspect the app's internal data — conversations, messages, and settings.\n\n" +
        "When to use:\n" +
        "- tables: Show all database tables with row counts\n" +
        "- schema: View a table's column definitions\n" +
        "- query: Run a custom SELECT/PRAGMA statement\n" +
        "- search: Search messages and other content for a keyword\n" +
        "- export: Export table data as JSON or CSV\n" +
        "- peek: Show the first rows of a table\n\n" +
        "When NOT to use:\n" +
        "- Modifying data (SELECT/PRAGMA only — no INSERT/UPDATE/DELETE)\n\n" +
        "Args:\n" +
        "- action: tables | schema | query | search | export | peek\n" +
        "- table: Table name (schema, export, peek)\n" +
        "- sql: SQL SELECT or PRAGMA statement (query)\n" +
        "- keyword: Search keyword (search)\n" +
        "- format: json | csv (export, default: json)\n" +
        "- limit: Max rows (default: 50, max: 200)",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                put("enum", buildJsonArray {
                    add("tables"); add("schema"); add("query"); add("search"); add("export"); add("peek")
                })
                put("description", "Operation: tables=list tables+row counts, schema=view table fields, query=run SQL, search=full text search, export=export table, peek=show first N rows")
                })
                put("table", buildJsonObject {
                    put("type", "string")
                    put("description", "Table name (for schema/export actions)")
                })
                put("sql", buildJsonObject {
                    put("type", "string")
                    put("description", "SQL query (for query action). SELECT only, no INSERT/UPDATE/DELETE.")
                })
                put("keyword", buildJsonObject {
                    put("type", "string")
                    put("description", "Search keyword (for search action)")
                })
                put("format", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("json"); add("csv") })
                    put("description", "Export format (for export action, default: json)")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max results (default: 50)")
                })
            },
            required = listOf("action"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val action = obj["action"]?.jsonPrimitive?.contentOrNull ?: error("action required")
        val limit = (obj["limit"]?.jsonPrimitive?.intOrNull ?: 50).coerceIn(1, 200)
        val db = database.openHelper.writableDatabase

        fun requireTable(name: String): String {
            require(name.matches(Regex("[A-Za-z0-9_]+"))) { "invalid table name: $name" }
            return name
        }

        when (action) {
            "tables" -> {
                val tableNames = mutableListOf<String>()
                try {
                    val cursor = db.query(SimpleSQLiteQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' " +
                        "AND name NOT LIKE 'sqlite_%' " +
                        "AND name NOT LIKE 'room_%' " +
                        "AND name NOT LIKE 'android_%' " +
                        "AND name NOT LIKE '%_fts' " +
                        "AND name NOT LIKE '%_fts_%' " +
                        "AND name NOT LIKE '%_idx' " +
                        "AND name NOT LIKE '%_docsize' " +
                        "AND name NOT LIKE '%_config' " +
                        "AND name NOT LIKE '%_content' " +
                        "AND name NOT LIKE '%_data' " +
                        "ORDER BY name"
                    ))
                    while (cursor.moveToNext()) {
                        tableNames.add(cursor.getString(0))
                    }
                    cursor.close()
                } catch (_: Exception) { }
                val result = buildJsonArray {
                    for (table in tableNames) {
                        try {
                            val cursor = db.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM \"$table\""))
                            cursor.moveToFirst()
                            val count = cursor.getInt(0)
                            cursor.close()
                            add(buildJsonObject {
                                put("table", table)
                                put("rows", count)
                            })
                        } catch (_: Exception) {
                            // Table might not exist
                        }
                    }
                }
                listOf(UIMessagePart.Text("Tables:\n" + result.joinToString("\n") {
                    val t = it.jsonObject
                    "  ${t["table"]?.jsonPrimitive?.contentOrNull ?: "?"}: ${t["rows"]?.jsonPrimitive?.intOrNull ?: 0} rows"
                }))
            }
            "schema" -> {
                val table = requireTable(obj["table"]?.jsonPrimitive?.contentOrNull ?: error("table required"))
                val cursor = try {
                    db.query(SimpleSQLiteQuery("PRAGMA table_info(\"$table\")"))
                } catch (e: Exception) {
                    error("Table '$table' not found or not accessible: ${e.message?.take(100)}")
                }
                val columns = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    val type = cursor.getString(cursor.getColumnIndexOrThrow("type"))
                    val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1
                    val pk = cursor.getInt(cursor.getColumnIndexOrThrow("pk")) == 1
                    columns.add("  $name ($type)${if (pk) " PK" else ""}${if (notNull) " NOT NULL" else ""}")
                }
                cursor.close()
                listOf(UIMessagePart.Text("Schema of '$table':\n${columns.joinToString("\n")}"))
            }
            "query" -> {
                val sql = obj["sql"]?.jsonPrimitive?.contentOrNull ?: error("sql required")
                val upperSql = sql.trim().uppercase()
                if (!upperSql.startsWith("SELECT") && !upperSql.startsWith("PRAGMA")) {
                    error("Only SELECT and PRAGMA queries are allowed")
                }
                val cursor = try {
                    db.query(SimpleSQLiteQuery(sql))
                } catch (e: Exception) {
                    error("SQL error: ${e.message?.take(200)}")
                }
                val colNames = cursor.columnNames
                val rows = mutableListOf<JsonObject>()
                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    val row = buildJsonObject {
                        for (col in colNames) {
                            val idx = cursor.getColumnIndexOrThrow(col)
                            when (cursor.getType(idx)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> put(col, JsonNull)
                                android.database.Cursor.FIELD_TYPE_INTEGER -> put(col, JsonPrimitive(cursor.getLong(idx)))
                                android.database.Cursor.FIELD_TYPE_FLOAT -> put(col, JsonPrimitive(cursor.getDouble(idx)))
                                android.database.Cursor.FIELD_TYPE_BLOB -> put(col, JsonPrimitive("[BLOB ${cursor.getBlob(idx).size} bytes]"))
                                else -> put(col, JsonPrimitive(cursor.getString(idx)?.take(500) ?: ""))
                            }
                        }
                    }
                    rows.add(row)
                    count++
                }
                cursor.close()
                val totalMsg = buildString {
                    append("Returned $count rows")
                    if (count == limit) append(" (limit reached)")
                }
                val result = buildJsonObject {
                    put("columns", buildJsonArray { colNames.forEach { add(it) } })
                    put("rows", buildJsonArray { rows.forEach { add(it) } })
                    put("returned", count)
                    put("note", totalMsg)
                }
                listOf(UIMessagePart.Text(result.toString()))
            }
            "search" -> {
                val keyword = obj["keyword"]?.jsonPrimitive?.contentOrNull ?: error("keyword required")
                val searchPattern = "%$keyword%"
                val results = mutableListOf<JsonObject>()

                // FTS5 search on message_fts (much faster than LIKE)
                try {
                    val safeKeyword = keyword.replace("\"", " ").split(" ").filter { it.isNotBlank() }
                        .joinToString(" ") { "\"$it\"" }
                    val ftsCursor = db.query(SimpleSQLiteQuery(
                        "SELECT text FROM message_fts WHERE text MATCH ? LIMIT ${limit.coerceAtMost(20)}",
                        arrayOf<Any?>(safeKeyword)
                    ))
                    while (ftsCursor.moveToNext()) {
                        val text = ftsCursor.getString(0)?.trim() ?: ""
                        if (text.startsWith("search 关键词:") || text.startsWith("以下是关于")) continue
                        results.add(buildJsonObject {
                            put("table", "messages")
                            put("snippet", text.take(200))
                        })
                    }
                    ftsCursor.close()
                } catch (_: Exception) { /* FTS table might not exist */ }

                // LIKE search on other tables
                val tablesToSearch = listOf(
                    "knowledge_chunks" to "text",
                    "knowledge_base_entries" to listOf("title", "content"),
                    "assistant_memories" to "content",
                    "conversations" to listOf("title"),
                    "lorebook_entries" to listOf("name", "content"),
                )

                for ((table, columns) in tablesToSearch) {
                    val colList = when (columns) {
                        is String -> listOf(columns)
                        is List<*> -> @Suppress("UNCHECKED_CAST") columns as List<String>
                        else -> continue
                    }
                    val whereClause = colList.joinToString(" OR ") { "\"$it\" LIKE ?" }
                    val placeholders = colList.map { searchPattern }.toTypedArray<Any?>()
                    try {
                        val cursor = db.query(SimpleSQLiteQuery(
                            "SELECT rowid, * FROM \"$table\" WHERE $whereClause LIMIT ${limit.coerceAtMost(20)}",
                            placeholders
                        ))
                        while (cursor.moveToNext()) {
                            val colNames = cursor.columnNames
                            val firstContent = colList.firstOrNull { col ->
                                val idx = cursor.getColumnIndexOrThrow(col)
                                cursor.getString(idx)?.contains(keyword, ignoreCase = true) == true
                            }
                            val snippet = firstContent?.let { col ->
                                val idx = cursor.getColumnIndexOrThrow(col)
                                cursor.getString(idx)?.take(200)
                            } ?: ""
                            results.add(buildJsonObject {
                                put("table", table)
                                put("snippet", snippet.take(150))
                            })
                        }
                        cursor.close()
                    } catch (_: Exception) { /* skip if table doesn't have column */ }
                }
                listOf(UIMessagePart.Text(
                    if (results.isEmpty()) "No results for '$keyword'"
                    else buildString {
                        appendLine("Search results for '$keyword' (${results.size} matches):")
                        results.forEach { r ->
                            val table = r["table"]?.jsonPrimitive?.contentOrNull ?: ""
                            val snippet = r["snippet"]?.jsonPrimitive?.contentOrNull ?: ""
                            appendLine("  [$table] ${snippet.take(120)}")
                        }
                    }
                ))
            }
            "peek" -> {
                val table = requireTable(obj["table"]?.jsonPrimitive?.contentOrNull ?: error("table required"))
                val cursor = try {
                    db.query(SimpleSQLiteQuery("SELECT * FROM \"$table\" LIMIT $limit"))
                } catch (e: Exception) {
                    error("Table '$table' not found: ${e.message?.take(100)}")
                }
                val colNames = cursor.columnNames
                val rows = mutableListOf<JsonObject>()
                while (cursor.moveToNext()) {
                    val row = buildJsonObject {
                        for (col in colNames) {
                            val idx = cursor.getColumnIndexOrThrow(col)
                            when (cursor.getType(idx)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> put(col, JsonNull)
                                android.database.Cursor.FIELD_TYPE_INTEGER -> put(col, JsonPrimitive(cursor.getLong(idx)))
                                android.database.Cursor.FIELD_TYPE_FLOAT -> put(col, JsonPrimitive(cursor.getDouble(idx)))
                                android.database.Cursor.FIELD_TYPE_BLOB -> put(col, JsonPrimitive("[BLOB ${cursor.getBlob(idx).size} bytes]"))
                                else -> put(col, JsonPrimitive(cursor.getString(idx)?.take(500) ?: ""))
                            }
                        }
                    }
                    rows.add(row)
                }
                cursor.close()
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("columns", buildJsonArray { colNames.forEach { add(it) } })
                    put("rows", buildJsonArray { rows.forEach { add(it) } })
                    put("returned", rows.size)
                }.toString()))
            }
            "export" -> {
                val table = requireTable(obj["table"]?.jsonPrimitive?.contentOrNull ?: error("table required"))
                val format = obj["format"]?.jsonPrimitive?.contentOrNull ?: "json"
                val cursor = try {
                    db.query(SimpleSQLiteQuery("SELECT * FROM \"$table\" LIMIT $limit"))
                } catch (e: Exception) {
                    error("Table '$table' not found: ${e.message?.take(100)}")
                }
                val colNames = cursor.columnNames
                val rows = mutableListOf<JsonObject>()
                while (cursor.moveToNext()) {
                    val row = buildJsonObject {
                        for (col in colNames) {
                            val idx = cursor.getColumnIndexOrThrow(col)
                            when (cursor.getType(idx)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> put(col, JsonNull)
                                android.database.Cursor.FIELD_TYPE_INTEGER -> put(col, JsonPrimitive(cursor.getLong(idx)))
                                android.database.Cursor.FIELD_TYPE_FLOAT -> put(col, JsonPrimitive(cursor.getDouble(idx)))
                                android.database.Cursor.FIELD_TYPE_BLOB -> put(col, JsonPrimitive("[BLOB ${cursor.getBlob(idx).size} bytes]"))
                                else -> put(col, JsonPrimitive(cursor.getString(idx) ?: ""))
                            }
                        }
                    }
                    rows.add(row)
                }
                cursor.close()

                val output = if (format == "csv") {
                    val sb = StringBuilder()
                    sb.appendLine(colNames.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" })
                    for (row in rows) {
                        sb.appendLine(colNames.joinToString(",") { col ->
                            val v = row[col]?.jsonPrimitive?.contentOrNull ?: ""
                            "\"${v.replace("\"", "\"\"")}\""
                        })
                    }
                    sb.toString()
                } else {
                    buildJsonArray { rows.forEach { add(it) } }.toString()
                }
                listOf(UIMessagePart.Text(output.take(30000)))
            }
            else -> error("Unknown action: $action")
        }
    },
)
