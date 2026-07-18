# Current Plan: Commit and Release Kotori v0.20.1-51

Consulted skills:

- `github:yeet`
- `git-workflow`
- `github-ops`
- `deployment-patterns`
- `verification-loop`

Ledger constraints: preserve the verified Novel Fever source identity, canonical
chapter ordering/bookmarks, and seamless reader state; do not mix unrelated
local instruction changes into the release.

## Plan

1. Audit the complete diff and stage only the Novel Fever/Light Novel source,
   reader, search/filter, UI, Spec-Kit, and ledger files.
2. Re-run compile and unit tests. Build the distributable release variants
   using the repository's existing signing configuration; inspect the produced
   APKs before publication.
3. Review the staged diff for scope, secrets, generated files, chapter-source
   compatibility, and release risks. Resolve all HIGH/CRITICAL findings.
4. Commit on `main` with a conventional message covering the full feature/fix
   set, then push `main` to `origin`.
5. Create annotated tag `v0.20.1-51` on the pushed commit and push it.
6. Publish `Kotori v0.20.1-51` on GitHub with concise Vietnamese notes and the
   appropriate APK assets.
7. Verify the remote branch SHA, tag SHA, release URL, assets, and final CI
   state. Keep the unrelated `AGENTS.md` modification local and unstaged.
