package com.audiopro.djmrec.diagnostics

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.audiopro.djmrec.BuildConfig
import com.audiopro.djmrec.audio.AudioEngine
import com.audiopro.djmrec.usb.RootUsbHostController
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a self-contained diagnostic report (device info, USB host capability, currently
 * enumerated USB devices, AudioManager routing state, battery/charging state, and this
 * process's own logcat) and shares it via the system share sheet.
 *
 * No special permissions are required: apps have always been able to read their own process's
 * logcat buffer without READ_LOGS (that restriction only applies to reading *other* apps' logs).
 */
object LogExporter {

    private const val TAG = "LogExporter"
    private const val PREFS_NAME = "settings"
    private const val KEY_ROOT_USB_MODE = "root_usb_mode"
    private const val KEY_USB_CHANNEL_OFFSET = "usb_channel_offset"
    private const val KEY_FORCE_ANDROID_CAPTURE = "force_android_capture"

    /** Runs on whatever thread it's called from — callers should invoke off the main thread. */
    fun collectDiagnosticReport(context: Context): String {
        val sb = StringBuilder()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        sb.appendLine("djmrec diagnostic report")
        sb.appendLine("generated: $timestamp")
        sb.appendLine("app version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("build type: ${BuildConfig.BUILD_TYPE} debug=${BuildConfig.DEBUG}")
        sb.appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
        sb.appendLine("hardware: ${Build.HARDWARE} board=${Build.BOARD} supportedAbis=${Build.SUPPORTED_ABIS.toList()}")
        sb.appendLine()

        val nativeSummary = AudioEngine.getDiagnosticSummary()
        appendUsbSection(context, sb)
        UsbDiagnosticsCollector.append(context, sb, nativeSummary)
        appendAudioSection(context, sb)
        appendPowerSection(context, sb)
        appendRootSection(context, sb)
        appendUsbCaptureSettingsSection(context, sb)
        appendUsbTransferStatsSection(sb, nativeSummary)
        sb.appendLine("=== Native audio pipeline snapshot ===")
        sb.appendLine(nativeSummary)
        sb.appendLine()

        sb.appendLine("=== logcat (this app's process only, most recent first not guaranteed) ===")
        sb.append(readOwnLogcat())

        return sb.toString()
    }

    private fun appendUsbSection(context: Context, sb: StringBuilder) {
        val pm = context.packageManager
        val hasUsbHost = pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
        sb.appendLine("=== USB host capability ===")
        sb.appendLine("FEATURE_USB_HOST supported: $hasUsbHost")
        if (!hasUsbHost) {
            sb.appendLine(
                "WARNING: this device does not report USB host support at all. It cannot act " +
                    "as a USB host regardless of cable/adapter, so a UAC2 mixer can never be " +
                    "recognized as an audio input on this hardware."
            )
        }
        sb.appendLine()

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = usbManager.deviceList.values.toList()
        sb.appendLine("=== USB devices currently enumerated by the host (${devices.size}) ===")
        if (devices.isEmpty()) {
            sb.appendLine(
                "No USB devices enumerated at all. If the mixer is physically plugged in via " +
                    "USB-C right now and this list is still empty, the phone is very likely NOT " +
                    "entering USB host/OTG data mode -- this is almost always a cable/adapter " +
                    "problem (a charge-only USB-C cable or a cheap OTG adapter that doesn't pull " +
                    "the CC line correctly), not something this app can fix in software. Try a " +
                    "cable/adapter explicitly labelled 'USB-C OTG' or 'USB 3.0 OTG'."
            )
        } else {
            devices.forEach { d ->
                val interfaces = (0 until d.interfaceCount).joinToString(prefix = "[", postfix = "]") { i ->
                    val intf = d.getInterface(i)
                    "if${intf.id}/alt${intf.alternateSetting}(class=${intf.interfaceClass},sub=${intf.interfaceSubclass})"
                }
                sb.appendLine(
                    "  ${d.deviceName} vid=${d.vendorId} pid=${d.productId} name=${d.productName} " +
                        "hasPermission=${usbManager.hasPermission(d)} interfaces=$interfaces"
                )
            }
        }
        sb.appendLine()
    }

    private fun appendAudioSection(context: Context, sb: StringBuilder) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        sb.appendLine("=== AudioManager input devices (${inputs.size}) ===")
        inputs.forEach { info: AudioDeviceInfo ->
            appendAudioDevice(sb, info)
        }
        sb.appendLine()

        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        sb.appendLine("=== AudioManager output devices (${outputs.size}) ===")
        outputs.forEach { info: AudioDeviceInfo -> appendAudioDevice(sb, info) }
        sb.appendLine()
    }

    private fun appendAudioDevice(sb: StringBuilder, info: AudioDeviceInfo) {
        sb.appendLine(
            "  id=${info.id} type=${info.type} address=${info.address} product=${info.productName} " +
                "source=${info.isSource} sink=${info.isSink} sampleRates=${info.sampleRates.toList()} " +
                "channelCounts=${info.channelCounts.toList()} " +
                "channelIndexMasks=${info.channelIndexMasks.toList()} " +
                "channelMasks=${info.channelMasks.toList()} encodings=${info.encodings.toList()}"
        )
    }

    private fun appendPowerSection(context: Context, sb: StringBuilder) {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        sb.appendLine("=== Power state ===")
        if (bm != null) {
            val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            sb.appendLine("phone is charging: $isCharging")
            if (isCharging) {
                sb.appendLine(
                    "NOTE: if the mixer is connected and the phone shows only a charging icon " +
                        "with no USB devices listed above, the port negotiated a power-only / " +
                        "charging data role instead of USB host role -- see the USB section above."
                )
            }
        } else {
            sb.appendLine("BatteryManager unavailable")
        }
        sb.appendLine()
    }

    private fun appendRootSection(context: Context, sb: StringBuilder) {
        val rootModeEnabled = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ROOT_USB_MODE, false)
        sb.appendLine("=== Root USB assist ===")
        sb.appendLine("enabled in app settings: $rootModeEnabled")
        if (rootModeEnabled) {
            val status = RootUsbHostController.collectRootStatus()
            sb.appendLine("su exit=${status.exitCode} timedOut=${status.timedOut}")
            sb.appendLine(status.output)

            val alsaCandidates = RootUsbHostController.findAlsaCaptureDevices(status.output)
            sb.appendLine("root ALSA capture candidates (${alsaCandidates.size}):")
            if (alsaCandidates.isEmpty()) {
                sb.appendLine("  none")
            } else {
                alsaCandidates.forEach { candidate ->
                    sb.appendLine("  hw:${candidate.card},${candidate.device} ${candidate.path} ${candidate.description}")
                }
            }

            val kernelUsbScan = RootUsbHostController.scanKernelUsbDevices()
            sb.appendLine("--- kernel-level USB device scan (bypasses Android UsbManager) ---")
            sb.appendLine("scan exit=${kernelUsbScan.exitCode} timedOut=${kernelUsbScan.timedOut}")
            sb.appendLine(kernelUsbScan.output)

            sb.appendLine("--- kernel dmesg (usb/typec/dwc3/xhci lines, last 150) ---")
            sb.appendLine(
                "This is the KERNEL's own view of USB attach events, independent of what " +
                    "Android's UsbManager reports above. If you see 'new high-speed USB device' " +
                    "and descriptor reads succeeding for the mixer here, the hardware negotiation " +
                    "worked and Android's framework is the one hiding it from apps -- a very " +
                    "different (and more fixable) problem than the kernel never seeing it at all."
            )
            val kernelLog = RootUsbHostController.captureKernelUsbLog()
            sb.appendLine("dmesg exit=${kernelLog.exitCode} timedOut=${kernelLog.timedOut}")
            sb.appendLine(kernelLog.output)
        } else {
            sb.appendLine("disabled; enable it from the settings button if this rooted phone needs host-role forcing")
        }
        sb.appendLine()
    }

    private fun appendUsbCaptureSettingsSection(context: Context, sb: StringBuilder) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val offset = prefs.getInt(KEY_USB_CHANNEL_OFFSET, -1)
        val forceAndroidCapture = prefs.getBoolean(KEY_FORCE_ANDROID_CAPTURE, false)
        val rootModeEnabled = prefs.getBoolean(KEY_ROOT_USB_MODE, false)
        sb.appendLine("=== USB capture settings ===")
        sb.appendLine(
            "capture path: " + when {
                rootModeEnabled -> "Root ALSA /dev/snd"
                forceAndroidCapture -> "Android audio stack"
                else -> "Raw libusb isochronous"
            }
        )
        sb.appendLine(
            "stereo pair: " + if (offset < 0) "Auto" else "USB channels ${offset + 1}-${offset + 2}"
        )
        sb.appendLine()
    }

    private fun appendUsbTransferStatsSection(sb: StringBuilder, nativeSummary: String) {
        val stats = AudioEngine.getUsbIsoTransferStats()
        sb.appendLine("=== Raw USB transfer stats ===")
        if (stats.size >= 7) {
            sb.appendLine(
                "completed=${stats[0]} missed=${stats[1]} empty=${stats[2]} " +
                    "partial=${stats[3]} bytes=${stats[4]} nonZeroBytes=${stats[5]} " +
                    "resubmitFailures=${stats[6]}"
            )
            val active = nativeSummary.lineSequence().any { it == "source_mode=usb_iso" } &&
                nativeSummary.lineSequence().any { it == "stream_open=true" }
            val health = when {
                !active -> "INFO - raw USB capture not active"
                stats[0] == 0L || stats[4] == 0L -> "FAIL - endpoint delivers no packets/audio bytes"
                stats[5] == 0L -> "WARN - packets arrive but payload is digital silence"
                stats[6] > 0L -> "WARN - isochronous transfer resubmission failed"
                stats[1] > 0L -> "WARN - one or more isochronous packets were missed"
                else -> "PASS - packets and non-zero audio payload are arriving"
            }
            sb.appendLine("health: $health")
        } else {
            sb.appendLine("unavailable")
        }
        sb.appendLine()
    }

    private fun readOwnLogcat(): String {
        return try {
            val process = ProcessBuilder("logcat", "-d", "-v", "threadtime")
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            process.waitFor()
            if (output.isBlank()) "(empty logcat buffer)" else output
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read logcat", e)
            "(failed to read logcat: ${e.message})"
        }
    }

    /** Writes the report to a timestamped file under the app's external files dir (falls back to cache). */
    fun writeReportToFile(context: Context, report: String): File {
        val dir = (context.getExternalFilesDir("logs") ?: File(context.cacheDir, "logs")).apply { mkdirs() }
        val filename = "djmrec-diagnostics-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
        val file = File(dir, filename)
        file.writeText(report)
        return file
    }

    /** Opens the system share sheet for the given report file. Must be called with an Activity/UI [Context]. */
    fun shareReport(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "djmrec diagnostic log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share diagnostic log"))
    }
}
