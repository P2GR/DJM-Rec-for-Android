package com.audiopro.djmrec.audio

enum class RecordingHealthLevel {
    READY,
    GOOD,
    SILENCE,
    USB_UNSTABLE,
    LOW_STORAGE,
    ERROR
}

data class RecordingHealth(
    val level: RecordingHealthLevel,
    val message: String,
    val freeBytes: Long = 0,
    val remainingSeconds: Long = 0
) {
    companion object {
        val Ready = RecordingHealth(RecordingHealthLevel.READY, "Waiting for recording")
    }
}

data class RecordingHealthInput(
    val recording: Boolean,
    val usbIso: Boolean,
    val streamOpen: Boolean,
    val freeBytes: Long,
    val remainingSeconds: Long,
    val packetDelta: Long,
    val byteDelta: Long,
    val nonZeroByteDelta: Long,
    val missedPacketDelta: Long,
    val resubmitFailures: Long,
    val xRuns: Int,
    val writerErrorCode: Int,
    val minimumFreeBytes: Long = 64L * 1024 * 1024
)

object RecordingHealthEvaluator {
    fun evaluate(input: RecordingHealthInput): RecordingHealth {
        if (!input.streamOpen) {
            return RecordingHealth(
                RecordingHealthLevel.ERROR,
                "Audio stream closed unexpectedly",
                input.freeBytes,
                input.remainingSeconds
            )
        }
        if (input.writerErrorCode != 0) {
            return RecordingHealth(
                RecordingHealthLevel.ERROR,
                "Storage writer failed (code ${input.writerErrorCode})",
                input.freeBytes,
                input.remainingSeconds
            )
        }
        if (input.recording &&
            (input.freeBytes in 0..input.minimumFreeBytes || input.remainingSeconds in 0..59)) {
            return RecordingHealth(
                RecordingHealthLevel.LOW_STORAGE,
                "Less than one minute of storage remains",
                input.freeBytes,
                input.remainingSeconds
            )
        }
        if (input.usbIso && input.packetDelta <= 0) {
            return RecordingHealth(
                RecordingHealthLevel.USB_UNSTABLE,
                "USB audio packets stopped",
                input.freeBytes,
                input.remainingSeconds
            )
        }
        if (input.usbIso && (input.missedPacketDelta > 0 || input.resubmitFailures > 0)) {
            return RecordingHealth(
                RecordingHealthLevel.USB_UNSTABLE,
                "USB packet loss detected",
                input.freeBytes,
                input.remainingSeconds
            )
        }
        if (input.xRuns > 0) {
            return RecordingHealth(
                RecordingHealthLevel.USB_UNSTABLE,
                "Audio buffer overrun detected",
                input.freeBytes,
                input.remainingSeconds
            )
        }
        if (input.usbIso && input.byteDelta > 0 && input.nonZeroByteDelta == 0L) {
            return RecordingHealth(
                RecordingHealthLevel.SILENCE,
                "USB connected, but signal is digital silence",
                input.freeBytes,
                input.remainingSeconds
            )
        }
        return RecordingHealth(
            RecordingHealthLevel.GOOD,
            if (input.recording) "Recording healthy" else "USB signal ready",
            input.freeBytes,
            input.remainingSeconds
        )
    }
}
