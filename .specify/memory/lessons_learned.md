# AI Memory Ledger: Lessons Learned & Pitfalls

<!-- [VIBECODER-LEDGER-VERSION: v1.1.0] -->

> **CRITICAL STANDARD:** ALL AI models MUST read this ledger before coding.
> Append learnings here autonomously after resolving bugs to prevent circular regression.

## Verified Constraints & Past Fixes

### 1. Baseline Surgical Rule
- **Mistake to Avoid:** Rewiring adjacent working code while trying to fix an isolated bug.
- **Enforced Solution:** Strictly isolate target lines. Verify localized behavior before modifying.

