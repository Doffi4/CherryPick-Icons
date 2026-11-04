# CherryPick Icons 0.2.35

Release artwork and documentation:

- The launcher icon uses the author's supplied cherry artwork. The foreground is inset to retain the full drawing under adaptive icon masks; the same artwork extends its background.
- Kostubet Topics is included alongside the existing acknowledgements in settings and both README languages.
- A bilingual installation guide covers root access, LSPosed scope, Flux, module activation, launcher restart and diagnostics before reporting bugs.

First beta release:

- Fresh-install detection now runs before photo defaults populate preferences. New installations and absent blur-mode keys use Live; explicitly saved modes remain respected.
- Refraction edge width is visible and applied without developer mode for icons, independent folders and photos. Saved width values and reset defaults remain unchanged.

Release preparation:

- Settings includes a localized Support the project button opening the CloudTips page in an external application.
- Settings writes are restricted to the module UID; native icon appearance reports additionally allow the supported launcher packages. Reading renderer settings stays available to the launcher.
- Source packaging excludes local SDK paths, signing keys, build output and reference checkouts. Builds without the local testing key use Gradle's generated debug key.
- Three outdated or ambiguous comments about wallpaper capture, log filtering and mini-icon pacing now describe the actual behavior.
- The bilingual project README identifies the beta status, ColorOS 17 device testing and possible ColorOS 16 compatibility issues, and credits GPT 6.1 Sol as requested by the author.
- GPU shader entry points explicitly check Android 13 availability. The unused legacy XML screen, old colors and obsolete localized strings were removed together with unused ViewBinding generation. Lint errors now fail release checks; optional OEM reflection and caught wallpaper-permission fallbacks have narrowly documented annotations.

Launcher restart / ColorOS 16 recovery:

- Supplied 0.2.30 diagnostics include a fatal WeakHashMap.values.toList() singleton copy during updateSamplers. That hot path now iterates directly; remaining weak key/map snapshots also copy by iterator without consulting a changing size. Synchronization alone cannot stop garbage collection of weak keys.
- Every visible root pre-draw repairs discarded sample lists and invalidates their owner once, in static and live modes, independently of resume callback timing. This restores retained GPU resources without updating a crop or consuming a material FPS slot. Missing final-plate lists are rebuilt in place so an OEM cached folder preview can retain its outer list.
- Icon recording uses the recording canvas clip, rather than the host's current screen visibility. OEM adjacent-page recording must retain the icon even before its page enters the screen. Ordinary offscreen live refresh and edge throttling remain separate.
- Native animation class selection and launcher transforms are unchanged. Both supplied APK contracts (16.6.20 and modified 17.3.12) are inspected; runtime checks use the installed stock 17.3.9. Remote ColorOS 16 visual confirmation remains necessary.

Validation: ../scratch/launcher-restarts/validation.md.

Material experiments:

- Color-depth quantization has one selected stage: before blur (0), after blur/before refraction (1), after refraction/before the plate overlay (2), or the finished plate (3, the retained default). The native order remains wallpaper/vibrancy → shared blur/tone → lens/colorization → plate tint/volume → foreground logo/contour. Thus “above blur” and “below refraction” are the same boundary. All stages preserve the foreground artwork and mask alpha; 24 bits bypasses quantization.
- Early stages execute in the existing shared wallpaper GPU graph, including independently processed folders. CPU processing follows the same order. Motion fingerprinting is a separate, untouched target. The post-refraction stage finishes the retained background node before the overlay; the final-plate stage retains the previous behavior. Direct native/snapshot rendering and software snapshots support both late stages. Shared effect caches are synchronized across the wallpaper worker and renderer.
- The developer-only iOS haze experiment defaults off. It adds a low-contrast, slightly desaturated, spatially varying optical veil after the lens. Haze does not alter the wallpaper blur radius: zero, the radius switch, scaling and folder overrides behave exactly as without haze. There are no additional per-fragment backdrop lookups. The finish leaves foreground artwork, contour and alpha intact. It can be combined with every quantization stage and works independently of color depth. It is an approximation based on public Apple material descriptions, not Apple's shader implementation.
- Folder-icon blur, disable-edge-optimization and mini-icon 30 FPS controls default on for new installs and resets. Explicit saved choices remain respected. The two scheduling controls also retain their effects when developer controls are hidden. The new optical experiments remain gated by developer mode. Global material FPS and min(global, 30) for miniatures are unchanged.

Validation, GPU/software tests and visual examples: ../scratch/material231/validation.md.

Folder mini-icon pacing correction:

- The global Live FPS limit applies to every blur material (at most 60 FPS), including normal icons, folder plates, photos, direct/snapshot rendering and native animation surfaces. The developer mini-icon switch lowers only small preview samples to min(global FPS, 30). The normal blurred/refracted wallpaper, tint, opacity, contour and glyph are preserved. The old compact path that removed wallpaper and increased plate opacity is deleted.
- Each preview leaf is classified by its local displayed footprint relative to its native drawable bounds (at most half in both axes), covering small 1x1 folder icons and tiny groups in enlarged folders. Large slots, open-folder icons, desktop icons, folder plates and photo materials retain the global FPS limit. Cached-group context alone no longer classifies an icon as small.
- One per-sampler deadline is shared by pre-draw updates and native re-records, so neither path bypasses the limit. Phase is retained when display and blur rates differ (for example 90 Hz / 60 FPS). Native positions and transforms keep their own animation cadence. A coalesced weak-root trailing refresh applies the final pending crop after motion stops; no permanent refresh scheduler is added. Clearing materials cancels pending refreshes.
- Existing preference keys are retained; Russian and English descriptions now state the 30 FPS behavior. The separate folder-icon blur switch continues to control whether blur exists at all.

Validation: ../scratch/mini230/validation.md. On-device deterministic sampler/GPU tests cover 60/90/120 Hz, same-folder large/mini independence, native redraw attempts, toggle-off behavior and preservation of backdrop/refraction.

Performance and diagnostics update:

- Native pre-draw selects the latest geometry, including 120 Hz swipes, while actual material updates honor the global Live FPS limit. The timer no longer requests redundant root traversals for a stationary touch. New wallpaper publications request a frame directly. Native icon surfaces keep the same global limit and one in-flight worker submission.
- Registered views use a cached weak-reference snapshot. A visible material computes its global transform once per pass, reuses it for its samplers, and retains shader effects when transform, wallpaper, scroll and style are unchanged. Liquid lenses update moving crops independently from unchanged mask, optical and color uniforms. Unused frosted shader allocation was removed from the liquid path.
- With edge optimization enabled, partially offscreen BubbleTextViews freeze their retained wallpaper sample in both the live updater and native redraw path. Fully offscreen views are skipped. The developer override restores edge/offscreen updates. Native clipping rejects hidden folder page icons before layer extraction and glyph/shadow/contour baking; native animation surface captures retain their own geometry path.
- Folder material paths and path fingerprints are cached until bounds, radius, spans, item or geometry revision change. Theme/settings changes invalidate this geometry. Folder-icon blur lives below drawer-icon blur in the icon blur subsection, independently from folder plate settings; their reset groups follow this separation.
- Diagnostics collects bounded module logcat, launcher PID logs, system/events records, crash stack context, current LSPosed modules/verbose files (with old-log fallback), and rendering metadata. Root errors, unavailable sources, timeouts and truncation are explicit. Output drains stdout/stderr concurrently, with per-command deadlines and size limits. Export remains a private UTF-8 file shared through the user-selected Android chooser. Log timestamps are thread-local.

Validation and measurements: ../scratch/optimization229/validation.md. Tested on PLZ110, API 37, 120 Hz, System Launcher 17.3.9. The OEM folder plate path was restored and compared on the same launcher with custom icon blur retained. It did not establish a resource advantage; the default stays on the shared custom wallpaper material. Full-workspace OEM folder transition blur stays enabled.

Current integration:

- Folder plates retire the OEM capture producer while custom materials are enabled, and restore it when disabled. The OEM corner resolver/geometry wrapper supplies their outlines, including host-type radius conversion. Hover/resize retains custom material when the normal background View is hidden.
- Folder blur can inherit icon settings or use independent settings. A separate folder-icon blur toggle is retained. Plate saturation changes only the overlay RGB color, independently of wallpaper Vibrancy.
- Icons, folder plates and photos share the icon lens. Square folders match the original icon shader. Wide/tall shapes extend its middle cross-section between rounded ends. Native masks still supply exact clipping. The refraction-before-blur experiment and its saved keys have been removed.
- Photo cards are identified through LauncherCardInfo.mProviderName/getTargetComponent, supporting China/Oplus/OnePlus Gallery packages and both card/AppWidget hosts. Square/wide IDs are a fallback for absent metadata. Base compositor methods/subclasses and snapshot-only hosts are covered. The complete OEM compositor is enclosed, including live smart content, cached Pictures, preloads and snapshots; treating only the smart subtree left opaque photos above the glass. The actual native alpha mask and press/hover transform are used. Photos have opacity, original/icon-color selection and separate lens parameters, with no volume.
- Sampling footprints retain their full size when a View crosses the screen edge. Texture CLAMP handles out-of-bounds samples instead of squeezing a cropped texture back into the View. A developer switch can force edge/offscreen icon updates for diagnostics.
- Developer controls contain optional total RGB depth8..24 and a selected processing stage. RGB332 gives visible posterization at8bits; 24bits/off bypasses the effect. The default quantizes the completed blurred/tinted/shaded plate before foreground artwork. Alpha stays continuous. Dithering code/resources/keys were removed; the enabled flag migrates. Nested plate RenderNodes participate in display-list recovery. The24MB material cache counts both background and foreground textures.
- Clock/calendar/legacy-icon material discovery supports both desktop Views and closed-folder WorkspaceItemInfo/Drawable pairs. The direct preview draw and cached mini-group paths bind leaf drawables; multi-icon group containers are not flattened into a single app icon. Native invalidation callbacks and tick-based material caching remain intact.
- The optional developer mini-icon toggle caps small preview samples at 30 FPS, preserving blur/refraction. Large folder icons retain their normal cadence. Native preview caches remain in use.
- Public APK filenames use CherryPick_Icons_vVERSION.apk. The internal project directory suffix is not user-facing.

Full-workspace folder blur stays under the native transition controller; only folder plate capture producers are retired. Suppressing workspace blur caused it to disappear after returning from an application.

Validation and limitations: see ../scratch/v228-validation.md. Installed and inspected on the authorized PLZ110; calculator return, existing photo hosts and color depth were checked. Global OnePlus matching was tested against the APK, but the remote phone still needs user acceptance.

Historical integration notes:

UI and material primitives are reused; IconGlassHook is a new material integration.

- Glass style is persisted independently of live/static updates. Frosted retains a soft shared blur; Liquid Glass uses a retained AGSL lens with controllable edge width and refraction strength on Android 13+. Both styles preserve the exact native Flux alpha mask. The manual corner override is removed; optical curvature is derived from the native silhouette.
- 0.2.14 replaces nearest-side displacement with a continuously domed lens: radial rays intersect a rounded optical boundary, a smooth shoulder defines surface slopes, and AGSL refract computes the backdrop offset. This removes the four-side planar appearance at high width/strength. This is an independent optical approximation, not Apple's private renderer. It keeps one backdrop lookup per fragment and no additional blur/capture pass.
- A common cached contour texture supplies the Liquid Glass edge lighting in both styles, controlled by the existing icon-contour toggle/opacity. It replaces the old round-rect stroke, is slightly subdued, and does not require per-frame CPU rendering.
- 0.2.20 adds wallpaper vibrancy (0–200%) to Tone and color, default zero. Chroma enhancement runs before spatial blur on the shared wallpaper texture, with the same CPU fallback and GPU formula. It preferentially strengthens less saturated colors while preserving each pixel's maximum RGB channel, so black and dark colors are not lifted. Raising vibrancy also blends the legacy backdrop colorization toward the original wallpaper (full preservation at 100% and above), preventing custom tinting from erasing the source colors. Plate opacity and glyph rendering remain separate.
- Refraction strength now spans 0–300%; the old 0–100% mapping and saved values are unchanged.
- A developer-only Liquid Glass switch selects refraction before blur. Normal mode samples the shared blurred wallpaper; experimental mode obtains an unblurred, color-processed texture and performs a 5x5 binomial convolution of the refracted image in the lens shader, masking afterward. Zero radius bypasses convolution. The experimental approximation adds 25 backdrop lookups per sample and is disabled when developer mode is off; the saved preference is retained.
- Only reset-button holds emit repeated CLOCK_TICK feedback, every 65 ms during the existing 1.5-second hold. Release, cancellation, leaving the button, multi-touch, detachment, disabling, or activity pause stops the hold; completion retains its confirmation feedback.
- Validation: offline release assembly; signed APK verification; isolated app_process GPU tests on connected PLZ110 (without installing/updating the module or changing settings). Actual APK vibrancy CPU/GPU parity at 0/100/200% differed by at most one channel value; black stays black, dark values remain dark, and chroma increases. Both lens orders and 300% refraction rendered; radius zero matched the non-convolution output. This is shader/processing validation, not a visual launcher or physical haptic acceptance test. Test source and output: `../scratch/v220-shader-test/`.

- 0.2.19 adds the System Launcher 16.6.13 native animation path, selected by class availability rather than Android API level (both ColorOS generations can use Android 16):
  * ColorOS 16 has no `surface.IconSurfaceRenderer`: `IconSurfaceRecord.transferViewToSurface` creates the buffer and calls `DragShadowBuilder.onDrawShadow` inline. The legacy provider/record live directly in `anim.iconsurfacemanager`.
  * A scoped transfer context starts material capture after the exact provider buffer is returned and finishes after the native transfer. The record ID comes from the record instance; argument 1 is the source desktop View. Native draw, transforms, clipping, visibility and completion remain owned by the launcher.
  * `IconSurfaceRecord.onIconLoaded` supplies the source while legacy `setDrawable` prepares its material. Legacy material wrappers record their own draw operations for the existing leased live worker, including transformed wallpaper sampling.
  * The modern renderer, loader and lifecycle hooks stay selected on 17.3.9; legacy wrapper capture is disabled there. Shared capture bookkeeping was extracted without changing its token/lease rules.
  * Validation: offline release assembly and APK contract checks against supplied 16.6.13 and local 17.3.9. No on-device animation verification was available; visual behavior on both OS versions still needs device confirmation. Evidence and source diffs: `../scratch/cos16-compat/`.

- 0.2.18 fixes missing desktop icon blur on System Launcher 17.3.12+ (Android 17 / ColorOS):
  * Dynamic resolution of native behind-window BlurBitmapFactory in LauncherWallpaperCapture via MaterialBlurAnimHelper / ClockWallpaperObserver fields, eliminating the hardcoded "tn.c" which broke on 17.3.12 where the factory became "un.c".
  * Dynamic method discovery for processed wallpaper delivery on LauncherWallpaperManager (method "u" in 17.3.9 was obfuscated to "s" in 17.3.12).
  * WallpaperHelper respects delivered launcherWallpaper before checking wallpaper effects, and allows fallback static capture during cold-start instead of returning null when no blurred wallpaper exists yet.

- 0.2.16 replaces the fixed rounded-rectangle contour with an inward distance field of the native mask, cached by alpha geometry and opacity. Equivalent native masks share one baked geometry. It fades from a soft upper-left highlight to a dark lower-right reflection. The contour toggle and full-range linear opacity rebuild recorded materials after new preference values are published. Native icon-shape fingerprints include launcher corner/radius fields, not only the icon-pack configuration.
- Blur radius, quality and Live FPS share one collapsed subsection. Tone and color has one master switch/reset for its three plain sliders. Legacy disabled tone controls migrate to neutral values once, preserving their effective appearance. Developer mode defaults off: quality and refraction edge width are hidden and use 0/40 respectively; saved developer values are retained for when it is enabled again. New-install defaults are also 0/40. There is no background-blur/style explanatory text or manual corner slider.
- Native surface preparation on pointer-down is not treated as a flying animation until both native position and matrix arrive. Prepared surfaces do not exclude the desktop icon from live sampling or submit a stationary material child. The first native pose activates the worker; unused preparations expire independently.
- Screen/wake/user-present events and launcher window focus restore the live scheduler immediately. Window registration no longer adds a 150ms delay. Static ImageWallpaper is eligible for a bounded unlock capture because ColorOS animates it too. Motion detection still stops capture when settled. Revoked capture epochs cannot publish old frames, and an older static load cannot overwrite a newer live publication.
- Diagnostics writes UTF-8 TXT with app/device/settings metadata and collected logs into the private diagnostics cache directory, then opens ACTION_SEND via a non-exported FileProvider with a temporary read grant. The user chooses a recipient; no message is sent automatically.
- Blur strength supports zero (no spatial blur, retaining refraction). Quality controls shared texture resolution (600–1800px high, capped at source resolution); bitmap shaders explicitly use linear filtering on Android 13+. Existing color/opacity/shadow settings remain independent.
- Wallpaper sample coordinates update in ViewTreeObserver.onPreDraw after native scroll/animation transforms, before the current frame is committed. Live FPS still paces refresh requests; geometry can follow each native display frame using the retained wallpaper texture. This avoids the previous timer/scroll phase offset.
- Slow producer allocation/draw/queue waits occur outside the native lease lock. Visibility/token checks prevent a revoked producer from reappearing. Two hidden, detached material producers are prewarmed and reused; retired producers enter the bounded pool only after their in-flight worker job ends. Disabling the module/blur clears the pool.
- The language button appears only on the settings page.

- Stock launcher owns visibility, transforms, morphing, SurfaceControl transactions and animation completion.
- NativeSurfaceLive records only icon material draw operations and native canvas geometry. It never stores or replays an OEM DragShadowBuilder or a mutable native drawable view.
- Every writer has an identity token, native record ID and exact provider/SurfaceControl lease. New native draws revoke previous writers synchronously; provider handoff, reset, hide, release and native showOriginalIcon retire matching work before the operation. Asynchronous initial draws cannot resurrect a retired token.
- The wallpaper is blurred once into a shared GPU texture. Workspace icons and folder items sample that texture using persistent RenderNodes; scrolling changes their sampling effect without re-recording every glyph or the native folder preview. Offscreen views and unchanged samples are skipped.
- Native live refresh follows the icon's actual SurfaceControl transform, so it updates its wallpaper footprint during flight even with static wallpaper. A display-paced worker submits a small material buffer (210x210 on the test device, instead of the native 1200x1200 capture); a child SurfaceControl inherits stock animation geometry and alpha. Native surfaces retain their original OEM initial draw. Completed native hide/reset/reassignment detaches and pools or releases the child.
- GPU command caches are recoverable: OEM snapshot/resource trimming can discard their display lists without changing geometry or wallpaper identity. Live updates check RenderNode.hasDisplayList, restore missing contents, and re-record the owner/parent folder once when needed. showOriginalIcon invalidates the exact returned source and checks other visible samples after the native handoff. Healthy folder display lists remain cached.
- The wallpaper frame source may run during an active surface transition, including activity pause. A 4-second watchdog removes orphaned writers; completed transitions release captured material references.
- Native enlarged FastBitmapDrawable snapshots can already contain desktop glass. The surface renderer uses the clicked view's original icon layers instead, avoiding a second extraction and tint of the composed snapshot.
- Workspace view registration is independent of drawable/style caches and includes Drawable.Callback owners. Config resets cannot empty the live repaint list; only native surface draws retain a fixed footprint, while workspace draws sample current positions. Composed GPU shaders are rebound when the wallpaper crop changes.
- Enlarged initial surface draws reuse the desktop-size effect cache and GPU scaling, retaining the original icon's wallpaper footprint. Live surface updates sample the actual transformed screen bounds. Anchors are not overwritten while the workspace is scaled during a native transition.
- ClipIconView wraps only its material background, following PixelLauncherEnhanced's FloatingGlassBackground integration (see THIRD_PARTY_NOTICES.md).
- No hidden desktop logo, animation-end restoration, timer-based landing fade or mutation of the launcher's wallpaper.
- Settings credit PixelLauncherEnhanced and @anime_killer2009 with clickable links.

Build: ../gradlew.bat -p . assembleRelease --offline --no-daemon

Validation artifacts are in ../scratch/next-027-*, next-028-* and next-029-*. In the 0.2.7 Perfetto trace, launcher traversal averaged 0.91 ms (maximum 4.42 ms); the earlier 0.2.6 trace averaged 1.83 ms (maximum 17.90 ms). These are CPU traversal measurements from different scripted sequences, not overall FPS or proof of hitch-free rendering. 0.2.7 had a discarded-display-list regression, reported by the user; 0.2.8 logs confirmed missing sample contents after transitions. 0.2.9 adds cache-health recovery on both pages. The test device has static wallpaper; animated wallpaper was not selected for this check.

Application ID is retained to preserve settings and avoid two module engines loading together. Previous source/APKs remain in the parent directory.
