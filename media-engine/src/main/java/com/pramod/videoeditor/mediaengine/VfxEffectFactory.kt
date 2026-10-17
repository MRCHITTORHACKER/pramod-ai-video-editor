package com.pramod.videoeditor.mediaengine

import android.app.ActivityManager
import android.content.Context
import android.graphics.Matrix
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ConvolutionFunction1D
import androidx.media3.effect.GaussianFunction
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.SeparableConvolution
import com.pramod.videoeditor.featurevfx.VfxDefinitions
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 0..1 envelope for a clip-relative time window, with ~120ms raised edge
 * ramps so effects fade in/out instead of popping. 0 outside
 * [startUs, endUs]. Shared by the custom shader effects and the timed
 * Media3-native wrappers below.
 */
internal fun effectEnvelope(presentationTimeUs: Long, startUs: Long, endUs: Long): Float {
    if (presentationTimeUs < startUs || presentationTimeUs > endUs) return 0f
    val span = (endUs - startUs).coerceAtLeast(1L)
    val ramp = min(120_000L, span / 4).coerceAtLeast(1L)
    val tIn = (presentationTimeUs - startUs).toFloat() / ramp
    val tOut = (endUs - presentationTimeUs).toFloat() / ramp
    return min(tIn, tOut).coerceIn(0f, 1f)
}

/** Column-major 4x4 identity, matching Media3's GL matrix layout. Never mutate. */
internal val IDENTITY_4X4: FloatArray =
    floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

/** Honest low-end heuristic: Android low-RAM flag or < 3GB total RAM. */
object DevicePerformance {
    fun isLowEnd(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        if (am.isLowRamDevice) return true
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem < 3L * 1024L * 1024L * 1024L
    }
}

/**
 * Maps every [VfxDefinitions] effect id to REAL Media3 [Effect] instances.
 *
 * Two implementation families:
 * - Media3-native: [MatrixTransformation]/[RgbMatrix]/[SeparableConvolution]
 *   implementations (time-varying via the per-timestamp callbacks Media3
 *   designed for exactly this). These are androidx.media3.effect APIs, not
 *   custom GL code.
 * - Custom shaders: the [VfxGlEffect] subclasses in GlShaderEffects.kt, which
 *   are real [androidx.media3.effect.GlEffect]s with real GLSL.
 *
 * Documented deviations from VfxDefinitions.renderPath: `film_grain` and
 * `vignette` are labeled "media3" but media3-effect 1.11.0 ships no
 * grain/vignette effect, so they are implemented as custom shaders rather
 * than faked.
 *
 * Low-end fallbacks from each definition are applied honestly here:
 * degraded (clamped params / cheaper mode), never silently dropped — except
 * where the definition itself says "skip"/"disable" ("shake",
 * "light_leak", "glow_text"), which return an empty list.
 *
 * @param intensity 0..1, already combined (template * user). Scales each
 *   effect's strength toward neutral.
 */
object VfxEffectFactory {

    fun create(
        effectId: String,
        params: Map<String, Float>,
        intensity: Float,
        startUs: Long,
        endUs: Long,
        lowEnd: Boolean,
    ): List<Effect> {
        val def = VfxDefinitions.byId(effectId) ?: return emptyList()
        fun p(key: String): Float = params[key] ?: def.params[key]?.third ?: 0f

        return when (effectId) {
            "smooth_zoom" -> {
                val zoomFrom = 1f + (p("zoomFrom") - 1f) * intensity
                var zoomTo = p("zoomTo")
                if (lowEnd) zoomTo = min(zoomTo, 1.1f) // fallback: reduce zoom range to 1.0-1.1
                zoomTo = 1f + (zoomTo - 1f) * intensity
                if (abs(zoomFrom - 1f) < 1e-3f && abs(zoomTo - 1f) < 1e-3f) emptyList()
                else listOf(AnimatedZoomEffect(zoomFrom, zoomTo, startUs, endUs))
            }

            "shake" -> {
                if (lowEnd) return emptyList() // fallback: disable shake, keep static frame
                val amp = p("amplitudePx")
                if (amp <= 0.01f) emptyList()
                else listOf(
                    ShakeGlEffect(effectId, startUs, endUs, intensity, lowEnd, amp, p("frequencyHz"))
                )
            }

            "rgb_split" -> {
                var offset = p("offsetPx")
                if (lowEnd) offset = min(offset, 3f) // fallback: reduce offset to <= 3px
                if (offset <= 0.01f) emptyList()
                else listOf(RgbSplitGlEffect(effectId, startUs, endUs, intensity, lowEnd, offset))
            }

            "digital_glitch" -> {
                val jitter = p("seedJitter")
                if (jitter <= 0.001f) return emptyList()
                // Low-end fallback: single-frame RGB split only (no slice displacement).
                listOf(
                    DigitalGlitchGlEffect(
                        effectId, startUs, endUs, intensity, lowEnd,
                        blocks = p("blocks"), seedJitter = jitter, lowQuality = lowEnd,
                    )
                )
            }

            "film_grain" -> {
                var amount = p("amount")
                if (lowEnd) amount *= 0.5f // fallback: halve grain amount
                if (amount <= 0.001f) emptyList()
                else listOf(FilmGrainGlEffect(effectId, startUs, endUs, intensity, lowEnd, amount))
            }

            "vignette" -> {
                var strength = p("strength")
                if (lowEnd) strength = min(strength, 0.25f) // fallback: reduce strength to 0.25
                if (strength <= 0.001f) emptyList()
                else listOf(VignetteGlEffect(effectId, startUs, endUs, intensity, lowEnd, strength))
            }

            "light_leak" -> {
                if (lowEnd) return emptyList() // fallback: skip on low-end devices
                val strength = p("strength")
                if (strength <= 0.001f) emptyList()
                else listOf(
                    LightLeakGlEffect(
                        effectId, startUs, endUs, intensity, lowEnd,
                        strength, hueToRgb(p("hue")),
                    )
                )
            }

            "sparkle" -> {
                var count = p("count").toInt()
                if (lowEnd) count = min(count, 8) // fallback: reduce count to 8
                if (count <= 0) emptyList()
                else listOf(
                    SparkleGlEffect(
                        effectId, startUs, endUs, intensity, lowEnd,
                        count.coerceIn(0, 64), p("sizePx"),
                    )
                )
            }

            "color_grade" -> {
                if (lowEnd) {
                    // Fallback: apply contrast only.
                    val contrast = 1f + (p("contrast") - 1f) * intensity
                    if (abs(contrast - 1f) < 1e-3f) emptyList()
                    else listOf(TimedContrastEffect(contrast, startUs, endUs))
                } else {
                    val out = mutableListOf<Effect>()
                    val contrast = 1f + (p("contrast") - 1f) * intensity
                    if (abs(contrast - 1f) >= 1e-3f) out += TimedContrastEffect(contrast, startUs, endUs)
                    val saturation = 1f + (p("saturation") - 1f) * intensity
                    if (abs(saturation - 1f) >= 1e-3f) out += TimedSaturationEffect(saturation, startUs, endUs)
                    val warmth = 0.5f + (p("warmth") - 0.5f) * intensity
                    if (abs(warmth - 0.5f) >= 1e-3f) out += TimedWarmthEffect(warmth, startUs, endUs)
                    out
                }
            }

            "blur_transition" -> {
                val radiusPx = p("radiusPx") * intensity
                if (lowEnd) {
                    // Fallback: use fade instead — a dip through black mid-window.
                    if (radiusPx <= 0.01f) emptyList()
                    else listOf(TimedFadeDipEffect(0.55f * intensity, startUs, endUs))
                } else {
                    if (radiusPx <= 0.01f) emptyList()
                    else listOf(TimedBlurEffect(radiusPx * 0.5f, startUs, endUs))
                }
            }

            "fade" -> {
                // Fade to black: brightness -(1-opacity). Same cheap path on low-end.
                val darkening = (1f - p("opacity")) * intensity
                if (darkening <= 0.001f) emptyList()
                else listOf(TimedBrightnessEffect(-darkening, startUs, endUs))
            }

            "flash" -> {
                var peak = p("peakWhite") * intensity
                if (lowEnd) peak = min(peak, 0.4f) // fallback: reduce peak (photosensitivity)
                if (peak <= 0.001f) emptyList()
                else listOf(FlashEffect(peak, startUs, endUs))
            }

            "glow_text" -> {
                if (lowEnd) return emptyList() // fallback: plain text, no glow
                val strength = p("strength")
                if (strength <= 0.001f) emptyList()
                else listOf(
                    GlowTextGlEffect(
                        effectId, startUs, endUs, intensity, lowEnd, p("radiusPx"), strength
                    )
                )
            }

            "letterbox" -> {
                // Cheap on all devices — same path on low-end.
                val bar = p("barPercent") / 100f * intensity
                if (bar <= 0.001f) emptyList()
                else listOf(TimedLetterboxEffect(bar, startUs, endUs))
            }

            "dim_background" -> {
                // Cheap on all devices — same path on low-end.
                val opacity = p("opacity") * intensity
                if (opacity <= 0.001f) emptyList()
                else listOf(TimedBrightnessEffect(-opacity, startUs, endUs))
            }

            else -> emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Color matrix helpers (column-major 4x4, Media3's GL layout).
    // ------------------------------------------------------------------

    /** Media3's own contrast matrix: factor (1+c)/(1.0001-c) about 0.5 gray. */
    internal fun contrastMatrix(contrast: Float): FloatArray {
        val f = (1f + contrast) / (1.0001f - contrast)
        val t = (1f - f) * 0.5f
        return floatArrayOf(
            f, 0f, 0f, 0f,
            0f, f, 0f, 0f,
            0f, 0f, f, 0f,
            t, t, t, 1f,
        )
    }

    /** Standard saturation matrix (luminance-preserving). s=1 -> identity. */
    internal fun saturationMatrix(s: Float): FloatArray {
        val lr = 0.299f; val lg = 0.587f; val lb = 0.114f
        return floatArrayOf(
            lr + (1f - lr) * s, lr * (1f - s), lr * (1f - s), 0f,
            lg * (1f - s), lg + (1f - lg) * s, lg * (1f - s), 0f,
            lb * (1f - s), lb * (1f - s), lb + (1f - lb) * s, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    /** Warmth: red up / blue down around neutral 0.5. */
    internal fun warmthMatrix(warmth: Float): FloatArray {
        val w = (warmth - 0.5f) * 0.6f
        return floatArrayOf(
            1f + w, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f - w, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    /** Additive brightness (Media3's Brightness semantics). */
    internal fun brightnessMatrix(brightness: Float): FloatArray {
        return floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            brightness, brightness, brightness, 1f,
        )
    }

    /** HSV hue (0..360) -> linear-ish RGB for the light-leak tint. */
    internal fun hueToRgb(hueDeg: Float): FloatArray {
        val h = ((hueDeg % 360f) + 360f) % 360f / 60f
        val c = 1f
        val x = c * (1f - abs(h % 2f - 1f))
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return floatArrayOf(r, g, b)
    }
}

// ---------------------------------------------------------------------------
// Timed Media3-native effects.
// ---------------------------------------------------------------------------

/**
 * Base for time-windowed [RgbMatrix] effects. [fullMatrix] is the effect at
 * full strength; the per-frame matrix is lerped from identity by the
 * envelope, so the effect ramps in/out instead of popping.
 */
abstract class TimedRgbMatrixEffect(
    private val startUs: Long,
    private val endUs: Long,
) : RgbMatrix {

    protected abstract fun fullMatrix(): FloatArray

    final override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val t = effectEnvelope(presentationTimeUs, startUs, endUs)
        if (t <= 0f) return IDENTITY_4X4
        val full = fullMatrix()
        return FloatArray(16) { i -> IDENTITY_4X4[i] + (full[i] - IDENTITY_4X4[i]) * t }
    }
}

/** "color_grade" contrast arm — Media3-native (cf. Contrast). */
class TimedContrastEffect(
    private val contrast: Float,
    startUs: Long,
    endUs: Long,
) : TimedRgbMatrixEffect(startUs, endUs) {
    override fun fullMatrix(): FloatArray = VfxEffectFactory.contrastMatrix(contrast - 1f)
}

/** "color_grade" saturation arm — Media3-native RgbMatrix. */
class TimedSaturationEffect(
    private val saturation: Float,
    startUs: Long,
    endUs: Long,
) : TimedRgbMatrixEffect(startUs, endUs) {
    override fun fullMatrix(): FloatArray = VfxEffectFactory.saturationMatrix(saturation)
}

/** "color_grade" warmth arm — Media3-native RgbMatrix (cf. RgbAdjustment). */
class TimedWarmthEffect(
    private val warmth: Float,
    startUs: Long,
    endUs: Long,
) : TimedRgbMatrixEffect(startUs, endUs) {
    override fun fullMatrix(): FloatArray = VfxEffectFactory.warmthMatrix(warmth)
}

/**
 * Additive brightness with a time window. Backs "fade" (negative values =
 * fade to black) and "dim_background". Media3-native (cf. Brightness).
 */
class TimedBrightnessEffect(
    private val brightness: Float,
    startUs: Long,
    endUs: Long,
) : TimedRgbMatrixEffect(startUs, endUs) {
    override fun fullMatrix(): FloatArray = VfxEffectFactory.brightnessMatrix(brightness)
}

/**
 * Low-end substitute for "blur_transition" ("use fade instead"): a dip
 * through black peaking mid-window. Media3-native.
 */
class TimedFadeDipEffect(
    private val depth: Float,
    private val startUs: Long,
    private val endUs: Long,
) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val span = (endUs - startUs).coerceAtLeast(1L)
        if (presentationTimeUs < startUs || presentationTimeUs > endUs) return IDENTITY_4X4
        val t01 = (presentationTimeUs - startUs).toFloat() / span
        val b = -depth * sin(PI.toFloat() * t01)
        if (abs(b) < 1e-4f) return IDENTITY_4X4
        return VfxEffectFactory.brightnessMatrix(b)
    }
}

/**
 * "flash" — white burst peaking mid-window (smooth sine profile, no hard
 * edges). Media3-native RgbMatrix.
 */
class FlashEffect(
    private val peakWhite: Float,
    private val startUs: Long,
    private val endUs: Long,
) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val span = (endUs - startUs).coerceAtLeast(1L)
        if (presentationTimeUs < startUs || presentationTimeUs > endUs) return IDENTITY_4X4
        val t01 = (presentationTimeUs - startUs).toFloat() / span
        val b = peakWhite * sin(PI.toFloat() * t01)
        if (b < 1e-4f) return IDENTITY_4X4
        return VfxEffectFactory.brightnessMatrix(b)
    }
}

/**
 * "smooth_zoom" — animated scale about the frame center, driven by
 * [MatrixTransformation.getMatrix], which Media3 calls per timestamp.
 * Smoothstep easing for a natural push. Genuinely Media3-native.
 */
class AnimatedZoomEffect(
    private val zoomFrom: Float,
    private val zoomTo: Float,
    private val startUs: Long,
    private val endUs: Long,
) : MatrixTransformation {

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val span = (endUs - startUs).coerceAtLeast(1L)
        val t = ((presentationTimeUs - startUs).toFloat() / span).coerceIn(0f, 1f)
        val eased = t * t * (3f - 2f * t)
        val z = zoomFrom + (zoomTo - zoomFrom) * eased
        // NDC origin is the frame center, so this zooms about the center.
        return Matrix().apply { setScale(z, z) }
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        abs(zoomFrom - 1f) < 1e-3f && abs(zoomTo - 1f) < 1e-3f
}

/**
 * "letterbox" — vertical squeeze revealing black bars top/bottom (the
 * cleared output buffer is (0,0,0,0), which encodes as black). Animated via
 * the envelope so bars slide in/out. Media3-native.
 */
class TimedLetterboxEffect(
    private val barFraction: Float,
    private val startUs: Long,
    private val endUs: Long,
) : MatrixTransformation {

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val t = effectEnvelope(presentationTimeUs, startUs, endUs)
        val sy = 1f - 2f * barFraction * t
        return Matrix().apply { setScale(1f, sy.coerceAtLeast(0.6f)) }
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = barFraction < 1e-3f
}

/**
 * "blur_transition" — time-windowed Gaussian blur. Media3-native via
 * [SeparableConvolution]; sigma follows the envelope (floored at 1e-3 so the
 * [GaussianFunction] constructor's sigma > 0 requirement holds — visually
 * identical to no blur).
 */
class TimedBlurEffect(
    private val sigma: Float,
    private val startUs: Long,
    private val endUs: Long,
) : SeparableConvolution() {

    override fun getConvolution(presentationTimeUs: Long): ConvolutionFunction1D {
        val t = effectEnvelope(presentationTimeUs, startUs, endUs)
        return GaussianFunction(max(sigma * t, 1e-3f), /* numStandardDeviations= */ 2.0f)
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = sigma <= 1e-3f
}

// ---------------------------------------------------------------------------
// Audio: timeline placement for SFX / audio clips / clip volume.
// ---------------------------------------------------------------------------

/**
 * PCM audio processor that (a) prepends [delayUs] of silence so an SFX or
 * audio clip lands at its timeline offset inside a parallel
 * [androidx.media3.transformer.EditedMediaItemSequence] (sequences mix when
 * overlapping; this is the 1.11.0-safe way to place audio in time),
 * (b) applies a fixed gain from [gainDb], and (c) applies linear
 * fade-in/fade-out anchored at the audible (post-delay) stream.
 *
 * Supports 16-bit and 32-bit-float PCM; any other encoding bypasses the
 * processor ([AudioProcessor.AudioFormat.NOT_SET]) rather than failing.
 * When delay/gain/fades are all neutral the processor also bypasses itself.
 *
 * Backs: SFX events (delay + gain + fades), ClipType.AUDIO clips (delay +
 * gain), and per-clip volume (gain only).
 */
@UnstableApi
class TimelineAudioProcessor(
    private val delayUs: Long = 0L,
    private val gainDb: Float = 0f,
    private val fadeInUs: Long = 0L,
    private val fadeOutUs: Long = 0L,
    /** Audible (post-delay) duration, for fade-out anchoring; <= 0 = unknown. */
    private val audibleDurationUs: Long = 0L,
) : BaseAudioProcessor() {

    private var bytesPerSecond: Long = 0L
    private var bytesPerFrame: Int = 0
    private var pcmEncoding: Int = 0
    private var delayBytesRemaining: Long = 0L
    private var audibleBytesProcessed: Long = 0L
    private val linearGain: Float = 10f.pow(gainDb / 20f)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat)
            : AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET // bypass, don't fail
        }
        if (delayUs <= 0L && abs(gainDb) < 0.01f && fadeInUs <= 0L && fadeOutUs <= 0L) {
            return AudioProcessor.AudioFormat.NOT_SET // neutral: bypass
        }
        pcmEncoding = encoding
        val bytesPerSample = if (encoding == C.ENCODING_PCM_16BIT) 2 else 4
        bytesPerFrame = bytesPerSample * inputAudioFormat.channelCount
        bytesPerSecond = bytesPerFrame.toLong() * inputAudioFormat.sampleRate
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        if (!isActive) return
        delayBytesRemaining = if (bytesPerSecond > 0) delayUs * bytesPerSecond / 1_000_000L else 0L
        audibleBytesProcessed = 0L
    }

    override fun onReset() {
        delayBytesRemaining = 0L
        audibleBytesProcessed = 0L
        bytesPerSecond = 0L
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inputBytes = inputBuffer.remaining()
        // Emit pending delay as silence first, then the (processed) input in the
        // same output buffer — input is always fully consumed, so the pipeline
        // can never stall on an unconsumed buffer.
        val silenceBytes = min(delayBytesRemaining, MAX_SILENCE_CHUNK_BYTES).toInt()
        val out = replaceOutputBuffer(silenceBytes + inputBytes)
        repeat(silenceBytes) { out.put(0.toByte()) }
        delayBytesRemaining -= silenceBytes

        if (inputBytes > 0) {
            processSamples(inputBuffer, out, inputBytes)
            audibleBytesProcessed += inputBytes
            inputBuffer.position(inputBuffer.limit())
        }
        out.flip()
    }

    private fun processSamples(input: ByteBuffer, out: ByteBuffer, inputBytes: Int) {
        val frames = inputBytes / bytesPerFrame
        val startByte = input.position()
        if (pcmEncoding == C.ENCODING_PCM_16BIT) {
            for (f in 0 until frames) {
                val factor = frameFactor(audibleBytesProcessed + f * bytesPerFrame.toLong())
                for (ch in 0 until bytesPerFrame / 2) {
                    val idx = startByte + f * bytesPerFrame + ch * 2
                    val sample = input.getShort(idx)
                    val scaled = (sample * linearGain * factor).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    out.putShort(scaled.toShort())
                }
            }
        } else {
            for (f in 0 until frames) {
                val factor = frameFactor(audibleBytesProcessed + f * bytesPerFrame.toLong())
                for (ch in 0 until bytesPerFrame / 4) {
                    val idx = startByte + f * bytesPerFrame + ch * 4
                    val sample = input.getFloat(idx)
                    out.putFloat((sample * linearGain * factor).coerceIn(-1f, 1f))
                }
            }
        }
    }

    /** Combined fade-in/out factor for a byte position in the audible stream. */
    private fun frameFactor(audibleBytePos: Long): Float {
        var factor = 1f
        if (fadeInUs > 0 && bytesPerSecond > 0) {
            val us = audibleBytePos * 1_000_000L / bytesPerSecond
            if (us < fadeInUs) factor *= us.toFloat() / fadeInUs
        }
        if (fadeOutUs > 0 && audibleDurationUs > 0 && bytesPerSecond > 0) {
            val us = audibleBytePos * 1_000_000L / bytesPerSecond
            val remaining = audibleDurationUs - us
            if (remaining < fadeOutUs) factor *= (remaining.coerceAtLeast(0L)).toFloat() / fadeOutUs
        }
        return factor.coerceIn(0f, 1f)
    }

    companion object {
        /** Max silence emitted per queueInput call (keeps output buffers small). */
        private const val MAX_SILENCE_CHUNK_BYTES = 32 * 1024
    }
}
