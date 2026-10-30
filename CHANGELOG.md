# Changelog

All notable changes to GhostRoute. Format: keep-a-changelog. Versioning:
semver. This file was retro-started during v0.2.1 — earlier releases are
summarized in docs/RELEASE-*.md and PROJECT_REQUIREMENTS.md.

## 0.5.3 — Live with IP holds on VPN loss, offline chip, visible start errors (2026-10-30)

- **Live with IP now follows the VPN exit only.** A session becomes VPN-anchored when a lookup is seen to ride a VPN. If the VPN then goes away, the last location is held instead of moving to your real address (0.5.0 to 0.5.2 followed the direct address after 20 seconds, which would move the device to where you really are). The chip shows "live with IP · waiting for VPN"; a VPN carrying the lookups again resumes following; turning Live with IP off and on again starts a fresh session that follows whatever connection exists. Sessions that never saw a VPN are unchanged. This default was chosen for a privacy-first app; it is one rule in `RuntimeIpSync.pushLocked` if you prefer the old behavior.
- **Fixed:** turning Live with IP on while GhostRoute bypasses the VPN adopted the real location (the background path already refused; this path did not).
- **Fixed:** the online chip stayed "offline" after a failed map load until the map restyled. Device connectivity now drives it, and every network change re-probes the VPN status.
- **Fixed:** a start refused because GhostRoute is not the mock location app did nothing visible. A banner explains the fix (three wordings: another app selected, access revoked, not selected) and opens Diagnostics. Found while checking my own checklist: I had written that the app shows a message, and it did not.
- **Caught before shipping:** the first version of the refusal listener registered a receiver that was still null (a Kotlin initialization-order trap: the registering `init` block ran above the property). A test that checks the receiver is really registered fails against that ordering (checked by temporarily putting it back).
- Added `docs/DEVICE-TEST-CHECKLIST.md`.
- Verification: 1,587 unit-test executions, 0 failures, errors or skipped (app debug and release 449 each, core-data 310 each, engine 69; core-data and engine were not re-run in the last pass because nothing in them changed). Lint: 0 errors, 65 warnings. Debug APK assembled and verified with `apksigner`. No emulator or device was available.
- Not changed: the exported command service and the wake lock (owner decisions, see the verification notes).

## 0.5.2 — route start fix, safer starts, Live with IP safety (2026-10-30)

Two more independent read-only audits (core-data / IP sync, and Route Studio / profiles / engine) reported. Each claim was re-checked in the code before acting. The Route Studio audit found a long-standing bug that the earlier releases and their tests had missed.

- **Fixed (present in the original upload):** Route Studio's Start, Run route and Restart from an idle state sent START with route extras, and the service ignored them, so a *stationary* mock started at the first waypoint. The route only moved the device if a simulation was already running (SET_ROUTE). Follow roads (0.5.0, 0.5.1) was affected the same way. START now honours `extra-route-points`, `extra-route-speed` and `extra-route-mode`. The old tests only checked the extras on the outgoing intent and never fed it to the service; new end-to-end tests do (plain route and Follow roads), and fail against the old behaviour (checked by temporarily putting the old code back).
- **Fixed:** a START that was rejected (mock location not granted, malformed extras) could crash the app. START arrives through `startForegroundService()`, and Android takes the app down if the service stops without calling `startForeground()`. The service now enters the foreground first and releases it when nothing runs; a bad command while a run is live changes nothing. This follows Android's documented requirement; the crash itself could not be reproduced without a device. One existing test asserted the opposite (rejected START must not go foreground) and was changed on purpose.
- **Fixed:** rotating the screen replayed the launch intent (the Stop shortcut would stop a newer run; a `geo:` link would move the pin again). It is handled on first creation only. No automated test covers this one-line guard.
- **Fixed:** the home-screen widget was refreshed by a broadcast on every tick (once a second); it now refreshes only when the state changes.
- **Fixed:** the quick-settings tile declared no label or icon; it has both now (not checked on a device).
- **Docs corrected:** the intent table listed route modes `jitter|route` (the code takes `loop`, `once`, `pingpong`), implied `extra-speed` moves the device (it only tunes tick spacing), said UPDATE re-anchors a route run (it restarts the route and ignores the anchor), and said PING answers external callers (the reply is restricted to GhostRoute's own package).

### Live with IP safety (core-data audit)

Some of that audit's claims (missing network permission, no network-change detection, stale pooled connections) were already addressed in 0.5.0. The rest were checked and fixed:

- **Fixed:** an IP change during a **route run** re-anchored the run, which restarts the route at its first point (an UPDATE keeps the movement and rebuilds the strategy). IP locations no longer move a route run, from either the background sync or the "use IP location" action.
- **Fixed:** a lookup that reaches the server **outside the VPN** (the app is excluded from the tunnel) showed the real location and the run followed it. It is held back now.
- **Fixed:** an **IPv6-only answer** while IPv4 failed became the target. A VPN tunnel is typically IPv4-only, so during a flap that answer comes from the physical connection and moved the simulated location to where the device really is. It is reported as stale now and the last location is kept. (A purely IPv6-only network can therefore not use Live with IP.)
- **Fixed:** a location that arrived **while a run was paused** was dropped, so resuming left the device at the old place. Resume now brings it to the latest IP location.
- **Fixed:** Persian users got English place search results because the search never sent the language.
- **Gentler default:** the IP refresh interval defaults to 5 minutes (was 1 minute), because network changes are checked immediately now and each lookup is two requests to a free third-party service whose limits could not be confirmed (public information conflicts). Anyone who chose an interval keeps it.
- **Honest wording:** "recording is off" no longer claims lookups are not saved; the latest lookup is still cached so the app can resume after a restart.
- **Found, not changed:** GPX import accepts only `trkpt` entries and does not check the XML namespace; a CSV re-import of your own export fails whole as "name already in use" with a generic message; "Refresh now" also retargets a live run when Live with IP is on; the IP history sheet does not show the tunnel status it records; a session's stored config is never written or read.

### Verification and what was left alone

- **Verification:** 1,563 unit-test executions, 0 failures, errors or skipped (app debug and release 437 each, core-data 310 each, engine 69; core-data and engine were not re-run in the last pass because nothing in them changed after their passing run). Lint: 0 errors, 64 warnings. Debug APK assembled and verified with `apksigner`. No emulator or device was available.
- **Reported by the audits and not changed (details in `docs/FEATURE-VERIFICATION-0.5.0.md`):** jitter movement, altitude and the config bearing exist in the engine but nothing can set them; accuracy is clamped to 5–50 m by the realism policy; saved routes keep geometry only (no speed or mode); the "manual pin" profile block is unreachable; the joystick pad's repeated nudges do not accumulate within one tick and report speed 0; the wake-lock permission is declared but never used (whether ticks stall with the screen off needs a device); the exported command service has no permission.
- Not changed because they are the owner's decision or need a device: the exported command service and a wake lock.

## 0.5.1 — fixes from the first feature audit (2026-10-29)

An independent read-only trace of the map screen, tools, search, spots, settings, diagnostics, timeline and dashboard; each finding was re-checked against the code before acting.

- **Fixed:** search history could never be cleared (the repository method had no caller). The history list now has a Clear action with a confirmation; saved spots and recents are untouched.
- **Fixed:** restoring a backup could overwrite this device's first-run flag, so a file from another device skipped the setup wizard and a minimal file sent a configured device back into it. Restore no longer touches it (it is device state).
- **Fixed:** the IP panel never showed the exit coordinates it was built to show.
- **Clearer:** the IP refresh interval says it applies while Live with IP is on; the timeline retention says older sessions are removed at the next app start; the setup wizard no longer calls the shipped joystick "future".
- **Removed:** the recents quick-access chips, which were built but never shown anywhere (recents remain in the Spots sheet), with their string and test.
- Verification: 1,533 unit-test executions, 0 failures, errors or skipped (app debug and release 422 each, core-data 310 each, engine 69). Lint: 0 errors, 63 warnings. No device or emulator was available.
- **Found, deliberately not changed:** starting a profile saves its interval and accuracy as your global defaults (documented and tested behavior, but a later plain Go reuses them); first launch stages a default pin at the saved or fallback location (Tehran) so Go works before anything is chosen; the online chip may stay "offline" after a failed style load until the map reloads (needs a device to check); Dashboard elapsed time includes paused time; the "all OK" diagnostics line refers to the mock slot only; a few unused pill composables remain; About lists the same GitHub address in two rows (both rows were specified verbatim).
- Reports from the other two audits (Route Studio / profiles / engine path, and core-data / IP sync) had not arrived when this was packaged.

## 0.5.0 — Follow roads, Live with IP, simpler profiles (2026-10-29)

- **Follow roads** (Route Studio, off by default). Waypoints are snapped to real roads through the public routing service routing.openstreetmap.de (OpenStreetMap data, no key): footpaths up to 9 km/h, bike routes up to 25 km/h, roads for cars above that. **Show road route** previews it on the map; Start, Apply and route profiles use it. Safeguards, each covered by tests against real server responses: a waypoint more than 500 m from a suitable road is rejected (the server otherwise snaps it to a distant road), more than 100 waypoints is rejected, long geometries are simplified to the engine's 2,000-point limit, and any failure starts nothing, with no straight-line fallback. Nothing is sent while the switch is off. The About page and privacy note list the new destination.
- **Live with IP** (the chip formerly labelled "ip sync"). Turning it on goes to the current IP location and starts a simulation there; while a simulation runs, a network change (VPN reconnect or server switch, Wi-Fi/cellular switch) triggers a re-check after a short settle time, drops pooled connections, and retries while the network stabilises. If a VPN drops, it waits 20 seconds for it to return before following the direct address. The periodic check still covers VPNs that switch server without reconnecting. Needs the normal ACCESS_NETWORK_STATE permission, which also fixes the VPN status line (it could never read the network state before).
- **Profiles are simpler**: one-step "Save current setup", a plain summary and a single Start button per profile, secondary actions in the menu, and no interval/accuracy fields in the editor.
- **Liberty is the default map style.** Dark and light stay in Settings; an explicit earlier choice is kept.
- **Fix:** a race where the Follow roads switch could be overwritten by a stale stored value while the write was still in flight.
- New tests: road router (real server fixtures, snap limit, simplification, error cases), path simplifier, Live with IP follower (virtual time), network-change source, Route Studio and profile road behaviour, one-step profile save, Live with IP toggle.
- Verification: 1,535 unit-test executions, 0 failures, errors or skipped (app debug and release 424 each, core-data 309 each, engine 69). Lint: 0 errors, 64 warnings (two new plural-wording notes). No emulator, device, native-map, overlay or VPN test was run, so Follow roads on real maps and Live with IP across a real VPN switch are untested on hardware.
- Not changed: the exported command service keeps its documented intent API; release signing; route seek; timer persistence across process death. `showNotification` and `units` are stored and backed up but nothing reads them, and no screen exposes them.

## 0.4.0 — pause and resume, plus two fixes (2026-10-29)

- **Pause and resume for the live run.** Pause holds the simulated device in place while samples keep flowing; Resume continues from the same spot with the paused time excluded. Controls: Pause/Resume beside Stop on the map panel, Pause/Resume on the notification, and the explicit `app.ghostroute.PAUSE` / `app.ghostroute.RESUME` intents (documented in the README). The status chip and the dock turn amber while paused, and the session dashboard shows Paused with route progress on the pause-free clock.
- While paused, the GPS-style noise still varies the reported fix slightly, as a stationary phone does. IP-sync pushes are held until resume; the next IP change then applies.
- Auto-stop timers keep counting wall-clock time while paused.
- **Fix:** About-page links could report that no app could open them on Android 11+, because package visibility hid the browser. The manifest now declares the http and https VIEW queries; the lint warning for this is gone.
- **Fix:** an IP-sync update could land after a joystick nudge and pull the device back. The handoff flag is now set synchronously before the nudge's UPDATE, and the push check runs under the same lock. No dedicated race test was added, and the path was not reproduced on a device.
- Version 0.4.0 (versionCode 5).
- Verification: 1,429 unit-test executions with 0 failures, errors, or skips (app debug and release 400 each, core-data 280 each, engine 69). Lint: 0 errors, 62 warnings. Debug APK assembled and verified with apksigner (v2). No emulator, device, native-map, overlay, or VPN test was run.
- Not changed: the exported command service keeps its documented intent API, which lint flags as ExportedService; restricting it is a decision for the owner. Release signing, route seek, and timer persistence across process death are also unchanged.

## [0.3.1] — Night Navigation design update

### Changed
- Ink/navy layered surfaces, periwinkle/blue/mint accents, explicit Material container roles and a complete typography hierarchy.
- A redesigned destination dock with split coordinate tiles, a fixed full-width Go/Stop action and icon navigation for Search/Spots/Route/More. Large-text mode uses compact coordinate rows.
- Shared top/bottom layout budgets, wrapping status chips, collapsible network details and 48dp provider action targets.
- Grouped toolkit with colored icon tiles, improved profile cards with primary Prepare/Start actions, and dashboard metric cards.
- Local vector-style glyphs and shared surfaces; no network assets/fonts/dependencies added. Teal/amber/rose retain live/warning/Stop semantics.

### Verification
- Full suite: 1,415 executions, zero failures/errors/skipped; standalone lintDebug and debug assembly pass.
- Actual Compose render tests include narrow Persian RTL at 150% text size, with a no-overlap assertion for status and dock. These are component fixtures, not native-map/device tests.
- See docs/DESIGN-0.3.1.md and docs/design-previews.

## [0.3.0] — audited feature/test build

### Added
- Profile Prepare / Start profile actions with full route and settings fidelity.
- Route geometry undo/redo, reverse order, walking/cycling/driving speed presets and Ping-pong mode selection.
- Explicit add/move-waypoint map mode with confirmation/cancel, Apply Destination Now, Show simulated position, and 5/15/30/60-minute auto-stop timer.
- Feature-verification report with per-feature evidence, limitations and device acceptance checklist.

### Fixed
- Profile activation blocked by old manual selections; prepared routes lost on Go; live spot profiles retaining old route movement/settings.
- Joystick hold release/cancel, minimize repeat cancellation, disabled overlay teardown; provider-enable partial cleanup.
- Stale tile state and incorrect pre-29 setter; stale search picks/repeated coordinates; frozen timeline selection; IP manual-to-auto polling and Activity-owned sync lifetime.
- Malformed backup schemaVersion crash, invalid service settings, missing retention preference, bounded reads and settings compensation on apply failure.
- GPX failure feedback/size limits and off-Main I/O; Loop estimates; route remaining clock after swaps.
- Engine ticks and commands now share Main dispatch, avoiding concurrent STOP/UPDATE state commits.

### Verification / limits
- Full unit suite: 1,397 executions, zero failures/errors/skips; standalone lintDebug passes with warnings; debug assembly passes.
- No physical-device/emulator smoke test performed. Backup remains two stores with best-effort compensation. Stop/Start is not pause/resume; no hidden claims of routing/offline packs/KML/anti-detection.

## [Unreleased — historical 0.2.x notes]

### Fixed
- IP/VPN lookups now run whenever sync is enabled — previously the poll
  only ran while a simulation session was Running, so the IP panel froze
  the rest of the time (P1 rework; 60 s minimum interval kept).
- A background IP poll never moves the map anymore: no pin, no camera, no
  recent/last-position writes. Placing anything requires the explicit
  "use IP location" action (or a manual pin drop).
- The floating joystick no longer marks IP sync as "manual pin" — no more
  wrong "ip sync · manual pin" chip after nudging; profile activation no
  longer gets blocked by a nudge.
- Manual "refresh now" action for the IP panel, workable with any refresh
  interval including manual-only.
- Saved-position hydration no longer overwrites a geo/share/search selection
  made during startup; camera requests remain pending until MapLibre is ready.
- Camera-only recentering no longer changes the staged destination, and explicit
  map/search/geo selections now take priority over an enabled IP-sync poll.
- Ordinary map pan/zoom no longer changes the staged destination or writes a
  "manual pin" recent; staging requires long-press or the explicit Set
  Destination control. Existing recents labeled "manual pin" can be removed by
  a separate confirm-gated cleanup that leaves other recents and saved Spots intact.
- MapView now receives the host lifecycle events and releases native resources
  through a single guarded teardown path.

### Added
- Long-press map placement for exact coordinates, plus Save, Copy, and Share
  actions on the destination card. Sharing uses an Android `geo:` text link;
  saving writes a named Spot through the existing repository.
- While a simulation is running, the destination card shows straight-line range
  and compass bearing from the live simulated fix to the staged destination.
- Named Spots can be exported to CSV or imported from CSV. Import is size/row
  limited, validates the complete file, previews the records, rejects duplicate
  names, and commits the batch in one Room transaction; spreadsheet-formula-like
  name/note values are escaped on export.
- Persistent IP history (P6a): every successful IP lookup — background polls
  included — is recorded on-device (Room `ip_history`; your data never leaves
  the device). The IP panel gains a "history" clock action opening a timeline
  list (newest first: IP, place, how long ago). Recording obeys a retention
  dial — 50 / 200 (default) / 500 kept observations, or completely OFF
  (nothing recorded, nothing changed in the panel) — and consecutive
  identical observations of the same exit update the timestamps instead of
  piling up rows. "Clear" wipes history behind a confirm dialog, and
  "share as CSV" exports the full record through the regular share sheet.
  Recording never moves the map: pins, camera and last position are untouched.
- Honest VPN-exit verification in the IP panel: distinguishes lookups that
  ride the VPN tunnel from the split-tunneling case where this app bypasses
  it (shown as an amber warning instead of presenting the real location as
  the VPN exit), plus "no VPN" and unknowable states that stay silent
  rather than guessed. Device-local (any non-loopback/link-local interface —
  tun, wlan, rndis, eth) addresses shown separately
  from the observed public exit; exit coordinates when the provider
  supplies them. All strings in English and Persian.

### Changed
- Rebuilt the map dock as one full-width, scroll-safe destination panel; the Go/
  Stop action, selected destination, map-center preview, explicit Set action,
  Search, Spots, and More now occupy one adaptive stack rather than opposing
  bottom corners. Buttons wrap or scroll instead of colliding at narrow widths.
- Route Studio, Timeline, Dashboard, Profiles, and Settings remain grouped in
  More, reducing map-screen clutter without hiding primary actions.

### Changed — Visible UI polish (v0.2.1)
- Map top bar is now a branded status hero: GhostRoute mark + live engine
  dot above the state/online/sync pills, on an elevated glass panel.
- The IP panel was rebuilt to be readable: the detected public IP is the
  big hero line, a freshness pill shows how old the data is (teal fresh /
  amber stale), the VPN-tunnel status is a distinct colored banner
  (teal = riding the VPN, amber = app bypasses it / warning, grey = no
  VPN — still silent when unknowable), and refresh/info are proper round
  icon buttons with "use IP location" as a real teal button.
- The map pin got a contrast rim + soft halo so it reads on both the dark
  and the light map styles, and the pill buttons / spot card float with
  real shadows.
- Every bottom sheet now sits on the shared elevated treatment: drop
  shadow, hairline edge, teal signal-glyph next to the title (dashboard,
  diagnostics, route studio), aligned key-value rows with hairline
  dividers (dashboard, diagnostics), and route studio gained section
  headers plus a ghost-branded empty state for the waypoint list.

## [0.2.1-mvp] — in progress
- Notification Stop fixed (broadcast-to-service bug), About identity links,
  first-run developer-mode setup guide (bilingual), P1 IP/VPN rework above.

## [0.2.0] — 2026-10-27
- Route Studio, session recorder + timeline, dashboards/diagnostics,
  location profiles, backup/restore, floating joystick, automation intake,
  search history, third map style, design tokens/motion/a11y — see
  docs/RELEASE-v0.2.0.md.
