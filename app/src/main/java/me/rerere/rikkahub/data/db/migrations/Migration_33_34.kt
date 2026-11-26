package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseSchemaReconciler

/**
 * 版本 33 -> 34（上游 c1c3c4d）：
 * message_node 的索引从 (conversation_id) 换成 (conversation_id, node_index)。
 *
 * 加载会话按 node_index 排序分页读取，只有 conversation_id 索引时每一页都要把整个会话取出来排，
 * 几千条消息的会话要好几秒才能打开。复合索引以 conversation_id 开头，外键级联和按会话查询照样能用。
 * 只动索引不动数据，末尾照旧做一次结构收敛。
 */
object Migration_33_34 : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_message_node_conversation_id_node_index` " +
                "ON `message_node` (`conversation_id`, `node_index`)"
        )
        db.execSQL("DROP INDEX IF EXISTS `index_message_node_conversation_id`")
        DatabaseSchemaReconciler.reconcile(db)
    }
}
