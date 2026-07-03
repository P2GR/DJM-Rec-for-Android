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

    /**
     * Checks whether USB OTG / host mode appears enabled at the Android framework level.
     * Uses root to inspect sysprops and type-c role files.
     */
    fun checkOtgStatus(): OtgStatus {
        if (!isRootAvailable()) {
            return OtgStatus(
                enabled = true,
                details = "Root unavailable; cannot inspect OTG state",
                suggestions = emptyList()
            )
        }
        val result = runSu(
            command = """
                echo 'persist.sys.usb.config='$(getprop persist.sys.usb.config 2>/dev/null)
                echo 'sys.usb.config='$(getprop sys.usb.config 2>/dev/null)
                echo 'persist.vendor.usb.config='$(getprop persist.vendor.usb.config 2>/dev/null)
                echo 'sys.usb.state='$(getprop sys.usb.state 2>/dev/null)
                for f in /sys/class/typec/*/data_role /sys/class/typec/*/port*/data_role; do
                  if [ -e "${'$'}f" ]; then echo "typec_role=${'$'}f=${'$'}(cat ${'$'}f 2>/dev/null)"; fi
                done
            """.trimIndent(),
            timeoutSeconds = 5
        )
        val output = result.output
        val usbConfig = output.lineSequence()
            .firstNotNullOfOrNull { line ->
                if (line.startsWith("persist.sys.usb.config=")) line.substringAfter("=") else null
            }?.trim().orEmpty()
        val typecRole = output.lineSequence()
            .firstNotNullOfOrNull { line ->
                if (line.startsWith("typec_role=")) line.substringAfter("=") else null
            }?.trim().orEmpty()

        val isHostRole = typecRole.contains("[host]") || typecRole.contains("host")
        val configLooksOff = usbConfig.equals("none", ignoreCase = true) || usbConfig.isBlank()
        val otgLikelyOff = configLooksOff && !isHostRole
        val suggestions = mutableListOf<String>()
        if (configLooksOff) suggestions.add("USB default config is 'none' or empty")
        if (!isHostRole) suggestions.add("Type-C data role is not set to host")

        return OtgStatus(
            enabled = !otgLikelyOff,
            details = output,
            suggestions = suggestions
        )
    }

    data class OtgStatus(
        val enabled: Boolean,
        val details: String,
        val suggestions: List<String>
    )

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

    /** Returns the app's own Linux UID for use with [grantUsbDeviceAccess]. */
    fun getAppUid(): Int = android.os.Process.myUid()

    fun forcePersistentHostMode(): CommandResult {
        return runSu(
            command = """
                echo 'djmrec persistent host start'
                id
                getenforce 2>/dev/null || echo selinux-unavailable

                # -- iOS-like PHY / dwc3 tweaks -------------------------------------------
                # iPhones use USB 2.0 (High-Speed) for DJM REC; disable SuperSpeed so the
                # PHY doesn't waste time trying USB 3.0 negotiation that the DJM may reject.
                for ss in /sys/devices/platform/soc/*.ssusb/power/control \
                          /sys/devices/platform/soc/*.dwc3/power/control; do
                  if [ -e "${'$'}ss" ]; then
                    echo "power/control ${'$'}ss was $(cat ${'$'}ss 2>/dev/null)"
                    echo on > "${'$'}ss" 2>/dev/null || echo "   write failed"
                  fi
                done

                # Tell the DWC3 core to stay in host-only mode via its debugfs mode file.
                for d in /sys/kernel/debug/usb/*dwc3/mode \
                         /sys/kernel/debug/*.dwc3/mode; do
                  if [ -e "${'$'}d" ]; then
                    echo "dwc3 mode ${'$'}d was $(cat ${'$'}d 2>/dev/null)"
                    echo host > "${'$'}d" 2>/dev/null || echo "   write failed (may need debugfs mounted)"
                  fi
                done

                # Prevent the DWC3 core from suspending / entering low-power.
                for lpm in /sys/devices/platform/soc/*.dwc3/power/autosuspend_delay_ms; do
                  if [ -e "${'$'}lpm" ]; then
                    echo -1 > "${'$'}lpm" 2>/dev/null || true
                  fi
                done

                # -- Disable Android USB gadget framework --------------------------------
                # iOS has no "gadget" concept -- the phone is host-only from the start.
                # We kill every Android gadget path so the kernel can't flip to device mode.
                setprop sys.usb.config none 2>/dev/null || true
                setprop vendor.usb.config none 2>/dev/null || true
                setprop persist.sys.usb.config none 2>/dev/null || true
                setprop persist.vendor.usb.config none 2>/dev/null || true
                for f in /sys/kernel/config/usb_gadget/*/UDC; do
                  if [ -e "${'$'}f" ]; then
                    echo "disable gadget: ${'$'}f was $(cat ${'$'}f 2>/dev/null)"
                    echo "" > "${'$'}f" 2>/dev/null || echo "   write failed"
                  fi
                done

                # -- Force host role on all kernel switches --------------------------------
                for f in /sys/class/typec/*/data_role /sys/class/typec/*/port*/data_role; do
                  if [ -e "${'$'}f" ]; then echo host > "${'$'}f" 2>/dev/null || true; fi
                done
                for f in /sys/class/typec/*/power_role /sys/class/typec/*/port*/power_role; do
                  if [ -e "${'$'}f" ]; then echo source > "${'$'}f" 2>/dev/null || true; fi
                done
                for f in /sys/class/dual_role_usb/*/mode; do
                  if [ -e "${'$'}f" ]; then echo host > "${'$'}f" 2>/dev/null || true; fi
                done
                for f in /sys/class/usb_role/*/role; do
                  if [ -e "${'$'}f" ]; then echo host > "${'$'}f" 2>/dev/null || true; fi
                done

                echo 'after force:'
                for f in /sys/class/typec/*/data_role /sys/class/typec/*/power_role /sys/class/usb_role/*/role; do
                  if [ -e "${'$'}f" ]; then echo "  ${'$'}f=$(cat ${'$'}f 2>/dev/null)"; fi
                done
                echo 'sys.usb.config='$(getprop sys.usb.config 2>/dev/null)
                echo 'sys.usb.state='$(getprop sys.usb.state 2>/dev/null)
                echo 'djmrec persistent host end'
            """.trimIndent(),
            timeoutSeconds = 12
        )
    }

    fun scanKernelUsbDevices(): CommandResult {
        return runSu(
            command = """
                echo 'kernel usb device scan: start'
                for dev in /sys/bus/usb/devices/*/idVendor; do
                  dir=$(dirname "${'$'}dev")
                  vid=$(cat "${'$'}dev" 2>/dev/null)
                  pid=$(cat "${'$'}dir/idProduct" 2>/dev/null)
                  speed=$(cat "${'$'}dir/speed" 2>/dev/null)
                  product=$(cat "${'$'}dir/product" 2>/dev/null)
                  manufacturer=$(cat "${'$'}dir/manufacturer" 2>/dev/null)
                  echo "usb_dev ${'$'}dir vid=0x${'$'}vid pid=0x${'$'}pid speed=${'$'}speed product=${'$'}product mfr=${'$'}manufacturer"
                done
                echo 'kernel usb device scan: end'
                find /dev/bus/usb -type c -ls 2>/dev/null
            """.trimIndent(),
            timeoutSeconds = 8
        )
    }

    fun grantUsbDeviceAccess(appUid: Int): CommandResult {
        return runSu(
            command = """
                echo 'grant usb access to uid ${appUid}: start'
                for node in /dev/bus/usb/*/*; do
                  if [ -c "${'$'}node" ]; then
                    chown ${appUid}:${appUid} "${'$'}node" 2>/dev/null || true
                    chmod 666 "${'$'}node" 2>/dev/null || true
                    ls -l "${'$'}node" 2>/dev/null
                  fi
                done
                echo 'grant usb access: end'
            """.trimIndent(),
            timeoutSeconds = 6
        )
    }

    /**
     * Tries every known USB connection strategy in sequence and reports results.
     * Designed for the DJM-A9 Multi I/O port which can be stubborn to enumerate.
     * Returns detailed output showing which strategy (if any) found a Pioneer device.
     */
    fun tryAllConnectionStrategies(): CommandResult {
        return runSu(
            command = """
                echo '===== DJMREC TRY-EVERYTHING START ====='
                id
                getenforce 2>/dev/null || echo selinux-unavailable

                # Helper: quick scan for Pioneer/AlphaTheta devices.
                scan_pioneer() {
                  for dev in /sys/bus/usb/devices/*/idVendor; do
                    vid=$(cat "${'$'}dev" 2>/dev/null)
                    case "${'$'}vid" in 2b73|08e4|2B73|08E4)
                      pid=$(cat "${'$'}(dirname ${'$'}dev)/idProduct" 2>/dev/null)
                      speed=$(cat "${'$'}(dirname ${'$'}dev)/speed" 2>/dev/null)
                      echo "  FOUND Pioneer vid=0x${'$'}vid pid=0x${'$'}pid speed=${'$'}speed at ${'$'}(dirname ${'$'}dev)"
                      return 0
                    esac
                  done
                  echo "  (no Pioneer device on bus)"
                  return 1
                }

                # Helper: force host role on all switches.
                force_host() {
                  for f in /sys/class/typec/*/data_role /sys/class/typec/*/port*/data_role; do
                    [ -e "${'$'}f" ] && echo host > "${'$'}f" 2>/dev/null || true
                  done
                  for f in /sys/class/typec/*/power_role /sys/class/typec/*/port*/power_role; do
                    [ -e "${'$'}f" ] && echo source > "${'$'}f" 2>/dev/null || true
                  done
                  for f in /sys/class/dual_role_usb/*/mode; do
                    [ -e "${'$'}f" ] && echo host > "${'$'}f" 2>/dev/null || true
                  done
                  for f in /sys/class/usb_role/*/role; do
                    [ -e "${'$'}f" ] && echo host > "${'$'}f" 2>/dev/null || true
                  done
                }

                # Kill gadget first.
                setprop sys.usb.config none 2>/dev/null || true
                setprop vendor.usb.config none 2>/dev/null || true
                for f in /sys/kernel/config/usb_gadget/*/UDC; do
                  [ -e "${'$'}f" ] && echo "" > "${'$'}f" 2>/dev/null || true
                done

                # --- Strategy 1: USB 2.0 High-Speed only (like iPhone Lightning) ---
                echo '=== Strategy 1: Force USB 2.0 High-Speed + host ==='
                for ms in /sys/devices/platform/soc/*.dwc3/maximum_speed \
                         /sys/kernel/debug/usb/*dwc3/maximum_speed; do
                  if [ -e "${'$'}ms" ]; then
                    echo "max_speed was $(cat ${'$'}ms 2>/dev/null)"
                    echo high-speed > "${'$'}ms" 2>/dev/null && echo "  -> set to high-speed" || echo "  write failed"
                  fi
                done
                force_host
                sleep 3
                scan_pioneer

                # --- Strategy 2: USB 3.0 SuperSpeed ---
                echo '=== Strategy 2: Force USB 3.0 SuperSpeed + host ==='
                for ms in /sys/devices/platform/soc/*.dwc3/maximum_speed \
                         /sys/kernel/debug/usb/*dwc3/maximum_speed; do
                  if [ -e "${'$'}ms" ]; then
                    echo super-speed > "${'$'}ms" 2>/dev/null && echo "  -> set to super-speed" || echo "  write failed"
                  fi
                done
                force_host
                sleep 3
                scan_pioneer

                # --- Strategy 3: USB bus reset (unbind/rebind xHCI) ---
                echo '=== Strategy 3: USB bus reset (xhci unbind/rebind) ==='
                for xhci in /sys/bus/platform/drivers/xhci-hcd/*.auto; do
                  if [ -e "${'$'}xhci" ]; then
                    echo "unbind ${'$'}xhci"
                    echo "${'$'}(basename ${'$'}xhci)" > /sys/bus/platform/drivers/xhci-hcd/unbind 2>/dev/null || true
                    sleep 1
                    echo "${'$'}(basename ${'$'}xhci)" > /sys/bus/platform/drivers/xhci-hcd/bind 2>/dev/null || true
                    echo "rebound ${'$'}xhci"
                  fi
                done
                force_host
                sleep 3
                scan_pioneer

                # --- Strategy 4: Gadget probe then back to host ---
                echo '=== Strategy 4: Brief gadget mode, then return to host ==='
                setprop sys.usb.config adb 2>/dev/null || true
                sleep 2
                force_host
                setprop sys.usb.config none 2>/dev/null || true
                sleep 2
                scan_pioneer

                # --- Strategy 5: Aggressive PHY power-on + host ---
                echo '=== Strategy 5: Aggressive PHY wake + host ==='
                for p in /sys/devices/platform/soc/*.ssusb/power/control \
                         /sys/devices/platform/soc/*.dwc3/power/control \
                         /sys/devices/platform/soc/*.hsphy/power/control; do
                  [ -e "${'$'}p" ] && echo on > "${'$'}p" 2>/dev/null || true
                done
                for lpm in /sys/devices/platform/soc/*.dwc3/power/autosuspend_delay_ms; do
                  [ -e "${'$'}lpm" ] && echo -1 > "${'$'}lpm" 2>/dev/null || true
                done
                force_host
                sleep 3
                scan_pioneer

                # --- Final poll: watch the bus for 10 seconds ---
                echo '=== Final poll: watching bus for 10s ==='
                for i in 1 2 3 4 5 6 7 8 9 10; do
                  echo "  poll ${'$'}i:"
                  for dev in /sys/bus/usb/devices/*/idVendor; do
                    vid=$(cat "${'$'}dev" 2>/dev/null)
                    pid=$(cat "${'$'}(dirname ${'$'}dev)/idProduct" 2>/dev/null)
                    speed=$(cat "${'$'}(dirname ${'$'}dev)/speed" 2>/dev/null)
                    echo "    vid=0x${'$'}vid pid=0x${'$'}pid speed=${'$'}speed"
                  done
                  sleep 1
                done

                echo '===== DJMREC TRY-EVERYTHING END ====='
            """.trimIndent(),
            timeoutSeconds = 60
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