# GhostRoute MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship GhostRoute Tier-1 (MVP): map-first fake-GPS app with a robust mock engine, wizard, spots, search, tile/widget, and a baseline-compatible intent API.

**Architecture:** Three Gradle modules — `:engine` (pure-Kotlin geo/movement/realism, JVM-tested), `:core-data` (Room + DataStore + network clients), `:app` (Compose UI, FGS service, DI). Service owns `EngineState: StateFlow`; UI observes.

**Tech Stack:** Kotlin 2.2, AGP 8.13, Gradle 9.1 (JDK 21 daemon), Compose + Material 3, MapLibre Native, Room, DataStore, Retrofit/OkHttp.

**Spec:** `/root/GhostRoute/docs/DESIGN.md` (the plan argues from the spec; executors read both).

**Amendments 2026-10-26 (from approved brainstorm proposal, `docs/proposals/2026-10-26-feature-brainstorm.md`):**
- Task 2: `SimulatorConfig` gains a `seed: Long` field (F-45 dependency; breaking later if skipped).
- Task 4: distinguish mock-slot failure modes in diagnostics: app deselected vs slot cleared (dev-options toggle resets `secure mock_location` + appop while Settings still shows the app). Feeds F-09 self-heal.
- Task 5: add `lastActiveSimConfig` persistence (F-24 boot/crash recovery dependency).
- Task 7: `GeocodeClient` is **Photon-first** (search-as-you-type permitted) with Nominatim fallback (1 req/s, identifying UA `GhostRoute/<ver> (+github.com/amrsh88)`), per verified usage policies.
- Task 6: intent filters must handle incoming `geo:` URIs and share-intake (F-46).
- Out of MVP scope (later tiers per proposal §5): F-02 route builder, F-08 Shizuku grant, F-27 profiles, F-36 diagnostics center — planned as the "Reliability release" (F-09/F-36/F-37/38/39/24 + polish) immediately after MVP.

## Global Constraints
- minSdk 26; targetSdk 36; compileSdk 36; Kotlin jvmTarget 21.
- `:engine` must not import `android.*` or any Android artifact.
- No Firebase/GA/Crashlytics/Ads dependencies anywhere.
- All user-visible strings via resources; locales `values/` (en) and `values-fa/` from Task 2 onward.
- Permissions declared: INTERNET, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, ACCESS_MOCK_LOCATION, POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, WAKE_LOCK.
- Package `app.ghostroute`; applicationId `app.ghostroute`.

## Review Focus
1. **Mock app deselected mid-run** → engine must stop cleanly and surface diagnosis, never crash-loop. Pinned by Task 4 test `stops on SecurityException`.
2. **Interval larger than traversal demands** (slow ticker on fast route) → apps see teleport jumps; ticker must self-quantize to speed. Pinned by Task 3 test `ticker quantizes to speed`.
3. **Zero/absent accuracy or bearing** on injected samples → detection heuristics fire; `SignalRealizer` must always emit plausible accuracy>0 and bearing when moving. Pinned by Task 2 tests.
4. **Non-`en`/`fa` system locale** → must fall back to English without missing-resource crashes. Pinned by Task 10 lint check.
5. **FLP absent (no GMS)** → fused injection must be optional; app must not crash on de-Googled ROMs. Pinned by Task 4 test `skips fused when unavailable`.

---

### Task 1: Project scaffold that builds
**Files:** Create `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/app/ghostroute/GhostRouteApp.kt`, `app/src/main/java/app/ghostroute/MainActivity.kt`, `:engine` + `:core-data` module shells.
**Interfaces:** Produces: compilable `app.ghostroute.MainActivity` (Compose "GhostRoute" placeholder), modules resolvable as `:engine`, `:core-data`.

- [ ] Write root Gradle files + version catalog (Kotlin 2.2.x, AGP 8.13.x, compose-bom latest, maplibre, room 2.7.x, datastore, retrofit 3.x)
- [ ] `gradle.properties`: `org.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64`, `org.gradle.jvmargs=-Xmx4g`
- [ ] Run: `gradle :app:assembleDebug` → BUILD SUCCESSFUL, APK exists at `app/build/outputs/apk/debug/`
- [ ] `git init && git add -A && git commit -m "chore: scaffold"`

### Task 2: Engine models + SignalRealizer (TDD)
**Files:** Create `engine/src/main/kotlin/app/ghostroute/engine/{SimulatorConfig.kt,LocationSample.kt,RealismPolicy.kt}`; Test `engine/src/test/kotlin/.../SignalRealizerTest.kt`.
**Interfaces:** Produces: `data class LocationSample(lat, lng, altM, bearingDeg, speedMps, accuracyM, t: Instant)`; `class RealismPolicy` with `fun realize(base: Waypoint, moving: Boolean, prev: LocationSample?): LocationSample`; `data class Waypoint(lat, lng)`.

- [ ] Write failing tests: accuracy always > 0; bearing 0..360; moving ⇒ speed>0 and bearing set; stationary ⇒ speed≈0; timestamps monotonic
- [ ] `gradle :engine:test` → FAIL (unresolved)
- [ ] Implement models + `RealismPolicy` (noise: accuracy ~ N(base, base*0.15) clamped [5, 50]m; speed from Δpos/Δt; bearing from Δpos)
- [ ] `gradle :engine:test` → PASS
- [ ] Commit `feat(engine): models and realism policy`

### Task 3: Movement strategies (TDD)
**Files:** Create `engine/.../movement/{MovementSpec.kt,MovementStrategy.kt,StaticStrategy.kt,JitterStrategy.kt,RouteStrategy.kt}`; Test `movement/MovementStrategyTest.kt`.
**Interfaces:** Produces: `sealed interface MovementSpec`; `interface MovementStrategy { val spec: MovementSpec; fun tick(t: Instant): Waypoint }`; `fun quantizeInterval(speedMps: Double, intervalMs: Long): Long` (helpers `geo.kt`: `haversineM`, `bearingDeg`, `interpolate`).

- [ ] Failing tests: Static returns same point; Jitter stays within radiusM (uniform-disk, like baseline's math but bounded); RouteStrategy advances along polyline at speedMps — after 60s at 10m/s from p0 toward p1 (100m apart) position is 600m along path; Loop wraps; PingPong reverses; `quantizeInterval(10, 5000)` ≤ 1000
- [ ] `gradle :engine:test` → FAIL → implement → PASS
- [ ] Commit `feat(engine): movement strategies`

### Task 4: LocationInjector + MockService (Robolectric)
**Files:** Create `app/.../location/{LocationInjector.kt,MockService.kt}`; Test `app/src/test/.../{LocationInjectorTest.kt,MockServiceTest.kt}`.
**Interfaces:** Consumes Task 2/3 types. Produces: `class LocationInjector(lm: LocationManager, flp: Any? /* FLP or null */): fun bind(providers); fun inject(sample); fun unbind()`; `class MockService : Service` with `EngineState` + actions `app.ghostroute.START/STOP/UPDATE/SET_ROUTE/PING` (extras: `extra-simulator-config` Parcelable + `latitude`/`longitude` strings, baseline-compatible), broadcast `app.ghostroute.ACTION_MOCK_PERMISSION_LOST`.

- [ ] Robolectric tests: register/unbind calls add/removeTestProvider for gps+network; inject sets test provider locations with fresh timestamps; fused-null path no-crash; SecurityException ⇒ unbind + broadcast + stopSelf
- [ ] RED → implement → GREEN
- [ ] Manifest: service entry, `specialUse` FGS + PROPERTY_SPECIAL_USE_FGS_SUBTYPE, intent-filters, receiver-less design
- [ ] Commit `feat(app): injector and mock service`

### Task 5: DataStore prefs + last-position persistence
**Files:** Create `core-data/.../{SettingsRepository.kt,LastPositionRepository.kt}`; Test `core-data/src/test/.../SettingsRepositoryTest.kt`.
**Interfaces:** Produces: `SettingsRepository.flow: Flow<AppSettings>` / `suspend set(...)` (intervalMs, accuracyM, showNotification, map style, units); `LastPositionRepository.get/set(lat,lng)` (keys `pref_last_known_latitude/longitude`, baseline-compatible).

- [ ] Test defaults + roundtrip (Robolectric/DataStore test) RED→GREEN
- [ ] Commit `feat(core-data): settings and last position`

### Task 6: Map main screen
**Files:** Create `app/.../ui/map/{MapScreen.kt,MapViewModel.kt,StatusChips.kt,PinComposable.kt}`, `app/.../theme/{Theme.kt,Color.kt,Type.kt}`; Modify `MainActivity.kt`.
**Interfaces:** Consumes EngineState + repos. Produces: `MapViewModel(uiState: StateFlow<MapUiState>, onPinMoved(Waypoint), onGo(), onStop())`; `MapUiState(center, pin: Waypoint?, engine: EngineState, settings)`.

- [ ] Compose screen: MapLibre view in `AndroidView`, floating pin via overlay composable, bottom sheet (spot card), FAB morph Go/Stop, status chips row
- [ ] ViewModel wires: intent-less start via service binding; haptics on pin drop/Go/Stop
- [ ] Manual verify on emulator: pin drag → Go → mock location visible in a test client app
- [ ] Commit `feat(app): map screen and engine wiring`

### Task 7: Search + coordinate input
**Files:** Create `core-data/.../net/{GeocodeClient.kt}`, `app/.../ui/search/{SearchSheet.kt,SearchViewModel.kt}`.
**Interfaces:** Produces: `GeocodeClient.search(q): List<PlaceResult(name, lat, lng, type)>` (Nominatim `/search?format=jsonv2`, Photon fallback), `parseCoords(s): Waypoint?` supporting `dd.dd, ddd.dd` and `DD MM SS.SS" N` forms.

- [ ] `parseCoords` unit tests (DD, DMS, negative/southern/western forms) RED→GREEN (pure JVM, lives in `:engine`)
- [ ] Search sheet UI: debounced search, results list, tap → move pin
- [ ] Commit `feat: search and coordinate entry`

### Task 8: Spots, recents, folders
**Files:** Create `core-data/.../db/{GhostRouteDb.kt,SpotEntity.kt,FolderEntity.kt,RecentEntity.kt,Daos.kt}`, `core-data/.../SpotRepository.kt`, `app/.../ui/spots/{SpotsSheet.kt,SpotsViewModel.kt}`.
**Interfaces:** Produces: `SpotRepository.upsert/delete/byFolder/all/recents` (Flow-returning); DB v1 schema per DESIGN §6.

- [ ] DAO tests (Robolectric, in-memory) RED→GREEN
- [ ] Manage sheet UI: list, folders as tabs, long-press context menu (rename/move/delete), tap → jump pin
- [ ] Commit `feat(core-data,app): spots and folders`

### Task 9: Setup wizard
**Files:** Create `app/.../ui/wizard/{WizardScreen.kt,WizardViewModel.kt}`, `app/.../system/{PermissionChecker.kt,DevSettingsLauncher.kt}`.
**Interfaces:** Produces: `PermissionChecker.status(): WizardStatus(devMode, mockSelected, notifications, overlay)`; launcher intents: `ACTION_APPLICATION_DEVELOPMENT_SETTINGS`, mock-app picker (`ACTION_APPLICATION_DEVELOPMENT_SETTINGS` + guide card — API-dependent, see code comment), POST_NOTIFICATIONS runtime.

- [ ] Wizard: 4 steps with progress dots, live `WizardStatus` recheck on `ON_RESUME`, "Open settings" buttons, final "All set" → main
- [ ] Emulator verify: wizard completes on API 30+ image
- [ ] Commit `feat(app): setup wizard`

### Task 10: Tile, widget, intent hardening, i18n
**Files:** Create `app/.../tile/MockTileService.kt`, `app/.../widget/{MockWidgetProvider.kt,WidgetCompose? no—RemoteViews}`; Modify `AndroidManifest.xml`; Create `app/src/main/res/values-fa/strings.xml`.
**Interfaces:** Produces: TileService toggle bound to EngineState; widget RemoteViews (on/off + open app); exhaustive fa translations of all en strings.

- [ ] Tile tests via Robolectric (toggle → service action)
- [ ] Widget provider + manifest receiver; update on state change
- [ ] `gradle :app:lintDebug` → zero errors; verify `values-fa` coverage (missing-translation lint passes)
- [ ] Commit `feat(app): tile, widget, fa i18n`

### Task 11: Release readiness
**Files:** Modify `app/build.gradle.kts` (release config, R8 full, resource shrink); Create `README.md` (build + install + dev-mode instructions, fa+en).
- [ ] `gradle :app:assembleRelease` → unsigned release APK builds clean
- [ ] Tag `v0.1.0-mvp`
