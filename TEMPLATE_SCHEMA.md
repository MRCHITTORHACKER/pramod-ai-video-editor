# Template Configuration Schema

Every template is a JSON file in `feature-templates/src/main/assets/templates/`
parsed by `TemplateParser` into `TemplateConfig` (see `core-model/Models.kt`).

## Required fields (all 23)

| # | Field | Type | Notes |
|---|-------|------|-------|
| 1 | `id` | string | `^[a-z0-9_]{3,48}$`, unique across all templates |
| 2 | `name` | string | Display name |
| 3 | `category` | string | viral \| cinematic \| education \| motivation \| vlog \| social \| gaming \| business |
| 4 | `description` | string | One-line purpose |
| 5 | `thumbnailRef` | string | Asset file name of the preview thumbnail |
| 6 | `aspectRatios` | string[] | Subset of `R_9_16`, `R_16_9`, `R_1_1` |
| 7 | `minDurationSec` | int | > 0 |
| 8 | `maxDurationSec` | int | >= minDurationSec |
| 9 | `captionLayout` | object | position, maxWordsPerCaption, safeMarginPercent, backgroundStyle, backgroundOpacity |
| 10 | `typography` | object | fontFamily, devanagariSupport, baseSizeSp, textColor, accentColor, strokeColor?, strokeWidthDp |
| 11 | `textAnimations` | object[] | type ∈ scale_in/fade_in/slide_up/pop/typewriter/none; scales; fadeInMs; easing; overshoot |
| 12 | `transitions` | object[] | type ∈ cut/fade/zoom_blur/glitch/slide/flash; durationMs |
| 13 | `vfx` | object[] | effectId, placement ∈ intro/emphasis/throughout/transitions/outro, intensity 0..1, params |
| 14 | `emojiRules` | object | category, animation, maxPerVideo, autoSuggest |
| 15 | `sfxRules` | object[] | onEvent, category, gainDb (≤ 0 recommended), enabled, maxOccurrences |
| 16 | `musicBehavior` | object | mood, duckUnderSpeech, duckLevelDb, fadeInMs, fadeOutMs |
| 17 | `timingRules` | object | syncSource ∈ speech/fixed_beats/manual_markers/sentence; requireRealBeatAnalysis; min/maxTextOnScreenMs |
| 18 | `effectIntensity` | float | 0..1 global scaler |
| 19 | `previewNotes` | string | How preview must behave |
| 20 | `exportNotes` | string | How export must behave |
| 21 | `deviceRequirements` | string | e.g. minSdk + low-effects-mode rule |
| 22 | `assetLicense` | string | Provenance of bundled assets |
| 23 | `version` | string | semver MAJOR.MINOR.PATCH |
| + | `testCases` | string[] | Human-readable acceptance checks (required by validator as warning if empty) |

## Strictness

`TemplateParser` uses `ignoreUnknownKeys = false`: unknown fields are a
config bug and fail parsing. `TemplateValidator` reports ERRORs (unusable
template) and WARNINGs (usable but suspicious).

## Honesty rules encoded in the engine

- `syncSource: fixed_beats` + `requireRealBeatAnalysis: true` →
  `TemplateTimelineMapper` skips beat-synced events when no real beat grid
  was supplied, and records a warning. Beats are never guessed.
- Speaker labels are only set when diarization data actually exists.
- Missing SFX assets → event skipped with message, never silent failure.
- Unsupported effects → validated fallback or user prompt to disable.
