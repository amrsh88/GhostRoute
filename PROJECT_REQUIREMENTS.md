# GhostRoute — PROJECT_REQUIREMENTS.md

Living record of requirements, features, decisions, and task status.
Every session must read this file first (enforced by AGENTS.md).
Source-of-truth brief: `Last.txt` (user, Parts 1–7) + the 2026-10-27 PDF roadmap
(Parts 1–13, priorities P0–P4) archived at
`docs/briefs/2026-10-27-jssk-roadmap.txt`.

**Status vocabulary:** PLANNED / IN PROGRESS / COMPLETE / BLOCKED (with reason).

## CRITICAL RULES (binding, from the user)
No rewrites of working code; audit before build; every feature functional in the
UI (no placeholders/fake data/dead buttons); engine/core-data/app separation;
phased plan + independent review; incremental commits; no new deps / paid
services / API keys / telemetry; never claim unexecuted tests; correctness >
feature count; update this file after every completed task.

## Identity (fixed by user)
- Developer: **Amirreza**
- GitHub: https://github.com/amrsh88
- Telegram Channel: https://t.me/net_republic
- Telegram Community: https://t.me/netrepublic (Net Republic — technology &
  free/open-internet community)
- Repo identity for commits: Amirreza <amirreza@ghostroute.local>

## 0.3.1 Night Navigation design pass

- Colorful modern UI: layered navy, periwinkle primary actions, navigation blue and soft mint accents. Live teal, warning amber and Stop/error rose remain distinct.
- Redrawn map dock, fixed session action, four-item icon navigation, grouped toolkit, clearer profile actions, dashboard metric cards, shared surfaces and typography.
- Status and dock share height budgets; network details collapse; large-font coordinates adapt. Three actual Compose render fixtures include 320×568 Persian RTL at 150% text size, asserting no status/dock overlap.
- Full test suite: 1,415 executions, no failures/errors/skips. Lint passes with warnings; debug APK built. Native map, OEM overlays, keyboard and device acceptance remain unverified.
- Design notes and preview scope: docs/DESIGN-0.3.1.md. Existing 0.3.0 feature/runtime limitations still apply.

## 0.3.0 audited feature pass — current behavior overrides historical rulings below

- Profiles have distinct Prepare and Start profile actions. A prior manual pin no longer permanently blocks an explicit profile selection. A prepared route retains its points, speed, mode, interval and accuracy for Go. Full live config replacement supports route-to-spot transitions.
- Added route undo/redo, reverse order, walking/cycling/driving presets, Ping-pong selection, explicit add/move-waypoint map mode, live Apply Destination Now, Show simulated position, and an auto-stop timer.
- Repaired joystick release/cancel/repeat lifecycle and live opt-out; tile state APIs/observation; reactive timeline; repeated-coordinate/stale-result search; malformed backup version handling and settings validation; manual-to-auto IP polling; process-lifetime IP sync and opt-in history; route remaining after traversal resets; partial provider-bind cleanup; serialized engine commands/ticks.
- Backup has atomic Room writes plus one DataStore batch and best-effort compensation, NOT a globally atomic cross-store guarantee. Process death/compensation failure remain limitations and are disclosed in the verification report.
- Verified gates: 1,397 unit/Robolectric executions (0 failures/errors/skips), standalone lintDebug passed with warnings, debug APK assembled. No device/emulator UI or OEM verification is claimed.
- Full feature inventory, exact remaining limits and device acceptance checklist: docs/FEATURE-VERIFICATION-0.3.0.md.
- This is an installable debug test build, not a claim that every historical roadmap item is implemented or every phone is validated. Native map styling, large traces, OEM overlays/settings and real VPN routing require device acceptance.

## Feature & Status Ledger

### Location simulation
- [COMPLETE] Spot simulation (lat/lng, interval, accuracy) — v0.1
- [COMPLETE] Route Studio: draw/edit/reorder waypoints, distance/duration, speed, preview — v0.1–v0.2
- [COMPLETE] Route playback: pause=hold in place (0.4.0), Once/Loop/PingPong — **seek BLOCKED** (pause/resume shipped in 0.4.0) (engine-seek concept; E13 deferral, do-not-touch :engine without new ruling)
- [COMPLETE] Floating joystick overlay (opt-in, S/M/L steps, stop, minimize/drag, 3 fail-closed gates) — v0.2 P4b
- [COMPLETE] Location profiles (create/edit/duplicate/delete/activate; corrupt rows blocked honestly) — v0.2 P2-6
- [PLANNED] Altitude/bearing simulation knobs (engine supports bearing; altitude plumbing PARTIAL via LocationSample.altM)
- [COMPLETE] GPX import/export — v0.1/v0.2; [PLANNED] KML/CSV import/export
- [COMPLETE] Session recording + timeline + retention (lossy thresholded trace, documented)
- [PLANNED] Location recording/replay of arbitrary traces

### Maps & navigation
- [COMPLETE] MapLibre + OpenFreeMap (keyless): dark/light/liberty, persisted choice
- [COMPLETE] Photon search (+coordinate parse); [PLANNED] search history: PARTIAL (recents), full history IN PROGRESS
- [COMPLETE] Saved locations + folders (create/rename/move/delete)
- [COMPLETE 2026-10-28; device verification pending] Exact-point long-press selection, explicit Set Destination from the map center, named-pin saving, `geo:` sharing, and live straight-line range/bearing from the simulated fix to the staged destination. Ordinary pan/zoom is viewport-only and creates no pin or recent. Unit tests and debug build pass.
- [COMPLETE 2026-10-28; device verification pending] Named-Spot CSV import/export with bounded reads, full-file validation, preview, duplicate rejection, spreadsheet-safe text, and transactional batch insert. Unit tests and debug build pass; this is Spot CSV, not KML or route CSV.
- [PLANNED] Offline-map support (tile caching; provider-terms check required)
- [PLANNED] Road-following/routing (BLOCKED: external routing service adds dependency; needs user ruling)

### Automation
- [COMPLETE] Intent API (START/STOP/UPDATE/SET_ROUTE/PING + app-level OPEN_SPOT/OPEN_ROUTE, validated; docs/intents.md)
- [COMPLETE] Launcher shortcuts (Stop, Last location)
- [PLANNED] Quick Settings tile — PARTIAL exists (tile/MockTileService), needs doc verification
- [COMPLETE] Notification status + controls (Stop FIXED 2026-10-27 — broadcast-to-service bug)
- [PLANNED] Tasker integration (after intent API soak) / Scheduled simulation (background-restrict caveat)

### Diagnostics & reliability
- [COMPLETE] Session dashboard (real engine state: coords, speed, elapsed, distance, route progress/remaining — E10/E11)
- [COMPLETE] Diagnostics sheet (mock permission, service state, last update, recovery text)
- [COMPLETE] Backup/restore (versioned, all-or-nothing, strict gates)
- [PLANNED] Location-provider failure UI expansion / lost-permission recovery flow verification

### UI & experience
- [COMPLETE] Token system (5-step radii/spacings), GhostPill, amber(warn)/rose(danger)
- [COMPLETE] Reduced-motion gate, honest run-marker, sheet transitions, a11y pass
- [COMPLETE] User-visible polish pass v0.2.1 (P5d, user feedback: earlier pass
  too subtle) — branded map status hero bar, IP panel rework (hero public-IP
  line, freshness pill, tunnel-status banner, icon actions, "use IP location"
  button), pin contrast rim/halo, floating pills/cards/sheets (shared
  ghostSheet treatment + shadow), icon+title sheet headers, aligned key-value
  rows with dividers, route-studio section headers + ghost-branded empty
  state. Tone classifiers (TunnelTone/freshnessTone) unit-tested; en/fa
  parity holds (one new key: route_section_waypoints).
- [COMPLETE 2026-10-28; device verification pending] Map destination pass:
  restored/explicit state gates native map creation; camera requests remain
  pending until MapLibre is ready; MapView lifecycle is forwarded; pan/zoom and
  camera-only recenter never stage pins. A single scroll-safe control panel
  replaces opposing bottom controls. Regression tests cover early intake,
  viewport-only movement, and manual-selection/IP-sync ownership.
- [COMPLETE 2026-10-28; device verification pending] Long-press exact-point
  selection, explicit Set Destination, named-Spot save, coordinate copy/share,
  live range/bearing, and one full-width responsive control panel with tools in
  More. English/Persian resources parity checked; unit suite and standalone
  debug lint pass.
- [COMPLETE 2026-10-28; device verification pending] A confirm-gated cleanup
  removes only Recents rows labeled "manual pin"; named Spots and other recents
  remain untouched.
- [COMPLETE 2026-10-28; device verification pending] Saved-Spot CSV import/export
  with bounded file reads, full-file preview/validation, duplicate rejection,
  and all-or-nothing Room writes. Codec and repository tests pass.
- [PLANNED] First-run developer-mode guide — DONE (see About/Credits section)

### Privacy (standing)
- [COMPLETE] No ads/analytics/accounts; external-service disclosure in About
- [COMPLETE] IP lookups opt-in; 60 s floor; explicit manual destination
  selections (map/search/geo/spot) take priority over sync; background IP
  polling is display-only (map untouched without explicit user action)
- [COMPLETE] IP history retention cap + clear-history (P6a: the dial lives on
  the history sheet — 0=off/50/200/500, default 200; off = recording disabled
  entirely; clear is confirm-gated)

### Public IP / VPN (Part 4 — HIGHEST PRIORITY)
- [COMPLETE] ipwho.is lookup + display (keyless) — v0.2; endpoint re-verified
  2026-10-27 (GET 200 keyless; HEAD answers 405 — the service is GET-only).
- [COMPLETE f9d4ff2] **Lookup runs INDEPENDENT of any running session** (user
  rework): poll whenever sync is enabled; configurable interval off/manual/
  60 s/2 min/5 min (0 = manual-only); 60 s hard floor kept (privacy rule);
  "refresh now" manual action (works at any setting, display-only).
- [COMPLETE 6c43c09] **Nudge no longer marks sync "manual pin"** (user report
  2026-10-27): a joystick nudge still disables sync (pad-vs-sync control
  handoff) but NEVER surfaces chip_ip_sync_manual and never latches the
  manual-pin activation gate. Disabled-by-pin now only from real pin drops.
- [COMPLETE 6c43c09] **Background IP polls never mutate the map** (binding):
  no pin, no camera, no lastPos/recents writes from polls. The ONLY paths
  that place anything on the map are explicit: "use IP location" (places
  pin+camera+lastPos+"IP exit" recent; drives the engine when Running) or a
  manual destination selection. Opt-in sync = engine-target-only while Running
  (UPDATE intent, manual-selection-override-gated, explicit selection wins).
- [COMPLETE 6c43c09] Honest VPN-exit verification (VpnTunnel, :app): a VPN
  reported active is not proof our traffic rides it — THROUGH_TUNNEL /
  BYPASSES_TUNNEL (split tunneling excludes the app → amber "NOT the VPN
  exit" warning) / NO_VPN / UNKNOWN (hidden, never guessed); lookups ride
  the default network (no bindProcessToNetwork); device-local (tun/wlan)
  addresses shown separately from the public exit; coords when supplied;
  IPv4+IPv6 dual-stack + leak warning (P1-1a/b) intact.
- [PLANNED] ISP/org display, DNS resolver info (provider parses exist; UI pending)
- [COMPLETE P6a] **Persistent IP history** (Room `ip_history`, DB v6, additive
  v5→v6): every SUCCESSFUL lookup records (background polls included); dedupe
  = consecutive identical ip+tunnel-status refreshes lastSeenAt in place; a
  different IP or observed tunnel status starts a new run; coords only when
  the provider supplied them; unknown tunnel status stays "" (never guessed).
  Timeline sheet from the IP panel ("history" clock action): newest-first
  rows (IP · place · relative time), retention dial 50/200/500/off (default
  200; off = recording disabled entirely — stated in-sheet), confirm-gated
  "clear", CSV export via ACTION_SEND (RFC 4180 quoting, IpHistoryCsv).
  HARD RULES held: recording is map-blind (no pin/camera/lastPos/recents);
  recording OFF never affects lookup display; JoystickService/notifications
  untouched.
- [PLANNED] IP Intelligence Dashboard (network type, VPN-as-reported vs verified, freshness)

### About / Credits (Part 2 — REQUIRED NOW)
- [COMPLETE 29fd4c3] Links are real ACTION_VIEW intents for the 4 identity
  entries (pinned in AboutContent.IDENTITY_LINKS + AboutLinksTest — exact
  URLs, fail-closed openExternal with graceful no-browser message).
- [COMPLETE 29fd4c3] First-run developer-mode guide: designed bilingual
  EN+فارسی HTML bundled in assets/guide.html, rendered in-app by
  GuideActivity, opened from the wizard's FIRST page (pinned by
  GuideActivityTest — bilingual content, identity links, no JS, no external
  resources).

## Release state
- v0.1.0-mvp tagged (ec35561). v0.2.0 tagged (543d89f; APKs delivered 2026-10-27).
- v0.2.1 in progress: notification-Stop fix (done), About links, dev-mode guide,
  IP rework, nudge de-pin, visible polish. Two consecutive fresh gates are the
  completion bar; final counts recorded in the ledger per release.
- Test counts lineage (fresh gates): 1087/0 (v0.2.0 release x2). Post-fix counts recorded per commit.

## Known issues / unfinished (persistent)
1. No emulator/KVM on build host — runtime/device verification relies on the user; never claim device testing.
2. Robolectric 4.17: implicit broadcasts to runtime receivers undependable; service-PI sends enqueue without delivery (both documented in tests).
3. The original uploaded archive contains keystore/release signing material. Treat it as exposed; the returned source bundle must omit it. Rotate before public distribution, plan for Android signing-key continuity, and remove existing tracked copies (adding .gitignore entries alone does not untrack them).
4. Combined full gate (test+lint+assemble) OOMs sometimes: split lint into its own run (protocol in AGENTS.md).
