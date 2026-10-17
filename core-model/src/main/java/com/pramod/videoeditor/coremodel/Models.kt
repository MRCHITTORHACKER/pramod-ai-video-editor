package com.pramod.videoeditor.coremodel

import kotlinx.serialization.Serializable

/** Supported output aspect ratios. */
@Serializable
enum class AspectRatio(val label: String, val w: Int, val h: Int) {
    R_9_16("9:16", 9, 16),
    R_16_9("16:9", 16, 9),
    R_1_1("1:1", 1, 1);
}

/** Kind of timeline clip. */
@Serializable
enum class ClipType { VIDEO, AUDIO, TEXT, EMOJI, STICKER }

/** A single item on the project timeline. All times in milliseconds. */
@Serializable
data class Clip(
    val id: String,
    val type: ClipType,
    /** Content URI / asset path of the source media. Null for generated text clips. */
    val sourceUri: String? = null,
    val startMs: Long,
    val endMs: Long,
    /** Trim window inside the source media. */
    val trimInMs: Long = 0,
    val trimOutMs: Long = 0,
    val volume: Float = 1.0f,
    val muted: Boolean = false,
)

/** One word with its timestamp — the basis of kinetic captions. */
@Serializable
data class WordTimestamp(
    val word: String,
    val startMs: Long,
    val endMs: Long,
)

/** A caption segment shown on screen for [startMs, endMs]. */
@Serializable
data class CaptionSegment(
    val id: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val words: List<WordTimestamp> = emptyList(),
    /** Optional speaker label. Only set when speaker info is actually available. */
    val speaker: String? = null,
    /**
     * Kinetic typography preset id applied to this caption (see KineticPresets ids:
     * "scale_in" | "fade_in" | "slide_up" | "pop" | "typewriter" | "none").
     * Null = app/template default. Added for the caption/typography editor.
     */
    val presetId: String? = null,
)

/** A placed visual effect on the timeline. */
@Serializable
data class EffectInstance(
    val id: String,
    /** References a VFX definition id, e.g. "vignette", "rgb_split". */
    val effectId: String,
    val startMs: Long,
    val durationMs: Long,
    /** 0.0 (off) .. 1.0 (full). */
    val intensity: Float,
    /** Effect-specific numeric parameters, validated against the definition's ranges. */
    val params: Map<String, Float> = emptyMap(),
)

/** An emoji/sticker placed on the preview at a timeline position. All times in milliseconds. */
@Serializable
data class EmojiOverlay(
    val id: String,
    /** The emoji character(s) to render, e.g. "🔥". */
    val emoji: String,
    val startMs: Long,
    val endMs: Long,
    /** Position as a fraction of the preview width/height (0.0..1.0). */
    val xFraction: Float = 0.5f,
    val yFraction: Float = 0.5f,
    /** Size multiplier. */
    val scale: Float = 1f,
)

/** A placed sound effect on the timeline. */
@Serializable
data class SfxEvent(
    val assetId: String,
    val startMs: Long,
    val durationMs: Long,
    val gainDb: Float = 0f,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    val trimStartMs: Long = 0,
    val trimEndMs: Long = 0,
    /** "sfx" | "music" | "voice" — which mixer track it plays on. */
    val track: String = "sfx",
    val enabled: Boolean = true,
    /** Result of validating the referenced asset at placement time. */
    val validationResult: String = "ok",
)

/** Export configuration chosen by the user. */
@Serializable
data class ExportSettings(
    val aspectRatio: AspectRatio = AspectRatio.R_9_16,
    val width: Int = 1080,
    val height: Int = 1920,
    val fps: Int = 30,
    val videoBitrate: Int = 8_000_000,
    val audioBitrate: Int = 128_000,
)

/** The editable project. */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val aspectRatio: AspectRatio = AspectRatio.R_9_16,
    val clips: List<Clip> = emptyList(),
    val captions: List<CaptionSegment> = emptyList(),
    val effects: List<EffectInstance> = emptyList(),
    val sfx: List<SfxEvent> = emptyList(),
    /** Emoji/sticker overlays placed on the preview timeline. */
    val emojiOverlays: List<EmojiOverlay> = emptyList(),
    val appliedTemplateId: String? = null,
    val exportSettings: ExportSettings = ExportSettings(),
    /** Content URI of the user-picked background music track. Null = no music. */
    val backgroundMusicUri: String? = null,
)

// ---------------------------------------------------------------------------
// Template configuration — every template is data, parsed by TemplateParser.
// The 23 required fields from the spec are all present here.
// ---------------------------------------------------------------------------

@Serializable
data class CaptionLayout(
    /** "bottom_center" | "center" | "top_center" */
    val position: String = "bottom_center",
    val maxWordsPerCaption: Int = 6,
    val safeMarginPercent: Float = 8f,
    val backgroundStyle: String = "none", // none | pill | box | highlight
    val backgroundOpacity: Float = 0f,
)

@Serializable
data class TypographySettings(
    val fontFamily: String = "sans_serif_bold",
    /** true = must support Devanagari matras & conjuncts. */
    val devanagariSupport: Boolean = true,
    val baseSizeSp: Int = 42,
    val textColor: String = "#FFFFFF",
    val accentColor: String = "#7C4DFF",
    val strokeColor: String? = "#000000",
    val strokeWidthDp: Float = 2f,
)

@Serializable
data class TextAnimation(
    /** "scale_in" | "fade_in" | "slide_up" | "pop" | "typewriter" | "none" */
    val type: String = "scale_in",
    val startScale: Float = 0.88f,
    val endScale: Float = 1.0f,
    val fadeInMs: Int = 200,
    /** "ease_out" | "ease_in_out" | "overshoot" | "linear" */
    val easing: String = "ease_out",
    val overshoot: Boolean = false,
)

@Serializable
data class TransitionDef(
    /** "cut" | "fade" | "zoom_blur" | "glitch" | "slide" | "flash" */
    val type: String = "cut",
    val durationMs: Int = 300,
)

@Serializable
data class VfxDef(
    val effectId: String,
    /** Where the effect applies: "intro" | "emphasis" | "throughout" | "transitions" */
    val placement: String = "emphasis",
    val intensity: Float = 0.5f,
    val params: Map<String, Float> = emptyMap(),
)

@Serializable
data class EmojiRule(
    /** Emoji category that may be suggested, e.g. "motivation". "none" disables. */
    val category: String = "none",
    /** "pop" | "bounce" | "float" | "pulse" | "none" */
    val animation: String = "pop",
    val maxPerVideo: Int = 3,
    /** Never auto-add an emoji to every caption — enforced by the engine. */
    val autoSuggest: Boolean = false,
)

@Serializable
data class SfxRule(
    /** Timeline event that triggers the sound, e.g. "title_entry". */
    val onEvent: String,
    /** SFX category, e.g. "whoosh". */
    val category: String,
    val gainDb: Float = -6f,
    val enabled: Boolean = true,
    val maxOccurrences: Int = 3,
)

@Serializable
data class MusicBehavior(
    /** "none" | "ambient" | "energetic" | "emotional" | "user_pick" */
    val mood: String = "none",
    /** true = duck music while speech is present. */
    val duckUnderSpeech: Boolean = true,
    val duckLevelDb: Float = -10f,
    val fadeInMs: Long = 800,
    val fadeOutMs: Long = 1200,
)

@Serializable
data class TimingRule(
    /** "speech" | "fixed_beats" | "manual_markers" | "sentence" */
    val syncSource: String = "speech",
    /** If true, beat sync is only used when a real beat analysis ran. Never guess. */
    val requireRealBeatAnalysis: Boolean = true,
    val minTextOnScreenMs: Long = 1200,
    val maxTextOnScreenMs: Long = 4000,
)

/**
 * Full template configuration — the 23 required fields.
 * 1:id 2:name 3:category 4:description 5:thumbnailRef 6:aspectRatios
 * 7:minDurationSec 8:maxDurationSec 9:captionLayout 10:typography
 * 11:textAnimations 12:transitions 13:vfx 14:emojiRules 15:sfxRules
 * 16:musicBehavior 17:timingRules 18:effectIntensity 19:previewNotes
 * 20:exportNotes 21:deviceRequirements 22:assetLicense 23:version
 */
@Serializable
data class TemplateConfig(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val thumbnailRef: String,
    val aspectRatios: List<AspectRatio>,
    val minDurationSec: Int,
    val maxDurationSec: Int,
    val captionLayout: CaptionLayout = CaptionLayout(),
    val typography: TypographySettings = TypographySettings(),
    val textAnimations: List<TextAnimation> = emptyList(),
    val transitions: List<TransitionDef> = emptyList(),
    val vfx: List<VfxDef> = emptyList(),
    val emojiRules: EmojiRule = EmojiRule(),
    val sfxRules: List<SfxRule> = emptyList(),
    val musicBehavior: MusicBehavior = MusicBehavior(),
    val timingRules: TimingRule = TimingRule(),
    /** 0.0..1.0 global intensity scaler for all effects in this template. */
    val effectIntensity: Float = 0.5f,
    val previewNotes: String = "",
    val exportNotes: String = "",
    val deviceRequirements: String = "minSdk 26; low-effects mode auto-enabled on low-RAM devices",
    val assetLicense: String = "Original configuration; no third-party assets bundled.",
    val version: String = "1.0.0",
    /** Human-readable acceptance checks for this template. */
    val testCases: List<String> = emptyList(),
)

/** Result of applying a template to a project — editable, never destructive. */
@Serializable
data class TimelinePlan(
    val templateId: String,
    val templateVersion: String,
    val captionEvents: List<CaptionSegment>,
    val textAnimations: List<TextAnimation>,
    val effects: List<EffectInstance>,
    val sfx: List<SfxEvent>,
    val warnings: List<String> = emptyList(),
)

/** AI provider selection. */
@Serializable
enum class AiProvider { NONE, GROQ, GEMINI }

/** API key validation status shown in settings. */
@Serializable
enum class AiKeyStatus { UNKNOWN, VALID, INVALID, RATE_LIMITED, NETWORK_ERROR, QUOTA_EXHAUSTED }
