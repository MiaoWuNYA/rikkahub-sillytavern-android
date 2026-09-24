package me.rerere.rikkahub.ui.pages.life

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.koin.compose.koinInject
import java.util.UUID
import me.rerere.rikkahub.R
import androidx.compose.ui.res.stringResource

private const val MUSIC_PREFS = "tumin_music_space"
private const val MUSIC_TRACKS_KEY = "tracks"

private enum class MusicSource { LOCAL, DIRECT_URL, NETEASE }

// "未知歌手" 会持久化到 SharedPreferences 中，保持字面量稳定。
private const val MUSIC_UNKNOWN_ARTIST = "未知歌手"

private data class MusicTrack(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val artist: String = MUSIC_UNKNOWN_ARTIST,
    val coverUrl: String = "",
    val source: MusicSource,
    val sourceUrl: String,
    val playableUrl: String = "",
    val neteaseId: String = "",
    val lyricsLrc: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Composable
fun MusicSpacePanel() {
    val context = LocalContext.current
    val client: OkHttpClient = koinInject()
    val scope = rememberCoroutineScope()
    val playback by MusicPlaybackSession.state.collectAsState()
    var tracks by remember { mutableStateOf(loadMusicTracks(context)) }
    var showImportMenu by remember { mutableStateOf(false) }
    var showNeteaseImport by remember { mutableStateOf(false) }
    var showDirectUrlImport by remember { mutableStateOf(false) }
    var togetherMode by remember { mutableStateOf(playback.togetherMode) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val cancelText = stringResource(R.string.cancel)
    val saveText = stringResource(R.string.common_save)
    val importText = stringResource(R.string.life_music_import)
    val neteaseParseFailedText = stringResource(R.string.life_music_error_parse_failed)

    fun persist(updated: List<MusicTrack>) {
        tracks = updated
        saveMusicTracks(context, updated)
    }

    fun play(track: MusicTrack) {
        if (track.playableUrl.isBlank()) {
            openMusicSource(context, track)
            return
        }
        MusicPlaybackSession.play(
            context = context,
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            coverUrl = track.coverUrl,
            playableUrl = track.playableUrl,
            togetherMode = togetherMode,
            lyrics = MusicPlaybackSession.parseLrc(track.lyricsLrc),
        )
    }

    val localImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val name = queryDisplayName(context, uri).substringBeforeLast('.').ifBlank { "本地音乐" }
            val track = MusicTrack(
                title = name,
                source = MusicSource.LOCAL,
                sourceUrl = uri.toString(),
                playableUrl = uri.toString(),
            )
            persist(tracks + track)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 18.dp, 16.dp, if (playback.active) 116.dp else 88.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                MusicPlaylistHero(
                    count = tracks.size,
                    onImport = { showImportMenu = true },
                    onPlayAll = { tracks.firstOrNull { it.playableUrl.isNotBlank() }?.let(::play) },
                )
            }

            if (tracks.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        color = Color(0xFFFFF8F7),
                        border = BorderStroke(1.dp, Color(0xFFF0DEDC)),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(30.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("🎧", style = MaterialTheme.typography.headlineMedium)
                            Text(stringResource(R.string.life_music_empty_title), fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.life_music_empty_desc), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FilledTonalButton(onClick = { showImportMenu = true }) { Text(stringResource(R.string.life_music_empty_import)) }
                        }
                    }
                }
            } else {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.life_music_track_count, tracks.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            togetherMode = !togetherMode
                            MusicPlaybackSession.setTogetherMode(togetherMode)
                        }) {
                            Text(stringResource(if (togetherMode) R.string.life_music_mode_together else R.string.life_music_mode_solo))
                        }
                    }
                }
                itemsIndexed(tracks, key = { _, item -> item.id }) { index, track ->
                    MusicTrackRow(
                        index = index + 1,
                        track = track,
                        selected = playback.trackId == track.id,
                        onClick = { play(track) },
                        onDelete = {
                            if (playback.trackId == track.id) {
                                MusicPlaybackSession.stop()
                            }
                            persist(tracks.filterNot { it.id == track.id })
                        },
                    )
                }
            }
        }

        val activeTrack = tracks.firstOrNull { it.id == playback.trackId }
        if (playback.active && activeTrack != null) {
            MusicMiniPlayer(
                track = activeTrack,
                isPlaying = playback.isPlaying,
                togetherMode = playback.togetherMode,
                modifier = Modifier.align(Alignment.BottomCenter),
                onPlayPause = { MusicPlaybackSession.togglePlayPause() },
                onOpen = { },
            )
        }
    }

    if (showImportMenu) {
        AlertDialog(
            onDismissRequest = { showImportMenu = false },
            shape = RoundedCornerShape(24.dp),
            title = { Text(importText) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = {
                            showImportMenu = false
                            showNeteaseImport = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.life_music_source_netease)) }
                    OutlinedButton(
                        onClick = {
                            showImportMenu = false
                            localImporter.launch(arrayOf("audio/*"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.life_music_source_local)) }
                    OutlinedButton(
                        onClick = {
                            showImportMenu = false
                            showDirectUrlImport = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.life_music_source_url)) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showImportMenu = false }) { Text(cancelText) } },
        )
    }

    if (showNeteaseImport) {
        MusicTextImportDialog(
            title = stringResource(R.string.life_music_netease_title),
            hint = stringResource(R.string.life_music_netease_hint),
            label = stringResource(R.string.life_music_netease_label),
            loading = loading,
            error = error,
            onDismiss = {
                if (!loading) {
                    showNeteaseImport = false
                    error = null
                }
            },
            onImport = { input ->
                loading = true
                error = null
                scope.launch {
                    runCatching { importNeteaseTrack(context, client, input) }
                        .onSuccess { imported ->
                            persist((tracks + imported).distinctBy { it.sourceUrl })
                            showNeteaseImport = false
                        }
                        .onFailure { error = it.message ?: neteaseParseFailedText }
                    loading = false
                }
            },
        )
    }

    if (showDirectUrlImport) {
        MusicDirectUrlDialog(
            onDismiss = { showDirectUrlImport = false },
            onSave = { title, artist, url, lyrics ->
                persist(
                    tracks + MusicTrack(
                        title = title,
                        artist = artist.ifBlank { MUSIC_UNKNOWN_ARTIST },
                        source = MusicSource.DIRECT_URL,
                        sourceUrl = url,
                        playableUrl = url,
                        lyricsLrc = lyrics,
                    )
                )
                showDirectUrlImport = false
            },
        )
    }
}

@Composable
private fun MusicPlaylistHero(count: Int, onImport: () -> Unit, onPlayAll: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = Color(0xFF2E2527),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(112.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF493A3F),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("🎧💕", style = MaterialTheme.typography.headlineMedium)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(stringResource(R.string.life_music_hero_title), color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("OUR PLAYLIST", color = Color.White.copy(alpha = 0.58f), style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.life_music_hero_desc), color = Color.White.copy(alpha = 0.76f), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.life_music_hero_count, count), color = Color.White.copy(alpha = 0.58f), style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onPlayAll,
                    enabled = count > 0,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE9484F)),
                    shape = RoundedCornerShape(18.dp),
                ) { Text(stringResource(R.string.life_music_play_all)) }
                OutlinedButton(
                    onClick = onImport,
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.30f)),
                ) { Text(stringResource(R.string.life_music_import)) }
            }
        }
    }
}

@Composable
private fun MusicTrackRow(
    index: Int,
    track: MusicTrack,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val sourceLocalText = stringResource(R.string.life_music_source_local_short)
    val sourceUrlText = stringResource(R.string.life_music_source_url_short)
    val sourceNeteaseText = stringResource(R.string.life_music_source_netease_short)
    val hasLyricsText = stringResource(R.string.life_music_has_lyrics)
    val openText = stringResource(R.string.life_music_open)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) Color(0xFFFFECEE) else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(index.toString().padStart(2, '0'), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Surface(modifier = Modifier.size(48.dp), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                if (track.coverUrl.isNotBlank()) {
                    AsyncImage(
                        model = track.coverUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("♪") }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(
                    buildString {
                        append(track.artist)
                        append(" · ")
                        append(
                            when (track.source) {
                                MusicSource.LOCAL -> sourceLocalText
                                MusicSource.DIRECT_URL -> sourceUrlText
                                MusicSource.NETEASE -> sourceNeteaseText
                            }
                        )
                        if (track.lyricsLrc.isNotBlank()) append(" · $hasLyricsText")
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (track.playableUrl.isBlank()) {
                Text(openText, color = Color(0xFFE9484F), style = MaterialTheme.typography.labelMedium)
            } else {
                Text("▶", color = Color(0xFFE9484F))
            }
            TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("×") }
        }
    }
}

@Composable
private fun MusicMiniPlayer(
    track: MusicTrack,
    isPlaying: Boolean,
    togetherMode: Boolean,
    modifier: Modifier = Modifier,
    onPlayPause: () -> Unit,
    onOpen: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFF2F292B),
        shadowElevation = 8.dp,
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(Modifier.size(48.dp), shape = CircleShape, color = Color(0xFF4A4043)) {
                if (track.coverUrl.isNotBlank()) {
                    AsyncImage(model = track.coverUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("♪", color = Color.White) }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(track.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (togetherMode) stringResource(R.string.life_music_listening_together) else track.artist,
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FilledIconButton(onClick = onPlayPause, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)) {
                Text(if (isPlaying) "Ⅱ" else "▶")
            }
        }
    }
}

@Composable
private fun MusicTextImportDialog(
    title: String,
    hint: String,
    label: String,
    loading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    val cancelText = stringResource(R.string.cancel)
    val importText = stringResource(R.string.life_music_import)
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(label) },
                    minLines = 4,
                )
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            FilledTonalButton(enabled = value.isNotBlank() && !loading, onClick = { onImport(value.trim()) }) { Text(importText) }
        },
        dismissButton = { TextButton(enabled = !loading, onClick = onDismiss) { Text(cancelText) } },
    )
}

@Composable
private fun MusicDirectUrlDialog(onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var lyrics by remember { mutableStateOf("") }
    val cancelText = stringResource(R.string.cancel)
    val saveText = stringResource(R.string.common_save)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.life_music_url_import_title)) },
        text = {
            Column(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.life_music_url_field_title)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(artist, { artist = it }, label = { Text(stringResource(R.string.life_music_url_field_artist)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.life_music_url_field_url)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    lyrics,
                    { lyrics = it },
                    label = { Text(stringResource(R.string.life_music_url_field_lyrics)) },
                    placeholder = { Text(stringResource(R.string.life_music_url_field_lyrics_hint)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && (url.startsWith("http://") || url.startsWith("https://")),
                onClick = { onSave(title.trim(), artist.trim(), url.trim(), lyrics.trim()) },
            ) { Text(saveText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(cancelText) } },
    )
}

private suspend fun importNeteaseTrack(context: Context, client: OkHttpClient, rawInput: String): MusicTrack = withContext(Dispatchers.IO) {
    val url = extractFirstUrl(rawInput) ?: error(context.getString(R.string.life_music_error_no_link))
    val request = Request.Builder().url(url).get().build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) error(context.getString(R.string.life_music_error_page_open_failed, response.code))
        val finalUrl = response.request.url.toString()
        if (!finalUrl.contains("music.163.com") && !url.contains("music.163.com") && !url.contains("163cn.tv")) {
            error(context.getString(R.string.life_music_error_not_netease))
        }
        val html = response.body.string()
        val type = when {
            Regex("(?:song\\?id=|/song/)(\\d+)").containsMatchIn(finalUrl) -> "song"
            Regex("(?:playlist\\?id=|/playlist/)(\\d+)").containsMatchIn(finalUrl) -> "playlist"
            else -> "song"
        }
        val id = Regex("(?:${type}\\?id=|/${type}/)(\\d+)").find(finalUrl)?.groupValues?.getOrNull(1).orEmpty()
        val title = extractMeta(html, "og:title")
            .ifBlank { extractShareTitle(rawInput) }
            .ifBlank { if (type == "playlist") "网易云歌单${id.takeIf { it.isNotBlank() }?.let { " #$it" }.orEmpty()}" else "网易云歌曲${id.takeIf { it.isNotBlank() }?.let { " #$it" }.orEmpty()}" }
        val description = extractMeta(html, "og:description")
        val artist = description.substringBefore("。").substringBefore("-").trim().ifBlank { "网易云音乐" }
        val cover = extractMeta(html, "og:image")
        val lyric = if (type == "song" && id.isNotBlank()) fetchNeteaseLyric(client, id) else ""
        MusicTrack(
            title = title.cleanNeteaseTitle(),
            artist = if (type == "playlist") "网易云歌单" else artist,
            coverUrl = cover,
            source = MusicSource.NETEASE,
            sourceUrl = finalUrl,
            playableUrl = "",
            neteaseId = id,
            lyricsLrc = lyric,
        )
    }
}

private fun fetchNeteaseLyric(client: OkHttpClient, id: String): String = runCatching {
    val request = Request.Builder()
        .url("https://music.163.com/api/song/lyric?id=$id&lv=1&kv=-1&tv=-1")
        .header("Referer", "https://music.163.com/")
        .get()
        .build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@runCatching ""
        val body = response.body.string()
        JSONObject(body).optJSONObject("lrc")?.optString("lyric").orEmpty()
    }
}.getOrDefault("")

private fun extractFirstUrl(text: String): String? =
    Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
        .find(text)
        ?.value
        ?.trimEnd('。', '，', ',', ')', '）', ']', '】')

private fun extractShareTitle(text: String): String =
    text.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() && !it.startsWith("http") && !it.contains("复制") }
        .orEmpty()
        .take(80)

private fun extractMeta(html: String, property: String): String {
    val escaped = Regex.escape(property)
    val first = Regex("<meta[^>]+property=[\\\"']$escaped[\\\"'][^>]+content=[\\\"']([^\\\"']*)[\\\"'][^>]*>", RegexOption.IGNORE_CASE)
        .find(html)?.groupValues?.getOrNull(1)
    val second = Regex("<meta[^>]+content=[\\\"']([^\\\"']*)[\\\"'][^>]+property=[\\\"']$escaped[\\\"'][^>]*>", RegexOption.IGNORE_CASE)
        .find(html)?.groupValues?.getOrNull(1)
    return (first ?: second).orEmpty().htmlUnescape()
}

private fun String.htmlUnescape(): String =
    replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

private fun String.cleanNeteaseTitle(): String =
    removeSuffix(" - 网易云音乐")
        .removeSuffix("- 网易云音乐")
        .trim()

private fun openMusicSource(context: Context, track: MusicTrack) {
    val uri = runCatching { Uri.parse(track.sourceUrl) }.getOrNull() ?: return
    val appIntent = Intent(Intent.ACTION_VIEW, uri)
    runCatching { context.startActivity(appIntent) }
}

private fun queryDisplayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

private fun loadMusicTracks(context: Context): List<MusicTrack> = runCatching {
    val raw = context.getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE).getString(MUSIC_TRACKS_KEY, "[]") ?: "[]"
    val array = JSONArray(raw)
    buildList {
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            add(
                MusicTrack(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    title = item.optString("title", "未命名歌曲"),
                    artist = item.optString("artist", "未知歌手"),
                    coverUrl = item.optString("coverUrl"),
                    source = runCatching { MusicSource.valueOf(item.optString("source")) }.getOrDefault(MusicSource.NETEASE),
                    sourceUrl = item.optString("sourceUrl"),
                    playableUrl = item.optString("playableUrl"),
                    neteaseId = item.optString("neteaseId"),
                    lyricsLrc = item.optString("lyricsLrc"),
                    createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                )
            )
        }
    }
}.getOrDefault(emptyList())

private fun saveMusicTracks(context: Context, tracks: List<MusicTrack>) {
    val array = JSONArray()
    tracks.forEach { track ->
        array.put(JSONObject().apply {
            put("id", track.id)
            put("title", track.title)
            put("artist", track.artist)
            put("coverUrl", track.coverUrl)
            put("source", track.source.name)
            put("sourceUrl", track.sourceUrl)
            put("playableUrl", track.playableUrl)
            put("neteaseId", track.neteaseId)
            put("lyricsLrc", track.lyricsLrc)
            put("createdAt", track.createdAt)
        })
    }
    context.getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE).edit().putString(MUSIC_TRACKS_KEY, array.toString()).apply()
}
