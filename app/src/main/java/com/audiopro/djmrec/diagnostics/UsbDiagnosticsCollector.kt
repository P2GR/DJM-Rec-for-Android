package com.audiopro.djmrec.diagnostics

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import com.audiopro.djmrec.DjmRecApplication
import com.audiopro.djmrec.usb.PioneerMixerProfile
import com.audiopro.djmrec.usb.UacTopology
import com.audiopro.djmrec.usb.UsbAudioDescriptorParser
import com.audiopro.djmrec.usb.UsbAudioDeviceInfo
import java.security.MessageDigest

internal object UsbDiagnosticsCollector {

    private const val MAX_DESCRIPTOR_DUMP_BYTES = 4096

    fun append(context: Context, report: StringBuilder, nativeSummary: String) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val published = (context.applicationContext as? DjmRecApplication)
            ?.usbAudioManager?.deviceState?.value
        val rawIsoActive = nativeSummary.lineSequence().any { it == "source_mode=usb_iso" } &&
            nativeSummary.lineSequence().any { it == "stream_open=true" }
        val devices = usbManager.deviceList.values.sortedBy { it.deviceName }

        report.appendLine("=== Detailed USB mixer diagnostics (${devices.size}) ===")
        report.appendLine("raw isochronous session active: $rawIsoActive")
        if (devices.isEmpty()) {
            report.appendLine("RESULT: FAIL - Android UsbManager enumerates no USB device")
            report.appendLine()
            return
        }

        devices.forEachIndexed { index, device ->
            appendDevice(
                report = report,
                usbManager = usbManager,
                device = device,
                published = published?.takeIf {
                    it.vendorId == device.vendorId && it.productId == device.productId
                },
                rawIsoActive = rawIsoActive,
                ordinal = index + 1
            )
        }
    }

    private fun appendDevice(
        report: StringBuilder,
        usbManager: UsbManager,
        device: UsbDevice,
        published: UsbAudioDeviceInfo?,
        rawIsoActive: Boolean,
        ordinal: Int
    ) {
        val permission = usbManager.hasPermission(device)
        val profile = PioneerMixerProfile.find(device.vendorId, device.productId)
        report.appendLine("--- USB device $ordinal ---")
        report.appendLine(
            "identity: path=${device.deviceName} id=${device.deviceId} " +
                "vid=${hex4(device.vendorId)} pid=${hex4(device.productId)}"
        )
        report.appendLine(
            "strings: manufacturer=${safeString { device.manufacturerName }} " +
                "product=${safeString { device.productName }} version=${safeString { device.version }}"
        )
        report.appendLine(
            "device class=${device.deviceClass} subclass=${device.deviceSubclass} " +
                "protocol=${device.deviceProtocol} configurations=${device.configurationCount} " +
                "permission=$permission"
        )
        report.appendLine("profile: ${profile?.displayName ?: "unsupported/unknown"}")

        for (configurationIndex in 0 until device.configurationCount) {
            val configuration = device.getConfiguration(configurationIndex)
            report.appendLine(
                "configuration[$configurationIndex]: id=${configuration.id} " +
                    "name=${configuration.name ?: "none"} maxPower=${configuration.maxPower}mA " +
                    "remoteWakeup=${configuration.isRemoteWakeup} selfPowered=${configuration.isSelfPowered} " +
                    "interfaces=${configuration.interfaceCount}"
            )
            for (interfaceIndex in 0 until configuration.interfaceCount) {
                val usbInterface = configuration.getInterface(interfaceIndex)
                report.appendLine(
                    "  interface[$interfaceIndex]: if=${usbInterface.id} alt=${usbInterface.alternateSetting} " +
                        "class=${usbInterface.interfaceClass} subclass=${usbInterface.interfaceSubclass} " +
                        "protocol=${usbInterface.interfaceProtocol} endpoints=${usbInterface.endpointCount} " +
                        "name=${usbInterface.name ?: "none"}"
                )
                for (endpointIndex in 0 until usbInterface.endpointCount) {
                    report.appendLine("    ${describeEndpoint(usbInterface.getEndpoint(endpointIndex))}")
                }
            }
        }

        appendProfileContract(report, profile)
        appendPublishedSelection(report, published)

        var connection: UsbDeviceConnection? = null
        val rawDescriptors = when {
            published?.rawDescriptors?.isNotEmpty() == true -> published.rawDescriptors
            !permission -> byteArrayOf()
            rawIsoActive -> byteArrayOf()
            else -> {
                connection = usbManager.openDevice(device)
                connection?.rawDescriptors ?: byteArrayOf()
            }
        }

        if (rawDescriptors.isEmpty()) {
            val reason = when {
                !permission -> "USB permission missing"
                rawIsoActive -> "live capture active and no published descriptor snapshot available"
                else -> "openDevice/getRawDescriptors failed"
            }
            report.appendLine("raw descriptors: unavailable ($reason)")
            report.appendLine("RESULT: WARN - descriptor-level protocol cannot be verified")
        } else {
            appendRawDescriptorFacts(report, rawDescriptors)
        }

        if (profile != null) {
            if (rawIsoActive) {
                report.appendLine("route GET probe: skipped; native live session owns mixer connection")
                report.appendLine("route evidence: see native pipeline snapshot below")
            } else if (!permission) {
                report.appendLine("route GET probe: skipped; USB permission missing")
            } else {
                if (connection == null) connection = usbManager.openDevice(device)
                appendReadOnlyRouteProbe(report, connection, profile)
            }
        }
        connection?.close()
        report.appendLine()
    }

    private fun appendProfileContract(report: StringBuilder, profile: PioneerMixerProfile?) {
        if (profile == null) {
            report.appendLine("protocol contract: generic UAC path; no proprietary route writes allowed")
            return
        }
        report.appendLine(
            "protocol contract: vendor GET request=${hex2(PioneerMixerProfile.ROUTE_GET_REQUEST)} " +
                "SET request=${hex2(PioneerMixerProfile.ROUTE_SET_REQUEST)} " +
                "index=${hex4(PioneerMixerProfile.ROUTE_INDEX)} readMode=${profile.routeReadMode} " +
                "readLength=${profile.routeReadMode.responseLength}"
        )
        report.appendLine(
            "route SET encoding: bmRequestType=0x40 wValue=((output+1)<<8)|source; " +
                "endpoint clock GET_CUR=0x81 SET_CUR=0x01 wValue=0x0100"
        )
        report.appendLine(
            "capture contract: outputs=${profile.outputCount} " +
                "defaultPair=${profile.defaultCaptureChannelOffset + 1}-" +
                "${profile.defaultCaptureChannelOffset + 2} " +
                "MIX/REC sources=${profile.mixWithoutMicSources.joinToString { hex2(it) }}"
        )
        report.appendLine(
            "duplex keepalive: required=${profile.requiresPlaybackTraffic} " +
                "interface=${profile.playbackInterface} alt=${profile.playbackAlternateSetting}"
        )
    }

    private fun appendPublishedSelection(report: StringBuilder, info: UsbAudioDeviceInfo?) {
        if (info == null) {
            report.appendLine("app selection: not published by UsbAudioManager")
            return
        }
        report.appendLine(
            "app selection: if${info.streamingInterfaceNumber}/alt${info.activeAlternateSetting} " +
                "ep=${hex2(info.isochronousInEndpointAddress)} packet=${info.isochronousInMaxPacketSize} " +
                "wire=${info.channelCount}ch/${info.bitResolution}bit/subframe${info.subframeSize} " +
                "rates=${info.supportedSampleRates} AudioManagerId=${info.audioManagerDeviceId} " +
                "rawIso=${info.requiresIsoCapture}"
        )
    }

    private fun appendRawDescriptorFacts(report: StringBuilder, raw: ByteArray) {
        report.appendLine(
            "raw descriptors: bytes=${raw.size} sha256=${sha256(raw)} " +
                "dumpLimit=$MAX_DESCRIPTOR_DUMP_BYTES"
        )
        if (raw.size >= 18 && unsigned(raw[1]) == 0x01) {
            report.appendLine(
                "device descriptor: bcdUSB=${bcd(le16(raw, 2))} bcdDevice=${bcd(le16(raw, 12))} " +
                    "ep0MaxPacket=${unsigned(raw[7])} configurations=${unsigned(raw[17])}"
            )
        }
        val streaming = runCatching {
            UsbAudioDescriptorParser.findAudioStreamingInterfaces(raw)
        }.getOrElse {
            report.appendLine("descriptor parser error: ${it.javaClass.simpleName}: ${it.message}")
            emptyList()
        }
        val topology = runCatching { UsbAudioDescriptorParser.parseTopology(raw) }.getOrNull()
        val selected = UsbAudioDescriptorParser.selectBestStereoInterface(streaming)
        report.appendLine("AudioStreaming alternates (${streaming.size}):")
        streaming.forEach {
            report.appendLine(
                "  if${it.interfaceNumber}/alt${it.alternateSetting} terminal=${it.terminalLink} " +
                    "${it.channelCount}ch/${it.bitResolution}bit/subframe${it.subframeSize} " +
                    "inEp=${it.isochronousInEndpointAddress?.let(::hex2) ?: "none"} " +
                    "packet=${it.isochronousInMaxPacketSize ?: -1} " +
                    "feedbackEp=${it.isochronousFeedbackEndpointAddress?.let(::hex2) ?: "none"} " +
                    "feedbackPacket=${it.isochronousFeedbackMaxPacketSize ?: -1}"
            )
        }
        report.appendLine(
            "parser selection: " + if (selected == null) {
                "FAIL - no usable isochronous IN alternate"
            } else {
                "PASS - if${selected.interfaceNumber}/alt${selected.alternateSetting} " +
                    "ep=${selected.isochronousInEndpointAddress?.let(::hex2)} ${selected.channelCount}ch"
            }
        )
        topology?.let { appendTopology(report, it) }
        report.appendLine("raw descriptor hex:")
        raw.take(MAX_DESCRIPTOR_DUMP_BYTES).chunked(16).forEachIndexed { line, bytes ->
            report.appendLine(
                "  ${line.times(16).toString(16).padStart(4, '0')}: " +
                    bytes.joinToString(" ") { unsigned(it).toString(16).padStart(2, '0') }
            )
        }
        if (raw.size > MAX_DESCRIPTOR_DUMP_BYTES) {
            report.appendLine("  ... ${raw.size - MAX_DESCRIPTOR_DUMP_BYTES} bytes omitted")
        }
    }

    private fun appendTopology(report: StringBuilder, topology: UacTopology) {
        report.appendLine(
            "UAC topology: AC=${topology.audioControlInterfaces.joinToString { "if${it.interfaceNumber}/v${it.audioClassVersion.toString(16)}" }} " +
                "rates=${topology.descriptorSampleRates} clocks=${topology.clockSources.size} " +
                "selectors=${topology.clockSelectors.size} features=${topology.featureUnits.size} " +
                "mixers=${topology.mixerUnits.size} terminals=" +
                "${topology.inputTerminals.size}/${topology.outputTerminals.size}"
        )
        topology.clockSources.forEach {
            report.appendLine(
                "  clock id=${it.id} if=${it.interfaceNumber} attrs=${hex2(it.attributes)} " +
                    "controls=${hex2(it.controls)} readable=${it.supportsFrequencyControl} " +
                    "settable=${it.supportsFrequencySet} terminal=${it.associatedTerminalId}"
            )
        }
        topology.inputTerminals.forEach {
            report.appendLine(
                "  input terminal id=${it.id} type=${hex4(it.terminalType)} clock=${it.clockSourceId} " +
                    "channels=${it.channelCount} config=0x${it.channelConfig.toString(16)}"
            )
        }
        topology.outputTerminals.forEach {
            report.appendLine(
                "  output terminal id=${it.id} type=${hex4(it.terminalType)} source=${it.sourceId} " +
                    "clock=${it.clockSourceId}"
            )
        }
    }

    private fun appendReadOnlyRouteProbe(
        report: StringBuilder,
        connection: UsbDeviceConnection?,
        profile: PioneerMixerProfile
    ) {
        if (connection == null) {
            report.appendLine("route GET probe: FAIL - openDevice returned null")
            return
        }
        report.appendLine("route GET probe (read-only, no mixer settings changed):")
        val outputs = if (profile.routeReadMode == PioneerMixerProfile.RouteReadMode.ALL_OUTPUTS) {
            listOf(0)
        } else {
            (0 until profile.outputCount).toList()
        }
        outputs.forEach { requestOutput ->
            val response = ByteArray(profile.routeReadMode.responseLength)
            val transferred = connection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR,
                PioneerMixerProfile.ROUTE_GET_REQUEST,
                profile.routeReadValue(requestOutput),
                PioneerMixerProfile.ROUTE_INDEX,
                response,
                response.size,
                750
            )
            if (transferred != response.size) {
                report.appendLine(
                    "  FAIL value=${hex4(profile.routeReadValue(requestOutput))} " +
                        "expected=${response.size} transferred=$transferred"
                )
            } else if (profile.routeReadMode == PioneerMixerProfile.RouteReadMode.ALL_OUTPUTS) {
                for (output in 0 until profile.outputCount) appendRouteResult(report, profile, response, output)
            } else {
                appendRouteResult(report, profile, response, requestOutput)
            }
        }
    }

    private fun appendRouteResult(
        report: StringBuilder,
        profile: PioneerMixerProfile,
        response: ByteArray,
        output: Int
    ) {
        val source = profile.decodeRouteSource(response, output)
        val expected = profile.mixWithoutMicSources.getOrNull(output)
        val state = when {
            !profile.isRouteResponseValid(response, output) -> "FAIL output selector mismatch"
            source == null -> "FAIL decode"
            source == expected -> "PASS MIX/REC"
            else -> "INFO other mixer route"
        }
        report.appendLine(
            "  output=${output + 1} source=${source?.let(::hex2) ?: "unknown"} " +
                "expectedMix=${expected?.let(::hex2) ?: "n/a"} result=$state " +
                "raw=${response.joinToString(" ") { hex2(unsigned(it)) }}"
        )
    }

    private fun describeEndpoint(endpoint: UsbEndpoint): String {
        val direction = if (endpoint.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
        val type = when (endpoint.type) {
            UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
            UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
            UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
            UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
            else -> "unknown(${endpoint.type})"
        }
        val sync = if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
            when ((endpoint.attributes shr 2) and 0x03) {
                0 -> "none"
                1 -> "asynchronous"
                2 -> "adaptive"
                else -> "synchronous"
            }
        } else {
            "n/a"
        }
        val usage = if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
            when ((endpoint.attributes shr 4) and 0x03) {
                0 -> "data"
                1 -> "feedback"
                2 -> "implicit-feedback"
                else -> "reserved"
            }
        } else {
            "n/a"
        }
        return "endpoint addr=${hex2(endpoint.address)} dir=$direction type=$type " +
            "attrs=${hex2(endpoint.attributes)} sync=$sync usage=$usage packet=${endpoint.maxPacketSize} " +
            "interval=${endpoint.interval}"
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { unsigned(it).toString(16).padStart(2, '0') }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        unsigned(bytes[offset]) or (unsigned(bytes[offset + 1]) shl 8)

    private fun bcd(value: Int): String =
        "${(value shr 8).toString(16)}.${(value and 0xFF).toString(16).padStart(2, '0')}"

    private fun unsigned(value: Byte): Int = value.toInt() and 0xFF
    private fun hex2(value: Int): String = "0x${value.and(0xFF).toString(16).padStart(2, '0')}"
    private fun hex4(value: Int): String = "0x${value.and(0xFFFF).toString(16).padStart(4, '0')}"

    private inline fun safeString(block: () -> String?): String = try {
        block()?.takeIf { it.isNotBlank() } ?: "unavailable"
    } catch (_: SecurityException) {
        "permission-denied"
    }
}
