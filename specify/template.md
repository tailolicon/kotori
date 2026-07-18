# Functional Specification: Publish Kotori v0.20.1-51

## Goal

Publish the completed Novel Fever source correction and Light Novel reader
parity work to the `tailolicon/kotori` GitHub repository as the next Kotori
release.

## Scope

1. Commit the Novel Fever API migration, Novel-only search/filter isolation,
   description cleanup, and native reader improvements currently verified in
   the worktree.
2. Include the associated Spec-Kit plan and lessons ledger.
3. Exclude the unrelated local `AGENTS.md` instruction change.
4. Push the commit directly to the existing `main` branch, matching this
   repository's current release delivery convention.
5. Create and push tag `v0.20.1-51`, derived from version `0.20.1` and the
   post-commit Git commit count.
6. Publish a GitHub release named `Kotori v0.20.1-51` with Vietnamese release
   notes and attach the verified Android APKs.

## Quality Gates

- Confirm the staged file list contains no unrelated local changes or secrets.
- Re-run Kotlin compilation, all debug unit tests, and the release APK build
  required for distributable artifacts.
- Verify APK signatures/metadata sufficiently to prevent uploading a corrupt or
  debug-incompatible artifact.
- Confirm `origin/main`, the release tag, and the GitHub release all resolve to
  the exact committed SHA.
- If GitHub authentication or signing blocks publishing, complete every safe
  local step and report the exact external blocker without inventing success.
