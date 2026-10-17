package com.pramod.videoeditor.mediaengine

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.pramod.videoeditor.featurevfx.VfxDefinitions
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Real GPU implementations of the VFX whose `renderPath` is `custom`
 * (plus film_grain/vignette — see [VfxEffectFactory] for why those live here
 * too), for the Media3 Transformer export pipeline.
 *
 * Pattern (verified against media3-effect 1.11.0 sources):
 * each effect implements [GlEffect] and returns a [BaseGlShaderProgram]
 * subclass built on [GlProgram]. The frame processor feeds every program a
 * regular 2D texture (`sampler2D` — the decoder's external OES texture is
 * converted to an internal texture by Media3's own sampling program before
 * any effect program runs), focused on the output FBO with the viewport set,
 * so [drawFrame] only has to draw a full-screen quad.
 *
 * Every effect is time-windowed: [startUs]/[endUs] are clip-relative
 * microseconds (see ExportEngine.effectWindowUs). Outside the window the
 * shader is a passthrough. The per-frame envelope (with short edge ramps)
 * is folded into the `u_active` uniform together with [intensity].
 *
 * Low-end fallbacks from [VfxDefinitions] are applied by [VfxEffectFactory]
 * before these classes are constructed (skip / degrade / clamp); the classes
 * themselves always render the real effect.
 */
abstract class VfxGlEffect(
    override val effectId: String,
    protected val startUs: Long,
    protected val endUs: Long,
    protected val intensity: Float,
    protected val lowEnd: Boolean,
) : GlEffect, CustomRenderer {

    override fun isSupported(): Boolean = true

    override fun fallbackDescription(): String =
        VfxDefinitions.byId(effectId)?.lowEndFallback.orEmpty()

    /** Effect-specific fragment shader. Must declare AND use `u_tex` and `u_active`. */
    protected abstract val fragmentShader: String

    /**
     * Sets this effect's own uniforms. Only names that are declared (and used)
     * in [fragmentShader] may be set — GlProgram null-checks every name.
     */
    protected open fun updateUniforms(
        program: GlProgram,
        presentationTimeUs: Long,
        inputWidth: Int,
        inputHeight: Int,
    ) {
    }

    final override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return object : BaseGlShaderProgram(
            /* useHighPrecisionColorComponents= */ false,
            /* texturePoolCapacity= */ 1,
        ) {
            private val program: GlProgram = try {
                GlProgram(VERTEX_SHADER, fragmentShader)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
            private var frameW: Int = 0
            private var frameH: Int = 0

            override fun configure(inputWidth: Int, inputHeight: Int): Size {
                frameW = inputWidth
                frameH = inputHeight
                return Size(inputWidth, inputHeight)
            }

            override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
                try {
                    val active = effectEnvelope(presentationTimeUs, startUs, endUs) * intensity
                    program.use()
                    program.setSamplerTexIdUniform("u_tex", inputTexId, /* texUnitIndex= */ 0)
                    program.setBufferAttribute("a_pos", FULLSCREEN_QUAD, /* size= */ 4)
                    program.setFloatUniform("u_active", active)
                    updateUniforms(program, presentationTimeUs, frameW, frameH)
                    program.bindAttributesAndUniforms()
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
                    GlUtil.checkGlError()
                } catch (e: GlUtil.GlException) {
                    throw VideoFrameProcessingException(e, presentationTimeUs)
                }
            }

            override fun release() {
                super.release()
                try {
                    program.delete()
                } catch (e: GlUtil.GlException) {
                    throw VideoFrameProcessingException(e)
                }
            }
        }
    }

    companion object {
        /** Full-screen quad as a triangle strip: (x, y, z, w) per vertex. */
        val FULLSCREEN_QUAD = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 0f, 1f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 0f, 1f,
        )

        const val VERTEX_SHADER: String = """#version 100
attribute vec4 a_pos;
varying vec2 v_uv;
void main() {
  gl_Position = a_pos;
  v_uv = a_pos.xy * 0.5 + 0.5;
}
"""

        /** Integer hash without bit ops (ES 2.0 fragment shaders lack them). */
        const val HASH_GLSL: String = """
float vfx_hash(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}
"""
    }
}

/**
 * "shake" — sinusoidal camera shake. The offset is computed on the CPU per
 * frame (avoids mediump trig precision issues) and applied as a UV shift in
 * the fragment shader. Edge pixels clamp (GL default wrap).
 */
class ShakeGlEffect(
    effectId: String,
    startUs: Long,
    endUs: Long,
    intensity: Float,
    lowEnd: Boolean,
    private val amplitudePx: Float,
    private val frequencyHz: Float,
) : VfxGlEffect(effectId, startUs, endUs, intensity, lowEnd) {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = amplitudePx <= 0.01f

    override val fragmentShader: String = """#version 100
precision mediump float;
varying vec2 v_uv;
uniform sampler2D u_tex;
uniform float u_active;
uniform vec2 u_shake_uv;
void main() {
  vec2 uv = v_uv + u_shake_uv * u_active;
  gl_FragColor = texture2D(u_tex, uv);
}
"""

    override fun updateUniforms(
        program: GlProgram,
        presentationTimeUs: Long,
        inputWidth: Int,
        inputHeight: Int,
    ) {
        val t = presentationTimeUs / 1_000_000f
        val env = effectEnvelope(presentationTimeUs, startUs, endUs)
        val phase = 2f * PI.toFloat() * frequencyHz * t
        val w = inputWidth.coerceAtLeast(1).toFloat()
        val h = inputHeight.coerceAtLeast(1).toFloat()
        val dx = amplitudePx * sin(phase) / w * env
        val dy = amplitudePx * cos(phase * 0.9f) / h * env
        program.setFloatsUniform("u_shake_uv", floatArrayOf(dx, dy))
    }
}

/** "rgb_split" — chromatic aberration: R/B channels shifted horizontally. */
class RgbSplitGlEffect(
    effectId: String,
    startUs: Long,
    endUs: Long,
    intensity: Float,
    lowEnd: Boolean,
    private val offsetPx: Float,
) : VfxGlEffect(effectId, startUs, endUs, intensity, lowEnd) {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = offsetPx <= 0.01f

    override val fragmentShader: String = """#version 100
precision mediump float;
varying vec2 v_uv;
uniform sampler2D u_tex;
uniform float u_active;
uniform float u_offset_u;
void main() {
  float dx = u_offset_u * u_active;
  vec3 rgb;
  rgb.r = texture2D(u_tex, v_uv + vec2(dx, 0.0)).r;
  rgb.g = texture2D(u_tex, v_uv).g;
  rgb.b = texture2D(u_tex, v_uv - vec2(dx, 0.0)).b;
  gl_FragColor = vec4(rgb, texture2D(u_tex, v_uv).a);
}
"""

    override fun updateUniforms(
        program: GlProgram,
        presentationTimeUs: Long,
        inputWidth: Int,
        inputHeight: Int,
    ) {
        program.setFloatUniform("u_offset_u", offsetPx / inputWidth.coerceAtLeast(1).toFloat())
    }
}

/**
 * "digital_glitch" — horizontal slice displacement on hash-selected rows plus
 * chromatic aberration and a subtle scanline shimmer, all time-seeded.
 * Low-end mode (`u_lowq = 1`) keeps only the single-frame RGB split, per the
 * definition's fallback.
 */
class DigitalGlitchGlEffect(
    effectId: String,
    startUs: Long,
    endUs: Long,
    intensity: Float,
    lowEnd: Boolean,
    private val blocks: Float,
    private val seedJitter: Float,
    /** 1 = low-end RGB-split-only mode. */
    private val lowQuality: Boolean,
) : VfxGlEffect(effectId, startUs, endUs, intensity, lowEnd) {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        seedJitter * intensity <= 0.001f

    override val fragmentShader: String = """#version 100
precision mediump float;
varying vec2 v_uv;
uniform sampler2D u_tex;
uniform float u_active;
uniform float u_time_s;
uniform float u_blocks;
uniform float u_jitter;
uniform float u_rgb_u;
uniform float u_lowq;
${HASH_GLSL}
void main() {
  vec2 uv = v_uv;
  float t = u_time_s;
  if (u_lowq < 0.5) {
    float row = floor(uv.y * u_blocks);
    float rnd = vfx_hash(vec2(row, floor(t * 24.0)));
    float gate = step(0.55, vfx_hash(vec2(floor(t * 8.0), row * 0.37 + 1.7)));
    float shift = (rnd - 0.5) * u_jitter * 0.35 * gate * u_active;
    uv.x = fract(uv.x + shift);
    float tearGate = step(0.93, vfx_hash(vec2(floor(t * 3.0), 7.0)));
    float tear = (vfx_hash(vec2(3.0, floor(t * 3.0))) - 0.5) * 0.12 * tearGate * u_active;
    uv.y = fract(uv.y + tear);
  }
  float dx = (u_rgb_u + u_jitter * 0.004) * u_active;
  vec3 rgb;
  rgb.r = texture2D(u_tex, uv + vec2(dx, 0.0)).r;
  rgb.g = texture2D(u_tex, uv).g;
  rgb.b = texture2D(u_tex, uv - vec2(dx, 0.0)).b;
  float scan = 0.92 + 0.08 * sin(uv.y * 720.0);
  rgb *= mix(1.0, scan, u_active * (1.0 - u_lowq) * 0.5);
  gl_FragColor = vec4(rgb, texture2D(u_tex, uv).a);
}
"""

    override fun updateUniforms(
        program: GlProgram,
        presentationTimeUs: Long,
        inputWidth: Int,
        inputHeight: Int,
    ) {
        program.setFloatUniform("u_time_s", presentationTimeUs / 1_000_000f)
        program.setFloatUniform("u_blocks", blocks.coerceAtLeast(2f))
        program.setFloatUniform("u_jitter", seedJitter)
        program.setFloatUniform(
            "u_rgb_u", 6f / inputWidth.coerceAtLeast(1).toFloat()
        )
        program.setFloatUniform("u_lowq", if (lowQuality) 1f else 0f)
    }
}

/**
 * "film_grain" — animated luminance grain.
 * NOTE: VfxDefinitions marks this `renderPath: media3`, but media3-effect
 * 1.11.0 ships no grain/noise effect, so this is a real custom shader
 * (documented deviation, not a fake).
 */
class FilmGrainGlEffect(
    effectId: String,
    startUs: Long,
    endUs: Long,
    intensity: Float,
    lowEnd: Boolean,
    private val amount: Float,
) : VfxGlEffect(effectId, startUs, endUs, intensity, lowEnd) {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        amount * intensity <= 0.001f

    override val fragmentShader: String = """#version 100
precision mediump float;
varying vec2 v_uv;
uniform sampler2D u_tex;
uniform float u_active;
uniform float u_time_s;
uniform float u_amount;
uniform vec2 u_tex_size;
${HASH_GLSL}
void main() {
  vec4 c = texture2D(u_tex, v_uv);
  vec2 cell = v_uv * u_tex_size
      + vec2(fract(u_time_s * 13.7) * 91.0, fract(u_time_s * 7.31) * 57.0);
  float g = vfx_hash(cell);
  c.rgb += (g - 0.5) * u_amount * u_active;
  gl_FragColor = c;
}
"""

    override fun updateUniforms(
        program: GlProgram,
        presentationTimeUs: Long,
        inputWidth: Int,
        inputHeight: Int,
    ) {
        program.setFloatUniform("u_time_s", presentationTimeUs / 1_000_000f)
        program.setFloatUniform("u_amount", amount)
        program.setFloatsUniform(
            "u_tex_size",
            floatArrayOf(inputWidth.toFloat(), inputHeight.toFloat()),
        )
    }
}

/**
 * "vignette" — aspect-correct radial darkening.
 * NOTE: same documented deviation as film_grain — no Media3 vignette exists
 * in 1.11.0, so this is a real custom shader.
 */
class VignetteGlEffect(
    effectId: String,
    startUs: Long,
    endUs: Long,
    intensity: Float,
    lowEnd: Boolean,
    private val strength: Float,
) : VfxGlEffect(effectId, startUs, endUs, intensity, lowEnd) {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        strength * intensity <= 0.001f

    override val fragmentShader: String = """#version 100
precision mediump float;
varying vec2 v_uv;
uniform sampler2D u_tex;
uniform float u_active;
uniform float u_strength;
uniform float u_aspect;
void main() {
  vec4 c = texture2D(u_tex, v_uv);
  vec2 p = (v_uv - 0.5) * vec2(u_aspect, 1.0);
  float d = length(p);
  float vig = 1.0 - smoothstep(0.25, 0.85, d);
  c.rgb *= mix(1.0, vig, u_strength * u_active);
  gl_FragColor = c;
}
"""

    override fun updateUniforms(
        program: GlProgram,
        presentationTimeUs: Long,
        inputWidth: Int,
        inputHeight: Int,
    ) {
        program.setFloatUniform("u_strength", strength)
        program.setFloatUniform(
            "u_aspect",
            inputWidth.toFloat() / inputHeight.coerceAtLeast(1).toFloat(),
        )
    }
}

/**
 * "light_leak" — warm color wash bleeding in from the top-right (and faintly
 * the bottom-left), tinted by the `hue` parameter. Skipped on low-end devices
 * per the definition's fallback (the factory returns no effect).
 */
class LightLeakGlEffect(
    effectId: String,
    startUs: Long,
    endUs: Long,
    intensity: Float,
    lowEnd: Boolean,
    private val strength: Float,
    private val leakRgb: FloatArray,
) : VfxGlEffect(effectId, startUs, endUs, intensity, lowEnd) {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        strength * intensity <= 0.001f

    override val fragmentShader: String = """#version 100
precision mediump float;
varying vec2 v_uv;
uniform sampler2D u_tex;
uniform float u_active;
uniform float u_strength;
uniform vec3 u_leak_rgb;
void main() {
  vec4 c = texture2D(u_tex, v_uv);
  float d1 = distance(v_uv, vec2(1.0, 0.05));
  float d2 = distance(v_uv, vec2(0.0, 0.95));
  float leak = exp(-d1 * d1 * 4.0) + 0.55 * exp(-d2 * d2 * 5.0);
  float k = clamp(leak, 0.0, 1.0) * u_strength * u_active;
  c.rgb = mix(c.rgb, u_leak_rgb, k * 0.55);
  c.rgb += u_leak_rgb * leak * 0.18 * u_strength * u_active;
  gl_FragColor = c;
}
"""

    override fun updateUniforms(
        program: GlProgram,
        presentationTimeUs: Long,
        inputWidth: Int,
        inputHeight: Int,
    ) {
        program.setFloatUniform("u_strength", strength)
        program.setFloatsUniform("u_leak_rgb", leakRgb)
    }
}

/**
 * "sparkle" — procedural twinkling star sparkles at hash-distributed
 * positions. Constant loop bound (64) with an early break on `u_count`,
 * which is legal in GLSL ES 1.00.
 */
class SparkleGlEffect(
    effectId: String,
    startUs: Long,
    endUs: Long,
    intensity: Float,
    lowEnd: Boolean,
    private val count: Int,
    private val sizePx: Float,
) : VfxGlEffect(effectId, startUs, endUs, intensity, lowEnd) {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = count <= 0

    override val fragmentShader: String = """#version 100
precision mediump float;
varying vec2 v_uv;
uniform sampler2D u_tex;
uniform float u_active;
uniform float u_time_s;
uniform int u_count;
uniform float u_size_u;
uniform float u_aspect;
${HASH_GLSL}
void main() {
  vec4 c = texture2D(u_tex, v_uv);
  vec3 acc = vec3(0.0);
  for (int i = 0; i < 64; i++) {
    if (i >= u_count) break;
    float fi = float(i);
    vec2 pos = vec2(vfx_hash(vec2(fi * 1.37, 11.0)), vfx_hash(vec2(fi * 2.11, 47.0)));
    float spd = 1.5 + vfx_hash(vec2(fi, 99.0)) * 3.5;
    float tw = 0.5 + 0.5 * sin(u_time_s * spd + fi * 2.399);
    vec2 delta = (v_uv - pos) * vec2(u_aspect, 1.0);
    float sz = u_size_u * (0.6 + 0.4 * tw);
    float core = 1.0 - smoothstep(0.0, sz, length(delta));
    float cross = (1.0 - smoothstep(0.0, sz * 2.6, abs(delta.x)))
                    * (1.0 - smoothstep(0.0, sz * 0.35, abs(delta.y)))
                + (1.0 - smoothstep(0.0, sz * 2.6, abs(delta.y)))
                    * (1.0 - smoothstep(0.0, sz * 0.35, abs(delta.x)));
    acc += vec3(1.0, 0.97, 0.88) * (core * 0.7 + cross * 0.5) * tw;
  }
  c.rgb += acc * u_active;
  gl_FragColor = c;
}
"""

    override fun updateUniforms(
        program: GlProgram,
        presentationTimeUs: Long,
        inputWidth: Int,
        inputHeight: Int,
    ) {
        program.setFloatUniform("u_time_s", presentationTimeUs / 1_000_000f)
        program.setIntUniform("u_count", count.coerceIn(0, 64))
        program.setFloatUniform("u_size_u", sizePx / inputHeight.coerceAtLeast(1).toFloat())
        program.setFloatUniform(
            "u_aspect",
            inputWidth.toFloat() / inputHeight.coerceAtLeast(1).toFloat(),
        )
    }
}

/**
 * "glow_text" — soft bright-pass bloom over the frame.
 *
 * Honest scoping note: true text glow belongs to the text renderer (the
 * captions/overlay chunk draws text and should use Paint.setShadowLayer
 * there). This GlEffect resolves the `glow_text` effect id to a real
 * frame-level glow so the id never silently does nothing in export; it is
 * skipped on low-end devices ("Plain text, no glow" fallback).
 */
class GlowTextGlEffect(
    effectId: String,
    startUs: Long,
    endUs: Long,
    intensity: Float,
    lowEnd: Boolean,
    private val radiusPx: Float,
    private val strength: Float,
) : VfxGlEffect(effectId, startUs, endUs, intensity, lowEnd) {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        strength * intensity <= 0.001f || radiusPx <= 0.01f

    override val fragmentShader: String = """#version 100
precision mediump float;
varying vec2 v_uv;
uniform sampler2D u_tex;
uniform float u_active;
uniform float u_strength;
uniform float u_radius_u;
void main() {
  vec4 c = texture2D(u_tex, v_uv);
  vec3 blur = vec3(0.0);
  for (int x = -1; x <= 1; x++) {
    for (int y = -1; y <= 1; y++) {
      blur += texture2D(u_tex, v_uv + vec2(float(x), float(y)) * u_radius_u).rgb;
    }
  }
  blur /= 9.0;
  vec3 bright = max(blur - vec3(0.5), vec3(0.0)) * 2.0;
  c.rgb += bright * u_strength * u_active;
  gl_FragColor = c;
}
"""

    override fun updateUniforms(
        program: GlProgram,
        presentationTimeUs: Long,
        inputWidth: Int,
        inputHeight: Int,
    ) {
        program.setFloatUniform("u_strength", strength)
        program.setFloatUniform("u_radius_u", radiusPx / inputWidth.coerceAtLeast(1).toFloat())
    }
}
