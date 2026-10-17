package com.pramod.videoeditor.featurecaptions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionTimingTest {

    @Test
    fun `even split divides duration across words`() {
        val ws = deriveWordTimings("one two three four", 0, 4000)
        assertEquals(4, ws.size)
        assertEquals(0L, ws[0].startMs)
        assertEquals(1000L, ws[0].endMs)
        assertEquals(1000L, ws[1].startMs)
        assertEquals(3000L, ws[3].startMs)
        assertEquals(4000L, ws[3].endMs)
    }

    @Test
    fun `words are contiguous with no gaps`() {
        val ws = deriveWordTimings("a bb ccc dddd eeeee", 500, 2500)
        ws.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
        assertEquals(500L, ws.first().startMs)
        assertEquals(2500L, ws.last().endMs)
    }

    @Test
    fun `empty or blank text yields no words`() {
        assertTrue(deriveWordTimings("", 0, 1000).isEmpty())
        assertTrue(deriveWordTimings("   ", 0, 1000).isEmpty())
    }

    @Test
    fun `zero or negative duration yields no words`() {
        assertTrue(deriveWordTimings("hello", 1000, 1000).isEmpty())
        assertTrue(deriveWordTimings("hello", 2000, 1000).isEmpty())
    }

    @Test
    fun `word text is preserved including devanagari`() {
        val ws = deriveWordTimings("नमस्ते दुनिया", 500, 2500)
        assertEquals(listOf("नमस्ते", "दुनिया"), ws.map { it.word })
        assertEquals(500L, ws[0].startMs)
        assertEquals(2500L, ws[1].endMs)
    }

    @Test
    fun `single word spans the whole segment`() {
        val ws = deriveWordTimings("hello", 100, 900)
        assertEquals(1, ws.size)
        assertEquals(100L, ws[0].startMs)
        assertEquals(900L, ws[0].endMs)
    }
}
