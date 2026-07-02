package com.audiopro.djmrec.usb

import android.hardware.usb.UsbDeviceConnection
import android.util.Log

/**
 * EXPERIMENTAL / UNVERIFIED -- deliberately NOT wired into the recording flow anywhere.
 *
 * This is "Path B": the idea of sending a Pioneer vendor-specific USB control transfer to
 * reroute the mixer's "REC OUT" (or an equivalent internal routing matrix entry) onto USB
 * channels 1/2, so that the *existing* AAudio/[com.audiopro.djmrec.audio.AudioEngine.open]
 * path could capture the Master Mix without needing raw isochronous access at all.
 *
 * Pioneer/AlphaTheta has not published this protocol. There is no public documentation of the
 * DJM-A9's vendor bRequest/wValue/wIndex layout for internal routing control, so the values
 * below are PLACEHOLDERS and almost certainly a no-op (or a harmless STALL) on real hardware
 * exactly as written -- do not expect this to work out of the box.
 *
 * To make this real:
 *  1. Connect the DJM-A9 to a PC running Pioneer's own mixer control software (if one exists
 *     for this model) or rekordbox, with USB traffic capture running (Wireshark + USBPcap on
 *     Windows, or usbmon on Linux).
 *  2. Change the routing/rec-out assignment from the software and see exactly which control
 *     transfer(s) go out -- bmRequestType, bRequest, wValue, wIndex, and any data stage.
 *  3. Replace the UNVERIFIED_* constants below with the values you observed.
 *  4. Verify by recording a WAV afterwards and confirming channels 1/2 actually contain the
 *     Master Mix, not silence or some other channel pair -- a successful `controlTransfer()`
 *     return value only proves the device ACKed the request, not that it did what you assume.
 *
 * Until that reverse-engineering work is done, [UsbIsoAudioSource] (the libusb raw
 * isochronous capture path, reachable via [UsbAudioManager.openIsoCaptureHandle] +
 * `AudioEngine.openUsbIso`) is the only verified way this app reaches the Master Mix pair.
 */
object PioneerVendorControl {
    private const val TAG = "PioneerVendorControl"

    // --- UNVERIFIED PLACEHOLDERS -- see class doc before touching anything below. ---
    /** Host-to-device, vendor-specific, device recipient (standard USB bmRequestType layout). */
    private const val REQUEST_TYPE_VENDOR_OUT = 0x40
    private const val UNVERIFIED_REQUEST_REROUTE_REC_OUT = 0x00
    private const val UNVERIFIED_VALUE = 0x0000
    private const val UNVERIFIED_INDEX = 0x0000

    /**
     * Best-effort, unverified attempt to send the placeholder vendor control transfer above.
     *
     * @return true only if the transfer completed without a USB-layer error -- this does NOT
     *   mean the mixer's routing actually changed. Sending an unrecognized vendor request is
     *   ordinarily harmless (most USB devices just STALL the control endpoint and ignore it),
     *   but that is not a hardware guarantee for every device/firmware, which is exactly why
     *   this function is not called from anywhere in the app by default.
     */
    fun tryRerouteRecOutToUsb12(connection: UsbDeviceConnection): Boolean {
        Log.w(TAG, "tryRerouteRecOutToUsb12: sending an UNVERIFIED vendor control transfer -- see class doc")
        val result = connection.controlTransfer(
            REQUEST_TYPE_VENDOR_OUT,
            UNVERIFIED_REQUEST_REROUTE_REC_OUT,
            UNVERIFIED_VALUE,
            UNVERIFIED_INDEX,
            null,
            0,
            1000
        )
        Log.w(
            TAG,
            "tryRerouteRecOutToUsb12: controlTransfer returned $result " +
                "(>= 0 only means the transfer completed, NOT that it changed anything on the mixer)"
        )
        return result >= 0
    }
}
