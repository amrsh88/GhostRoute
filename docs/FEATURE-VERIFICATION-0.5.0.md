# Feature check — 0.5.0

This lists what was verified for the 0.5.0 changes, how, and what was **not** verified. "Unit tests pass" and "builds" are not device certification: no emulator or device was available (no `/dev/kvm`), so nothing below was run on hardware.

## Evidence for this release

- 1,535 unit-test executions, 0 failures, errors or skipped (app debug and release 424 each, core-data 309 each, engine 69). Lint: 0 errors, 64 warnings. Debug APK assembled; manifest and signature checked with `aapt2` and `apksigner`.
- The road-router tests parse **real responses** captured from routing.openstreetmap.de on 2026-10-29 (`core-data/src/test/resources/osrm`), not hand-written JSON.
- Compose renders of the new screens (docs/design-previews) use fixture data. They are not a MapLibre or device test.

## New and changed features

| Feature | What is verified | What is not |
|---|---|---|
| Liberty default map style | `mapStyleUrl("default")` and unknown keys resolve to the Liberty URL; explicit dark/light are kept; the Settings chip selection follows. The Liberty style URL returned HTTP 200 from the sandbox on 2026-10-29. | The style rendering in the native MapLibre view. |
| Follow roads | Router URL, snap limit (500 m per waypoint), waypoint cap (100), simplification to the engine limit, every error mapped, no straight-line fallback, preview reuse and invalidation, profile starts — all unit-tested. A live request to the public server returned real road geometry for car, bike and foot profiles. | The road line on the real map; behavior of the public server under load; rural/ferry/one-way edge cases; routes longer than a few hundred km (they are simplified, the screen shows the tolerance). |
| Live with IP | The decision logic (settle time, connection eviction, retries, VPN-drop grace, cancellation) is unit-tested in virtual time; the hold-on-VPN-loss rule is unit-tested with an injected tunnel status; the network-change source is tested against Robolectric's connectivity shadow; turning it on goes to the IP location and starts or moves a run (tested with a fake lookup). | A real VPN switching or reconnecting; OEM background limits; split tunneling; VPNs that change server without a network change (only the periodic check catches those). It cannot start a simulation from the background on Android 12+. |
| Simpler profiles | One-step save (place, selected pin vs. last run, route, settings snapshot), point counts and the render of the new cards. Existing profile activation tests still pass. | Usability with real data. |
| Pause / resume (0.4.0) | Unchanged from 0.4.0 notes. | Device behavior. |

## Settings that nothing reads

Checked by searching for every `AppSettings` field outside storage and backup code:

- `showNotification` and `units` are stored and included in backups, but **no code reads them and no screen exposes them**. They are invisible dead state, not controls that lie. They were left in place because removing them changes the backup format.
- Every other setting has at least one consumer.

## Other findings in this pass

- The VPN status line could never read the network state: `ConnectivityManager.getAllNetworks()` needs `ACCESS_NETWORK_STATE`, which the manifest lacked, and the failure was swallowed as "unknown". The permission (a normal one, no prompt) is declared now. Not verified on a device.
- The only empty click handlers in the UI code are the intentional "swallow taps inside the sheet" ones; every row in the More tools sheet is wired to a real action.

## Independent code traces

Three read-only audits of a snapshot of the source were run (map screen and settings; core-data and IP sync; Route Studio, profiles and the engine path). All three have reported. Each claim was re-checked in the code before acting, and a claim was only acted on when the code confirmed it. "Traced in code" means the whole chain from control to effect was followed; none of it was run on a device.

**Fixed because the code confirmed it**

| Finding | Release |
|---|---|
| Route Studio Start / Run route / Restart from idle started a stationary mock at waypoint 1: the service ignored the route extras of START. Present in the original upload; also broke Follow roads from idle. | 0.5.2 |
| A rejected START (mock slot not granted, malformed extras) could crash the app: START arrives via `startForegroundService()` and the service stopped without calling `startForeground()`. | 0.5.2 |
| IP changes re-anchored a route run (restarting the route); lookups outside the VPN and IPv6-only answers could move the run; a change that arrived while paused was dropped. | 0.5.2 |
| Launch intent replayed on rotation; widget refreshed every tick; tile had no label or icon; intent table wrong (route modes, `extra-speed`, UPDATE on routes, PING reach). | 0.5.2 |
| Search history could not be cleared; restore overwrote the first-run flag; IP exit coordinates never displayed; dead recents chips; misleading hints. | 0.5.1 |

**Confirmed and deliberately not changed**

| Finding | Why |
|---|---|
| `showNotification` and `units` are stored and backed up but unread, with no UI. | Removing them changes the backup format. |
| Jitter movement, altitude and the config bearing exist in the engine and parcel but nothing in the app can set them. | No UI was asked for; the README no longer advertises a jitter mode. |
| Accuracy is clamped to 5–50 m by the realism policy. | Documented here; no UI or intent promises more. |
| Saved routes store geometry only (no speed or mode), every save is a new row, rename has no UI. | Behavior, not a broken control. |
| The profile "manual pin blocked" reason is never returned. | Unreachable code, harmless. |
| Fused provider is not mocked directly (DESIGN.md says it is). | Apps normally receive the mock through the GPS and network providers; not verified on a device. |
| Starting a profile saves its interval and accuracy as the global defaults; first launch stages a default pin; dashboard elapsed time includes paused time; the online chip can stay offline after a failed style load. | Documented behavior or needs a device. |
| GPX import accepts only `trkpt` and does not check the namespace; re-importing your own spot CSV fails whole with a generic "name already in use". | Low impact. |
| The exported command service has no permission. | A decision for the owner; restricting it breaks documented automation. |

**Reported by the audits and not re-checked in the code** (treat as leads for an on-device test): the joystick pad's repeated nudges do not accumulate within one tick and report speed 0; the shortcut "Last location" opens the last pin, not the last simulated spot; the widget and tile show active while paused.

**Open question that needs a device:** the wake-lock permission is declared but never acquired, and the ticker is a main-looper delay. Whether ticks stall with the screen off cannot be judged here. Holding a wake lock would steady the ticks and cost battery, so it is left for the owner to decide after a test.

## 0.5.2 addendum

1,563 unit-test executions, 0 failures, errors or skipped; lint 0 errors, 64 warnings; debug APK verified with `apksigner`. The new route and foreground tests were checked to fail against the old behaviour (the old service code was put back temporarily and eight tests failed). Still no device or emulator run.

## 0.5.3 addendum

New: Live with IP holds the last location when a VPN-anchored session loses its VPN; the online chip follows connectivity; a refused start shows a banner. Each has unit tests. The registration of the refusal listener is covered by a test that was checked to fail against the broken ordering. Not verified on a device: the VPN transitions themselves (the tunnel status is injected in the tests), the banner on a real refusal, and the chip behavior while a real VPN switches. `docs/DEVICE-TEST-CHECKLIST.md` lists those checks.

1,587 unit-test executions, 0 failures, errors or skipped; lint 0 errors, 65 warnings; debug APK verified with `apksigner`.

## Still true from earlier releases

Debug-signed APK (the original release key was in the uploaded archive and must be treated as exposed); no anti-detection; no route seek; the auto-stop timer does not survive process death; backup restore is best-effort across two stores; the exported command service keeps its documented intent API.
