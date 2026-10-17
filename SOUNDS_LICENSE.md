# Sound asset licensing

All sound-effect assets bundled with this app are **original works created
programmatically for this project** — synthesized from scratch with a Python
script (sine/noise primitives, ADSR envelopes, digital filters). **No
third-party samples, recordings, or library sounds are used anywhere.**

## License: CC0 (public domain)

The author(s) dedicate every sound file listed below to the public domain
under **Creative Commons CC0 1.0 Universal**. You may copy, modify, and use
them for any purpose — including commercial use — with no attribution
required and no restrictions. Safe for the app, its exports, and any
re-release.

## Provenance

- Generator: `tools/gen_sfx.py` in this repo (deterministic, seeded RNG —
  the exact same bytes regenerate on every run).
- The `.ogg` files are NOT stored in git (binary). They are regenerated
  automatically by CI (`.github/workflows/build-apk.yml`) before every APK
  build, and locally by running `python3 tools/gen_sfx.py` (needs
  Python 3 + `numpy` + `scipy` + `ffmpeg`).
- Created: 2026-10-17.
- Format: 44.1 kHz mono, Ogg Vorbis, peak-normalized to −3 dBFS
  (digital_glitch −4.5 dBFS for extra encode headroom), DC-free,
  click-free fade in/out. Each file < 500 KB (actual sizes below).

## Asset table

| category            | file                              | duration | size (bytes) | source   | license |
|---------------------|-----------------------------------|----------|--------------|----------|---------|
| whoosh              | `sfx/whoosh/1.ogg`                | 0.90 s   | 13170        | original | CC0     |
| pop                 | `sfx/pop/1.ogg`                   | 0.35 s   | 5909         | original | CC0     |
| impact              | `sfx/impact/1.ogg`                | 1.00 s   | 7786         | original | CC0     |
| riser               | `sfx/riser/1.ogg`                 | 1.60 s   | 19931        | original | CC0     |
| digital_glitch      | `sfx/digital_glitch/1.ogg`        | 0.70 s   | 13262        | original | CC0     |
| sparkle             | `sfx/sparkle/1.ogg`               | 1.10 s   | 9800         | original | CC0     |
| notification        | `sfx/notification/1.ogg`          | 0.62 s   | 6059         | original | CC0     |
| cinematic_hit       | `sfx/cinematic_hit/1.ogg`         | 1.50 s   | 20463        | original | CC0     |
| ambient_atmosphere  | `sfx/ambient_atmosphere/1.ogg`    | 3.50 s   | 15641        | original | CC0     |
| victory_accent      | `sfx/victory_accent/1.ogg`        | 1.20 s   | 9095         | original | CC0     |
| soft_transition     | `sfx/soft_transition/1.ogg`       | 0.80 s   | 8457         | original | CC0     |
| educational_cue     | `sfx/educational_cue/1.ogg`       | 0.50 s   | 6043         | original | CC0     |
| gaming_accent       | `sfx/gaming_accent/1.ogg`         | 0.70 s   | 6018         | original | CC0     |
| emotional_accent    | `sfx/emotional_accent/1.ogg`      | 1.80 s   | 10643        | original | CC0     |

Total: 14 files, ~152 KB.

## Resolution rules

- Asset ids like `sfx/whoosh_1` resolve via `SfxLibrary.resolve()` to the
  shipped file above. Each category ships exactly one recorded variant;
  ids with higher indices (`sfx/whoosh_2`, …) resolve to the same canonical
  file — this is documented behavior, not a placeholder.
- `SfxLibrary.resolve()` returns null for unknown categories or malformed
  ids; the placement engine skips those events with a user-visible message
  instead of failing silently.

## Replacing or adding sounds

Never bundle sounds with unclear redistribution rights. If a future sound is
added or replaced, record its provenance in the table above and keep the
"no third-party samples" rule intact — the app must stay strike-free and
safe for commercial use.
