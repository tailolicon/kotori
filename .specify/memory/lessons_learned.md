# AI Memory Ledger: Lessons Learned & Pitfalls

<!-- [VIBECODER-LEDGER-VERSION: v1.1.0] -->

> **CRITICAL STANDARD:** ALL AI models MUST read this ledger before coding.
> Append learnings here autonomously after resolving bugs to prevent circular regression.

## Verified Constraints & Past Fixes

### 1. Baseline Surgical Rule
- **Mistake to Avoid:** Rewiring adjacent working code while trying to fix an isolated bug.
- **Enforced Solution:** Strictly isolate target lines. Verify localized behavior before modifying.

### 2. Novel Reader Chapter Boundaries
- **Mistake to Avoid:** Treating prose progress as passive state while reusing the previous chapter's scroll position; this prevents normal transitions or can cascade across chapters.
- **Enforced Solution:** Resolve the next chapter with the canonical ascending chapter comparator, guard completion by chapter ID, and create a fresh scroll state whenever chapter content changes.

### 3. Seamless Novel Chapter Navigation
- **Mistake to Avoid:** Replacing reader state with a loading screen at a chapter boundary or storing reader-only bookmark state; both break continuity and desynchronize the chapter list.
- **Enforced Solution:** Keep the outgoing chapter visible while an adjacent chapter loads, atomically guard navigation, animate immutable chapter snapshots in the gesture direction, and persist bookmarks only through the canonical chapter record.

### 4. Physical Device Class Must Ignore Tablet UI Overrides
- **Mistake to Avoid:** Classifying phone versus tablet from an Activity `resources.configuration`; `prepareTabletUiContext()` can spoof `smallestScreenWidthDp` according to the UI preference and accidentally unlock landscape on a phone.
- **Enforced Solution:** Read physical `smallestScreenWidthDp` from `applicationContext.resources.configuration`, then derive the Kotori layout separately from the live orientation. Keep rotation-driven player fullscreen as state so MPV is never recreated.

### 5. MuMu x86 MediaCodec Can Advertise an Unusable Decoder
- **Mistake to Avoid:** Assuming an advertised x86 VP9/H.264 MediaCodec is usable; MuMu can fail with `surface/native_window NULL`, leaving audio active and the video blank.
- **Enforced Solution:** Pass `hwdec=no` as a per-file mpv option on x86/x86_64 unless the source explicitly overrides it. Do not change ARM decoding, and verify first frame plus resume in MuMu logcat before release.
### 6. YouTube HLS Startup and Early PiP Metadata
- **Mistake to Avoid:** Selecting adaptive HLS first on MuMu/x86, where it can take more than 30 seconds to start, or force-unwrapping MPV aspect metadata before the first frame is ready.
- **Enforced Solution:** Prefer the best progressive muxed stream only on x86/x86_64 while preserving HLS quality on ARM, and build PiP aspect ratio only from finite positive metadata. Verify cold-start time and keep playback alive for at least 30 seconds.

### 7. Download-Then-Upload Sync Must Merge Progress Monotonically
- **Mistake to Avoid:** Restoring a non-zero but older cloud position over newer local progress immediately before creating the upload backup; this silently re-uploads the stale value.
- **Enforced Solution:** Merge chapter/page and episode/time positions with the maximum local/remote value, enqueue persisted progress immediately, and skip no-op history restores to avoid sync feedback loops.
