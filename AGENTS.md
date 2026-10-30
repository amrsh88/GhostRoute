# AGENTS.md — operational rules for any agent/session working on GhostRoute

1. **Read PROJECT_REQUIREMENTS.md first** — at session start and before starting
   any new task. It is the living record of requirements, feature status
   (PLANNED/IN PROGRESS/COMPLETE/BLOCKED), identity links, and known issues.
   Update it (and the changelog below) after every completed task.
2. Preserve working functionality. Audit before declaring anything missing or
   complete. Never rewrite working components without a ruled reason.
3. Engine / core-data / app separation is binding: no `android.*` in `:engine`
   or `:core-data`; engine changes need an explicit E-ruling (do-not-touch by
   default).
4. No new dependencies, paid services, API keys, or telemetry. No placeholder
   UI, fake data, or dead buttons — every control functional, integrated,
   tested, and reachable.
5. TDD where logic is testable (RED proven honestly — beware false greens from
   real IO landing mid-test); Kotlin/Robolectric JVM tests; commits small and
   meaningful (`feat|fix|test|docs(scope): summary`), tree green per commit.
6. **Never claim tests/builds/device-behavior passed unless actually executed.**
   There is no emulator/KVM on this host — NEVER claim device verification.
7. Gradle protocol: `./gradlew --stop` first; long runs in background with
   `-Dorg.gradle.jvmargs='-Xmx2048m' -Dkotlin.daemon.jvm.options='-Xmx1400m'`;
   NEVER edit gradle.properties; never two concurrent builds; two consecutive
   fresh `--rerun-tasks` green gates is the completion bar; if the combined
   test+lint run dies with "daemon disappeared" after writing results, re-run
   `:app:lintDebug` standalone. Poll loops ≤ ~400 s.
8. Robolectric 4.17 known limits (documented in tests): implicit broadcasts to
   runtime receivers undependable (drive handlers directly); service-PI `send()`
   enqueues without delivering (assert `nextStartedService` + dispatch manually).
9. Branch: `mvp` (never master). Repo-local identity Amirreza
   <amirreza@ghostroute.local>.
10. Scan every patched file for glitch tokens (stray words like `BubbleGum`,
    ` berlin`) before committing.
11. Running tasks ledger: `.superpowers/sdd/2026-10-26-ghostroute-mvp/progress.md`
    (gitignored; heredoc-append only). Unique task IDs: P5-x for the current
    phase.
12. Changelog: `CHANGELOG.md` (keep-a-changelog format) — update per release
    and per user-visible fix.

## Change log (major)
- 2026-10-27 (P1, f9d4ff2+6c43c09): IP/VPN rework — lookups session-independent,
  background polls display-only ("use IP location" is the only map placement),
  joystick nudge no longer marks sync "manual pin", honest VPN-tunnel/split-
  tunnel status (VpnTunnel probe, :app). TEST RULE learned: injected fake
  lookups must resolve after a real-dispatcher hop, else the poll's virtual
  delay loop livelocks advanceUntilIdle; unit tests use the
  MapViewModel.ipLookupOverride seam and never hit literal https.
- 2026-10-27: PROJECT_REQUIREMENTS.md + AGENTS.md created (per user directive).
  Notification-Stop PendingIntent bug fixed (getBroadcast→getService, RED→GREEN).
  v0.2.1 phase started per user's PDF roadmap (P0: requirements/about/stop-fix;
  P1: IP rework; P2: studio/profiles/maps; P3: tile/automation/backup/polish;
  P4: offline maps/advanced routing).
