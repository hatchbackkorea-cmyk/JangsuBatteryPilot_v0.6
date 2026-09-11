package com.seungjae.jangsu280battery

import org.junit.Assert.assertEquals
import org.junit.Test

class RaceCompletionPolicyTest {
    @Test
    fun startAndFinishMakesLegacyReviewValid() {
        assertEquals("VALID", normalizeRaceCompletionStatus("REVIEW", 1_000L, 2_000L))
    }

    @Test
    fun startAndFinishMakesLegacyInvalidValid() {
        assertEquals("VALID", normalizeRaceCompletionStatus("INVALID", 1_000L, 2_000L))
    }

    @Test
    fun explicitDnfStaysDnfEvenIfFinishTimestampExists() {
        assertEquals("DNF", normalizeRaceCompletionStatus("DNF", 1_000L, 2_000L))
    }

    @Test
    fun missingFinishIsDnf() {
        assertEquals("DNF", normalizeRaceCompletionStatus("VALID", 1_000L, 0L))
    }

    @Test
    fun finishMustBeAfterStart() {
        assertEquals("DNF", normalizeRaceCompletionStatus("VALID", 2_000L, 1_000L))
    }
}
