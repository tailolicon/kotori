# Current Plan

1. Inspect release signing/build configuration and preserve the working tree.
2. Remove the obsolete app-level duplicate, run unit tests, and build the minified release APK set.
3. Verify version, ABI contents, signing certificate, hashes, and file sizes.
4. Commit/push the reproducible build fix, tag `v0.20.1-29`, and publish universal/x86_64 APKs.
5. Read back GitHub Release metadata and perform a final review.
