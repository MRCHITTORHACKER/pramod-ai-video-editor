package com.pramod.videoeditor.mediaengine

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import com.pramod.videoeditor.coremodel.Clip
import com.pramod.videoeditor.coremodel.ClipType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real on-device preview player for the editor.
 *
 * Builds an ExoPlayer playlist from the project's media clips (VIDEO/AUDIO with a
 * source URI), applying each clip's [Clip.trimInMs]/[Clip.trimOutMs] as a Media3
 * [MediaItem.ClippingConfiguration]. The player window position is translated back
 * to project-timeline milliseconds so Compose UI (playhead, seek bar, trim/split
 * tools) can work in timeline coordinates.
 *
 * Must be created on the main thread (ExoPlayer requirement). Call [release] when
 * the owner is done with it.
 */
class PreviewPlayer(appContext: Context) {

    /** Maps a playlist window back to its timeline clip. */
    private data class WindowMapping(
        val clip: Clip,
        val windowIndex: Int,
        val timelineStartMs: Long,
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * The underlying ExoPlayer. Attach it to a PlayerView with
     * `playerView.player = previewPlayer.player`.
     */
    val player: ExoPlayer = ExoPlayer.Builder(appContext).build().apply {
        repeatMode = Player.REPEAT_MODE_ALL
    }

    private val _positionMs = MutableStateFlow(0L)
    /** Current playhead in project-timeline milliseconds, updated ~10Hz while playing. */
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _timelineDurationMs = MutableStateFlow(0L)
    /** End of the last playable clip on the timeline. */
    val timelineDurationMs: StateFlow<Long> = _timelineDurationMs.asStateFlow()

    private val _sourceDurations = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Real source durations keyed by clip id, filled in once the player loads each window. */
    val sourceDurations: StateFlow<Map<String, Long>> = _sourceDurations.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Last playback error message, or null. Cleared on [play] and [loadClips]. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private var mappings: List<WindowMapping> = emptyList()

    private val tick = Runnable {
        updatePosition()
        if (_isPlaying.value) scheduleTick()
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) scheduleTick() else mainHandler.removeCallbacks(tick)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            updatePosition()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            applyClipVolume()
            refreshSourceDurations()
            updatePosition()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) refreshSourceDurations()
        }

        override fun onPlayerError(error: PlaybackException) {
            _error.value = error.message ?: "Playback failed (code ${error.errorCode})"
            _isPlaying.value = false
        }
    }

    init {
        player.addListener(listener)
    }

    /**
     * Rebuilds the playlist from [clips]. Clips without playable media
     * (no source URI, or TEXT/EMOJI/STICKER type) are skipped.
     */
    fun loadClips(clips: List<Clip>) = runOnMain {
        val playable = clips
            .filter { (it.type == ClipType.VIDEO || it.type == ClipType.AUDIO) && !it.sourceUri.isNullOrBlank() }
            .sortedBy { it.startMs }
        val items = playable.map { clip ->
            val builder = MediaItem.Builder()
                .setUri(clip.sourceUri!!)
                .setMediaId(clip.id)
            if (clip.trimOutMs > clip.trimInMs) {
                builder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.trimInMs)
                        .setEndPositionMs(clip.trimOutMs)
                        .build(),
                )
            }
            builder.build()
        }
        mappings = playable.mapIndexed { index, clip ->
            WindowMapping(clip, index, clip.startMs)
        }
        _timelineDurationMs.value = playable.maxOfOrNull { it.endMs } ?: 0L
        _sourceDurations.value = emptyMap()
        _error.value = null
        player.setMediaItems(items)
        player.prepare()
        updatePosition()
    }

    fun play() = runOnMain {
        _error.value = null
        player.play()
    }

    fun pause() = runOnMain { player.pause() }

    /** Seeks to a project-timeline position, clamped to the loaded media. */
    fun seekTo(timelineMs: Long) = runOnMain {
        val mapping = mappings.lastOrNull { it.timelineStartMs <= timelineMs }
            ?: mappings.firstOrNull()
            ?: return@runOnMain
        val clipLength = (mapping.clip.endMs - mapping.clip.startMs).coerceAtLeast(0L)
        val within = (timelineMs - mapping.timelineStartMs).coerceIn(0L, clipLength)
        player.seekTo(mapping.windowIndex, within)
        updatePosition()
    }

    fun release() = runOnMain {
        mainHandler.removeCallbacks(tick)
        player.removeListener(listener)
        player.release()
    }

    private fun scheduleTick() {
        mainHandler.removeCallbacks(tick)
        mainHandler.postDelayed(tick, 100L)
    }

    private fun updatePosition() {
        val mapping = mappings.getOrNull(player.currentMediaItemIndex)
        _positionMs.value = if (mapping == null) {
            0L
        } else {
            (mapping.timelineStartMs + player.currentPosition).coerceAtLeast(0L)
        }
    }

    /** Applies per-clip mute/volume so the preview matches the timeline mix. */
    private fun applyClipVolume() {
        val mapping = mappings.getOrNull(player.currentMediaItemIndex) ?: return
        player.volume = if (mapping.clip.muted) 0f else mapping.clip.volume.coerceIn(0f, 1f)
    }

    private fun refreshSourceDurations() {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return
        val window = Timeline.Window()
        val durations = mutableMapOf<String, Long>()
        for (i in 0 until timeline.windowCount) {
            timeline.getWindow(i, window)
            val clipId = mappings.getOrNull(i)?.clip?.id ?: continue
            if (window.durationMs != C.TIME_UNSET && window.durationMs > 0) {
                durations[clipId] = window.durationMs
            }
        }
        if (durations.isNotEmpty()) _sourceDurations.value = durations
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }
}
