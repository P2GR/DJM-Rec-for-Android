package com.audiopro.djmrec.diagnostics

import com.audiopro.djmrec.usb.UsbAudioDeviceInfo
import kotlin.test.*

class MixerDiagnosticReportTest {
    @Test fun knownMixerIncludesNameAndProfileContract() {
        val device = UsbAudioDeviceInfo("usb-test", "DJM-A9", 0x2b73, 0x003c,
            0, 1, 0x82, 1024, 12, 24, 3, listOf(48000, 96000))
        val text = MixerDiagnosticReport.selected(device)
        listOf("Detected: DJM-A9", "profile=DJM-A9", "total channels=12", "48.0 kHz", "96.0 kHz",
            "Playback keepalive=true", "default USB offset=8").forEach { assertContains(text, it) }
    }
    @Test fun unknownDeviceNeverInheritsAMixerContractOrGuessedRates() {
        assertContains(MixerDiagnosticReport.identity("Unlisted mixer", 0x1234, 0x5678), "UNKNOWN")
        assertContains(MixerDiagnosticReport.rates(emptyList()), "unknown")
    }
    @Test fun reportsAdvertisedUac1ChannelsAndRatesWithoutHardware() {
        val raw = intArrayOf(9,4,1,1,1,1,2,0,0, 7,0x24,1,1,1,1,0,
            11,0x24,2,1,8,3,24,1,0x80,0xbb,0, 7,5,0x81,1,0x20,1,1).map { it.toByte() }.toByteArray()
        val text = MixerDiagnosticReport.capabilities(raw).joinToString()
        assertContains(text, "total channels=8")
        assertContains(text, "48.0 kHz")
        assertContains(text, "capture=true")
        assertFalse(text.contains("96.0 kHz"))
        assertContains(MixerDiagnosticReport.capabilities(byteArrayOf()).joinToString(), "No standard PCM")
    }
}
