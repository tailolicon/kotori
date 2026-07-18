# AGENTS.md — Kotori

Kotori is a **Mihon 0.20.1 fork** (application id `app.mihon.dev`, private repo
`github.com/tailolicon/kotori`) with integrated **Aniyomi** anime support, **built-in novel
sources**, and an "Aurora Glass" redesign. Kotlin + Jetpack Compose. Content runs on three stacks —
Manga, Anime, and Novel (novels ride the manga stack; a source is a novel when it implements
`NovelSource`).

---

## 1. Git discipline — READ THIS FIRST

**Several agent sessions and a background checkpoint mechanism operate on this one working tree, and
that mechanism periodically runs `git reset`, which silently discards *uncommitted* changes.**
Committed work is safe; anything left uncommitted between turns can vanish. This is not
hypothetical — it is how an R8 keep-rule fix was lost and shipped broken in a release.

Rules, in priority order:

1. **Commit the moment a change compiles and is verified.** Small, atomic commits — never batch a
   pile of edits to "commit at the end." Between turns the tree must be clean.
2. **Push immediately after every commit** (`git push origin <branch>`). Pushing backs the work up,
   makes it visible to other sessions, and — critically — makes it eligible for release builds.
3. **Before starting work:** `git fetch origin`, then reconcile with `origin/main`
   (`git log --left-right --count HEAD...origin/main`). Never start from a stale HEAD.
4. **If you must pause mid-change,** commit a `wip:` commit rather than leaving the tree dirty.
5. **Never assume another session's uncommitted files are present.** If it isn't committed, treat it
   as gone.

Commit messages: conventional prefix (`feat`/`fix`/`docs`/`chore`/`refactor`), imperative subject,
a body that explains *why*. End with:
`Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
Commit or push only what the task calls for; branch first if you are on the default branch and the
work is substantial.

## 2. Parallel sessions

- `origin/main` is the source of truth. Fetch before building anything for release.
- Small fixes may commit straight to `main` — but push right away. For larger, multi-commit work,
  use a short-lived branch and fast-forward / PR into `main`.
- A build only contains what is on disk at build time: commits that are ancestors of the HEAD you
  build from, plus any uncommitted working-tree files. Work from another session that isn't yet an
  ancestor of your HEAD is **not** in your build — so always fetch + verify sync before releasing.

## 3. Builds & releases

- **Windows JVM:** set a short ASCII temp dir before gradlew, or the build fails:
  `export TMP=C:/Windows/Temp TEMP=C:/Windows/Temp`.
- **Version name is commit-count driven:** `0.20.1-<git rev-list --count HEAD>`. A release is only
  meaningful from a fully-synced HEAD.
- **Before any release build:** `git fetch origin` → confirm local `HEAD == origin/main` → build.
  Never cut a release from a HEAD behind origin, or the released APK silently misses commits.
- **R8 / minification:** the `release`/`update` build types are minified. Keep-rules live in
  `app/proguard-rules.pro`. Native (`is.xyz.mpv.MPVLib` JNI callbacks) and reflection-accessed
  members (protobuf `GeneratedMessageLite` fields for NewPipe) **must** be `-keep`'d there or R8
  strips them and release builds crash where debug builds work. After touching proguard, verify the
  kept classes survive.
- `./gradlew :app:assembleUpdate` produces per-ABI + universal APKs. Ship **arm64-v8a** for phones,
  **x86_64** for emulators. Release via `gh release create` (run in the foreground — background
  uploads have failed and left empty drafts).

## 4. Verify before claiming done

- **"It compiles" ≠ "it works."** For anything user-facing, run it on a device/emulator over `adb`
  and confirm the actual behavior before reporting success. Reproduce a bug before fixing it and
  re-verify the fix on-device.
- **Report honestly.** If something is unverified, say so plainly. Don't claim a fix works from a
  green compile alone.

## 5. Style & scope

- Match the surrounding code's style, naming, and idiom. Keep changes surgical and scoped to the
  request — no opportunistic rewiring.
- Prefer the dedicated file/search tools over shell `grep`/`cat`.
- MIUI/HyperOS quirks (Xiaomi test device): needs `com.android.permission.GET_INSTALLED_APPS` for
  extension visibility; blocks `adb shell input` unless "USB debugging (Security settings)" is on;
  `pm grant`/`revoke` silently no-op for undeclared permissions.

## 6. Project memory (optional, lightweight)

- If `.specify/memory/lessons_learned.md` exists, skim it before non-trivial work and append a
  one-line lesson after a hard-won root-cause fix. Keep it short; don't let it bloat.
- When spawning subagents, route mechanical work (build/lint/format fixes) to cheaper models and
  design/debugging to stronger ones. Advisory, not mandatory — pass `model` on `Agent()` when it
  clearly helps, otherwise inherit the session model.

## Anti-patterns (do not do these)

- ❌ Auto-`git stash`/`git-checkpoint`/`reset` "backups" — they discard uncommitted work. Commit instead.
- ❌ Arbitrary "max N files per session" caps — change what the task needs.
- ❌ Mandatory spec/plan/graph ceremony that dirties the tree before every edit.
- ❌ Releasing without fetching and confirming HEAD matches origin.
