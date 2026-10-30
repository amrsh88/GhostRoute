# GhostRoute v0.2.0 — Final Release Summary (Part 7)

Tag: `v0.2.0` @ commit `54f022c` (branch `mvp`). Full spec coverage: brief Parts 1–7.

## What v0.2 delivers (on top of v0.1.0-mvp)

**Phase 1 — Map + simulation core**
- MapLibre + OpenFreeMap (keyless, no API key, no telemetry): dark default, light, and — new in v0.2 — the third style **liberty** (URL verified live before shipping; a 404 candidate was dropped, never shipped broken).
- Simulation engine: spot / route (Once, Loop, PingPong) movement, interval/accuracy control, honest speed reporting, mid-run route swap. Engine strictly separated (`:engine` — no Android deps).

**Phase 2 — Features (all independently reviewed, all reconciled)**
- **IP/VPN sync** (opt-in only): polls your public-IP location (ipwho.is, keyless), manual pin always wins and disables sync (persisted, by-pin marker on the chip, race-window latched), failure keeps last fix + shows the real stale reason, parse failures fail closed.
- **History**: sessions with folders, rename/delete, timeline UI distinct "empty" from "pending", recorder stays lossy-and-thresholded by design.
- **Route Studio**: draw/edit routes, distance/segment stats, GPX import+export.
- **Profiles**: saved spot/route profiles, one-tap activation (activation only *prepares*; explicit start), corrupt profiles blocked with an honest per-reason message (never claims "manual pin" for damaged data).
- **Search**: map/POI search; **Dashboard**: read-only live run stats (now including route progress + remaining distance from the engine's own math); **Backup**: all-or-nothing export/import with strict record gating and counted skips; **About/Diagnostics/Settings**: privacy disclosure, diagnostics without any network beyond what you trigger.

**Phase 3 — UI polish (reviewed 9/9)**
- 5-step radius/spacing token system, GhostPill motif, amber = warning-only / rose = danger-only color contract.
- Motion: system reduced-motion honored everywhere (one gate), run-marker glides only between real engine samples (≤400 ms, never extrapolates, snaps on reduced motion and on teleport-sized jumps so it never draws a route the engine did not run), sheet transitions degrade to none.
- Accessibility: pill/button contentDescriptions, polite live region, empty/error/loading states on every surface.

**Phase 4 — Extras (reviewed PASS; 3 minors fixed)**
- **Floating joystick** (opt-in, default off): overlay pad while running — directional nudge (25/100/500 m cycle), draggable + minimizable, Stop, route runs take it down, 3 fail-closed gates (preference, overlay permission — including revoke-while-shown teardown —, run-in-progress). Nudges ride the existing validated UPDATE path and disable IP-sync exactly like a pin drop. No fake state ever shown.
- **Automation**: `OPEN_SPOT` / `OPEN_ROUTE` app intents (validated; unknown id/name → localized message, zero state change; opening a route never starts the engine), launcher shortcuts (Stop, Last location), `docs/intents.md` with adb + Tasker examples.
- **Extras**: route progress/remaining in the dashboard, recents quick-access chips on the map, run-value audit with reasoned rulings.

## Verified test results (actual runs, fresh `--rerun-tasks`)
- Two consecutive full green gates at the release commit: **1087 tests / 0 failures** (engine 65 + core-data 227×2 + app 568×2 JVM test variants), `lintDebug` **0 errors** both times.
- Strings parity en/fa **286 = 286** (machine-checked in-gate + matched pairs).
- Invariants held at review: `:engine`/`:core-data` untouched since P2 close; manifest delta limited to `SYSTEM_ALERT_WINDOW` + shortcuts metadata; no analytics/ads/network additions; intents documented, validated, and destructive actions stay explicit.

## Deferred items (with reasons — nothing silently dropped)
- **Favorites quick-access** (recents implemented instead): no favorites column exists; adding one means touching `:core-data` schema under do-not-touch. Quick recents covers the access need.
- **Waypoint pauses / resume-from-position / route seek**: needs a new engine seek concept (`:engine`); honesty rule forbids faking a resume.
- **Playback realism tuning** (movement interpolation quality): engine interpolation is honest linear arc-length with constant speed; richer easing deferred with the seek concept.
- **Tasker plugin UI**: stable documented intents shipped; dedicated Tasker integration later.
- **First-run guidance**: the Setup wizard + contextual joystick permission explainer already cover onboarding.

## Known limitations (stated honestly)
- Robolectric JVM testing only — no physical-device claims; overlay rendering verified by code-walk + compile (stated in test files).
- Implicit broadcasts to runtime receivers are unreliable under Robolectric — receiver logic is tested directly; the send side end-to-end.
- After a mid-run route swap, dashboard "remaining" counts pre-swap elapsed time against the new route (transparent, disclosed in-app docs + code).
- **Security note**: the release keystore and `keystore.properties` are committed to the repo by choice (reproducible local builds). **Rotate + untrack before any public distribution.**
- Session GPX/summary export stays on faithful route data; session traces are intentionally lossy thresholded polylines.

## APKs
- Release: `app/build/outputs/apk/release/app-release.apk` (signed)
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Both verified with `apksigner verify` before delivery.
