package com.audiopro.djmrec.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineRulerLabelTest {
    @Test
    fun `ruler labels use minutes and seconds within the first hour`() {
        assertEquals("0:00", rulerLabel(0))
        assertEquals("0:50", rulerLabel(50))
        assertEquals("59:50", rulerLabel(3_590))
    }

    @Test
    fun `ruler labels add hours for long sets`() {
        assertEquals("1:00:00", rulerLabel(3_600))
        assertEquals("1:24:10", rulerLabel(5_050))
        assertEquals("10:05:09", rulerLabel(36_309))
    }
}
