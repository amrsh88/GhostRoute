# GhostRoute — Automatable Intent Surface (P4c)

Every action here lives on the exported `MainActivity` intake (`singleTask`;
new deliveries arrive via `onNewIntent`). The baseline service API
(`app.ghostroute.START/STOP/UPDATE/SET_ROUTE/PING` + extras) is **unchanged**
and remains documented by its own extras (`MockService`); this file is the
*app-level* automation surface. Unknown actions are ignored (the F-46 rule).

## Actions

### `app.ghostroute.intent.OPEN_SPOT`
Open a saved spot: centers the map, stages the pin, records the jump into
recents (the same seam as `geo:` intake / the Spots sheet jump).

| Extra | Type | Meaning |
|---|---|---|
| `spot_id` | long, int, or decimal string | Saved-spot primary key (accepted: `Long`, `Int`, numeric `String`). |
| `spot_name` | string | Exact case-insensitive name over ALL saved spots (folders flattened). |

* If both extras are present **`spot_id` wins** (E7).
* Validation: `spot_id` must be >= 1; `spot_name` must be non-blank after trim.
* Errors (toast, localized; **no state change**): malformed/missing extras →
  "Malformed automation request"; unknown id/name → "Spot not found".

```bash
adb shell am start -n app.ghostroute/.MainActivity \
  -a app.ghostroute.intent.OPEN_SPOT --es spot_name "Airport"
# or: --el spot_id 3
```

### `app.ghostroute.intent.OPEN_ROUTE`
Load a saved route into the Route Studio and bring the sheet up.

| Extra | Type | Meaning |
|---|---|---|
| `route_id` | long, int, or decimal string | Saved-route primary key, must be >= 1. |

* The load goes through the studio's existing **dirty guard**: unsaved edits
  park behind the discard confirm instead of being silently replaced.
* The engine is **never started or re-targeted** (E8) — automation prepares;
  the user presses Activate explicitly.
* Errors (same shape as OPEN_SPOT): malformed → "Malformed automation
  request"; unknown id → "Route not found".

```bash
adb shell am start -n app.ghostroute/.MainActivity \
  -a app.ghostroute.intent.OPEN_ROUTE --el route_id 2
```

### `app.ghostroute.intent.STOP` (launcher shortcut "Stop")
Stop the simulation. Routed to the **real** service path
(`MapViewModel.onStop` → `MockService` `app.ghostroute.STOP`), so polling,
teardown and the joystick pad are torn down exactly as an in-app stop does
(E6: a static launcher shortcut cannot bind a service; no second authority).
No extras.

### `app.ghostroute.intent.OPEN_LAST` (launcher shortcut "Last location")
Center the map camera on the last simulated spot (`LastPositionRepository`).
Camera-only: no pin move, no recents write (E9). With nothing stored the app
opens and shows "No stored last location".

## Launcher shortcuts (long-press)

`res/xml/shortcuts.xml` registers two static shortcuts: **Stop** and
**Last location** — the two actions above, no extras. Labels are localized
(en/fa).

## Tasker notes (Tasker integration itself is a later phase)

* Target ghost of choice: **App → Send Intent**, then:
  * Action: `app.ghostroute.intent.OPEN_SPOT` (etc.),
    *Target*: `Activity`, *Package*: `app.ghostroute`,
    *Class*: `app.ghostroute.MainActivity`.
* The activity is `singleTask`: a Tasker fire while the app is foreground is
  delivered through `onNewIntent` — identical code path.
* All payload extras are optional-but-validated; send `--el spot_id` or
  `--es spot_name` as above. Any failure surfaces a localized toast; the
  automation can therefore poll the foreground/visibility for success, or
  simply rely on "no toast" semantics.
* Do NOT send raw `app.ghostroute.*` service actions from automation on
  Android 12+: background service starts from Tasker are restricted; the
  activity surface above is the supported path.

## Guarantees

* Never crashes on malformed input; unknown targets never change state.
* No telemetry, no network, no permissions beyond the existing set.
* Destructive or sensitive operations (delete, backup, profile activation,
  engine START/SET_ROUTE) are deliberately **not** exposed: automation is
  explicit-only.
* Error strings live in resources (en + fa, parity-tested).
