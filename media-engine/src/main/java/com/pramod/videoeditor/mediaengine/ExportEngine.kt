package com.pramod.videoeditor.mediaengine

import android.content.Context
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.pramod.videoeditor.coremodel.Clip
import com.pramod.videoeditor.coremodel.ClipType
import com.pramod.videoeditor.coremodel.EffectInstance
import com.pramod.videoeditor.coremodel.ExportSettings
import com.pramod.videoeditor.coremodel.Project
import com.pramod.videoeditor.coremodel.SfxEvent
import com.pramod.videoeditor.featureaudio.SfxLibrary
import com.pramod.videoeditor.featurevfx.VfxDefinitions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.log10

/**
 * Real export path built on Media3 Transformer.
 *
 * Pipeline: project clips -> EditedMediaItems (video + audio) ->
 * EditedMediaItemSequences -> Composition -> Transformer -> MP4 file.
 *
 * - Video effects: every [VfxDefinitions] id maps to a REAL Media3 [Effect]
 *   via [VfxEffectFactory] — Media3-native effects
 *   ([androidx.media3.effect] matrix/RGB/convolution) where a Media3 API
 *   exists, real custom GLSL [GlEffect]s ([VfxGlEffect]) otherwise.
 *   EffectInstance.startMs/durationMs are honored per clip (clip-relative
 *   windows); intensity scales each effect; low-end fallbacks from the
 *   definitions are applied.
 * - Stills: photo clips (ClipType.VIDEO with an image URI, as produced by
 *   the media picker) are rendered as video frames for their timeline
 *   duration via Transformer's image input
 *   ([MediaItem.Builder.setImageDurationMs] + [EditedMediaItem.Builder.setFrameRate]).
 * - Audio: the composition mixes overlapping sequences — background music
 *   ([Project.backgroundMusicUri]) gets its own sequence; each SFX event and
 *   each ClipType.AUDIO clip gets its own sequence positioned in time by
 *   [TimelineAudioProcessor] (silence-prefixed delay + gain + fades).
 *   Clip audio is kept unless the clip is muted (volume scales via gain).
 *
 * Progress is reported through BOTH channels (additive contract with the
 * export-progress chunk): the [progressListener] property
 * `(fraction 0..1, status text)` and the legacy [onProgress] lambda.
 */
class Media3ExportEngine(private val context: Context) {

    sealed interface ExportResult {
        data class Success(val file: File, val warnings: List<String> = emptyList()) : ExportResult
        data class Failure(val message: String) : ExportResult
        data object Cancelled : ExportResult
    }

    /**
     * Additive progress channel: (fraction 0..1, status text like
     * "Exporting… 42%"). Set by the export-progress chunk; the legacy
     * [onProgress] parameter of [export] keeps working alongside it.
     */
    var progressListener: ((fraction: Float, status: String) -> Unit)? = null

    suspend fun export(
        project: Project,
        settings: ExportSettings,
        output: File,
        onProgress: (Float) -> Unit,
    ): ExportResult = withContext(Dispatchers.Main) {
        try {
            val lowEnd = DevicePerformance.isLowEnd(context)
            val warnings = mutableListOf<String>()

            val videoClips = project.clips
                .filter { it.type == ClipType.VIDEO && !it.sourceUri.isNullOrBlank() }
                .sortedBy { it.startMs }
            if (videoClips.isEmpty()) {
                return@withContext ExportResult.Failure("No video clips in project")
            }

            val sequences = mutableListOf(
                EditedMediaItemSequence.Builder(
                    *videoClips.map { buildVideoItem(it, project, settings, lowEnd) }.toTypedArray()
                ).build()
            )

            // Background music: its own sequence, overlapping the video in
            // time, so Transformer mixes it under the clip audio.
            project.backgroundMusicUri?.takeIf { it.isNotBlank() }?.let { musicUri ->
                if (uriReadable(musicUri)) {
                    val musicItem = EditedMediaItem.Builder(MediaItem.fromUri(musicUri))
                        .setRemoveVideo(true)
                        .build()
                    sequences += EditedMediaItemSequence.Builder(musicItem).build()
                } else {
                    warnings += "Background music not found — exported without music."
                }
            }

            // User audio clips: each gets its own sequence, positioned at its
            // timeline offset by TimelineAudioProcessor.
            project.clips
                .filter { it.type == ClipType.AUDIO && !it.sourceUri.isNullOrBlank() }
                .sortedBy { it.startMs }
                .mapNotNull { buildAudioClipItem(it) }
                .forEach { sequences += EditedMediaItemSequence.Builder(it).build() }

            // SFX events: one sequence per event. (Items inside a single
            // sequence concatenate; separate overlapping sequences mix.)
            project.sfx
                .filter { it.enabled && it.validationResult == "ok" }
                .sortedBy { it.startMs }
                .mapNotNull { buildSfxItem(it, warnings) }
                .forEach { sequences += EditedMediaItemSequence.Builder(it).build() }

            val composition = Composition.Builder(sequences).build()

            suspendCancellableCoroutine { cont ->
                var pollJob: Job? = null
                val listener = object : Transformer.Listener {
                    override fun onCompleted(
                        composition: Composition,
                        exportResult: androidx.media3.transformer.ExportResult,
                    ) {
                        pollJob?.cancel()
                        if (cont.isActive) {
                            progressListener?.invoke(1f, "Export complete")
                            onProgress(1f)
                            cont.resume(ExportResult.Success(output, warnings.toList()))
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: androidx.media3.transformer.ExportResult,
                        exportException: ExportException,
                    ) {
                        pollJob?.cancel()
                        if (cont.isActive) {
                            progressListener?.invoke(0f, "Export failed")
                            cont.resume(
                                ExportResult.Failure(exportException.message ?: "Export failed")
                            )
                        }
                    }
                }
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType("video/avc")
                    .addListener(listener)
                    .build()
                output.parentFile?.mkdirs()
                transformer.start(composition, output.absolutePath)

                // ProgressHolder polling — getProgress must run on the
                // application thread; this whole function is on Dispatchers.Main.
                pollJob = CoroutineScope(Dispatchers.Main).launch {
                    val holder = ProgressHolder()
                    while (isActive) {
                        val state = try {
                            transformer.getProgress(holder)
                        } catch (_: Exception) {
                            Transformer.PROGRESS_STATE_UNAVAILABLE
                        }
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            val fraction = (holder.progress / 100f).coerceIn(0f, 1f)
                            progressListener?.invoke(fraction, "Exporting… ${holder.progress}%")
                            onProgress(fraction)
                        }
                        delay(300)
                    }
                }
                cont.invokeOnCancellation {
                    pollJob?.cancel()
                    transformer.cancel()
                    progressListener?.invoke(0f, "Export cancelled")
                }
            }
        } catch (e: Exception) {
            ExportResult.Failure(e.message ?: "Export failed")
        }
    }

    // ------------------------------------------------------------------
    // Media items
    // ------------------------------------------------------------------

    private fun buildVideoItem(
        clip: Clip,
        project: Project,
        settings: ExportSettings,
        lowEnd: Boolean,
    ): EditedMediaItem {
        val uri = clip.sourceUri!!
        val timelineMs = (clip.endMs - clip.startMs).coerceAtLeast(1L)
        val isPhoto = isImageUri(uri)

        val mediaItemBuilder = MediaItem.Builder().setUri(uri)
        if (isPhoto) {
            // Still image -> video frames for the clip's timeline duration.
            mediaItemBuilder.setImageDurationMs(timelineMs)
        } else if (clip.trimOutMs > clip.trimInMs) {
            mediaItemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.trimInMs.coerceAtLeast(0L))
                    .setEndPositionMs(clip.trimOutMs)
                    .build()
            )
        }

        val videoEffects = mutableListOf<Effect>()
        // Aspect-ratio presentation: letterbox/pillarbox into the target frame.
        videoEffects.add(
            Presentation.createForWidthAndHeight(
                settings.width, settings.height,
                Presentation.LAYOUT_SCALE_TO_FIT,
            )
        )
        // Real effects for every placed instance (timing window + intensity +
        // low-end fallback handled inside the factory).
        for (e in project.effects) {
            if (e.intensity <= 0f) continue
            if (VfxDefinitions.byId(e.effectId) == null) continue
            val window = effectWindowUs(clip, e) ?: continue
            val params = VfxDefinitions.clampParams(e.effectId, e.params)
            videoEffects += VfxEffectFactory.create(
                effectId = e.effectId,
                params = params,
                intensity = e.intensity.coerceIn(0f, 1f),
                startUs = window.first,
                endUs = window.second,
                lowEnd = lowEnd,
            )
        }

        val audioProcessors = mutableListOf<AudioProcessor>()
        var removeAudio = isPhoto
        if (!removeAudio) {
            if (clip.muted || clip.volume <= 0.001f) {
                removeAudio = true
            } else if (clip.volume < 0.999f) {
                audioProcessors += TimelineAudioProcessor(
                    gainDb = (20f * log10(clip.volume)).toFloat()
                )
            }
        }

        val itemBuilder = EditedMediaItem.Builder(mediaItemBuilder.build())
            .setEffects(Effects(audioProcessors, videoEffects))
        if (isPhoto) {
            itemBuilder.setFrameRate(settings.fps.coerceAtLeast(1))
            itemBuilder.setRemoveAudio(true)
        } else if (removeAudio) {
            itemBuilder.setRemoveAudio(true)
        }
        return itemBuilder.build()
    }

    /** A user audio clip: audio-only item, delayed to its timeline offset. */
    private fun buildAudioClipItem(clip: Clip): EditedMediaItem? {
        val uri = clip.sourceUri ?: return null
        if (clip.muted || clip.volume <= 0.001f) return null // contributes nothing
        val mediaItemBuilder = MediaItem.Builder().setUri(uri)
        if (clip.trimOutMs > clip.trimInMs) {
            mediaItemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.trimInMs.coerceAtLeast(0L))
                    .setEndPositionMs(clip.trimOutMs)
                    .build()
            )
        }
        val processor = TimelineAudioProcessor(
            delayUs = clip.startMs.coerceAtLeast(0L) * 1000L,
            gainDb = if (clip.volume < 0.999f) (20f * log10(clip.volume)).toFloat() else 0f,
        )
        return EditedMediaItem.Builder(mediaItemBuilder.build())
            .setRemoveVideo(true)
            .setEffects(Effects(listOf(processor), emptyList()))
            .build()
    }

    /** An SFX event: audio-only item from the shipped asset, delayed to its timeline offset. */
    private fun buildSfxItem(sfx: SfxEvent, warnings: MutableList<String>): EditedMediaItem? {
        val asset = SfxLibrary.resolve(sfx.assetId)
        if (asset == null) {
            warnings += "SFX asset missing: ${sfx.assetId} — event skipped."
            return null
        }
        val mediaItemBuilder = MediaItem.Builder().setUri("asset:///${asset.fileName}")
        if (sfx.trimOutMs > sfx.trimStartMs) {
            mediaItemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(sfx.trimStartMs.coerceAtLeast(0L))
                    .setEndPositionMs(sfx.trimOutMs)
                    .build()
            )
        }
        val processor = TimelineAudioProcessor(
            delayUs = sfx.startMs.coerceAtLeast(0L) * 1000L,
            gainDb = sfx.gainDb,
            fadeInUs = sfx.fadeInMs.coerceAtLeast(0L) * 1000L,
            fadeOutUs = sfx.fadeOutMs.coerceAtLeast(0L) * 1000L,
            audibleDurationUs = sfx.durationMs.coerceAtLeast(0L) * 1000L,
        )
        return EditedMediaItem.Builder(mediaItemBuilder.build())
            .setRemoveVideo(true)
            .setEffects(Effects(listOf(processor), emptyList()))
            .build()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Clip-relative [startUs, endUs] overlap of an effect with a clip, or null
     * when the effect does not touch this clip. EffectInstance timing is
     * project-timeline-relative (see TemplateTimelineMapper); Media3 effects
     * apply per EditedMediaItem, so the overlap is converted to
     * item-relative microseconds. durationMs <= 0 means "whole clip".
     */
    private fun effectWindowUs(clip: Clip, e: EffectInstance): Pair<Long, Long>? {
        val effStartMs: Long
        val effEndMs: Long
        if (e.durationMs <= 0L) {
            effStartMs = clip.startMs
            effEndMs = clip.endMs
        } else {
            effStartMs = maxOf(e.startMs, clip.startMs)
            effEndMs = minOf(e.startMs + e.durationMs, clip.endMs)
        }
        if (effEndMs <= effStartMs) return null
        return Pair(
            (effStartMs - clip.startMs) * 1000L,
            (effEndMs - clip.startMs) * 1000L,
        )
    }

    /** Photo detection matching the media picker's rule (MIME type, then extension). */
    private fun isImageUri(uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            val mime = context.contentResolver.getType(uri)
            if (mime != null) return mime.startsWith("image/")
            val path = uriString.substringBefore('?').lowercase()
            IMAGE_EXTENSIONS.any { path.endsWith(it) }
        } catch (_: Exception) {
            false
        }
    }

    private fun uriReadable(uriString: String): Boolean {
        return try {
            context.contentResolver.openInputStream(Uri.parse(uriString))?.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private val IMAGE_EXTENSIONS = listOf(
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic", ".heif"
        )
    }
}

/**
 * Abstraction for effects Media3 cannot express directly (custom shaders,
 * particle systems). Implementations must render into the actual output —
 * drawing a static sticker over the preview and calling it "VFX" is not
 * acceptable and is rejected in code review (see REPORT.md honesty section).
 *
 * The concrete implementations are the [VfxGlEffect] subclasses in
 * GlShaderEffects.kt — real [androidx.media3.effect.GlEffect]s backed by
 * real GLSL, wired into the export pipeline by [VfxEffectFactory].
 */
interface CustomRenderer {
    val effectId: String
    fun isSupported(): Boolean
    fun fallbackDescription(): String
}
