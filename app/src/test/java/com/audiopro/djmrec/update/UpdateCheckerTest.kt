package com.audiopro.djmrec.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateCheckerTest {
    @Test
    fun comparesSemanticVersions() {
        assertTrue(UpdateChecker.isNewer("0.35.0", "0.34.0"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.99.9"))
        assertFalse(UpdateChecker.isNewer("0.34", "0.34.0"))
        assertFalse(UpdateChecker.isNewer("0.33.9", "0.34.0"))
    }
}
