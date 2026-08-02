# Current Plan: Publish Latest Kotori GitHub Release

## Consulted Skills

- Codex `github:yeet`
- ECC `android-clean-architecture`
- ECC `deployment-patterns`
- ECC `github-ops`

Ledger constraints: preserve `app.mihon`, retain ARM builds for normal Android
devices, retain x86_64 for MuMu, and do not regress the verified software-decoder
path on x86.

## Plan

1. Audit the seven commits ahead of `origin/main`, repository state, signing
   configuration, and existing release/tag convention.
2. Commit only these Spec-Kit release records, yielding the next commit-count
   version (`0.20.1-115` after the HTTPS review fix).
3. Run debug unit tests and build the signed release APK variants. Fix and retest
   any failures before proceeding.
4. Inspect APK metadata, signatures, ABI contents, filenames, and hashes; select
   the universal/ARM-capable artifact and the separate x86_64 artifact.
5. Review the outgoing commits and staged release records for secrets, generated
   files, compatibility risks, and HIGH/CRITICAL findings.
6. Push `main`, create and push tag `v0.20.1-115`, and publish the GitHub release
   with Vietnamese notes and both verified APKs.
7. Verify the remote branch SHA, tag SHA, release URL, assets, and final CI state.
