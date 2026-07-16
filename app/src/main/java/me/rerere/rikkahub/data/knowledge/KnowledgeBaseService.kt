package me.rerere.rikkahub.data.knowledge

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.ProviderManager
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.KnowledgeBaseDao
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.KnowledgeChunkEntity
import me.rerere.rikkahub.data.db.entity.KnowledgeSourceAssistantEntity
import me.rerere.rikkahub.data.db.entity.KnowledgeSourceEntity
import me.rerere.rikkahub.data.model.KnowledgeSearchResult
import me.rerere.rikkahub.data.model.KnowledgeSource
import me.rerere.rikkahub.data.model.KnowledgeSourceType
import me.rerere.rikkahub.data.model.MatchType
import me.rerere.rikkahub.data.model.KnowledgeChunk
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.Conversation
import java.io.File
import kotlin.math.sqrt
import kotlin.uuid.Uuid

private const val TAG = "KnowledgeBaseService"

class KnowledgeBaseService(
    private val context: Context,
    private val database: AppDatabase,
    private val chunker: DocumentChunker,
    private val providerManager: ProviderManager,
    private val settingsStore: SettingsStore,
) {
    private val dao: KnowledgeBaseDao = database.knowledgeBaseDao()
    private val writableDb get() = database.openHelper.writableDatabase

    // 导入进度
    val importProgress = MutableStateFlow<ImportProgress?>(null)
    val embeddingProgress = MutableStateFlow<EmbeddingProgress?>(null)

    init {
        // 确保 FTS5 表存在（不在 @Entity 中，仅通过 Migration_20_21 的 raw SQL 创建，
        // 但该迁移从未注册到 AppDatabase，导致新装/未走迁移的数据库没有此表）
        try {
            writableDb.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS `knowledge_fts` USING fts5(
                    `text`,
                    `chunk_id` UNINDEXED,
                    `source_id` UNINDEXED,
                    tokenize='unicode61'
                )
            """)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create knowledge_fts FTS table", e)
        }
    }

    // ---- 数据源管理 ----

    /**
     * 将知识源绑定到指定助理（多对多关联表）
     * @param assistantId 非null=绑，null=解除绑定
     */
    suspend fun assignSourceToAssistant(sourceId: String, assistantId: String?) {
        if (assistantId != null) {
            dao.addSourceAssistants(listOf(KnowledgeSourceAssistantEntity(sourceId, assistantId)))
        } else {
            dao.clearSourceAssistants(sourceId)
        }
    }

    /** 获取指定助理已绑定的知识源 ID 列表 */
    suspend fun getBoundSourceIds(assistantId: String): List<String> =
        dao.getSourceIdsForAssistant(assistantId)

    fun getAllSourcesFlow(): Flow<List<KnowledgeSourceEntity>> = dao.getAllSourcesFlow()

    fun getSourcesForAssistantFlow(assistantId: String): Flow<List<KnowledgeSourceEntity>> =
        dao.getSourcesForAssistantFlow(assistantId)

    suspend fun deleteSource(sourceId: String) = withContext(Dispatchers.IO) {
        dao.deleteChunksBySource(sourceId)
        dao.deleteSource(sourceId)
        // 清理FTS（表可能不存在，如旧版DB未正确迁移）
        try {
            writableDb.execSQL("DELETE FROM knowledge_fts WHERE source_id = ?", arrayOf(sourceId))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete FTS entries for $sourceId", e)
        }
    }

    // ---- 文件导入 ----

    suspend fun importFile(
        uri: Uri,
        fileName: String,
        assistantId: String? = null,
        chunkSize: Int = 10,
        overlap: Int = 2,
    ): String? = withContext(Dispatchers.IO) {
        val sourceId = Uuid.random().toString()
        try {
            val text = readDocument(uri) ?: run {
                Log.e(TAG, "Failed to read document: $fileName")
                return@withContext null
            }
            if (text.isBlank()) {
                Log.w(TAG, "Empty document: $fileName")
                return@withContext null
            }

            val sourceName = fileName.substringBeforeLast(".")

            // 1. 创建知识源
            val source = KnowledgeSourceEntity(
                id = sourceId,
                name = sourceName,
                type = KnowledgeSourceType.FILE.name,
                assistantId = assistantId,
                filePath = uri.toString(),
                fileSize = text.length.toLong(),
                createdAt = System.currentTimeMillis(),
            )
            dao.insertSource(source)

            // 2. 分块（双级：parent 1024 + child 256）
            val parentChunks = chunker.chunkDocument(text, chunkSize = 1024, overlap = 200)
            val childChunks = chunker.chunkDocument(text, chunkSize = 256, overlap = 50)

            val parentEntities = parentChunks.chunks.mapIndexed { index, chunk ->
                KnowledgeChunkEntity(
                    id = "${sourceId}_p_$index",
                    sourceId = sourceId,
                    chunkIndex = index,
                    text = chunk.text,
                    sentenceStart = chunk.sentenceStart,
                    sentenceEnd = chunk.sentenceEnd,
                )
            }
            val childEntities = childChunks.chunks.mapIndexed { index, chunk ->
                // 找到所属的 parent chunk
                val parentIdx = parentChunks.chunks.indexOfLast { p ->
                    p.sentenceStart <= chunk.sentenceStart
                }
                KnowledgeChunkEntity(
                    id = "${sourceId}_c_$index",
                    sourceId = sourceId,
                    chunkIndex = index,
                    text = chunk.text,
                    sentenceStart = chunk.sentenceStart,
                    sentenceEnd = chunk.sentenceEnd,
                    parentChunkId = if (parentIdx >= 0) "${sourceId}_p_$parentIdx" else null,
                )
            }
            dao.insertChunks(parentEntities + childEntities)

            // 3. 索引FTS5（只索引 child chunks）
            indexFts5(childEntities)

            // 4. 自动embedding
            autoEmbedIfEnabled(sourceId)

            // 5. 更新计数
            dao.deleteSource(sourceId)
            dao.insertSource(source.copy(chunkCount = parentEntities.size))

            Log.i(TAG, "Imported $fileName: ${parentEntities.size} parent chunks, ${childEntities.size} child chunks")
            sourceId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import file: $fileName", e)
            // 清理已插入的源和chunks（避免显示空文件名但点删除崩溃）
            try { dao.deleteSource(sourceId) } catch (_: Exception) {}
            try { dao.deleteChunksBySource(sourceId) } catch (_: Exception) {}
            null
        }
    }

    // ---- 批量文件夹导入 ----

    suspend fun importFolder(
        folderUri: Uri,
        folderName: String,
        assistantId: String? = null,
        chunkSize: Int = 10,
        overlap: Int = 2,
    ): Int = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(folderUri)
            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                folderUri,
                treeDocId
            )
            val children = resolver.query(childrenUri, null, null, null, null)?.use { cursor ->
                val names = mutableListOf<Pair<String, Uri>>()
                while (cursor.moveToNext()) {
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
                    ))
                    val mime = cursor.getString(cursor.getColumnIndexOrThrow(
                        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
                    ))
                    val docId = cursor.getString(cursor.getColumnIndexOrThrow(
                        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID
                    ))
                    if (mime == "application/vnd.google-apps.folder") continue
                    val childUri = android.provider.DocumentsContract.buildDocumentUri(
                        folderUri.authority ?: return@use null,
                        docId
                    )
                    val lower = name.lowercase()
                    if (lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".epub") ||
                        lower.endsWith(".pptx") || lower.endsWith(".txt") || lower.endsWith(".md")) {
                        names.add(name to childUri)
                    }
                }
                names
            } ?: return@withContext 0

            if (children.isEmpty()) return@withContext 0

            importProgress.value = ImportProgress(children.size, 0, "")
            var imported = 0
            children.forEachIndexed { i, (name, uri) ->
                importProgress.value = ImportProgress(children.size, i, name)
                try {
                    val text = readDocument(uri) ?: return@forEachIndexed
                    if (text.isBlank()) return@forEachIndexed

                    val sourceId = Uuid.random().toString()
                    val source = KnowledgeSourceEntity(
                        id = sourceId,
                        name = name,
                        type = KnowledgeSourceType.BATCH.name,
                        assistantId = assistantId,
                        filePath = uri.toString(),
                        fileSize = text.length.toLong(),
                        createdAt = System.currentTimeMillis(),
                    )
                    dao.insertSource(source)

                    val (parentEntities, childEntities) = createDualChunks(sourceId, text)
                    dao.insertChunks(parentEntities + childEntities)
                    indexFts5(childEntities)
                    dao.deleteSource(sourceId)
                    dao.insertSource(source.copy(chunkCount = parentEntities.size))
                    imported++
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to import $name in folder", e)
                }
            }
            Log.i(TAG, "Folder import complete: $imported files from $folderName")
            importProgress.value = null
            imported
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import folder: $folderName", e)
            0
        }
    }

    // ---- 聊天记录导入 ----

    suspend fun importChatHistory(
        conversation: Conversation,
        assistantId: String? = null,
        maxNodes: Int = 200,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val text = buildString {
                val nodes = conversation.messageNodes.takeLast(maxNodes)
                nodes.forEach { node ->
                    val speaker = if (node.role == MessageRole.ASSISTANT) "AI" else "User"
                    node.messages.forEach { msg ->
                        val msgText = msg.toText()
                        if (msgText.isNotBlank()) {
                            appendLine("$speaker: $msgText")
                        }
                    }
                }
            }
            if (text.isBlank()) return@withContext null

            val title = conversation.title.ifBlank { "聊天记录" }
            val sourceId = Uuid.random().toString()

            val source = KnowledgeSourceEntity(
                id = sourceId,
                name = title,
                type = KnowledgeSourceType.CHAT.name,
                assistantId = assistantId,
                filePath = null,
                fileSize = text.length.toLong(),
                createdAt = System.currentTimeMillis(),
            )
            dao.insertSource(source)

            val (parentEntities, childEntities) = createDualChunks(sourceId, text)
            dao.insertChunks(parentEntities + childEntities)
            indexFts5(childEntities)

            dao.deleteSource(sourceId)
            dao.insertSource(source.copy(chunkCount = parentEntities.size))

            Log.i(TAG, "Imported chat '$title': ${parentEntities.size} parent chunks, ${childEntities.size} child chunks")
            sourceId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import chat history", e)
            null
        }
    }

    // ---- 笔记/文本导入 ----

    suspend fun importText(
        title: String,
        text: String,
        assistantId: String? = null,
        chunkSize: Int = 10,
        overlap: Int = 2,
    ): String? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        try {
            val sourceId = Uuid.random().toString()
            val source = KnowledgeSourceEntity(
                id = sourceId,
                name = title,
                type = KnowledgeSourceType.TEXT.name,
                assistantId = assistantId,
                filePath = null,
                fileSize = text.length.toLong(),
                createdAt = System.currentTimeMillis(),
            )
            dao.insertSource(source)

            val (parentEntities, childEntities) = createDualChunks(sourceId, text)
            dao.insertChunks(parentEntities + childEntities)
            indexFts5(childEntities)

            dao.deleteSource(sourceId)
            dao.insertSource(source.copy(chunkCount = parentEntities.size))

            Log.i(TAG, "Imported text '$title': ${parentEntities.size} parent chunks, ${childEntities.size} child chunks")
            sourceId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import text", e)
            null
        }
    }

    // ---- Embedding（批量异步） ----

    private suspend fun ensureEmbeddings(settings: Settings): String? = withContext(Dispatchers.IO) {
        if (!settings.displaySetting.embeddingEnabled) return@withContext null
        // 只embed未处理的chunks
        val unembedded = writableDb.query(
            "SELECT id, source_id, chunk_index, text, sentence_start, sentence_end FROM knowledge_chunks WHERE embedding IS NULL AND id LIKE ?",
            arrayOf("%_c_%")
        )
        val chunks = mutableListOf<KnowledgeChunkEntity>()
        try {
            while (unembedded.moveToNext()) {
                chunks.add(KnowledgeChunkEntity(
                    id = unembedded.getString(0),
                    sourceId = unembedded.getString(1),
                    chunkIndex = unembedded.getInt(2),
                    text = unembedded.getString(3),
                    sentenceStart = unembedded.getInt(4),
                    sentenceEnd = unembedded.getInt(5),
                ))
            }
        } finally {
            unembedded.close()
        }
        if (chunks.isEmpty()) return@withContext null

        val model = findEmbeddingModel(settings) ?: run {
            embeddingProgress.value = null
            Log.w(TAG, "No embedding model configured")
            return@withContext "未配置向量模型，请在模型设置中添加一个 Embedding 模型"
        }

        Log.i(TAG, "Embedding ${chunks.size} unembedded chunks...")
        embeddingProgress.value = EmbeddingProgress(chunks.size, 0)
        val batchSize = 5
        chunks.chunked(batchSize).forEachIndexed { batchIndex, batch ->
            embeddingProgress.value = EmbeddingProgress(chunks.size, batchIndex * batchSize)
            try {
                val texts = batch.map { it.text }
                val provider = model.findProvider(settings.providers) ?: return@forEachIndexed
                val providerImpl = providerManager.getProviderByType(provider)
                val result = providerImpl.generateEmbedding(provider, EmbeddingGenerationParams(
                    model = model,
                    input = texts,
                ))
                result.embeddings.forEachIndexed { i, embedding ->
                    writableDb.execSQL(
                        "UPDATE knowledge_chunks SET embedding = ?, embedding_dim = ? WHERE id = ?",
                        arrayOf(
                            KnowledgeChunkEntity.floatsToBytes(embedding),
                            embedding.size,
                            batch[i].id
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to embed batch $batchIndex", e)
            }
        }
        Log.i(TAG, "Embedding complete: ${chunks.size} chunks")
        embeddingProgress.value = null
        null
    }

    // ---- 检索 ----

    suspend fun search(
        query: String,
        assistantId: String? = null,
        settings: Settings? = null,
        topK: Int = 5,
        scoreThreshold: Float = 0.25f,
    ): List<KnowledgeSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, KnowledgeSearchResult>()

        // 0. Query Rewrite：生成多个搜索变体提高召回率
        val queries = buildList {
            add(query) // 原问题
            // 变体1：去掉疑问词，只保留关键词
            val stripped = query.replace(Regex("(?i)^(what|how|why|where|when|who|which|tell me|show me|给我|请问|如何|为什么|怎么|哪里|什么是|有哪些)\\s*"), "")
            if (stripped.length >= 4 && stripped != query) add(stripped)
            // 变体2：只取中英文词
            val keywords = Regex("""[\w\u4e00-\u9fff]+""").findAll(query).map { it.value }.joinToString(" ")
            if (keywords.length >= 4 && keywords != query) add(keywords)
        }.distinct()

        // 1. FTS5 精确搜索（所有变体）
        try {
            queries.forEach { q ->
                val ftsQuery = q.trim()
                    .replace(Regex("""[^\w\u4e00-\u9fff\s]"""), " ")
                    .split(Regex("\\s+"))
                    .filter { it.length >= 2 }
                    .joinToString(" AND ")
                if (ftsQuery.isNotBlank()) {
                    val assistFilter = if (assistantId != null) {
                        "AND kc.source_id IN (SELECT source_id FROM knowledge_source_assistants WHERE assistant_id = ?)"
                    } else ""
                    val params = mutableListOf(ftsQuery)
                    if (assistantId != null) params.add(assistantId)
                    params.add((topK * 2).toString())
                    val cursor = writableDb.query("""
                        SELECT kc.id, kc.source_id, kc.chunk_index, kc.text, kc.sentence_start, kc.sentence_end
                        FROM knowledge_fts kf
                        INNER JOIN knowledge_chunks kc ON kc.id = kf.chunk_id
                        WHERE kf.text MATCH ?
                        $assistFilter
                        ORDER BY rank
                        LIMIT ?
                    """, params.toTypedArray())
                    try {
                        while (cursor.moveToNext()) {
                            val chunk = KnowledgeChunkEntity(
                                id = cursor.getString(0),
                                sourceId = cursor.getString(1),
                                chunkIndex = cursor.getInt(2),
                                text = cursor.getString(3),
                                sentenceStart = cursor.getInt(4),
                                sentenceEnd = cursor.getInt(5),
                            )
                            val key = chunk.id
                            val existing = results[key]
                            results[key] = KnowledgeSearchResult(
                                chunk = chunk.toDomain(),
                                score = maxOf(existing?.score ?: 0f, 0.6f),
                                source = KnowledgeSource(),
                                matchType = if (existing?.matchType == MatchType.EMBEDDING) MatchType.HYBRID else MatchType.FTS,
                            )
                        }
                    } finally {
                        cursor.close()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FTS5 search failed: ${e.message}")
        }

        // 2. Embedding 语义搜索（需要 settings）
        if (settings != null) {
            try {
                // 懒embedding：首次搜索时自动embed未处理的chunks
                ensureEmbeddings(settings)

                val model = findEmbeddingModel(settings)
                if (model != null) {
                    val provider = model.findProvider(settings.providers)
                    if (provider != null) {
                        val providerImpl = providerManager.getProviderByType(provider)
                        val queryEmbedding = providerImpl.generateEmbedding(provider, EmbeddingGenerationParams(
                            model = model,
                            input = listOf(query),
                        )).embeddings.firstOrNull()

                        if (queryEmbedding != null) {
                            val embeddedChunks = if (assistantId != null) {
                                dao.getEmbeddedChunksForAssistant(assistantId)
                            } else {
                                dao.getAllEmbeddedChunks()
                            }
                            embeddedChunks.forEach { chunk ->
                                val chunkEmbedding = chunk.embedding?.let { KnowledgeChunkEntity.bytesToFloats(it) }
                                if (chunkEmbedding != null && chunkEmbedding.size == queryEmbedding.size) {
                                    val score = cosineSimilarity(queryEmbedding, chunkEmbedding)
                                    if (score >= scoreThreshold) {
                                        val key = chunk.id
                                        val existing = results[key]
                                        results[key] = KnowledgeSearchResult(
                                            chunk = chunk.toDomain(),
                                            score = maxOf(existing?.score ?: 0f, score),
                                            source = KnowledgeSource(),
                                            matchType = if (existing != null) MatchType.HYBRID else MatchType.EMBEDDING,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Embedding search failed: ${e.message}")
            }
        }

        // 3. 合并排序
        val sorted = results.entries
            .sortedByDescending { it.value.score }
            .take(topK)

        // 4. 上下文窗口扩展：每块前后各取1块，拼成完整段落
        val expanded = sorted.map { (_, result) ->
            val allChunks = dao.getChunksBySource(result.chunk.sourceId.toString())
                .sortedBy { it.sentenceStart }
            val idx = allChunks.indexOfFirst { it.id == result.chunk.id }
            if (idx < 0) return@map result

            val start = maxOf(0, idx - 1)
            val end = minOf(allChunks.size - 1, idx + 1)
            val window = allChunks.subList(start, end + 1)
            val fullText = window.joinToString("") { it.text }

            result.copy(
                chunk = result.chunk.copy(text = fullText),
                score = result.score * 1.1f, // 小加分，有上下文的更优先
            )
        }

        // 5. 填充 source 信息
        expanded.map { result ->
            val sourceEntity = dao.getSourceById(result.chunk.sourceId.toString())
            result.copy(source = sourceEntity?.toDomain() ?: KnowledgeSource())
        }
    }

    // ---- 内部方法 ----

    private fun indexFts5(chunks: List<KnowledgeChunkEntity>) {
        chunks.forEach { chunk ->
            writableDb.execSQL(
                "INSERT INTO knowledge_fts(text, chunk_id, source_id) VALUES (?, ?, ?)",
                arrayOf(chunk.text, chunk.id, chunk.sourceId)
            )
        }
    }

    /** 自动embedding：仅当设置开启时 */
    private suspend fun autoEmbedIfEnabled(sourceId: String) {
        try {
            val settings = settingsStore.settingsFlow.value
            if (settings.displaySetting.autoEmbedOnImport) {
                ensureEmbeddings(settings)
            }
        } catch (_: Exception) { }
    }

    /** 手动embed指定来源 */
    suspend fun embedSource(sourceId: String, settings: Settings): String? = withContext(Dispatchers.IO) {
        try {
            val chunks = dao.getChunksBySource(sourceId)
            if (chunks.isEmpty()) return@withContext null
            ensureEmbeddings(settings)
        } catch (e: Exception) {
            embeddingProgress.value = null
            Log.e(TAG, "Failed to embed source $sourceId", e)
            e.message ?: "向量化失败"
        }
    }

    /** 重命名知识源 */
    suspend fun renameSource(sourceId: String, newName: String) = withContext(Dispatchers.IO) {
        try {
            val source = dao.getSourceById(sourceId) ?: return@withContext
            dao.insertSource(source.copy(name = newName))
            Log.i(TAG, "Renamed source $sourceId → $newName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rename source $sourceId", e)
        }
    }

    /** 编辑知识源的标签 */
    suspend fun editSourceTags(sourceId: String, tags: String) = withContext(Dispatchers.IO) {
        try {
            val source = dao.getSourceById(sourceId) ?: return@withContext
            dao.insertSource(source.copy(tags = tags))
            Log.i(TAG, "Updated tags for source $sourceId: $tags")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update tags for $sourceId", e)
        }
    }

    /** 文档预览：提取文本前300字符用于预览 */
    suspend fun previewDocument(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            readDocument(uri)?.take(300)
        } catch (e: Exception) {
            Log.e(TAG, "Preview failed", e)
            null
        }
    }

    /**
     * 双级分块：生成 parent chunks（1024 tokens）和 child chunks（256 tokens）
     * 仅嵌入 child chunks，搜索时返回 parent chunks 获得更完整的上下文
     */
    private fun createDualChunks(
        sourceId: String,
        text: String,
    ): Pair<List<KnowledgeChunkEntity>, List<KnowledgeChunkEntity>> {
        val parentChunks = chunker.chunkDocument(text, chunkSize = 1024, overlap = 200)
        val childChunks = chunker.chunkDocument(text, chunkSize = 256, overlap = 50)

        val parentEntities = parentChunks.chunks.mapIndexed { index, chunk ->
            KnowledgeChunkEntity(
                id = "${sourceId}_p_$index",
                sourceId = sourceId,
                chunkIndex = index,
                text = chunk.text,
                sentenceStart = chunk.sentenceStart,
                sentenceEnd = chunk.sentenceEnd,
            )
        }
        val childEntities = childChunks.chunks.mapIndexed { index, chunk ->
            val parentIdx = parentChunks.chunks.indexOfLast { p ->
                p.sentenceStart <= chunk.sentenceStart
            }
            KnowledgeChunkEntity(
                id = "${sourceId}_c_$index",
                sourceId = sourceId,
                chunkIndex = index,
                text = chunk.text,
                sentenceStart = chunk.sentenceStart,
                sentenceEnd = chunk.sentenceEnd,
                parentChunkId = if (parentIdx >= 0) "${sourceId}_p_$parentIdx" else null,
            )
        }
        return parentEntities to childEntities
    }

    /** 为自动注入优化的搜索：RRF融合 + 去重 + Token预算 */
    suspend fun searchForInjection(
        query: String,
        assistantId: String? = null,
        settings: Settings,
    ): List<KnowledgeSearchResult> = withContext(Dispatchers.IO) {
        val kbSettings = settings.kbInjectionSettings
        if (!kbSettings.enabled) return@withContext emptyList()

        try {
            // 1. Query Rewrite（可选）
            val queries = if (kbSettings.useQueryRewrite) {
                buildList {
                    add(query)
                    val stripped = query.replace(Regex("(?i)^(what|how|why|where|when|who|which|tell me|show me|给我|请问|如何|为什么|怎么|哪里|什么是|有哪些)\\s*"), "")
                    if (stripped.length >= 4 && stripped != query) add(stripped)
                    val keywords = Regex("""[\w\u4e00-\u9fff]+""").findAll(query).map { it.value }.joinToString(" ")
                    if (keywords.length >= 4 && keywords != query) add(keywords)
                }.distinct()
            } else {
                listOf(query)
            }

            // 2. 并行混合检索（FTS5 + 向量）
            val ftsResults = mutableListOf<KnowledgeSearchResult>()
            val embeddingResults = mutableListOf<KnowledgeSearchResult>()

            queries.forEach { q ->
                // FTS5
                try {
                    val ftsQuery = q.trim()
                        .replace(Regex("""[^\w\u4e00-\u9fff\s]"""), " ")
                        .split(Regex("\\s+"))
                        .filter { it.length >= 2 }
                        .joinToString(" AND ")
                    if (ftsQuery.isNotBlank()) {
                        val assistFilter = if (assistantId != null) {
                            "AND kc.source_id IN (SELECT source_id FROM knowledge_source_assistants WHERE assistant_id = ?)"
                        } else ""
                        val params = mutableListOf(ftsQuery)
                        if (assistantId != null) params.add(assistantId)
                        params.add((kbSettings.chunkCount * 4).toString())
                        val cursor = writableDb.query("""
                            SELECT kc.id, kc.source_id, kc.chunk_index, kc.text, kc.sentence_start, kc.sentence_end
                            FROM knowledge_fts kf
                            INNER JOIN knowledge_chunks kc ON kc.id = kf.chunk_id
                            WHERE kf.text MATCH ?
                            $assistFilter
                            ORDER BY rank
                            LIMIT ?
                        """, params.toTypedArray())
                        try {
                            while (cursor.moveToNext()) {
                                val chunk = KnowledgeChunkEntity(
                                    id = cursor.getString(0),
                                    sourceId = cursor.getString(1),
                                    chunkIndex = cursor.getInt(2),
                                    text = cursor.getString(3),
                                    sentenceStart = cursor.getInt(4),
                                    sentenceEnd = cursor.getInt(5),
                                )
                                val sourceEntity = dao.getSourceById(chunk.sourceId)
                                ftsResults.add(KnowledgeSearchResult(
                                    chunk = chunk.toDomain(),
                                    score = 0.6f,
                                    source = sourceEntity?.toDomain() ?: KnowledgeSource(),
                                    matchType = MatchType.FTS,
                                ))
                            }
                        } finally {
                            cursor.close()
                        }
                    }
                } catch (_: Exception) {}

                // 向量搜索（混合模式）
                if (kbSettings.useHybridSearch) {
                    try {
                        val model = findEmbeddingModel(settings)
                        if (model != null) {
                            ensureEmbeddings(settings)
                            val provider = model.findProvider(settings.providers)
                            if (provider != null) {
                                val providerImpl = providerManager.getProviderByType(provider)
                                val emb = providerImpl.generateEmbedding(provider, EmbeddingGenerationParams(
                                    model = model, input = listOf(q),
                                )).embeddings.firstOrNull()
                                if (emb != null) {
                                    val embeddedChunks = if (assistantId != null) {
                                        dao.getEmbeddedChunksForAssistant(assistantId)
                                    } else {
                                        dao.getAllEmbeddedChunks()
                                    }
                                    embeddedChunks.forEach { chunk ->
                                        val chunkEmb = chunk.embedding?.let { KnowledgeChunkEntity.bytesToFloats(it) }
                                        if (chunkEmb != null && chunkEmb.size == emb.size) {
                                            val score = cosineSimilarity(emb, chunkEmb)
                                            if (score >= kbSettings.scoreThreshold) {
                                                val sourceEntity = dao.getSourceById(chunk.sourceId)
                                                embeddingResults.add(KnowledgeSearchResult(
                                                    chunk = chunk.toDomain(),
                                                    score = score,
                                                    source = sourceEntity?.toDomain() ?: KnowledgeSource(),
                                                    matchType = MatchType.EMBEDDING,
                                                ))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            // 3. RRF 融合
            val merged = rrfMerge(ftsResults, embeddingResults)

            // 4. 去重 + Parent-Document 解析
            val deduped = if (kbSettings.enableDedup) {
                val seen = mutableSetOf<String>()
                merged.filter { seen.add(it.chunk.id) }
            } else {
                merged
            }
            val withParents = resolveParentChunks(deduped)

            // 5. Token预算裁剪
            var tokenCount = 0
            val budget = kbSettings.tokenBudget
            withParents.takeWhile { result ->
                val tokens = estimateTokens(result.chunk.text)
                if (tokenCount + tokens > budget) false
                else { tokenCount += tokens; true }
            }

        } catch (e: Exception) {
            Log.w(TAG, "searchForInjection failed", e)
            emptyList()
        }
    }

    /** RRF: Reciprocal Rank Fusion */
    private fun rrfMerge(
        fts: List<KnowledgeSearchResult>,
        embedding: List<KnowledgeSearchResult>,
        k: Int = 60,
    ): List<KnowledgeSearchResult> {
        val scores = mutableMapOf<String, Float>()
        fts.forEachIndexed { i, r -> scores[r.chunk.id] = (scores[r.chunk.id] ?: 0f) + 1f / (k + i + 1) }
        embedding.forEachIndexed { i, r -> scores[r.chunk.id] = (scores[r.chunk.id] ?: 0f) + 1f / (k + i + 1) }
        return (fts + embedding)
            .distinctBy { it.chunk.id }
            .sortedByDescending { scores[it.chunk.id] }
    }

    /**
     * 将搜索结果中的 child chunks 替换为 parent chunks
     * 提升上下文的完整性
     */
    private suspend fun resolveParentChunks(
        results: List<KnowledgeSearchResult>,
    ): List<KnowledgeSearchResult> {
        val mapped = results.map { result ->
            val chunkId = result.chunk.id
            if (chunkId.contains("_c_")) {
                // 这是一个 child chunk，找 parent
                val parentId = chunkId.replace("_c_", "_p_")
                // 通常 parent index 和 child index 不同，直接从 DAO 查
                val childEntity = dao.getChunkById(chunkId)
                if (childEntity?.parentChunkId != null) {
                    val parentEntity = dao.getChunkById(childEntity.parentChunkId)
                    if (parentEntity != null) {
                        return@map result.copy(
                            chunk = parentEntity.toDomain(),
                            score = result.score * 1.1f, // 小幅加分
                        )
                    }
                }
            }
            result
        }
        // 去重：同一 parent 可能被多个 child 命中
        val seen = mutableSetOf<String>()
        return mapped.filter { seen.add(it.chunk.id) }
    }

    /** 粗略估算中英文混合文本的token数（每字0.75 token） */
    private fun estimateTokens(text: String): Int = maxOf(1, (text.length * 0.75).toInt())

    /** UI搜索（返回可直接展示的结果） */
    data class SearchResultUi(
        val chunkId: String,
        val text: String,
        val sourceName: String,
        val score: Float,
        val matchType: String,
    )

    suspend fun searchForUi(query: String, settings: Settings): List<SearchResultUi> = withContext(Dispatchers.IO) {
        val results = search(query, settings = settings, topK = 20, scoreThreshold = 0.1f)
        results.map { r ->
            val source = dao.getSourceById(r.chunk.sourceId.toString())
            SearchResultUi(
                chunkId = r.chunk.id,
                text = r.chunk.text.take(300),
                sourceName = source?.name ?: "未知",
                score = r.score,
                matchType = r.matchType.name,
            )
        }
    }

    private fun readDocument(uri: Uri): String? {
        return try {
            // content:// URI 必须用 ContentResolver 读取，不能用 uri.toFile()
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return null
            val tempFile = File(context.cacheDir, "kb_import_${Uuid.random().toHexString()}")
            try {
                tempFile.outputStream().use { output ->
                    inputStream.copyTo(output)
                }
                if (tempFile.length() > 10 * 1024 * 1024) return null // 10MB limit

                val name = uri.lastPathSegment?.lowercase() ?: ""
                val result = when {
                    name.endsWith(".pdf") -> PdfParser.parserPdf(tempFile)
                    name.endsWith(".docx") -> DocxParser.parse(tempFile)
                    name.endsWith(".pptx") -> PptxParser.parse(tempFile)
                    name.endsWith(".epub") -> EpubParser.parse(tempFile)
                    name.endsWith(".txt") || name.endsWith(".md") -> tempFile.readText()
                    else -> tempFile.readText() // fallback
                }
                result
            } finally {
                tempFile.delete()
                inputStream.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "readDocument failed", e)
            null
        }
    }

    private fun findEmbeddingModel(settings: Settings): me.rerere.ai.provider.Model? {
        if (!settings.displaySetting.embeddingEnabled) return null
        // 优先使用专用的embedding模型
        val embeddingId = settings.embeddingModelId
        if (embeddingId != null) {
            val model = settings.findModelById(embeddingId)
            if (model != null) return model
        }
        // fallback到聊天模型
        return settings.findModelById(settings.chatModelId)
    }

    private fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
        if (a.size != b.size) return 0f
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dotProduct / denom
    }
}

// ---- 进度数据类型 ----

/** 批量导入进度 */
data class ImportProgress(
    val total: Int,
    val completed: Int,
    val currentFileName: String,
)

/** Embedding 向量化进度 */
data class EmbeddingProgress(
    val total: Int,
    val completed: Int,
)

// ---- 扩展函数 ----

private fun KnowledgeChunkEntity.toDomain() = KnowledgeChunk(
    id = id,
    sourceId = kotlin.uuid.Uuid.parse(sourceId),
    chunkIndex = chunkIndex,
    text = text,
    sentenceStart = sentenceStart,
    sentenceEnd = sentenceEnd,
    embedding = embedding?.let { KnowledgeChunkEntity.bytesToFloats(it) },
)

private fun KnowledgeSourceEntity.toDomain() = KnowledgeSource(
    id = kotlin.uuid.Uuid.parse(id),
    name = name,
    type = try { KnowledgeSourceType.valueOf(type) } catch (_: Exception) { KnowledgeSourceType.FILE },
    assistantId = assistantId?.let { kotlin.uuid.Uuid.parse(it) },
    filePath = filePath,
    fileSize = fileSize,
    chunkCount = chunkCount,
    createdAt = createdAt,
)
