package com.audiopro.djmrec.audio

import kotlin.test.Test
import kotlin.test.assertEquals

class RecordingHealthEvaluatorTest {
    private fun input(
        recording: Boolean = true,
        streamOpen: Boolean = true,
        freeBytes: Long = 1024L * 1024 * 1024,
        remainingSeconds: Long = 3600,
        packetDelta: Long = 100,
        byteDelta: Long = 1000,
        nonZeroByteDelta: Long = 500,
        missedPacketDelta: Long = 0,
        writerErrorCode: Int = 0,
        xRuns: Int = 0
    ) = RecordingHealthInput(
        recording = recording,
        usbIso = true,
        streamOpen = streamOpen,
        freeBytes = freeBytes,
        remainingSeconds = remainingSeconds,
        packetDelta = packetDelta,
        byteDelta = byteDelta,
        nonZeroByteDelta = nonZeroByteDelta,
        missedPacketDelta = missedPacketDelta,
        resubmitFailures = 0,
        xRuns = xRuns,
        writerErrorCode = writerErrorCode
    )

    @Test
    fun `nonzero USB noise with silent selected channels is not ready`() {
        assertEquals(RecordingHealthLevel.SILENCE,
            RecordingHealthEvaluator.evaluate(input(recording = false).copy(selectedPeakDb = -60f)).level)
        assertEquals(RecordingHealthLevel.GOOD,
            RecordingHealthEvaluator.evaluate(input(recording = false).copy(selectedPeakDb = -20f)).level)
    }

    @Test
    fun `healthy stream reports good`() {
        assertEquals(RecordingHealthLevel.GOOD, RecordingHealthEvaluator.evaluate(input()).level)
    }

    @Test
    fun `digital silence is distinguished from disconnect`() {
        val health = RecordingHealthEvaluator.evaluate(input(nonZeroByteDelta = 0))
        assertEquals(RecordingHealthLevel.SILENCE, health.level)
    }

    @Test
    fun `stalled packets report unstable USB`() {
        val health = RecordingHealthEvaluator.evaluate(input(packetDelta = 0, byteDelta = 0))
        assertEquals(RecordingHealthLevel.USB_UNSTABLE, health.level)
    }

    @Test
    fun `critical free space stops before disk exhaustion`() {
        val health = RecordingHealthEvaluator.evaluate(input(freeBytes = 32L * 1024 * 1024))
        assertEquals(RecordingHealthLevel.LOW_STORAGE, health.level)
    }

    @Test
    fun `writer error outranks other health signals`() {
        val health = RecordingHealthEvaluator.evaluate(input(writerErrorCode = 1, packetDelta = 0))
        assertEquals(RecordingHealthLevel.ERROR, health.level)
    }

    @Test
    fun `new buffer overrun reports unstable audio`() {
        val health = RecordingHealthEvaluator.evaluate(input(xRuns = 1))
        assertEquals(RecordingHealthLevel.USB_UNSTABLE, health.level)
    }

    @Test
    fun `low battery warns only when not charging`() {
        assertEquals(RecordingHealthLevel.LOW_BATTERY,
            RecordingHealthEvaluator.evaluate(input().copy(batteryPercent = 12, charging = false)).level)
        assertEquals(RecordingHealthLevel.GOOD,
            RecordingHealthEvaluator.evaluate(input().copy(batteryPercent = 12, charging = true)).level)
        assertEquals(RecordingHealthLevel.GOOD,
            RecordingHealthEvaluator.evaluate(input().copy(batteryPercent = 40, charging = false)).level)
    }

    @Test
    fun `critical battery outranks usb warnings but not storage`() {
        assertEquals(RecordingHealthLevel.LOW_BATTERY,
            RecordingHealthEvaluator.evaluate(input(missedPacketDelta = 3).copy(batteryPercent = 4, charging = false)).level)
        assertEquals(RecordingHealthLevel.USB_UNSTABLE,
            RecordingHealthEvaluator.evaluate(input(missedPacketDelta = 3).copy(batteryPercent = 12, charging = false)).level)
        assertEquals(RecordingHealthLevel.LOW_STORAGE,
            RecordingHealthEvaluator.evaluate(input(remainingSeconds = 30).copy(batteryPercent = 4, charging = false)).level)
    }

    @Test
    fun `severe thermal status warns about overheating`() {
        assertEquals(RecordingHealthLevel.OVERHEATING,
            RecordingHealthEvaluator.evaluate(input().copy(thermalStatus = 3)).level)
        assertEquals(RecordingHealthLevel.GOOD,
            RecordingHealthEvaluator.evaluate(input().copy(thermalStatus = 2)).level)
    }
}
