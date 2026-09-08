package com.audiopro.djmrec.ui.components

/** Display-clock pacing with rounding tolerance; high-refresh screens get at most ~60 draws/s. */
internal class WaveformFrameLimiter {
    private var nextFrame = Long.MIN_VALUE
    fun shouldRender(nanos: Long): Boolean {
        val period = 16_666_667L
        if (nextFrame != Long.MIN_VALUE && nanos + 500_000 < nextFrame) return false
        nextFrame = if (nextFrame == Long.MIN_VALUE || nanos - nextFrame > period) nanos + period
            else nextFrame + period
        return true
    }
}
