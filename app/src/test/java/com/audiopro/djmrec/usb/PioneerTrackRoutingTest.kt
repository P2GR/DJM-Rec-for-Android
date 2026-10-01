package com.audiopro.djmrec.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PioneerTrackRoutingTest {
    /**
     * Per-output capture options from the Linux kernel's sound/usb/mixer_quirks.c
     * (`snd_djm_opts_<model>_capN`, low byte of each wValue). The app's catalogs must only
     * offer sources the mixer firmware actually accepts on that output.
     */
    private val kernelA9 = listOf(
        listOf(0x07, 0x08, 0x09, 0x0a, 0x0e, 0x11, 0x12, 0x13, 0x14, 0x31, 0x32, 0x33, 0x34),
        listOf(0x01, 0x02, 0x03, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0e),
        listOf(0x01, 0x02, 0x03, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0e),
        listOf(0x01, 0x02, 0x03, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0e),
        listOf(0x01, 0x02, 0x03, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0e)
    )
    private val kernel750Mk2 = List(4) { listOf(0x00, 0x02, 0x03, 0x06, 0x07, 0x08, 0x09, 0x0a) } +
        listOf(listOf(0x07, 0x08, 0x09, 0x0a, 0x11, 0x12, 0x13, 0x14))

    @Test
    fun `catalogs only offer sources the kernel lists for each output`() {
        mapOf(
            PioneerMixerProfile.DJM_A9 to kernelA9,
            PioneerMixerProfile.DJM_750MK2 to kernel750Mk2
        ).forEach { (profile, kernel) ->
            val catalog = PioneerTrackRouting.catalogFor(profile)!!
            assertEquals(profile.outputCount, catalog.outputs.size, profile.displayName)
            catalog.outputs.forEachIndexed { output, codes ->
                assertTrue(kernel[output].containsAll(codes), "${profile.displayName} output ${output + 1}: $codes")
            }
        }
    }

    @Test
    fun `presets put the mix on the master slot and every channel post-fader`() {
        listOf(PioneerMixerProfile.DJM_A9, PioneerMixerProfile.DJM_750MK2).forEach { profile ->
            val catalog = PioneerTrackRouting.catalogFor(profile)!!
            assertEquals(PioneerTrackRouting.REC_OUT, catalog.preset[catalog.masterOutput])
            catalog.preset.forEachIndexed { output, code ->
                assertTrue(code in catalog.outputs[output], "${profile.displayName} preset for output ${output + 1}")
                if (output != catalog.masterOutput) assertEquals(PioneerTrackRouting.POST_FADER, code)
            }
        }
        assertEquals(0, PioneerTrackRouting.catalogFor(PioneerMixerProfile.DJM_A9)!!.masterOffset)
        assertEquals(8, PioneerTrackRouting.catalogFor(PioneerMixerProfile.DJM_750MK2)!!.masterOffset)
    }

    @Test
    fun `mixers whose routes cannot be read back get no catalog`() {
        listOf(PioneerMixerProfile.DJM_V10, PioneerMixerProfile.DJM_S11, PioneerMixerProfile.DJM_450).forEach {
            assertEquals(PioneerMixerProfile.RouteReadMode.NONE, it.routeReadMode)
            assertNull(PioneerTrackRouting.catalogFor(it))
        }
        assertNull(PioneerTrackRouting.catalogFor(PioneerMixerProfile.DJM_V5)) // no kernel table
        assertNull(PioneerTrackRouting.catalogFor(PioneerMixerProfile.DJM_900NXS2)) // GET is static
        assertNull(PioneerTrackRouting.catalogFor(null))
    }

    @Test
    fun `per-channel sources are named after the channel of their output`() {
        val a9 = PioneerTrackRouting.catalogFor(PioneerMixerProfile.DJM_A9)!!
        assertEquals("CH1 Post-fader", PioneerTrackRouting.options(a9, 1).first().label)
        assertEquals("CH4 Phono", PioneerTrackRouting.source(PioneerTrackRouting.PHONO, 4).label)
        val mk2 = PioneerTrackRouting.catalogFor(PioneerMixerProfile.DJM_750MK2)!!
        assertEquals("CH1 Post-fader", PioneerTrackRouting.options(mk2, 0).first().label)
        assertEquals("Rec Out", PioneerTrackRouting.options(mk2, 4).first().label)
        assertEquals("CH3 Post-fader", PioneerTrackRouting.source(0x13, null).label)
        assertEquals("CH2 Pre-fader", PioneerTrackRouting.source(0x32, null).label)
        assertEquals("Source 0x42", PioneerTrackRouting.source(0x42, null).label)
        assertTrue(PioneerTrackRouting.options(a9, 9).isEmpty())
    }
}
