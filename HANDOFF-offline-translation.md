# Handoff — offline (on-device) translation provider for Kotori

Written 2026-08-03. Self-contained: you do not need the prior conversation.

## Goal

Add a fourth translation provider that runs a local LLM on the device, so manga translation keeps
working with no API key, no quota and no network. The user's Gemini key is currently blocked by
Google (HTTP 403, billing/dunning), and Google Translate — the only working fallback — produces
output like "người truyền hình" for *climber* and "BẠN YÊU VÀO CỦA TÔI BẪY". That is the motivation:
**quality and independence, not speed.**

## What is already downloaded, and where

```
C:\Users\Tailolicon\AppData\Local\Temp\claude\E--Project-kotori\43940d81-8495-4c8e-bc4c-00673f67b3b9\scratchpad\
├── hymt\HY-MT1.5-1.8B-Q4_K_M.gguf    1.06 GB   (GGUF v3, verified magic)
├── hymt\License.txt                   16 KB
└── llamacpp\                          45 MB    (llama.cpp b10240, win-cpu-x64, llama-cli.exe + DLLs)
```

**This is a temp directory and may be wiped at any time** — another working directory in this same
session (`E:\Project\kotori\.transwork`) was deleted mid-session without warning. Move these
somewhere durable before relying on them. Free space is tight: C: ~24 GB, E: ~7.8 GB.

Re-download if lost:
- Model: `https://huggingface.co/tencent/HY-MT1.5-1.8B-GGUF/resolve/main/HY-MT1.5-1.8B-Q4_K_M.gguf`
- llama.cpp: GitHub `ggml-org/llama.cpp` releases, asset `llama-b10240-bin-win-cpu-x64.zip`

## Model facts (verified, not assumed)

- Official name **HY-MT1.5-1.8B**. "1.5" is the *version*; **1.8 B parameters** is the size.
  Do not confuse it with a "1.5B" model — no such variant exists.
- Tencent's official GGUF quantisations and sizes: **Q4_K_M 1.13 GB** (downloaded; 1.06 GiB on disk),
  Q6_K 1.47 GB, Q8_0 1.91 GB.
- The widely-quoted **440 MB build is 1.25-bit** and needs Tencent's **custom STQ kernel for mobile
  CPUs**, distributed on ModelScope with an Android demo APK. It is *not* plain llama.cpp GGUF.
  Treat it as unavailable until Tencent open-sources that kernel — depending on a closed runtime is
  a bad trade for this project. If you want to evaluate it anyway, install their demo APK.
- Supports 33 languages; Chinese, English and Vietnamese all included. WMT25: first place in 30 of
  31 categories (that result is for the 7B model).
- License: **TENCENT HY COMMUNITY LICENSE AGREEMENT**. Two clauses that matter for shipping:
  - **Does not apply in the EU, UK and South Korea** (territory-limited).
  - Requires a separate license from Tencent above **100 million MAU** (irrelevant here).
  Read `License.txt` in full before publishing anything that bundles or auto-downloads the model.

## Known gotcha, already hit

`llama-cli` with default context tries to allocate a **16 GB KV cache** and dies with
`failed to allocate buffer of size 17179869184`. Always pass `-c 2048` (or similar). This matters
doubly on a phone — the context window must be pinned small.

## What was NOT done

**No benchmark numbers exist.** A benchmark script was written and started twice but never produced
output: the first run died on the KV-cache issue above, the second was cancelled by the user, and a
third was killed when the session ended. So there is **no measured quality comparison and no measured
tokens/sec** — treat any speed claim as unverified.

The script is at `scratchpad\bench.ps1`. It translates 8 real bubbles from the user's chapter 8
(Apocalypse Sword God), one call per line, timing each, then projects to a 20-bubble page. Reuse or
replace it. Compare its output against what Google Translate produced for the same lines — those are
in the conversation record and in `.transwork/chap8*/view/sheets/*.png` if that directory still exists.

## Speed expectation (estimate, unverified)

A page has 10–25 bubbles. Gemini does a whole page in **one vision call, 3–6 s measured**. An
on-device LLM does **one generation per bubble**, so expect roughly 20–40 s per page — 5–10× slower.
Also note MuMu benchmarks are not phone benchmarks: the emulator runs on the user's i5-11400H, far
faster than a mid-range Snapdragon. Any number obtained there is an optimistic upper bound.

## Where it plugs in

The architecture already supports this. **No renderer changes are needed** — do not touch
`BubbleRenderer`, `BubbleFill`, `GlyphMask` or `Inpainter`, which have just been through several
rounds of pixel-level fixes.

`mihon/feature/translation/provider/TranslationProvider.kt` defines:

```kotlin
interface TranslationProvider {
    val displayName: String
    val supportsVisionOcr: Boolean get() = false
    suspend fun translateLines(texts: List<String>, context: TranslationContext): List<BubbleTranslation>
    suspend fun ocrAndTranslate(bitmap: Bitmap, boxes: List<BubbleBox>, context: TranslationContext): List<BubbleTranslation>
    suspend fun translateProse(paragraphs: List<String>, context: TranslationContext, prose: ProseContext): ProseTranslation
}
```

An offline provider implements **`translateLines` only**, leaving `supportsVisionOcr = false`. The
pipeline then uses on-device ML Kit OCR (already wired, `ocr/BubbleTextRecognizer.kt`) to extract
text per bubble, exactly as the Google and Groq providers do. `BubbleTranslation(source, translation)`
must echo the source string back — `PageTranslator.realignToOcr` uses it to detect and fix
translations landing in the wrong bubble.

Touch points to register a new provider:

| File | Change |
|---|---|
| `TranslationPreferences.kt` | add `OFFLINE` to `enum class TranslationProviderType`; add prefs for model path / thread count |
| `provider/TranslationProviders.kt` | add the branch in `of(type, preferences)` |
| `TranslationPreferences.hasCredentialsFor()` | offline has no key — return whether the model file exists |
| `eu/kanade/presentation/reader/settings/TranslationSettingsPage.kt` | add to the provider list (line ~33) and to the "missing credential" check (line ~82) |

`TranslationPreferences.outputStamp()` feeds the translated-page cache key and already includes
provider name and model — make sure the offline model identity lands in it, or switching models will
serve stale rendered pages. Bump `RENDERER_VERSION` only if rendering changes (currently `r19`).

## Runtime decision still open

llama.cpp needs to run inside the app. Options, in rough order of effort:

1. **JNI bind llama.cpp via NDK** — most control, most work. The repo already ships an ONNX Runtime
   dependency, so native deps are not new territory here.
2. **An existing Android llama.cpp wrapper library** — check maintenance status and license.
3. **Bundled `llama-server` binary + localhost HTTP** — quickest to prototype, awkward to ship.

Whatever you pick, the model must be a **user-initiated external download**, not bundled: the APK is
~200 MB today and the model is 1.06 GB. Provide a settings entry that downloads, verifies (size +
hash) and stores it, and that reports progress. Note the app already has a hard lesson about
verifying extracted binaries — see `BubbleDetector.extractModel()`/`isExtractionCurrent()`, which
were rewritten after a corrupt model file silently disabled all translation.

## Project conventions you must follow

- **Build with the PowerShell tool, not Bash.** `gradlew.bat` fails with "not recognized" under the
  Bash tool on this machine. Use:
  `$env:TMP='C:\Windows\Temp'; $env:TEMP='C:\Windows\Temp'; .\gradlew.bat --console=plain :app:assembleDebug`
- **Any new binary asset must be listed in `.gitattributes` as `binary` in the same commit.** The
  file starts with `* text=auto`, and an unlisted `.onnx` model had every `0x0D` byte stripped on
  commit — 130 bytes gone, protobuf unparseable, all translation dead on every page for every
  provider. Fixed in `c5e90db`; do not reintroduce it.
- Test device is MuMu at `127.0.0.1:7555` (x86_64, Android). `adb connect` first; it drops often.
  Note the emulator reports `smallestScreenWidthDp` 617, so the app treats it as a **tablet**. To
  test phone behaviour: `adb shell wm density 420` (→ 411 dp), and `wm density reset` afterwards.
- Verify rendered output by diffing translated pages against originals, not by reading code. Pull
  `cache/translated/<mangaId>/<chapterId>/` and `cache/chapter_disk_cache/` off the device, match by
  dimensions **then disambiguate by pixel diff** — dimensions alone once mispaired two same-size
  pages and produced an entirely bogus review.
- Comparing "faint changed pixels" between two runs is **invalid** when the provider is Google
  Translate: each run returns different wording, so the changed area differs regardless. Compare
  images.

## Suggested first steps

1. Move the model and llama.cpp out of the temp directory.
2. Run `bench.ps1` (fix the output parsing if needed) and get real quality + speed numbers on the PC.
   Decide from those whether this is worth building at all.
3. If yes, prototype the runtime on the emulator before writing any app code.
4. Only then wire up `OfflineTranslationProvider` at the four touch points above.
