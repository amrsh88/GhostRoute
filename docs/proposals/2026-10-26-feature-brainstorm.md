# GhostRoute — Feature Brainstorm, Expansion & Credits Proposal

**Date:** 2026-10-26 · **Status:** PROPOSAL — awaiting Amirreza's approval before any implementation
**Scope:** Research + brainstorm only. No code, no changes to `DESIGN.md` or the MVP plan.
**Builds on:** `/root/GhostRoute/docs/DESIGN.md`, `docs/plans/2026-10-26-ghostroute-mvp.md`

---

## 1. Executive summary

GhostRoute's MVP plan already covers parity-plus against the market: map-first UI, a real engine (movement strategies instead of Fake GPS Pro's random teleport), wizard, spots/search, tile/widget, baseline-compatible intent API, fa/en. This proposal extends it with **48 concrete features** across 10 categories, prioritized into 4 tiers, and singles out the **10 differentiators** most likely to make GhostRoute the best mock-location app available.

The recurring research conclusion: competitors (Fake GPS Pro, GPS Joystick-class apps) have breadth — joysticks, route playback, red-light simulation, GPX — but their weakness is **reliability and honesty about platform limits**: they crash or silently break when the mock slot is cleared (dev-options toggle wipes it), don't diagnose Doze/battery-optimizer kills, and fake " realism" with scatter. GhostRoute's biggest wins are therefore **(a) a diagnostics/recovery layer no competitor has**, **(b) an engine whose realism is principled (speed/bearing/accuracy consistency, quantized tickers)**, and **(c) a Shizuku-assisted repair path** discovered in research that fixes the #1 field complaint (mock slot cleared) without root.

Nothing here requires accounts, telemetry, ads, or mandatory cloud. Every online-optional feature degrades gracefully offline. `:engine` stays pure Kotlin throughout; new engine work (realism models, teleport-smoothing, route generation) lives there; Android-touching work lives in `:app`/`:core-data`.

---

## 2. Competitor & technology research (verified, with sources)

### Competitors

**Fake GPS Pro 5.8.7** (`com.blogspot.newapphorizons.fakegps`) — REA-decompiled in the prior session (evidence in transcript).
- Verified: legacy + v2 service split (abandoned-architecture smell); Google analytics/ads SDKs bundled; movement is a **random teleport within a radius each tick** — trivially detected; intent API `START`/`STOP`/`UPDATE` with `extra-simulator-config` parcelable + lat/lng string extras; fresh `time`/`elapsedRealtimeNanos` on injection (this part is correct and worth keeping).
- Gaps: no route following, no diagnosis on `SecurityException`, no schedules beyond naive timers, ad-interrupted UX, no RTL, no offline story.

**FakeTraveler 2.3.2** (F-Droid, GPL-3.0+, active — last release May 2026). Sources: f-droid.org/packages/cl.coders.faketraveler/, GitHub README (mcastillof/FakeTraveler).
- Verified features: map long-press pick, coordinate entry, **geo: link intake**, "mock over a period of time", **step-movement each interval with speed**, changeable map provider.
- Verified known pitfall (their own README): reported location "bounces" because **Wi-Fi scanning and Bluetooth scanning** (system location services) compete with the mocked GPS — user must disable them manually. → GhostRoute can auto-detect and warn (F-37).
- Gaps: no search, no routes, no automation, bare-bones UI. Strong signal that FOSS users want *reliability*, not just features.

**GPS Joystick / Fake GPS Joystick class** (Play Store apps, e.g. "GPS Joystick - Fake GPS Location", Lexa/hihao apps). Sources: web search of current Play listings, 2025–2026 reviews.
- Verified feature set: floating **on-screen joystick** bubble, **route playback with speed control**, waypoint marking, **red-light simulation mode** (stops at traffic lights along route), **GPX import**, **record real movement then replay**, favorites, "statusbar/mock indicator" warnings.
- Verified common weaknesses: heavy ads, frequent "not working after Android update" complaints, battery-optimizer kills with no guidance, no per-route realism profiles, no offline routing.
- "Red-light simulation" and "teleport smoothing" are competitor-validated realism ideas worth absorbing properly.

**Testing utilities** (Mock Locations (fake GPS path) by Sixo, Android emulator extended controls): route loading from GPX, multi-point playback — confirms route replay as a developer expectation, not a gamer gimmick.

### Platform facts (all verified against developer.android.com / official docs)

| Fact | Consequence |
|---|---|
| `Location.isMock()` added API 31; `isFromMockProvider()` deprecated at 31 but still functional below | Consumers can detect mocks; only a root/Xposed companion (approach B) can strip the flag — do **not** promise anti-detection in the non-root app. |
| `addTestProvider`/`setTestProviderLocation`/FLP `setMockMode` require the `mock_location` appop, grantable only via Developer Options (or root/shell) | The wizard is non-optional. Shizuku path below can automate the grant. |
| `appops set <pkg> android:mock_location allow` works from shell UID 2000 → **Shizuku (or wireless ADB) can grant mock-location without root**; turning dev options off/on **clears the mock slot** (settings `secure mock_location` + appop reset) while the setting may still *display* the app | Two features: Shizuku-assisted setup (F-08) and **Mock-Slot Repair** (F-09) — a repair flow no competitor has. |
| Android 14+ (API 34): FGS must declare a type and hold the matching permission; `specialUse` requires `FOREGROUND_SERVICE_SPECIAL_USE` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` | Already in MVP plan; correct. |
| Android 12+ (API 31): background FGS start restrictions; a service needing **while-in-use location permission** can't be *created* from background | Scheduled-start features must use `setExactAndAllowWhileIdle` alarms + a visible-activity exemption path, or start the FGS from a full-screen intent/notification. Design constraint for F-22. |
| Android 15 (API 35): `BOOT_COMPLETED` receivers can't launch `dataSync/camera/mediaPlayback/phoneCall/mediaProjection/microphone` FGS types — `specialUse` is **not** in the banned list | Boot-resume of an active simulation (F-24) is feasible via `specialUse`, but must re-verify the mock appop at boot. |
| FGS are exempt from Doze; `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is Play-policy-restricted and OEM Doze variants (Xiaomi/Samsung) still kill apps | Diagnostics must detect OEM battery killers and instruct; do not request exemption by default (F-38). |
| Nominatim public policy: ≤1 req/s total, identifying User-Agent, **no autocomplete**, cache required, ODbL attribution (operations.osmfoundation.org) | Geocoding must be Photon-first for typeahead. |
| **Photon** (komoot): keyless public endpoint, search-as-you-type explicitly permitted, Apache-2.0, reverse geocoding; "reasonable use" | Primary geocoder (F-14). |
| OSRM demo server (`router.project-osrm.org`): ≤1 req/s, non-commercial, attribution + honest UA, no SLA | Road-snap for MVP is demo-server based with cache + graceful fallback; self-host/Valhalla later. |
| Open-Meteo Elevation API: free, keyless, Copernicus DEM GLO-90, ≤100 coords/request | Route altitude enrichment (F-05), cached. |
| MapLibre Native: BSD-2, `OfflineManager` offline packs on Android; OSM tile policy **forbids apps offering prefetch** of osm.org tiles; OpenFreeMap allows caching/commercial | Offline packs must point at OpenFreeMap/MapTiler-style providers, not raw osm.org tiles (F-15). |
| BRouter: MIT, offline OSM router with elevation, segment files per region | Long-term offline routing option (Tier 3/4), not MVP. |

### Libraries worth evaluating
- **MapLibre Native Android** (already planned) + `maplibre-compose` community bindings.
- **Shizuku API** (`dev.rikka.shizuku`) — shell-privileged operations, no root. Apache-2.0.
- **BRouter** (MIT) — offline routing, later.
- **osmdroid** only if MapLibre offline proves limiting — not needed now.
- No new mandatory deps for Tier 1 beyond what the MVP plan already has.

---

## 3. Feature proposals (48)

Format per feature: **Description / User value / Complexity (L/M/H/VH) / Dependencies / Offline / Architecture impact / Priority / Release target / Risks.** "Arch" values: `:engine`, `:core-data`, `:app`, `docs`. Priority: E=Essential, HV=High-value, O=Optional, X=Experimental.

### A. Advanced Location Simulation

**F-01 · Virtual Joystick Bubble**
Floating draggable bubble + directional pad; each tick moves the mock pin by `speed × dt` in the stick direction; works while spoofing is active.
Value: real-time walking/driving without redrawing a route. Complexity: **M**. Deps: `SYSTEM_ALERT_WINDOW` overlay permission (already in wizard). Offline: **full**. Arch: `:app` (overlay service) + new `JoystickSpec` in `:engine` (already sketched in DESIGN §6). Priority: **HV**. Target: next release. Risks: OEM overlay restrictions; must pause joystick injection when overlay is dismissed; battery from 1 Hz overlay updates — throttle to engine tick.

**F-02 · Route Builder on Map**
Draw multi-point routes by tapping/long-pressing; snap each leg to roads via OSRM (cached per leg); editable waypoint list (insert/move/delete); save as named route.
Value: the headline "road-following" feature done properly, editable unlike competitors. Complexity: **H**. Deps: OSRM demo (rate-limited), route table in Room (already in DESIGN §6 as `Route`). Offline: **partial** (snap online; raw polylines offline). Arch: `:engine` (`RouteSpec` exists), `:core-data` (route CRUD), `:app` (editor UI). Priority: **HV**. Target: next release. Risks: OSRM demo SLA-less → aggressive cache, offline "straight-line legs" fallback; snapping can mangle off-road intents — keep "no-snap" toggle.

**F-03 · Playback Controls (pause / resume / reverse / seek)**
Transport bar during route simulation: pause, resume, restart, reverse direction, scrub timeline, skip-to-waypoint; live ETA per waypoint.
Value: turning route playback into a controllable tool. Complexity: **M**. Deps: F-02. Offline: **full**. Arch: `:engine` (strategy gets `seek/step` semantics), `:app` UI. Priority: **HV**. Target: next release. Risks: seek + realism interplay (jump must re-emit plausible samples); keep pause = stationary samples, not stop-injecting.

**F-04 · Speed Profiles & Acceleration Curves**
Per-route movement profile: base speed ± jitter band, acceleration limits, cruise/top speed, walk/bike/car/rail presets (with plausible accel/decel).
Value: GPS-forensics-flavored consistency — speed spikes are the easiest tell after teleports. Complexity: **M**. Deps: F-02. Offline: **full**. Arch: `:engine` only (pure). Priority: **HV**. Target: next release. Risks: over-tuning; ship presets, hide advanced sliders.

**F-05 · Altitude Interpolation**
Fetch (or import) elevation for route points via Open-Meteo (≤100 pts/req, cached in Room) or GPX metadata; interpolate smoothly; static spots get single altitude.
Value: consumers (e.g., fitness apps, some geo-fences) check altitude plausibility. Complexity: **L–M**. Deps: Open-Meteo (keyless, cached). Offline: **partial** (cached; GPX offline). Arch: `:core-data` (client+cache), `:engine` (interpolation). Priority: **O→HV** once routes exist. Target: next release. Risks: ~90 m grid resolution — don't over-promise precision.

**F-06 · Teleport Smoothing ("Fly-in")**
When the user re-targets mid-run or starts far from the previous fake point, optionally interpolate the move at a chosen max speed instead of a jump (competitor-validated idea, done honestly).
Value: the single most obvious tell today is the jump. Complexity: **L–M**. Deps: none. Offline: **full**. Arch: `:engine` (new movement wrapper). Priority: **HV**. Target: next release. Risks: off by default; user must understand a moving-in period.

**F-07 · Red-Light / Traffic Behavior (experimental)**
Along snapped road routes, simulate realistic speed histograms: decelerate to 0 at mapped crossing nodes for a plausible dwell, resume.
Value: differentiator against GPS Joystick's "red light mode"; makes long drives look real. Complexity: **VH**. Deps: F-02 + OSM node classification. Offline: **partial**. Arch: `:engine`. Priority: **X**. Target: experimental. Risks: road-class data quality varies globally; ship per-profile toggle, off by default.

**F-08 · Shizuku-Assisted Setup (no-root grant)**
Optional: if Shizuku is installed/running, GhostRoute requests shell access and runs `cmd appops set <pkg> android:mock_location allow` itself — skipping the manual mock-app picker.
Value: cuts the most confusing wizard step; huge for non-technical users. Complexity: **M**. Deps: Shizuku API (Apache-2.0), optional runtime. Offline: **full** (Shizuku runs locally). Arch: `:app` (new `system/GrantBridge`), wizard step variant. Priority: **HV**. Target: next release. Risks: must degrade cleanly when Shizuku absent; still needs dev-mode on (Shizuku needs ADB once — document honestly).

**F-09 · Mock-Slot Repair ("Self-Heal")**
Detect the known failure mode (dev options toggled off/on clears `secure mock_location` + appop while Settings still *shows* the app → every injection throws `SecurityException`); diagnose it explicitly; offer guided repair (re-select instructions, or one-tap via F-08 Shizuku when present).
Value: fixes the #1 field complaint across all competitor reviews; nobody has this. Complexity: **L–M**. Deps: F-08 optional. Offline: **full**. Arch: `:app` diagnostics (extends planned `SecurityException` handling in Task 4). Priority: **HV**. Target: next release. Risks: none serious; UX copy must not imply rooting.

**F-10 · GPX / KML / CSV Import & Export**
Import routes and tracks (GPX trk/rte, KML LineString/Placemark, CSV lat,lng[,name]); export spots, routes, recorded tracks; schema-documented; tolerant parsing with per-line error report on bad imports (no partial silent corruption).
Value: interop with Strava/Komoot/Google Earth exports and dev tools. Complexity: **M**. Deps: none beyond kotlinx.serialization/DI-agnostic parsing (pure `:engine`/`:core-data`). Offline: **full**. Arch: `:engine` (parsers, pure), `:core-data` (import service), `:app` (picker UI). Priority: **HV**. Target: next release. Risks: GPX dialect zoo — validate with fixture corpus; cap file size.

**F-11 · Route Recording & Replay**
Record real movement (own FusedLocation-or-LocationManager stream while *not* spoofing; FLP optional) → save as Track; replay with original or scaled speed.
Value: the "I was actually there yesterday" use case. Complexity: **H**. Deps: real-location permission (already declared), `Track` table (in DESIGN §6). Offline: **full**. Arch: `:app` (recorder service/state), `:engine` (`TrackSpec` exists). Priority: **O**. Target: later release. Risks: recorder must not conflict with active spoof; battery; Android 14 while-in-use rules for background recording — start recorder only in foreground.

**F-12 · Waypoint Dwell & Behavior Tags**
Per-waypoint attributes: dwell N seconds, speed limit on leg, "pause here", loop marker.
Value: scenario authoring (visit 3 shops, 5 min each). Complexity: **M**. Deps: F-02. Offline: **full**. Arch: `:engine` + `:core-data` schema. Priority: **O**. Target: later release. Risks: UI complexity creep — keep tags minimal (dwell + speed only in v1).

**F-13 · Custom Movement Profiles**
Named bundles of realism parameters (speed band, accel, accuracy behavior, smoothing) selectable per spot/route/profile — the engine-side counterpart of F-27.
Value: one-tap switching between "walking", "driving", "testing". Complexity: **L**. Deps: F-04. Offline: **full**. Arch: `:engine` (specs) + `:core-data` (profile rows exist as `Profile`). Priority: **HV**. Target: next release. Risks: low.

### B. Maps & Location Discovery

**F-14 · Search: Photon-first, Nominatim-fallback, History & Suggestions**
Search-as-you-type via Photon (keyless, autocomplete permitted by policy); Nominatim fallback for miss/hard queries (1 req/s, honest UA `GhostRoute/<ver> (+github.com/amrsh88)`); local history; reverse geocode of dropped pin.
Value: fast, policy-compliant, no keys. Complexity: **M** (refines MVP Task 7). Deps: network; cached results. Offline: **partial** (offline: recents + coordinate entry still work). Arch: `:core-data` (`GeocodeClient` refactor). Priority: **E-refinement**. Target: MVP improvement. Risks: Photon outage → fallback + explicit offline notice.

**F-15 · Offline Map Packs**
Download map regions (bbox or around-spot) as MapLibre offline packs; manage list (size, age, delete); tiles from cache-friendly providers (OpenFreeMap style; *not* raw osm.org tiles whose policy forbids app prefetch).
Value: use GhostRoute offline/VPN'd/airplane-mode; differentiator vs web-map competitors. Complexity: **H**. Deps: MapLibre `OfflineManager`; provider ToS care. Offline: **full after download**. Arch: `:app` (map layer), `:core-data` (pack registry). Priority: **HV**. Target: later release. Risks: storage size; tile-provider terms vary — implement per-provider "allow offline" flag; quota cap.

**F-16 · Multiple Map Styles**
Light/dark/satellite-ish styles from OpenFreeMap/MapTiler-free styles + `values-fa`-aware labels; persisted choice.
Value: polish + readability at night (core demographic uses this at night). Complexity: **L**. Deps: style URLs. Offline: **partial** (packs cover chosen style only). Arch: `:app`. Priority: **HV**. Target: MVP improvement (cheap). Risks: style availability changes — pin URL + fallback default.

**F-17 · Coordinate Input Everywhere**
Already planned (Task 7): DD + DMS. Extensions: paste auto-detect (Google/OSM/plus-code URLs and `geo:` URIs), MGRM skipped (niche), quick swap lat/lng, live map preview of typed coords.
Value: power users and spreadsheet imports live on this. Complexity: **L**. Deps: existing `parseCoords`. Offline: **full**. Arch: `:engine` (parser), `:app` UI. Priority: **HV**. Target: MVP improvement. Risks: low.

**F-18 · Saved Collections + Batch Ops**
Folders already planned; add: multi-select (move/delete/export), "nearby" grouping, pin colors/icons per folder.
Value: organizing 50+ spots (route farmers, testers). Complexity: **M**. Deps: Task 8 schema. Offline: **full**. Arch: `:core-data`, `:app`. Priority: **O**. Target: later release. Risks: low.

**F-19 · Distance/Bearing Readout + Measure Tool**
Show distance & bearing from current fake (or real) position to pin; simple multi-point measure on map.
Value: scenario planning ("is this within the geo-fence?"). Complexity: **L**. Deps: geo math already in `:engine`. Offline: **full**. Arch: `:engine` (haversine exists), `:app`. Priority: **O**. Target: next release. Risks: low.

### C. Automation & Integrations

**F-20 · Tasker / Automation App Integration (documented intent API)**
MVP already has baseline-compatible `START/STOP/UPDATE`. Extend: `GET_STATUS` broadcast reply, `SET_ROUTE` with GPX URI extra, Tasker *event* plugin on permission-lost/state-change, published intent-API doc page.
Value: Tasker/MacroDroid/Automate users are a large, vocal segment. Complexity: **M**. Deps: none. Offline: **full**. Arch: `:app` (service actions — mostly done), `docs`. Priority: **HV**. Target: next release. Risks: keep API stable once published; version the extras.

**F-21 · Scheduled Simulations**
Start/stop at times (exact alarms `setExactAndAllowWhileIdle` + boot re-arm), with schedule rows (cron-lite: daily/weekly + window), per-schedule profile.
Value: "fake commute" automation without Tasker. Complexity: **H**. Deps: Android 12+ FGS background-start rules — schedule start needs an exemption path (full-screen intent notification the user taps, or battery-optimization exemption). Offline: **full**. Arch: `:app` (AlarmManager + BootReceiver), `:core-data` (schedule table). Priority: **O**. Target: later release. Risks: OEM alarm throttling; honest docs about Android 12+ constraint; fail-safe = notification prompting user, never silent failure.

**F-22 · Geofence Triggers (real-GPS based)**
Enter/exit real regions (perimeter geofencing on the *real* location stream while not spoofing, or on fake position while spoofing) trigger profile/stop/start.
Value: "when I get home, stop spoofing". Complexity: **VH**. Deps: F-11 recorder infra (location stream), no GMS geofencing — hand-rolled perimeter checks. Offline: **full**. Arch: `:app` + `:engine` (point-in-polygon). Priority: **X**. Target: experimental. Risks: battery; coexistence with active spoof; ship read-only "notify me" before "act on my behalf".

**F-23 · Notification Actions & Persistent Mini-Controls**
Notification with Stop / Pause / Next-waypoint actions + trip stats (already partly planned as notification); add "favorite switcher" actions.
Value: control without opening the app. Complexity: **L**. Deps: FGS already exists. Offline: **full**. Arch: `:app`. Priority: **HV**. Target: MVP improvement. Risks: Android 13 notification permission already in wizard.

**F-24 · Boot & Crash Recovery**
Persist active sim config; on `BOOT_COMPLETED` (specialUse allowed — verified above) re-arm if it was active; on abnormal process death, detect last state and offer one-tap resume; also auto-detect "mock slot cleared" (F-09).
Value: competitor's worst gap — everything silently dies on reboot. Complexity: **M–H**. Deps: BootReceiver, state persistence. Offline: **full**. Arch: `:app` (+ `:core-data` last-state row). Priority: **HV**. Target: next release. Risks: Android 15 FGS-from-boot restrictions satisfied via `specialUse`; verify per-OEM.

**F-25 · Local HTTP Control API (optional, off by default)**
Loopback-only HTTP server (127.0.0.1, user-enabled, random token) exposing start/stop/status — for desktop scripts and adb-driven test rigs.
Value: CI/dev testing without root; power-user scripting. Complexity: **M**. Deps: Ktor/NanoHTTPD (small). Offline: **full** (loopback). Arch: `:app`. Priority: **O**. Target: later release. Risks: security surface — loopback + token + explicit opt-in; document loudly.

**F-26 · Quick-Tile Long-Press → Deep Shortcuts**
Tile already planned; add long-press tile → app shortcut to last spot; home-screen shortcuts to profiles (F-27).
Value: one-tap muscle memory. Complexity: **L**. Deps: shortcutManager. Offline: **full**. Arch: `:app`. Priority: **O**. Target: next release. Risks: low.

### D. Profiles & Customization

**F-27 · Named Simulation Profiles**
Bundle: spot or route + movement profile (F-13) + realism + providers + notification style; one-tap activate from app/widget/tile/intent (`START` with `profile` extra).
Value: the "Vacation" / "Game X" / "QA route" workflow. Complexity: **M**. Deps: schema exists in DESIGN (`Profile`). Offline: **full**. Arch: `:core-data`, `:app`. Priority: **HV**. Target: next release. Risks: profile schema versioning from day one.

**F-28 · Theme System & Dynamic Color**
Material 3 dynamic color, dark default-capable, GhostRoute brand accent; per-app language override (Android 13 per-app locale or in-app fallback).
Value: polish expectation. Complexity: **L**. Deps: Compose M3 (already). Offline: **full**. Arch: `:app`. Priority: **HV** (mostly already in MVP). Target: MVP improvement. Risks: low.

**F-29 · Persian RTL Polish Pass**
Dedicated RTL audit: mirrored navigation, Persian digit option for coordinates, Persian place names in search results (Photon `lang=fa`).
Value: Persian users are a first-class audience; competitors ignore them entirely. Complexity: **M**. Deps: none. Offline: **full**. Arch: `:app`, `:core-data` (search lang param). Priority: **HV**. Target: next release. Risks: coordinate mixing (LTR digits in RTL text) — test with pseudo-locale.

**F-30 · Units & Format Settings**
Metric/imperial, DD/DMS default, 12/24h, Persian digits toggle.
Value: correctness for US users + Persian preference. Complexity: **L**. Offline: **full**. Arch: `:app`, `:engine` (format helpers). Priority: **HV**. Target: MVP improvement. Risks: low.

**F-31 · Profile Import/Export**
Share a profile as a single `.grprof` JSON file (documented schema; includes route GPX inline); import with conflict resolution.
Value: community route-sharing without any cloud. Complexity: **M**. Deps: F-10 parsers. Offline: **full**. Arch: `:core-data`, `docs`. Priority: **O**. Target: later release. Risks: schema evolution — version field mandatory.

### E. Data Management

**F-32 · Full Backup & Restore**
Single-file export of everything (spots, folders, routes, tracks, profiles, schedules, settings) → JSON + optional attachment; restore with duplicate detection (same-coords-or-name).
Value: device migration without Google backup. Complexity: **M**. Deps: Room → JSON serializer. Offline: **full**. Arch: `:core-data`, `:app`. Priority: **HV**. Target: next release. Risks: versioned schema; corrupted-file recovery = fail loudly, restore nothing.

**F-33 · Optional Local Encryption**
SQLCipher (or DataStore-encrypted) toggle for the DB — off by default, documented tradeoff (no cloud to worry about anyway; protects against casual device inspection).
Value: privacy-first brand credibility. Complexity: **M**. Deps: SQLCipher (adds dep — evaluate `androidx.sqlite` + Tink alternative). Offline: **full**. Arch: `:core-data`. Priority: **O**. Target: later release. Risks: key custody question (what protects the key?); be honest in docs that it's against casual access, not a forensic adversary.

**F-34 · Storage Dashboard & Cleanup**
Show DB sizes, offline pack sizes, log size; one-tap prune (old recents, orphaned files).
Value: trust + maintenance. Complexity: **L**. Offline: **full**. Arch: `:app`, `:core-data`. Priority: **O**. Target: later release. Risks: low.

**F-35 · Safe Import Pipeline**
All imports (F-10/F-31/F-32) go through: parse → validate → preview list → apply in transaction → per-item error report; never mutate live data on partial failure.
Value: "reliable recovery from corrupted imports" requirement made concrete. Complexity: **M**. Deps: none. Offline: **full**. Arch: `:core-data`. Priority: **HV**. Target: next release (build with F-10). Risks: none.

### F. Diagnostics & Reliability

**F-36 · Diagnostics Center**
Single screen: mock-permission state, mock-slot intact (verify by test-inject or appop read via Shizuku), FGS alive, providers mocked, battery-optimization state, OEM battery-killer detection (Xiaomi/Samsung heuristics), last injection timestamp, SecurityException history, log ring buffer (local only).
Value: converts "it doesn't work" into a copyable, privacy-clean diagnosis. **The killer differentiator.** Complexity: **M–H**. Deps: Shizuku optional for appop read. Offline: **full**. Arch: `:app` (diagnostics module), `:core-data` (event log). Priority: **HV**. Target: next release. Risks: keep log strictly local & redacted; export excludes coordinates by default.

**F-37 · Interference Warnings**
Detect/advise about the FakeTraveler-documented issue: Wi-Fi/Bluetooth scanning location sources fighting the mock; Settings deep-links to toggle them; also detect "GPS only" vs battery-saving location mode mismatches.
Value: kills the "location bounces" class of bug reports. Complexity: **L–M**. Offline: **full**. Arch: `:app`. Priority: **HV**. Target: next release. Risks: settings differ per OEM — best-effort deep links with fallback instructions.

**F-38 · Battery & Doze Guidance**
Honest education screen: FGS keeps tickers alive through Doze (verified), but OEM killers still exist; optional (user-initiated) link to battery-optimization exemption settings — never auto-request (Play-restricted, and anti-goal).
Value: fewer "it stopped at 3am" reports. Complexity: **L**. Offline: **full**. Arch: `:app`. Priority: **HV**. Target: next release. Risks: none.

**F-39 · Simulation Health Monitor**
Engine emits self-checks each tick (last sample age, providers acknowledging, SecurityException count); UI chip turns amber/red; auto-recovery attempts (re-register providers) with escalation to user.
Value: reliability as a feature. Complexity: **M**. Deps: FGS (done in plan). Offline: **full**. Arch: `:engine` (health counters) + `:app`. Priority: **HV**. Target: next release. Risks: avoid recovery storms — capped retries.

**F-40 · Local Bug-Report Export**
Structured export (settings summary, diagnostics, sanitized logs, device info: Android ver/OEM/build, no coordinates) as a shareable file.
Value: support without telemetry. Complexity: **L**. Offline: **full**. Arch: `:app`. Priority: **O**. Target: next release. Risks: redaction correctness — coordinates must be stripped by default with explicit user toggle to include.

### G. Advanced & Experimental

**F-41 · Offline Route Generation**
Generate plausible road-following routes offline: straight-line fallback (already planned), or bundled low-cost grid/waypoint smoothing (bezier/Chaikin over user points) without server snapping; full OSRM/Valhalla when online.
Value: offline-first promise kept for the flagship feature. Complexity: **M–H**. Offline: **full**. Arch: `:engine` (pure geometry). Priority: **HV**. Target: next release (straight-line + smoothing first, snapping upgrade when online). Risks: unsnapped routes look non-road; label honestly.

**F-42 · Route Library & Sharing**
Curated/local library of routes (city walks, drives) importable from GPX; user library with tags/search.
Value: content flywheel, no server needed (files). Complexity: **M**. Deps: F-10. Offline: **full**. Arch: `:core-data`, `:app`. Priority: **O**. Target: later release. Risks: no hosting — purely user-supplied files; keep it that way (privacy).

**F-43 · Advanced Simulation Statistics**
Trip history (per profile/route): distance, duration, avg/max speed, samples count; charts; all local.
Value: QA teams and realism tinkerers. Complexity: **M**. Offline: **full**. Arch: `:core-data`, `:app`. Priority: **O**. Target: later release. Risks: storage growth — prune policy.

**F-44 · Per-App Location Hooking (Root Companion, separate artifact)**
Zygisk/Xposed module: hook `LocationManager`/FLP per target app, strip `isMock()`, no dev-mode needed. Separate APK/module, never bundled; degrades gracefully.
Value: the true "pro tier" — verified `isMock()` is the detection vector, and only hooking removes it. Complexity: **VH**. Deps: root, Magisk/Zygisk. Offline: **full**. Arch: separate repo/artifact (`:companion` or standalone). Priority: **X**. Target: experimental/Phase 3 (as DESIGN §3-B already scoped). Risks: Play Integrity breakage for users; maintenance across Android versions; legal/ToS gray zone — keep entirely optional, unaffiliated branding.

**F-45 · Developer Testing Mode**
"QA mode": inject synthetic nmea-like extras? — no; realistic scope: fixed-seed realism (reproducible noise), sample-export of injected stream to CSV, provider-level toggle matrix (which providers mocked).
Value: the dev/tester audience (Mock Locations (fake GPS path) users) gets a proper tool. Complexity: **L–M**. Offline: **full**. Arch: `:engine` (seeded RNG), `:app` (export). Priority: **O**. Target: later release. Risks: low; clear labeling to avoid confusing consumers.

**F-46 · Route Sharing via Geo Links / Deep Links**
Share a route as a `geo:`/custom-scheme deep link or file; open-with flow handles incoming links (baseline FakeTraveler already accepts geo: — match + exceed).
Value: frictionless spot handoff between users/devices. Complexity: **L**. Offline: **full** (links carry coords). Arch: `:app` (intent filters — partially in MVP). Priority: **HV**. Target: MVP improvement. Risks: none.

**F-47 · Widget Pro (configurable)**
Per-widget target spot/profile picker on widget resize; multiple widgets on one screen.
Value: quick-acting multi-scenario users. Complexity: **M**. Deps: Task 10 widget. Offline: **full**. Arch: `:app`. Priority: **O**. Target: later release. Risks: RemoteViews limitations (no per-item dynamic lists without ListView support — keep simple rows).

**F-48 · Watch/Companion (experimental)**
Wear OS quick Stop button + status; or peer-control over the local HTTP API (F-25) from any device.
Value: convenience; also strong demo/flashiness. Complexity: **VH** (Wear) / **M** (via F-25). Offline: **full** (local network). Arch: new `:wear` module or reuse F-25. Priority: **X**. Target: experimental. Risks: scope creep — recommend F-25-based path only.

---

## 4. Prioritization table

Complexity (L/M/H/VH) × Value (1–5) × Dependencies. Sorted by value-per-effort within tier.

| # | Feature | Cx | Value | Depends on | Offline | Tier |
|---|---|---|---|---|---|---|
| F-46 | Geo-link/deep-link route sharing | L | 4 | — | full | 1 |
| F-17 | Coordinate paste/format handling | L | 4 | Task 7 | full | 1 |
| F-16 | Map styles | L | 3 | — | partial | 1 |
| F-28/30 | Theme/units polish | L | 3 | — | full | 1 |
| F-23 | Notification actions + stats | L | 4 | FGS | full | 1 |
| F-10+35 | GPX/KML/CSV + safe import | M | 5 | — | full | 1 |
| F-01 | Joystick | M | 5 | overlay perm | full | 1 |
| F-06 | Teleport smoothing | L–M | 4 | — | full | 1 |
| F-13 | Movement profiles | L | 4 | F-04 | full | 1 |
| F-09 | Mock-slot repair | L–M | 5 | — | full | 1 |
| F-36 | Diagnostics center | M–H | 5 | F-09 | full | 1 |
| F-37/38 | Interference + battery guidance | L–M | 4 | — | full | 1 |
| F-39 | Health monitor | M | 5 | FGS | full | 1 |
| F-24 | Boot/crash recovery | M–H | 5 | F-09 | full | 1 |
| F-02 | Route builder (OSRM snap) | H | 5 | — | partial | 2 |
| F-03 | Playback transport | M | 4 | F-02 | full | 2 |
| F-04 | Speed profiles/accel | M | 4 | — | full | 2 |
| F-41 | Offline route generation | M–H | 4 | — | full | 2 |
| F-14 | Photon-first search | M | 4 | Task 7 | partial | 2 (refines MVP) |
| F-08 | Shizuku setup | M | 5 | Shizuku | full | 2 |
| F-27 | Profiles | M | 5 | F-13 | full | 2 |
| F-32 | Backup/restore | M | 4 | — | full | 2 |
| F-29 | RTL/Persian polish | M | 4 | — | full | 2 |
| F-05 | Altitude interpolation | L–M | 3 | F-02 | partial | 2 |
| F-20 | Tasker doc + events | M | 4 | — | full | 2 |
| F-26 | Tile/shortcut extras | L | 3 | Task 10 | full | 2 |
| F-19 | Measure tool | L | 3 | — | full | 2 |
| F-15 | Offline map packs | H | 4 | — | full | 3 |
| F-11 | Record & replay | H | 4 | — | full | 3 |
| F-12 | Waypoint dwell tags | M | 3 | F-02 | full | 3 |
| F-18 | Collections/batch | M | 3 | Task 8 | full | 3 |
| F-31 | Profile import/export | M | 3 | F-10 | full | 3 |
| F-33 | Local encryption | M | 3 | — | full | 3 |
| F-34 | Storage dashboard | L | 2 | — | full | 3 |
| F-40 | Bug-report export | L | 3 | F-36 | full | 3 |
| F-43 | Trip statistics | M | 3 | — | full | 3 |
| F-45 | Developer testing mode | L–M | 3 | — | full | 3 |
| F-42 | Route library | M | 3 | F-10 | full | 3 |
| F-47 | Widget pro | M | 3 | Task 10 | full | 3 |
| F-21 | Schedules | H | 4 | F-27 | full | 3 |
| F-25 | Local HTTP API | M | 3 | — | full | 3 |
| F-07 | Red-light behavior | VH | 4 | F-02, F-04 | partial | 4 |
| F-22 | Geofence triggers | VH | 3 | F-11 | full | 4 |
| F-44 | Root companion | VH | 5* | root | full | 4 (separate) |
| F-48 | Wear/peer control | VH/M | 2 | F-25 | full | 4 |

*Value 5 but for a niche root-user audience, and a separate artifact.

---

## 5. Recommended release roadmap

**Tier 1 — "Reliability release" (MVP polish, ~4–6 weeks of effort):** F-46, F-17, F-16, F-23, F-28/30 (cheap wins) → then F-10+35 (GPX+safe import), F-01 (joystick), F-06, F-13, F-09, F-36, F-37/38, F-39, F-24.
Rationale: everything here is either (a) finishing planned MVP features to a memorable standard, or (b) reliability/diagnostics — the highest value-per-effort in the entire catalog and what no competitor has. Dependencies: F-36/F-39/F-24 share the event-log + health-counter work (build F-39's counters first). F-10 before any route features (fixtures the parsers).

**Tier 2 — "The engine release":** F-02 route builder → F-03 transport → F-04 speed profiles → F-41 offline generation; parallel track: F-08 Shizuku, F-27 profiles, F-32 backup, F-14 search upgrade, F-29 RTL pass, F-20 Tasker docs, F-05 altitude.
Rationale: routes are the flagship; order inside the chain is forced (builder → transport → profiles). F-08/F-27/F-32 are independent of the route chain and can interleave. OSRM demo dependency is the main schedule risk — F-41's offline smoothing ships first as insurance.

**Tier 3 — "Power release":** F-15 offline packs, F-11 recording, F-21 schedules, plus the O-tail (F-12, F-18, F-31, F-33, F-34, F-40, F-42, F-43, F-45, F-47, F-25).
Rationale: each is valuable but none blocks another; pick by community feedback. F-21 is the hardest (Android 12+ FGS start rules) — design doc update required before implementing.

**Tier 4 — Experimental/separate:** F-07 red-light realism, F-22 geofences, F-44 root companion (separate repo/artifact as DESIGN already scopes), F-48.
Rationale: high complexity or ecosystem-dependent; only after Tiers 1–3 are solid.

Independence map: F-09 → F-36 → F-40; F-02 → F-03/F-04/F-05/F-07/F-12; F-10 → F-31/F-42; F-13 → F-27 → F-21; everything else stands alone. (This is deliberate: the reliability tier is dependency-light, so it can ship while the route engine is designed.)

---

## 6. Top 10 differentiators

1. **Diagnostics Center + Mock-Slot Self-Heal (F-36/F-09).** Nobody diagnoses the dev-options-wipes-slot failure; every competitor gets 1-star "stopped working" reviews for it. Medium effort, unique, immediately recognizable. ← flagship.
2. **Principled realism engine (F-04/F-06/F-39 vs competitors' scatter/teleport).** Consistent speed/bearing/accuracy with accel limits and fly-in smoothing — "survives scrutiny" is the product thesis already in DESIGN §2.
3. **Shizuku-assisted setup (F-08).** Sub-minute setup without root, unique among FOSS competitors; Fake GPS-class apps all make users hunt the mock-app picker.
4. **Offline-first maps + offline route generation (F-15/F-41).** Competitors require connectivity for maps and routing; GhostRoute works VPN'd/airgapped (relevant for the Persian audience where Google services are constrained).
5. **True Persian support (F-29 + fa from day one).** No competitor ships real RTL; also Photon `lang=fa` place names. High emotional value for the core audience.
6. **Editable route builder with transport controls (F-02/F-03).** Competitors: fixed GPX import or rigid waypoint lists; GhostRoute: tap-edit-scrub-reverse.
7. **Boot/crash recovery (F-24).** "It survived a reboot" is a review-winning sentence; competitors universally fail here.
8. **Privacy-clean diagnostics export (F-40).** Support without analytics — brand-consistent and unique.
9. **Honest platform framing.** Clearly state what mock-API can/can't do (`isMock()` detectability, dev-mode requirement) in-app; trust converts skeptical users better than fake "undetectable" claims competitors make.
10. **Root companion as a separate, optional artifact (F-44).** Per-app hooking + `isMock()` stripping is the only real anti-detection path (verified); keeping it separate protects the main app's simplicity and store-friendliness while capturing power users.

Feasibility honest split: 1–8 are genuinely feasible as specified; 9 is messaging; 10 is feasible but high-maintenance (per-Android-version hook updates) — that's why it's Phase 4/separate.

---

## 7. Features to avoid or postpone

- **Cloud sync / accounts / any backend.** Violates privacy-first anti-goals; zero competitor advantage. Permanent exclusion.
- **Analytics, crash reporters (Firebase/Crashlytics), ads.** Permanent exclusion; F-40 covers support instead.
- **Automatic battery-optimization exemption requests.** Play-policy-restricted (verified); instead educate (F-38). Postpone the *direct* request intent indefinitely.
- **Stripping `isMock()` in the non-root app.** Platform-impossible without hooks; promising it would be dishonest (F-44 is the honest home for it).
- **Virtual-app container spoofing (DESIGN approach C).** Verified fragile with GMS apps, heavy maintenance, per-app only; keep research-only.
- **Built-in navigation/turn-by-turn.** Enormous scope, competes with OsmAnd; route *playback* is not navigation.
- **Live traffic-aware realism (beyond F-07).** Requires online data feeds → breaks offline-first; F-07's static histograms are the right ceiling.
- **iOS/desktop versions.** Out of scope; dilute focus.
- **In-app route marketplace/online sharing with accounts.** File-based sharing (F-31/F-42) gives the benefit without the infrastructure.
- **NMEA/AGPS-level spoofing in-app.** Requires root hooks; belongs to the companion only.
- **"Stealth" icon/disguise options (DESIGN Tier-3 item 20).** Re-evaluate: adds maintenance + ToS risk, marginal value; recommend cutting from DESIGN Tier 3 in favor of F-15/F-33.

---

## 8. About / Credits page specification

**Location:** Settings → About GhostRoute (full-screen Compose page, Material 3, consistent with app theme).

**Structure (top → bottom):**
1. **App block:** app icon, "GhostRoute", version + build info (`versionName (versionCode)`, plus short commit hash in debug builds), one-line tagline ("Privacy-first mock location for Android").
2. **Developer block:** "Developed by **Amirreza**" — prominent, styled as a section header, not buried in a list.
3. **Links block** — three rows, each an `IconButton` + label, 48dp touch targets, chevron:
   - **GitHub** — https://github.com/amrsh88 — GitHub mark (a packaged vector drawable, not a network fetch — no tracking, offline-safe).
   - **Telegram Channel** — https://t.me/net_republic — Telegram glyph.
   - **Telegram Community** — https://t.me/netrepublic — Telegram glyph.
   - Launch via `Intent.ACTION_VIEW` with `try-resolve`: Telegram URI first (`tg://resolve?domain=…`), fall back to https URL → resolves into installed Telegram or any browser; if nothing resolves, show a non-fatal toast/copy-to-clipboard.
4. **Open source section:** third-party libraries with licenses (MapLibre Native — BSD-2, Room/DataStore/Compose/Coroutines — Apache-2.0, OkHttp/Retrofit — Apache-2.0, Shizuku API if shipped — Apache-2.0, OpenStreetMap data — ODbL attribution notice, tile provider attribution per provider). Generated list from the Gradle license report, not hand-maintained.
5. **Credits note:** explicit separation — "Developed by Amirreza" section vs. "Third-party & data" section; contributors thanked by name if any appear.
6. Footer: short copyright line.

**Implementation requirements:**
- All 3 URLs live in one constants file (e.g. `Links.kt` or a build-config field) — trivially updatable, no string-resource scattering.
- No network calls on this page — icons packaged locally; no profile fetches (requirement met).
- Link verification before release: manually check all three (GitHub profile resolves, both t.me links open) — record in release checklist.
- Fully localized en/fa; RTL-correct layout automatically via Compose.
- Version info from `BuildConfig`, never hardcoded.

**Additional branding suggestions:**
- Distinct app icon (compass/route-line motif, works as monochrome themed icon for Android 13+).
- Consistent notification branding (small icon + "GhostRoute" title).
- In-app "route accent color" used for the active-route overlay and Go button — one brand color carried through.
- Changelog screen fed from a local resource (no network).

---

## 9. Recommended changes to the existing implementation plan

1. **Task 4 (MockService):** add the mock-slot-integrity check + `ACTION_MOCK_PERMISSION_LOST` distinction between "app deselected" vs "slot cleared" — feeds F-09. Small change, do it while the code is fresh.
2. **Task 2/3 (engine):** add a `seed` field to `SimulatorConfig` now (F-45 dependency) — trivial today, breaking later.
3. **Task 5 (DataStore):** add `lastActiveSimConfig` persistence hook (F-24 dependency) — one field, avoids migration.
4. **Task 7 (search):** implement `GeocodeClient` against Photon first, Nominatim second (policy-verified above); MVP plan currently says Nominatim primary.
5. **Task 10 (tile/widget):** nothing changes; F-46 deep links extend Task 6's intent filters.
6. **DESIGN.md Tier-3 item 20 (icon disguise):** recommend removing (§7 above).
7. **DESIGN.md §7:** add the verified Shizuku grant path as a footnote under the dev-mode question — it doesn't change the decision (dev mode still needed for Shizuku bootstrap) but informs the wizard design.
8. No architecture changes needed: every proposal above fits the three-module split; only F-44 lives outside it (separate artifact, as already decided).

---

## 10. Final shortlist — 15 best features to consider first

1. **F-09 Mock-Slot Self-Heal** — unique, cheap, fixes the market's top complaint.
2. **F-36 Diagnostics Center** — flagship differentiator; pairs with F-09.
3. **F-01 Virtual Joystick** — the most-requested competitor feature, done cleanly.
4. **F-10+F-35 GPX/KML/CSV with safe import** — unlocks routes, sharing, library.
5. **F-06 Teleport Smoothing** — small engine addition, big realism win.
6. **F-13 Movement Profiles** — cheap; foundation for profiles.
7. **F-46 Geo-link & deep-link sharing** — cheap, high convenience.
8. **F-23 Notification Actions + Trip Stats** — finishes the FGS story.
9. **F-39 Health Monitor** — engine self-checks; reliability brand.
10. **F-24 Boot & Crash Recovery** — competitor-critical gap.
11. **F-02 Route Builder with road-snap** — the flagship Tier-2 feature.
12. **F-03 Playback Transport** — makes routes truly usable.
13. **F-08 Shizuku-Assisted Setup** — sub-minute onboarding.
14. **F-27 Simulation Profiles** — ties everything into one-tap scenarios.
15. **F-14 Photon-first Search** — policy-correct, better UX than planned.

*(F-17, F-16, F-28/30 are also-first cheap polish folded into MVP tasks per §9.)*

---

**Next step:** review this proposal; on approval, the chosen Tier-1 features get folded into `DESIGN.md` §4 and the MVP plan as follow-on tasks — no implementation before that.
