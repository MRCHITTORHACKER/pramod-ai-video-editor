package com.pramod.videoeditor.featureaudio

/**
 * Catalog of sound-effect categories and the timeline events that trigger them.
 *
 * All 14 categories ship with a real, original sound effect synthesized
 * programmatically for this app (see SOUNDS_LICENSE.md — CC0, no third-party
 * samples). Asset files live at:
 *     feature-audio/src/main/assets/sfx/<category>/1.ogg
 * Open them with `context.assets.openFd(asset.fileName)` — .ogg is in the
 * default no-compress list, so AssetFileDescriptor offsets are valid.
 */
object SfxLibrary {

    /** The 14 required categories from the spec. */
    val categories: List<String> = listOf(
        "whoosh", "pop", "impact", "riser", "digital_glitch", "sparkle",
        "notification", "cinematic_hit", "ambient_atmosphere", "victory_accent",
        "soft_transition", "educational_cue", "gaming_accent", "emotional_accent",
    )

    /**
     * Measured duration of each shipped asset in milliseconds (decoded from
     * the actual .ogg files; includes Vorbis encoder priming).
     */
    val durationsMs: Map<String, Int> = mapOf(
        "whoosh" to 900,
        "pop" to 350,
        "impact" to 1000,
        "riser" to 1600,
        "digital_glitch" to 700,
        "sparkle" to 1100,
        "notification" to 620,
        "cinematic_hit" to 1500,
        "ambient_atmosphere" to 3500,
        "victory_accent" to 1200,
        "soft_transition" to 800,
        "educational_cue" to 500,
        "gaming_accent" to 700,
        "emotional_accent" to 1800,
    )

    /** Timeline events the placement engine understands. */
    val knownEvents: List<String> = listOf(
        "title_entry", "major_title", "major_phrase", "key_moment",
        "word_emphasis", "caption_boundary", "transition", "zoom_start",
        "feature_reveal", "scene", "video_end", "beat",
    )

    /**
     * A resolved SFX asset.
     * @param fileName path of the file inside the APK's assets/ tree,
     *   e.g. "sfx/whoosh/1.ogg" — open with `context.assets.openFd(fileName)`.
     */
    data class Asset(val assetId: String, val category: String, val fileName: String)

    /** Canonical asset id for previewing a whole category. */
    fun assetIdFor(category: String): String = "sfx/${category}_1"

    /**
     * Resolves an asset id like "sfx/whoosh_1" to a concrete, shipped asset.
     *
     * Each category ships exactly ONE recorded variant (variant 1). Indices
     * n >= 1 (e.g. sfx/whoosh_2, produced by rules with maxOccurrences > 1)
     * all resolve to that same canonical file — documented, not faked.
     *
     * Returns null for unknown categories or malformed ids; the caller must
     * skip and surface a useful message instead of failing silently.
     */
    fun resolve(assetId: String): Asset? {
        val parts = assetId.removePrefix("sfx/").split("_")
        if (parts.size < 2) return null
        val index = parts.last().toIntOrNull() ?: return null
        if (index < 1) return null
        val category = parts.dropLast(1).joinToString("_")
        if (category !in categories) return null
        return Asset(assetId, category, "sfx/$category/1.ogg")
    }
}
