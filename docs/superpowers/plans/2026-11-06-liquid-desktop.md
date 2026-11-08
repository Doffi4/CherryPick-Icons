# Liquid and desktop implementation plan

**Goal:** sharp Liquid optics and a native grid/dragging menu.
**Architecture:** existing wallpaper/lens pipeline; one-shot SettingsProvider command opens a launcher-owned dialog. No new dependencies or layout database.
**Spec:** ../specs/2026-11-06-liquid-desktop-design.md

- [x] Test blur-radius selection, highlight response and grid validation.
- [x] Replace Liquid blur scaling in WallpaperHelper and IconMaterialRenderer; add GPU/CPU bloom and veil to the existing lens paths.
- [x] Add a protected one-shot desktop-menu request, native launcher dialog and localized CherryPick entry. Resolve native APIs before enabling application.
- [x] Run unit tests, assemble and lint. Install on the supplied phone and inspect the menu without applying grid changes. Review the final diff.

Risks to check: transparent bloom samples, scoped Frosted materials, lifted lens coordinates, failed/missing native APIs, duplicate/stale commands. Native grid migration can move items and is not reversible by restoring dimensions alone.

## Validation record

- Debug and Release: 24 unit tests each, no failures. Release lint: 0 errors, 29 existing warnings.
- Both SkSL entry points compiled and rendered on the supplied OnePlus 13 GPU using a standalone harness. A white stripe kept its sharp edge (53→255 and 55→255) while the dark side received veil/bloom.
- APK signed with the user-supplied original key, installed with `adb install -r`. Key is ignored by Git.
- Native menu opens and enters TOGGLE_BAR. 5×6 read-only layout preview reports 0 unplaced pages; confirmation cancelled. Actual grid remains 4×6.
- Review found native input loading deletes empty folders and can update migration counters. Preview-only thread-local hooks suppress both; device log confirms 5 cleanup calls skipped. All 112 layout records/positions match the original backup after QA.
- Both desktop provider methods reject the unprivileged shell caller.
- Previous installed APK and a quiescent launcher database/preferences archive are under ignored `reference/liquid-desktop-phone/`.
- Actual grid migration, extreme overflow rejection on a populated launcher, other ROMs and sustained GPU performance remain unverified. Existing dock software fallback still uses a flat backdrop rather than the GPU lens.
