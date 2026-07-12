# Handoff: Kotori — Manga · Anime · Light Novel App Redesign (Mihon fork)

## Overview

**Kotori** is a complete UI/UX redesign of [Mihon](https://mihon.app) (open-source Android manga reader, Kotlin + Jetpack Compose) that extends it from manga-only to **three content types: Manga, Anime (video), and Light Novels (text)**.

The design keeps **100% feature parity** with Mihon (library, categories, updates, history, browse/sources/extensions, migration, downloads, tracking, stats, backup, settings, readers) and adds: a video player, a text/novel reader, an airing-season calendar, resume-watching, and episode downloads.

Design language: **"Aurora Glass"** — deep violet-black night backgrounds, slowly drifting aurora light blobs, frosted-glass panels, asymmetric corner radii (one corner "clipped" smaller), mode-colored accents, display font Unbounded. UI copy is **Vietnamese** and must stay Vietnamese (exact strings are in this doc and in the HTML).

## About the Design Files

The files in this bundle are **design references created in HTML** — high-fidelity mockups showing intended look and behavior. They are **not production code**. Your task is to **recreate these designs inside the Mihon codebase** (Kotlin, Jetpack Compose, Material 3, Voyager navigation, Coil, SQLDelight) following its established patterns — screen-by-screen mappings to Mihon packages are given below. Where a feature is new (video player, novel reader, calendar), implement it with the ecosystem-standard library (e.g. `androidx.media3` / ExoPlayer for video).

Open `Kotori Concepts.dc.html` in a browser to see all 22 screens on one pannable canvas. Screens are labeled `01`–`22` under each phone. `android-frame.jsx` renders the phone bezel (not part of the design). `image-slot.js` renders dashed drop-zones — these represent **runtime cover art / video frames loaded from sources via Coil**, not static assets.

## Fidelity

**High-fidelity.** Colors, typography, spacing, radii, blur, copy and states are final and should be matched closely. All px values were designed on a **428 px-wide phone canvas** → treat 1 px ≈ 1 dp on a standard 428 dp-wide device. One deliberate exception: some type sizes are mockup-compressed; **minimum body text in the real app should be 12 sp and touch targets ≥ 48 dp** (e.g. the 36 px icon buttons in mocks → 40–48 dp with padding).

---

## Design Tokens

### Colors — base (all modes)

| Token | Value | Use |
|---|---|---|
| `bg/base` | `#14101F` | Main app background |
| `bg/player` | `#100D18` | Video player screen |
| `bg/readerManga` | `#0B0910` | Manga reader chrome |
| `bg/sheet` | `rgba(24,19,38,.97)` | Bottom sheets |
| `bg/navbar` | `rgba(24,19,38,.75)` + blur 20 px | Floating bottom nav |
| `text/primary` | `#F3EFFB` | Titles, body |
| `text/secondary` | `#A79FC0` | Descriptions |
| `text/muted` | `#8D84AC` | Metadata, sublabels |
| `text/faint` | `#6E6590` | Disabled, footnotes |
| `glass/bg` | `rgba(255,255,255,.05)` (rows) / `.06`–`.07` (chips, buttons) | Frosted panels |
| `glass/border` | `rgba(255,255,255,.09)` (rows) / `.10`–`.14` (elevated) | 1 px borders |
| `danger` | `#FB7185` | Delete, errors, obsolete |
| `warning` | `#FCD34D` / `#FDE68A` | Untrusted, star ratings |
| `success` | `#5EEAD4` | Downloaded ✓, linked ✓ |
| `highlight/pink` | `#F0ABFC` | Countdown text, seek time |

### Colors — mode accents (the core theming mechanic)

Switching content mode re-themes every accent in the app:

| Mode | Gradient (buttons, active segments, progress) | Light accent (icons, labels) | CTA gradient |
|---|---|---|---|
| **Anime** | `linear-gradient(120deg,#8B5CF6,#C084FC)` | `#C4B5FD` | `linear-gradient(120deg,#8B5CF6,#F472B6)` |
| **Manga** | `linear-gradient(120deg,#EC4899,#F472B6)` | `#F9A8D4` | same as gradient |
| **Novel** | `linear-gradient(120deg,#14B8A6,#5EEAD4)` | `#5EEAD4` | same; text on teal = `#0B1512` |

Accent glow shadows: `0 6px 20px` … `0 10px 30px` of the accent at 40–45% alpha (e.g. `rgba(139,92,246,.45)`).

### Aurora background blobs

2 per screen, absolutely positioned, behind content:
`width/height 260–320px; border-radius:50%; background:radial-gradient(circle, <accent at .13–.34 alpha>, transparent 70%); filter:blur(10–12px);`
Animation `kAurora`: translate(30px,−24px) scale(1.15) at 50%, back at 100%; 9–12 s ease-in-out infinite (stagger durations; reverse one). Implement in Compose as two blurred radial-gradient circles animated with `rememberInfiniteTransition`.

### Radii — the "Kotori corner" (signature)

Asymmetric: three large corners + one small "clipped" corner.

| Element | Radius |
|---|---|
| List rows / glass cards | `18 18 18 6` |
| Large hero cards | `26 26 26 8` |
| Library cover tiles | `24` with one `8` corner, **rotating position per tile** (see Library) |
| Thumbnails inside rows | `12 12 12 4` (small) / `14–16 … 4` |
| Primary CTA buttons | `20 8 20 20` |
| Active segment in switchers | `18 6 18 18` (position of the 6 varies to point at content) |
| Chips / small buttons | `13–16` uniform |
| Icon buttons | circle |
| Bottom nav container | `28` |
| Bottom sheets | `28 28 0 0` |

### Typography

| Role | Font | Details |
|---|---|---|
| Display / titles / numerals | **Unbounded** (Google Fonts, 400–900) | Screen titles 20 px w700; card titles 13–13.5 px w600; stat numbers 21 px w700 |
| Section labels | Unbounded 10 px, `letter-spacing:.14–.18em`, UPPERCASE, colored mode-light accent (e.g. `HÔM NAY`, `ĐANG TẢI`) |
| Body / UI | **Be Vietnam Pro** 400–700 | Row titles 12.5–13 px w700; body 11.5 px lh 1.6; meta 10.5 px |
| Novel reading text | **Literata** (serif) | 15–18 px user-adjustable, lh 1.85 |
| Icons | **Material Symbols Rounded** | Filled variant for active states (`FILL 1`) |
| Wordmark | Unbounded 800, gradient-clipped text `kotori ✦` |

All three fonts support Vietnamese diacritics — do not substitute fonts that don't.

### Spacing

Screen H-padding **18 px**. Vertical rhythm: 12–16 px between blocks, 8–9 px between list rows. Card inner padding 10–14 px. Chip padding 7–8 px × 13–14 px. Grid gaps: library 14 px, browse grid 12×10 px.

---

## App Structure & Navigation

Bottom nav (floating glass pill, 5 tabs — maps 1:1 to Mihon `HomeScreen` tabs):
**Thư viện · Cập nhật · Lịch sử · Duyệt · Khác** (Library, Updates, History, Browse, More). Active tab = mode-gradient pill + filled icon; inactive = `#8D84AC`.

**Mode switcher** (top of Library, screens 01/04): 3-segment glass control `Manga · Anime · Novel`. Switching mode: (1) filters library/updates/history to that type, (2) swaps the global accent + aurora colors, (3) swaps verbs (Xem/Đọc), progress units (tập/chương/trang), and hero card. Selected mode persists across restarts. Detail/reader screens infer accent from the entry's type regardless of global mode.

Content-type union: library entries, updates, history, downloads, search results and stats all mix the three types; type is always visible via accent color and unit wording (`Tập` = episode, `Ch.` = chapter, `trang` = page).

---

## Screens (22)

Legend: each maps to a Mihon package to modify, or **NEW**.

### 01 · Thư viện — mode Anime (`eu.kanade.presentation.library`)
- Header: wordmark `kotori ✦` (gradient text) left; 36 px glass circle icon buttons right: `search`, `tune` (library settings/filter sheet).
- **Mode switcher** (see above), active segment "Anime" violet gradient, radius `18 6 18 18`.
- **Resume hero card** (`ĐANG XEM DỞ`): glass card radius `26 26 26 8`; 118×76 px thumb (radius `16 16 16 4`), title Unbounded 13.5, meta `Tập 7 · còn 09:12`, 4 px progress bar 62% violet→pink gradient, 46 px circular gradient play FAB.
- **Category chips** (horizontal scroll): active = gradient fill; inactive = glass. With counts: `Tất cả 24 · Đang xem 8 · Chờ tập 5 · Xong 11`.
- **Cover grid 2-col**, aspect ≈ 3/3.6, radius 24 with one 8-corner rotating pattern per tile: `[bottom-left, bottom-right, top-left, top-right]`, repeating. Overlays: top-right unread badge `n mới` (gradient pill), top-left downloaded ✓ chip (dark glass circle, teal icon), bottom gradient scrim with title (Unbounded 13, white) + status line (`Tập 12 · Đang chiếu`).
- Floating bottom nav.
- In mocks, gradient fills stand in for cover art; real covers load via Coil. `image-slot` = drop zone = cover image.

### 02 · Chi tiết Anime (`eu.kanade.presentation.manga` → generalize to MediaScreen)
- 300 px key-visual header, scrim to bg at 96%; glass circle buttons: back / `public` (WebView) / `share` / `more_vert` (menu: migrate, edit categories…).
- Over image: status chips (`Đang chiếu` gradient, `2026`, `12 tập` glass), title Unbounded 24, meta `Ame Studio · AniSrc VN`, star `4.6` in `#FDE68A`.
- Action row: full-width CTA `▶ Xem tiếp · Tập 7` (CTA gradient, radius `20 8 20 20`, glow) + 3 glass circles: `favorite` (filled pink = in library; toggles add/remove), `sync` (tracking sheet, screen 21), `download`.
- Synopsis 2 lines + `Xem thêm` expander. Tab row `Tập / Tương tự` with 3 px gradient underline + `filter_list` (sort/filter episodes).
- Episode list rows (glass, radius `18 18 18 6`): 86×52 thumb with `T{n}` + watch-progress bar, title, sub (`24:00 · xem dở 62%` / `đã tải`), state icon (`downloading` violet / `play_circle` pink / `check_circle` watched-faint / `bookmark` gold). Watched rows at 55% opacity.

### 03 · Player Anime (**NEW** — `androidx.media3`/ExoPlayer)
- Top: 16:9 video, 244 px, rounded bottom `0 0 28 28`. Overlay top bar: back, `Kiếm Vực · T7`, `cast`, `subtitles`. Center controls: glass `replay_10` / gradient 64 px `pause` / glass `forward_10`. **`Bỏ qua intro ›`** glass chip bottom-right (appears during intro window). Seek bar: 5 px, buffered track white 28%, played gradient, white knob with pink glow; times `09:12` (pink) / `24:00`.
- Below (portrait mode): quick-setting glass chips: `Phụ đề · Việt`, `1.0x` speed, `Auto 1080p`, `Khóa` (lock controls), `PiP`.
- **Auto-next card**: `TIẾP THEO · TỰ PHÁT 5s` + next-episode thumb + `Hủy` button (5 s countdown after credits).
- Brightness/volume panel: two gradient sliders with % + gesture legend: double-tap edges = ±10 s seek; vertical swipe left = brightness, right = volume.
- Footer meta: `Nguồn: AniSrc VN · Đã tải T5–T7 · 1.2 GB`.
- Fullscreen-landscape variant not mocked: same components, controls overlay the video.

### 04 · Thư viện — mode Manga
Same as 01 with pink accent, `ĐANG ĐỌC DỞ` hero (`Chương 42 · trang 12/28`, `auto_stories` FAB), categories `Tất cả 36 / Đang đọc 14 / Chờ chương 9 / Xong 13`, unread = chapter counts.

### 05 · Cập nhật (`eu.kanade.presentation.updates`)
- Header `Cập nhật` + glass buttons `refresh` (update library now), `filter_alt`. Sub: `Cập nhật thư viện lần cuối: 5 phút trước` (italic 11 px).
- Grouped by day (`HÔM NAY`, `HÔM QUA` section labels). Row: 46×62 cover thumb, title w700, sub = episode/chapter name + optional progress (`xem dở 04:12`), right download-state button: `download` (not downloaded) / `downloading` (animating) / `download_done` (teal).
- **Multi-select**: long-press enters selection; selected rows get violet fill `rgba(139,92,246,.16)` + gradient check circle. Bottom action bar (glass, violet border): `1 đã chọn` + icons `bookmark` (mark read), `done_all` (mark all previous read), `download`, `delete` (red).

### 06 · Lịch sử (`eu.kanade.presentation.history`)
- Header `Lịch sử` + `delete_sweep` (clear-all dialog). Glass search field `Tìm trong lịch sử…`.
- Day groups. Row: 48×66 thumb, title, sub `Tập 7 · 21:14 · xem dở 62%`, actions: optional `favorite` (appears for non-library entries → add to library), `delete` (remove this history entry — confirm dialog with "remove all for this entry?" option), gradient circular resume `play_arrow`/`auto_stories`.

### 07 · Duyệt · Nguồn (`eu.kanade.presentation.browse` — Sources tab)
- Header `Duyệt` + `filter_list` (language filter). Segment control **Nguồn / Tiện ích / Di dời** (3 Browse tabs).
- **Global search field** with gradient border (`border:1.5px` gradient via padding-box/border-box trick): `Tìm trên mọi nguồn…` → screen 17.
- Sections `ĐÃ GHIM` / `TIẾNG VIỆT · 3`. Source row: 42 px gradient monogram tile (radius `14 14 14 4`), name w700, sub `VI · Anime`, actions: `Mới` chip (latest feed), `Hot` chip (popular, pinned rows only), pin icon (filled violet = pinned, faint = not).

### 08 · Duyệt · Tiện ích (Extensions tab)
- Update banner (gradient-tinted glass): `extension` icon, `2 tiện ích có bản cập nhật`, gradient button `Cập nhật tất cả`.
- Section `ĐÃ CÀI · 5`. Row: monogram tile, name, sub `v2.1.0 · VI` + colored status note; right button variants: `Cập nhật` (gradient), `Đã cài` (glass), `45%` progress (teal outline, installing), `Tin cậy` (amber outline, untrusted — tap to trust), `Gỡ` (red outline, obsolete).

### 09 · Manga Reader (`eu.kanade.tachiyomi.ui.reader`)
- Full-bleed page (the image-slot). Chrome fades in/out on center tap.
- Top scrim bar: back, title 13 w700, chapter (`Chương 42 — Cáo đêm`, pink 10.5), `bookmark`, `public`, `more_vert`.
- Right-center floating page badge `12 / 28` (dark glass pill).
- Bottom scrim: `skip_previous` / seek slider (gradient fill, white glow knob) / `skip_next`; then 5 glass tool tiles: `Webtoon` (`view_day` — reading-mode cycle: webtoon/paged LTR/RTL/vertical), `Xoay` (`screen_rotation`), `Cắt viền` (`crop`), `Sáng` (`brightness_6`), `Thêm` (`settings` → full reader settings sheet).
- Gesture legend (in-app hint): center tap = toggle chrome; left/right edge = page turn; long-press page = save/share.

### 10 · Novel Reader (**NEW**)
- Paper background `#F3EAD8` (sepia theme shown), text `#33302A`, Literata 15–18 px lh 1.85; chapter label `CHƯƠNG 12` (Unbounded 11, teal `#0D9488`); teal drop-cap (Unbounded 44 px).
- Progress row: `43%` — 3 px teal gradient bar — `Ch. 12/48`.
- **Settings sheet** (dark glass, radius `26 26 0 0`, always-dark regardless of paper theme): font-size slider `A− … A+ · 18px`; font segmented `Literata / Noto Serif / Be Vietnam` (active teal gradient, dark text); theme swatch circles: `#FFFFFF`, `#F3EAD8` (selected, teal ring), `#1A1723`, `#000000`; line-spacing icons.
- Same top chrome + gestures as manga reader (tap zones page-turn or scroll mode).

### 11 · Lịch mùa (extends `mihon.feature.upcoming`)
- Header: back, `Lịch mùa`, sub `Tháng 7 · Mùa Hè 2026`, toggle chip `✓ Chỉ thư viện` (filter: library-only vs whole season).
- Week strip `T2…CN`: today = gradient pill; days with releases = outlined.
- Timeline rows grouped by day: time (Unbounded 10.5 violet) + fading vertical line; glass card: 74×46 thumb, title, `Tập 9 · còn 13 giờ` (countdown pink), bell icon (filled violet = notify on air, outline = off).
- Bottom-center gradient button `Hôm nay` (scroll to today). Footnote: times follow device timezone, synced from sources.

### 12 · Khác (`eu.kanade.presentation.more`)
- Active-state banner strip under status bar when enabled: teal `CHỈ NỘI DUNG ĐÃ TẢI — ĐANG BẬT` (also exists for incognito: gray/violet variant `CHẾ ĐỘ ẨN DANH — ĐANG BẬT`).
- App card: 44 px gradient `K` tile, `Kotori 1.0.0 · kênh ổn định`, heart (sponsor/about link).
- Two toggle rows with custom switches (40×22 pill; ON = teal gradient + white knob right): **Chỉ nội dung đã tải** (`cloud_off`, teal-tinted row when on), **Chế độ ẩn danh** (`visibility_off`).
- Menu rows (glass): Hàng đợi tải (badge `5`), Danh mục (`6 danh mục`), Thống kê, Sao lưu & khôi phục (`Tự động · lần cuối hôm qua 23:00`), Cài đặt, Giới thiệu, Trợ giúp — each with chevron.

### 13 · Cài đặt (`presentation.more.settings`)
Rows with 38 px violet-tinted icon tiles: Giao diện / Thư viện / Trình đọc manga / Trình đọc novel / Trình phát anime / Tải xuống / Theo dõi / Duyệt / Bảo mật / Nâng cao — subs describe contents (see HTML for exact strings). Header has settings-search. Sub-screens reuse Mihon's preference structure with Kotori styling.

### 14 · Hàng đợi tải (`eu.kanade.tachiyomi.ui.download`)
- Summary bar: `2 đang tải · 3 đang chờ · 1.9 GB` + gradient button `⏸ Dừng tất cả` (toggles to `Tiếp tục tất cả`).
- `ĐANG TẢI` rows: drag handle `⠿`, title `Kiếm Vực — Tập 8`, sub `Anime · 612 MB / 1.3 GB · 4.2 MB/s` (manga shows `12/28 trang`), pause + red cancel circles, 4 px gradient progress.
- `ĐANG CHỜ` rows at 70% opacity with `schedule` icon. Drag ⠿ to reorder; footnote mentions Wi-Fi-only setting.

### 15 · Danh mục (`eu.kanade.tachiyomi.ui.category`)
- Sub: `Dùng chung cho cả 3 mode · kéo ⠿ để sắp xếp`.
- Row: ⠿, name w700, count chip `12 bộ` (violet outline), `edit` (rename dialog), red `delete` (confirm). FAB `+ Danh mục mới` (gradient, radius `20 8 20 20`).

### 16 · Thống kê (`presentation.more.stats`)
- 2×2 stat cards (radius `20 20 20 7`): icon, Unbounded 21 number, label — `58 Bộ sưu tầm / 24 Hoàn thành / 312 Tập đã xem / 1 240 Chương đã đọc`.
- `THEO LOẠI NỘI DUNG` card: 8 px rounded bars — Manga 62% pink, Anime 34% violet, Novel 22% teal, with counts.
- Split card: `THỜI GIAN XEM 9 ngày 4 giờ` (violet) | `THỜI GIAN ĐỌC 112 giờ` (pink).
- Tracker card: `AL` tile, `AniList · 46 bộ liên kết · điểm TB 8.1`, gold star.

### 17 · Tìm toàn cục (browse global search)
- Search field (gradient border) with query + clear ✕. Filter chips: `Tất cả nguồn` (active) / `Đã ghim` / `Có trong thư viện`.
- Per-source result groups: source name + type tag chip (`ANIME · VI`) + `Xem hết ›`; horizontal row of 96×128 covers (radius `16 16 16 5`) with titles. Sources still loading show shimmer placeholders (same geometry); empty sources collapse to faint `MangaVerse — không có kết quả`.

### 18 · Duyệt nguồn (source browse)
- Header: back, source name + `Anime · Tiếng Việt · v2.1.0`, search + `public` icons.
- Feed pills: `🔥 Phổ biến` (active gradient) / `Mới nhất`.
- 3-col cover grid (aspect 2/2.9, radius `16 16 16 5`); items already in library get dark glass badge `✓ THƯ VIỆN` top-left + could dim slightly. Infinite scroll.
- FAB `Bộ lọc` (gradient) → source filter sheet (genre/status/sort — standard Mihon filter sheet, restyled).

### 19 · Di dời (Migrate tab)
- Info card (gradient-tinted): explains migration keeps progress, categories & tracking.
- `NGUỒN CÓ BỘ SƯU TẦM` rows: monogram, name, `12 bộ · nguồn lỗi thời`, chevron.
- Flow note (dashed border card): `Chọn bộ → chọn nguồn đích → khớp tự động → xác nhận`.

### 20 · Chi tiết Manga
Same skeleton as 02 with: pink accent, chips `Đang ra / TruyenViet / 43 chương`, CTA `Đọc tiếp · Ch. 42` (`auto_stories`), **tracking badge**: `sync` button has a `2` gradient bubble (number of linked trackers), chapter rows use `Ch. n` + name + `sub` (`đọc dở trang 12`, `đã tải`), tab `43 chương / Tương tự`.

### 21 · Sheet Theo dõi (`presentation.track`)
- Opens over detail (background blurred 3 px, 45% opacity + dark scrim). Sheet radius `28 28 0 0`, grab handle.
- Header `Theo dõi` + link `Quản lý dịch vụ`.
- **Linked service card** (AniList, violet-outlined): logo tile `AL`, `✓ Đã liên kết · <entry>` in teal, `open_in_new`; grid of tappable value cells (radius 13): TRẠNG THÁI `Đang đọc ▾`, TIẾN ĐỘ `42 / — ▾`, ĐIỂM `8.5 ▾` (gold), BẮT ĐẦU `02/03/2026`, KẾT THÚC `—`. Each opens its picker dialog.
- **Unlinked cards**: MyAnimeList → gradient button `Liên kết` (opens search-match flow); Kitsu (not logged in) → glass button `Đăng nhập`.

### 22 · Chi tiết Novel
Same skeleton as 20 with teal accent: portrait cover 118×170 left + info right (chips `Đang ra / NovelNhà`, title, `Tsukino Hane · minh hoạ Rin`, `48 chương · ~6 phút/chương`, star 4.8, `AniList ✓`), CTA `Đọc tiếp · Ch. 12` (teal gradient, **dark text `#0B1512`**), tabs `48 chương / Minh hoạ` (illustrations gallery), chapter rows with reading % subs.

---

## Interactions & Behavior

- **Transitions**: screen pushes = standard Compose slide/fade; bottom sheets slide up 250–300 ms `FastOutSlowIn`; chrome show/hide in readers/player = 200 ms fade+slide; mode switch = accent colors crossfade ~300 ms, segment thumb slides.
- **Press states**: glass rows/chips brighten bg to `rgba(255,255,255,.10)`; gradient buttons scale 0.97 + reduce glow.
- **Aurora blobs** drift continuously (9–12 s loops). Respect reduced-motion: freeze blobs.
- **Long-press**: library tile & updates row → multi-select mode (action bar as in 05); reader page → save/share dialog.
- **Pull-to-refresh**: library (update category), updates (update library), detail (refresh from source).
- **Swipe on rows** (parity with Mihon prefs): chapter/episode row swipe = mark read / download (configurable in Cài đặt → Thư viện).
- **Downloads**: per-item states not-downloaded → queued → downloading (progress) → downloaded (teal ✓). Queue supports pause-all/resume-all, per-item pause/cancel, drag-reorder.
- **Auto-next** (player): after credits, 5 s countdown card; `Hủy` aborts. `Bỏ qua intro` appears when intro markers known.
- **Calendar bells**: toggle per-release notification; fires local notification at air time.
- **Empty / loading / error states** (not mocked — build in Kotori style): loading = glass skeleton shimmer in row/tile geometry; empty = centered aurora blob + Unbounded label + hint (e.g. Library empty: `Thư viện trống — Thêm từ tab Duyệt`); error = red-tinted glass card + `Thử lại` gradient button; WebView/Cloudflare intercept as in Mihon.

## State Management

Reuse Mihon domain/data layers, extended:
- `MediaType` enum `MANGA | ANIME | NOVEL` on entries, sources, extensions.
- Global UI state: `activeMode` (persisted preference; drives accent theme + library filter), `downloadedOnly`, `incognito`.
- Per entry: favourite, categories[], per-unit progress (page index / seconds watched / scroll %), downloaded units, tracking bindings (service, status, progress, score, dates).
- Player state: position (persist for resume + hero card), speed, quality, subtitle track, skip-intro markers.
- Novel reader prefs: fontSize (12–28), fontFamily, theme (white/sepia/dark/black), lineSpacing.
- Calendar: per-release notify flags. Stats derive from history + library + tracking.

## Design Tokens — quick sheet

```
bg.base       #14101F      text.primary  #F3EFFB     danger   #FB7185
bg.player     #100D18      text.second   #A79FC0     warning  #FCD34D
bg.readerM    #0B0910      text.muted    #8D84AC     success  #5EEAD4
bg.sheet      rgba(24,19,38,.97)  text.faint #6E6590  star     #FDE68A
paper.sepia   #F3EAD8 (ink #33302A, accent #0D9488)
glass.bg      rgba(255,255,255,.05–.07)   glass.border rgba(255,255,255,.09–.14)
blur          10–20px      nav.shadow    0 12px 34px rgba(0,0,0,.45)
accent.anime  #8B5CF6→#C084FC (light #C4B5FD)   cta.anime #8B5CF6→#F472B6
accent.manga  #EC4899→#F472B6 (light #F9A8D4)
accent.novel  #14B8A6→#5EEAD4 (light #5EEAD4, on-accent #0B1512)
radius        row 18/18/18/6 · hero 26/26/26/8 · tile 24+one 8 · cta 20/8/20/20 · chip 13–16 · sheet 28/28/0/0
fonts         Unbounded (display) · Be Vietnam Pro (UI) · Literata (novel) · Material Symbols Rounded (icons)
```

## Assets

- **No bitmap assets shipped.** All covers/frames in mocks are gradient stand-ins or user drop-zones — at runtime load real art from sources via Coil.
- Fonts: bundle Unbounded, Be Vietnam Pro, Literata (Google Fonts, OFL) or use downloadable fonts. Icons: Material Symbols Rounded (or `androidx.compose.material.icons` rounded set; keep names — they match `Icons.Rounded.*` closely).
- App icon suggestion: gradient `K` tile with kotori-corner (as on screen 12).

## Files

- `Kotori Concepts.dc.html` — all 22 screens on one canvas (open in browser; sections newest-first: Lượt 3 = screens 12–22, Lượt 2 = 04–11, Lượt 1 = 01–03 in chosen style 1B).
- `support.js` — mock runtime (ignore).
- `android-frame.jsx` — phone bezel for presentation (ignore).
- `image-slot.js` — drop-zone placeholder component (ignore; represents runtime images).

## Implementation order (suggested)

1. Theme layer: colors/typography/shapes + mode-accent system + aurora background composable + glass card/chip/button/nav components.
2. Restyle existing Mihon screens (01, 04–09, 11–21) with new components.
3. New domain: `MediaType`, anime/novel source APIs in extension contract.
4. New features: video player (03), novel reader (10, 22), calendar upgrades (11).
