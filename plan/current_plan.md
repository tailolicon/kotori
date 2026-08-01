# Current Plan: Anime Startup First, Tablet Fidelity Second

## Consulted Skills

- ECC `android-clean-architecture`
- ECC `kotlin-coroutines-flows`
- ECC `e2e-testing`
- ECC `compose-multiplatform-patterns`
- ECC `kotlin-testing`

Ledger constraints: keep fixes surgical; classify physical devices independently
from tablet UI overrides; on MuMu x86 do not trust an advertised MediaCodec until
a real first frame is observed. Do not use Claude CLI.

## Verified This Pass

- Fixed `createPipParams()` null crash before MPV aspect metadata is ready.
- MuMu/x86 now prefers progressive muxed YouTube video; cold T12 first frame improved from 37.7 s to about 2.37 s. ARM keeps adaptive HLS as the preferred default.
- Final player stayed foreground for 30 s with no FATAL, ANR, or codec-open failure.
- Tablet portrait renders the phone composition at 1080x1920; tablet landscape restores the 1920x1080 rail, T1 library, T2 detail grid, and T3 docked player drawer.
- Physical phones are locked to sensor portrait in both MainActivity and PlayerActivity; only physical tablets auto-enter landscape player fullscreen.
- Built-in AnimeHay and AnimeVietsub endpoints currently resolve to 127.0.0.1, so website episode E2E remains blocked pending a verified endpoint/parser migration.
- Remaining tablet fidelity begins with missing T4 manga reader, then the partial T5/T7/T8 surfaces. Do not release yet.
## Plan

1. Preserve the restored orientation and x86 decoder fixes, then rebuild and
   install the x86_64 debug APK on MuMu.
2. Reproduce and timestamp YouTube playback for a new episode and a resumed
   episode. Gate on first visible frame and absence of `Could not open codec`.
3. Instrument/analyze the hoster pipeline from episode click through extraction,
   video selection, `loadfile`, track readiness, and first frame without logging
   signed URLs, cookies, or API keys.
4. In at most three product files for this phase, remove the verified startup
   blockers: x86 decoder failure, wait-for-all-hoster latency/loading leaks, and
   sequential website embed resolution. Preserve cancellation and source/video
   fallback behavior.
5. Run format checks, unit tests, and repeated MuMu E2E for Muse/Ani-One plus a
   website source, including resume, episode switch, portrait/landscape, PiP,
   FATAL/ANR, and p50/p95 startup timing.
6. Only after anime passes, resume the tablet UI gap list: T4 manga reader first,
   then T3 player drawer, T5 novel columns, T7 whole-season toggle, T8 player
   settings, and the partial phone screens identified in the audit.
7. Add screenshot/golden coverage for phone portrait, tablet portrait phone UI,
   and tablet landscape UI before claiming design fidelity.
8. Continue manga/light-novel translation as the next phase, using a clean-room
   design because the reference models/fonts/code have license and quality
   blockers.
9. Review changes and resolve all CRITICAL/HIGH findings. Then build universal
   ARM-capable and x86_64 release APKs, test MuMu, commit, push, tag, and release.