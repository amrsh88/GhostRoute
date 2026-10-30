# GhostRoute v0.2 — Feature Expansion, Fixes & Polish: Audit + Phased Roadmap

> **For agentic workers:** REQUIRED SUB-SKILL: subagent-driven pipeline (implement → controller verify → independent reviewer → reconcile) per task.
> Spec: user brief 2026-10-26 (Parts 1–7, saved at /root/.hermes/cache/documents/.../doc_7759aaf57cf5_Last.txt).

## Audit summary (2026-10-26, deleg_ab925d77, 3 read-only auditors)

- **A1 (IP/VPN)**: root cause = **IPv6 leak**. `ipwho.is` has AAAA; plain OkHttp (IpLocationClient.kt:59-62) follows them outside an IPv4-only TUN → real location. No GPS/fabricated fallback exists (grep-verified). Secondary: per-app split tunneling (user setting — must be *explained in UI*). The client discards response fields `type/region/isp` (IpLocation.kt:50-56) and the chip shows no IP/provider/timestamp — masking the leak. IPv4-vs-IPv6 independent lookup is feasible with two Dns-filtered OkHttpClients + `https://ipwho.is/{ip}` geolocate; zero new deps.
- **A2 (history)**: recents exist but are tap-only (no dedup, no labels, no per-item delete, never written from pin/geocode); folder rename/delete are repo-only (no UI); spot coords not editable; timeline F-50 absent. Room v1, `exportSchema=false` — schema baseline must be committed BEFORE v2 migration. Migration = additive `MIGRATION_1_2`.
- **A3 (UI)**: single-Activity binary routing (wizard↔map). No settings, About, diagnostics, dashboard, Route Studio UI, map-style picker, search history/copy-paste, or animations beyond pin pulse + FAB morph. Engine already supplies everything a dashboard and Route Studio UI need (`Running.spot/startedAt/lastSample`, `SET_ROUTE` lat-lng list + speed + loop/pingpong/once) — unused.

## Global constraints (binding, every task)
- Preserve all working behavior; no rewrites of working components; audit before declaring missing.
- No new deps / no API keys / no telemetry / no paid services. MapLibre + OpenFreeMap stay.
- en+fa string parity; no `android.*` in `:engine`; baseline-compatible intent API.
- Strict TDD (RED→GREEN), one commit per task, ledger entry per task, independent reviewer per task, controller verifies personally.
- Never fabricate data: failed lookups → stale+reason; dashboard/diagnostics only real state.
- Manual pin drop always wins over IP sync (v0.1 ruling preserved).
- Machine: 2 CPU/7 GB (jvmargs -Xmx2560m, workers.max=2); Robolectric only, no emulator.

## Phase 1 — Correctness (first)
### P1-1 IP/VPN correctness (CRITICAL — user's field bug)
1a. Parse full ipwho.is response: `ip`, `type` (IPv4/IPv6), `region`, `country`, keep city/lat/lng; timestamp every success. Malformed → Error (fail-closed as today).
1b. Dual-stack lookup: two OkHttpClients with filtered `Dns` (A-only / AAAA-only); geolocate each stack's observed IP via `https://ipwho.is/{ip}`; unavailable stack → its own Error; **leak detection** = stacks materially disagree (city or 3-dec cell) → new `IpOutcome` info surfaced as `ipv6Leak` warning, never a location swap by itself (prefer the address-family the VPN actually tunnels: display both, sync from the TUN-visible one — default IPv4).
1c. UI: IP chip/section shows detected IP + city/region/country, provider label, "last update Xm ago", stale-reason, ipv6-leak + split-tunnel explainer dialog (en+fa). Refresh-interval setting (manual / 60s / 2m / 5m / off) wired to SyncController poll period (60s floor kept). Never touch GPS coordinates on failure.
- Acceptance: MockWebServer tests for parse/429/timeout/malformed; Dns-filter unit tests; chip-state VM tests; en+fa parity; all 205+ green; zero new deps/permissions.

### P1-2 History correctness (recents + saved + DB v2)
2a. Enable `exportSchema=true` + schemas dir; commit `schemas/1.json` baseline (BEFORE migration).
2b. Room v2 (additive MIGRATION_1_2, no data loss): `recents` gains label; `sessions` + `positions` tables created (for P2 timeline) in the SAME migration; composite index (sessionId, ts); FK cascade to sessions.
2c. Recents: record ALL successful selections (pin drop, geocode pick, IP-sync target, route start); dedup by rounded coordinate (update label+timestamp instead of new row); per-item delete + clear-all UI; polished empty state; timestamps shown.
2d. Saved+folders UI completion: folder rename/delete wired in UI (delete keeps unfiling semantics + confirm), spot coordinate editing, duplicate-name guards (create+rename), repository-level coordinate validation.
- Acceptance: Room migration test (v1→v2 preserves rows); DAO/VM/UI tests; write-path tests pin the dedup rule.

### P1-3 About + Settings + Diagnostics anchor
3a. Settings sheet (from map): style picker (dark/light — persisted via existing `mapStyle` pref), IP refresh interval, retention/clear-history, About entry.
3b. About screen: real versionName/versionCode (BuildConfig), credits (MapLibre, OpenFreeMap, OpenStreetMap, Photon, ipwho.is, kotlinx/OkHttp/Room), what-network-calls-we-make privacy disclosure, no invented URLs/licenses.
3c. Diagnostics sheet: mock-slot status via existing MockSlotDiagnostics/AppOp plumbing, service state, last update time, IP-sync status, actionable recovery text; export-report button (redacted by default).
- Acceptance: reachable through UI (no dead entries); every value from real state (Robolectric tests); en+fa.

## Phase 2 — Core expansion
- **P2-1 Session timeline (F-50)**: recorder collecting EngineState position changes (>N m or >T s), batch inserts off hot loop; session lifecycle rows (start/stop/interrupt-recovery via ActiveSimRepository); timeline sheet (chronological, timestamps, coords, rename/delete), route polyline on map, retention policy + clear-all; sessions survive restarts.
- **P2-2 Route Studio**: multi-waypoint map editing (add/move/reorder/remove), polyline preview, distance/duration estimate, speed control, pause/resume/restart, loop/reverse, save/load routes (Room), GPX 1.1 import (validated, malformed-safe) + export. Reuses engine SET_ROUTE — zero movement logic in UI.
- **P2-3 Session dashboard**: live coords/speed/elapsed/distance/active route or profile/IP-sync state — only real values (Running.spot/startedAt/lastSample already exist).
- **P2-4 Search improvements**: search history (persisted), copy/paste coordinates, ambiguous-result labels, geographic labels.
- **P2-5 Backup & restore**: versioned JSON export/import (saved, folders, routes, profiles, settings) via SAF document picker; validate + version-check + import summary; no secrets in exports.
- **P2-6 Location profiles**: location+route+movement presets; create/edit/duplicate/delete/activate; activation never overrides manual pin or silently enables sync.

## Phase 3 — UI polish
- One or two complementary accents on the teal base; consistent tokens.
- Screen/sheet transitions, marker position animation during runs (never misrepresenting data), animated loading/states, reduced-motion respect.
- Empty/error/loading states everywhere; spacing/type/radius consistency; accessibility pass.

## Phase 4 — Optional advanced (assess after audit of value/complexity)
- Floating joystick overlay (go/no-go decision documented first), extra map styles (provider terms verified), automation/shortcuts + documented intent surface (Tasker later).

## Release
- Tag `v0.2.0` after full pipeline green; APKs delivered. Sub-phases may release separately (`v0.1.x` correctness patch if a fix is urgent).
