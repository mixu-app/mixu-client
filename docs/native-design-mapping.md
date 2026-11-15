# Native visual design mapping

Status: **Partial device acceptance** · CTX-0044

The approved visual reference is the workspace's `recording/html/mixu.html`.
The HTML remains a read-only reference. App surfaces use Kotlin Compose and the
existing library, reader and graph controllers.

## Mapping

| Reference | Native surface | Behavior |
| --- | --- | --- |
| Warm app background, navy accent, white cards | `LightMixuColors` | Existing dark, custom and E-Ink palettes remain selectable |
| Collections-first home | `KindleHomeScreen` | Real collection counts; opens existing collection filters |
| Continue-reading cover card | `KindleHomeScreen` | Existing reader and case-board callbacks, actual progress |
| Cover grid | `KindleHomeScreen` | Responsive columns, local cover cache, actual library data |
| Pill search and compact masthead | `LibrarySearchBar`, `MixuPageHeader` | Home search matches title, author and collection; library search retains its controller |
| Phone navigation and wide sidebar | `MixuAdaptiveNavigation` | Same destinations; safe system-navigation insets |
| More hub | `SettingsPreviewScreen` | Grouped native hub linking account/wallet, cloud services, reader preferences and help; no fabricated sync claims |
| Paper and native reader controls | `ReaderScreen`, `ReaderPreferencesDialog` | Compact navigation, single-line footer actions and a focused settings dialog |

## Preserved boundaries

- Guest reading, import, bookmarks and personal data remain local.
- Graph projection and horizon filtering remain in existing controllers.
- No sample books, cover artwork, fictional account metrics or JS graph code
  are copied from the reference into the application.
- Collection management remains available through the library book actions.
- App licensing, dependency pins and server contracts are unchanged.
- App chrome uses compact SansSerif typography independently of publication
  font, size and line-spacing preferences. Reader text and chapter headings
  retain the configured publication typography.
- Embedded case-board panels omit the standalone masthead, use compact reveal
  controls and horizontally scroll overflowing action rows. Filtered graph data
  and controllers remain unchanged.
- E-Ink grids use three columns and system bars follow the effective palette.

## Verification and remaining acceptance

Independent commander verification passed `just check` with **109 tests**, then
built and installed the debug APK. Device checks covered phone portrait,
landscape, tablet and a narrow 320 dp window with 1.5 font scaling. The compact
footer remains single-line with touch targets at least 48 dp and height no more
than 64 dp in that narrow-window check.

The scrollable reading-settings panel reaches margins. Android Back closes both
the `Aa` settings dialog and the contents dialog. Font changes persist, and
switching from a custom theme to the built-in dark theme remains effective after
process restart. The publication parser has no diff from the baseline; reading
normalization is not migrated. Retained screenshot evidence uses original
synthetic fixtures, with no real-book screenshots or content copied into this
repository.

This is partial device acceptance, not full HTML parity. The existing graph and
Copilot controllers are hosted in a bottom companion panel on compact windows
and an end panel on expanded windows. Reader chrome follows the later native-UI
direction: one compact title/action bar and a restrained single-line footer,
with appearance controls shown only after choosing `Aa`.

Physical E-Ink refresh, ghosting and power validation remain outstanding. Known
preexisting issues are tracked separately: **CTX-0050** covers visual-page locator
persistence, and **CTX-0051** covers an image-heavy publication opening timeout.
Neither is resolved by this UI change.

The HTML's home collection creation/rename controls and custom library pagination
remain unimplemented. Existing library management and scrolling remain available.

## Device geometry checks

Use the selected disposable Android instance, supplied through `ANDROID_SERIAL`.
These commands alter only that instance's display geometry:

```sh
# Phone portrait
adb -s "$ANDROID_SERIAL" shell wm size 1080x1920
adb -s "$ANDROID_SERIAL" shell wm density 420
# Tablet landscape
adb -s "$ANDROID_SERIAL" shell wm size 1920x1200
adb -s "$ANDROID_SERIAL" shell wm density 240
# E-Ink-like geometry; explicitly enable E-Ink mode in app settings as well
adb -s "$ANDROID_SERIAL" shell wm size 1404x1872
adb -s "$ANDROID_SERIAL" shell wm density 240
# Restore the instance defaults
adb -s "$ANDROID_SERIAL" shell wm size reset
adb -s "$ANDROID_SERIAL" shell wm density reset
```

Geometry alone does not simulate E-Ink. Test the monochrome policy separately;
physical refresh, ghosting and power behavior require a real E-Ink device.

## Reuse the persistent Android test instance

The rootful Podman instance persists independently of the UI client. Supply its
container name through `REDROID_CONTAINER`; start it only when stopped:

```sh
sudo podman start "$REDROID_CONTAINER"
sudo podman port "$REDROID_CONTAINER" 5555/tcp
```

Use the reported host binding as `ANDROID_SERIAL` (replace an all-interface
binding with the intended reachable host address). Then connect and open the
native display:

```sh
adb connect "$ANDROID_SERIAL"
scrcpy --serial "$ANDROID_SERIAL"
```

Closing scrcpy does not stop or delete the persistent container. If using a VNC
viewer for the desktop session, scrcpy remains the Android display inside that
session; VNC does not change the application renderer.

## Theme injection and branding

The masthead uses the existing canonical `MixuBrandLogo` drawable component;
its approved brand colors and asset geometry do not follow arbitrary theme
recoloring. Covers come from the imported local books rather than prototype
artwork.

Existing theme packs remain schema version 1. Their optional `chrome` object
now also accepts `font_family` (`SANS_SERIF`, `SERIF`, `MONOSPACE`),
`font_scale_multiplier`, `card_corner_radius_dp`, `control_corner_radius_dp`,
`pill_corner_radius_dp`, and `cover_corner_radius_dp`. Omitted values inherit
central `MixuChromeAppearance` defaults. Appearance resolves inside `MixuTheme`,
using the existing pack import/export path and CompositionLocal injection;
there is no second theme registry. Publication typography remains independent.

Chrome scaling is bounded to 0.75–1.5; radii to 0–100 dp; non-finite values fall
back. E-Ink takes precedence: monochrome palette, square shapes, compact SansSerif
chrome. Reader, case-board and Copilot colors continue using the existing theme
pack tokens. Adaptive breakpoints and touch target minimums remain layout and
accessibility policy, not arbitrary theme data.

## Contextual reading controls

User feedback supersedes the earlier selection-dashboard treatment. Selection
starts with a compact Material action row: copy, highlight, note and dictionary;
translation and explicit AI explanation are in overflow. Only the requested note
or dictionary content expands. There are no emoji mode chips, provider/latency
claims, resource-download simulations or credit/implementation labels in this
surface. Explicit AI requests still use the existing bounded Copilot callback.

Dictionary results contain genuine local headword data only. A missing name or
word reports no entry without invented pronunciation, part of speech or remote
response. Stored narrative commentary is not appended automatically.

Normalized publication paragraphs and image tokens remain byte-for-byte
compatible with the current parser, including its existing small-image admission
rules. Their lengths contribute to persisted locators and are not migrated by
this visual change. At presentation time only, legacy generated image labels
become a neutral accessibility description; genuine publisher alt remains.
The reader and image inspection view add no visible generated captions, image
classes or sequence numbers. Original publisher text remains content.

## Reader chrome hierarchy

The reader menu reveals a compact back/title/contents/bookmark/companion bar and
one modest page-navigation row. Favorite, dictionary and AI actions remain in
overflow. No agent orb or technical units are placed in the reading toolbar.

The single-line `‹ / Aa / ›` footer exposes previous page, reading settings and
next page through full Chinese accessibility descriptions. `Aa` opens a focused
native dialog only on request. Its scrollable
sections retain font family/size, line spacing, theme, all reading presets,
columns, paged/continuous layout, indentation and margins. Changes use the
existing display-config callback. Explicit built-in theme or reading-style
selection clears only the active custom-pack override; it does not delete an
imported theme file or change hardware E-Ink policy outside the existing preset
behavior. Clearing that optional override deletes only its current-scope settings
key in the existing save transaction, so it stays cleared after reopening while
other reader preferences and scopes remain intact. Popup surfaces, shapes,
contrast and elevation use the existing theme provider, including E-Ink overrides. Reading normalization,
pagination, locators and graph horizons are unchanged.

The contents list is a themed native modal with a scrollable chapter list and
close action. Android Back dismisses the modal. Chapter selection retains the
existing navigation callback and knowledge-horizon confirmation behavior;
opening the modal does not resize the reading viewport.
