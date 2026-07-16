<div align="center">

<img src="./.github/assets/logo.png" alt="Kotori logo" title="Kotori logo" width="96" style="border-radius:22px"/>

# Kotori

### Manga, Anime & Novels in one app
A single Android library for everything you read and watch — with a real mpv video player, anime sources built into the app, and a glassmorphism redesign throughout.

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache%202.0-0877d2?labelColor=27303D)](/LICENSE)
[![Releases](https://img.shields.io/github/v/release/tailolicon/kotori?labelColor=27303D&color=0877d2)](https://github.com/tailolicon/kotori/releases)

*Requires Android 8.0 or higher.*

## Download

Grab the latest APK from the [Releases](https://github.com/tailolicon/kotori/releases) page. `arm64-v8a` is the right build for essentially every phone made in the last decade; take the `universal` APK only if you know you need it.

</div>

## Features

<div align="left">

### One library, three kinds of content

Manga, Anime, and Novels live side by side, switchable from the top of the library screen. Each mode swaps the whole tab set — library, browse, updates, history, and detail screens are purpose-built per content type rather than bolted onto a reader.

### Anime, properly

* Playback runs on **mpv**, not a toy player: subtitles, dub/audio track switching, and quality selection all work.
* **Portrait-first player** — tapping an episode starts it at the top of the screen with the episode list right below (thumbnails + watch progress), so changing episodes never means leaving the screen. One button toggles landscape fullscreen without interrupting playback, and track menus open as bottom sheets instead of covering the video.
* Per-episode watch progress, episode downloads, and anime trackers.

### Anime sources built into the app

No extension install, no repo to add — these ship compiled into the APK:

| Source | What it is |
| --- | --- |
| **Muse Việt Nam** | Official licensed anime on YouTube |
| **Ani-One Vietnam** | Official licensed anime on YouTube |
| **AnimeHay** | Vietnamese-subbed anime site |
| **AnimeVietsub** | Vietnamese-subbed anime site *(experimental — the site sits behind Cloudflare)* |

The YouTube sources are extracted with [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor), and each *playlist* is treated as one series, so episodes stay grouped as a show instead of scattering one-per-entry.

When one of these sites changes domain, you fix it in the app rather than waiting on a rebuild: open the source → ⋮ → Settings → paste the new domain.

### Aurora Glass

A dark theme with glassmorphism cards and purple/pink gradient accents, applied consistently across library, browse, detail, updates, and history — for manga and anime alike, not just the screens that were easy.

### Everything a mature reader needs

* Local reading of content, and a configurable reader with multiple viewers and reading directions.
* Extension sources for manga and anime, plus categories to organize your library.
* Trackers: [MyAnimeList](https://myanimelist.net/), [AniList](https://anilist.co/), [Kitsu](https://kitsu.app/), [MangaUpdates](https://mangaupdates.com), [Shikimori](https://shikimori.one), [Bangumi](https://bgm.tv/), [Hikka](https://hikka.io/).
* Scheduled library updates for new chapters/episodes, and local backups.

</div>

## Lineage

<div align="left">

Kotori began as a [Mihon](https://github.com/mihonapp/mihon) fork and has since grown well past it — anime is a first-class content type here rather than a separate app, several sources are compiled in, and the UI has been redesigned end to end. It still tracks Mihon for the reader core.

It is an independent, unofficial personal project, and is **not affiliated with or endorsed by** the Mihon or Aniyomi projects. Please don't take Kotori's bugs to them.

### Credits

* [Mihon](https://github.com/mihonapp/mihon) — the reader core Kotori grew out of.
* [Aniyomi](https://github.com/aniyomiorg/aniyomi) and [mpvKt](https://github.com/abdallahmehiz/mpvKt) — the anime/mpv player foundation the anime support was ported from.
* [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) — YouTube extraction behind the built-in Muse/Ani-One sources.
* [Tachiyomi](https://github.com/tachiyomiorg) — the original project both Mihon and Aniyomi descend from.

</div>

## Contributing

<div align="left">

This is a personal project maintained for my own use, not a community distribution — there's no Discord, translation project, or contribution pipeline of its own.

Issues with **Kotori's own additions** (anime integration, Aurora Glass, built-in sources) belong on this repo. Anything upstream — general reader bugs, core features — belongs with [Mihon](https://github.com/mihonapp/mihon).

[Code of conduct](./CODE_OF_CONDUCT.md) · [Contributing guide](./CONTRIBUTING.md) *(inherited from Mihon; still a good read if you want to send a PR)*

### Disclaimer

The developer(s) of this application does not have any affiliation with the content providers available, and this application hosts zero content. The built-in anime sources scrape publicly available pages from third-party sites; the behavior and availability of those sites are outside this app's control.

### License

<pre>
Copyright © 2015 Javier Tomás
Copyright © 2024 Mihon Open Source Project
Copyright © 2024 Aniyomi Open Source Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
</pre>

</div>
