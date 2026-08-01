# Functional Specification: Anime Startup First, Tablet Fidelity Second

## Goal

Implement the updated anime and light-novel interface from
`Redesign giao diện anime lightnovel.zip` with pixel-level fidelity while
preserving all existing application functions.

## Execution Priority

1. First, make anime playback start reliably and quickly on YouTube and website
   sources, with particular attention to MuMu x86/x86_64, resume playback, and
   the current white-video/audio-only failure.
2. Only after the anime path passes build and MuMu runtime gates, continue the
   phone/tablet redesign, starting with the missing or partial tablet screens.
3. Translation work remains in scope after the anime and tablet phases.

## Functional Requirements

1. Compare every supplied design screen and state against the current Android
   implementation; reproduce layout, spacing, typography, colors, imagery,
   navigation, controls, and interaction states as closely as the platform
   permits.
2. Retain existing functions. Identify and report every design element that
   cannot be reproduced, plus every missing, extra, or behaviorally ambiguous
   function, before making a product decision on the user's behalf.
3. Phones use portrait orientation only.
4. Tablets in portrait use the phone layout whenever their current aspect and
   width match the phone breakpoint; rotating back to landscape restores the
   tablet layout.
5. While anime playback is active, rotating to landscape automatically enters
   fullscreen; returning to portrait exits the landscape fullscreen state
   consistently without losing playback.
6. Keep the application identity and update path unchanged (`app.mihon.dev`).
7. Produce both a normal Android phone APK (universal/ARM-capable) and a
   separate x86_64 APK for MuMu validation.
8. Measure and reduce anime time-to-first-frame on healthy networks without
   breaking source fallback, subtitles, resume state, YouTube, or website
   sources.
9. Integrate manga translation using evidence from `E:\Project\Manga-Translator`
   and `E:\Project\Translate`. Reader settings expose a translation action that
   translates the current chapter and prefetches the next two chapters.
10. Improve manga text removal/inpainting for colored pages and irregular
    speech bubbles: fully cover old text, preserve bubble outlines/artwork, and
    match local fill colors where needed. Validate on multiple real chapters.
11. Integrate context-aware light-novel translation with natural Vietnamese
    prose, consistent terminology/pronouns, and chapter-level context rather
    than literal sentence-by-sentence output.
12. Install and run the final x86_64 build on MuMu, then commit, push, tag, and
    publish a release containing both the standard universal/ARM-capable APK
    and the separate MuMu x86_64 APK.

## Quality Gates

- Establish an explicit screen-by-screen design inventory from the ZIP.
- Review responsive breakpoints and orientation handling for phone, portrait
  tablet, and landscape tablet.
- Run targeted tests and a release build after implementation.
- Validate phone portrait behavior and tablet portrait/landscape behavior on
  available emulator/device profiles.
- Validate anime landscape fullscreen without a crash, playback reset, or
  package-data loss.
- Capture anime startup timing before and after the fix on representative
  YouTube and website sources.
- On MuMu, confirm the active decoder produces a first frame, resumed playback
  seeks once, and logcat contains no Could not open codec, FATAL, or ANR.
- Translate multiple real manga chapters, including colored and irregular
  bubbles, then inspect old-text removal, boundary preservation, text fit, and
  current-plus-two prefetch continuity.
- Evaluate light-novel output across chapter boundaries for terminology,
  pronouns, tone, and natural Vietnamese phrasing.
- Run the final build and functional test on MuMu before publishing.
- Perform code review and resolve all CRITICAL/HIGH findings.
- Verify the normal APK includes ARM ABIs and the MuMu APK is x86_64-only.
