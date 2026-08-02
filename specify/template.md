# Functional Specification: Publish Latest Kotori GitHub Release

## Goal

Build, verify, and publish the latest committed Kotori application state to the
`tailolicon/kotori` GitHub repository as the next stable release.

## Scope

1. Publish the seven local WebDAV sync commits currently ahead of `origin/main`.
2. Preserve the Android package identity `app.mihon` and update compatibility.
3. Build signed release APKs for normal Android phones/tablets and MuMu x86_64.
4. Commit only the Spec-Kit release records created for this request; exclude
   checkpoints, generated files, caches, logs, and design references.
5. Push `main`, create the next version tag from the post-commit count, and
   publish a GitHub release with Vietnamese notes and verified APK assets.

## Quality Gates

- Confirm the complete local/remote commit delta and release version.
- Run debug unit tests and the release build; fix failures and retest until green.
- Verify APK package name, version, ABI coverage, size, and signing metadata.
- Review staged changes for unrelated files, generated artifacts, and secrets.
- Confirm `origin/main`, the tag, and GitHub release resolve to the same commit.
- Do not claim publication if GitHub authentication or signing is unavailable.
