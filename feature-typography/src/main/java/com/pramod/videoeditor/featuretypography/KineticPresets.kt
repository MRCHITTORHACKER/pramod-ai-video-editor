package com.pramod.videoeditor.featuretypography

import com.pramod.videoeditor.coremodel.TextAnimation

/**
 * Kinetic typography presets for the Typography editor.
 * Each preset maps 1:1 to a TextAnimation used by preview AND export.
 */
object KineticPresets {

    data class Preset(val id: String, val displayName: String, val animation: TextAnimation)

    val all: List<Preset> = listOf(
        Preset("scale_in", "Scale In",
            TextAnimation("scale_in", 0.88f, 1.0f, 200, "ease_out", overshoot = true)),
        Preset("fade_in", "Fade In",
            TextAnimation("fade_in", 1.0f, 1.0f, 400, "ease_in_out")),
        Preset("slide_up", "Slide Up",
            TextAnimation("slide_up", 1.0f, 1.0f, 300, "ease_out")),
        Preset("pop", "Pop",
            TextAnimation("pop", 0.9f, 1.0f, 120, "ease_out", overshoot = true)),
        Preset("typewriter", "Typewriter",
            TextAnimation("typewriter", 1.0f, 1.0f, 0, "linear")),
        Preset("none", "None",
            TextAnimation("none", 1.0f, 1.0f, 0, "linear")),
    )

    fun byId(id: String): Preset = all.find { it.id == id } ?: all.first()
}
