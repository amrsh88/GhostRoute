# GhostRoute 0.3.0 — feature audit and verification

## Evidence and scope

This build is an implementation/reliability update, not a certification that every feature works on every Android device. Source paths were audited from Compose controls to repositories and service commands. JVM/Robolectric tests were run; there was no physical Android device or emulator available. In particular, a recorded Intent is not proof that an OEM allowed a foreground service or that another app consumed a mock fix.

Executed gates:
- Full `test`: 1,397 executions, 0 failures, 0 errors, 0 skipped. App: 385 debug + 385 release; core-data: 280 debug + 280 release; engine: 67. Android variants repeat many tests; these are not 1,397 unique device tests.
- Standalone `lintDebug`: passed with warnings. No baseline or suppression was added to hide an error.
- `:app:assembleDebug`: passed.
- APK signature/manifest checks are performed before packaging. Debug build uses the local debug signing key, not the exposed original release key.

Individual unit tests use an isolated Application so unrelated process-lifetime network/recorder jobs do not run during their setup. RuntimeIpSyncTest separately exercises the real runtime coordinator with injected lookup responses and real settings/history persistence. A TileService base-lifecycle shadow works around Robolectric 4.17's ShadowTileService/ShadowService cast issue; the production tile override/collector is exercised. Several older assertions were corrected to await completed DataStore/Room writes instead of racing them.

## Feature inventory

| Feature | Repair / behavior in this build | Evidence | Still needs a device |
|---|---|---|---|
| Profile creation/edit/duplicate/delete | Numeric errors are rejected instead of silently using defaults; capture uses the actual route speed/mode. Corrupt routes fail validation. | ProfilesViewModelTest, ProfileRepositoryTest | Editor layout, keyboard, large text |
| Profile Prepare | Explicit selection can replace an old pin. Stores the whole config, including route path/mode/speed, for Go. Does not start an idle session. | MapViewModelTest: prepared route survives Go; explicit profile after manual pin | Native map camera and permissions |
| Start profile | Separate clearly labeled action sends the full START payload. Busy/error/selected-preset feedback is visible; real engine state remains authoritative. | MapViewModelTest + MockServiceTest | Actual foreground service start and provider grant |
| Live profile replacement | APPLY_CONFIG replaces movement, interval, accuracy, seed/realism and altitude; spot replacement stops following the previous route. Session time is retained, traversal time is reset. | MockServiceTest: actual route-to-static replacement | Other apps consuming fixes |
| Selected destination | Panning/zooming is viewport-only. Long-press/Set Destination is explicit. Apply destination now deliberately retargets a running session. | MapViewModelTest | MapLibre gesture/rendering behavior |
| Saved Spots and CSV | Existing named save/edit/folder actions remain. CSV supports preview, bounds, duplicate rejection and transactional inserts. | SpotsViewModelTest, SpotRepositoryTest, SpotCsvCodecTest | Android document providers and share apps |
| Route editing | Geometry undo/redo (100 steps), reverse order, walking/cycling/driving presets, Once/Loop/Ping-pong selector. | RouteStudioViewModelTest: 46 tests | Small screens and map gestures |
| Waypoint map placement | Add waypoint on map or move selected waypoint hides the modal editor, leaving the map usable. Confirm or long-press commits; cancel returns without moving the selected waypoint. | Parent wiring reviewed; VM commit/undo/cancel tests | End-to-end touch flow is not device-tested |
| Route playback | Start/Stop/Restart are honestly labeled. This is not pause-at-current-position/resume or seek. Loop repeats the open path and jumps to its beginning; Ping-pong reverses traversal. | RouteStrategy tests, RouteStudioViewModelTest | Sustained device run |
| GPX | 5 MiB read limit, 2,000-point route limit, off-Main file/codec work, busy/success/error states and explicit command failures. No silent truncation. | RouteStudioViewModelTest, GpxCodecTest | Picker/provider cancellation and real files |
| Joystick | Arrows react to DOWN/UP/CANCEL; accessibility click is one step; minimize/detach stop repeats. Disabling opt-in detaches the pad on the next state tick. | JoystickOverlayControllerTest actual touch events; JoystickServiceTest | Overlay permission, OEM windows, touch rendering |
| Auto-stop timer | More → Auto-stop: 5/15/30/60 minutes. Cancel supported. START clears the prior timer; live config replacement retains it. Stop, permission loss and destroy clear the deadline. | MockServiceTest deadline/cancel/destroy tests | Actual elapsed timer scheduling/OEM killing; timer is not persisted |
| Show simulated position | More action recenters camera on the live simulated sample without staging another destination. | Existing camera-only path tests + wiring review | Native map camera |
| IP lookup/sync | Controller observes actual enabled/interval preferences, including restore. Manual→auto polling restarts. Process-lifetime owner no longer depends on an open Activity. Manual choices hold target ownership until explicit sync re-enable. | SyncControllerTest, RuntimeIpSyncTest, MapViewModel IP tests | VPN split routing, provider availability and background OEM behavior |
| IP history | Records new lookup results, not hydrated cache as a new observation. Retention 0/50/200/500; lazy sheet can request all 500. | IpHistory tests + RuntimeIpSyncTest | Large-list rendering and share target |
| Search | Repeating consumed coordinates works; new queries clear stale results immediately; stale callbacks cannot place or misattribute a result. | SearchViewModelTest + SearchSheet guarded callbacks | Real Photon/Nominatim availability |
| Timeline | Selected trace follows Room position updates, clears stale buffers and collapses pruned/deleted sessions. | TimelineViewModelTest, SessionRepositoryPositionFlowTest | Large traces/map overlay performance |
| Dashboard | Route remaining uses movementStartedAt instead of the original session start after swaps/restarts. | Existing projection tests and MockService traversal epoch test | Real-time display; accumulated distance is observed-sample distance, not a forensic full trace |
| JSON backup/restore | Rejects object/array schemaVersion safely, validates service-compatible accuracy/interval, preserves IP history retention, bounds reads, applies settings in one edit with best-effort compensation. Apply/storage failures have a distinct warning. | BackupCodec/Manager/Settings/Gateway regressions | Document providers, disk failures and crash recovery |
| Widget / Quick Settings | Tile sets the real framework state on API 26/28/36 and observes engine transitions while listening; no optimistic Active on a failed start. Last-used config persists for deliberate restart after process restart. | MockTileServiceTest, MockWidgetProviderTest, ActiveSimRepositoryTest; restoration wiring reviewed | Real launcher/tile hosts and process-restart timing |
| Setup / diagnostics / guide / about | Existing permission checks, guide and identity links retained. Back dismisses overlay screens instead of immediately exiting the Activity. | Permission/Guide/About/Diagnostics tests; Back wiring reviewed | OEM settings/AppOps, accessibility and rotation |

## Important limits (not hidden behind fake controls)

- Android mock locations are detectable via mock flags. There is no anti-detection, root hooking or app-security bypass.
- There is no road snapping, offline map-pack downloader, KML route importer, real-GPS recorder, route seek or genuine pause/resume in this release. Planned roadmap items are not shipped features.
- Backup restore is not globally atomic across SQLite and DataStore. Settings are written as one batch inside the Room transaction. Settings failures roll back Room; a later Room failure triggers conflict-aware settings compensation. Process death between stores, compensation IO failure, or concurrent edits can still leave differences. The UI warns to inspect data after apply failure; keep the original backup.
- The auto-stop timer does not survive process death; restarting a run cancels it. Its displayed deadline uses wall time, so changing the device clock can affect deadline interpretation. No unattended auto-resume or boot-start is claimed.
- Saved profile preparation is screen/ViewModel state. After process recreation, select the profile again before Go. Last-used engine config is persisted for explicit widget/tile restart; this is not automatic recovery of an active run.
- Large historical session traces are not paginated; very long recordings may need further performance work. Map-style annotation reload ordering and visual sizing still need real MapLibre/device smoke tests.
- IP-provider coordinates are approximate, and a public-IP result alone does not prove that every device/app flow uses the VPN. Manual target handoff deliberately suppresses engine sync until the user re-enables it.
- Some lower-priority migration/rotation/keyboard interactions remain code-reviewed rather than instrumented UI-tested. No claim of “all bugs eliminated” is justified without those tests.

## Device acceptance checklist

1. Back up existing data before installing; this is a debug-signed APK. A differently signed app may reject the update. Do not uninstall data you need without a backup.
2. Select GhostRoute as the Android mock-location app and verify the setup/diagnostics screen.
3. Create a two-point route and a route profile. Prepare it, close Profiles, press Go; verify actual movement, selected mode and speed. Stop, then use Start profile directly.
4. While running a route, prepare a spot profile; verify the old route stops and the configured interval/accuracy are applied. Try a profile after a manual destination.
5. Pan/zoom without committing; confirm no new pin/history row. Explicitly set a destination and use Apply destination now.
6. Route Studio: add a waypoint on the map, cancel a move, undo/redo edits, reverse points, try each speed preset and each mode. Loop is a jump-back loop, not an invented road return.
7. Enable the joystick; tap/hold/release/cancel arrows, minimize, toggle the preference off, revoke overlay permission and stop the run. Verify motion does not continue after release.
8. Set/cancel/rearm a timer. Background the app and verify Stop; test OEM battery settings.
9. Export/import CSV and GPX with normal, malformed and oversized documents. Check summaries and explicit errors.
10. Test IP sync manual→automatic interval changes, opt-out, backgrounding/finishing the Activity and restoring enabled/disabled settings. Check history OFF and all retained rows.
11. Record a session, leave its timeline trace open while more points arrive, then delete it with confirmation.
12. Test tile/widget from fresh launch and after process restart; reject permission and confirm the UI does not falsely remain Active.

If reporting a failure, include the phone model, Android version, app version, exact steps, expected/actual behavior and a screenshot or screen recording. Never include release keys or secrets.
