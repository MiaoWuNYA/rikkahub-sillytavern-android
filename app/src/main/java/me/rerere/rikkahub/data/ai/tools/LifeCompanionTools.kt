package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.CoupleRepository
import me.rerere.rikkahub.ui.pages.life.MusicPlaybackSession
import org.json.JSONArray
import org.json.JSONObject

private fun lifeToolError(message: String) = listOf(
    UIMessagePart.Text(buildJsonObject {
        put("success", false)
        put("error", message)
    }.toString())
)

private fun lifeToolOk(builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = listOf(
    UIMessagePart.Text(buildJsonObject {
        put("success", true)
        builder()
    }.toString())
)

private suspend fun requireBoundRelationship(
    repository: CoupleRepository,
    assistantId: String,
): me.rerere.rikkahub.data.db.entity.CoupleRelationshipEntity {
    val relationship = repository.relationship.first() ?: error("还没有建立情侣空间")
    if (relationship.assistantId != assistantId) error("当前角色不是这个情侣空间绑定的角色")
    return relationship
}

fun sharedDiaryTool(repository: CoupleRepository, assistantId: String) = Tool(
    name = "shared_diary",
    description = "Read the bound couple's private journal or write a real assistant reply into a specific diary entry. Use action=list before reply when you do not know diary_id. Never pretend to have read or replied without a successful tool result.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject { put("type", "string"); put("description", "list or reply") })
                put("diary_id", buildJsonObject { put("type", "string"); put("description", "Exact diary id for reply") })
                put("reply", buildJsonObject { put("type", "string"); put("description", "Final in-character reply body") })
                put("limit", buildJsonObject { put("type", "integer"); put("description", "List limit, 1-20") })
            },
            required = listOf("action"),
        )
    },
    execute = { input ->
        val relationship = runCatching { requireBoundRelationship(repository, assistantId) }
            .getOrElse { return@Tool lifeToolError(it.message ?: "无法访问日记") }
        when (input.jsonObject["action"]?.jsonPrimitive?.contentOrNull) {
            "list" -> {
                val limit = input.jsonObject["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 20) ?: 8
                val diaries = repository.diaries(relationship.id).first().take(limit)
                lifeToolOk {
                    put("entries", buildJsonArray {
                        diaries.forEach { d -> add(buildJsonObject {
                            put("diary_id", d.id); put("title", d.title); put("content", d.content.take(3000)); put("entry_date", d.entryDate)
                            put("bookmarked", d.bookmarked); put("has_reply", !d.reply.isNullOrBlank()); d.reply?.let { put("reply", it.take(3000)) }
                        }) }
                    })
                }
            }
            "reply" -> {
                val id = input.jsonObject["diary_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val reply = input.jsonObject["reply"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (id.isBlank() || reply.isBlank()) return@Tool lifeToolError("diary_id 和 reply 不能为空")
                val entry = repository.diaries(relationship.id).first().firstOrNull { it.id == id }
                    ?: return@Tool lifeToolError("没有找到这篇日记")
                repository.saveDiaryReply(entry, reply.take(12000), entry.replyPaper ?: "cream_letter")
                lifeToolOk { put("diary_id", id); put("saved", true) }
            }
            else -> lifeToolError("action 必须是 list 或 reply")
        }
    },
)

fun anniversaryBookTool(repository: CoupleRepository, assistantId: String) = Tool(
    name = "anniversary_book",
    description = "Read or add real entries in the anniversary book bound to the current assistant. Use action=list to inspect existing entries and action=add to save a new anniversary. Dates are epoch milliseconds.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject { put("type", "string"); put("description", "list or add") })
                put("title", buildJsonObject { put("type", "string") })
                put("event_at", buildJsonObject { put("type", "integer"); put("description", "Epoch milliseconds") })
                put("yearly", buildJsonObject { put("type", "boolean") })
                put("category", buildJsonObject { put("type", "string"); put("description", "love, birthday, travel, promise, or memory") })
                put("note", buildJsonObject { put("type", "string") })
            },
            required = listOf("action"),
        )
    },
    execute = { input ->
        val relationship = runCatching { requireBoundRelationship(repository, assistantId) }
            .getOrElse { return@Tool lifeToolError(it.message ?: "无法访问纪念册") }
        when (input.jsonObject["action"]?.jsonPrimitive?.contentOrNull) {
            "list" -> {
                val values = repository.anniversaries(relationship.id).first()
                lifeToolOk {
                    put("entries", buildJsonArray { values.forEach { e -> add(buildJsonObject {
                        put("id", e.id); put("title", e.title); put("event_at", e.eventDate); put("yearly", e.yearly); put("category", e.category); put("favorite", e.favorite); e.note?.let { put("note", it) }
                    }) } })
                }
            }
            "add" -> {
                val title = input.jsonObject["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (title.isBlank()) return@Tool lifeToolError("title 不能为空")
                val at = input.jsonObject["event_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: System.currentTimeMillis()
                val yearly = input.jsonObject["yearly"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
                val category = input.jsonObject["category"]?.jsonPrimitive?.contentOrNull?.takeIf { it in setOf("love","birthday","travel","promise","memory") } ?: "memory"
                val note = input.jsonObject["note"]?.jsonPrimitive?.contentOrNull
                repository.addAnniversary(relationship.id, title.take(200), at, yearly, category, note)
                val saved = repository.anniversaries(relationship.id).first().maxByOrNull { it.createdAt }
                lifeToolOk { put("id", saved?.id.orEmpty()); put("saved", true) }
            }
            else -> lifeToolError("action 必须是 list 或 add")
        }
    },
)

fun lifeMemoTool(context: Context) = Tool(
    name = "life_memo",
    description = "Read, create, or update real memo cards in Life Memo. Use this when the user asks you to remember a task, mark a memo complete, pin it, or review their memos. Do not claim a memo changed unless this succeeds.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject { put("type", "string"); put("description", "list, create, or update") })
                put("id", buildJsonObject { put("type", "integer") })
                put("title", buildJsonObject { put("type", "string") })
                put("detail", buildJsonObject { put("type", "string") })
                put("category", buildJsonObject { put("type", "string"); put("description", "life, todo, idea, together, ai") })
                put("completed", buildJsonObject { put("type", "boolean") })
                put("pinned", buildJsonObject { put("type", "boolean") })
                put("reminder_at", buildJsonObject { put("type", "integer"); put("description", "Optional epoch milliseconds") })
            }, required = listOf("action")
        )
    },
    execute = { input ->
        val prefs = context.getSharedPreferences("tumin_life_hub", Context.MODE_PRIVATE)
        val array = runCatching { JSONArray(prefs.getString("entries", "[]") ?: "[]") }.getOrDefault(JSONArray())
        fun save() = prefs.edit().putString("entries", array.toString()).apply()
        when (input.jsonObject["action"]?.jsonPrimitive?.contentOrNull) {
            "list" -> lifeToolOk { put("memos", buildJsonArray {
                for (i in 0 until array.length()) { val o = array.getJSONObject(i); if (o.optString("section") == "MEMO") add(kotlinx.serialization.json.JsonPrimitive(o.toString())) }
            }) }
            "create" -> {
                val title = input.jsonObject["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(); if (title.isBlank()) return@Tool lifeToolError("title 不能为空")
                val id = System.currentTimeMillis()
                val o = JSONObject().apply {
                    put("id", id); put("section", "MEMO"); put("title", title.take(300)); put("detail", input.jsonObject["detail"]?.jsonPrimitive?.contentOrNull.orEmpty().take(4000)); put("tag", ""); put("createdAt", id)
                    put("memoCategory", input.jsonObject["category"]?.jsonPrimitive?.contentOrNull?.takeIf { it in setOf("life","todo","idea","together","ai") } ?: "life")
                    put("pinned", input.jsonObject["pinned"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false); put("completed", false)
                    input.jsonObject["reminder_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.let { put("reminderAt", it) }
                }
                array.put(o); save(); lifeToolOk { put("id", id); put("saved", true) }
            }
            "update" -> {
                val id = input.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@Tool lifeToolError("id 不能为空")
                var target: JSONObject? = null; for (i in 0 until array.length()) { val o = array.getJSONObject(i); if (o.optLong("id") == id && o.optString("section") == "MEMO") { target = o; break } }
                val o = target ?: return@Tool lifeToolError("没有找到这张备忘录")
                input.jsonObject["title"]?.jsonPrimitive?.contentOrNull?.let { o.put("title", it.take(300)) }
                input.jsonObject["detail"]?.jsonPrimitive?.contentOrNull?.let { o.put("detail", it.take(4000)) }
                input.jsonObject["category"]?.jsonPrimitive?.contentOrNull?.takeIf { it in setOf("life","todo","idea","together","ai") }?.let { o.put("memoCategory", it) }
                input.jsonObject["completed"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()?.let { o.put("completed", it) }
                input.jsonObject["pinned"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()?.let { o.put("pinned", it) }
                input.jsonObject["reminder_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.let { o.put("reminderAt", it) }
                save(); lifeToolOk { put("id", id); put("saved", true) }
            }
            else -> lifeToolError("action 必须是 list、create 或 update")
        }
    },
)

fun lifeCalendarTool(context: Context) = Tool(
    name = "life_calendar",
    description = "Read, create, update, or delete real events in the app's internal Life Calendar. This is different from the Android system calendar. Use exact event ids returned by list for updates/deletes.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("action", buildJsonObject { put("type", "string"); put("description", "list, create, update, or delete") })
            put("id", buildJsonObject { put("type", "integer") }); put("title", buildJsonObject { put("type", "string") }); put("detail", buildJsonObject { put("type", "string") })
            put("event_at", buildJsonObject { put("type", "integer"); put("description", "Epoch milliseconds") }); put("all_day", buildJsonObject { put("type", "boolean") }); put("category", buildJsonObject { put("type", "string"); put("description", "life, date, todo, health, memory") })
        }, required = listOf("action"))
    },
    execute = { input ->
        val prefs = context.getSharedPreferences("tumin_life_calendar", Context.MODE_PRIVATE)
        val array = runCatching { JSONArray(prefs.getString("events", "[]") ?: "[]") }.getOrDefault(JSONArray())
        fun save() = prefs.edit().putString("events", array.toString()).apply()
        when (input.jsonObject["action"]?.jsonPrimitive?.contentOrNull) {
            "list" -> lifeToolOk { put("events", buildJsonArray { for (i in 0 until array.length()) add(kotlinx.serialization.json.JsonPrimitive(array.getJSONObject(i).toString())) }) }
            "create" -> {
                val title = input.jsonObject["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(); if (title.isBlank()) return@Tool lifeToolError("title 不能为空")
                val id = System.currentTimeMillis(); array.put(JSONObject().apply { put("id", id); put("title", title.take(300)); put("detail", input.jsonObject["detail"]?.jsonPrimitive?.contentOrNull.orEmpty().take(4000)); put("eventAt", input.jsonObject["event_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: id); put("allDay", input.jsonObject["all_day"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false); put("category", input.jsonObject["category"]?.jsonPrimitive?.contentOrNull?.takeIf { it in setOf("life","date","todo","health","memory") } ?: "life"); put("createdAt", id) })
                save(); lifeToolOk { put("id", id); put("saved", true) }
            }
            "update" -> {
                val id = input.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@Tool lifeToolError("id 不能为空"); var target: JSONObject? = null
                for (i in 0 until array.length()) if (array.getJSONObject(i).optLong("id") == id) { target = array.getJSONObject(i); break }
                val o = target ?: return@Tool lifeToolError("没有找到这个日程")
                input.jsonObject["title"]?.jsonPrimitive?.contentOrNull?.let { o.put("title", it.take(300)) }; input.jsonObject["detail"]?.jsonPrimitive?.contentOrNull?.let { o.put("detail", it.take(4000)) }; input.jsonObject["event_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.let { o.put("eventAt", it) }; input.jsonObject["all_day"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()?.let { o.put("allDay", it) }; input.jsonObject["category"]?.jsonPrimitive?.contentOrNull?.takeIf { it in setOf("life","date","todo","health","memory") }?.let { o.put("category", it) }
                save(); lifeToolOk { put("id", id); put("saved", true) }
            }
            "delete" -> {
                val id = input.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@Tool lifeToolError("id 不能为空"); val next = JSONArray(); var found = false
                for (i in 0 until array.length()) { val o = array.getJSONObject(i); if (o.optLong("id") == id) found = true else next.put(o) }
                if (!found) return@Tool lifeToolError("没有找到这个日程"); prefs.edit().putString("events", next.toString()).apply(); lifeToolOk { put("id", id); put("deleted", true) }
            }
            else -> lifeToolError("action 必须是 list、create、update 或 delete")
        }
    },
)

fun sharedReadingTool(context: Context) = Tool(
    name = "shared_reading",
    description = "Read current shared-reading state and write real annotations, AI replies to annotations, bookmarks, or shared-reading memories. Use list first to get book_id/note_id when needed.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("action", buildJsonObject { put("type", "string"); put("description", "status, add_note, reply_note, add_bookmark, or add_memory") }); put("book_id", buildJsonObject { put("type", "string") }); put("note_id", buildJsonObject { put("type", "string") }); put("quote", buildJsonObject { put("type", "string") }); put("text", buildJsonObject { put("type", "string") }); put("type", buildJsonObject { put("type", "string") }); put("chapter_index", buildJsonObject { put("type", "integer") }); put("paragraph_index", buildJsonObject { put("type", "integer") })
        }, required = listOf("action"))
    },
    execute = { input ->
        val prefs = context.getSharedPreferences("tumin_reading_space", Context.MODE_PRIVATE)
        val books = runCatching { JSONArray(prefs.getString("books", "[]") ?: "[]") }.getOrDefault(JSONArray()); val notes = runCatching { JSONArray(prefs.getString("notes", "[]") ?: "[]") }.getOrDefault(JSONArray()); val bookmarks = runCatching { JSONArray(prefs.getString("bookmarks", "[]") ?: "[]") }.getOrDefault(JSONArray()); val memories = runCatching { JSONArray(prefs.getString("memories", "[]") ?: "[]") }.getOrDefault(JSONArray())
        when (input.jsonObject["action"]?.jsonPrimitive?.contentOrNull) {
            "status" -> lifeToolOk { put("books", buildJsonArray { for (i in 0 until books.length().coerceAtMost(20)) add(kotlinx.serialization.json.JsonPrimitive(books.getJSONObject(i).toString().take(500))) }); put("notes", buildJsonArray { for (i in 0 until notes.length().coerceAtMost(20)) add(kotlinx.serialization.json.JsonPrimitive(notes.getJSONObject(i).toString().take(500))) }); put("memories", buildJsonArray { for (i in 0 until memories.length().coerceAtMost(20)) add(kotlinx.serialization.json.JsonPrimitive(memories.getJSONObject(i).toString().take(500))) }) }
            "add_note" -> { val bookId = input.jsonObject["book_id"]?.jsonPrimitive?.contentOrNull.orEmpty(); val text = input.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(); if (bookId.isBlank() || text.isBlank()) return@Tool lifeToolError("book_id 和 text 不能为空"); val id = UUID.randomUUID().toString(); notes.put(JSONObject().apply { put("id", id); put("bookId", bookId); put("chapterIndex", input.jsonObject["chapter_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0); put("paragraphIndex", input.jsonObject["paragraph_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0); put("quote", input.jsonObject["quote"]?.jsonPrimitive?.contentOrNull.orEmpty().take(1200)); put("text", text.take(4000)); put("aiReply", ""); put("createdAt", System.currentTimeMillis()) }); prefs.edit().putString("notes", notes.toString()).apply(); lifeToolOk { put("note_id", id); put("saved", true) } }
            "reply_note" -> { val id = input.jsonObject["note_id"]?.jsonPrimitive?.contentOrNull.orEmpty(); val text = input.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(); var target: JSONObject? = null; for (i in 0 until notes.length()) if (notes.getJSONObject(i).optString("id") == id) { target = notes.getJSONObject(i); break }; val o = target ?: return@Tool lifeToolError("没有找到这条批注"); o.put("aiReply", text.take(6000)); prefs.edit().putString("notes", notes.toString()).apply(); lifeToolOk { put("note_id", id); put("saved", true) } }
            "add_bookmark" -> { val bookId = input.jsonObject["book_id"]?.jsonPrimitive?.contentOrNull.orEmpty(); if (bookId.isBlank()) return@Tool lifeToolError("book_id 不能为空"); val id = UUID.randomUUID().toString(); val type = input.jsonObject["type"]?.jsonPrimitive?.contentOrNull?.uppercase()?.takeIf { it in setOf("NORMAL","EMOTION","GUESS","MEMORY") } ?: "NORMAL"; bookmarks.put(JSONObject().apply { put("id", id); put("bookId", bookId); put("chapterIndex", input.jsonObject["chapter_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0); put("paragraphIndex", input.jsonObject["paragraph_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0); put("type", type); put("quote", input.jsonObject["quote"]?.jsonPrimitive?.contentOrNull.orEmpty().take(1200)); put("note", input.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty().take(3000)); put("createdAt", System.currentTimeMillis()) }); prefs.edit().putString("bookmarks", bookmarks.toString()).apply(); lifeToolOk { put("bookmark_id", id); put("saved", true) } }
            "add_memory" -> { val bookId = input.jsonObject["book_id"]?.jsonPrimitive?.contentOrNull.orEmpty(); val text = input.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(); if (bookId.isBlank() || text.isBlank()) return@Tool lifeToolError("book_id 和 text 不能为空"); val id = UUID.randomUUID().toString(); memories.put(JSONObject().apply { put("id", id); put("bookId", bookId); put("type", input.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "讨论内容"); put("text", text.take(4000)); put("createdAt", System.currentTimeMillis()) }); prefs.edit().putString("memories", memories.toString()).apply(); lifeToolOk { put("memory_id", id); put("saved", true) } }
            else -> lifeToolError("未知 shared_reading action")
        }
    },
)

fun sharedMusicTool(context: Context) = Tool(
    name = "shared_music",
    description = "Read the music library and control real in-app playback: list, play, pause, resume, stop, next, or set_together. Only playable local/direct-url tracks can be played inside the app.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { put("action", buildJsonObject { put("type", "string") }); put("track_id", buildJsonObject { put("type", "string") }); put("enabled", buildJsonObject { put("type", "boolean") }) }, required = listOf("action"))
    },
    execute = { input ->
        val prefs = context.getSharedPreferences("tumin_music_space", Context.MODE_PRIVATE); val tracks = runCatching { JSONArray(prefs.getString("tracks", "[]") ?: "[]") }.getOrDefault(JSONArray())
        fun playTrack(o: JSONObject): List<UIMessagePart> { val url = o.optString("playableUrl"); if (url.isBlank()) return lifeToolError("这首歌目前只有来源信息，没有可在应用内播放的音源"); MusicPlaybackSession.play(context, o.optString("id"), o.optString("title", "未命名歌曲"), o.optString("artist", "未知歌手"), o.optString("coverUrl"), url, MusicPlaybackSession.state.value.togetherMode, MusicPlaybackSession.parseLrc(o.optString("lyricsLrc"))); return lifeToolOk { put("track_id", o.optString("id")); put("playing", true) } }
        when (input.jsonObject["action"]?.jsonPrimitive?.contentOrNull) {
            "list" -> lifeToolOk { put("tracks", buildJsonArray { for (i in 0 until tracks.length()) { val o = tracks.getJSONObject(i); add(buildJsonObject { put("track_id", o.optString("id")); put("title", o.optString("title")); put("artist", o.optString("artist")); put("playable", o.optString("playableUrl").isNotBlank()) }) } }); val s = MusicPlaybackSession.state.value; put("current", "${s.trackId}|${s.title}|${s.isPlaying}|${s.togetherMode}") }
            "play" -> { val id = input.jsonObject["track_id"]?.jsonPrimitive?.contentOrNull.orEmpty(); var target: JSONObject? = null; for (i in 0 until tracks.length()) if (tracks.getJSONObject(i).optString("id") == id) { target = tracks.getJSONObject(i); break }; playTrack(target ?: return@Tool lifeToolError("没有找到这首歌")) }
            "pause" -> { if (MusicPlaybackSession.state.value.isPlaying) MusicPlaybackSession.togglePlayPause(); lifeToolOk { put("playing", false) } }
            "resume" -> { if (!MusicPlaybackSession.state.value.isPlaying && MusicPlaybackSession.state.value.active) MusicPlaybackSession.togglePlayPause(); lifeToolOk { put("playing", MusicPlaybackSession.state.value.isPlaying) } }
            "stop" -> { MusicPlaybackSession.stop(); lifeToolOk { put("stopped", true) } }
            "set_together" -> { val enabled = input.jsonObject["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true; MusicPlaybackSession.setTogetherMode(enabled); lifeToolOk { put("together", enabled) } }
            "next" -> { val current = MusicPlaybackSession.state.value.trackId; val playable = (0 until tracks.length()).map { tracks.getJSONObject(it) }.filter { it.optString("playableUrl").isNotBlank() }; if (playable.isEmpty()) return@Tool lifeToolError("歌单里没有可播放歌曲"); val idx = playable.indexOfFirst { it.optString("id") == current }; playTrack(playable[(if (idx < 0) 0 else (idx + 1) % playable.size)]) }
            else -> lifeToolError("未知 shared_music action")
        }
    },
)

/** 生活空间 6 个 AI 工具，其中 shared_diary / anniversary_book 依赖情侣空间绑定关系。 */
fun createLifeCompanionTools(
    context: Context,
    coupleRepository: CoupleRepository?,
    assistantId: String,
): List<Tool> = buildList {
    add(lifeMemoTool(context))
    add(lifeCalendarTool(context))
    add(sharedReadingTool(context))
    add(sharedMusicTool(context))
    if (coupleRepository != null) {
        add(sharedDiaryTool(coupleRepository, assistantId))
        add(anniversaryBookTool(coupleRepository, assistantId))
    }
}
