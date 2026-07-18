# AI Memory Ledger: Lessons Learned & Pitfalls

<!-- [VIBECODER-LEDGER-VERSION: v1.1.0] -->

> **CRITICAL STANDARD:** ALL AI models MUST read this ledger before coding.
> Append learnings here autonomously after resolving bugs to prevent circular regression.

## Verified Constraints & Past Fixes

### 1. Baseline Surgical Rule
- **Mistake to Avoid:** Rewiring adjacent working code while trying to fix an isolated bug.
- **Enforced Solution:** Strictly isolate target lines. Verify localized behavior before modifying.

### 2. Novel Reader Chapter Boundaries
- **Mistake to Avoid:** Treating prose progress as passive state while reusing the previous chapter's scroll position; this prevents normal transitions or can cascade across chapters.
- **Enforced Solution:** Resolve the next chapter with the canonical ascending chapter comparator, guard completion by chapter ID, and create a fresh scroll state whenever chapter content changes.

### 3. Seamless Novel Chapter Navigation
- **Mistake to Avoid:** Replacing reader state with a loading screen at a chapter boundary or storing reader-only bookmark state; both break continuity and desynchronize the chapter list.
- **Enforced Solution:** Keep the outgoing chapter visible while an adjacent chapter loads, atomically guard navigation, animate immutable chapter snapshots in the gesture direction, and persist bookmarks only through the canonical chapter record.
