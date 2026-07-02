package com.audiopro.djmrec.usb

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Best-effort rooted USB-C role helper for devices whose OEM UI leaves the port in device mode.
 *
 * Covers three different kernel USB role-switch APIs seen across Android devices, since the
 * exact sysfs path is chipset/kernel-version specific and there is no single portable one:
 *  - `/sys/class/typec/` port folders (newer Type-C port-manager class; `data_role`/`power_role` files)
 *  - `/sys/class/dual_role_usb/` (older Qualcomm dual-role class; single `mode` file)
 *  - `/sys/class/usb_role/` (generic USB role-switch class; single `role` file)
 * All writes are best-effort and silently skipped if the path doesn't exist on this kernel.
 */
object RootUsbHostController {

    data class CommandResult(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean
    )

    data class AlsaCaptureDevice(
        val card: Int,
        val device: Int,
        val path: String,
        val description: String
    )

    private const val ROLE_SWITCH_SCRIPT = """
        for f in /sys/class/typec/*/data_role /sys/class/typec/*/port*/data_role; do
          if [ -e "${'$'}f" ]; then
            echo "before ${'$'}f=$(cat "${'$'}f" 2>/dev/null)"
            echo host > "${'$'}f" 2>/dev/null || echo "write failed ${'$'}f"
            echo "after ${'$'}f=$(cat "${'$'}f" 2>/dev/null)"
          fi
        done
        for f in /sys/class/typec/*/power_role /sys/class/typec/*/port*/power_role; do
          if [ -e "${'$'}f" ]; then
            echo "before ${'$'}f=$(cat "${'$'}f" 2>/dev/null)"
            echo source > "${'$'}f" 2>/dev/null || echo "write failed ${'$'}f"
            echo "after ${'$'}f=$(cat "${'$'}f" 2>/dev/null)"
          fi
        done
        for f in /sys/class/dual_role_usb/*/mode; do
          if [ -e "${'$'}f" ]; then
            echo "before ${'$'}f=$(cat "${'$'}f" 2>/dev/null)"
            echo host > "${'$'}f" 2>/dev/null || echo "write failed ${'$'}f"
            echo "after ${'$'}f=$(cat "${'$'}f" 2>/dev/null)"
          fi
        done
        for f in /sys/class/usb_role/*/role; do
          if [ -e "${'$'}f" ]; then
            echo "before ${'$'}f=$(cat "${'$'}f" 2>/dev/null)"
            echo host > "${'$'}f" 2>/dev/null || echo "write failed ${'$'}f"
            echo "after ${'$'}f=$(cat "${'$'}f" 2>/dev/null)"
          fi
        done
    """

    fun isRootAvailable(): Boolean {
        val result = runSu("id", timeoutSeconds = 3)
        return result.exitCode == 0 && result.output.contains("uid=0")
    }

    fun tryForceHostMode(): CommandResult {
        return runSu(
            command = """
                echo 'djmrec root usb assist: start'
                id
                echo 'sys.usb.config='$(getprop sys.usb.config 2>/dev/null)
                echo 'sys.usb.state='$(getprop sys.usb.state 2>/dev/null)
                $ROLE_SWITCH_SCRIPT
                echo 'dev bus usb:'
                ls -l /dev/bus/usb 2>/dev/null
                find /dev/bus/usb -maxdepth 2 -type c -print 2>/dev/null
                echo 'djmrec root usb assist: end'
            """.trimIndent(),
            timeoutSeconds = 8
        )
    }

    fun collectRootStatus(): CommandResult {
        return runSu(
            command = """
                id
                echo 'sys.usb.config='$(getprop sys.usb.config 2>/dev/null)
                echo 'sys.usb.state='$(getprop sys.usb.state 2>/dev/null)
                echo 'proc asound cards:'
                cat /proc/asound/cards 2>/dev/null
                echo 'proc asound pcm:'
                cat /proc/asound/pcm 2>/dev/null
                echo 'dev snd:'
                ls -l /dev/snd 2>/dev/null
                echo 'role switch files (read-only snapshot):'
                for f in /sys/class/typec/*/data_role /sys/class/typec/*/power_role \
                         /sys/class/typec/*/port*/data_role /sys/class/typec/*/port*/power_role \
                         /sys/class/dual_role_usb/*/mode /sys/class/usb_role/*/role; do
                  if [ -e "${'$'}f" ]; then echo "${'$'}f=$(cat "${'$'}f" 2>/dev/null)"; fi
                done
                echo 'dev bus usb:'
                ls -l /dev/bus/usb 2>/dev/null
                find /dev/bus/usb -maxdepth 2 -type c -print 2>/dev/null
            """.trimIndent(),
            timeoutSeconds = 6
        )
    }

    fun prepareAlsaCaptureAccess(): CommandResult {
        return runSu(
            command = """
                echo 'djmrec root ALSA capture prepare: start'
                id
                echo 'selinux before='$(getenforce 2>/dev/null || echo unavailable)
                setenforce 0 2>/dev/null || true
                echo 'selinux after='$(getenforce 2>/dev/null || echo unavailable)
                echo 'proc asound cards:'
                cat /proc/asound/cards 2>/dev/null
                echo 'proc asound pcm:'
                cat /proc/asound/pcm 2>/dev/null
                echo 'before /dev/snd:'
                ls -l /dev/snd 2>/dev/null
                chmod 666 /dev/snd/controlC* /dev/snd/pcmC*D*c 2>/dev/null || true
                echo 'after /dev/snd:'
                ls -l /dev/snd 2>/dev/null
                echo 'djmrec root ALSA capture prepare: end'
            """.trimIndent(),
            timeoutSeconds = 8
        )
    }

    fun findAlsaCaptureDevices(rootOutput: String? = null): List<AlsaCaptureDevice> {
        val rootDevices = parseAlsaCaptureDevices(rootOutput.orEmpty())
        if (rootDevices.isNotEmpty()) return rootDevices.sortedByPriority()

        val procPcm = File("/proc/asound/pcm")
        val devices = mutableListOf<AlsaCaptureDevice>()
        if (procPcm.canRead()) {
            val regex = Regex("""^\s*(\d+)-(\d+):\s*(.*\bcapture\b.*)$""", RegexOption.IGNORE_CASE)
            procPcm.readLines().forEach { line ->
                val match = regex.find(line) ?: return@forEach
                val card = match.groupValues[1].toIntOrNull() ?: return@forEach
                val device = match.groupValues[2].toIntOrNull() ?: return@forEach
                val path = "/dev/snd/pcmC${card}D${device}c"
                if (File(path).exists()) {
                    devices += AlsaCaptureDevice(card, device, path, line.trim())
                }
            }
        }

        if (devices.isEmpty()) {
            File("/dev/snd").listFiles()?.forEach { file ->
                val match = Regex("""pcmC(\d+)D(\d+)c""").matchEntire(file.name) ?: return@forEach
                val card = match.groupValues[1].toIntOrNull() ?: return@forEach
                val device = match.groupValues[2].toIntOrNull() ?: return@forEach
                devices += AlsaCaptureDevice(card, device, file.absolutePath, file.name)
            }
        }

        return devices.sortedByPriority()
    }

    private fun parseAlsaCaptureDevices(text: String): List<AlsaCaptureDevice> {
        if (text.isBlank()) return emptyList()
        val regex = Regex("""^\s*(\d+)-(\d+):\s*(.*\bcapture\b.*)$""", RegexOption.IGNORE_CASE)
        return text.lineSequence().mapNotNull { line ->
            val match = regex.find(line) ?: return@mapNotNull null
            val card = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val device = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            AlsaCaptureDevice(
                card = card,
                device = device,
                path = "/dev/snd/pcmC${card}D${device}c",
                description = line.trim()
            )
        }.toList()
    }

    private fun List<AlsaCaptureDevice>.sortedByPriority(): List<AlsaCaptureDevice> {
        return sortedWith(compareByDescending<AlsaCaptureDevice> { it.priority }
            .thenBy { it.card }
            .thenBy { it.device })
    }

    private val AlsaCaptureDevice.priority: Int
        get() = when {
            description.contains("USB_AUDIO-TX", ignoreCase = true) -> 100
            description.contains("usb", ignoreCase = true) && description.contains("tx", ignoreCase = true) -> 90
            description.contains("djm", ignoreCase = true) -> 80
            description.contains("a9", ignoreCase = true) -> 70
            description.contains("usb", ignoreCase = true) -> 60
            else -> 0
        }

    /**
     * Reads the KERNEL's own view of USB attach events via `dmesg`, independent of whatever
     * Android's UsbManager/framework layer decides to expose to apps. This is the key diagnostic
     * to tell apart two very different failures:
     *  - kernel shows "new high-speed USB device" + descriptor reads succeeding -> the hardware
     *    negotiation worked and Android's framework is the one hiding the device from apps.
     *  - kernel shows errors like "device descriptor read/64, error -71" (or nothing at all when
     *    the mixer is plugged in) -> the phone and mixer are failing to negotiate power/data at
     *    the hardware/electrical level; no app-level code (root or not) can fix that.
     */
    fun captureKernelUsbLog(): CommandResult {
        return runSu(
            command = """
                dmesg 2>/dev/null | grep -iE 'usb|typec|dwc3|xhci' | tail -n 150 ||
                    echo '(dmesg unavailable or empty - some kernels restrict it even to root via dmesg_restrict)'
            """.trimIndent(),
            timeoutSeconds = 6
        )
    }

    private fun runSu(command: String, timeoutSeconds: Long): CommandResult {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                CommandResult(-1, "su command timed out", timedOut = true)
            } else {
                val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                CommandResult(process.exitValue(), output, timedOut = false)
            }
        } catch (e: Exception) {
            CommandResult(-1, e.message ?: e::class.java.simpleName, timedOut = false)
        }
    }
}