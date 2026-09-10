package me.rerere.rikkahub.ui.pages.life

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide music playback session.
 * The player is intentionally owned outside a composable so leaving MusicSpacePanel
 * does not stop playback. The process still owns the lifetime; a foreground media
 * service can be added later for stronger process-death/background guarantees.
 */
object MusicPlaybackSession {
    data class LyricLine(val timeMs: Long, val text: String)

    data class State(
        val trackId: String = "",
        val title: String = "",
        val artist: String = "",
        val coverUrl: String = "",
        val playableUrl: String = "",
        val togetherMode: Boolean = false,
        val isPlaying: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val lyrics: List<LyricLine> = emptyList(),
    ) {
        val active: Boolean get() = title.isNotBlank() && playableUrl.isNotBlank()

        fun lyricIndex(): Int {
            if (lyrics.isEmpty()) return -1
            val index = lyrics.indexOfLast { it.timeMs <= positionMs }
            return index.coerceAtLeast(0)
        }
    }

    private var player: ExoPlayer? = null
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private fun ensurePlayer(context: Context): ExoPlayer {
        player?.let { return it }
        return ExoPlayer.Builder(context.applicationContext).build().also { created ->
            created.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _state.value = _state.value.copy(isPlaying = isPlaying)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    syncPosition()
                }
            })
            player = created
        }
    }

    fun play(
        context: Context,
        trackId: String,
        title: String,
        artist: String,
        coverUrl: String,
        playableUrl: String,
        togetherMode: Boolean,
        lyrics: List<LyricLine> = emptyList(),
    ) {
        if (playableUrl.isBlank()) return
        val p = ensurePlayer(context)
        val sameTrack = _state.value.trackId == trackId && _state.value.playableUrl == playableUrl
        _state.value = _state.value.copy(
            trackId = trackId,
            title = title,
            artist = artist,
            coverUrl = coverUrl,
            playableUrl = playableUrl,
            togetherMode = togetherMode,
            lyrics = lyrics,
        )
        if (!sameTrack) {
            p.setMediaItem(MediaItem.fromUri(playableUrl))
            p.prepare()
        }
        p.play()
        syncPosition()
    }

    fun setTogetherMode(enabled: Boolean) {
        _state.value = _state.value.copy(togetherMode = enabled)
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        syncPosition()
    }

    fun seekTo(positionMs: Long) {
        val p = player ?: return
        p.seekTo(positionMs.coerceAtLeast(0L))
        syncPosition()
    }

    fun stop() {
        player?.stop()
        _state.value = State()
    }

    fun syncPosition() {
        val p = player ?: return
        _state.value = _state.value.copy(
            isPlaying = p.isPlaying,
            positionMs = p.currentPosition.coerceAtLeast(0L),
            durationMs = p.duration.takeIf { it > 0L } ?: 0L,
        )
    }

    /** Parses common [mm:ss.xx] / [mm:ss] LRC text. */
    fun parseLrc(raw: String): List<LyricLine> = raw.lineSequence()
        .flatMap { line ->
            val matches = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]" ).findAll(line).toList()
            val text = line.replace(Regex("\\[[^]]+]"), "").trim()
            matches.asSequence().mapNotNull { match ->
                if (text.isBlank()) return@mapNotNull null
                val minute = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val second = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                val fractionRaw = match.groupValues[3]
                val fraction = when (fractionRaw.length) {
                    1 -> fractionRaw.toLongOrNull()?.times(100L) ?: 0L
                    2 -> fractionRaw.toLongOrNull()?.times(10L) ?: 0L
                    3 -> fractionRaw.toLongOrNull() ?: 0L
                    else -> 0L
                }
                LyricLine((minute * 60L + second) * 1000L + fraction, text)
            }
        }
        .sortedBy { it.timeMs }
        .toList()
}
