# Contributing to GhostRoute

Thanks for your interest! GhostRoute is a small, intentionally strict codebase.

## Ground rules

- **Module purity is binding.** `:engine` and `:core-data` must never import `android.*` — the engine is pure Kotlin and must stay JVM-testable.
- **No new dependencies**, paid services, API keys, or telemetry. The app is deliberately keyless and offline-friendly (MapLibre + OpenFreeMap + Photon/Nominatim + OSRM, all keyless and disclosed in the README).
- **Test-driven** where logic is testable: Kotlin + Robolectric JVM tests. Run `./gradlew test` before proposing a change.
- **No placeholder UI** — every control must be functional, wired, and reachable.
- Keep user-facing strings in `strings.xml` (English + Persian) — no hardcoded copy.

## Workflow

1. Fork, create a topic branch.
2. Make your change with tests (RED → GREEN).
3. `./gradlew test assembleDebug` must pass.
4. Open a pull request with a short description and, if user-visible, before/after screenshots.

Commits follow `feat|fix|test|docs(scope): summary`.

## Reporting issues

Please include: device model, Android version, steps to reproduce, and the output from the in-app Diagnostics screen. Security-sensitive reports (anything about bypassing mock-location detection, crash bugs in the foreground service) — mark them clearly in the title.
