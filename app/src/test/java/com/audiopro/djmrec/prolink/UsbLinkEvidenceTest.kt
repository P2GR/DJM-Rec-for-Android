package com.audiopro.djmrec.prolink

import kotlin.test.*

class UsbLinkEvidenceTest {
    private fun descriptor(cls: Int, sub: Int, protocol: Int = 0) =
        byteArrayOf(9, 4, 0, 0, 1, cls.toByte(), sub.toByte(), protocol.toByte(), 0)

    @Test fun `audio and vendor interfaces never prove absence of proprietary tunnel`() {
        assertTrue(UsbLinkEvidence.describe(descriptor(1, 2)).contains("undocumented tunnel"))
        assertTrue(UsbLinkEvidence.describe(descriptor(255, 0)).contains("remains unverified"))
    }

    @Test fun `CDC network and RNDIS advertisements remain only candidates`() {
        for (bytes in listOf(descriptor(2, 6), descriptor(2, 13), descriptor(2, 14), descriptor(224, 1, 3))) {
            assertTrue(UsbLinkEvidence.describe(bytes).contains("only received Link packets prove"))
        }
        assertTrue(UsbLinkEvidence.describe(descriptor(1, 3)).contains("No standard USB network"))
    }

    @Test fun `missing and broken descriptors remain unknown and terminate`() {
        assertTrue(UsbLinkEvidence.describe(byteArrayOf()).contains("unavailable"))
        for (bytes in listOf(byteArrayOf(0, 4), byteArrayOf(9, 4, 0), byteArrayOf(2, 4), byteArrayOf(1))) {
            assertTrue(UsbLinkEvidence.describe(bytes).contains("unknown"))
        }
    }
}
