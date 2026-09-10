package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "couple_relationship", indices = [Index("assistant_id", unique = true)])
data class CoupleRelationshipEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("assistant_id") val assistantId: String,
    @ColumnInfo("started_at") val startedAt: Long,
    @ColumnInfo(name = "journal_cover", defaultValue = "rose_velvet") val journalCover: String = "rose_velvet",
    @ColumnInfo("journal_cover_title") val journalCoverTitle: String? = null,
    @ColumnInfo("journal_cover_date") val journalCoverDate: String? = null,
    @ColumnInfo("created_at") val createdAt: Long,
)

@Entity(tableName = "couple_post", indices = [Index("relationship_id"), Index("created_at")])
data class CouplePostEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("relationship_id") val relationshipId: String,
    @ColumnInfo("author") val author: String,
    val content: String,
    @ColumnInfo("image_uri") val imageUri: String? = null,
    val liked: Boolean = false,
    @ColumnInfo("created_at") val createdAt: Long,
)

@Entity(
    tableName = "couple_comment",
    indices = [Index("relationship_id"), Index("post_id"), Index("created_at")],
)
data class CoupleCommentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("relationship_id") val relationshipId: String,
    @ColumnInfo("post_id") val postId: String,
    val author: String,
    val content: String,
    @ColumnInfo("created_at") val createdAt: Long,
)

@Entity(tableName = "couple_diary", indices = [Index("relationship_id"), Index("entry_date")])
data class CoupleDiaryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("relationship_id") val relationshipId: String,
    val author: String,
    val title: String,
    val content: String,
    @ColumnInfo("entry_date") val entryDate: Long,
    val folder: String? = null,
    val paper: String? = null,
    val reply: String? = null,
    @ColumnInfo("reply_at") val replyAt: Long? = null,
    @ColumnInfo("reply_paper") val replyPaper: String? = null,
    @ColumnInfo(name = "bookmarked", defaultValue = "0") val bookmarked: Boolean = false,
    @ColumnInfo("created_at") val createdAt: Long,
)

@Entity(
    tableName = "couple_diary_folder",
    indices = [Index(value = ["relationship_id", "name"], unique = true), Index("sort_order")],
)
data class CoupleDiaryFolderEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("relationship_id") val relationshipId: String,
    val name: String,
    @ColumnInfo("sort_order") val sortOrder: Int = 0,
    @ColumnInfo("created_at") val createdAt: Long,
)

@Entity(tableName = "couple_anniversary", indices = [Index("relationship_id"), Index("event_date")])
data class CoupleAnniversaryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("relationship_id") val relationshipId: String,
    val title: String,
    @ColumnInfo("event_date") val eventDate: Long,
    val yearly: Boolean = true,
    @ColumnInfo(name = "category", defaultValue = "love") val category: String = "love",
    @ColumnInfo("note") val note: String? = null,
    @ColumnInfo(name = "favorite", defaultValue = "0") val favorite: Boolean = false,
    @ColumnInfo("created_at") val createdAt: Long,
)
