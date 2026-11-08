# Desktop designer usability and safe application

**Goal:** show the native calculated layout before applying and report the actual native migration outcome.
**Architecture:** keep native persistence and drag handling; render anonymous cell footprints from the native preview. Use a scoped transaction guard during designer-triggered migration to reject item deletion or unknown writes before the batch runs. Suppress empty-folder cleanup for both preview and designer application. Preserve regular native behavior outside those operations.

- [x] Test preview bounds/overlap validation and mutation policy with JVM tests.
- [x] Display pages, icon/widget footprints, grid dimensions and checking/error states. Disable Apply until the latest selected grid has a successful preview; discard stale worker results.
- [x] Guard actual native migration batches, surface the native completion result, recreate the launcher on failed migration to discard transient grid dimensions, and enter native edit mode after success.
- [x] Compile, test and lint; install with the original signing key. Test preview, page switching and cancellation. After the user's explicit approval, apply 4×6 → 5×6 and verify persistence and record preservation.

No second layout database, no new dependencies, no unrelated glass changes. Private ColorOS 17 APIs remain a compatibility constraint. A backup is not an automatic undo.

Review: native provider can return null after a failed transaction; scoped after-hook converts null/incomplete responses into failure before native code persists the grid dimensions. Device reflection differs from JADX's generated Java field names; use real dex fields and hook the erased completion method.

Found an existing SIGBUS in LsposedScopeStatus.read: overlapping Activity readers truncated the same mmap-backed temporary database. Serialized that read; subsequent module entries succeeded.

## Final validation

- Release build and all 26 JVM tests pass. Lint: 0 errors, 29 existing warnings. `git diff --check` passes.
- Native previews for 4×6 and 5×6 show four pages. Navigation and recalculation work. Apply is disabled for an unchanged grid.
- The user explicitly authorized applying 5×6 and restarting the launcher. A fresh quiescent backup is at ignored `reference/liquid-desktop-phone/before-live-5x6.tar`.
- Initial application failed before invoking migration due to an obfuscated screen-URI field differing on the device. Replaced that lookup with LauncherModeManager.getScreenTableName(). The failure path reloaded the untouched 4×6 layout.
- Authorized live 4×6 → 5×6 migration succeeded; native completion callback reported success and entered editing mode.
- All 112 record identities, item types and folder ownership were preserved; 20 positions/sizes changed. Zero records removed.
- After force-stopping/restarting the launcher, saved grid remains 5×6. Reopened constructor shows 5×6, four pages and disabled Apply.
- Other launcher versions and stress testing remain unverified. The preview is a cell-footprint diagram; dragging continues to use the native editor.
