package com.pramod.videoeditor.featureaudio

import com.pramod.videoeditor.coremodel.SfxRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SfxPlacementEngineTest {

    @Test
    fun `places sfx on matching timeline events`() {
        val rules = listOf(SfxRule("title_entry", "whoosh", -6f, true, 2))
        val (events, _) = SfxPlacementEngine.place(rules, mapOf("title_entry" to listOf(150L)), 10_000L)
        assertEquals(1, events.size)
        assertEquals(150L, events[0].startMs)
        assertTrue(events[0].gainDb <= 0f)
    }

    @Test
    fun `skips rule with no matching events and reports message`() {
        val rules = listOf(SfxRule("major_title", "impact", -4f, true, 1))
        val (events, messages) = SfxPlacementEngine.place(rules, emptyMap(), 10_000L)
        assertTrue(events.isEmpty())
        assertTrue(messages.isNotEmpty())
    }

    @Test
    fun `respects maxOccurrences`() {
        val rules = listOf(SfxRule("caption_boundary", "pop", -10f, true, 2))
        val times = mapOf("caption_boundary" to listOf(1000L, 2000L, 3000L, 4000L))
        val (events, _) = SfxPlacementEngine.place(rules, times, 10_000L)
        assertEquals(2, events.size)
    }

    @Test
    fun `mix levels duck music under speech`() {
        val (music, sfx) = SfxPlacementEngine.mixLevels(speechPresent = true)
        assertEquals(-10f, music)
        assertEquals(-6f, sfx)
        val (music2, _) = SfxPlacementEngine.mixLevels(speechPresent = false)
        assertEquals(0f, music2)
    }
}
