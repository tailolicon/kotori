# Kotori in-app updates

Kotori reads a small `update.json` feed instead of requiring the user to browse and download an APK
manually. The app selects the APK matching the device ABI, compares the manifest `versionCode` with
the installed package, downloads the APK in the existing updater UI, verifies its byte length and
SHA-256, then opens Android's package installer.

## MuMu or an ADB-connected phone

Run from the repository root:

```powershell
.\tools\publish-kotori-update.ps1 -Changelog "Mô tả thay đổi"
```

The script assigns a monotonic version code, builds the `update` variant, generates the feed under
`.update-feed`, recreates the localhost tunnel through `mumu-cli`, and serves the files. Keep the window
open. In Kotori, open **More → About → Check for updates**, then accept the update.

MuMu player index `0` and the standard global installation path are used by default. Override them
with `-MuMuVmIndex` or `-MuMuCliPath` when needed. If MuMu CLI is unavailable, the script falls back to
the system ADB for a connected physical phone.

For a quicker unminified development cycle, add `-Variant Debug`. The default `Update` variant is the
smaller production-style APK.

Official builds read `https://github.com/tailolicon/kotori/releases/latest/download/update.json`, so
the user checks and installs a matching APK entirely inside Kotori. The local publishing script
overrides that URL with `http://127.0.0.1:8765/update.json` for MuMu testing.

## Hosted feed

Upload the generated files to any static HTTPS host and build Kotori with that manifest URL:

```powershell
.\gradlew.bat assembleUpdate -Pkotori-update-url=https://updates.example.com/kotori/update.json
```

Asset URLs in the manifest may be relative to `update.json`, so moving the entire directory to a static
host requires no edits. A hosted feed does not need ADB or GitHub.

`KOTORI_UPDATE_URL` and `KOTORI_VERSION_CODE` environment variables are equivalent to the two Gradle
properties. Every published build must use a greater version code. The publishing script derives a
monotonic code from the Git commit count, existing build metadata, and Kotori installations on
connected ADB devices, then advances past the greatest value it finds.

For an official release, build the signed release APKs and matching feed together:

```powershell
.\tools\publish-kotori-update.ps1 `
  -Variant Release `
  -UpdateUrl "https://github.com/tailolicon/kotori/releases/latest/download/update.json" `
  -ReleaseUrl "https://github.com/tailolicon/kotori/releases/tag/v1.0.6" `
  -AssetSuffix "v1.0.6" `
  -Changelog "Nội dung bản phát hành" `
  -SkipAdbReverse -NoServe
```
