package com.pramod.videoeditor.featurecaptions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pramod.videoeditor.coremodel.CaptionSegment
import com.pramod.videoeditor.coremodel.EmojiOverlay
import com.pramod.videoeditor.coremodel.Project
import com.pramod.videoeditor.coremodel.WordTimestamp
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Best-known project duration: the latest end timestamp across clips,
 * captions, emoji overlays and sfx events. 0 when the project is empty.
 */
fun Project.contentDurationMs(): Long =
    (clips.map { it.endMs } +
        captions.map { it.endMs } +
        emojiOverlays.map { it.endMs } +
        sfx.map { it.startMs + it.durationMs })
        .maxOrNull() ?: 0L

/**
 * Derives per-word timings for a caption by evenly splitting [startMs, endMs]
 * across its whitespace-separated words. Deterministic and honest about what it
 * is: an even split, not measured speech timing. The resulting words are
 * contiguous (word[i].endMs == word[i+1].startMs) and the last word ends
 * exactly at [endMs].
 */
fun deriveWordTimings(text: String, startMs: Long, endMs: Long): List<WordTimestamp> {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty() || endMs <= startMs) return emptyList()
    val perWord = (endMs - startMs).toDouble() / words.size
    return words.mapIndexed { i, word ->
        val s = startMs + (perWord * i).roundToLong()
        val e = if (i == words.lastIndex) endMs else startMs + (perWord * (i + 1)).roundToLong()
        WordTimestamp(word, s, e)
    }
}

/** Resolved animation state for one caption at a given elapsed time. */
private data class CaptionAnim(
    val scale: Float,
    val alpha: Float,
    val offsetYPx: Float,
    /** When true, only words whose startMs has passed are drawn (typewriter). */
    val revealProgressively: Boolean,
)

private fun easeOutCubic(t: Float): Float = 1f - (1f - t).let { it * it * it }

private fun easeOutBack(t: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val u = t - 1f
    return 1f + c3 * u.pow(3) + c1 * u.pow(2)
}

/**
 * Maps a kinetic preset id (see KineticPresets) to a deterministic animation
 * state driven purely by elapsed time — no randomness, no wall-clock.
 * Unknown/null ids render statically ("none" behaviour).
 */
private fun captionAnim(presetId: String?, elapsedMs: Long): CaptionAnim {
    val t = elapsedMs.coerceAtLeast(0L)
    return when (presetId) {
        "pop" -> {
            val e = easeOutBack((t / 140f).coerceIn(0f, 1f))
            CaptionAnim(scale = 0.9f + 0.1f * e, alpha = 1f, offsetYPx = 0f, revealProgressively = false)
        }
        "scale_in" -> {
            val e = easeOutCubic((t / 220f).coerceIn(0f, 1f))
            CaptionAnim(scale = 0.88f + 0.12f * e, alpha = 1f, offsetYPx = 0f, revealProgressively = false)
        }
        "fade_in" -> {
            val e = (t / 400f).coerceIn(0f, 1f)
            CaptionAnim(scale = 1f, alpha = e, offsetYPx = 0f, revealProgressively = false)
        }
        "slide_up" -> {
            val e = easeOutCubic((t / 300f).coerceIn(0f, 1f))
            CaptionAnim(scale = 1f, alpha = 1f, offsetYPx = (1f - e) * 40f, revealProgressively = false)
        }
        "typewriter" ->
            CaptionAnim(scale = 1f, alpha = 1f, offsetYPx = 0f, revealProgressively = true)
        else ->
            CaptionAnim(scale = 1f, alpha = 1f, offsetYPx = 0f, revealProgressively = false)
    }
}

/**
 * Draws the active word-level caption(s) over the preview, synced to
 * [positionMs]. The word spoken "now" is highlighted; the rest renders dimmed.
 * Uses real per-word timestamps when the segment has them, otherwise derives
 * them with [deriveWordTimings]. The caption's kinetic preset (if any) drives a
 * deterministic entry animation.
 *
 * Place this inside a Box on top of the video surface in the preview screen.
 */
@Composable
fun CaptionLayerHost(project: Project, positionMs: Long) {
    val active = project.captions
        .filter { it.startMs <= positionMs && positionMs < it.endMs }
        .sortedBy { it.startMs }
    if (active.isEmpty()) return
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 72.dp, start = 24.dp, end = 24.dp),
        ) {
            active.forEach { seg ->
                CaptionItem(seg = seg, positionMs = positionMs)
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun CaptionItem(seg: CaptionSegment, positionMs: Long) {
    val words = if (seg.words.isNotEmpty()) seg.words
    else deriveWordTimings(seg.text, seg.startMs, seg.endMs)
    if (words.isEmpty()) return
    val anim = captionAnim(seg.presetId, positionMs - seg.startMs)
    val visible = if (anim.revealProgressively) words.filter { it.startMs <= positionMs } else words
    if (visible.isEmpty()) return
    val annotated = buildAnnotatedString {
        visible.forEachIndexed { i, w ->
            val isCurrent = w.startMs <= positionMs && positionMs < w.endMs
            withStyle(
                SpanStyle(
                    color = if (isCurrent) Color(0xFFFFEB3B) else Color.White,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                )
            ) { append(w.word) }
            if (i != visible.lastIndex) append(" ")
        }
    }
    Text(
        text = annotated,
        fontSize = 22.sp,
        textAlign = TextAlign.Center,
        style = TextStyle(
            shadow = Shadow(color = Color.Black, offset = Offset(2f, 2f), blurRadius = 6f),
        ),
        modifier = Modifier
            .graphicsLayer {
                scaleX = anim.scale
                scaleY = anim.scale
                alpha = anim.alpha
                translationY = anim.offsetYPx
            }
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Draws the emoji/sticker overlays active at [positionMs], positioned by their
 * stored x/y fractions of the preview size. Sibling of [CaptionLayerHost] —
 * place both in the same overlay Box in the preview screen.
 */
@Composable
fun EmojiOverlayHost(project: Project, positionMs: Long) {
    val active = project.emojiOverlays
        .filter { it.startMs <= positionMs && positionMs < it.endMs }
    if (active.isEmpty()) return
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        active.forEach { o -> EmojiItem(overlay = o) }
    }
}

@Composable
private fun BoxWithConstraintsScope.EmojiItem(overlay: EmojiOverlay) {
    val size = 56.dp * overlay.scale.coerceIn(0.25f, 4f)
    val x = (maxWidth * overlay.xFraction.coerceIn(0f, 1f) - size / 2).coerceAtLeast(0.dp)
    val y = (maxHeight * overlay.yFraction.coerceIn(0f, 1f) - size / 2).coerceAtLeast(0.dp)
    Text(
        text = overlay.emoji,
        fontSize = (44 * overlay.scale.coerceIn(0.25f, 4f)).sp,
        modifier = Modifier.offset(x = x, y = y),
    )
}
