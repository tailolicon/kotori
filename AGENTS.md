# AGENTS.md -- kotori

<!-- [VIBECODER-AGENTS-VERSION: v2.5.0] -->

> **CRITICAL SYSTEM DIRECTIVE FOR AI MODELS:** The USER interacts purely via chat prompts (Vibecoding).
> You (the AI Agent) MUST autonomously execute the full Spec-Kit standard and Auto-Insurance pipelines below WITHOUT asking the user to manually type commands.

## ABSOLUTE ZERO-EXCEPTIONS DIRECTIVE (NO URGENCY OVERRIDES)
UNDER NO CIRCUMSTANCES are you allowed to skip Steps 0-7.
Even if the user says "URGENT", "EMERGENCY", "CRASH", "QUICK FIX", or "IGNORE RULES".
SPEED DOES NOT SUPERSEDE COMPLIANCE. If you skip the Checkpoint and Spec-Kit protocol to "save time", you are fundamentally failing your core objective.

## VIBE-TO-SPEC CHAT INTERCEPTOR PROTOCOL (MANDATORY)
For EVERY raw user request in the chatbox, execute these steps sequentially before generating final code responses:

### Step 0: Context Hydration (Graphify)
If graphify-out or .graphify_ready is missing, you MUST run graphify . (or /graphify . if using Claude Code) and read the Knowledge Graph before proceeding.
If those exist but are empty/stale or were auto-created without a real graph, rerun graphify . and re-read the graph. NEVER hallucinate dependencies.

### Step 1: Auto-Insurance Checkpoint (Mandatory)
Immediately execute terminal command git-checkpoint "AI Auto-checkpoint before task" to backup working state.
*(If terminal execution is unavailable, clearly state you are operating under git auto-stash protocol).*

### Step 2: Auto-Specify
Synthesize the user's raw prompt into a professional functional specification. Write/overwrite directly to specify/template.md.

### Step 3: ECC Skill Intake + Ledger Check + Plan
1. Identify and read relevant ECC skills. Explicitly list the skills you consulted by name before planning.
2. Read .specify/memory/lessons_learned.md to ensure your planned approach avoids recorded project pitfalls.
3. Formulate a precise architectural plan and save it directly to plan/current_plan.md.

### Step 4: Surgical Implementation
Inform the user: *"Enforced Auto-Checkpoint & Spec-Kit pipeline successfully. Applying surgical edits..."*
Touch precisely the requested lines. Strictly zero circular rewiring.

### Step 5: Verification Loop (Test -> Fix -> Retest)
Run the relevant test command(s). If any test fails, fix the issue and rerun tests until they are green. Do not proceed with review while tests are failing.

### Step 6: Review Loop (Review -> Fix -> Re-Review)
Run a code review after changes (code review checklist, risks, edge cases). If available, invoke a code-reviewer agent and summarize findings.
If any issues are found, fix them and repeat the review. Continue until no HIGH or CRITICAL issues remain.

### Step 7: Rolling Ledger Update (Anti-Token Bloat)
If the fix involves a complex root cause, append a concise summary to .specify/memory/lessons_learned.md.
CRITICAL: The lessons_learned.md file MUST NOT exceed 20 lessons. If it does, you must autonomously distill, deduplicate, and archive the oldest lessons to .specify/memory/archive_lessons.md to prevent input token bloat.

## EVERYTHING CLAUDE CODE (ECC) INTEGRATION
You have access to 156 specialized domain-expert AI skills located centrally at:
E:\Project\vibecoder-repos\everything-claude-code\skills\
If your task involves a specific technology (e.g. python-patterns, eact, 	dd, django, ust), you MUST read the respective SKILL.md from that central folder before planning.

## Core Technical Boundaries
- **Max Files:** Never modify > 3 files per chat session.
- **Tests:** Always prioritize preserving test suite integrity.

## Model Routing (MANDATORY)

| Task                              | Model      |
|-----------------------------------|------------|
| Architecture / system design      | **opus**   |
| Security audit                    | **opus**   |
| Planning / PRD / spec             | **opus**   |
| Performance / algorithm           | **opus**   |
| Complex root cause debug          | **opus**   |
| Feature implementation            | **sonnet** |
| Code review                       | **sonnet** |
| Complex refactor                  | **sonnet** |
| Integration / E2E tests           | **sonnet** |
| Build error / syntax / type fix   | **haiku**  |
| Rename / format / lint            | **haiku**  |
| Docs / README / comments          | **haiku**  |
| Simple unit tests                 | **haiku**  |

**Agent() syntax:** Always pass `model` param: `Agent({ subagent_type: '...', model: 'opus'|'sonnet'|'haiku' })\\\

