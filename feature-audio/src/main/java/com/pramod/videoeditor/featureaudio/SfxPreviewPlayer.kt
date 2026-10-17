package com.pramod.videoeditor.featureaudio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer

/**
 * Plays real SFX assets from the bundled sfx/ assets for audible preview.
 *
 * Single-instance: starting a new preview stops the previous one.
 * Uses the framework [MediaPlayer] (no new dependencies) with an
 * [android.content.res.AssetFileDescriptor] opened via
 * `context.assets.openFd(SfxLibrary.resolve(...)!!.fileName)`.
 *
 * Never fakes success: [play] returns false when the category has no asset
 * or playback fails to start, so the UI can show a real error message.
 */
object SfxPreviewPlayer {

    private var player: MediaPlayer? = null

    /**
     * Plays the canonical sound for [category].
     * @param volume 0f..1f gain applied to the preview (e.g. mixer SFX level)
     * @return true when playback actually started, false otherwise.
     */
    @Synchronized
    fun play(context: Context, category: String, volume: Float = 1f): Boolean {
        val asset = SfxLibrary.resolve(SfxLibrary.assetIdFor(category)) ?: return false
        stopLocked()
        val appContext = context.applicationContext
        return try {
            val afd = appContext.assets.openFd(asset.fileName)
            val mp = MediaPlayer()
            try {
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            } finally {
                afd.close()
            }
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setOnCompletionListener { finished -> if (finished === player) stopLocked() }
            mp.setOnErrorListener { errored, _, _ -> if (errored === player) stopLocked(); true }
            mp.setVolume(volume.coerceIn(0f, 1f), volume.coerceIn(0f, 1f))
            mp.prepare()
            mp.start()
            player = mp
            true
        } catch (e: Exception) {
            stopLocked()
            false
        }
    }

    /** Stops any in-flight preview. Safe to call anytime. */
    @Synchronized
    fun stop() {
        stopLocked()
    }

    private fun stopLocked() {
        val mp = player
        player = null
        if (mp != null) {
            try {
                mp.stop()
            } catch (_: Exception) {
                // Already stopped/completed — nothing to do.
            }
            try {
                mp.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }
}
