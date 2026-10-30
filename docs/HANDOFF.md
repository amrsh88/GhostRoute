# GhostRoute — Session Handoff

**Date:** 2026-10-26 · **Status:** MVP approved, awaiting first build session
**Repo:** `/root/GhostRoute` (git; commit history exists — verify with `git log`)

## State
- **DESIGN.md** (`docs/DESIGN.md`) — approved, with 2026-10-26 amendment: disguise feature dropped; Photon-first search; Shizuku grant + mock-slot self-heal planned; seed field + last-state persistence.
- **MVP plan** (`docs/plans/2026-10-26-ghostroute-mvp.md`) — 11 tasks, TDD, checkboxes all unchecked; amendments block added at top. Execute with superpowers:subagent-driven-development or superpowers:executing-plans.
- **Feature proposal** (`docs/proposals/2026-10-26-feature-brainstorm.md`) — 48 features (F-01…F-48), 4-tier roadmap, top-10 differentiators, final shortlist of 15. Tier-1 "Reliability release" (F-09, F-36, F-01, F-10+35, F-06, F-13, F-24, F-39, F-37/38, F-46, F-17, F-16, F-23, F-28/30) comes after MVP.

## Toolchain (verified on this machine)
- JDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64` (gradle.properties sets `org.gradle.java.home`)
- Gradle 9.1 daemon, AGP 8.13, Kotlin 2.2
- Android SDK installed; emulator available for manual Task 6/9 verification
- Baseline reference APK (Fake GPS Pro 5.8.7) was REA-decompiled in a prior session for behavioral parity of the intent API

## Order for a new session
1. Read DESIGN.md + the MVP plan top-to-bottom (the plan's Review Focus pins 5 failure modes).
2. Execute Task 1 → 11 in order, TDD (RED→GREEN per task), commit per task.
3. Do not skip the lint/i18n gate in Task 10 (`values-fa` coverage) or the emulator verifications in Tasks 6/9.
4. After `v0.1.0-mvp`: start the Reliability release from the proposal's Tier 1.

## Non-negotiables
- `:engine` has zero `android.*` imports.
- No Firebase/analytics/ads dependencies.
- All strings via resources, en + fa from Task 2 onward.
- Intent API stays baseline-compatible (`START/STOP/UPDATE`, `extra-simulator-config`, string lat/lng extras).
