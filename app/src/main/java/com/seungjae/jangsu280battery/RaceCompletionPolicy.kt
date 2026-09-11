package com.seungjae.jangsu280battery

/**
 * Completion validity is intentionally simple:
 * - An explicit manual DNF stays DNF.
 * - Otherwise a measured START followed by a measured FINISH is a valid lap.
 * - CP passage and GPS quality are diagnostics only and never invalidate a finished lap.
 * - A record without a complete START -> FINISH pair is treated as DNF/not finished.
 */
internal fun normalizeRaceCompletionStatus(
    currentStatus: String,
    startedAtMs: Long,
    finishedAtMs: Long
): String {
    if (currentStatus.equals("DNF", ignoreCase = true)) return "DNF"
    return if (startedAtMs > 0L && finishedAtMs > startedAtMs) "VALID" else "DNF"
}
