package com.seungjae.jangsu280battery

/**
 * TimeGate phone display convention:
 * - Lap session key = event code + local course + local calendar day, with the first activation time persisted.
 * - Leaving/re-entering the same race room on the same day does not reset lap numbering/history.
 * - BEST: fastest completed usable lap in the current persistent session, showing lap number + FINISH time.
 * - PREVIOUS: immediately previous completed lap in the session, showing lap number + FINISH time.
 * - CURRENT: current session lap number + live elapsed time.
 * - DELTA: continuous current-position comparison against the immediately previous usable lap.
 * - Live screen stays limited to BEST / PREVIOUS / CURRENT; full history belongs on the lap-history page.
 * - Lap-history sector table marks each sector's fastest value blue and slowest value red.
 * - THEORETICAL BEST is the sum of the fastest recorded sector times in the current session.
 * Product sign convention is intentionally +/blue when faster and −/red when slower.
 */
object TimeGateLapSemantics
