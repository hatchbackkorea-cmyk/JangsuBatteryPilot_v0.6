package com.seungjae.jangsu280battery

import org.junit.Assert.assertEquals
import org.junit.Test

class RaceTimeDisplayTest {
    @Test fun roundsToNearestTenth() {
        assertEquals("0.0", formatRaceTime(0L))
        assertEquals("1.1", formatRaceTime(1_149L))
        assertEquals("1.2", formatRaceTime(1_150L))
        assertEquals("59.9", formatRaceTime(59_949L))
        assertEquals("1:00.0", formatRaceTime(59_950L))
        assertEquals("1:25.1", formatRaceTime(85_149L))
        assertEquals("1:25.2", formatRaceTime(85_150L))
    }

    @Test fun canonicalRoundedMillisecondsAreHalfUpTo100ms() {
        assertEquals(1_100L, roundRaceTimeMs(1_149L))
        assertEquals(1_200L, roundRaceTimeMs(1_150L))
        assertEquals(60_000L, roundRaceTimeMs(59_950L))
        assertEquals(85_200L, roundRaceTimeMs(85_199L))
    }
}
