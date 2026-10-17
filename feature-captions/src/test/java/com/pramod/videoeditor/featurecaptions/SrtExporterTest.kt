package com.pramod.videoeditor.featurecaptions

import com.pramod.videoeditor.coremodel.CaptionSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class SrtExporterTest {

    @Test
    fun `exports valid srt`() {
        val segments = listOf(
            CaptionSegment("c1", "hello", 0, 1500),
            CaptionSegment("c2", "world", 2000, 3500),
        )
        val srt = SrtExporter.export(segments)
        assertEquals(
            "1\n00:00:00,000 --> 00:00:01,500\nhello\n\n" +
                "2\n00:00:02,000 --> 00:00:03,500\nworld\n\n",
            srt,
        )
    }

    @Test
    fun `includes speaker label when present`() {
        val segments = listOf(CaptionSegment("c1", "hi", 61_000, 62_500, speaker = "A"))
        val srt = SrtExporter.export(segments)
        assertEquals("1\n00:01:01,000 --> 00:01:02,500\n[A] hi\n\n", srt)
    }
}
