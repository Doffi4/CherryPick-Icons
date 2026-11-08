# Shared desktop sizing

The user chose global controls: enlarge icons/folders, reduce unused space around the page indicator, lower the workspace towards the dock, and reduce supported widget exterior padding. Keep the current grid and item database unchanged.

- [x] Test bounded icon sizing, padding scaling and downward alignment.
- [x] Add portable, validated sizing preferences to the existing module settings card and provider.
- [x] Hook native profile construction and inset updates so native layout and hit regions follow the dimensions; retain system/navigation insets and indicator touch area. Defaults preserve native behavior.
- [x] Build, lint, review and test on the supplied phone; compare layout identities/positions and verify sizing survives restart without compounding.

Use the existing restart action to apply native geometry changes. Do not add a second layout store or scale widget content beyond its supported cell bounds. Widget padding controls only the exterior margins exposed by the native launcher.

Phone baseline at this iteration was 4×6, manually changed since the previous grid-migration test. Preserved that grid. A fresh layout backup is at ignored `reference/liquid-desktop-phone/before-sizing.tar`.

Runtime: selected 130% icon/folder size, 25% indicator spacing, 50% supported widget padding and 100% bottom bias through the module UI. Native icon calculation changed 224→291 px; workspace shifted down roughly 150 px. Visual check found no clipping at this setting. Repeated restart kept 291 px. All 112 database records, cell positions and spans match the baseline exactly.

Review caught native updateInsets overwriting the workspace position; apply the adjustment after that native recalculation too. Other launcher versions, landscape custom geometry and every third-party widget are unverified; landscape keeps native geometry. Existing 2×2 folders/widgets remain bounded by their allocated cells.

Final signed release installed. All 29 JVM tests pass; release lint reports 0 errors and 29 pre-existing warnings. Native resource-name lookup is cached and deliberately used because another APK owns the IDs. Final settings remain 130/25/50/100; the original 4×6 grid is unchanged.
