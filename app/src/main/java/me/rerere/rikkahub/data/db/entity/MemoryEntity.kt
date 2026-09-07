package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(indices = [Index(value = ["assistant_id"])])
data class MemoryEntity(
    @PrimaryKey(true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String = "",
    // 记忆类别（fact / episodic）
    @ColumnInfo(name = "memory_type", defaultValue = "fact")
    val memoryType: String = "fact",
    @ColumnInfo(name = "created_at", defaultValue = "0")
    val createdAt: Long = 0L,
    @ColumnInfo(name = "source_conversation_id")
    val sourceConversationId: String? = null,
    // 嵌入向量（little-endian float 字节流），语义检索用；null 表示未索引
    @ColumnInfo(name = "embedding")
    val embedding: ByteArray? = null,
    @ColumnInfo(name = "embedding_model_id")
    val embeddingModelId: String? = null,
    @ColumnInfo(name = "embedding_dimension")
    val embeddingDimension: Int? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is MemoryEntity && other.id == id

    override fun hashCode(): Int = id
}
