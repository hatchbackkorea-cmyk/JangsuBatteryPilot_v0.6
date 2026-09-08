package com.seungjae.jangsu280battery

/**
 * TimeGate phone display convention:
 * - BEST: today's fastest completed lap on the same local course, showing that lap number + FINISH time.
 * - PREVIOUS: the immediately previous completed lap, showing its lap number + FINISH time.
 * - CURRENT: today's current lap number + live elapsed time.
 * - DELTA: continuous current-position comparison against the immediately previous usable lap.
 * - Live screen stays limited to BEST / PREVIOUS / CURRENT; full history belongs on the lap-history page.
 * - Lap-history sector table marks each sector's fastest value blue and slowest value red.
 * - THEORETICAL BEST is the sum of the fastest recorded sector times for that course/day.
 * Product sign convention is intentionally +/blue when faster and −/red when slower.
 */
object TimeGateLapSemantics
