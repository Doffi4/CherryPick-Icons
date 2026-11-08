# Reference glass and enlarged footprints

The user wants the supplied rounded-square, neutral glass reference rather than the current pink circular material. Keep the 4×6 layout and all item identities/positions.

- [x] Regress the enlarged 291px desktop versus 224px source texture classification and native 1200px snapshots.
- [x] Retain bindings through retained-resolution rendering; derive desktop footprints from native displayed icon bounds, not bitmap resolution.
- [x] Back up module preferences, native icon configuration and launcher layout. Use the existing preset-import flow for restrained neutral material; adjust only native custom-icon corner radius while preserving every other configuration bit.
- [x] Build/lint/test, inspect regular/large icons and native context-menu lifting/cancellation on the connected OnePlus 13. Verify item data remains unchanged and deliver the tested APK/preset with remaining visual differences identified.

No copied reference artwork, no replacing the user's wallpaper/widgets, no layout migration or app-data deletion. Device personalization changes are reversible and must be recorded with original values. The reference's glass dock may blur; Liquid icon plates must retain sharp backdrop refraction.

## Validation on 2026-11-08

- OnePlus 13 / ColorOS 17 / launcher 17.3.12: retained material 240×240 at 291×291 logged with a 291×291 binding. Wide clock and tall Play Store plates inspected separately. Code review found no additional binding-scale issue.
- Native shape applied through `com.oplus.uxdesign/.icon.UxIconStyleActivity`, not cache deletion. `key_ux_icon_config` changed from `3479900907244619057` to `3540980977190831409`; all bits outside the 12-bit corner field remain identical. Native UI encodes corner 36 with flag 256 (292). Direct raw-radius writes did not regenerate existing masks.
- Native bottom “Find and ask” pill disabled through launcher settings; page dots now visible. Grid remains 4×6, shared icon size 130%.
- Final preset imported through the module's normal document picker. It changes material/sizing only; native corner style and search-pill visibility are separate launcher settings.
- All 108 `(id,type,container,screen,x,y,spanX,spanY)` records match the pre-change database exactly after restart and context-menu cancellation.
- `testReleaseUnitTest assembleRelease lintRelease --offline`: success; 31 tests, zero failures, lint zero errors / 26 warnings. Signing certificate unchanged.
- Backups, screenshots and device snapshots are under ignored `reference/visual-2026-11-08/`. Public preset is `presets/Reference-Glass.json`.
- Remaining visual difference: native glyph relief is weaker than the supplied reference. Existing weather/search/photo widgets keep their own appearance. Continuous moving drag/refraction, prolonged usage and other launchers were not verified; the attempted moving-drag capture did not establish that state.
