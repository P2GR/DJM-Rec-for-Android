package com.audiopro.djmrec.usb

/** Identities from official Windows audio-driver INFs. Wire formats must come from USB descriptors.
 * These are recognition profiles, not proprietary DJM routing contracts or hardware certification.
 */
enum class AllInOneProfile(val displayName: String, val productId: Int, val recordChannelOffset: Int?) {
    XDJ_XZ("XDJ-XZ", 0x002D, 4),
    XDJ_RX3("XDJ-RX3", 0x003D, null),
    OPUS_QUAD("OPUS-QUAD", 0x0043, 0),
    OMNIS_DUO("OMNIS-DUO", 0x0048, 0),
    XDJ_AZ("XDJ-AZ", 0x004A, 0);

    val setupHint: String get() = if (this == XDJ_RX3)
        "RX3's documented USB setup has no recording input. Use MASTER REC to USB storage, or connect its analog output through a USB audio interface."
    else "Use the rear PC/Mac USB port and a data cable. Auto selects the documented master-return pair. Check signal before recording; Android hardware validation is pending. USB-A storage and Link Export are not audio inputs."

    companion object {
        fun find(vendorId: Int, productId: Int): AllInOneProfile? =
            if (vendorId == 0x2B73) entries.firstOrNull { it.productId == productId } else null
    }
}
