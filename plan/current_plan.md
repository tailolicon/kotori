# Current Plan: Match the Tablet Browse Mockup

## Consulted Skills

- ECC `compose-multiplatform-patterns`
- ECC `kotlin-patterns`

Ledger constraints: make a surgical presentation-layer change, preserve phone
behaviour, and avoid rewiring the existing source, extension, and calendar
screens. `graphify` is unavailable in this environment, so dependencies were
hydrated directly from the relevant Compose hosts, models, and navigation routes.

## Plan

1. Keep the existing phone `TabbedScreen` flow unchanged and alter only the
   tablet Browse host.
2. Reproduce the prototype hierarchy inside the tablet host: a 300dp left pane
   containing Browse title, segment, and pane content; a flexible right pane
   containing the global-search field and contextual action/content.
3. Keep the canonical Sources tab permanently mounted on the left so its language
   and disabled-source filters remain reactive. Global-search query/chips still
   belong exclusively to the right pane.
4. Replace right-pane pending updates in place when the search field gains focus;
   embed the canonical manga/anime global-search model and result shelves rather
   than pushing a new Voyager screen. Back/close restores pending updates and
   Seasonal Calendar.
5. Keep Extensions and Migrate selectable with their existing canonical content;
   show the Sources filter on Sources and the Extensions filter on Extensions.
6. Compile/test, fix and retest until green; install the newest APK on MuMu and
   verify both media paths and all required actions.
7. Review navigation, state duplication, empty/loading states, localization,
   accessibility, and regressions; resolve every HIGH/CRITICAL issue.
