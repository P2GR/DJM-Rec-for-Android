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
}
