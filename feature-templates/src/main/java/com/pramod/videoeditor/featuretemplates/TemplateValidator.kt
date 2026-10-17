package com.pramod.videoeditor.featuretemplates

import com.pramod.videoeditor.coremodel.TemplateConfig

/**
 * Validates a parsed [TemplateConfig] against the template contract.
 * Pure Kotlin — unit-testable on the JVM without Android.
 */
object TemplateValidator {

    enum class Severity { ERROR, WARNING }

    data class Issue(val severity: Severity, val field: String, val message: String)

    private val ID_PATTERN = Regex("^[a-z0-9_]{3,48}$")
    private val VERSION_PATTERN = Regex("^\\d+\\.\\d+\\.\\d+$")
    private val COLOR_PATTERN = Regex("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$")
    private val KNOWN_ANIMATIONS = setOf("scale_in", "fade_in", "slide_up", "pop", "typewriter", "none")
    private val KNOWN_TRANSITIONS = setOf("cut", "fade", "zoom_blur", "glitch", "slide", "flash")
    private val KNOWN_SYNC_SOURCES = setOf("speech", "fixed_beats", "manual_markers", "sentence")

    fun validate(t: TemplateConfig): List<Issue> {
        val issues = mutableListOf<Issue>()
        fun err(field: String, msg: String) = issues.add(Issue(Severity.ERROR, field, msg))
        fun warn(field: String, msg: String) = issues.add(Issue(Severity.WARNING, field, msg))

        if (!ID_PATTERN.matches(t.id)) err("id", "must match $ID_PATTERN")
        if (t.name.isBlank()) err("name", "must not be blank")
        if (t.category.isBlank()) err("category", "must not be blank")
        if (t.aspectRatios.isEmpty()) err("aspectRatios", "at least one aspect ratio required")
        if (t.minDurationSec <= 0) err("minDurationSec", "must be > 0")
        if (t.maxDurationSec < t.minDurationSec) err("maxDurationSec", "must be >= minDurationSec")
        if (!VERSION_PATTERN.matches(t.version)) err("version", "must be semver MAJOR.MINOR.PATCH")
        if (t.effectIntensity !in 0f..1f) err("effectIntensity", "must be within 0..1")

        if (!COLOR_PATTERN.matches(t.typography.textColor)) err("typography.textColor", "must be #RRGGBB[AA]")
        if (!COLOR_PATTERN.matches(t.typography.accentColor)) err("typography.accentColor", "must be #RRGGBB[AA]")
        if (t.typography.baseSizeSp !in 12..120) warn("typography.baseSizeSp", "unusual size, check readability")

        t.textAnimations.forEachIndexed { i, a ->
            if (a.type !in KNOWN_ANIMATIONS) err("textAnimations[$i].type", "unknown animation '${a.type}'")
            if (a.startScale <= 0f || a.endScale <= 0f) err("textAnimations[$i]", "scales must be > 0")
            if (a.fadeInMs !in 0..2000) warn("textAnimations[$i].fadeInMs", "unusual fade duration")
        }
        t.transitions.forEachIndexed { i, tr ->
            if (tr.type !in KNOWN_TRANSITIONS) err("transitions[$i].type", "unknown transition '${tr.type}'")
            if (tr.durationMs !in 0..3000) warn("transitions[$i].durationMs", "unusual transition duration")
        }
        if (t.timingRules.syncSource !in KNOWN_SYNC_SOURCES) {
            err("timingRules.syncSource", "unknown sync source '${t.timingRules.syncSource}'")
        }
        if (t.timingRules.syncSource == "fixed_beats" && t.timingRules.requireRealBeatAnalysis) {
            warn("timingRules", "beat sync requires a real beat analysis; engine must fall back to manual markers otherwise")
        }
        t.sfxRules.forEachIndexed { i, r ->
            if (r.gainDb > 0f) warn("sfxRules[$i].gainDb", "positive gain risks clipping; prefer <= 0 dB")
            if (r.maxOccurrences <= 0) err("sfxRules[$i].maxOccurrences", "must be > 0")
        }
        if (t.emojiRules.autoSuggest && t.emojiRules.category == "none") {
            warn("emojiRules", "autoSuggest=true but category=none — no emoji will ever be suggested")
        }
        if (t.testCases.isEmpty()) warn("testCases", "no acceptance test cases documented")

        return issues
    }

    /** True when there are no ERROR-severity issues. */
    fun isUsable(t: TemplateConfig): Boolean =
        validate(t).none { it.severity == Severity.ERROR }
}
