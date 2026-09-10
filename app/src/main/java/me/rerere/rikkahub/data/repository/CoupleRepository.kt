package me.rerere.rikkahub.data.repository

import java.util.UUID
import me.rerere.rikkahub.data.db.dao.CoupleDAO
import me.rerere.rikkahub.data.db.entity.*
import org.json.JSONArray

class CoupleRepository(private val dao: CoupleDAO) {
    val relationship = dao.relationship()

    fun posts(id: String) = dao.posts(id)
    fun comments(id: String) = dao.comments(id)
    fun diaries(id: String) = dao.diaries(id)
    fun diaryFolders(id: String) = dao.diaryFolders(id)
    fun anniversaries(id: String) = dao.anniversaries(id)

    suspend fun bind(assistantId: String, startedAt: Long) {
        dao.clearRelationship()
        dao.saveRelationship(
            CoupleRelationshipEntity(
                id = UUID.randomUUID().toString(),
                assistantId = assistantId,
                startedAt = startedAt,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun updateJournalCover(relationship: CoupleRelationshipEntity, cover: String) =
        dao.saveRelationship(relationship.copy(journalCover = cover))

    suspend fun updateJournalInscription(
        relationship: CoupleRelationshipEntity,
        title: String?,
        date: String?,
    ) = dao.saveRelationship(
        relationship.copy(
            journalCoverTitle = title?.trim()?.takeIf { it.isNotBlank() },
            journalCoverDate = date?.trim()?.takeIf { it.isNotBlank() },
        )
    )

    suspend fun addPost(
        relationshipId: String,
        author: String,
        content: String,
        imageUris: List<String> = emptyList(),
    ): CouplePostEntity {
        val post = CouplePostEntity(
            id = UUID.randomUUID().toString(),
            relationshipId = relationshipId,
            author = author,
            content = content,
            imageUri = imageUris.take(9).takeIf { it.isNotEmpty() }?.let { JSONArray(it).toString() },
            createdAt = System.currentTimeMillis(),
        )
        dao.savePost(post)
        return post
    }

    suspend fun toggleLike(post: CouplePostEntity) = dao.savePost(post.copy(liked = !post.liked))

    suspend fun deletePost(post: CouplePostEntity) {
        dao.deleteCommentsForPost(post.id)
        dao.deletePost(post)
    }

    suspend fun addComment(
        relationshipId: String,
        postId: String,
        author: String,
        content: String,
    ): CoupleCommentEntity {
        val comment = CoupleCommentEntity(
            id = UUID.randomUUID().toString(),
            relationshipId = relationshipId,
            postId = postId,
            author = author,
            content = content,
            createdAt = System.currentTimeMillis(),
        )
        dao.saveComment(comment)
        return comment
    }

    suspend fun deleteComment(comment: CoupleCommentEntity) = dao.deleteComment(comment)

    suspend fun addDiary(
        relationshipId: String,
        author: String,
        title: String,
        content: String,
        date: Long,
        folder: String = "全部心事",
        paper: String = "ivory",
    ): CoupleDiaryEntity {
        val entry = CoupleDiaryEntity(
            id = UUID.randomUUID().toString(),
            relationshipId = relationshipId,
            author = author,
            title = title,
            content = content,
            entryDate = date,
            folder = folder,
            paper = paper,
            createdAt = System.currentTimeMillis(),
        )
        dao.saveDiary(entry)
        return entry
    }

    suspend fun updateDiary(
        entry: CoupleDiaryEntity,
        title: String,
        content: String,
        folder: String,
        paper: String,
    ) = dao.saveDiary(
        entry.copy(
            title = title,
            content = content,
            folder = folder,
            paper = paper,
        )
    )

    suspend fun toggleDiaryBookmark(entry: CoupleDiaryEntity) =
        dao.saveDiary(entry.copy(bookmarked = !entry.bookmarked))

    suspend fun saveDiaryReply(
        entry: CoupleDiaryEntity,
        reply: String,
        replyPaper: String = entry.replyPaper ?: "cream_letter",
    ) = dao.saveDiary(
        entry.copy(
            reply = reply,
            replyAt = System.currentTimeMillis(),
            replyPaper = replyPaper,
        )
    )

    suspend fun updateReplyPaper(entry: CoupleDiaryEntity, replyPaper: String) =
        dao.saveDiary(entry.copy(replyPaper = replyPaper))

    suspend fun deleteDiary(entry: CoupleDiaryEntity) = dao.deleteDiary(entry)

    suspend fun addDiaryFolder(relationshipId: String, name: String, sortOrder: Int = 0): CoupleDiaryFolderEntity {
        val folder = CoupleDiaryFolderEntity(
            id = UUID.randomUUID().toString(),
            relationshipId = relationshipId,
            name = name,
            sortOrder = sortOrder,
            createdAt = System.currentTimeMillis(),
        )
        dao.saveDiaryFolder(folder)
        return folder
    }

    suspend fun renameDiaryFolder(folder: CoupleDiaryFolderEntity, newName: String) {
        dao.renameDiaryFolderOnEntries(folder.relationshipId, folder.name, newName)
        dao.saveDiaryFolder(folder.copy(name = newName))
    }

    suspend fun deleteDiaryFolder(folder: CoupleDiaryFolderEntity) {
        dao.clearDiaryFolderOnEntries(folder.relationshipId, folder.name)
        dao.deleteDiaryFolder(folder)
    }

    suspend fun addAnniversary(
        relationshipId: String,
        title: String,
        date: Long,
        yearly: Boolean,
        category: String = "love",
        note: String? = null,
    ) = dao.saveAnniversary(
        CoupleAnniversaryEntity(
            id = UUID.randomUUID().toString(),
            relationshipId = relationshipId,
            title = title,
            eventDate = date,
            yearly = yearly,
            category = category,
            note = note?.trim()?.takeIf { it.isNotBlank() },
            createdAt = System.currentTimeMillis(),
        )
    )

    suspend fun updateAnniversary(
        entry: CoupleAnniversaryEntity,
        title: String,
        date: Long,
        yearly: Boolean,
        category: String,
        note: String?,
    ) = dao.saveAnniversary(
        entry.copy(
            title = title,
            eventDate = date,
            yearly = yearly,
            category = category,
            note = note?.trim()?.takeIf { it.isNotBlank() },
        )
    )

    suspend fun toggleAnniversaryFavorite(entry: CoupleAnniversaryEntity) =
        dao.saveAnniversary(entry.copy(favorite = !entry.favorite))

    suspend fun deleteAnniversary(entry: CoupleAnniversaryEntity) = dao.deleteAnniversary(entry)
}