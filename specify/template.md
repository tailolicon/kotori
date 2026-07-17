# Functional Specification: Kotori v0.20.1-44 Release

## Objective
Publish a verified Kotori release containing the native Novel reader and DocLN text/illustration support added since `v0.20.1-34`.

## Scope
- Create one release-preparation commit and tag its resulting commit-count version as `v0.20.1-44`.
- Build minified, resource-shrunk `release` APKs for `arm64-v8a` and `x86_64`.
- Preserve package identity and signing compatibility with the previous Kotori release.
- Verify package metadata, ABI contents, signature, alignment, hashes, and MuMu installation.
- Push `main` and the tag, then publish a non-draft GitHub Release with both APK assets and concise Vietnamese notes.

## Acceptance Criteria
- Relevant unit tests and release builds pass.
- The x86_64 release APK installs and launches on MuMu.
- Both APKs are signed, aligned, contain only their intended ABI, and have recorded SHA-256 hashes.
- Git tag `v0.20.1-44` resolves to the reviewed release commit.
- GitHub Release `Kotori v0.20.1-44` is published with exactly the intended APK assets.
