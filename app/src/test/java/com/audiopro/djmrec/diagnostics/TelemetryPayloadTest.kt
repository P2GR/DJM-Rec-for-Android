package com.audiopro.djmrec.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelemetryPayloadTest {
    @Test
    fun distinguishesAcceptedFailedAndUnattemptedSetupCommands() {
        val accepted = RemoteDiagnostics.setupTelemetryValues(
            "capture_setup=rate_set_result:3 route_value:778 route_set_result:0 route_readback:unsupported"
        )
        assertEquals(mapOf("rate_set_result" to 3L, "route_value" to 778L, "route_set_result" to 0L), accepted)
        val failed = RemoteDiagnostics.setupTelemetryValues(
            "capture_setup=rate_set_result:-9 route_value:778 route_set_result:-6 route_readback:unsupported"
        )
        assertEquals(-9L, failed["rate_set_result"])
        assertEquals(-6L, failed["route_set_result"])
        val fixedPair = RemoteDiagnostics.setupTelemetryValues(
            "capture_setup=rate_set_result:3 route_value:-1 route_set_result:-999 route_readback:unsupported"
        )
        assertEquals(-999L, fixedPair["route_set_result"])
        assertEquals(emptyMap<String, Long>(), RemoteDiagnostics.setupTelemetryValues("profile=DJM-A9"))
    }

    @Test
    fun mapsExistingDiagnosticCategoriesToStableAnalyticsEvents() {
        assertEquals("usb_connection", RemoteDiagnostics.telemetryEventName("MixerConnection"))
        assertEquals("recording_state", RemoteDiagnostics.telemetryEventName("RecordingState"))
        assertEquals("diagnostic_event", RemoteDiagnostics.telemetryEventName("FutureCategory"))
    }

    @Test
    fun extractsCaptureHealthWithoutIncludingTheRawDiagnosticDump() {
        val values = RemoteDiagnostics.healthTelemetryValues(
            "SILENCE: USB connected; no audible signal on the selected channels",
            """
                running=true
                profile=DJM-450
                sample_rate=requested:48000 opened:48000
                channel_offset=requested:-1 resolved:4
                playback_keepalive=required:true claimed_if:2 transfers:8
                route_fallback_stage=3
                transfers=completed:900 missed:2 empty:3 partial:4 bytes:864000 nonzero_bytes:120 resubmit_failures:0
                channel_activity=pre-gain 1-second peak window; age_ms=42; active_threshold=-60dBFS; approximate snapshot
                USB1=-120.0dBFS(below threshold) USB2=-120.0dBFS(below threshold) USB3=-18.2dBFS(active) USB4=-19.1dBFS(active) USB5=-21.4dBFS(active) USB6=-22.0dBFS(active)
            """.trimIndent()
        )

        assertEquals("SILENCE", values["health_level"])
        assertEquals("DJM-450", values["profile"])
        assertEquals(48_000L, values["opened_rate"])
        assertEquals(-1L, values["requested_channel"])
        assertEquals(4L, values["resolved_channel"])
        assertEquals("auto", values["requested_pair"])
        assertEquals("usb_5_6", values["resolved_pair"])
        assertEquals(1L, values["playback_required"])
        assertEquals(8L, values["playback_transfers"])
        assertEquals("3,4,5,6", values["active_channels"])
        assertEquals(3L, values["loudest_channel"])
        assertEquals(-182L, values["loudest_db_x10"])
        assertEquals(-214L, values["resolved_pair_db_x10"])
        assertEquals(864_000L, values["bytes_received"])
        assertEquals(120L, values["nonzero_bytes"])
        assertEquals(0L, values["resubmit_failures"])
        assertTrue(values.size <= 23, "capture_health must leave room for its two context parameters")
    }
}
