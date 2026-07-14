<div align="center">

<img src="./.github/assets/logo.png" alt="Kotori logo" title="Kotori logo" width="96" style="border-radius:22px"/>

# Kotori

### Manga, Anime & Novels in one app
A personal [Mihon](https://github.com/mihonapp/mihon) fork with built-in Anime support (powered by [Aniyomi](https://aniyomi.org)'s mpv player) and an "Aurora Glass" visual redesign, running on Android.

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache%202.0-0877d2?labelColor=27303D)](/LICENSE)
[![Fork of Mihon](https://img.shields.io/badge/fork%20of-mihon-27303D?labelColor=161B22&color=0877d2)](https://github.com/mihonapp/mihon)

*Requires Android 8.0 or higher.*

## Download

Built privately for personal use — grab the latest APK from this repo's [Releases](https://github.com/tailolicon/kotori/releases) page.

## What's different from Mihon

<div align="left">

* **Three content modes in one library**: Manga, Anime, and Novel, switchable from the top of the library screen.
* **Full Anime support**, ported from [Aniyomi](https://github.com/aniyomiorg/aniyomi): browse/search anime sources, per-episode watch progress, and playback through Aniyomi's own **mpv** player — subtitles, dubbing/audio tracks, and quality selection all work as in upstream Aniyomi.
* **Portrait-first anime player**: tapping an episode plays it at the top of the screen with the episode list right below it (thumbnails + watch progress), so switching episodes doesn't require leaving the screen. A single button toggles landscape fullscreen without interrupting playback; track menus open as bottom sheets instead of covering the video.
* **Built-in anime sources** — compiled directly into the app, no extension install required:
  * **AnimeHay** and **AnimeVietsub** (Vietnamese-subbed anime sites)
  * **Muse Việt Nam** and **Ani-One Vietnam** (official, licensed YouTube channels, extracted via [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor); each YouTube *playlist* is treated as one series so episodes stay grouped)
  * If a site's domain changes, it's fixable in-app: open the source → ⋮ → Settings → paste the new domain, no rebuild needed.
* **"Aurora Glass" theme**: a redesigned dark theme with glassmorphism cards and purple/pink gradient accents, applied consistently across library, browse, detail, updates, and history screens for both manga and anime.
* Everything else Mihon already does — see below.

</div>

## Also includes (inherited from Mihon)

<div align="left">

* Local reading of content.
* A configurable reader with multiple viewers, reading directions and other settings.
* Tracker support: [MyAnimeList](https://myanimelist.net/), [AniList](https://anilist.co/), [Kitsu](https://kitsu.app/), [MangaUpdates](https://mangaupdates.com), [Shikimori](https://shikimori.one), [Bangumi](https://bgm.tv/) and [Hikka](https://hikka.io/) support.
* Categories to organize your library.
* Light and dark themes.
* Schedule updating your library for new chapters/episodes.
* Create backups locally to read offline or to your desired cloud service.

</div>

## Contributing

This is a personal fork maintained for my own use, not a general-purpose community project — so there's no Discord, translation project, or contribution pipeline of its own.

For anything **not specific to Kotori's changes** (general reader bugs, upstream features), the [Mihon repository](https://github.com/mihonapp/mihon) is the right place. For issues with Kotori's own additions (anime integration, Aurora Glass theme, built-in sources), open an issue on this repo.

[Code of conduct](./CODE_OF_CONDUCT.md) · [Contributing guide](./CONTRIBUTING.md) *(inherited from Mihon; still a good read if you want to send a PR)*

### Credits

Kotori builds on the work of:

* [Mihon](https://github.com/mihonapp/mihon) — the reader this fork is based on.
* [Aniyomi](https://github.com/aniyomiorg/aniyomi) and [mpvKt](https://github.com/abdallahmehiz/mpvKt) — the anime/mpv player foundation this fork's anime support is ported from.
* [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) — YouTube extraction used by the built-in Muse/Ani-One sources.
* [Tachiyomi](https://github.com/tachiyomiorg) — the original project both Mihon and Aniyomi descend from.

### Disclaimer

The developer(s) of this application does not have any affiliation with the content providers available, and this application hosts zero content. Built-in anime sources scrape publicly available pages from third-party sites; behavior and availability of those sites are outside this app's control.

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

Kotori is an independent, unofficial personal fork and is not affiliated with or endorsed by the Mihon or Aniyomi projects.

</div>
