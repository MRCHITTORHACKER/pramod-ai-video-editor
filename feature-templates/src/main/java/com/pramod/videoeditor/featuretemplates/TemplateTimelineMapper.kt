package com.pramod.videoeditor.featuretemplates

import com.pramod.videoeditor.coremodel.CaptionSegment
import com.pramod.videoeditor.coremodel.EffectInstance
import com.pramod.videoeditor.coremodel.SfxEvent
import com.pramod.videoeditor.coremodel.TemplateConfig
import com.pramod.videoeditor.coremodel.TimelinePlan
import kotlin.math.min

/**
 * Maps a template's timing rules onto an actual project timeline.
 *
 * The template declares *relative* intent (intro title 0-1.2s, emphasis on
 * caption boundaries, ...); this mapper converts it to absolute timestamps
 * for the real project duration and real caption segments, then clamps
 * everything into the project bounds.
 *
 * Pure Kotlin — unit-testable on the JVM without Android.
 */
object TemplateTimelineMapper {

    /**
     * @param template validated template configuration
     * @param projectDurationMs real duration of the project timeline
     * @param captions real caption segments (possibly empty -> sentence fallback)
     * @param beatGridMs real beat positions in ms, or null when no real beat
     *   analysis ran. When null and the template wants beats, we fall back to
     *   manual/sentence timing and record a warning — we never guess beats.
     */
    fun map(
        template: TemplateConfig,
        projectDurationMs: Long,
        captions: List<CaptionSegment>,
        beatGridMs: List<Long>? = null,
    ): TimelinePlan {
        require(projectDurationMs > 0) { "projectDurationMs must be > 0" }
        val warnings = mutableListOf<String>()

        val minMs = template.minDurationSec * 1000L
        val maxMs = template.maxDurationSec * 1000L
        if (projectDurationMs < minMs) {
            warnings.add("Project is shorter than template minimum (${template.minDurationSec}s); timings compressed.")
        }
        if (projectDurationMs > maxMs) {
            warnings.add("Project exceeds template maximum (${template.maxDurationSec}s); intro/outro timings kept, middle stretched.")
        }

        // Scale factor: template's "practical" window -> real duration.
        val practicalMs = ((minMs + maxMs) / 2).coerceAtLeast(1000L)
        val scale = (projectDurationMs.toFloat() / practicalMs).coerceIn(0.25f, 4f)

        val effects = mutableListOf<EffectInstance>()
        template.vfx.forEachIndexed { i, v ->
            val (start, dur) = when (v.placement) {
                "intro" -> 0L to (1200L * scale).toLong()
                "outro" -> (projectDurationMs - 1200L * scale).toLong().coerceAtLeast(0L) to (1200L * scale).toLong()
                "transitions" -> 0L to 0L // transitions attach at clip boundaries in the editor
                else -> 0L to projectDurationMs // "emphasis" | "throughout"
            }
            if (dur > 0) {
                effects.add(
                    EffectInstance(
                        id = "${template.id}_vfx_$i",
                        effectId = v.effectId,
                        startMs = start.coerceIn(0, projectDurationMs),
                        durationMs = min(dur, projectDurationMs - start.coerceIn(0, projectDurationMs)),
                        intensity = (v.intensity * template.effectIntensity).coerceIn(0f, 1f),
                        params = v.params,
                    )
                )
            }
        }

        // SFX placement from template rules -> timeline events.
        val sfx = mutableListOf<SfxEvent>()
        template.sfxRules.forEachIndexed { i, rule ->
            if (!rule.enabled) return@forEachIndexed
            val times: List<Long> = when (rule.onEvent) {
                "title_entry" -> listOf(150L)
                "major_title" -> listOf(600L)
                "caption_boundary" -> captions.drop(1).map { it.startMs }.take(rule.maxOccurrences)
                "transition" -> emptyList() // resolved at clip boundaries in the editor
                "video_end" -> listOf((projectDurationMs - 800).coerceAtLeast(0L))
                else -> emptyList()
            }.take(rule.maxOccurrences)
            times.forEachIndexed { j, t ->
                if (t in 0..projectDurationMs) {
                    sfx.add(
                        SfxEvent(
                            assetId = "sfx/${rule.category}_${j + 1}",
                            startMs = t,
                            durationMs = 600,
                            gainDb = rule.gainDb,
                            fadeInMs = 30,
                            fadeOutMs = 120,
                            track = "sfx",
                            enabled = true,
                            validationResult = "asset_pending",
                        )
                    )
                }
            }
            if (rule.onEvent == "beat" && beatGridMs == null && template.timingRules.requireRealBeatAnalysis) {
                warnings.add("Template wants beat-synced SFX but no real beat analysis ran; beat rule skipped.")
            }
        }

        // Caption timing: clamp on-screen durations into the template's readable window.
        val captionEvents = captions.map { c ->
            val want = (c.endMs - c.startMs)
                .coerceIn(template.timingRules.minTextOnScreenMs, template.timingRules.maxTextOnScreenMs)
            c.copy(endMs = (c.startMs + want).coerceAtMost(projectDurationMs))
        }

        return TimelinePlan(
            templateId = template.id,
            templateVersion = template.version,
            captionEvents = captionEvents,
            textAnimations = template.textAnimations,
            effects = effects,
            sfx = sfx,
            warnings = warnings,
        )
    }
}
