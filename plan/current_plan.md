# Current Plan

1. Compare `HEAD` with `v0.20.1-34`, inspect prior release metadata, and confirm `v0.20.1-44` as the next commit-count tag.
2. Commit this release specification and plan so the tag points to a clean, reproducible revision.
3. Run unit tests and build minified release APKs for `arm64-v8a` and `x86_64`.
4. Verify APK package/version, ABI isolation, signing certificate, zip alignment, sizes, and SHA-256 hashes.
5. Install the x86_64 release APK on MuMu and smoke-test app startup plus the native Novel reader.
6. Review the release diff and evidence; stop if any HIGH/CRITICAL issue or failing check remains.
7. Push `main`, create and push `v0.20.1-44`, then publish and read back the GitHub Release and assets.
