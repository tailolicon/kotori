# Functional Specification: Commit DocLN Native Reader Delivery

## Objective
Create one local Git commit containing the remaining reviewed changes for DocLN native text and illustration rendering.

## Scope
- Include the native reader block parsing, trusted image URL validation, Coil illustration rendering, and failure placeholder.
- Include the current Spec-Kit specification and delivery plan.
- Exclude unrelated files, generated APKs, MuMu evidence, and checkpoint stashes.
- Do not push.

## Acceptance Criteria
- The staged diff contains exactly the intended remaining files.
- `git diff --check` passes before staging and `git diff --cached --check` passes after staging.
- The commit succeeds with a descriptive conventional commit message.
- The working tree is clean after the commit.
