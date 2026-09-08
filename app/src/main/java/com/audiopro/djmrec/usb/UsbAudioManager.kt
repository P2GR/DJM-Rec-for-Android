package com.audiopro.djmrec.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the full USB lifecycle for the mixer: attach/detach detection, runtime permission
 * request, descriptor inspection (to prove the device is really UAC2 stereo audio and to
 * surface its native format to the UI), and resolution of the matching [AudioDeviceInfo] id
 * that the native AAudio/Oboe engine binds to for the actual capture stream.
 *
 * This class deliberately never opens a bulk/iso transfer itself — see class doc on
 * [UsbAudioDescriptorParser] for why.
 */
data class UsbInputOption(val deviceName: String, val label: String, val hasPermission: Boolean, val captureCandidate: Boolean)

class UsbAudioManager(private val context: Context) {

    companion object {
        private const val TAG = "UsbAudioManager"
        const val ACTION_USB_PERMISSION = "com.audiopro.djmrec.USB_PERMISSION"

        /** Pioneer Corporation (legacy) and AlphaTheta/Pioneer DJ (current) USB vendor IDs. */
        val PIONEER_VENDOR_IDS = setOf(0x08E4, 0x2B73)

        const val AUTO_CHANNEL_OFFSET = -1
        private fun isPioneerDevice(device: UsbDevice) = device.vendorId in PIONEER_VENDOR_IDS
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _deviceState = MutableStateFlow<UsbAudioDeviceInfo?>(null)
    val deviceState: StateFlow<UsbAudioDeviceInfo?> = _deviceState.asStateFlow()
    private val _inputs = MutableStateFlow<List<UsbInputOption>>(emptyList())
    val inputs = _inputs.asStateFlow()
    private val _connectionNotice = MutableStateFlow<String?>(null)
    val connectionNotice = _connectionNotice.asStateFlow()
    private var requestedDeviceName: String? = null
    private val diagnosticDevices = mutableSetOf<String>()

    private fun trace(device: UsbDevice, stage: String, detail: String = "") =
        com.audiopro.djmrec.diagnostics.RemoteDiagnostics.usbEvent(device.deviceName,
            device.productName ?: "Unknown USB device", device.vendorId, device.productId, stage, detail)

    fun refreshInputs() {
        val connected = usbManager.deviceList.values
        diagnosticDevices.retainAll(connected.map { it.deviceName }.toSet())
        connected.filter { diagnosticDevices.add(it.deviceName) }.forEach { device ->
            trace(device, "refreshInputs", "permission=${usbManager.hasPermission(device)}; capture candidate=${isCaptureCandidate(device)}; " +
                (0 until device.interfaceCount).joinToString { index ->
                    val intf = device.getInterface(index)
                    "if${intf.id}/alt${intf.alternateSetting} class=${intf.interfaceClass}/${intf.interfaceSubclass} protocol=${intf.interfaceProtocol} " +
                        (0 until intf.endpointCount).joinToString { epIndex ->
                            val ep = intf.getEndpoint(epIndex)
                            "ep=${ep.address} direction=${ep.direction} type=${ep.type} packet=${ep.maxPacketSize} interval=${ep.interval}"
                        }
                })
        }
        _inputs.value = usbManager.deviceList.values.map { device ->
            UsbInputOption(device.deviceName, device.productName ?: "USB ${device.vendorId.toString(16)}:${device.productId.toString(16)}",
                usbManager.hasPermission(device), isCaptureCandidate(device))
        }.sortedBy { it.label }
    }

    /** Caller must stop monitoring first. Never close an active raw capture here. */
    fun selectDevice(deviceName: String): Boolean {
        if (activeIsoConnection != null) return false
        val device = usbManager.deviceList[deviceName] ?: return false
        if (!isCaptureCandidate(device)) return false
        requestedDeviceName = deviceName
        _deviceState.value = null
        onDeviceAttached(device)
        return true
    }

    private var registered = false
    private var rootModeEnabled = false

    /**
     * Kept open (not `.close()`'d) for as long as native libusb capture is running -- its fd
     * is handed to `libusb_wrap_sys_device()` on the native side, so closing it mid-capture
     * would pull the fd out from under libusb. See [openIsoCaptureHandle]/[releaseIsoCaptureConnection].
     */
    private var activeIsoConnection: UsbDeviceConnection? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> handlePermissionResult(intent)
            }
        }
    }

    private val usbDeviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = getIntentDevice(intent) ?: return
                    Log.i(TAG, "Attach broadcast received for ${device.deviceName}")
                    onDeviceAttached(device)
                    refreshInputs()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = getIntentDevice(intent) ?: return
                    com.audiopro.djmrec.diagnostics.RemoteDiagnostics.usbDetached(device.deviceName)
                    if (_deviceState.value?.deviceName == device.deviceName) {
                        Log.i(TAG, "Mixer detached: ${device.deviceName}")
                        _deviceState.value = null
                        _connectionNotice.value = "USB input disconnected. Reconnect to continue."
                    }
                    refreshInputs()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getIntentDevice(intent: Intent): UsbDevice? =
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    /** Call once (e.g. from Application.onCreate) to start listening for attach/detach/permission events. */
    fun start() {
        if (registered) return
        ContextCompat.registerReceiver(
            context,
            permissionReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val usbDeviceFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            context,
            usbDeviceReceiver,
            usbDeviceFilter,
            ContextCompat.RECEIVER_EXPORTED
        )
        registered = true

        // Pick up a mixer that was already plugged in before the app started.
        scanForConnectedMixer("start")
    }

    fun stop() {
        if (!registered) return
        context.unregisterReceiver(permissionReceiver)
        context.unregisterReceiver(usbDeviceReceiver)
        registered = false
        releaseIsoCaptureConnection()
    }

    /** Explicit UI-triggered scan. If Android exposes the mixer in UsbManager, this requests permission/opens it. */
    fun scanForConnectedMixer(reason: String = "manual-rescan"): Boolean {
        refreshInputs()
        if (rootModeEnabled) {
            // Persistent host-mode + kernel USB scan for the DJM REC port.
            val hostResult = RootUsbHostController.forcePersistentHostMode()
            Log.i(TAG, "$reason: root persistent host exit=${hostResult.exitCode} timedOut=${hostResult.timedOut}\n${hostResult.output}")
            RootUsbHostController.grantUsbDeviceAccess(RootUsbHostController.getAppUid())
            val kernelScan = RootUsbHostController.scanKernelUsbDevices()
            Log.i(TAG, "$reason: kernel USB scan exit=${kernelScan.exitCode} timedOut=${kernelScan.timedOut}\n${kernelScan.output}")
        }
        logEnumeratedDevices(reason)
        val device = findConnectedAudioClassDevice()
        if (device == null) {
            Log.w(TAG, "$reason: no connected device exposes a supported audio capture interface")
            if (rootModeEnabled) {
                // The framework says nothing is attached -- ask the kernel directly whether it
                // ever even saw the mixer negotiate, independent of what UsbManager reports.
                val kernelLog = RootUsbHostController.captureKernelUsbLog()
                Log.w(
                    TAG,
                    "$reason: kernel dmesg (usb/typec/dwc3/xhci) exit=${kernelLog.exitCode} " +
                        "timedOut=${kernelLog.timedOut}\n${kernelLog.output}"
                )
            }
            _deviceState.value = null
            _connectionNotice.value = if (_inputs.value.isEmpty()) "Connect a mixer or USB audio interface using a data cable."
                else "Connected USB devices expose no audio capture input. Use the PC/Mac audio port, not a storage or Link Export connection."
            return false
        }
        Log.i(TAG, "$reason: found USB audio class device ${device.deviceName}; connecting")
        onDeviceAttached(device)
        return true
    }

    fun setRootModeEnabled(enabled: Boolean) {
        rootModeEnabled = enabled
        Log.i(TAG, "Root USB assist mode enabled=$enabled")
    }

    private fun logEnumeratedDevices(reason: String) {
        val allDevices = usbManager.deviceList.values
        Log.i(TAG, "$reason: ${allDevices.size} USB device(s) currently enumerated by the host")
        allDevices.forEach { d ->
            val classes = (0 until d.interfaceCount).joinToString { i ->
                val intf = d.getInterface(i)
                "if${intf.id}/alt${intf.alternateSetting}=class:${intf.interfaceClass}/sub:${intf.interfaceSubclass}"
            }
            Log.i(TAG, "  device ${d.deviceName} vid=${d.vendorId} pid=${d.productId} name=${d.productName} interfaces=[$classes]")
        }
    }

    private fun isCaptureCandidate(device: UsbDevice): Boolean =
        PioneerMixerProfile.find(device.vendorId, device.productId) != null ||
            AllInOneProfile.find(device.vendorId, device.productId) != null ||
            (0 until device.interfaceCount).any { i ->
                val intf = device.getInterface(i)
                intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    intf.interfaceSubclass == 2 &&
                    (0 until intf.endpointCount).any { endpointIndex ->
                        val endpoint = intf.getEndpoint(endpointIndex)
                        endpoint.direction == UsbConstants.USB_DIR_IN &&
                            endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC
                    }
            }

    private fun findConnectedAudioClassDevice(): UsbDevice? =
        usbManager.deviceList.values.filter(::isCaptureCandidate)
            .sortedWith(compareByDescending<UsbDevice> { it.deviceName == _deviceState.value?.deviceName }
                .thenByDescending { PioneerMixerProfile.find(it.vendorId, it.productId) != null }
                .thenBy { it.deviceName })
            .firstOrNull()

    private fun onDeviceAttached(device: UsbDevice) {
        trace(device, "onDeviceAttached", "permission=${usbManager.hasPermission(device)}; capture candidate=${isCaptureCandidate(device)}")
        _deviceState.value?.let { active ->
            // Refresh Android's delayed input registration without reopening a live USB connection.
            if (active.deviceName == device.deviceName && active.audioManagerDeviceId < 0) {
                resolveAudioManagerDeviceId(device)?.let { (id, rates) ->
                    _deviceState.value = active.copy(audioManagerDeviceId = id,
                        supportedSampleRates = (active.supportedSampleRates + rates).distinct())
                }
            }
            return // Never replace a live source on another attach/rescan.
        }
        if (!isCaptureCandidate(device)) {
            Log.w(TAG, "Attached device ${device.deviceName} (${device.vendorId}:${device.productId}) has no USB_CLASS_AUDIO interface; ignoring")
            return
        }

        Log.i(TAG, "UAC candidate attached: ${device.deviceName} (${device.vendorId}:${device.productId})")
        requestedDeviceName = device.deviceName
        _connectionNotice.value = "Checking ${device.productName ?: "USB input"}..."

        if (usbManager.hasPermission(device)) {
            Log.i(TAG, "Already have permission for ${device.deviceName}; inspecting descriptors")
            inspectAndPublish(device)
        } else {
            _connectionNotice.value = "Allow USB access to ${device.productName ?: "your input"}."
            Log.i(TAG, "No permission yet for ${device.deviceName}; requesting")
            requestPermission(device)
        }
    }

    private fun requestPermission(device: UsbDevice) {
        trace(device, "requestPermission", "Requesting USB permission; descriptors unavailable until allowed")
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, intent, flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun handlePermissionResult(intent: Intent) {
        val device = getIntentDevice(intent) ?: return
        if (requestedDeviceName != device.deviceName) return
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        trace(device, "handlePermissionResult", "granted=$granted")
        Log.i(TAG, "Permission result for ${device.deviceName}: granted=$granted")
        if (granted) {
            if (_deviceState.value == null && usbManager.deviceList.containsKey(device.deviceName)) {
                inspectAndPublish(device)
            }
        } else {
            Log.w(TAG, "USB permission denied for ${device.deviceName}")
            _connectionNotice.value = "USB access denied. Open Inputs and select the device to retry."
        }
        refreshInputs()
    }

    /**
     * Opens a short-lived control connection purely to read descriptors, resolves the matching
     * routable [AudioDeviceInfo], and publishes the combined [UsbAudioDeviceInfo] snapshot.
     */
    private fun inspectAndPublish(device: UsbDevice) {
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            trace(device, "inspectAndPublish", "FAILED: Android could not open USB control connection")
            Log.e(TAG, "Failed to open control connection to ${device.deviceName}")
            _connectionNotice.value = "Could not open USB input. Check permission and reconnect."
            return
        }

        val streamingInterfaces: List<AudioStreamingInterfaceInfo>
        val rawDescriptors: ByteArray
        val topology: UacTopology
        var clockSampleRates = emptyList<Int>()
        var mixerProfile: PioneerMixerProfile? = null
        val bestInterface = try {
            rawDescriptors = connection.rawDescriptors ?: ByteArray(0)
            com.audiopro.djmrec.diagnostics.RemoteDiagnostics.descriptors(device.vendorId, device.productId, rawDescriptors, device.deviceName)
            Log.i(TAG, "${device.deviceName}: read ${rawDescriptors.size} bytes of raw descriptors")
            streamingInterfaces = UsbAudioDescriptorParser.findAudioStreamingInterfaces(rawDescriptors)
            topology = UsbAudioDescriptorParser.parseTopology(rawDescriptors)
            mixerProfile = PioneerMixerProfile.find(device.vendorId, device.productId)
            clockSampleRates = if (mixerProfile != null) {
                Log.i(TAG, "${device.deviceName}: using ${mixerProfile.displayName} endpoint/vendor clock profile")
                emptyList()
            } else {
                queryClockSampleRates(device, connection, topology)
            }
            Log.i(
                TAG,
                "${device.deviceName}: found ${streamingInterfaces.size} AudioStreaming alternate setting(s): " +
                    streamingInterfaces.joinToString {
                        "if${it.interfaceNumber}/alt${it.alternateSetting} ${it.channelCount}ch " +
                            "${it.bitResolution}bit ep=${it.isochronousInEndpointAddress}"
                    }
            )
                    Log.i(
                    TAG,
                    "${device.deviceName}: UAC topology AC=" +
                        topology.audioControlInterfaces.joinToString(prefix = "[", postfix = "]") {
                            "if${it.interfaceNumber}:v${it.audioClassVersion.toString(16)}"
                        } + " " +
                        "clocks=${topology.clockSources.size} selectors=${topology.clockSelectors.size} " +
                        "features=${topology.featureUnits.size} mixers=${topology.mixerUnits.size} " +
                        "terminals=${topology.inputTerminals.size}/${topology.outputTerminals.size} " +
                            "descriptorRates=${topology.descriptorSampleRates} clockRates=$clockSampleRates"
                    )
            val recordOffset = AllInOneProfile.find(device.vendorId, device.productId)?.recordChannelOffset ?: 0
            val standardBest = UsbAudioDescriptorParser.selectBestStereoInterface(streamingInterfaces.filter {
                it.channelCount >= recordOffset + (if (recordOffset == 0) 1 else 2) &&
                    (mixerProfile?.hasVendorCaptureOverride != true ||
                    (it.interfaceNumber == mixerProfile.vendorCaptureInterface &&
                        it.alternateSetting == mixerProfile.vendorCaptureAlternateSetting &&
                        it.channelCount == mixerProfile.vendorCaptureChannelCount &&
                        it.subframeSize == mixerProfile.vendorCaptureSubframeSize &&
                        it.bitResolution == mixerProfile.vendorCaptureBitResolution))
            })
            standardBest ?: mixerProfile?.takeIf { it.hasVendorCaptureOverride }?.let { profile ->
                // Never replace an explicit, conflicting PCM descriptor with guessed bytes.
                if (streamingInterfaces.any { it.interfaceNumber == profile.vendorCaptureInterface &&
                        it.alternateSetting == profile.vendorCaptureAlternateSetting }) return@let null
                Log.i(
                    TAG,
                    "${device.deviceName}: no standard AudioStreaming interface; trying " +
                        "${profile.displayName} vendor-class capture override " +
                        "if${profile.vendorCaptureInterface}/alt${profile.vendorCaptureAlternateSetting}"
                )
                UsbAudioDescriptorParser.findVendorEndpoint(
                    rawDescriptors, profile.vendorCaptureInterface, profile.vendorCaptureAlternateSetting
                )?.copy(
                    channelCount = profile.vendorCaptureChannelCount,
                    bitResolution = profile.vendorCaptureBitResolution,
                    subframeSize = profile.vendorCaptureSubframeSize
                ).also {
                    if (it == null) {
                        Log.w(
                            TAG,
                            "${device.deviceName}: vendor capture override interface has no " +
                                "isochronous IN endpoint either -- descriptor layout doesn't match " +
                                "what was captured when this profile was written"
                        )
                    } else {
                        Log.w(
                            TAG,
                            "${device.deviceName}: using ${profile.displayName} vendor capture format " +
                                "(${it.channelCount}ch/${it.bitResolution}bit); " +
                                "hardwareConfirmed=${profile.isHardwareConfirmed}"
                        )
                    }
                }
            }
        } finally {
            // We only needed the descriptors; AAudio/AudioFlinger owns the real data connection.
            connection.close()
        }

        if (bestInterface == null) {
            trace(device, "inspectAndPublish", "FAILED: no supported capture format; configuration logged under MixerCapabilities and UsbDescriptors")
            Log.w(TAG, "${device.deviceName} exposes no usable isochronous IN audio streaming interface")
            _deviceState.value = null
            _connectionNotice.value = AllInOneProfile.find(device.vendorId, device.productId)?.takeIf { it == AllInOneProfile.XDJ_RX3 }?.setupHint
                ?: "${device.productName ?: "This device"} exposes no supported PCM capture format. Try its PC/Mac audio mode. A vendor-specific format needs a verified driver profile."
            return
        }
        Log.i(
            TAG,
            "${device.deviceName}: selected if${bestInterface.interfaceNumber}/alt${bestInterface.alternateSetting} " +
                "${bestInterface.channelCount}ch/${bestInterface.bitResolution}bit for capture"
        )

        val routedDeviceId = resolveAudioManagerDeviceId(device)
        if (routedDeviceId == null) {
            Log.w(TAG, "${device.deviceName}: no matching AudioManager USB input device found (routing will fail)")
        }

        _deviceState.value = UsbAudioDeviceInfo(
            deviceName = device.deviceName,
            productName = device.productName ?: "USB Audio Device",
            vendorId = device.vendorId,
            productId = device.productId,
            streamingInterfaceNumber = bestInterface.interfaceNumber,
            activeAlternateSetting = bestInterface.alternateSetting,
            isochronousInEndpointAddress = bestInterface.isochronousInEndpointAddress ?: -1,
            isochronousInMaxPacketSize = bestInterface.isochronousInMaxPacketSize ?: -1,
            channelCount = bestInterface.channelCount,
            bitResolution = bestInterface.bitResolution,
            subframeSize = bestInterface.subframeSize,
            supportedSampleRates = mixerProfile?.vendorCaptureSampleRates?.takeIf { it.isNotEmpty() }
                ?: bestInterface.sampleRates.takeIf { it.isNotEmpty() }
                ?: (clockSampleRates +
                    (routedDeviceId?.second ?: emptyList())).distinct(),
            audioManagerDeviceId = routedDeviceId?.first ?: -1,
            hasPermission = true,
            isPioneer = isPioneerDevice(device),
            rawDescriptors = rawDescriptors,
            topology = topology
        )
        _connectionNotice.value = null
        refreshInputs()
    }

    private fun queryClockSampleRates(
        device: UsbDevice,
        connection: UsbDeviceConnection,
        topology: UacTopology
    ): List<Int> {
        val rates = linkedSetOf<Int>()
        val recordOffset = AllInOneProfile.find(device.vendorId, device.productId)?.recordChannelOffset ?: 0
        val selected = UsbAudioDescriptorParser.selectBestStereoInterface(topology.audioStreamingInterfaces.filter {
            it.channelCount >= recordOffset + (if (recordOffset == 0) 1 else 2)
        })
        val selectedClock = selected?.let { clockFor(it, topology) }
        topology.clockSources.filter { it.supportsFrequencyControl && it == selectedClock }.forEach { clock ->
            val controlInterface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.id == clock.interfaceNumber }
            if (controlInterface == null || !connection.claimInterface(controlInterface, true)) {
                Log.w(TAG, "Clock source ${clock.id}: could not claim AC interface ${clock.interfaceNumber}")
                return@forEach
            }
            val buffer = ByteArray(2 + 12 * 32)
            val transferred = try {
                connection.controlTransfer(
                    UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_CLASS or 0x01,
                    0x82,
                    0x0100,
                    (clock.id shl 8) or clock.interfaceNumber,
                    buffer,
                    buffer.size,
                    500
                )
            } finally {
                connection.releaseInterface(controlInterface)
            }
            if (transferred < 2) {
                Log.w(TAG, "Clock source ${clock.id}: GET_RANGE failed or unsupported ($transferred)")
                return@forEach
            }
            val rangeCount = (buffer[0].toInt() and 0xFF) or ((buffer[1].toInt() and 0xFF) shl 8)
            for (rangeIndex in 0 until rangeCount) {
                val base = 2 + rangeIndex * 12
                if (base + 11 >= transferred) break
                val minimum = readLe32(buffer, base)
                val maximum = readLe32(buffer, base + 4)
                rates += CaptureFormatPolicy.ratesInRange(minimum, maximum, readLe32(buffer, base + 8))
            }
        }
        return rates.toList()
    }

    private fun clockFor(
        streaming: AudioStreamingInterfaceInfo,
        topology: UacTopology
    ): ClockSourceInfo? {
        val terminal = (topology.inputTerminals + topology.outputTerminals)
            .firstOrNull { it.id == streaming.terminalLink }
        return topology.clockSources.firstOrNull { it.id == terminal?.clockSourceId }
    }

    private fun readLe32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    /**
     * AAudio/Oboe binds to devices via the *AudioManager* device id space, which is separate
     * from the raw UsbDevice handle. We cross-reference by USB product name, since
     * [AudioDeviceInfo] does not expose vendor/product IDs directly.
     */
    private fun resolveAudioManagerDeviceId(device: UsbDevice): Pair<Int, List<Int>>? {
        val candidates = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        Log.i(
            TAG,
            "AudioManager input devices: " + candidates.joinToString {
                "id=${it.id} type=${it.type} product=${it.productName}"
            }
        )
        val usbInputs = candidates.filter {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        val match = usbInputs.firstOrNull { it.address == device.deviceName }
            ?: usbInputs.filter { it.productName?.toString()?.trim() == device.productName?.trim() }
                .singleOrNull()

        return match?.let { it.id to it.sampleRates.toList() }
    }

    /** Re-resolves the negotiated sample rate once the native engine reports the opened stream's rate. */
    fun updateNegotiatedSampleRate(sampleRate: Int) {
        _deviceState.value = _deviceState.value?.copy(negotiatedSampleRate = sampleRate)
    }

    /**
     * Opens (and holds open) a fresh [UsbDeviceConnection] to the currently published device
     * purely for the native libusb capture path, and returns everything
     * `UsbIsoAudioSource`/`AudioEngine.openUsbIso` needs to claim the interface and start
     * pulling isochronous transfers.
     *
     * IMPORTANT: unlike [inspectAndPublish]'s short-lived descriptor-reading connection, the
     * connection opened here is deliberately kept alive in [activeIsoConnection] -- its fd is
     * handed to `libusb_wrap_sys_device()`, and closing the connection while libusb still holds
     * that fd would pull capture out from under it. Call [releaseIsoCaptureConnection] once the
     * native side has fully torn down (after `AudioEngine.close()` returns).
     *
     * Returns null if there is no published device, permission has not been granted, or the
     * connection could not be opened -- callers should fall back to the AAudio path in that case.
     */
    /**
     * Sets the mixer's MIX/REC OUT route via Android's own `UsbDeviceConnection.controlTransfer`
     * API, on the same connection whose fd is about to be handed to libusb.
     *
     * Why here and not in native code: on real DJM-900NXS2 hardware, the route GET/SET requests
     * reliably succeed through this Java API but reliably fail (`LIBUSB_ERROR_BUSY`) through
     * `libusb_control_transfer()` on a `libusb_wrap_sys_device` handle wrapping the very same fd
     * once isochronous transfers are in flight -- regardless of claim/alt-setting ordering.
     *
     * Why this no longer verifies the SET by reading it back: a real USBPcap capture of Pioneer's
     * own Windows Setting Utility toggling this exact output between MIX and another source
     * confirmed two things -- (1) `wValue = ((output+1)<<8)|source` with source `0x0A` for MIX is
     * the correct encoding (the utility sent literally that, for both output 1 and output 5), and
     * (2) the GET response at this wIndex is `00 01 01 01 01` for the *entire* capture -- before
     * the SET, immediately after it, and hundreds of polls later -- never once reflecting the
     * change the utility had just made and the user could see take effect on screen. So this
     * register is not a live route readout (or at least not one the utility itself trusts), and
     * gating success/retry on it -- as this function and its native counterpart used to -- was
     * chasing a signal that was never going to move. The official driver doesn't verify either;
     * it just sends the SET and trusts it. This does the same.
     */
    private fun establishPioneerRoute(connection: UsbDeviceConnection, profile: PioneerMixerProfile) {
        val defaultOutput = profile.defaultCaptureChannelOffset / 2
        val outputs = (listOf(defaultOutput) + profile.additionalMixOutputs)
            .distinct()
            .filter { it in 0 until profile.outputCount }
        for (output in outputs) {
            val mixSource = profile.mixWithoutMicSources.getOrNull(output) ?: continue
            if (mixSource < 0) continue
            val setValue = ((output + 1) shl 8) or mixSource
            val setResult = connection.controlTransfer(
                UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR,
                PioneerMixerProfile.ROUTE_SET_REQUEST,
                setValue,
                PioneerMixerProfile.ROUTE_INDEX,
                null,
                0,
                1000
            )
            if (setResult < 0) {
                Log.w(TAG, "${profile.displayName}: route SET output ${output + 1} value 0x${setValue.toString(16)} failed (result=$setResult)")
            } else {
                Log.i(TAG, "${profile.displayName}: sent MIX/REC OUT route SET for output ${output + 1} (source=0x${mixSource.toString(16)}) -- not verified by readback, see function doc")
            }
        }
    }

    fun openIsoCaptureHandle(): UsbIsoCaptureHandle? {
        val info = _deviceState.value ?: run {
            Log.w(TAG, "openIsoCaptureHandle: no device currently published")
            return null
        }
        val device = usbManager.deviceList.values.firstOrNull {
            it.deviceName == info.deviceName && it.vendorId == info.vendorId && it.productId == info.productId
        } ?: run {
            Log.w(TAG, "openIsoCaptureHandle: ${info.deviceName} is no longer in UsbManager.deviceList")
            return null
        }
        if (!usbManager.hasPermission(device)) {
            Log.w(TAG, "openIsoCaptureHandle: no permission for ${device.deviceName}")
            return null
        }

        // Replace any stale connection from a previous session before opening a new one.
        releaseIsoCaptureConnection()

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.e(TAG, "openIsoCaptureHandle: openDevice failed for ${device.deviceName}")
            return null
        }
        activeIsoConnection = connection
        info.pioneerMixerProfile?.let { profile ->
            establishPioneerRoute(connection, profile)
        }
        val topology = info.topology
        val streaming = topology?.audioStreamingInterfaces?.firstOrNull {
            it.interfaceNumber == info.streamingInterfaceNumber && it.alternateSetting == info.activeAlternateSetting
        }
        // Supported Pioneer profiles use endpoint/vendor clock flow. Entity requests stall
        // some firmware, including the A9, so native code measures the active stream cadence.
        val clock = when {
            info.pioneerMixerProfile != null -> null
            streaming != null -> clockFor(streaming, topology)
            else -> null
        }
        Log.i(
            TAG,
            "openIsoCaptureHandle: if${info.streamingInterfaceNumber}/alt${info.activeAlternateSetting} " +
                "terminal=${streaming?.terminalLink} clock=${clock?.id}@if${clock?.interfaceNumber} " +
                "settable=${clock?.supportsFrequencySet}"
        )

        return UsbIsoCaptureHandle(
            fd = connection.fileDescriptor,
            interfaceNumber = info.streamingInterfaceNumber,
            alternateSetting = info.activeAlternateSetting,
            endpointAddress = info.isochronousInEndpointAddress,
            maxPacketSize = info.isochronousInMaxPacketSize,
            totalChannels = info.channelCount,
            subframeSize = info.subframeSize,
            bitResolution = info.bitResolution,
            rawDescriptors = info.rawDescriptors,
            clockControlInterfaceNumber = clock?.interfaceNumber ?: -1,
            clockSourceId = clock?.id ?: -1,
            clockSupportsFrequencySet = clock?.supportsFrequencySet == true,
            feedbackEndpointAddress = info.streamingInterfaceNumber.let { interfaceNumber ->
                info.topology?.audioStreamingInterfaces
                    ?.firstOrNull { it.interfaceNumber == interfaceNumber && it.alternateSetting == info.activeAlternateSetting }
                    ?.isochronousFeedbackEndpointAddress ?: -1
            },
            feedbackMaxPacketSize = info.streamingInterfaceNumber.let { interfaceNumber ->
                info.topology?.audioStreamingInterfaces
                    ?.firstOrNull { it.interfaceNumber == interfaceNumber && it.alternateSetting == info.activeAlternateSetting }
                    ?.isochronousFeedbackMaxPacketSize ?: -1
            },
            vendorId = info.vendorId,
            productId = info.productId
        )
    }

    /**
     * Closes the connection opened by [openIsoCaptureHandle], if any. Must only be called once
     * native capture has fully stopped (i.e. after `AudioEngine.close()` returns) -- see that
     * method's contract.
     */
    fun releaseIsoCaptureConnection() {
        activeIsoConnection?.close()
        activeIsoConnection = null
    }
}
