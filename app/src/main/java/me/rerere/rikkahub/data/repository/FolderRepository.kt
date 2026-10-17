package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FolderDAO
import me.rerere.rikkahub.data.db.entity.FolderEntity
import me.rerere.rikkahub.data.model.Folder
import java.time.Instant
import kotlin.uuid.Uuid

class FolderRepository(
    private val folderDAO: FolderDAO,
    private val conversationDAO: ConversationDAO,
) {
    fun getFoldersOfAssistant(assistantId: Uuid): Flow<List<Folder>> {
        return folderDAO.getFoldersOfAssistant(assistantId.toString())
            .map { list -> list.mapNotNull { it.toFolder() } }
    }

    suspend fun getFolderById(id: Uuid): Folder? {
        return folderDAO.getFolderById(id.toString())?.toFolder()
    }

    suspend fun createFolder(assistantId: Uuid, name: String): Folder {
        val folder = Folder(
            assistantId = assistantId,
            name = name,
            createAt = Instant.now(),
        )
        folderDAO.insert(folder.toEntity())
        return folder
    }

    suspend fun renameFolder(id: Uuid, name: String) {
        folderDAO.rename(id.toString(), name)
    }

    /**
     * 删除文件夹，先把归属该文件夹的会话 folder_id 清空，再删除文件夹本身（不影响会话）。
     */
    suspend fun deleteFolder(id: Uuid) {
        conversationDAO.clearFolder(id.toString())
        folderDAO.deleteById(id.toString())
    }

    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        conversationDAO.updateFolderId(conversationId.toString(), folderId?.toString() ?: "")
    }
}

private fun FolderEntity.toFolder(): Folder? {
    val folderId = runCatching { Uuid.parse(id) }.getOrNull() ?: return null
    val ownerId = runCatching { Uuid.parse(assistantId) }.getOrNull() ?: return null
    return Folder(
        id = folderId,
        assistantId = ownerId,
        name = name,
        sortIndex = sortIndex,
        createAt = Instant.ofEpochMilli(createAt),
    )
}

// 空串或非法 UUID 一律当作未归类，避免脏数据让整个会话列表加载失败
internal fun String.toFolderUuidOrNull(): Uuid? =
    takeIf { it.isNotEmpty() }?.let { runCatching { Uuid.parse(it) }.getOrNull() }

private fun Folder.toEntity(): FolderEntity = FolderEntity(
    id = id.toString(),
    assistantId = assistantId.toString(),
    name = name,
    sortIndex = sortIndex,
    createAt = createAt.toEpochMilli(),
)
