package app.ghostroute.coredata

/**
 * P1-2c: relative-time helper shared by map (IP chip) and spots (recents ages).
 * Ledger: extracted from MapViewModel.minutesAgo (its companion now delegates);
 * placed in :core-data because it is pure logic with no Android deps and both
 * feature modules already depend on this one.
 */
/** Whole minutes elapsed since [fromMs], floored at 0; [fromMs] <= 0 (UNKNOWN age, pre-P1.1 rows) -> null. */
fun minutesAgo(fromMs: Long, nowMs: Long): Long? =
    if (fromMs <= 0L) null
    else (nowMs - fromMs).coerceAtLeast(0L) / 60_000L
