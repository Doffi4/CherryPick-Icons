# Liquid and desktop designer

The user chose a configurable grid with native icon/widget dragging instead of free positioning of the dock and search bar.

Liquid keeps its sharp refracted backdrop, with a faint veil and bloom from bright backdrop samples. Frosted keeps its existing blur. Shared, scoped and lifted materials must agree; CPU and GPU paths keep the same finish.

The desktop menu launches from CherryPick, inside the actual launcher, and offers grid dimensions and the native editing state. Use the launcher's layout migration rather than writing icon/widget database records. Requests are one-shot and restricted to CherryPick and the scoped launcher. Fail closed when native entry points are missing. Applying a grid requires an explicit in-menu action explaining that items may move. Do not automatically change the user's grid during QA.

Native migration deletes overflow pages/items. Before offering confirmation, run its read-only layout calculation on a worker and reject targets with any unplaced pages. Reject a changed source grid or a busy/loading launcher at application time. These private APIs are version-specific; failure must leave the layout untouched.

Alternatives: free-positioned overlays would detach hit areas and persistence from the launcher; a separate layout database duplicates native state. Native migration and dragging are the selected approach. Existing placements remain owned by the launcher. Grid migration is not a layout backup or an undo operation.

Validation: unit tests for optics and grid limits; debug/release compilation and lint; open menu on the connected OnePlus 13 without applying a new grid. Other launcher versions remain unverified.
