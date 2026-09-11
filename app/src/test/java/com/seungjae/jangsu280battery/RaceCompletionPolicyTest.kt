package com.seungjae.jangsu280battery

import org.junit.Assert.assertEquals
import org.junit.Test

class RaceCompletionPolicyTest {
    @Test fun startFinishAlwaysValidRegardlessOfOldStatus() {
        assertEquals("VALID", normalizeRaceCompletionStatus("REVIEW", 1000, 5000, 4000))
        assertEquals("VALID", normalizeRaceCompletionStatus("INVALID", 1000, 5000, 4000))
    }

    @Test fun explicitDnfAlwaysStaysDnf() {
        assertEquals("DNF", normalizeRaceCompletionStatus("DNF", 1000, 5000, 4000))
    }

    @Test fun legacyCompletedElapsedWithoutRawStartCanBeValid() {
        assertEquals("VALID", normalizeRaceCompletionStatus("INVALID", 0, 5000, 4000))
    }

    @Test fun startedWithoutFinishIsDnf() {
        assertEquals("DNF", normalizeRaceCompletionStatus("INVALID", 1000, 0, 2000))
    }
}
