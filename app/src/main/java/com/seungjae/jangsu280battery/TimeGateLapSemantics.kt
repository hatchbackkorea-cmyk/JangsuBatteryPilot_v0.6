package com.seungjae.jangsu280battery

/**
 * TimeGate display convention:
 * - BEST: fastest earlier lap on the same local courseId.
 * - PREVIOUS: immediately previous lap on the same local courseId.
 * - CURRENT: live elapsed time.
 * - DELTA: updated only at CP/FINISH and held until the next gate.
 * Product sign convention is intentionally +/blue when faster and −/red when slower.
 */
object TimeGateLapSemantics
