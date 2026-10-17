package com.pramod.videoeditor.ui.navigation

/** All 27 app routes. There is intentionally NO login/auth route. */
object Routes {
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val NEW_PROJECT = "new_project"
    const val MEDIA_PICKER = "media_picker"
    const val VIDEO_PREVIEW = "video_preview"
    const val TEMPLATES = "templates"
    const val TEMPLATE_DETAIL = "template_detail/{templateId}"
    const val AI_SETUP = "ai_setup"
    const val AI_PROGRESS = "ai_progress"
    const val TIMELINE = "timeline"
    const val CAPTIONS = "captions"
    const val TYPOGRAPHY = "typography"
    const val EMOJI = "emoji"
    const val VFX = "vfx"
    const val SFX = "sfx"
    const val MUSIC = "music"
    const val MIXER = "mixer"
    const val EXPORT_SETTINGS = "export_settings"
    const val EXPORT_PROGRESS = "export_progress"
    const val EXPORT_HISTORY = "export_history"
    const val PROJECTS = "projects"
    const val RECOVERY = "recovery"
    const val AI_SETTINGS = "ai_settings"
    const val SETTINGS = "settings"
    const val PRIVACY = "privacy"
    const val ABOUT = "about"

    fun templateDetail(id: String) = "template_detail/$id"
}
