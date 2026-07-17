# Functional Specification: Push Kotori APK Build Revision

## Objective
Push the existing local commits for the completed DocLN native reader and APK build preparation to `origin/main`.

## Scope
- Preserve the already-built arm64 and x86_64 APKs as local artifacts.
- Push the reviewed local commits to the existing `main` branch.
- Do not create or push a tag.
- Do not create a GitHub Release or upload APK assets.
- Do not reformat unrelated files.

## Acceptance Criteria
- The working tree is clean before push.
- `origin/main` advances to the local `HEAD`.
- No tag or GitHub Release is created.
