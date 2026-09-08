package com.audiopro.djmrec.diagnostics

import kotlin.test.*

class DiagnosticPrivacyTest {
    @Test fun omitsCredentialsDestinationsAndPrivatePaths() {
        val text = DiagnosticPrivacy.redact("access_token=secret stream_key=hidden rtmps://host/live/key /storage/emulated/0/Music/private set.wav")
        listOf("secret", "hidden", "host/live/key", "private set.wav").forEach { assertFalse(text.contains(it)) }
        assertFalse(DiagnosticPrivacy.redact("Authorization: Bearer confidential-value").contains("confidential-value"))
        assertEquals("sample_rate=48000 channel_offset=4", DiagnosticPrivacy.redact("sample_rate=48000 channel_offset=4"))
    }
    @Test fun dropsAudioPayloadAndUnrelatedLogcat() {
        assertFalse(DiagnosticPrivacy.allowLogcat("UsbIsoAudioSource", "raw iso packet #1 dump: abc"))
        assertFalse(DiagnosticPrivacy.allowLogcat("Auth", "token response"))
        assertFalse(DiagnosticPrivacy.allowLogcat("UsbAudioManager", "kernel dmesg"))
        assertTrue(DiagnosticPrivacy.allowLogcat("UsbIsoAudioSource", "decoded pair peaks: 12 54; selected ch 5-6"))
    }
    @Test fun preservesConfigurationButRemovesStringDescriptors() {
        val raw = byteArrayOf(4, 3, 65, 66, 4, 0x24, 1, 2)
        assertEquals("04240102 ", DiagnosticPrivacy.descriptorHex(raw))
        assertTrue(DiagnosticPrivacy.descriptorHex(byteArrayOf(9, 4)).contains("truncated"))
    }
    @Test fun boundsOversizedLogMessages() {
        assertEquals(12000, DiagnosticPrivacy.redact("a".repeat(50000)).length)
    }
}
