# Functional Specification: Match the Tablet Browse Mockup

## Goal

Finish the incomplete Kotori tablet Browse screen without regressing the phone
flow or the fixes already committed in `e150da7`.

## Required Behaviour

1. Match the landscape T6 structure in `Kotori Prototype.dc.html`:
   - fixed ~300dp left pane with Browse title, Sources / Extensions / Migrate
     segment, then the pane content;
   - flexible right pane with the global-search field at the top;
   - the seasonal-calendar action sits beside search while search is inactive.
2. On the Sources tab, the left pane renders the canonical filtered source list:
   enabled languages and disabled-source preferences remain effective. Global
   search chips/query still apply only to the right search pane.
3. The Sources tab right pane defaults to extension updates pending.
4. Focusing or tapping the global-search field replaces the pending-update pane
   in place with the previous global-search UI. It must not navigate away.
5. Inline search keeps the left source list visible and provides the three
   filters shown in the supplied mockup: All sources, Pinned, In library.
6. Clearing/backing out of inline search restores pending updates and the
   seasonal-calendar action.
7. Expose the Sources filter on Sources and the Extensions filter on Extensions;
   each filter changes only its own canonical data set.
8. Restore the missing seasonal-calendar action beside search where specified.
9. Preserve the Popular and Latest source-feed actions and ensure navigation
   from the tablet Browse screen still reaches a source feed that exposes them.
10. Extensions and Migrate segments must remain selectable on tablet.
11. Phone Browse behaviour remains unchanged.

## Quality Gates

- Compile the affected Kotlin module and run its relevant tests.
- Install the newest debug APK on MuMu and visually compare the default and
  active-search states against the supplied screenshots and HTML mockup.
- Review state ownership, empty/loading states, navigation, and tablet/phone
  branching; leave no HIGH or CRITICAL finding unresolved.
