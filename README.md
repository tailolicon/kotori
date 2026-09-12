<div align="center">

<img src="./.github/assets/logo.png" alt="Kotori logo" title="Kotori" width="112" style="border-radius:24px"/>

# Kotori

### Manga, anime and novels — one Android library

Kotori is a media library built for people who read manga, watch anime and follow novels on the same device. It combines a configurable manga reader, an mpv-powered anime experience, a dedicated novel reader, on-page translation, listening modes, downloads, tracking and source management behind one consistent interface.

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache%202.0-0877d2?labelColor=27303D)](./LICENSE)
[![Latest release](https://img.shields.io/github/v/release/tailolicon/kotori?label=Release&labelColor=27303D&color=0877d2)](https://github.com/tailolicon/kotori/releases)
[![Android 8+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#download)

**Android 8.0 / API 26 or newer**

[Download the latest release](https://github.com/tailolicon/kotori/releases)

</div>

## Why Kotori

Most media apps solve one job. Kotori is designed around the library itself: the same place can hold what you are reading, watching and listening to without reducing every content type to the same UI.

- **Manga** gets a full reader, downloads, local content, translation and page-aware rendering.
- **Anime** gets a real video player with tracks, subtitles, quality controls and episode progress.
- **Novels** get their own reader, source flow, translation and multi-engine text-to-speech.
- **Your library** keeps categories, updates, history, tracking and backups together.

## Highlights

| | Capability |
| --- | --- |
| 📚 | **Three first-class media types** — Manga, Anime and Novels each have purpose-built library, browse, history and detail flows. |
| 🎬 | **mpv anime playback** — subtitles, audio/dub tracks, quality selection, fullscreen switching and episode progress. |
| 💬 | **Manga translation on the page** — speech-region detection, OCR, translation, cleanup and rendered replacement text. |
| 📴 | **Optional offline translation** — on-device HY-MT through llama.cpp; no API key and translated text stays on the device. |
| 🔊 | **Novel listening mode** — Android system TTS, offline neural voices, Microsoft neural voices and Vietnamese CapCut voices. |
| 🌐 | **Flexible sources** — built-in anime sources plus installable manga/anime/novel sources and local content. |
| 🎨 | **Aurora Glass UI** — a dark glassmorphism design with a consistent purple/pink visual language across the app. |
| ☁️ | **Downloads, backups and trackers** — built for a long-lived personal library, not a one-session viewer. |

## Download

Releases are published on the [GitHub Releases](https://github.com/tailolicon/kotori/releases) page.

Choose the APK that matches your device:

| Build | Use it for |
| --- | --- |
| **`arm64-v8a`** | Modern Android phones and tablets. This is the normal choice for physical devices. |
| **`x86_64`** | Supported Android emulators running an x86_64 system image. |

Kotori does **not** publish a universal APK in the current build configuration. Keeping architecture-specific packages avoids shipping hundreds of megabytes of native libraries that a device cannot use.

> Updating over an existing installation requires a compatible package/signing identity. Back up your library before changing between independently signed builds.

## Manga

### A reader that adapts to the title

Kotori supports the reading modes expected from a full manga library, including paged reading in either direction and vertical/long-strip reading for webtoons. Reader behavior, gestures and display options can be customized instead of being tied to one global layout.

You can also:

- download chapters for offline reading;
- browse local content;
- organize titles into categories;
- receive scheduled library updates;
- keep reading history and per-title progress;
- back up and restore the library and settings;
- migrate titles between compatible sources when needed.

### On-page manga translation

Translation is integrated into the reader rather than treated as a separate screenshot workflow. The current pipeline includes:

1. **speech/text-region detection** with an ONNX speech-bubble detector and page-aware guards;
2. **OCR** using bundled ML Kit models and specialized cleanup for Japanese, Chinese, Korean and manga text patterns;
3. **translation** through the selected provider;
4. **layout cleanup and rendering** that fills/cleans the source region and places translated text back into the page;
5. **cache and retry logic** for expensive page work.

Available translation routes in the app include **Google**, **Gemini**, **Groq**, and an **offline** provider.

#### Offline translation

The offline route uses **HY-MT1.5-1.8B Q4_K_M** through a native llama.cpp runtime.

- The model is **not bundled in the APK**; it is downloaded only when you choose to enable it.
- Translation runs on-device after the model is installed.
- No translation API key is required for this mode.
- Model downloads are resumable and verified before use.

The HY-MT model has its own license and territory restrictions. Kotori shows the applicable license and confirmation UI before download; in particular, the bundled notice states that the Tencent HY Community License does not apply in the **European Union, United Kingdom or South Korea**. Review that notice before enabling the model.

## Anime

### mpv-powered playback

Anime playback uses **mpv** and is built around episodes rather than a generic embedded video view.

- subtitle selection;
- audio and dub track switching;
- quality selection;
- portrait-first playback with the episode list kept close to the player;
- landscape fullscreen without losing the current episode;
- per-episode watch progress;
- episode downloads;
- anime tracking support.

Track and quality controls use compact sheets so they do not unnecessarily cover the video.

### Built-in anime sources

Kotori includes several anime sources directly in the app:

| Source | Notes |
| --- | --- |
| **Muse Việt Nam** | Official licensed anime published on YouTube. |
| **Ani-One Vietnam** | Official licensed anime published on YouTube. |
| **AnimeHay** | Vietnamese-subbed anime source. |
| **AnimeVietsub** | Vietnamese-subbed anime source; availability can be affected by site-side protection. |

The YouTube integrations use [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor). Playlists are treated as series so episodes remain grouped together instead of appearing as unrelated videos.

For website-backed sources whose domain can change, source settings allow the active domain to be updated without waiting for a full application release.

## Novels

Novels are a separate content mode with their own reading experience rather than manga pages forced into a text view.

### Reading and translation

Kotori includes novel-specific source support and a translation path for text content, alongside normal history/library behavior. The repository also contains source modules for novel-oriented services such as **DocLN**, **Novel Fever** and **Wattpad**.

### Listen instead of read

The novel reader can turn chapters into continuous speech through multiple engines:

| Engine | Behavior |
| --- | --- |
| **System** | Uses Android's installed text-to-speech voices. |
| **Offline neural** | Runs locally after its voice/model assets are downloaded. |
| **Microsoft** | Streams Microsoft neural voices and requires a network connection. |
| **CapCut** | Streams a Vietnamese voice catalogue and requires a network connection. |

The player has fallback behavior between engines, so a missing local model or unavailable network voice does not have to end the listening session immediately.

## Sources and extension store

Kotori supports installable content sources in addition to the integrations compiled into the application. The repository contains its own extension-store tooling under [`extensions/`](./extensions), including source modules for anime, manga and novels.

Source availability is inherently external to Kotori: websites can change HTML, APIs, domains, anti-bot rules or geographic availability without notice. A broken third-party source does not necessarily indicate a reader or player failure.

## Library, updates and tracking

Kotori is built to keep a large library usable over time:

- custom categories;
- automatic chapter and episode update checks;
- history and progress tracking;
- chapter/episode downloads;
- local backups and restore;
- duplicate/migration tools;
- extension/source management;
- configurable themes and reader behavior.

Tracker integrations in the codebase include services such as:

- [MyAnimeList](https://myanimelist.net/)
- [AniList](https://anilist.co/)
- [Kitsu](https://kitsu.app/)
- [MangaUpdates](https://mangaupdates.com/)
- [MangaBaka](https://mangabaka.org/)
- [Shikimori](https://shikimori.one/)
- [Bangumi](https://bgm.tv/)
- [Hikka](https://hikka.io/)

Actual tracker capabilities can differ by media type and by the service's own API.

## Aurora Glass

Kotori's visual direction is **Aurora Glass**: dark surfaces, translucent cards, soft separation and purple/pink accents. The design is intended to stay coherent across manga, anime and novel workflows rather than styling only the home screen.

The UI is implemented with modern Android/Compose components while retaining the performance characteristics expected from a large offline library.

## Build from source

### Toolchain

The repository currently targets:

- **JDK 21** for the project build;
- **Android min SDK 26** (Android 8.0);
- **Android target SDK 36**;
- **Android compile SDK 37**;
- **Android NDK 29.0.14206865**;
- **CMake 3.31.6** for the native translation runtime.

Android Studio can install the matching SDK/NDK components, or you can provide them through your normal Android SDK setup.

### Build

Linux/macOS:

```bash
./gradlew assembleRelease
```

Windows:

```powershell
.\gradlew.bat assembleRelease
```

The normal phone artifact is generated under:

```text
app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

### Tests and formatting

The CI path runs these checks:

```bash
./gradlew spotlessCheck
./gradlew testDebugUnitTest
./gradlew verifySqlDelightMigration
```

## Repository structure

```text
kotori/
├── app/                    # Android application and feature integrations
├── core/                   # shared platform/core libraries
├── core-metadata/          # metadata infrastructure
├── data/                   # data layer
├── domain/                 # domain/use-case layer
├── extensions/             # Kotori extension source modules + repository tooling
├── i18n/                   # shared strings/resources
├── i18n-aniyomi/           # anime-oriented localization resources
├── presentation-core/      # shared presentation code
├── presentation-widget/    # reusable UI components
├── source-api/             # source contracts
├── source-local/           # local content source
├── telemetry/              # optional telemetry integration
├── third_party/            # pinned third-party native sources
└── fastlane/               # store/release metadata
```

## Contributing

Bug reports and focused pull requests are welcome when they are reproducible and scoped to this repository.

Before opening a PR, please read:

- [CONTRIBUTING.md](./CONTRIBUTING.md)
- [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md)

For source-related reports, include the affected source, a reproducible title/URL when appropriate, the app version and enough detail to distinguish a site change from an application bug.

## Acknowledgements

Kotori integrates and benefits from a number of open-source projects and ecosystems. In particular:

- [Mihon](https://github.com/mihonapp/mihon) — reader, library and extension ecosystem work;
- [Aniyomi](https://github.com/aniyomiorg/aniyomi) and [mpvKt](https://github.com/abdallahmehiz/mpvKt) — anime and mpv-related components and ideas;
- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) — YouTube extraction used by built-in channel sources;
- [Tachiyomi](https://github.com/tachiyomiorg) and its ecosystem — long-running open-source reader conventions and components;
- [llama.cpp](https://github.com/ggml-org/llama.cpp) — native inference runtime used by optional offline translation.

Third-party components remain subject to their own licenses and notices. See the repository's license and bundled notices for details.

## Disclaimer

Kotori does not host manga, anime or novel content. Content is obtained from local files or third-party services selected by the user or exposed through source integrations. The availability, legality and behavior of those services are outside the application's control; users are responsible for following the laws and terms that apply to them.

Kotori is not affiliated with or endorsed by the content providers exposed through its sources.

## License

Kotori is distributed under the [Apache License 2.0](./LICENSE).

The repository retains the copyright and attribution notices required by the open-source components it uses, including the notices already present in the project license and source tree.
