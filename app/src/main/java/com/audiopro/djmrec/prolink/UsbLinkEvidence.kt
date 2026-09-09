package com.audiopro.djmrec.prolink

/** Descriptor evidence only. Never claims an interface or sends vendor-specific USB requests. */
object UsbLinkEvidence {
    fun describe(descriptors: ByteArray): String {
        if (descriptors.isEmpty()) return "USB descriptors unavailable. Connect the mixer and allow USB access to inspect its interfaces."
        var offset = 0
        var interfaces = 0
        var network = false
        var vendor = false
        while (offset < descriptors.size) {
            if (offset + 2 > descriptors.size) return "USB descriptor data is incomplete; network capability unknown."
            val size = descriptors[offset].toInt() and 255
            if (size < 2 || offset + size > descriptors.size) return "USB descriptor data is incomplete; network capability unknown."
            if (descriptors[offset + 1].toInt() == 4) {
                if (size < 9) return "USB interface descriptor is incomplete; network capability unknown."
                interfaces++
                val cls = descriptors[offset + 5].toInt() and 255
                val sub = descriptors[offset + 6].toInt() and 255
                val protocol = descriptors[offset + 7].toInt() and 255
                network = network || (cls == 2 && sub in setOf(6, 13, 14)) || (cls == 224 && sub == 1 && protocol == 3)
                vendor = vendor || cls == 255
            }
            offset += size
        }
        return when {
            interfaces == 0 -> "No USB interface descriptors available; network capability unknown."
            network -> "USB advertises a network interface. Check whether Android exposes a matching LAN below; only received Link packets prove protocol access."
            vendor -> "No standard USB network interface advertised. Vendor-specific interfaces exist; a proprietary Link tunnel remains unverified."
            else -> "No standard USB network interface advertised. Use a separate LAN connection for Pro DJ Link; this does not rule out an undocumented tunnel."
        }
    }
}
