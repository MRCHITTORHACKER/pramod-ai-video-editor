# Pramod AI Video Editor

A real, native Android video editor (Kotlin + Jetpack Compose): AI-assisted auto-edit,
64 original template configurations driven by a real template engine, animated captions,
automatic SFX placement, VFX, emoji overlays, and MP4 export via Media3 Transformer.

No login page, no ads, no fake account/cloud features. All data stays on-device;
AI features are optional and use the user's own free-tier API keys.

## Project structure

17 Gradle modules (Kotlin DSL):

- `app` — entry point, navigation (27 screens), Material3 premium-dark theme
- `core-model` — Project, Clip, Caption, Effect, SfxEvent, 23-field TemplateConfig
- `core-database` — Room (projects, templates, export jobs)
- `core-storage` — app-private project files + crash recovery scan
- `core-security` — Android Keystore-backed API key storage
- `feature-templates` — template engine: parser, validator, timeline mapper + 64 JSON configs
- `feature-audio` — 14-category SFX library, event-driven placement engine, mixer
- `feature-vfx` — 15 effect definitions with parameter ranges + fallbacks
- `feature-captions` — caption editing + SRT export
- `feature-typography` — kinetic typography presets
- `feature-editor` — AI auto-edit orchestrator (honest failure handling)
- `feature-export` — export settings + foreground export service
- `feature-projects`, `feature-home` — project management, dashboard
- `ai-groq`, `ai-gemini` — optional transcription / caption-assist clients
- `media-engine` — Media3 Transformer export pipeline + CustomRenderer abstraction

Unit tests live per-module under `src/test` (JVM). See `REPORT.md` for the
honest implemented-vs-pending breakdown.

## Build requirements

- Android Studio (latest stable) with Android SDK
- JDK 17+
- `local.properties` with `sdk.dir=/path/to/Android SDK` (a starter file is included;
  point it at your SDK)

Versions used (verified 2026-10-17, `gradle/libs.versions.toml`):
AGP 9.4.1 · Kotlin 2.4.20 · Compose BOM 2026.09.00 · Media3 1.11.0 ·
Room 2.8.5 · Navigation Compose 2.9.6 · OkHttp 4.12.0 · compileSdk/targetSdk 36, minSdk 26.

> **Annotation processing note:** Room uses KAPT here (not KSP) because KSP has
> no release for Kotlin 2.4.x (latest KSP is 2.3.12, verified on Maven Central).
> KAPT ships with the Kotlin Gradle plugin, so its version always matches.

## Build the APK

### Option A — Android Studio (PC/laptop)

1. Open this folder in Android Studio.
2. Let Gradle sync (first sync downloads ~1–2 GB of dependencies).
3. Generate the SFX assets once: `python3 tools/gen_sfx.py`
   (needs Python 3 + `pip install numpy scipy` + `ffmpeg`; CI does this
   automatically, so this step is only for local builds).
4. `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
4. Install `app/build/outputs/apk/debug/app-debug.apk` on your phone
   (allow "Install unknown apps" for the browser/file manager).

### Option B — GitHub Actions (free, no PC needed)

A workflow is included at `.github/workflows/build-apk.yml`:

1. Create a free GitHub account and a new repository; upload this project's files.
2. Every push and every pull request automatically builds a **debug APK**
   (job "Debug APK"); manual runs are also possible from the Actions tab
   ("Build APKs" → Run workflow).
3. When it finishes, download the `app-debug-apk` artifact.
4. Install it on your phone (allow "Install unknown apps").

A **signed release APK** is built on every push to `main` (job "Signed release
APK", artifact `app-release-apk`) once the four keystore secrets below are set
in the repo: Settings → Secrets and variables → Actions → New repository secret.

## Signed release build

Release builds are signed with `keystore/upload-keystore.jks` (alias `upload`,
RSA-2048, 30-year validity). The build reads `keystore.properties` next to
`settings.gradle.kts`:

```
storeFile=keystore/upload-keystore.jks
storePassword=<the keystore password>
keyAlias=upload
keyPassword=<the key password>
```

This file is gitignored; the build works without it (debug/CI builds are
unaffected, and a release build without it simply isn't signed).

For GitHub Actions, create these four repository secrets (same values as
`keystore.properties`):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | base64 of `keystore/upload-keystore.jks` — generate with `base64 -w0 keystore/upload-keystore.jks`, then paste the whole single line |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | `upload` |
| `KEY_PASSWORD` | the key password |

> ⚠️ **BACK UP THE KEYSTORE.** Copy `keystore/upload-keystore.jks` **and** the
> password to at least two safe places (e.g. an encrypted USB drive and a
> password manager). If this keystore is lost, Google Play will **not** accept
> updates signed with a new key — you would have to publish the app as a brand
> new Play Store listing and every user would need to reinstall.

## Run unit tests

This repo ships no Gradle wrapper; use a local Gradle 9.8.0+ install
(the CI workflow installs it for you):

```
gradle :feature-templates:testDebugUnitTest :feature-audio:testDebugUnitTest :feature-captions:testDebugUnitTest
```

The pure-Kotlin engine (parser, validator, timeline mapper, SFX placement, SRT
export) is also compilable with plain `kotlinc`; 13/13 tests pass on the JVM
and all 64 template configs parse + validate with zero errors.

## AI keys (optional, free tier)

- Groq: console.groq.com → free API key → paste in app: Settings → AI Providers → Groq → Validate & Save.
- Gemini: aistudio.google.com → free API key → same screen → Gemini.
- Keys are stored encrypted in the Android Keystore and never logged.
- Without keys, transcription/caption-assist are unavailable; manual editing
  works fully.

## Known limitations (honest)

- Custom-shader VFX (`renderPath: custom`) go through the `CustomRenderer`
  interface; concrete GPU implementations are not done yet. Media3-native
  effects are wired in the export pipeline.
- The project was statically audited but **never compiled**: Gradle could not
  run in the build environment (~800MB RAM). API usage was checked by hand
  against the version catalog, but a real `./gradlew :app:assembleDebug`
  (or the CI workflow) is the first true compile check.
- `REPORT.md` has the per-chunk implemented-vs-pending breakdown.

## Needs on-device testing

Nothing here has run on a real phone yet. Before calling it done, verify on
device:

1. Install the debug APK; create a project, pick photos + a video, trim/split
   a clip in the timeline.
2. Add a caption, a kinetic preset, an emoji overlay, and a VFX effect; check
   the preview renders them.
3. Attach background music and an SFX event.
4. Run an export (9:16, 1080p) with a missing SFX asset to see the new
   warnings list on the progress screen; share the MP4 via the share sheet.
5. Force-quit mid-edit, reopen, and use Settings → Project Recovery to restore.
6. Install the **signed release APK** over the debug one — Android will refuse
   (different signature); uninstall first, then install the release build.
7. On Android 13+, confirm the notifications permission prompt appears before
   the first export.
