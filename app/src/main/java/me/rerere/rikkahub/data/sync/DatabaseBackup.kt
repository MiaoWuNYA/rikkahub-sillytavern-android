package me.rerere.rikkahub.data.sync

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import me.rerere.rikkahub.data.db.AppDatabaseSchema
import me.rerere.rikkahub.data.db.DatabaseSchemaReconciler
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.db.migrations.AppDatabaseMigrations
import java.io.File

internal object DatabaseBackup {
    private const val TAG = "DatabaseBackup"

    const val ARCHIVE_DATABASE = "rikka_hub.db"
    const val WAL = "rikka_hub-wal"
    const val SHM = "rikka_hub-shm"

    /** VACUUM INTO includes committed WAL contents in a consistent, standalone snapshot. */
    fun createSnapshot(database: SupportSQLiteDatabase, destination: File) {
        check(!destination.exists()) { "Backup snapshot already exists" }
        database.execSQL("VACUUM main INTO ?", arrayOf(destination.absolutePath))
    }

    /** Only opens the staged copy. The caller must place its matching WAL beside it first. */
    fun normalize(context: Context, databaseFile: File) {
        require(databaseFile.isFile && databaseFile.length() > 0) { "Backup database is missing or empty" }
        open(context, databaseFile, SQLiteDatabase.OPEN_READWRITE) { database ->
            checkpoint(database)
            database.query("PRAGMA integrity_check").use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext()) {
                    "Backup database failed its integrity check"
                }
            }
        }
        removeSidecars(databaseFile)
    }

    /**
     * 把暂存库升到当前 schema 版本，并让 Room 打开时能通过校验。
     *
     * 刻意不走 Room 的迁移容器：容器内容由 addMigrations 决定，只要有一处漏注册，导入旧版本备份
     * 就会整体失败（"A migration from X to Y was required but not found"）。恢复路径真正关心的是
     * 「备份库能不能变成当前结构」，所以这里显式跑迁移链（覆盖 v1 → 当前版本），再把结构对齐到
     * AppDatabaseSchema，最后删掉 room_master_table，让 Room 按当前 schema 校验并重写 identity hash。
     */
    fun upgradeToCurrent(context: Context, databaseFile: File) {
        val target = AppDatabaseSchema.VERSION
        open(context, databaseFile, SQLiteDatabase.OPEN_READWRITE) { database ->
            val from = database.version
            check(from <= target) {
                "备份数据库版本为 $from，高于当前应用支持的 $target；请更新应用后再恢复这份备份"
            }
            if (from < target) {
                Log.i(TAG, "Restoring backup database from v$from to v$target")
                val path = AppDatabaseMigrations.path(from, target)
                if (path != null) {
                    path.forEach { it.migrate(database) }
                } else {
                    // 理论上不会发生（链覆盖 v1 起），真遇到就只对齐结构，数据留在交集列里
                    Log.w(TAG, "No migration path from v$from to v$target, aligning schema only")
                }
            }
            DatabaseSchemaReconciler.reconcile(database)
            database.version = target
            database.execSQL("DROP TABLE IF EXISTS room_master_table")
            checkpoint(database)
        }
        removeSidecars(databaseFile)
    }

    /**
     * 宽松兜底：写回当前版本的 identity hash，让 Room 直接接受对齐后的结构。
     *
     * 只在 Room 的 schema 校验没过时使用。历史遗留的结构漂移（上游某个版本有、本地中间版本又没有
     * 的列）不该让整份旧备份导不进来——结构已经被 [DatabaseSchemaReconciler] 对齐过，
     * 这里只是把 Room 的校验结论从「拒绝」改成「接受」，问题留给日志。
     */
    fun acceptAlignedSchema(context: Context, databaseFile: File) {
        open(context, databaseFile, SQLiteDatabase.OPEN_READWRITE) { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)"
            )
            database.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, ?)",
                arrayOf(AppDatabaseSchema.IDENTITY_HASH),
            )
            checkpoint(database)
        }
        removeSidecars(databaseFile)
    }

    private inline fun <T> open(
        context: Context,
        databaseFile: File,
        flags: Int,
        block: (SQLiteDatabase) -> T,
    ): T {
        val configuration = SQLiteConfiguration.configure(
            context,
            SQLiteDatabaseConfiguration(databaseFile.absolutePath, flags)
        )
        return SQLiteDatabase.openDatabase(configuration, null) {
            error("Backup database is corrupt")
        }.use(block)
    }

    fun checkpoint(database: SupportSQLiteDatabase) {
        database.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                "Could not checkpoint the backup database"
            }
        }
    }

    fun removeSidecars(databaseFile: File) {
        // All connections must be closed and committed WAL data checkpointed before removal.
        check(File(databaseFile.path + "-wal").length() == 0L) { "Backup WAL was not fully checkpointed" }
        for (suffix in listOf("-wal", "-shm")) {
            val sidecar = File(databaseFile.path + suffix)
            check(!sidecar.exists() || sidecar.delete()) { "Could not remove staged database sidecar" }
        }
    }
}
