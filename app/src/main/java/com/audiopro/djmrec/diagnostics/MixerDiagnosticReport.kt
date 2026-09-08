package com.audiopro.djmrec.diagnostics

import com.audiopro.djmrec.usb.*
import java.util.Locale

/** Human-readable evidence, with advertised capabilities kept separate from selected settings. */
internal object MixerDiagnosticReport {
    fun identity(name: String, vendor: Int, product: Int): String {
        val model = PioneerMixerProfile.find(vendor, product)?.displayName
            ?: AllInOneProfile.find(vendor, product)?.displayName ?: name
        return "Detected: $model; USB product=$name; USB=%04X:%04X; profile=%s".format(Locale.ROOT, vendor, product,
            PioneerMixerProfile.find(vendor, product)?.displayName
                ?: AllInOneProfile.find(vendor, product)?.displayName?.plus(" descriptor profile")
                ?: "UNKNOWN / standard USB PCM detection")
    }

    fun rates(rates: List<Int>): String = if (rates.isEmpty()) "unknown (not advertised/queried)"
        else rates.distinct().sorted().joinToString { "${it / 1000.0} kHz" }

    fun capabilities(raw: ByteArray): List<String> {
        val topology = UsbAudioDescriptorParser.parseTopology(raw)
        return buildList {
            add("Advertised streaming interfaces=${topology.audioStreamingInterfaces.size}; " +
                "clock sources=${topology.clockSources.size}; clock selectors=${topology.clockSelectors.size}")
            topology.audioStreamingInterfaces.forEach { stream ->
                add("Advertised: if${stream.interfaceNumber}/alt${stream.alternateSetting}; " +
                    "capture=${stream.isochronousInEndpointAddress != null}; total channels=${stream.channelCount}; " +
                    "PCM=${stream.bitResolution}bit/${stream.subframeSize}bytes; " +
                    "IN endpoint=${stream.isochronousInEndpointAddress}; max packet=${stream.isochronousInMaxPacketSize}; " +
                    "terminal=${stream.terminalLink}; rates=${rates(stream.sampleRates)}")
            }
            topology.clockSources.forEach { clock -> add("Clock: $clock") }
            topology.clockSelectors.forEach { selector -> add("Clock selector: $selector") }
            if (topology.audioStreamingInterfaces.isEmpty())
                add("No standard PCM streaming format parsed. Inspect USB interface inventory and descriptor chunks; do not infer channel count or rates from model name.")
        }
    }

    fun selected(device: UsbAudioDeviceInfo): String = buildString {
        appendLine(identity(device.productName, device.vendorId, device.productId))
        appendLine("Connecting: ${device.profileDescription}; transport=${if (device.requiresIsoCapture) "raw USB" else "Android audio"}; " +
            "if${device.streamingInterfaceNumber}/alt${device.activeAlternateSetting}; endpoint=${device.isochronousInEndpointAddress}; packet=${device.isochronousInMaxPacketSize}")
        appendLine("Selected total channels=${device.channelCount}; PCM=${device.bitResolution}bit/${device.subframeSize}bytes; " +
            "available rates=${rates(device.supportedSampleRates)}; preferred=${device.preferredSampleRate / 1000.0} kHz (actual opened rate follows in CaptureHealth)")
        val profile = device.pioneerMixerProfile
        appendLine("Rate evidence=${when {
            profile?.vendorCaptureSampleRates?.isNotEmpty() == true -> "reviewed profile contract"
            device.topology?.audioStreamingInterfaces?.any { it.interfaceNumber == device.streamingInterfaceNumber && it.alternateSetting == device.activeAlternateSetting && it.sampleRates.isNotEmpty() } == true -> "selected USB interface descriptor"
            else -> "queried clock and/or Android route; may be unavailable"
        }}; Android routed device ID=${device.audioManagerDeviceId}")
        if (profile != null) {
            appendLine("Profile contract: vendor override=${profile.hasVendorCaptureOverride}; " +
                "profile rates=${rates(profile.vendorCaptureSampleRates)}; default USB offset=${profile.defaultCaptureChannelOffset}; " +
                "outputs=${profile.outputCount}; MIX sources=${profile.mixWithoutMicSources}; route read=${profile.routeReadMode}")
            append("Playback keepalive=${profile.requiresPlaybackTraffic}; if${profile.playbackInterface}/alt${profile.playbackAlternateSetting}; " +
                "format evidence=${if (profile.hasVendorCaptureOverride) "reviewed vendor contract matched to endpoint" else "USB descriptors"}")
        } else append("Default master offset=${device.allInOneProfile?.recordChannelOffset ?: 0}; no proprietary DJM routing commands")
    }
}
