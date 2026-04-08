package com.droidrun.portal.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun newerMajor_returnsTrue() {
        assertTrue(UpdateChecker.isNewerVersion("2.0.0", "1.9.9"))
    }

    @Test
    fun newerMinor_returnsTrue() {
        assertTrue(UpdateChecker.isNewerVersion("0.7.0", "0.6.1"))
    }

    @Test
    fun newerPatch_returnsTrue() {
        assertTrue(UpdateChecker.isNewerVersion("0.6.2", "0.6.1"))
    }

    @Test
    fun sameVersion_isNotNewer() {
        assertFalse(UpdateChecker.isNewerVersion("0.6.1", "0.6.1"))
    }

    @Test
    fun olderVersion_returnsFalse() {
        assertFalse(UpdateChecker.isNewerVersion("0.5.0", "0.6.1"))
    }

    @Test
    fun olderPatch_returnsFalse() {
        assertFalse(UpdateChecker.isNewerVersion("0.6.0", "0.6.1"))
    }

    @Test
    fun latestHasMoreSegments_handledCorrectly() {
        // "1.0.0.1" > "1.0.0"
        assertTrue(UpdateChecker.isNewerVersion("1.0.0.1", "1.0.0"))
    }

    @Test
    fun shorterLatest_isNotNewerThanLongerCurrent() {
        // "1.0.0" is NOT newer than "1.0.0.1" — the extra segment makes current newer
        assertFalse(UpdateChecker.isNewerVersion("1.0.0", "1.0.0.1"))
    }

    @Test
    fun nonNumericSegment_treatedAsZero() {
        // "1.0.beta" → [1, 0, 0], "1.0.0" → [1, 0, 0] → equal → not newer
        assertFalse(UpdateChecker.isNewerVersion("1.0.beta", "1.0.0"))
    }

    @Test
    fun emptyLatest_returnsFalse() {
        assertFalse(UpdateChecker.isNewerVersion("", "1.0.0"))
    }

    @Test
    fun emptyCurrent_treatedAsZeroSoAnyVersionIsNewer() {
        // Empty string splits to [""], toIntOrNull returns null → treated as [0]
        // So "1.0.0" > "0" → true (any valid version is newer than unknown)
        assertTrue(UpdateChecker.isNewerVersion("1.0.0", ""))
    }

    @Test
    fun emptyBoth_neitherIsNewer() {
        // Both empty → both treated as [0] → equal → not newer
        assertFalse(UpdateChecker.isNewerVersion("", ""))
    }
}
