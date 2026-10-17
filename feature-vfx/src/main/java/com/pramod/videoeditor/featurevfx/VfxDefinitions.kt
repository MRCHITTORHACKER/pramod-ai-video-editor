package com.pramod.videoeditor.featurevfx

/**
 * The 14 required visual effects. Each definition carries its parameter
 * ranges, device-compatibility rule and fallback, so the editor and the
 * export engine apply the SAME effect.
 */
data class VfxDefinition(
    val id: String,
    val displayName: String,
    /** param name -> (min, max, default) */
    val params: Map<String, Triple<Float, Float, Float>>,
    /** "media3" = supported Media3 effect, "custom" = needs RendererAbstraction */
    val renderPath: String,
    val lowEndFallback: String,
)

object VfxDefinitions {
    val all: List<VfxDefinition> = listOf(
        VfxDefinition("smooth_zoom", "Smooth Zoom",
            mapOf("zoomFrom" to Triple(1.0f, 2.0f, 1.0f), "zoomTo" to Triple(1.0f, 2.0f, 1.2f)),
            "media3", "Reduce zoom range to 1.0-1.1"),
        VfxDefinition("shake", "Shake",
            mapOf("amplitudePx" to Triple(0f, 24f, 8f), "frequencyHz" to Triple(1f, 12f, 6f)),
            "custom", "Disable shake, keep static frame"),
        VfxDefinition("rgb_split", "RGB Split",
            mapOf("offsetPx" to Triple(0f, 20f, 6f)),
            "custom", "Reduce offset to <= 3px"),
        VfxDefinition("digital_glitch", "Digital Glitch",
            mapOf("blocks" to Triple(2f, 24f, 8f), "seedJitter" to Triple(0f, 1f, 0.5f)),
            "custom", "Single-frame RGB split only"),
        VfxDefinition("film_grain", "Film Grain",
            mapOf("amount" to Triple(0f, 1f, 0.25f)),
            "media3", "Halve grain amount"),
        VfxDefinition("vignette", "Vignette",
            mapOf("strength" to Triple(0f, 1f, 0.45f)),
            "media3", "Reduce strength to 0.25"),
        VfxDefinition("light_leak", "Light Leak",
            mapOf("strength" to Triple(0f, 1f, 0.4f), "hue" to Triple(0f, 360f, 35f)),
            "custom", "Skip on low-end devices"),
        VfxDefinition("sparkle", "Sparkle Particles",
            mapOf("count" to Triple(4f, 64f, 20f), "sizePx" to Triple(2f, 16f, 6f)),
            "custom", "Reduce count to 8"),
        VfxDefinition("color_grade", "Color Grade",
            mapOf("warmth" to Triple(0f, 1f, 0.5f), "contrast" to Triple(0.8f, 1.3f, 1.05f),
                  "saturation" to Triple(0f, 2f, 1f)),
            "media3", "Apply contrast only"),
        VfxDefinition("blur_transition", "Blur Transition",
            mapOf("radiusPx" to Triple(0f, 40f, 16f)),
            "media3", "Use fade instead"),
        VfxDefinition("fade", "Fade",
            mapOf("opacity" to Triple(0f, 1f, 1f)),
            "media3", "Same (cheap)"),
        VfxDefinition("flash", "Flash",
            mapOf("peakWhite" to Triple(0f, 1f, 0.8f)),
            "media3", "Reduce peak to 0.4 (photosensitivity)"),
        VfxDefinition("glow_text", "Glow Text",
            mapOf("radiusPx" to Triple(0f, 24f, 10f), "strength" to Triple(0f, 1f, 0.6f)),
            "custom", "Plain text, no glow"),
        VfxDefinition("letterbox", "Letterbox",
            mapOf("barPercent" to Triple(5f, 20f, 12f)),
            "media3", "Same (cheap)"),
        VfxDefinition("dim_background", "Dim Background",
            mapOf("opacity" to Triple(0f, 0.8f, 0.35f)),
            "media3", "Same (cheap)"),
    )

    fun byId(id: String): VfxDefinition? = all.find { it.id == id }

    /** Clamp user params into the definition's valid ranges. */
    fun clampParams(id: String, params: Map<String, Float>): Map<String, Float> {
        val def = byId(id) ?: return emptyMap()
        return params.mapValues { (k, v) ->
            val range = def.params[k] ?: return@mapValues v
            v.coerceIn(range.first, range.second)
        }
    }
}
