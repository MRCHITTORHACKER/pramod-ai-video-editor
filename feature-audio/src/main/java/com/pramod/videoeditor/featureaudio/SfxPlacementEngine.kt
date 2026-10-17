package com.pramod.videoeditor.featureaudio

import com.pramod.videoeditor.coremodel.SfxEvent
import com.pramod.videoeditor.coremodel.SfxRule

/**
 * Converts template [SfxRule]s + real timeline event timestamps into
 * concrete [SfxEvent]s. Placement is event-driven, never random.
 * Pure Kotlin — unit-testable on the JVM.
 */
object SfxPlacementEngine {

    /**
     * @param rules template SFX rules
     * @param eventTimes map of event name -> sorted timestamps (ms)
     * @param projectDurationMs clamps every event into the project
     */
    fun place(
        rules: List<SfxRule>,
        eventTimes: Map<String, List<Long>>,
        projectDurationMs: Long,
    ): Pair<List<SfxEvent>, List<String>> {
        val events = mutableListOf<SfxEvent>()
        val messages = mutableListOf<String>()

        for (rule in rules) {
            if (!rule.enabled) continue
            val times = (eventTimes[rule.onEvent] ?: emptyList())
                .filter { it in 0..projectDurationMs }
                .take(rule.maxOccurrences)
            if (times.isEmpty()) {
                messages.add("SFX rule '${rule.onEvent}' -> '${rule.category}': no matching timeline events, skipped.")
                continue
            }
            times.forEachIndexed { i, t ->
                val assetId = "sfx/${rule.category}_${i + 1}"
                val asset = SfxLibrary.resolve(assetId)
                if (asset == null) {
                    messages.add("SFX asset missing: $assetId — event skipped.")
                } else {
                    events.add(
                        SfxEvent(
                            assetId = assetId,
                            startMs = t,
                            // Real shipped asset duration so export mixes the full sound.
                            durationMs = (SfxLibrary.durationsMs[rule.category] ?: 600).toLong(),
                            gainDb = rule.gainDb.coerceAtMost(0f),
                            fadeInMs = 30,
                            fadeOutMs = 120,
                            track = "sfx",
                            enabled = true,
                            validationResult = "ok",
                        )
                    )
                }
            }
        }

        // Guard: never stack loud impacts within 300ms of each other.
        val sorted = events.sortedBy { it.startMs }
        val deduped = sorted.filterIndexed { i, e ->
            if (i == 0) true else {
                val prev = sorted[i - 1]
                !(e.gainDb > -6f && prev.gainDb > -6f && e.startMs - prev.startMs < 300)
            }
        }
        if (deduped.size < sorted.size) {
            messages.add("Removed ${sorted.size - deduped.size} overlapping loud SFX to avoid clipping.")
        }
        return deduped to messages
    }

    /**
     * Automatic mixing levels. Music ducks under speech; SFX stays below speech.
     * Returns target gains in dB for (music, sfx) given whether speech is present.
     */
    fun mixLevels(speechPresent: Boolean, musicDuckDb: Float = -10f): Pair<Float, Float> {
        val music = if (speechPresent) musicDuckDb else 0f
        val sfx = if (speechPresent) -6f else 0f
        return music to sfx
    }
}
