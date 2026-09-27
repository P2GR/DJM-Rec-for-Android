package com.audiopro.djmrec.audio

enum class RecordingHealthLevel {
    READY,
    GOOD,
    SILENCE,
    USB_UNSTABLE,
    LOW_STORAGE,
    LOW_BATTERY,
    OVERHEATING,
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
    val selectedPeakDb: Float? = null,
    val minimumFreeBytes: Long = 64L * 1024 * 1024,
    /** Battery charge 0-100, or null when unknown. */
    val batteryPercent: Int? = null,
    val charging: Boolean = true,
    /** android.os.PowerManager THERMAL_STATUS_* value (0 = none). */
    val thermalStatus: Int = 0
)

/** Battery and heat thresholds shared by the health check and the video quality guard. */
object DevicePowerPolicy {
    const val LOW_BATTERY_PERCENT = 15
    const val CRITICAL_BATTERY_PERCENT = 5
    /** PowerManager.THERMAL_STATUS_SEVERE: Android starts throttling hard from here. */
    const val THERMAL_SEVERE = 3

    fun batteryLow(percent: Int?, charging: Boolean): Boolean =
        percent != null && !charging && percent <= LOW_BATTERY_PERCENT

    fun batteryCritical(percent: Int?, charging: Boolean): Boolean =
        percent != null && !charging && percent <= CRITICAL_BATTERY_PERCENT

    fun overheating(thermalStatus: Int): Boolean = thermalStatus >= THERMAL_SEVERE
}

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
        if (DevicePowerPolicy.batteryCritical(input.batteryPercent, input.charging)) {
            return batteryWarning(input)
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
        if (DevicePowerPolicy.overheating(input.thermalStatus)) {
            return RecordingHealth(
                RecordingHealthLevel.OVERHEATING,
                "Phone is overheating: video frame rate and bitrate reduced. Shade or cool the phone",
                input.freeBytes,
                input.remainingSeconds
            )
        }
        if (DevicePowerPolicy.batteryLow(input.batteryPercent, input.charging)) {
            return batteryWarning(input)
        }
        if (input.usbIso && input.byteDelta > 0 && (input.nonZeroByteDelta == 0L || input.selectedPeakDb?.let { it <= -60f } == true)) {
            return RecordingHealth(
                RecordingHealthLevel.SILENCE,
                "USB connected; no audible signal on the selected channels",
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

    private fun batteryWarning(input: RecordingHealthInput) = RecordingHealth(
        RecordingHealthLevel.LOW_BATTERY,
        "Battery ${input.batteryPercent}% and not charging: connect power",
        input.freeBytes,
        input.remainingSeconds
    )
}
