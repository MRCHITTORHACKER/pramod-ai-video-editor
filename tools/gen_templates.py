#!/usr/bin/env python3
"""Generate the 64 template JSON configs.
Run from project root: python3 tools/gen_templates.py
Every JSON carries all 23 required template fields.
"""
import json, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "feature-templates", "src", "main", "assets", "templates")
os.makedirs(OUT, exist_ok=True)

A916 = ["R_9_16"]; A169 = ["R_16_9"]; A11 = ["R_1_1"]; ALL = ["R_9_16", "R_16_9", "R_1_1"]
DEVREQ = "minSdk 26; low-effects mode auto-enabled on low-RAM devices"
LICENSE = "Original configuration; no third-party assets bundled."


def base(tid, name, category, desc, aspects, mn, mx, **kw):
    t = dict(
        id=tid, name=name, category=category, description=desc,
        thumbnailRef=f"thumb_{tid}.png",
        aspectRatios=aspects, minDurationSec=mn, maxDurationSec=mx,
        captionLayout=dict(position="bottom_center", maxWordsPerCaption=6,
                           safeMarginPercent=8.0, backgroundStyle="none", backgroundOpacity=0.0),
        typography=dict(fontFamily="sans_serif_bold", devanagariSupport=True,
                        baseSizeSp=42, textColor="#FFFFFF", accentColor="#7C4DFF",
                        strokeColor="#000000", strokeWidthDp=2.0),
        textAnimations=[], transitions=[], vfx=[],
        emojiRules=dict(category="none", animation="pop", maxPerVideo=3, autoSuggest=False),
        sfxRules=[], musicBehavior=dict(mood="none", duckUnderSpeech=True,
                                        duckLevelDb=-10.0, fadeInMs=800, fadeOutMs=1200),
        timingRules=dict(syncSource="speech", requireRealBeatAnalysis=True,
                         minTextOnScreenMs=1200, maxTextOnScreenMs=4000),
        effectIntensity=0.5,
        previewNotes="", exportNotes="",
        deviceRequirements=DEVREQ, assetLicense=LICENSE, version="1.0.0",
        testCases=[],
    )
    t.update(kw)
    return t


def anim(atype="scale_in", s0=0.88, s1=1.0, fade=200, easing="ease_out", over=False):
    return dict(type=atype, startScale=s0, endScale=s1, fadeInMs=fade, easing=easing, overshoot=over)


def trans(ttype="cut", dur=300):
    return dict(type=ttype, durationMs=dur)


def vfx(eid, placement="emphasis", intensity=0.5, **params):
    return dict(effectId=eid, placement=placement, intensity=intensity, params=params)


def sfx(on_event, category, gain=-6.0, enabled=True, maxocc=3):
    return dict(onEvent=on_event, category=category, gainDb=gain, enabled=enabled, maxOccurrences=maxocc)


# ---------------------------------------------------------------- T01..T12
T = []

T.append(base("t01_viral_hook_intro", "Viral Hook Intro", "viral",
    "Short videos with a strong opening: big bold title scales in over visible footage.",
    A916, 5, 30,
    captionLayout=dict(position="center", maxWordsPerCaption=5, safeMarginPercent=10.0,
                       backgroundStyle="none", backgroundOpacity=0.0),
    typography=dict(fontFamily="sans_serif_black", devanagariSupport=True, baseSizeSp=56,
                    textColor="#FFFFFF", accentColor="#FFD54F", strokeColor="#000000", strokeWidthDp=3.0),
    textAnimations=[anim("scale_in", 0.88, 1.0, 200, "ease_out", True)],
    transitions=[trans("cut", 0)],
    vfx=[vfx("dim_background", "intro", 0.35, opacity=0.35)],
    sfxRules=[sfx("title_entry", "whoosh", -6.0), sfx("major_title", "impact", -4.0, maxocc=1)],
    timingRules=dict(syncSource="speech", requireRealBeatAnalysis=True,
                     minTextOnScreenMs=1200, maxTextOnScreenMs=2500),
    effectIntensity=0.6,
    previewNotes="Title 0-1.2s over real video frames; keep faces uncovered.",
    exportNotes="Respect selected aspect ratio; preserve source audio unless disabled.",
    testCases=["Title enters 0-1.2s with scale 0.88->1.0 ease-out",
               "Exactly one whoosh + max one impact placed",
               "Faces/important content not covered by title safe-area check"]))

T.append(base("t02_beat_sync_captions", "Beat Sync Captions", "viral",
    "Energetic talking videos: word-level captions with pop animation, beat-synced only on real analysis.",
    A916, 5, 60,
    typography=dict(fontFamily="sans_serif_bold", devanagariSupport=True, baseSizeSp=44,
                    textColor="#FFFFFF", accentColor="#00E5FF", strokeColor="#000000", strokeWidthDp=2.0),
    textAnimations=[anim("pop", 0.9, 1.0, 120, "ease_out", False)],
    transitions=[trans("cut", 0)],
    sfxRules=[sfx("word_emphasis", "pop", -12.0, maxocc=8)],
    musicBehavior=dict(mood="energetic", duckUnderSpeech=True, duckLevelDb=-10.0, fadeInMs=600, fadeOutMs=1000),
    timingRules=dict(syncSource="fixed_beats", requireRealBeatAnalysis=True,
                     minTextOnScreenMs=800, maxTextOnScreenMs=3000),
    effectIntensity=0.55,
    previewNotes="If no beat analysis exists, engine falls back to manual markers / sentence timing.",
    exportNotes="Word timestamps must come from real transcription; never invent timings.",
    testCases=["Word timestamps drive caption changes",
               "No beat grid -> falls back to manual markers with warning",
               "Captions stay inside safe margins"]))

T.append(base("t03_bold_word_highlight", "Bold Word Highlight", "education",
    "Educational explainers: active word highlighted in accent color, Hindi matra-safe wrapping.",
    ALL, 10, 180,
    captionLayout=dict(position="bottom_center", maxWordsPerCaption=8, safeMarginPercent=8.0,
                       backgroundStyle="pill", backgroundOpacity=0.55),
    typography=dict(fontFamily="sans_serif", devanagariSupport=True, baseSizeSp=40,
                    textColor="#F5F5F5", accentColor="#FFD54F", strokeColor="#000000", strokeWidthDp=1.5),
    textAnimations=[anim("fade_in", 1.0, 1.0, 150, "ease_out", False)],
    sfxRules=[sfx("word_emphasis", "pop", -14.0, enabled=False, maxocc=4)],
    timingRules=dict(syncSource="speech", requireRealBeatAnalysis=True,
                     minTextOnScreenMs=1500, maxTextOnScreenMs=5000),
    effectIntensity=0.3,
    previewNotes="Inactive words stay readable; Devanagari conjuncts never split across lines.",
    exportNotes="SFX disabled by default; optional quiet pops only.",
    testCases=["Active word uses accent color, rest readable",
               "Hindi matras/conjuncts wrap correctly",
               "Max words-per-caption respected"]))

T.append(base("t04_cinematic_film_look", "Cinematic Film Look", "cinematic",
    "Travel/lifestyle storytelling: warm/cool grade, vignette, grain, optional letterbox.",
    ALL, 10, 300,
    captionLayout=dict(position="bottom_center", maxWordsPerCaption=10, safeMarginPercent=10.0,
                       backgroundStyle="none", backgroundOpacity=0.0),
    typography=dict(fontFamily="serif", devanagariSupport=True, baseSizeSp=36,
                    textColor="#FAF3E0", accentColor="#D4A017", strokeColor=None, strokeWidthDp=0.0),
    textAnimations=[anim("fade_in", 1.0, 1.0, 600, "ease_in_out", False)],
    transitions=[trans("fade", 600)],
    vfx=[vfx("color_grade", "throughout", 0.5, warmth=0.6, contrast=1.08),
         vfx("vignette", "throughout", 0.4, strength=0.45),
         vfx("film_grain", "throughout", 0.25, amount=0.25),
         vfx("letterbox", "throughout", 0.5, barPercent=12.0)],
    musicBehavior=dict(mood="ambient", duckUnderSpeech=True, duckLevelDb=-8.0, fadeInMs=1200, fadeOutMs=1500),
    sfxRules=[sfx("transition", "soft_transition", -10.0, maxocc=6)],
    effectIntensity=0.5,
    previewNotes="Grade is non-destructive; Reset restores original colors.",
    exportNotes="Never crop important content without user confirmation.",
    testCases=["Reset restores original colors exactly",
               "Letterbox does not crop faces (safe-area check)",
               "Grain amount stays subtle"]))

T.append(base("t05_smooth_zoom_story", "Smooth Zoom Story", "viral",
    "Emphasize moments with smooth interpolated zoom; no sudden jumps.",
    A916, 5, 60,
    textAnimations=[anim("fade_in", 1.0, 1.0, 250, "ease_out", False)],
    transitions=[trans("zoom_blur", 350)],
    vfx=[vfx("smooth_zoom", "emphasis", 0.6, zoomFrom=1.0, zoomTo=1.25)],
    sfxRules=[sfx("zoom_start", "whoosh", -10.0, maxocc=4)],
    effectIntensity=0.6,
    previewNotes="Zoom keeps subject inside frame; configurable 1.0x-1.5x range.",
    exportNotes="Interpolate zoom smoothly across the effect duration.",
    testCases=["Zoom interpolates without jumps",
               "Subject stays in frame (center-weighted)",
               "No impact SFX on every zoom"]))

T.append(base("t06_education_explainer", "Education Explainer", "education",
    "Lessons & exam prep: clean high-contrast captions, key-term highlights, fact cards.",
    ALL, 15, 300,
    captionLayout=dict(position="bottom_center", maxWordsPerCaption=10, safeMarginPercent=8.0,
                       backgroundStyle="box", backgroundOpacity=0.7),
    typography=dict(fontFamily="sans_serif", devanagariSupport=True, baseSizeSp=40,
                    textColor="#FFFFFF", accentColor="#80DEEA", strokeColor="#000000", strokeWidthDp=1.5),
    textAnimations=[anim("fade_in", 1.0, 1.0, 200, "ease_out", False)],
    transitions=[trans("fade", 250)],
    sfxRules=[sfx("transition", "educational_cue", -14.0, maxocc=6)],
    timingRules=dict(syncSource="sentence", requireRealBeatAnalysis=True,
                     minTextOnScreenMs=2000, maxTextOnScreenMs=6000),
    effectIntensity=0.25,
    previewNotes="Numbered fact cards + optional Q/A layout; Hindi + English.",
    exportNotes="Text stays long enough to read; minimal VFX.",
    testCases=["Key terms highlighted, definitions readable",
               "Hindi + English render correctly",
               "No loud effects by default"]))

T.append(base("t07_motivation_impact", "Motivation Impact", "motivation",
    "Motivational speeches: large bold key phrases rising up, restrained cinematic audio.",
    A916, 10, 120,
    captionLayout=dict(position="center", maxWordsPerCaption=6, safeMarginPercent=10.0,
                       backgroundStyle="none", backgroundOpacity=0.0),
    typography=dict(fontFamily="sans_serif_black", devanagariSupport=True, baseSizeSp=54,
                    textColor="#FFFFFF", accentColor="#FFB300", strokeColor="#000000", strokeWidthDp=3.0),
    textAnimations=[anim("slide_up", 1.0, 1.0, 300, "ease_out", False)],
    vfx=[vfx("color_grade", "throughout", 0.45, warmth=0.7, contrast=1.1)],
    sfxRules=[sfx("major_phrase", "riser", -8.0, maxocc=3),
              sfx("key_moment", "cinematic_hit", -6.0, maxocc=2)],
    musicBehavior=dict(mood="emotional", duckUnderSpeech=True, duckLevelDb=-12.0, fadeInMs=1000, fadeOutMs=1200),
    timingRules=dict(syncSource="speech", requireRealBeatAnalysis=True,
                     minTextOnScreenMs=1500, maxTextOnScreenMs=4000),
    effectIntensity=0.65,
    previewNotes="Phrases synced to real speech timestamps; never rewrite speaker meaning.",
    exportNotes="Speech must remain intelligible; risers never cover voice.",
    testCases=["Phrase entry synced to speech timestamps",
               "No arbitrary text altering meaning",
               "Speech intelligible over music (ducking verified)"]))

T.append(base("t08_minimal_aesthetic", "Minimal Aesthetic", "social",
    "Lifestyle/quotes: minimal text, gentle fades, soft colors, generous margins.",
    A916, 8, 90,
    captionLayout=dict(position="center", maxWordsPerCaption=8, safeMarginPercent=14.0,
                       backgroundStyle="none", backgroundOpacity=0.0),
    typography=dict(fontFamily="serif", devanagariSupport=True, baseSizeSp=38,
                    textColor="#F8F6F0", accentColor="#B39DDB", strokeColor=None, strokeWidthDp=0.0),
    textAnimations=[anim("fade_in", 1.0, 1.0, 800, "ease_in_out", False)],
    transitions=[trans("fade", 800)],
    vfx=[vfx("film_grain", "throughout", 0.15, amount=0.15)],
    musicBehavior=dict(mood="ambient", duckUnderSpeech=True, duckLevelDb=-8.0, fadeInMs=1500, fadeOutMs=1500),
    effectIntensity=0.2,
    previewNotes="Low clutter; no mandatory SFX.",
    exportNotes="Restrained music only.",
    testCases=["Text count per screen <= 12 words",
               "No SFX placed by default",
               "Safe margins >= 12%"]))

T.append(base("t09_podcast_captions", "Podcast Captions", "education",
    "Interviews & podcasts: speech-synced blocks, speaker labels only when diarization is real.",
    ALL, 30, 600,
    captionLayout=dict(position="bottom_center", maxWordsPerCaption=12, safeMarginPercent=8.0,
                       backgroundStyle="pill", backgroundOpacity=0.6),
    typography=dict(fontFamily="sans_serif", devanagariSupport=True, baseSizeSp=38,
                    textColor="#FFFFFF", accentColor="#69F0AE", strokeColor="#000000", strokeWidthDp=1.5),
    textAnimations=[anim("fade_in", 1.0, 1.0, 150, "ease_out", False)],
    timingRules=dict(syncSource="speech", requireRealBeatAnalysis=True,
                     minTextOnScreenMs=1500, maxTextOnScreenMs=6000),
    effectIntensity=0.2,
    previewNotes="Two-speaker colors ONLY when speaker info actually exists; never invent identities.",
    exportNotes="SRT export supported from caption editor.",
    testCases=["No invented speaker labels without diarization data",
               "Readable on portrait video",
               "SRT export round-trips timestamps"]))

T.append(base("t10_glitch_digital", "Glitch Digital", "gaming",
    "Gaming/tech: short controlled RGB split + digital distortion at selected cuts.",
    A916, 5, 60,
    typography=dict(fontFamily="monospace_bold", devanagariSupport=False, baseSizeSp=44,
                    textColor="#00FFCC", accentColor="#FF2D78", strokeColor="#000000", strokeWidthDp=2.0),
    textAnimations=[anim("pop", 0.92, 1.0, 100, "ease_out", False)],
    transitions=[trans("glitch", 220)],
    vfx=[vfx("rgb_split", "transitions", 0.7, offsetPx=6.0),
         vfx("digital_glitch", "transitions", 0.6, blocks=8.0)],
    sfxRules=[sfx("transition", "digital_glitch", -8.0, maxocc=5)],
    musicBehavior=dict(mood="energetic", duckUnderSpeech=True, duckLevelDb=-10.0, fadeInMs=500, fadeOutMs=800),
    effectIntensity=0.7,
    previewNotes="Footage stays recognizable; intensity auto-reduced on low-end devices.",
    exportNotes="Short glitch bursts only; avoid repeated loud distortion.",
    testCases=["Main footage recognizable during glitch",
               "Low-RAM device auto-reduces intensity",
               "Glitch SFX short (<400ms each)"]))

T.append(base("t11_travel_diary", "Travel Diary", "vlog",
    "Travel/scenic: smooth cross-clip transitions, warm/cool presets, user location labels.",
    ALL, 15, 300,
    captionLayout=dict(position="bottom_center", maxWordsPerCaption=8, safeMarginPercent=10.0,
                       backgroundStyle="none", backgroundOpacity=0.0),
    typography=dict(fontFamily="serif", devanagariSupport=True, baseSizeSp=40,
                    textColor="#FFF8E1", accentColor="#FFCC80", strokeColor="#000000", strokeWidthDp=1.5),
    textAnimations=[anim("fade_in", 1.0, 1.0, 500, "ease_in_out", False)],
    transitions=[trans("fade", 500)],
    vfx=[vfx("color_grade", "throughout", 0.4, warmth=0.65, contrast=1.05)],
    musicBehavior=dict(mood="ambient", duckUnderSpeech=False, duckLevelDb=-6.0, fadeInMs=1200, fadeOutMs=1500),
    sfxRules=[sfx("transition", "soft_transition", -12.0, maxocc=8),
              sfx("scene", "ambient_atmosphere", -16.0, maxocc=3)],
    effectIntensity=0.4,
    previewNotes="Location labels are user-supplied; never fabricate places/dates.",
    exportNotes="Music may span multiple clips.",
    testCases=["No fabricated locations/dates",
               "Transitions smooth between clips",
               "Music spans clips without gaps"]))

T.append(base("t12_app_promotion", "App Promotion", "business",
    "Promo for apps/products: user logo + name, feature titles, screenshot overlays, clean zooms.",
    ALL, 15, 120,
    captionLayout=dict(position="bottom_center", maxWordsPerCaption=8, safeMarginPercent=10.0,
                       backgroundStyle="pill", backgroundOpacity=0.5),
    typography=dict(fontFamily="sans_serif_bold", devanagariSupport=True, baseSizeSp=42,
                    textColor="#FFFFFF", accentColor="#40C4FF", strokeColor="#000000", strokeWidthDp=2.0),
    textAnimations=[anim("slide_up", 1.0, 1.0, 300, "ease_out", False)],
    transitions=[trans("slide", 350)],
    vfx=[vfx("smooth_zoom", "emphasis", 0.5, zoomFrom=1.0, zoomTo=1.15)],
    sfxRules=[sfx("transition", "whoosh", -10.0, maxocc=6),
              sfx("feature_reveal", "notification", -8.0, maxocc=4)],
    musicBehavior=dict(mood="energetic", duckUnderSpeech=True, duckLevelDb=-12.0, fadeInMs=800, fadeOutMs=1000),
    effectIntensity=0.5,
    previewNotes="All claims/pricing are user-provided; never fabricate reviews or download counts.",
    exportNotes="Speech ducking on background music during voiceover.",
    testCases=["No fabricated reviews/downloads shown",
               "Logo + app name editable",
               "Music ducks under voiceover"]))

# ------------------------------------------------- T13..T64 generated
# (category, name, desc, aspects, min, max, distinct params)
GEN = [
 # Viral Shorts
 ("t13_trending_reel", "Trending Reel", "viral", "Punchy short with fast hook text and trending-style cuts.", A916, 5, 30,
  dict(anim="pop", accent="#FF4081", trans=("cut", 0), sfx=[("title_entry", "whoosh")], intensity=0.65, mood="energetic")),
 ("t14_fast_zoom_cuts", "Fast Zoom Cuts", "viral", "Rapid zoom cuts synced to emphasis points.", A916, 5, 30,
  dict(anim="scale_in", accent="#00E5FF", trans=("zoom_blur", 250), sfx=[("transition", "whoosh")], intensity=0.7, mood="energetic",
       vfx_extra=[("smooth_zoom", "emphasis", 0.7)])),
 ("t15_impact_text_reveal", "Impact Text Reveal", "viral", "Single powerful line revealed with impact.", A916, 5, 20,
  dict(anim="scale_in", accent="#FFD54F", size=60, trans=("flash", 150), sfx=[("major_title", "impact"), ("title_entry", "whoosh")], intensity=0.7)),
 ("t16_minimal_shorts", "Minimal Shorts", "viral", "Clean shorts, one idea per screen.", A916, 8, 45,
  dict(anim="fade_in", accent="#B39DDB", trans=("fade", 400), sfx=[], intensity=0.25, mood="ambient")),
 ("t17_quick_facts", "Quick Facts", "viral", "Fast fact cards with numbered reveals.", A916, 8, 45,
  dict(anim="slide_up", accent="#69F0AE", trans=("slide", 300), sfx=[("transition", "pop")], intensity=0.5, words=5)),
 ("t18_dynamic_title", "Dynamic Title", "viral", "Animated channel/video title opener.", ALL, 5, 20,
  dict(anim="scale_in", accent="#40C4FF", size=58, trans=("zoom_blur", 300), sfx=[("title_entry", "whoosh")], intensity=0.6)),
 ("t19_speed_ramp", "Speed Ramp", "viral", "Speed-ramped action beats with punchy captions.", A916, 5, 30,
  dict(anim="pop", accent="#FF6E40", trans=("cut", 0), sfx=[("transition", "whoosh")], intensity=0.65, mood="energetic")),
 ("t20_creator_intro", "Creator Intro", "viral", "Personal creator intro sting with name reveal.", ALL, 5, 15,
  dict(anim="scale_in", accent="#7C4DFF", size=52, trans=("fade", 400), sfx=[("title_entry", "whoosh"), ("major_title", "sparkle")], intensity=0.5)),
 # Cinematic
 ("t21_golden_hour", "Golden Hour", "cinematic", "Warm golden grade for sunset footage.", ALL, 10, 180,
  dict(anim="fade_in", accent="#FFB300", trans=("fade", 700), intensity=0.5, mood="ambient",
       vfx_extra=[("color_grade", "throughout", 0.6), ("light_leak", "throughout", 0.35)])),
 ("t22_moody_rain", "Moody Rain", "cinematic", "Cool desaturated grade with soft vignette.", ALL, 10, 180,
  dict(anim="fade_in", accent="#90CAF9", trans=("fade", 700), intensity=0.5, mood="ambient",
       vfx_extra=[("color_grade", "throughout", 0.55), ("vignette", "throughout", 0.5)])),
 ("t23_dreamy_night", "Dreamy Night", "cinematic", "Soft night look with gentle glow text.", A916, 10, 120,
  dict(anim="fade_in", accent="#CE93D8", trans=("fade", 800), intensity=0.45, mood="ambient",
       vfx_extra=[("glow_text", "emphasis", 0.5), ("vignette", "throughout", 0.4)])),
 ("t24_black_white_cinema", "Black and White Cinema", "cinematic", "Classic monochrome with film grain.", ALL, 10, 180,
  dict(anim="fade_in", accent="#FFFFFF", trans=("fade", 600), intensity=0.5,
       vfx_extra=[("color_grade", "throughout", 0.8), ("film_grain", "throughout", 0.4)])),
 ("t25_travel_film", "Travel Film", "cinematic", "Cinematic travel grade with letterbox.", A169, 15, 300,
  dict(anim="fade_in", accent="#D4A017", trans=("fade", 600), intensity=0.5, mood="ambient",
       vfx_extra=[("letterbox", "throughout", 0.6), ("color_grade", "throughout", 0.5)])),
 ("t26_dramatic_trailer", "Dramatic Trailer", "cinematic", "Trailer-style titles with risers and hits.", A169, 15, 90,
  dict(anim="scale_in", accent="#FF5252", size=56, trans=("flash", 180), sfx=[("major_phrase", "riser"), ("key_moment", "cinematic_hit")], intensity=0.75)),
 ("t27_film_grain", "Film Grain", "cinematic", "Subtle grain overlay for texture.", ALL, 10, 180,
  dict(anim="fade_in", accent="#F5F5F5", trans=("fade", 500), intensity=0.3,
       vfx_extra=[("film_grain", "throughout", 0.5)])),
 ("t28_soft_light_leaks", "Soft Light Leaks", "cinematic", "Gentle light leaks for dreamy feel.", ALL, 10, 120,
  dict(anim="fade_in", accent="#FFE0B2", trans=("fade", 700), intensity=0.4, mood="ambient",
       vfx_extra=[("light_leak", "throughout", 0.5)])),
 # Education
 ("t29_study_notes", "Study Notes", "education", "Note-style cards for revision.", ALL, 15, 300,
  dict(anim="fade_in", accent="#80DEEA", trans=("fade", 250), sfx=[("transition", "educational_cue")], intensity=0.25, words=10)),
 ("t30_important_facts", "Important Facts", "education", "Highlighted key facts sequence.", ALL, 15, 180,
  dict(anim="slide_up", accent="#FFD54F", trans=("slide", 300), sfx=[("transition", "educational_cue")], intensity=0.35, words=8)),
 ("t31_exam_preparation", "Exam Preparation", "education", "Exam-focused explainers with Q/A.", ALL, 20, 300,
  dict(anim="fade_in", accent="#A5D6A7", trans=("fade", 250), sfx=[("transition", "educational_cue")], intensity=0.3, words=10)),
 ("t32_question_answer", "Question and Answer", "education", "Q then A reveal pattern.", ALL, 15, 180,
  dict(anim="pop", accent="#FFAB91", trans=("cut", 0), sfx=[("transition", "pop")], intensity=0.4, words=8)),
 ("t33_concept_explainer", "Concept Explainer", "education", "Step-by-step concept breakdown.", ALL, 20, 300,
  dict(anim="fade_in", accent="#9FA8DA", trans=("fade", 300), sfx=[("transition", "educational_cue")], intensity=0.3, words=10)),
 ("t34_chapter_summary", "Chapter Summary", "education", "End-of-chapter recap cards.", ALL, 20, 240,
  dict(anim="slide_up", accent="#80CBC4", trans=("slide", 300), sfx=[("transition", "soft_transition")], intensity=0.3, words=10)),
 ("t35_countdown_quiz", "Countdown Quiz", "education", "Quiz with countdown timer cards.", A916, 10, 90,
  dict(anim="pop", accent="#FFD54F", trans=("cut", 0), sfx=[("transition", "notification"), ("key_moment", "victory_accent")], intensity=0.5, words=6)),
 ("t36_top_10_facts", "Top 10 Facts", "education", "Ranked countdown of facts.", A916, 15, 120,
  dict(anim="slide_up", accent="#CE93D8", trans=("slide", 300), sfx=[("transition", "pop")], intensity=0.5, words=8)),
 # Motivation
 ("t37_never_give_up", "Never Give Up", "motivation", "Grit-themed speech edit.", A916, 10, 120,
  dict(anim="slide_up", accent="#FFB300", size=54, trans=("fade", 300), sfx=[("major_phrase", "riser")], intensity=0.65, mood="emotional")),
 ("t38_success_mindset", "Success Mindset", "motivation", "Mindset quotes with rising text.", A916, 10, 90,
  dict(anim="slide_up", accent="#FFD54F", trans=("fade", 300), sfx=[("key_moment", "cinematic_hit")], intensity=0.6, mood="emotional")),
 ("t39_dream_big", "Dream Big", "motivation", "Aspirational visuals, airy text.", A916, 10, 90,
  dict(anim="fade_in", accent="#80DEEA", trans=("fade", 600), sfx=[], intensity=0.4, mood="ambient")),
 ("t40_powerful_speech", "Powerful Speech", "motivation", "Speech highlights with impact.", A916, 15, 180,
  dict(anim="scale_in", accent="#FF8A65", size=52, trans=("cut", 0), sfx=[("major_phrase", "riser"), ("key_moment", "impact")], intensity=0.7)),
 ("t41_inspirational_journey", "Inspirational Journey", "motivation", "Journey arc with warm grade.", ALL, 20, 240,
  dict(anim="fade_in", accent="#FFCC80", trans=("fade", 500), intensity=0.5, mood="emotional",
       vfx_extra=[("color_grade", "throughout", 0.5)])),
 ("t42_high_energy_motivation", "High Energy Motivation", "motivation", "Fast motivational cut with beats.", A916, 10, 60,
  dict(anim="pop", accent="#FFD54F", trans=("cut", 0), sfx=[("transition", "whoosh")], intensity=0.7, mood="energetic")),
 ("t43_achievement_reveal", "Achievement Reveal", "motivation", "Trophy-moment reveal with sparkle.", A916, 8, 60,
  dict(anim="scale_in", accent="#FFD54F", size=56, trans=("flash", 200), sfx=[("key_moment", "victory_accent"), ("major_title", "sparkle")], intensity=0.65)),
 ("t44_minimal_quote", "Minimal Quote", "motivation", "Single quote, quiet background.", A916, 8, 60,
  dict(anim="fade_in", accent="#F5F5F5", trans=("fade", 800), sfx=[], intensity=0.2, mood="ambient", words=12)),
 # Vlog & Travel
 ("t45_travel_diary_2", "Wanderlog", "vlog", "Alternate travel diary with cool grade.", ALL, 15, 300,
  dict(anim="fade_in", accent="#90CAF9", trans=("fade", 500), intensity=0.4, mood="ambient",
       vfx_extra=[("color_grade", "throughout", 0.4)])),
 ("t46_road_trip", "Road Trip", "vlog", "Road-trip montage with motion feel.", A169, 15, 240,
  dict(anim="slide_up", accent="#FFCC80", trans=("slide", 400), sfx=[("transition", "whoosh")], intensity=0.55, mood="energetic")),
 ("t47_beach_memories", "Beach Memories", "vlog", "Bright beach grade, airy captions.", ALL, 15, 180,
  dict(anim="fade_in", accent="#80DEEA", trans=("fade", 500), intensity=0.4, mood="ambient",
       vfx_extra=[("color_grade", "throughout", 0.45), ("light_leak", "throughout", 0.3)])),
 ("t48_mountain_adventure", "Mountain Adventure", "vlog", "Epic mountain grade, bold titles.", A169, 15, 240,
  dict(anim="scale_in", accent="#FFFFFF", size=52, trans=("fade", 400), sfx=[("major_title", "cinematic_hit")], intensity=0.6)),
 ("t49_daily_lifestyle", "Daily Lifestyle", "vlog", "Casual daily vlog captions.", A916, 15, 300,
  dict(anim="fade_in", accent="#F5F5F5", trans=("fade", 300), sfx=[], intensity=0.3, words=10)),
 ("t50_photo_memories", "Photo Memories", "vlog", "Photo slideshow with gentle moves.", A11, 15, 180,
  dict(anim="fade_in", accent="#FFE0B2", trans=("fade", 700), intensity=0.35, mood="emotional",
       vfx_extra=[("smooth_zoom", "throughout", 0.4)])),
 ("t51_cinematic_vlog", "Cinematic Vlog", "vlog", "Vlog with filmic grade + letterbox.", A169, 20, 300,
  dict(anim="fade_in", accent="#D4A017", trans=("fade", 600), intensity=0.5, mood="ambient",
       vfx_extra=[("letterbox", "throughout", 0.5), ("film_grain", "throughout", 0.25)])),
 ("t52_city_nights", "City Nights", "vlog", "Neon night city look.", A916, 10, 120,
  dict(anim="pop", accent="#00E5FF", trans=("cut", 0), intensity=0.55, mood="energetic",
       vfx_extra=[("color_grade", "throughout", 0.55), ("glow_text", "emphasis", 0.5)])),
 # Social & Emotional
 ("t53_love_story", "Love Story", "social", "Tender romantic edit.", A916, 15, 180,
  dict(anim="fade_in", accent="#F48FB1", trans=("fade", 700), sfx=[], intensity=0.35, mood="emotional",
       emoji=dict(category="love", animation="float", maxPerVideo=3, autoSuggest=True))),
 ("t54_emotional_memories", "Emotional Memories", "social", "Nostalgic memory montage.", ALL, 20, 240,
  dict(anim="fade_in", accent="#CE93D8", trans=("fade", 700), intensity=0.4, mood="emotional",
       vfx_extra=[("film_grain", "throughout", 0.3), ("vignette", "throughout", 0.35)])),
 ("t55_friendship", "Friendship", "social", "Fun friendship moments.", A916, 10, 120,
  dict(anim="pop", accent="#FFD54F", trans=("cut", 0), sfx=[("transition", "pop")], intensity=0.5, mood="energetic",
       emoji=dict(category="funny", animation="bounce", maxPerVideo=4, autoSuggest=True))),
 ("t56_family_moments", "Family Moments", "social", "Warm family compilation.", ALL, 15, 240,
  dict(anim="fade_in", accent="#FFCC80", trans=("fade", 500), intensity=0.35, mood="emotional",
       emoji=dict(category="celebration", animation="pop", maxPerVideo=3, autoSuggest=True))),
 ("t57_birthday_celebration", "Birthday Celebration", "social", "Birthday edit with confetti pops.", A916, 10, 90,
  dict(anim="pop", accent="#FF8A65", trans=("flash", 200), sfx=[("key_moment", "victory_accent"), ("transition", "sparkle")], intensity=0.6, mood="energetic",
       emoji=dict(category="celebration", animation="pop", maxPerVideo=5, autoSuggest=True))),
 ("t58_special_occasion", "Special Occasion", "social", "Elegant occasion titles.", ALL, 10, 120,
  dict(anim="fade_in", accent="#D4A017", trans=("fade", 600), sfx=[("major_title", "sparkle")], intensity=0.4, mood="emotional")),
 ("t59_heartfelt_message", "Heartfelt Message", "social", "Direct-to-camera message, minimal.", A916, 10, 120,
  dict(anim="fade_in", accent="#F8F6F0", trans=("fade", 500), sfx=[], intensity=0.25, words=10)),
 ("t60_soft_aesthetic", "Soft Aesthetic", "social", "Soft pastel aesthetic edit.", A916, 8, 90,
  dict(anim="fade_in", accent="#CE93D8", trans=("fade", 700), sfx=[], intensity=0.25, mood="ambient",
       vfx_extra=[("film_grain", "throughout", 0.2)])),
 # Gaming & Business
 ("t61_gaming_highlights", "Gaming Highlights", "gaming", "Kill-cam style highlight reel.", A169, 10, 120,
  dict(anim="pop", accent="#00FFCC", trans=("glitch", 200), sfx=[("transition", "digital_glitch"), ("key_moment", "gaming_accent")], intensity=0.7, mood="energetic",
       vfx_extra=[("rgb_split", "transitions", 0.6)])),
 ("t62_action_impact", "Action Impact", "gaming", "Hard-hitting action cuts.", A916, 8, 60,
  dict(anim="scale_in", accent="#FF5252", size=54, trans=("flash", 150), sfx=[("key_moment", "impact"), ("transition", "whoosh")], intensity=0.75)),
 ("t63_business_promo", "Business Promo", "business", "Clean business promo with feature cards.", ALL, 15, 120,
  dict(anim="slide_up", accent="#40C4FF", trans=("slide", 350), sfx=[("transition", "whoosh"), ("feature_reveal", "notification")], intensity=0.5,
       vfx_extra=[("smooth_zoom", "emphasis", 0.4)])),
 ("t64_podcast_captions_2", "Studio Talk", "business", "Alternate podcast look, cool palette.", ALL, 30, 600,
  dict(anim="fade_in", accent="#80DEEA", trans=("fade", 200), sfx=[], intensity=0.2, words=12)),
]

for (tid, name, cat, desc, aspects, mn, mx, p) in GEN:
    ttype, tdur = p.get("trans", ("cut", 0))
    t = base(tid, name, cat, desc, aspects, mn, mx)
    t["typography"]["accentColor"] = p.get("accent", "#7C4DFF")
    if "size" in p:
        t["typography"]["baseSizeSp"] = p["size"]
    t["captionLayout"]["maxWordsPerCaption"] = p.get("words", 6)
    t["textAnimations"] = [anim(p.get("anim", "scale_in"))]
    t["transitions"] = [trans(ttype, tdur)]
    t["vfx"] = [vfx(eid, plc, inten) for (eid, plc, inten) in p.get("vfx_extra", [])]
    t["sfxRules"] = [sfx(ev, cat2, -8.0) for (ev, cat2) in p.get("sfx", [])]
    if p.get("mood", "none") != "none":
        t["musicBehavior"]["mood"] = p["mood"]
    if "emoji" in p:
        t["emojiRules"] = p["emoji"]
    t["effectIntensity"] = p.get("intensity", 0.5)
    t["previewNotes"] = f"Generated {cat} template; distinct timing/typography/transition profile."
    t["exportNotes"] = "Rendered with the same timing and parameters as preview."
    t["testCases"] = [f"Config parses and validates with zero errors",
                      f"Distinct from sibling templates in {cat}"]
    T.append(t)

# t11 already exists as travel_diary; t45 named Wanderlog to avoid duplicate concept.
# t09 podcast_captions exists; t64 is Studio Talk (distinct look).

for t in T:
    path = os.path.join(OUT, f"{t['id']}.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(t, f, ensure_ascii=False, indent=2)

print(f"wrote {len(T)} templates to {OUT}")
ids = [t["id"] for t in T]
assert len(ids) == len(set(ids)) == 64, f"expected 64 unique ids, got {len(ids)}/{len(set(ids))}"
print("64 unique ids OK")
