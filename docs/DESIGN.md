# GhostRoute — Design Document

**Date:** 2026-10-26 · **Status:** DRAFT — awaiting Amirreza's approval
**Baseline studied:** Fake GPS Pro 5.8.7 (`com.blogspot.newapphorizons.fakegps`), REA-decompiled, evidence in session transcript.

---

## 1. Intent & why

A modern, clean, trustworthy fake-GPS app for Android that beats Fake GPS Pro on every axis: better engine (real routes instead of scatter-jitter), better UI (Material 3, map-first), better hygiene (no ads, no analytics, no crack infrastructure), full Persian + English.

Why now: the baseline is an abandoned-architecture app (legacy + v2 service split), shipped with Google analytics/ads baggage, and its movement model is a random teleport — trivially detected and ugly to use. Its mechanism is proven; its implementation is not.

## 2. Goals / Non-goals / Anti-goals

**Goals**
- System-wide location spoofing via the mock-location provider API (the only sanctioned channel), wrapped in a setup flow so trivial a non-technical user completes it in under a minute.
- Movement that survives scrutiny: road-following routes, speed-controlled playback, consistent speed/bearing/accuracy — not scatter.
- Automation-first: intents, quick tile, widget, schedules — same public intent API as the original (START/STOP/UPDATE) for Tasker compatibility, richer extras.
- Zero telemetry. Offline-first. Full fa+en i18n from day one.

**Non-goals (v1)**
- Not a VPN/IP-geolocation changer.
- Not a system-wide spoofer *without* dev mode or root (platform-impossible; see §7).
- No cloud accounts, no sync servers.

**Anti-goals (what would make this a failure even if it works)**
- A clean UI bolted onto a flaky engine — the engine is the product.
- Notification spam, full-screen ads, "upgrade" nags.
- Shipping any Google Analytics/Firebase/Crashlytics/AdServices component.

## 3. Approaches considered

| # | Approach | Verdict |
|---|----------|---------|
| A | **Mock-location app** (framework `addTestProvider`/`setTestProviderLocation` + FLP `setMockMode`) — what the baseline does | ✅ **Core.** Sanctioned channel, no root, reversible, works system-wide. Requires Developer-Options selection (platform gate). |
| B | **Root companion** (Zygisk/Xposed module; `appops mock_location allow`; strip `isMock()` flag) | ✅ **Optional tier, Phase 3.** Kills the dev-mode requirement and most detection; needs root, breaks Play Integrity. |
| C | **Virtual-app container** (fake location only for apps run inside our virtual space) | ⚠️ **Research-only.** No root/dev-mode needed but fragile with GMS apps, heavy maintenance, per-app only. |
| D | **VPN IP-geo** | ❌ Rejected: doesn't touch device GPS; not a real answer. |

**Decision:** A now, B later, C research. The setup wizard exists precisely because A's only cost is the one-time Developer-Options visit.

## 4. Product plan — features

### Tier 1 — MVP (parity-plus)
1. **Map-first main screen** — MapLibre + OSM raster/vector tiles (no Google Maps API key, no billing), tap/drag pin, live marker, one-tap Go/Stop, status chips (mock permission ✓/✗, providers, engine state).
2. **Mock engine** — mocks `gps`, `network`, `fused` (FLP), registers `passive`; configurable update interval, accuracy, altitude, bearing; fresh `time`/`elapsedRealtimeNanos` on every injection (baseline already does this right — keep it).
3. **Setup wizard** — deep-links to exactly the right Developer-Options page, verifies each step (dev mode on → mock app selected → FGS/notification permission → overlay permission where needed), re-checks on resume.
4. **Foreground service** — Android 14+ `specialUse` FGS with Stop action; auto-stop + user-facing diagnosis on `SecurityException` (mock app switched mid-run), auto-recovery attempt.
5. **Spots & search** — favorites, recents, folders; search via OSM Nominatim/Photon (no API keys); coordinate input in DD and DMS; share a location *into* the app; share fake position *out* as geo: / map links.
6. **Quick tile + home-screen widget** — on/off + jump-to-favorite.
7. **Intent API** — `START`/`STOP`/`UPDATE` (baseline-compatible extras) + `SET_ROUTE`, `PING` with rich extras.

### Tier 2 — the "tons of features"
8. **Joystick mode** — floating, draggable bubble joystick; moves the pin while spoofing runs; overlay-permission flow.
9. **Route engine** — draw multi-point routes; snap to real roads via OSRM (public demo, no key); speed control (km/h), loop / ping-pong / single-shot; waypoint timeline with ETA; live route preview.
10. **Route recording & replay** — record real walks/drives (FusedLocation while not spoofing), save, replay later with original or scaled speed.
11. **GPX/KML/CSV import & export** — spots, routes, recorded tracks.
12. **Realism engine v2** — noise model (accuracy jitter consistent with movement), bearing from path tangent, speed from distance/Δt, optional teleport smoothing (gradual fly-in at configurable max speed), altitude interpolation.
13. **Schedules & geofences** — start/stop at times; trigger profiles on entering/leaving real places.
14. **Profiles** — named presets (e.g., "Vacation", per-game) bundling spot/route/movement style/realism settings; one-tap activate.
15. **Persian + English** — full fa RTL from day one; units (metric/imperial) setting.
16. **Trip stats** — distance, duration, avg speed of the current simulated trip, shown on the notification.

### Tier 3 — power tier
17. **Root/Xposed companion module** — no dev-mode path, `isMock()` flag stripping, per-app location hooking. Separate artifact; degrades gracefully when no root.
18. **Offline packs** — offline map regions + offline routing (Valhalla) for airgapped spoofing.
19. **Backup/restore** — single-file export of everything (Room → JSON).

> **2026-10-26 amendment (brainstorm proposal approval):** item 20 (icon/notification disguise) is **dropped** — maintenance + ToS risk outweigh marginal value (see proposal §7). Tier assignments for all 48 brainstorm features (F-01…F-48): see `docs/proposals/2026-10-26-feature-brainstorm.md` §4–5. Key updates adopted from the proposal: search is **Photon-first** (Nominatim fallback, policy: 1 req/s + identifying UA) instead of Nominatim-first; Shizuku-assisted mock-location grant + **mock-slot self-heal** (dev-options toggle clears `secure mock_location`/appop) are planned reliability features; `SimulatorConfig` gains a `seed` field; last-active-sim persistence added for boot/crash recovery; route transport controls (pause/resume/reverse/seek) extend the route engine.

## 5. UX design (the "really clean UI")

- **Map is the app.** No tabs. One screen: full-bleed map, floating pin, bottom sheet (drag for details), top search pill, FAB = Go/Stop morphing button with state colors.
- **Material 3 + dynamic color**, dark theme default-capable, expressive but restrained: 8pt grid, 3 elevation levels, state-layered buttons.
- **Status chips row** under the search pill: permission state, engine state, current provider — everything the user wonders about, glanceable.
- **Bottom sheet** = current spot card: coords (tap to copy, DD/DMS toggle), arrival time, quick actions (favorite, share, route, joystick).
- **Wizard**: full-screen, 4 steps, progress dots, live checkmarks, "Open settings" deep links, re-verify on resume. Nothing hidden.
- **Empty states** with illustrations; haptics on pin drop, Go, Stop.
- **One-thumb reachability**: all primary controls in the bottom 60% of the screen.

## 6. Architecture

### Modules
```
:app                    — Compose UI, DI wiring, manifest, service
:engine                 — pure Kotlin: geo math, movement strategies, realism, models (JVM tests)
:core-data              — Room DB, DataStore, repositories, geocoding/routing clients
```
(`:engine` has zero Android dependencies → fast unit tests, portable.)

### Engine design (`:engine`)
```
SimulatorConfig        (lat, lng, intervalMs, accuracyM, altitudeM, bearing, speed, movement: MovementSpec, realism: RealismSpec)
MovementSpec           = sealed: Static | Jitter(radiusM) | Route(path, speedMps, mode: Loop|PingPong|Once) | Joystick | Track(track, speedScale)
MovementStrategy       interface: fun tick(t: Instant): Waypoint   ← pluggable per spec above
SignalRealizer         Waypoint + realism → android-independent LocationSample(lat,lng,alt,bearing,speed,accuracy,t)
RealismPolicy          noise tables, smoothing policy
LocationInjector       interface: fun inject(sample: LocationSample, providers: Set<ProviderId>)   ← implemented in :app over LocationManager + FLP
EngineState            StateFlow: Idle | Preparing | Running(spot, strategy, stats)
```
Flow: Service owns `EngineState`; UI observes. Ticker = single coroutine, interval-adaptive to speed (never slower than distance-traversal demands). UPDATE intents mutate the active `MovementSpec` live (baseline's good idea, generalized).

### Service & intents
`MockService` (FGS specialUse): actions `START`, `STOP`, `UPDATE`, `SET_ROUTE`, `PING`; parcelable `SimulatorConfig` extra `extra-simulator-config` (baseline-compatible name), plus string lat/lng extras. Stop on `SecurityException` → broadcast `ACTION_MOCK_PERMISSION_LOST` so the UI can re-wizard.

### Data model (Room, `:core-data`)
`Spot(id, name, lat, lng, folderId, note, createdAt)` · `Folder(id, name, sort)` · `Route(id, name, pointsBlob, mode, speedMps)` · `Track(id, source, pointsBlob, recordedAt)` · `Profile(id, name, configJson)` · `Recents(id, lat, lng, usedAt)`

### Tech stack
Kotlin 2.2 · Jetpack Compose + Material 3 · Coroutines/Flow · Room + DataStore · MapLibre Native (OSM) · OkHttp/Retrofit (geocode/route) · minSdk 26, target/compileSdk 36 · JDK 21 toolchain · no analytics of any kind.

## 7. The developer-mode question (decided)

Framework-enforced: `addTestProvider`/`setTestProviderLocation`/`setMockMode` require the mock-location appop, grantable only via Developer Options (or root). Decompiled baseline handlers confirm. Therefore:
- **No dev mode, no root:** impossible system-wide; the only real product is the container approach (C) — research-only.
- **Root, no dev mode:** Phase 3 companion module (B).
- **Shizuku (verified):** `cmd appops set <pkg> android:mock_location allow` works from shell UID 2000 — GhostRoute can self-grant the mock appop without root when Shizuku is running (wizard shortcut + mock-slot repair). Dev options must still be on to bootstrap Shizuku.
- Product framing: the wizard makes the dev-mode path a 60-second one-time setup, so it stays the core.

## 8. Constraints
- No Google Maps / GMS dependency in the app itself (FLP optional at runtime via Play services availability check).
- All user-facing strings in `strings.xml` (en + fa) — no hardcoded copy.
- Engine logic unit-testable on JVM; no `android.*` imports in `:engine`.
- Repo: `/root/GhostRoute`, git from commit zero.

## 9. What's left to the builder (my calls, flagged)
- Exact library versions (pinned in `libs.versions.toml` during implementation).
- Tile-vector vs raster style choice after visual check.
- OSRM public demo server for MVP routing (rate-limited); offline Valhalla is Tier 3.
