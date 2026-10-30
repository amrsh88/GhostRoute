# GhostRoute 0.3.1 — Night Navigation UI

This release changes presentation and layout, retaining the 0.3.0 feature/service behavior. It is not a new mock-location or anti-detection mechanism.

## Visual direction

- Ink/navy canvas and explicit layered Material surface colors.
- Periwinkle primary action/profile accents, navigation blue for tools/routes, soft mint for places/metrics.
- Teal remains a live signal, amber a warning, and rose Stop/error. Stop uses a clearly distinct rose treatment.
- Complete local-font typography hierarchy; monospace reserved for measured values rather than every label.
- Packaged Canvas/vector-style feature icons and tinted icon tiles; no remote assets, network fonts or new runtime dependencies.

## Layout changes

- The destination dock splits latitude and longitude into readable value tiles. At large text scale it switches to compact full-width coordinate rows.
- The main Go/Stop action and four-icon Search/Spots/Route/More navigation stay outside scrolling destination details. Busy profile state disables the session action rather than exposing a silent no-op.
- The map status area and dock share a constrained Column, with separate maximum height budgets. Expanded top details scroll instead of covering the bottom action area.
- Network details are collapsible. Actual stale/leak/tunnel-bypass warnings are summarized on the collapsed entry; provider actions remain inside the expandable panel.
- More is a grouped toolkit with destination Save/Copy/Share cards and icon-led creation/session/settings rows. Its title/Done control stays fixed while the content scrolls.
- Profile cards distinguish Prepare/Start from secondary edit/duplicate/delete operations; destructive actions keep the established confirmation path.
- Dashboard speed, distance, elapsed time and injection count use colored metric cards, with remaining facts in aligned rows.
- Shared sheets, typography, semantic colors and key-value rows now use the same design language. Both key-value columns are constrained to avoid collisions.

## Verification

- Full unit/Robolectric suite passed: 1,415 executions, zero failures/errors/skipped. This includes repeated debug/release variants, not 1,415 unique device cases.
- Six additional theme/contrast/type contract tests; relevant text contrast tests require at least 4.5:1.
- Three actual Compose component render tests: 360×780 destination dock; grouped toolkit; 320×568 Persian RTL at 150% text scale. The constrained render asserts that top status and dock rectangles do not overlap and that the dock remains within the viewport.
- The render fixtures use sample coordinates and no live map/network. They do not certify native MapLibre, overlays, VPN routing, a keyboard, a real Android launcher or an OEM device.
- Standalone lintDebug passed with warnings, and the debug APK assembled and signature-verified.
- English/Persian resource parity is checked during packaging; feature callbacks and explicit pin-selection behavior are retained.

The UI renders under docs/design-previews are outputs from the actual Compose components, not AI-generated mockups. The montage labels the fixture/no-live-map scope. The existing feature report remains docs/FEATURE-VERIFICATION-0.3.0.md; its runtime limitations still apply.
