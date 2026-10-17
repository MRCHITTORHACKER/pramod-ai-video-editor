package com.pramod.videoeditor.featuretemplates

import com.pramod.videoeditor.coremodel.CaptionSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateEngineTest {

    private val minimalJson = """
    {
      "id": "t99_test",
      "name": "Test",
      "category": "viral",
      "description": "d",
      "thumbnailRef": "thumb_t99_test.png",
      "aspectRatios": ["R_9_16"],
      "minDurationSec": 5,
      "maxDurationSec": 30,
      "captionLayout": {"position": "bottom_center", "maxWordsPerCaption": 6, "safeMarginPercent": 8.0, "backgroundStyle": "none", "backgroundOpacity": 0.0},
      "typography": {"fontFamily": "sans_serif_bold", "devanagariSupport": true, "baseSizeSp": 42, "textColor": "#FFFFFF", "accentColor": "#7C4DFF", "strokeColor": "#000000", "strokeWidthDp": 2.0},
      "textAnimations": [{"type": "scale_in", "startScale": 0.88, "endScale": 1.0, "fadeInMs": 200, "easing": "ease_out", "overshoot": true}],
      "transitions": [],
      "vfx": [{"effectId": "vignette", "placement": "throughout", "intensity": 0.4, "params": {}}],
      "emojiRules": {"category": "none", "animation": "pop", "maxPerVideo": 3, "autoSuggest": false},
      "sfxRules": [{"onEvent": "title_entry", "category": "whoosh", "gainDb": -6.0, "enabled": true, "maxOccurrences": 1}],
      "musicBehavior": {"mood": "none", "duckUnderSpeech": true, "duckLevelDb": -10.0, "fadeInMs": 800, "fadeOutMs": 1200},
      "timingRules": {"syncSource": "speech", "requireRealBeatAnalysis": true, "minTextOnScreenMs": 1200, "maxTextOnScreenMs": 4000},
      "effectIntensity": 0.5,
      "previewNotes": "",
      "exportNotes": "",
      "deviceRequirements": "minSdk 26",
      "assetLicense": "test",
      "version": "1.0.0",
      "testCases": ["parses"]
    }
    """.trimIndent()

    @Test
    fun `parser accepts valid template`() {
        val r = TemplateParser.parse(minimalJson)
        assertTrue(r is TemplateParser.ParseResult.Ok)
        assertEquals("t99_test", (r as TemplateParser.ParseResult.Ok).config.id)
    }

    @Test
    fun `parser rejects malformed json`() {
        val r = TemplateParser.parse("{ not json")
        assertTrue(r is TemplateParser.ParseResult.Err)
    }

    @Test
    fun `parser rejects unknown fields strictly`() {
        val r = TemplateParser.parse(minimalJson.replace("\"version\": \"1.0.0\"", "\"version\": \"1.0.0\", \"bogus\": 1"))
        assertTrue(r is TemplateParser.ParseResult.Err)
    }

    @Test
    fun `validator accepts good config`() {
        val ok = (TemplateParser.parse(minimalJson) as TemplateParser.ParseResult.Ok).config
        assertTrue(TemplateValidator.isUsable(ok))
    }

    @Test
    fun `validator flags bad id and version`() {
        val ok = (TemplateParser.parse(minimalJson) as TemplateParser.ParseResult.Ok).config
        val bad = ok.copy(id = "BAD ID!", version = "1.0")
        val issues = TemplateValidator.validate(bad)
        assertTrue(issues.any { it.field == "id" && it.severity == TemplateValidator.Severity.ERROR })
        assertTrue(issues.any { it.field == "version" && it.severity == TemplateValidator.Severity.ERROR })
    }

    @Test
    fun `mapper clamps sfx and captions into project bounds`() {
        val ok = (TemplateParser.parse(minimalJson) as TemplateParser.ParseResult.Ok).config
        val captions = listOf(
            CaptionSegment("c1", "hello world", 0, 5000),
            CaptionSegment("c2", "second line", 5000, 9000),
        )
        val plan = TemplateTimelineMapper.map(ok, 10_000L, captions)
        assertTrue(plan.sfx.all { it.startMs in 0..10_000 })
        assertTrue(plan.captionEvents.all { it.endMs <= 10_000 })
        assertEquals(1, plan.sfx.size) // one title_entry rule, maxOccurrences=1
    }

    @Test
    fun `mapper warns when beat sync requested without real analysis`() {
        val ok = (TemplateParser.parse(minimalJson) as TemplateParser.ParseResult.Ok).config
        val beatTemplate = ok.copy(
            timingRules = ok.timingRules.copy(syncSource = "fixed_beats"),
            sfxRules = ok.sfxRules + com.pramod.videoeditor.coremodel.SfxRule("beat", "pop", -8f, true, 4),
        )
        val plan = TemplateTimelineMapper.map(beatTemplate, 10_000L, emptyList(), beatGridMs = null)
        assertTrue(plan.warnings.any { it.contains("beat", ignoreCase = true) })
    }
}
