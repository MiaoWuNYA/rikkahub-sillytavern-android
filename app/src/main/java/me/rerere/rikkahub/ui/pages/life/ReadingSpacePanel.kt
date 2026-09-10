package me.rerere.rikkahub.ui.pages.life

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

private const val READING_PREFS = "tumin_reading_space"
private const val BOOKS_KEY = "books"
private const val BOOKMARKS_KEY = "bookmarks"
private const val NOTES_KEY = "notes"
private const val MEMORIES_KEY = "memories"

private data class ReadingBook(
    val id: String,
    val title: String,
    val filePath: String,
    val chapterIndex: Int = 0,
    val paragraphIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = System.currentTimeMillis(),
)

private data class ReadingChapter(val title: String, val body: String)

private enum class BookmarkType(val label: String, val emoji: String) {
    NORMAL("普通书签", "🔖"),
    EMOTION("情绪书签", "💗"),
    GUESS("猜想书签", "💭"),
    MEMORY("记忆书签", "✨"),
}

private data class ReadingBookmark(
    val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val type: BookmarkType,
    val quote: String,
    val note: String,
    val createdAt: Long = System.currentTimeMillis(),
)

private data class ReadingNote(
    val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val quote: String,
    val text: String,
    val aiReply: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

private data class ReadingMemory(
    val id: String,
    val bookId: String,
    val type: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Composable
fun ReadingSpacePanel() {
    val context = LocalContext.current
    var books by remember { mutableStateOf(loadBooks(context)) }
    var bookmarks by remember { mutableStateOf(loadBookmarks(context)) }
    var notes by remember { mutableStateOf(loadNotes(context)) }
    var memories by remember { mutableStateOf(loadMemories(context)) }
    var selectedBookId by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    fun persistBooks(value: List<ReadingBook>) {
        books = value
        saveBooks(context, value)
    }
    fun persistBookmarks(value: List<ReadingBookmark>) {
        bookmarks = value
        saveBookmarks(context, value)
    }
    fun persistNotes(value: List<ReadingNote>) {
        notes = value
        saveNotes(context, value)
    }
    fun persistMemories(value: List<ReadingMemory>) {
        memories = value
        saveMemories(context, value)
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { importTxtBook(context, uri) }
                .onSuccess { book ->
                    persistBooks((books + book).distinctBy { it.id })
                    selectedBookId = book.id
                    importError = null
                }
                .onFailure { importError = it.message ?: "导入失败" }
        }
    }

    val selected = books.firstOrNull { it.id == selectedBookId }
    if (selected == null) {
        BookshelfView(
            books = books,
            bookmarks = bookmarks,
            notes = notes,
            memories = memories,
            importError = importError,
            onImport = { importer.launch(arrayOf("text/plain", "text/*")) },
            onOpen = { selectedBookId = it.id },
            onDelete = { book ->
                runCatching { File(book.filePath).delete() }
                persistBooks(books.filterNot { it.id == book.id })
                persistBookmarks(bookmarks.filterNot { it.bookId == book.id })
                persistNotes(notes.filterNot { it.bookId == book.id })
                persistMemories(memories.filterNot { it.bookId == book.id })
            },
        )
    } else {
        ReadingRoom(
            book = selected,
            bookmarks = bookmarks.filter { it.bookId == selected.id },
            notes = notes.filter { it.bookId == selected.id },
            memories = memories.filter { it.bookId == selected.id },
            onBack = { selectedBookId = null },
            onProgress = { chapter, paragraph ->
                persistBooks(
                    books.map {
                        if (it.id == selected.id) it.copy(
                            chapterIndex = chapter,
                            paragraphIndex = paragraph,
                            lastReadAt = System.currentTimeMillis(),
                        ) else it
                    }
                )
            },
            onAddBookmark = { persistBookmarks(bookmarks + it) },
            onDeleteBookmark = { target -> persistBookmarks(bookmarks.filterNot { it.id == target.id }) },
            onAddNote = { persistNotes(notes + it) },
            onDeleteNote = { target -> persistNotes(notes.filterNot { it.id == target.id }) },
            onAddMemory = { persistMemories(memories + it) },
            onDeleteMemory = { target -> persistMemories(memories.filterNot { it.id == target.id }) },
        )
    }
}

@Composable
private fun BookshelfView(
    books: List<ReadingBook>,
    bookmarks: List<ReadingBookmark>,
    notes: List<ReadingNote>,
    memories: List<ReadingMemory>,
    importError: String?,
    onImport: () -> Unit,
    onOpen: (ReadingBook) -> Unit,
    onDelete: (ReadingBook) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Color(0xFFFFF8F1),
                border = BorderStroke(1.dp, Color(0xFFECDCCB)),
            ) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("📖  OUR READING ROOM", color = Color(0xFF9C6A47), style = MaterialTheme.typography.labelLarge)
                    Text("共读小屋", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color(0xFF4F433B))
                    Text("不是把小说扔进来就算共读。我们会记住读到哪里、在哪一段停下来、又一起想过什么。", color = Color(0xFF7A6B61))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReadingStat("书", books.size, Modifier.weight(1f))
                        ReadingStat("书签", bookmarks.size, Modifier.weight(1f))
                        ReadingStat("批注", notes.size, Modifier.weight(1f))
                        ReadingStat("记忆", memories.size, Modifier.weight(1f))
                    }
                    FilledTonalButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("＋ 导入 TXT 小说") }
                    importError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        if (books.isEmpty()) {
            item {
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(
                        Modifier.fillMaxWidth().padding(30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("📚", style = MaterialTheme.typography.headlineLarge)
                        Text("书架还是空的", fontWeight = FontWeight.SemiBold)
                        Text("先导入一本 TXT 小说，我们从第一页开始。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        items(books.sortedByDescending { it.lastReadAt }, key = { it.id }) { book ->
            val progress = estimateBookProgress(book)
            Card(onClick = { onOpen(book) }, shape = RoundedCornerShape(22.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(width = 68.dp, height = 92.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFE8D7C5),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("📖", style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2)
                        Text("读到第 ${book.chapterIndex + 1} 章 · ${progress}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("继续阅读", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(onClick = { onDelete(book) }) { Text("删除") }
                }
            }
        }
    }
}

@Composable
private fun ReadingStat(label: String, count: Int, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = Color(0xFFF3E9DE)) {
        Column(Modifier.padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(count.toString(), fontWeight = FontWeight.Bold, color = Color(0xFF8A6044))
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF8A7567))
        }
    }
}

@Composable
private fun ReadingRoom(
    book: ReadingBook,
    bookmarks: List<ReadingBookmark>,
    notes: List<ReadingNote>,
    memories: List<ReadingMemory>,
    onBack: () -> Unit,
    onProgress: (Int, Int) -> Unit,
    onAddBookmark: (ReadingBookmark) -> Unit,
    onDeleteBookmark: (ReadingBookmark) -> Unit,
    onAddNote: (ReadingNote) -> Unit,
    onDeleteNote: (ReadingNote) -> Unit,
    onAddMemory: (ReadingMemory) -> Unit,
    onDeleteMemory: (ReadingMemory) -> Unit,
) {
    val chapters = remember(book.id) { loadBookChapters(book) }
    var chapterIndex by remember(book.id) { mutableIntStateOf(book.chapterIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))) }
    var showToc by remember { mutableStateOf(false) }
    var showMarks by remember { mutableStateOf(false) }
    var showMemories by remember { mutableStateOf(false) }
    var selectedParagraph by remember { mutableStateOf<Pair<Int, String>?>(null) }
    val chapter = chapters.getOrNull(chapterIndex) ?: ReadingChapter("正文", "")
    val paragraphs = remember(chapterIndex, chapter.body) { splitParagraphs(chapter.body) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = book.paragraphIndex.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0)))

    LaunchedEffect(chapterIndex) {
        listState.scrollToItem(if (chapterIndex == book.chapterIndex) book.paragraphIndex.coerceAtLeast(0) else 0)
    }
    LaunchedEffect(listState, chapterIndex) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { paragraph -> onProgress(chapterIndex, paragraph) }
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = Color(0xFFFFFBF4), shadowElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("‹ 书架") }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(book.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(chapter.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    TextButton(onClick = { showToc = true }) { Text("目录") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { showMarks = true }) { Text("🔖 ${bookmarks.size}") }
                    TextButton(onClick = { showMarks = true }) { Text("💬 ${notes.size}") }
                    TextButton(onClick = { showMemories = true }) { Text("✨ 共读记忆") }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Text(chapter.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color(0xFF4C423A))
            }
            itemsIndexed(paragraphs, key = { index, _ -> index }) { index, paragraph ->
                val marked = bookmarks.any { it.chapterIndex == chapterIndex && it.paragraphIndex == index }
                val annotated = notes.any { it.chapterIndex == chapterIndex && it.paragraphIndex == index }
                Surface(
                    modifier = Modifier.fillMaxWidth().combinedClickable(
                        onClick = { onProgress(chapterIndex, index) },
                        onLongClick = { selectedParagraph = index to paragraph },
                    ),
                    shape = RoundedCornerShape(12.dp),
                    color = when {
                        annotated -> Color(0xFFFFF1F3)
                        marked -> Color(0xFFFFF7DF)
                        else -> Color.Transparent
                    },
                ) {
                    Text(
                        paragraph,
                        modifier = Modifier.padding(if (marked || annotated) 10.dp else 0.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.45f,
                        fontFamily = FontFamily.Serif,
                        color = Color(0xFF453D38),
                    )
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }

        Surface(color = Color(0xFFFFFBF4), shadowElevation = 4.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(enabled = chapterIndex > 0, onClick = { chapterIndex -= 1; onProgress(chapterIndex, 0) }) { Text("‹ 上一章") }
                Text("${chapterIndex + 1} / ${chapters.size.coerceAtLeast(1)}", style = MaterialTheme.typography.labelMedium)
                TextButton(enabled = chapterIndex < chapters.lastIndex, onClick = { chapterIndex += 1; onProgress(chapterIndex, 0) }) { Text("下一章 ›") }
            }
        }
    }

    selectedParagraph?.let { (paragraphIndex, quote) ->
        ReadingAnnotationDialog(
            quote = quote,
            onDismiss = { selectedParagraph = null },
            onBookmark = { type, note ->
                onAddBookmark(
                    ReadingBookmark(
                        id = UUID.randomUUID().toString(),
                        bookId = book.id,
                        chapterIndex = chapterIndex,
                        paragraphIndex = paragraphIndex,
                        type = type,
                        quote = quote.take(280),
                        note = note,
                    )
                )
                selectedParagraph = null
            },
            onNote = { text ->
                onAddNote(
                    ReadingNote(
                        id = UUID.randomUUID().toString(),
                        bookId = book.id,
                        chapterIndex = chapterIndex,
                        paragraphIndex = paragraphIndex,
                        quote = quote.take(500),
                        text = text,
                    )
                )
                selectedParagraph = null
            },
        )
    }

    if (showToc) {
        AlertDialog(
            onDismissRequest = { showToc = false },
            title = { Text("目录") },
            text = {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    itemsIndexed(chapters) { index, item ->
                        TextButton(
                            onClick = { chapterIndex = index; showToc = false; onProgress(index, 0) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("${if (index == chapterIndex) "• " else ""}${item.title}", modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showToc = false }) { Text("关闭") } },
        )
    }

    if (showMarks) {
        ReadingMarksDialog(bookmarks, notes, onDeleteBookmark, onDeleteNote) { showMarks = false }
    }
    if (showMemories) {
        ReadingMemoriesDialog(book.id, memories, onAddMemory, onDeleteMemory) { showMemories = false }
    }
}

@Composable
private fun ReadingAnnotationDialog(
    quote: String,
    onDismiss: () -> Unit,
    onBookmark: (BookmarkType, String) -> Unit,
    onNote: (String) -> Unit,
) {
    var mode by remember { mutableStateOf("bookmark") }
    var type by remember { mutableStateOf(BookmarkType.NORMAL) }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("在这一段停一下") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFFFF8EF)) {
                    Text("“${quote.take(180)}”", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = Color(0xFF77675B))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == "bookmark", onClick = { mode = "bookmark" }, label = { Text("书签") })
                    FilterChip(selected = mode == "note", onClick = { mode = "note" }, label = { Text("批注给 TA") })
                }
                if (mode == "bookmark") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(BookmarkType.entries) { item ->
                            FilterChip(
                                selected = type == item,
                                onClick = { type = item },
                                label = { Text("${item.emoji} ${item.label}") },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (mode == "bookmark") "给这个书签留句话（可选）" else "你想和 TA 说什么？") },
                    minLines = 3,
                )
                if (mode == "note") {
                    Text("批注会进入共读上下文，TA 在聊天时能看到这一段和你的想法。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                enabled = mode == "bookmark" || text.isNotBlank(),
                onClick = { if (mode == "bookmark") onBookmark(type, text.trim()) else onNote(text.trim()) },
            ) { Text(if (mode == "bookmark") "夹进书里" else "留给 TA") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ReadingMarksDialog(
    bookmarks: List<ReadingBookmark>,
    notes: List<ReadingNote>,
    onDeleteBookmark: (ReadingBookmark) -> Unit,
    onDeleteNote: (ReadingNote) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("书签与批注") },
        text = {
            Column {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("书签 ${bookmarks.size}") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("批注 ${notes.size}") })
                }
                LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tab == 0) {
                        items(bookmarks.sortedByDescending { it.createdAt }, key = { it.id }) { item ->
                            MarkCard("${item.type.emoji} ${item.type.label}", item.quote, item.note) { onDeleteBookmark(item) }
                        }
                    } else {
                        items(notes.sortedByDescending { it.createdAt }, key = { it.id }) { item ->
                            MarkCard("💬 给 TA 的批注", item.quote, item.text) { onDeleteNote(item) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
private fun MarkCard(title: String, quote: String, note: String, onDelete: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onDelete) { Text("删除") }
            }
            Text("“${quote.take(180)}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (note.isNotBlank()) Text(note)
        }
    }
}

@Composable
private fun ReadingMemoriesDialog(
    bookId: String,
    memories: List<ReadingMemory>,
    onAdd: (ReadingMemory) -> Unit,
    onDelete: (ReadingMemory) -> Unit,
    onDismiss: () -> Unit,
) {
    var type by remember { mutableStateOf("喜欢的角色") }
    var text by remember { mutableStateOf("") }
    val types = listOf("喜欢的角色", "特别章节", "共同观点", "讨论内容")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("✨ 共读记忆") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(types) { item -> FilterChip(selected = type == item, onClick = { type = item }, label = { Text(item) }) }
                }
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), label = { Text("把这次阅读留下来") })
                FilledTonalButton(
                    enabled = text.isNotBlank(),
                    onClick = {
                        onAdd(ReadingMemory(UUID.randomUUID().toString(), bookId, type, text.trim()))
                        text = ""
                    },
                ) { Text("记住") }
                HorizontalDivider()
                LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(memories.sortedByDescending { it.createdAt }, key = { it.id }) { item ->
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.type, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    Text(item.text)
                                }
                                TextButton(onClick = { onDelete(item) }) { Text("删") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

private fun importTxtBook(context: Context, uri: Uri): ReadingBook {
    val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "导入的小说.txt"
    val id = UUID.randomUUID().toString()
    val dir = File(context.filesDir, "reading/books").apply { mkdirs() }
    val target = File(dir, "$id.txt")
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: error("无法读取这个 TXT 文件")
    if (target.length() == 0L) error("这个 TXT 文件是空的")
    return ReadingBook(
        id = id,
        title = displayName.substringBeforeLast('.').ifBlank { "未命名小说" },
        filePath = target.absolutePath,
    )
}

private fun loadBookChapters(book: ReadingBook): List<ReadingChapter> {
    val text = runCatching { File(book.filePath).readText() }.getOrDefault("")
    if (text.isBlank()) return listOf(ReadingChapter("正文", "这本书暂时读不到内容。"))
    val pattern = Regex("(?m)^\\s*((?:第[0-9一二三四五六七八九十百千万两〇零]+[章节回卷篇部])|(?:Chapter\\s+\\d+)|(?:CHAPTER\\s+\\d+)).*$")
    val matches = pattern.findAll(text).toList()
    if (matches.size >= 2) {
        return matches.mapIndexed { index, match ->
            val start = match.range.first
            val end = matches.getOrNull(index + 1)?.range?.first ?: text.length
            val block = text.substring(start, end).trim()
            val title = block.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { "第 ${index + 1} 章" }
            val body = block.substringAfter('\n', "").trim().ifBlank { block }
            ReadingChapter(title, body)
        }
    }
    val chunkSize = 12000
    return text.chunked(chunkSize).mapIndexed { index, chunk -> ReadingChapter(if (index == 0) "正文" else "正文 ${index + 1}", chunk.trim()) }
}

private fun splitParagraphs(body: String): List<String> {
    val blankSplit = body.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotBlank() }
    if (blankSplit.size > 2) return blankSplit
    return body.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList().ifEmpty { listOf(body) }
}

private fun estimateBookProgress(book: ReadingBook): Int = (book.chapterIndex * 7 + (book.paragraphIndex / 8)).coerceIn(0, 99)

private fun loadBooks(context: Context): List<ReadingBook> = runCatching {
    val array = JSONArray(context.getSharedPreferences(READING_PREFS, Context.MODE_PRIVATE).getString(BOOKS_KEY, "[]") ?: "[]")
    buildList {
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            add(ReadingBook(o.getString("id"), o.getString("title"), o.getString("filePath"), o.optInt("chapterIndex"), o.optInt("paragraphIndex"), o.optLong("createdAt"), o.optLong("lastReadAt")))
        }
    }
}.getOrDefault(emptyList())

private fun saveBooks(context: Context, books: List<ReadingBook>) {
    val array = JSONArray()
    books.forEach { b -> array.put(JSONObject().apply {
        put("id", b.id); put("title", b.title); put("filePath", b.filePath); put("chapterIndex", b.chapterIndex); put("paragraphIndex", b.paragraphIndex); put("createdAt", b.createdAt); put("lastReadAt", b.lastReadAt)
    }) }
    context.getSharedPreferences(READING_PREFS, Context.MODE_PRIVATE).edit().putString(BOOKS_KEY, array.toString()).apply()
}

private fun loadBookmarks(context: Context): List<ReadingBookmark> = runCatching {
    val array = JSONArray(context.getSharedPreferences(READING_PREFS, Context.MODE_PRIVATE).getString(BOOKMARKS_KEY, "[]") ?: "[]")
    buildList { for (i in 0 until array.length()) { val o = array.getJSONObject(i); add(ReadingBookmark(o.getString("id"), o.getString("bookId"), o.optInt("chapterIndex"), o.optInt("paragraphIndex"), runCatching { BookmarkType.valueOf(o.optString("type")) }.getOrDefault(BookmarkType.NORMAL), o.optString("quote"), o.optString("note"), o.optLong("createdAt"))) } }
}.getOrDefault(emptyList())

private fun saveBookmarks(context: Context, value: List<ReadingBookmark>) {
    val array = JSONArray(); value.forEach { v -> array.put(JSONObject().apply { put("id", v.id); put("bookId", v.bookId); put("chapterIndex", v.chapterIndex); put("paragraphIndex", v.paragraphIndex); put("type", v.type.name); put("quote", v.quote); put("note", v.note); put("createdAt", v.createdAt) }) }
    context.getSharedPreferences(READING_PREFS, Context.MODE_PRIVATE).edit().putString(BOOKMARKS_KEY, array.toString()).apply()
}

private fun loadNotes(context: Context): List<ReadingNote> = runCatching {
    val array = JSONArray(context.getSharedPreferences(READING_PREFS, Context.MODE_PRIVATE).getString(NOTES_KEY, "[]") ?: "[]")
    buildList { for (i in 0 until array.length()) { val o = array.getJSONObject(i); add(ReadingNote(o.getString("id"), o.getString("bookId"), o.optInt("chapterIndex"), o.optInt("paragraphIndex"), o.optString("quote"), o.optString("text"), o.optString("aiReply"), o.optLong("createdAt"))) } }
}.getOrDefault(emptyList())

private fun saveNotes(context: Context, value: List<ReadingNote>) {
    val array = JSONArray(); value.forEach { v -> array.put(JSONObject().apply { put("id", v.id); put("bookId", v.bookId); put("chapterIndex", v.chapterIndex); put("paragraphIndex", v.paragraphIndex); put("quote", v.quote); put("text", v.text); put("aiReply", v.aiReply); put("createdAt", v.createdAt) }) }
    context.getSharedPreferences(READING_PREFS, Context.MODE_PRIVATE).edit().putString(NOTES_KEY, array.toString()).apply()
}

private fun loadMemories(context: Context): List<ReadingMemory> = runCatching {
    val array = JSONArray(context.getSharedPreferences(READING_PREFS, Context.MODE_PRIVATE).getString(MEMORIES_KEY, "[]") ?: "[]")
    buildList { for (i in 0 until array.length()) { val o = array.getJSONObject(i); add(ReadingMemory(o.getString("id"), o.getString("bookId"), o.optString("type"), o.optString("text"), o.optLong("createdAt"))) } }
}.getOrDefault(emptyList())

private fun saveMemories(context: Context, value: List<ReadingMemory>) {
    val array = JSONArray(); value.forEach { v -> array.put(JSONObject().apply { put("id", v.id); put("bookId", v.bookId); put("type", v.type); put("text", v.text); put("createdAt", v.createdAt) }) }
    context.getSharedPreferences(READING_PREFS, Context.MODE_PRIVATE).edit().putString(MEMORIES_KEY, array.toString()).apply()
}
