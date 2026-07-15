# Functional Specification: Complete v0.20.1-29 APK Release

## Objective
Publish reproducible installable release APKs after removing the duplicate `FileExtensionsKt` class that blocks R8.

## Requirements
- Build the minified `release` variant, not `debug`.
- Publish the universal APK and MuMu-compatible x86_64 APK to GitHub Release `v0.20.1-29`.
- Verify package/version, APK signature, checksums, asset states, and exact sizes.
- Remove the misleading debug APK asset after verified release assets are present.
- Do not commit Spec-Kit metadata or unrelated workspace changes.

## Acceptance Criteria
- Gradle release build completes successfully.
- APK Analyzer/signing verification confirms an installable signed release artifact.
- GitHub Release exposes both universal and x86_64 release APKs with verified metadata.
