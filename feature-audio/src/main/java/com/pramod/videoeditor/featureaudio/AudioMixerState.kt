package com.pramod.videoeditor.featureaudio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Mixer state for the Audio Mixer screen: separate original/music/SFX controls. */
data class MixerState(
    val originalVolume: Float = 1f,
    val musicVolume: Float = 0.8f,
    val sfxVolume: Float = 0.9f,
    val originalMuted: Boolean = false,
    val musicMuted: Boolean = false,
    val sfxMuted: Boolean = false,
)

class AudioMixerController {
    private val _state = MutableStateFlow(MixerState())
    val state: StateFlow<MixerState> = _state.asStateFlow()

    fun setOriginalVolume(v: Float) = _state.update { it.copy(originalVolume = v.coerceIn(0f, 1f)) }
    fun setMusicVolume(v: Float) = _state.update { it.copy(musicVolume = v.coerceIn(0f, 1f)) }
    fun setSfxVolume(v: Float) = _state.update { it.copy(sfxVolume = v.coerceIn(0f, 1f)) }
    fun toggleOriginalMute() = _state.update { it.copy(originalMuted = !it.originalMuted) }
    fun toggleMusicMute() = _state.update { it.copy(musicMuted = !it.musicMuted) }
    fun toggleSfxMute() = _state.update { it.copy(sfxMuted = !it.sfxMuted) }
    fun muteAll() = _state.update { it.copy(originalMuted = true, musicMuted = true, sfxMuted = true) }
}
