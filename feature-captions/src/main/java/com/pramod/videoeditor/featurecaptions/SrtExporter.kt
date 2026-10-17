package com.pramod.videoeditor.featurecaptions

import com.pramod.videoeditor.coremodel.CaptionSegment

/**
 * Exports caption segments to SubRip (.srt) format.
 * Pure Kotlin — unit-testable on the JVM.
 */
object SrtExporter {

    fun export(segments: List<CaptionSegment>): String {
        val sb = StringBuilder()
        segments.sortedBy { it.startMs }.forEachIndexed { i, s ->
            sb.append("${i + 1}\n")
            sb.append("${fmt(s.startMs)} --> ${fmt(s.endMs)}\n")
            val label = if (s.speaker != null) "[${s.speaker}] ${s.text}" else s.text
            sb.append(label.trim()).append("\n\n")
        }
        return sb.toString()
    }

    private fun fmt(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val sec = (ms % 60_000) / 1000
        val milli = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, sec, milli)
    }
}
