package com.pramod.videoeditor.featureeditor

import com.pramod.videoeditor.aigemini.GeminiClient
import com.pramod.videoeditor.aigemini.GeminiException
import com.pramod.videoeditor.aigroq.GroqClient
import com.pramod.videoeditor.aigroq.GroqError
import com.pramod.videoeditor.aigroq.GroqException
import com.pramod.videoeditor.coremodel.AiProvider
import com.pramod.videoeditor.coremodel.CaptionSegment
import com.pramod.videoeditor.coremodel.Project
import com.pramod.videoeditor.coremodel.TemplateConfig
import com.pramod.videoeditor.coremodel.TimelinePlan
import com.pramod.videoeditor.featureaudio.SfxPlacementEngine
import com.pramod.videoeditor.featuretemplates.TemplateTimelineMapper
import java.io.File

/**
 * Persistence hook the app must provide. The reference implementation lives
 * next to ProjectRepository: `repository.save(project.copy(captions = ...))`.
 * Kept as an interface here because this module may not depend on
 * :feature-projects (Gradle constraint) — the app wires the real
 * implementation at startup.
 */
interface ProjectCaptionStore {
    /** Replaces the project's caption track with [captions]. */
    suspend fun updateCaptions(projectId: String, captions: List<CaptionSegment>)
}

/**
 * AI auto-edit orchestrator: user choices + analyzed media -> editable TimelinePlan.
 *
 * Real pipeline (no simulation):
 * 1. Validate media (project must have a duration).
 * 2. Resolve audio: [AutoEditInput.audioSource] -> temp/uploadable file.
 *    URIs go through [resolveAudioUri] (the app passes
 *    `AudioUriResolver.copyToTempFile(context, uri)`).
 * 3. Transcribe with Groq Whisper (provider GROQ) -> caption segments.
 * 4. Optionally improve wording with Gemini (conservative: text only).
 * 5. Persist captions to the project via [ProjectCaptionStore].
 * 6. Map template -> TimelinePlan, place SFX.
 *
 * Honesty contract (from the spec):
 * - If transcription fails -> manual caption mode, never fake timestamps.
 * - If no speech is detected -> say so plainly.
 * - If a sound asset is missing -> skip + message.
 * - If an effect is unsupported -> validated fallback or ask user to disable.
 * - If beat analysis failed -> manual/sentence timing. Never claim AI
 *   "analyzed the footage" when it didn't.
 * - Every failure message is user-facing and specific (bad key, no network,
 *   no speech, rate limit). API keys never appear in messages.
 */
class AiAutoEditOrchestrator(
    private val groq: GroqClient,
    private val gemini: GeminiClient,
    private val captionStore: ProjectCaptionStore,
    /** Resolves a content/file URI string to an uploadable temp audio file. */
    private val resolveAudioUri: (String) -> File,
) {
    /** Where the audio for transcription comes from. */
    sealed interface AudioSource {
        /** Already-resolved file (e.g. extracted by the media engine). */
        data class FromFile(val file: File) : AudioSource
        /** Project clip URI; resolved via [resolveAudioUri] (ContentResolver). */
        data class FromUri(val uriString: String) : AudioSource
    }

    data class AutoEditInput(
        val project: Project,
        val template: TemplateConfig,
        val provider: AiProvider,
        /** Audio to transcribe. Null = skip AI transcription. */
        val audioSource: AudioSource? = null,
        val language: String = "hi",
        val beatGridMs: List<Long>? = null,
        /** When true and provider == GEMINI, improve caption wording after transcription. */
        val improveCaptionsWithGemini: Boolean = false,
    )

    sealed interface AutoEditResult {
        data class Ready(val plan: TimelinePlan, val messages: List<String>) : AutoEditResult
        data class NeedsManualCaptions(val reason: String) : AutoEditResult
        /** Unrecoverable setup error (not a caption problem) — message is user-facing. */
        data class Failed(val message: String) : AutoEditResult
    }

    suspend fun run(input: AutoEditInput, onStep: (String) -> Unit): AutoEditResult {
        val messages = mutableListOf<String>()
        val durationMs = input.project.clips.maxOfOrNull { it.endMs } ?: 0L
        if (durationMs <= 0) {
            return AutoEditResult.NeedsManualCaptions(
                "Project has no media duration yet. Add a clip first, then run Auto Edit.")
        }

        onStep("Validating media…")

        // --- Transcription ------------------------------------------------
        var captions: List<CaptionSegment> = input.project.captions
        var transcribedFresh = false
        if (captions.isEmpty()) {
            when {
                input.provider == AiProvider.GROQ && input.audioSource != null -> {
                    onStep("Transcribing audio…")
                    val audioFile = try {
                        resolveAudio(input.audioSource)
                    } catch (e: GroqException) {
                        return AutoEditResult.NeedsManualCaptions(
                            "Could not read the project audio: ${e.message}")
                    } catch (e: Exception) {
                        return AutoEditResult.NeedsManualCaptions(
                            "Could not read the project audio (${e.javaClass.simpleName}).")
                    }
                    captions = try {
                        groq.transcribe(audioFile, input.language)
                    } catch (e: GroqException) {
                        if (e.kind == GroqError.NO_SPEECH) {
                            return AutoEditResult.NeedsManualCaptions(
                                "No speech detected in the audio. Add captions manually if the clip has speech.")
                        }
                        return AutoEditResult.NeedsManualCaptions(
                            "Transcription failed: ${e.message} Add captions manually.")
                    } catch (e: Exception) {
                        return AutoEditResult.NeedsManualCaptions(
                            "Transcription failed unexpectedly (${e.javaClass.simpleName}). " +
                                "Add captions manually.")
                    } finally {
                        // Temp copies made from URIs are deleted; caller-owned files are kept.
                        if (input.audioSource is AudioSource.FromUri) audioFile.delete()
                    }
                    transcribedFresh = true
                    messages.add(
                        "Transcribed ${captions.size} caption segments with Groq " +
                            "${GroqClient.TRANSCRIBE_MODEL}.")
                }
                input.provider == AiProvider.GROQ -> {
                    messages.add(
                        "Groq selected but no audio source was provided; manual captions enabled.")
                }
                else -> {
                    messages.add("No AI transcription (no provider selected); manual captions enabled.")
                }
            }
        }

        // --- Optional Gemini caption improvement ---------------------------
        if (input.improveCaptionsWithGemini && captions.isNotEmpty()
            && input.provider == AiProvider.GEMINI
        ) {
            onStep("Improving captions…")
            captions = try {
                val improved = gemini.improveCaptions(captions, language = "Hindi")
                val changed = improved.zip(captions).count { (a, b) -> a.text != b.text }
                messages.add("Gemini improved wording on $changed of ${captions.size} captions " +
                    "(timestamps untouched).")
                improved
            } catch (e: GeminiException) {
                messages.add("Caption assist failed: ${e.message} Keeping original captions.")
                captions
            } catch (e: Exception) {
                messages.add(
                    "Caption assist failed unexpectedly (${e.javaClass.simpleName}). " +
                        "Keeping original captions.")
                captions
            }
        }

        if (captions.isEmpty()) {
            return AutoEditResult.NeedsManualCaptions(
                "No captions to work with. Transcribe with Groq or add captions manually.")
        }

        // --- Persist captions to the project timeline ----------------------
        if (transcribedFresh) {
            onStep("Saving captions…")
            try {
                captionStore.updateCaptions(input.project.id, captions)
                messages.add("Captions saved to the project timeline.")
            } catch (e: Exception) {
                // Honest: the plan below still carries them, but they were not persisted.
                messages.add(
                    "Captions were generated but could not be saved to the project " +
                        "(${e.javaClass.simpleName}); they are included in the plan preview below.")
            }
        }

        // --- Template mapping + SFX (unchanged, real logic) -----------------
        onStep("Applying template '${input.template.name}'…")
        val plan = TemplateTimelineMapper.map(
            template = input.template,
            projectDurationMs = durationMs,
            captions = captions,
            beatGridMs = input.beatGridMs,
        )
        messages.addAll(plan.warnings)

        onStep("Placing sound effects…")
        val eventTimes = buildMap<String, List<Long>> {
            put("title_entry", listOf(150L))
            put("caption_boundary", captions.drop(1).map { it.startMs })
            put("video_end", listOf((durationMs - 800).coerceAtLeast(0L)))
            if (input.beatGridMs != null) put("beat", input.beatGridMs)
        }
        val (sfxEvents, sfxMessages) = SfxPlacementEngine.place(
            input.template.sfxRules, eventTimes, durationMs
        )
        messages.addAll(sfxMessages)

        val finalPlan = plan.copy(sfx = sfxEvents)
        return AutoEditResult.Ready(finalPlan, messages)
    }

    private fun resolveAudio(source: AudioSource): File = when (source) {
        is AudioSource.FromFile -> {
            if (!source.file.exists() || !source.file.canRead()) {
                throw GroqException(GroqError.BAD_AUDIO, "Audio file is missing or unreadable.")
            }
            source.file
        }
        is AudioSource.FromUri -> resolveAudioUri(source.uriString)
    }
}
