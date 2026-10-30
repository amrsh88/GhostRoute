# GhostRoute — Part-6 value audit + P4d E-rulings

Read-only audit of :engine reality (`engine/`, `MockService`), then item-by-item
rulings on the remaining v0.2 backlog. :engine/:core-data are do-not-touch in
P4 — rulings below either implement on the :app side, or defer with a reason.
Numbering continues P4a/P4b's E1–E5 and P4c's E6–E9.

## Movement audit (read-only; no :engine change)

* **Interpolation** — honest. `RouteStrategy` precomputes segment
  haversines/cumulative lengths and derives position purely from
  `(t − start)`; per-segment linear `Geo.interpolate` at constant `speedMps`.
  Deterministic, testable, no teleport seams.
* **Waypoint pauses** — NOT supported. `MovementSpec.Route` carries no
  per-point dwell; nothing between waypoints but traversal time.
* **Speed predictability** — movement speed is exactly constant `speedMps`
  (ticker quantized per movement). The *reported* speed
  (`LocationSample.speedMps`) is derived Δpos/Δt with a floor
  (`RealismPolicy.speedFor`), so it is stepwise but predictable; v1 noise is
  accuracy-only.
* **Interrupted playback** — pause = engine STOP; resume/restart = re-START
  seeded with the current route, and a mid-run SET_ROUTE builds a NEW strategy
  with `start = clock()` (`MockService.swapMovement`) — so resumed playback
  **restarts from path[0]**; there is no seek/resume-from-position.

### P4-E10 — dashboard route remaining (implemented)
Route progress/remaining in the session dashboard come from real engine state
via the engine's OWN formula (`speedMps × elapsed`), not the VM's spot
accumulator. Rationale: the accumulator honestly measures meters covered, but
it keeps accumulating across a mid-run SET_ROUTE swap while the engine
restarts the route from path[0] — using it for progress would eventually
contradict the engine. Remaining is derivable only for Once-mode routes
(Loop/PingPong never end → dash); degenerate routes (empty/zero-length) dash.

### P4-E11 — swap restarts route physics, not the run (documented, no change)
`EngineState.Running.startedAt` intentionally survives swapMovement (same run),
while the route replay restarts. P4-E10's formula choice (elapsed × speed)
means the dashboard NEVER privately accumulates — it tracks the engine. CORRECTION
(P4 review M3): it is NOT exact across a mid-run swap — the engine restarts
from path[0] at the swap instant while `startedAt` holds, so remaining counts
pre-swap elapsed time against the new route and reaches 0 early. The swap
instant is not carried in EngineState, so this is transparent via this
disclosure, not exact by construction (better than the accumulator, which is
what the brief demanded). A resume-from-position would need an engine seek
concept (:engine change) — deferred; rule it honestly rather than fake a resume.

### P4-E12 — quick access = recents (implemented); favorites ruled out
The map screen gains a quick-access chip row over the SAME data the Spots
sheet already surfaces (`SpotsRepository.recents()`), capped at the newest 5,
tapping through `SpotsViewModel.onRecentTapped` → `MapViewModel.onIntake`
(the exact sheet-jump seam, use recorded). **Favorites do not exist** —
no such column in the DB and :core-data is do-not-touch; inventing a favorites
field is schema churn for marginal MVP value. Deferred to a future DB revision
that ships it together with a real need. Chips carry a localized
contentDescription (a11y parity en/fa).

### P4-E13 — movement realism additions deferred (no change)
Waypoint pauses / less deterministic pacing / mid-route resume all require
:engine seal + MockService parser + studio format changes (do-not-touch) for
low demonstrated value at MVP (the engine's constant-speed linear traversal
is already the mock-detection-honest baseline; realism-v2 is the ledgered
home for noise/drift work). Deferred.

### P4-E14 — session summaries/export (ruled: keep existing)
Sessions already have rename, delete (modeled CASCADE, confirmed in-dialog),
clear-all + retention. GPX 1.1 export exists where the data is faithful
(routes, RouteStudio SAF). Session traces are the recorder's
record-thresholded, batched, lossy polylines — exporting them as GPX would
re-broadcast lower fidelity as authoritative and imply precision the store
does not hold; per-session numeric summaries beyond the live dashboard add
little. Ruled: no new export/summary in v0.2.

### P4-E15 — first-run guidance deferred
The wizard IS the first-run guidance (4 steps, deep links, verification). The
risky hidden affordances already carry contextual explainers (joystick
pre-permission explainer with its dismiss pref, P4b). A global dismissible
first-run card would duplicate the wizard for low marginal value at ship.
Deferred to a future phase; if built, use the P4b explainer pattern
(app-local pref, dismissible, en+fa parity).

## Verified, noted (no action)

* **Privacy controls** — the About sheet discloses the exhaustive,
  code-verified network list + no-telemetry. P4c/P4d added no endpoints, no
  permissions, no telemetry (manifest diff: one meta-data line only).
* **Stop really stops** — `MockService.handleStop` cancels the ticker,
  unbinds the injector, clears strategy/realism and lands `EngineState.Idle`;
  the launcher shortcut rides this exact path (P4c-E6).
* **Migration preservation** — `DbProvider` chains
  `MIGRATION_1_2 … MIGRATION_4_5` (additive alters only; the recents-label
  default lives in the migration), exercised by `core-data` `MigrationTest`
  (JVM/Robolectric) in the green suite.
