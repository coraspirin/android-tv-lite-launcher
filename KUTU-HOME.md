# KUTU-HOME.md — build and test record (guide sections 15-27, 31)

Built and tested 2026-09-20 against Mi Box S (MIBOX4 / oneday, Android 9, API 28).

**Project root: `E:\DATA\Projeler\_Projeler\tv-debloat`** (moved here from
`C:\Users\mucah\tv-debloat` on 2026-09-20; see "Relocation" at the end).

## Build metadata

| | |
|---|---|
| Package | `local.kutu.home` |
| Version | 1.0 (versionCode 1) |
| APK | `build/kutu-home/app/build/outputs/apk/release/app-release.apk` |
| Size | **1 045 000 bytes (1.02 MB)** |
| SHA-256 | `8e5f305b0a453a8f8465c7d1172d3380decb5495a5d5f144f6cc706f677cc6b6` |
| minSdk / targetSdk / compileSdk | 28 / 28 / 36 |
| Build type | release, `minifyEnabled true`, `shrinkResources true`, `debuggable false` |
| Signing cert SHA-256 | `22ae3b8a7f585010da25bcd5b0cc60f748a61de1e4936b9ff30e03585a6ef9b2` |
| Keystore | `keys\kutu-home.jks` (outside the Gradle project tree) |
| Total methods in dex | 338 (121 defined) |

The APK hash above is from the post-relocation clean rebuild. The build is not
byte-reproducible (absolute paths and timestamps leak into dex/ProGuard metadata),
so the hash differs from the pre-move build while the source is identical. The
**signing certificate is unchanged**, which is what actually matters: the rebuilt
APK installs over the existing one as an update.

## Architecture conformance (section 15)

| Requirement | Status |
|---|---|
| Plain Java / platform Views | yes — `android.widget` only |
| No ads, recommendations, Play Next | yes |
| No analytics / telemetry / cloud / accounts | yes |
| **No network permission** | yes — the only permission is `REQUEST_DELETE_PACKAGES` |
| No background service | verified on device: `dumpsys activity services local.kutu.home` = `(nothing)` |
| No wakelock | verified: no entry in `dumpsys power` |
| No boot receiver | none declared |
| No database | SharedPreferences only |
| No GMS dependency | `dependencies { }` is empty |
| No AndroidX / Compose | `android.useAndroidX=false`, zero dependencies |
| Essentially zero idle CPU | measured **0.0 %** over 60 s after reboot |
| `allowBackup=false`, `debuggable=false` | yes |

## Background image (section 17)

`kutu-home-background.png`, 1672x941 (aspect 1.7768 against 16:9 = 1.7778), taken
verbatim from the guide repo, sha256 `425f253a...d424`. Bundled in
`res/drawable-nodpi/`, rendered with `centerCrop` so it fills 1920x1080 without
stretching; the 0.06 % aspect difference crops well under one pixel. No network
loading, no animation, no runtime blur.

## Dock (section 16, 19)

Seed order chosen by the human, applied only when no saved dock exists:
YouTube, Stremio, Max, Apple TV, tabii, Turkcell TV+, Play Store.

Stored under SharedPreferences file `home`, key `dock_v1`, as ordered
`package \u001f rememberedLabel` rows.

## Measured results (section 31)

| | Stock launcher | FLauncher 0.18.0 | **Kutu Home** |
|---|---|---|---|
| PSS | 63.5 MB | 43.4 MB | **20.3 - 20.9 MB** |
| APK size | n/a (system) | 26 MB | **1.02 MB** |
| Idle CPU | not measured | not measured | **0.0 %** |
| Background services | yes (tvrecommendations) | none observed | **none** |
| Permissions | many | several | **1** |

Measured after a full reboot, app settled.

## Tests performed

| Test | Guide | Result |
|---|---|---|
| Builds and installs | 24 | pass |
| Launches via Leanback, no crash | 24 | pass — no FATAL in logcat |
| Background, clock, date render | 18, 24 | pass — `13:55`, `20 Eylül Pazar`, top-right |
| Focus: scale, brighten, accent glow, label shown once | 18 | pass — see `evidence/home/test_all_apps.png` |
| Resting icons not tinted by focus glow | 18 | pass — glow lives on tile background and shadow only |
| Adaptive/transparent icons normalised to rounded squares | 18 | pass — transparent logos get the near-black inner card |
| Long press opens the menu and does **not** launch the app | 20, 26 | pass — `mResumedActivity` stayed `HomeActivity` |
| Menu contents for a system app omit "Uygulamayı kaldır" | 21 | pass — YouTube showed only Taşı / kaldır / bilgi |
| Move mode: OK enters, LEFT/RIGHT moves, OK commits | 22 | pass — YouTube moved index 0 -> 2 |
| Custom dock survives `adb install -r` | 27 | pass |
| Custom dock survives reboot | 27 | pass — dock[0] still `Stremio` after reboot |
| All Apps lists apps, excludes Kutu Home | 23 | pass |
| Screen Mirroring panel opens in-launcher | 23 | pass — never touches MirrorActivity |
| Mirror panel state when receiver absent | 23 | pass — red "Alıcı kurulu değil", start button hidden |
| Settings chip resolves standard action first | 23 | implemented; standard `ACTION_SETTINGS` resolves on this box |
| BACK returns from each panel to Home | 24 | pass |
| No square elevation/shadow artifact on menus | 21 | pass — transparent window background, no elevation on the card |

## Bugs found in the section 26 real-remote test (reported by the human)

### 1. Confirming a move launched the first app

Symptom: long press, Taşı, reorder, then a short OK to confirm — the move committed
**and** the first app on the shelf opened.

Cause: `dispatchKeyEvent` handled OK's ACTION_DOWN by calling `commitMove()`, which
sets `moveIndex = -1`. The guard that swallowed the matching ACTION_UP was itself
written as `if (moveIndex >= 0 && ... ACTION_UP)`, so by the time UP arrived the guard
was already false. The event fell through to the focused tile's key listener, which
treats a select-key UP as a short press. `commitMove()` also re-renders and focuses
tile 0, which is why it was always the *first* app that opened.

Fix: a `swallowUpKeyCode` field records the key that ended move mode, and
`dispatchKeyEvent` discards that key's next ACTION_UP **before** looking at
`moveIndex`. Applied to BACK (cancel) as well as OK (commit).

Verified: after the fix, OK-to-confirm leaves `mResumedActivity` on `HomeActivity`
and produces no `ActivityManager: START` line at all, while the reorder still
persists (`dock[0]` changed from YouTube to Stremio as instructed).

### 2. No discoverable way to add an app to the home shelf

Symptom: the human could not add an app.

Cause: not a crash — a gap in the design. Adding was only possible by long-pressing an
app in All Apps that was *not* already on the shelf. The shelf's own menu has no
"add", and in All Apps an app already on the shelf shows only "Ana ekrandan kaldır",
so whichever app the human tried, no "add" appeared. Guide 19 only called for a `+`
tile when the shelf is empty, which hid the affordance exactly when it was needed.

Fix, two parts:
- The `+` tile is now **permanent**, always the last slot on the shelf, opening All
  Apps. It uses a dedicated `ic_add` glyph and reads "Uygulama ekle" when focused.
- In All Apps the focused label now says "<app>  ·  ana ekranda" for an app already on
  the shelf, so the differing menu is explained rather than surprising.

Tile geometry was retightened so seven apps plus the `+` still fit without scrolling
and nothing is clipped at the shelf edge (tile 96 -> 88 dp, icon 60 -> 56 dp, gap
14 -> 12 dp; 8 slots = 840 dp inside the 864 dp overscan-safe width).

### 3. Focus effect clipped, and scrolled icons showing through the fade

Two symptoms reported together: the hover effect was only partly visible, and icons
scrolling past the shelf edge were still visible underneath the gradient.

Cause of the second: the shelf had `clipToPadding="false"` plus
`requiresFadingEdge="horizontal"`. The fading edge does not remove overflow, it only
makes it semi-transparent, so half-icons stayed on screen under the gradient.
Fixed by clipping hard (`clipToPadding="true"`) and dropping the fading edge. This
departs from guide 18's "subtle faded edge" in favour of guide 19's stronger "never
draw off-shelf icons"; the two cannot both hold.

Cause of the first was subtler, and three rounds of increasing padding did not move
the clip boundary at all. Android scrolls a newly focused child into view using its
**unscaled** bounds, so the shelf stops scrolling exactly at the tile's edge and the
114% scale plus glow lands outside the visible area. Padding on the scrolling row
cannot help, because the scroll targets the child, not the row's padding.

Fixed structurally: each tile is now two layers. The outer `FrameLayout` is the
focusable view and carries `tile_inset` (12 dp) as padding; the inner `tile_card`
holds the background and is what gets scaled and lifted. The growth room is therefore
inside the bounds Android scrolls to, so a tile at either end of the shelf keeps its
full ring and glow. `TileBehaviour` animates the card and sets the shadow colours on
it; the card uses `duplicateParentState` so the focus selector still applies.

Geometry after the change: card 78 dp, inset 12 dp, outer box 102 dp, tile gap 0
(separation now comes from the two neighbouring insets, giving 24 dp of breathing
room — which also answers the "dock is too cramped" half of the report). Eight slots
occupy 848 dp inside the 864 dp overscan-safe width.

Verified on device: the focused `+` tile at the far right edge renders its complete
ring and glow on all four sides, while the leftmost partially scrolled tile is cut
cleanly with no gradient bleed.

## Bug found and fixed during earlier automated testing

`TileBehaviour` called `View.bringToFront()` when a tile gained focus. In a
`LinearLayout` that physically reorders the child, so focusing a tile moved it to the
end of the dock — the shelf silently rearranged itself. Removed; `translationZ`
already lifts the focused tile above its neighbours without touching layout order.
First screenshot (`kutu_home_01.png`) shows the wrong order, second
(`kutu_home_02.png`) shows it corrected.

## Sections 26, 29, 30, 31 — completed 2026-09-21

**Section 26 real-remote test: passed.** Confirmed by the human on the physical
remote. Two defects came out of it (move-commit launching an app; no discoverable way
to add), both fixed above, plus two follow-ups reported afterwards:

### 4. Focus jumped to the first tile after confirming a move

`commitMove()` called `renderDock(null)`, and a null focus target makes `renderDock`
fall back to `focusFirstTile()`. Fixed by capturing the moved package before clearing
`moveIndex` and passing it to `renderDock`, so focus stays on the tile that was just
moved. `cancelMove()` does the same. Verified: moving Stremio from slot 0 to slot 1
and confirming leaves focus on Stremio, with zero `ActivityManager: START` lines.

### 5. Permanent "+" tile removed at the human's request

They preferred adding via long-press in All Apps, which they found sufficient once it
was explained. The `+` now appears only in guide 19's original zero-favourites case.
With the slot freed, the shelf went back to 7 slots and the cards grew again: card
88 dp, inset 12 dp, outer box 112 dp, 7 slots = 816 dp inside the 864 dp safe width.
The 24 dp inter-card breathing room from fix 3 is retained.

**Sections 29/30 promotion: done.**

```
cmd package set-home-activity local.kutu.home/.HomeActivity
```

Verified before reboot: `resolve-activity` returns `local.kutu.home/.HomeActivity`,
and HOME pressed from inside YouTube lands on Kutu Home. Verified after reboot, with
no manual launch: Kutu Home is the resumed activity, `resolve-activity` agrees, and
`ResolverActivity` count is 0 — no chooser. Dock survived, Bluetooth and the remote
service both healthy, zero crashes in logcat.

FLauncher was already absent by this point (`pm uninstall` reported
`Unknown package: me.efesser.flauncher`); the human appears to have removed it
through Kutu Home's own uninstall flow during the remote test. Remaining HOME
candidates are exactly `local.kutu.home` and the system's
`com.android.tv.settings/.system.FallbackHome` safety net. Its APK is still kept at
`build/flauncher.apk` if it is ever wanted back.

**Section 31 measurements, after a full reboot with Kutu Home as HOME:**

| | Value |
|---|---|
| Kutu Home PSS | **23.2 MB** |
| Idle CPU over 60 s | **0.0 %** |
| Background services | `(nothing)` |
| Wakelocks | none |
| MemAvailable | 1 259 776 kB (~1.20 GiB) |
| /data free | **2.6 GB** (baseline 1.9 GB) |
| Crashes since boot | 0 |

Launcher-related resident memory went from 63.5 MB (stock launcher, plus a separate
`tvrecommendations` process) to a single 23.2 MB process with no services at all.

## Mirror panel on/off switch - 2026-09-21

The panel's one-way "Alici baslat" chip is now a switch, at the human's request.

- `Switch` replaces the start chip, tinted with the panel's accent so it reads correctly
  on the light glass, focusable so the D-pad reaches it and OK toggles it
- checked = the receiver is genuinely listening. The panel already read the real state
  from `/proc/net/tcp`, which needs no permission, so the switch shows reality rather
  than a remembered preference. Kutu Home still holds exactly one permission.
- on sends `local.kutu.mirror.ReceiverControlActivity`, off sends the new
  `ReceiverStopActivity`. Neither is Kutu Mirror's `MirrorActivity`, which guide 23
  forbids this screen from ever opening.
- after either, the port is re-read and the real state wins; if the shim cannot be
  reached the switch snaps back and says so
- when the state cannot be read, the switch is left where the user put it rather than
  being yanked to a guess

The matching deviation on the Kutu Mirror side, an exported stop shim where guide 11
describes only a start shim, is disclosed in `KUTU-MIRROR.md`.

Verified over ADB: start -> port 7000 LISTENING, 1 service; stop -> not listening,
0 services; start again -> LISTENING, 1 service.

## Focus restoration - 2026-09-21

Three defects reported by the human, all one root cause.

| Reported | What happened |
|---|---|
| Settings chip -> BACK | focus landed on the first icon instead of the Settings chip |
| Mirror chip -> BACK | focus landed on the first icon instead of the Mirror chip |
| Remove an app from the shelf | focus landed on the first icon instead of the removed tile's neighbour |

**Cause.** `onResume()` calls `rebuildDock()`, which called `renderDock(null)`, and a null
target makes `renderDock` fall back to `focusFirstTile()`. So every return to the
launcher - from a panel, from an app, from anywhere - threw focus back to slot 0. The
remove action passed `null` for the same reason. This is the same class of bug as
"focus jumped to the first tile after confirming a move", fixed earlier for that one
path only; the general case was still there.

**Fix.**

- `lastFocusedPkg` / `lastFocusedChip` record what had focus, written from the tile and
  chip focus listeners. `rebuildDock()` restores it: a chip if the person came from a
  chip, otherwise the tile.
- `renderDock`'s focus placement moved into `applyFocus`, with a `KEEP_FOCUS` sentinel
  for the case where the caller places focus itself (returning to a chip).
- Removing a favourite now computes its neighbour - the tile that slid into the gap, or
  the new last tile when the end of the shelf was removed - and focuses that.
- Pressing HOME still clears the memory first, so guide 30's "HOME lands on a clean
  Kutu Home" keeps working.

**Verified on device over ADB, with screenshots at each step:**

| Test | Result |
|---|---|
| Ayarlar chip -> open Settings -> BACK | focus back on **Ayarlar**, not slot 0 |
| Ekran Yansitma chip -> open panel -> BACK | focus back on **Ekran Yansitma** |
| Remove the last tile (tabii) | focus landed on **YouTube**, the new last tile |
| Long press still does not launch the app | menu opened, `mResumedActivity` unchanged |
| Shelf restored afterwards | tabii re-added from All Apps; order identical to before |

The mirror panel switch was exercised in the same pass: it read OFF with the receiver
stopped, turning it on made the state line go green "Alici hazir", and port 7000 came up
with one service.

## Mirror panel trimmed - 2026-09-21

Asked for by the human once the panel was working:

- the "iPhone veya iPad" and "Mac" instruction blocks are gone, along with the divider
  above them. The instructions had served their purpose; the panel is now title, state
  line, switch.
- the switch has no "Alici" label any more. The word moved to `contentDescription`, so
  the control still has a name for accessibility without printing one on screen.
- `panel_width` dropped 520dp -> 360dp, matching `menu_width`. With the instructions
  gone the card was mostly empty space. The dimension is used by this layout only, so
  nothing else moved. This one was my call, not a request - revert the dimen to 520dp
  if the wider card is wanted back.

Unused strings `mirror_ios_head`, `mirror_ios_body`, `mirror_mac_head` and
`mirror_mac_body` were deleted.

Verified on device: panel renders as intended, and the unlabelled switch still toggles -
port 7000 went LISTENING -> closed -> LISTENING across two presses, and BACK returned to
the shelf with focus on the Ekran Yansitma chip.

## Liquid glass pass - 2026-09-21

Asked for by the human: the chips and the shelf needed to be genuinely translucent,
taking iOS 26's liquid glass as the reference.

Two things decided the shape of it.

**Blur was never on the table, and was never needed.** `RenderEffect` is API 31 and the
box is API 28, and guide 17 forbids runtime blur anyway. But the supplied background is
an almost perfectly smooth gradient: it carries no high-frequency detail, so a blur of it
would be indistinguishable from the image itself. Layered white translucency plus a
vertical sheen reproduces the whole effect at no GPU cost.

**Dark glass and real transparency are mutually exclusive on this background.** Taking
the old `shelf_glass` (#C20D1626, 76% opaque) down to something that reads as glass lands
the pane near #8893A6, where the white `ink_on_glass` label text falls to about 2.5:1 -
unreadable across a room. So every glass surface is now white-tinted and every
"on glass" text colour is dark. Guide 18 and 22 were updated to say light glass.

What changed:

- `colors.xml` carries the whole redesign. `tile_bg`, `chip_bg` and `ink_on_glass` are
  each shared by several screens, so redefining token *values* moved the home shelf, the
  All Apps grid, the context menu and the mirror panel together, with no layout edits.
- every glass drawable is a single `<shape>` with a `<gradient>` + `<stroke>`, not a
  `<layer-list>`. Overdraw is unchanged from before, which matters on the S905X, and it
  keeps the shelf's outline correct - `HomeActivity` clips the shelf to its background's
  outline, and a lone `GradientDrawable` always reports a clean rounded rect where a
  layer-list would answer from whichever child layer came first. `dither` is on
  everywhere; an 8-bit 1080p panel bands on gradients this shallow.
- tiles are translucent now, so they composite on top of the shelf glass rather than
  hiding it. The resting tile gained a hairline it did not have before: without it a
  white icon card (Netflix, YouTube) dissolves into the pane behind it.
- `accent_glow` was darkened to #A62B6E9E. The old pale blue was invisible as a drop
  shadow on a light surface, which made the 6dp focus lift disappear entirely.
- `veil` went from a dark scrim to a light frost, so an overlay is the same material as
  the dock instead of a mode switch.
- the chips finally animate. They used to change colour and nothing else, which read as
  dead next to a tile that scales and lifts; they now lift too, on `TileBehaviour`'s
  timing (`ANIM_MS` is package-visible for that reason). Less scale than a tile - 1.06
  against 1.14 - because a chip is far wider than it is tall. `chips_row` and the bottom
  stack stopped clipping their children so the new shadow is not cut off.
- `tile_radius` 18dp -> 22dp. The four dimens `clampShelfWidth()` does arithmetic over
  (`tile_outer`, `tile_gap`, `shelf_padding`, `row_padding`) were deliberately left alone.

Deliberately unchanged: `tile_transparent_logo_bg` stays near-black. `IconNormalizer`
bakes that plate into the icon bitmap, so a light plate would erase light logos, and
guide 18.5 asks for near-black. A dark rounded plate also matches icons already in the
dock (HBO Max, TV+).

### Focus ring coloured by the icon

Asked for over two passes. The first put each icon's colour on its own card, face and
hairline together. Seen on the TV that was a row of seven competing colours, and the
focused tile lost the thing that had made it stand out, so the human's next note moved
all of it onto the selection: the focused tile carries a border in its own colour, every
resting tile is colourless glass behind a plain hairline. A zoom of the intended design
settled the rest - the border is thin, not a wide band, with a dusty gradient fading
inwards from it and a face tinted just enough to belong to them.

`TileGlass` derives one colour per app and builds that tile's `StateListDrawable`.
The focused state is a `LayerDrawable`: a tinted face, a 1.5dp outline, then seven 1dp
rings stepping inwards on a falling alpha ramp. The rings are what read as the haze.
A radial gradient would have been the obvious way to do it, but `GradientDrawable` takes
its radial radius in pixels and the card's size is not known when the background is
built; the rings need no size at all. Layer 0 is the only full-bounds layer, so it is
the one the view's outline - and therefore the focus lift's shadow - comes from.

Only the hue and roughly how vivid the artwork is are taken from the icon. Brightness is
discarded and replaced with a fixed light value, because most TV icons are dark cards -
HBO Max, Apple TV, TV+, tabii - and a near-black ring reads as a hole rather than as
that app's colour.

Three details in the extraction are load-bearing:

- hue is averaged as a vector, not arithmetically. A red logo has pixels at 359 and 1
  degrees, and the plain mean of those is cyan.
- a pixel must clear a saturation threshold to vote at all. Muted fields are most of
  what these icons are made of and win on sheer count however the votes are weighted.
  TV+ is the case that proves it: a small yellow mark on a big muted navy field. Weighted
  by area it came out navy; weighted by vividness the two nearly opposite hues cancelled
  and it came out white. Dropping the field outright lets the mark speak, and the ring is
  yellow, which is what anyone would have said the icon's colour was.
- the length of the hue vector says how far the survivors agreed. A logo built from
  clashing colours cancels itself out and is left neutral rather than taking whichever
  colour narrowly won. That is what keeps Play Store and Apple TV plain, correctly.

Ring saturation has a floor as well as a cap. Brand palettes vary far more than a focus
indicator should, and reproducing a muted logo's own saturation gave a ring too pale to
register next to a vivid one.

An icon with no usable chroma returns white, which is the plain bright edge the design
had before. The resting face is white whatever the icon, so a resting tile is identical
to the neutral `tile_bg` in XML - that drawable is still the background both tile
layouts are inflated with, and is what an unavailable app keeps.

The colours are cached per package and cleared alongside `IconNormalizer`'s cache on a
package broadcast - they are derived from those icons, so they go stale with them.

Applied on both the shelf and the All Apps grid; the two use the same two-layer tile
structure, so one call site each was enough.

Verified on device (192.168.61.115): release build installed over the existing one, so
the dock favourites survived. Home, tile focus, chip focus, All Apps, the long-press
context menu and the mirror panel were each captured and checked - all text dark on
light glass, focused tile and focused menu row both unmistakable, no clipped focus
effects at either end of the shelf.

## Dock centred vertically - 2026-09-21

Asked for: centre the objects vertically on screen, with the buttons still attached to the
dock, but the centring worked out from the dock alone.

Those two do not both survive a vertical stack, which is what the screen used to be - one
LinearLayout pinned to the bottom holding label, shelf, hint and chips. Centring a stack
centres its *total* height, so the shelf would land off centre by half the difference
between the label and the chips, and the chips block is the taller of the two. The
stronger objection is `move_hint`: it is shown and hidden as move mode is entered and
left, so a centred stack would change height and jog the dock every time a tile was
picked up.

So the shelf is now `layout_centerInParent` in a `RelativeLayout` and the other three are
anchored to it rather than stacked with it. The shelf depends on nothing, which is both
the literal reading of "only the dock as the basis" and the reason nothing a sibling does
can move it.

One detail is load-bearing and should not be tidied away: the chips are anchored below
`move_hint`, not below the shelf, and `move_hint` is anchored below the shelf.
`RelativeLayout.getRelatedView()` walks *up* past a GONE anchor and re-applies the same
rule, so with the hint hidden the chips resolve to sitting below the shelf, and with it
showing they sit below the hint. Anchoring the chips straight to the shelf would have
them overlap the hint in move mode.

`shelf_bottom` (64dp) was deleted; the new layout has no use for it. The similarly named
`glass_shelf_bottom` in `colors.xml` is an unrelated gradient stop and stays.

No Java changed. `clampShelfWidth()` reads `shelf.getLayoutParams()` as a plain
`ViewGroup.LayoutParams` and only sets `.width`, so it is indifferent to the parent type,
and `addChip()` still builds `LinearLayout.LayoutParams` because `chips_row` is itself
still a LinearLayout.

Verified on device by measuring the screenshot rather than eyeballing it: the shelf spans
y=415..665 of the 1080-line frame, centre 540.0, exactly the middle. Re-measured with
move mode active - the hint visible and the chips pushed down - and after cancelling:
415..665 both times, unmoved. `uiautomator dump` returns a null root node on this box, so
the numbers come from parsing raw `screencap` output.

## Light / dark theme - 2026-09-21

Asked for: a theme button in the top-left corner that switches between light and dark.

The usual mechanism was not available. `AppCompatDelegate.setDefaultNightMode` is
AndroidX, which guide 15 rules out, and `UiModeManager.setNightMode` needs the
signature-level MODIFY_DAY_NIGHT_MODE permission, so the system's own night setting
cannot be driven from an app on API 28 either. What remains is what AppCompat does
underneath: `applyOverrideConfiguration` in `attachBaseContext` sets the activity's own
uiMode before its resources are resolved, and the platform then picks -night resources.

That turned out to be cheap here, because every drawable and layout already reads
`@color` tokens rather than literals: `values-night/colors.xml` re-skins the shelf, the
tiles, the chips, the context menu, All Apps and the mirror panel without one of them
being edited. The night values are largely the dark-glass palette this launcher used
before the liquid-glass pass, so they were already proven on this wallpaper.

Two things in `ThemeStore.override` are easy to get wrong:

- the night bits are always written, never left alone. The box has a system night setting
  of its own, and inheriting it would have made this button work only half the time.
- the uiMode *type* bits are carried over rather than zeroed. uiMode packs the device type
  and the night flag into one field, so writing the night flag alone would have told the
  resource system this is no longer a television.

Three things the -night qualifier could not reach on its own:

- **`TileGlass` built its cards from a hardcoded `Color.WHITE` and a literal edge colour.**
  It is the only place that draws glass from code, so it was also the only thing that would
  have stayed bright white in the dark theme. It now reads `@color/tile_glass_base` and
  `@color/glass_edge`, and `mix()` takes the base as a parameter instead of assuming 255.
- **The wallpaper is a light image.** Rather than ship a second copy of it, `bg_scrim` is a
  token that is transparent in the light theme and dark in the night one, applied as
  `android:foreground` on the ImageView. On the ImageView and not on the root: on the root
  it would have dimmed the glass and the text along with the wallpaper. All Apps needed
  nothing, its existing `veil` overlay already does this job.
- **`tile_transparent_logo_bg` deliberately has no night value.** `IconNormalizer` bakes
  that plate into a static bitmap cache that outlives the recreate, so a second value would
  simply have gone stale. Near-black suits both themes anyway.

The icon is picked by the qualifier too: `drawable/ic_theme` is a moon and
`drawable-night/ic_theme` is a sun, so the button always shows what pressing it will give
you and no code chooses between them. The crescent is one closed outline rather than two
circles subtracted with evenOdd - subtracting fills the part of the cutout that falls
outside the disc as well, which draws a blob, and it did on the first build.

Tiles get `setNextFocusUpId(R.id.theme_toggle)` because the button shares no horizontal
overlap with the shelf and FocusFinder should not be left to guess. The chips' focus
animation moved into a shared `attachGlassFocus` so the button uses it rather than a second
copy. A static flag survives the `recreate()` and puts focus back on the button afterwards -
instance fields do not survive it, and onResume's rebuild would overwrite a plain
requestFocus posted from onCreate.

Verified on device: both directions of the toggle, then force-stop and relaunch to confirm
it persists. Every screen checked in dark - home, a focused tile (the icon-coloured ring
and its haze still read on dark glass), All Apps, the long-press menu and the mirror panel
including the switch track. The shelf still measures centre y=540.0 of the 1080-line frame
in dark mode.

## Still outstanding

- ~~`AppRepository.MIRROR_PKG` (`local.kutu.mirror`) does not exist yet~~ - Kutu Mirror
  is built and installed; see `KUTU-MIRROR.md`
- Guide 33 final cleanup, 34 security review, 35/36 final report, 37 disable debugging
- Kutu Mirror (guide 7-14) remains blocked on WSL
