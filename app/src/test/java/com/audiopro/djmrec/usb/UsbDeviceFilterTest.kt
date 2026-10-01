package com.audiopro.djmrec.usb

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * res/xml/usb_device_filter.xml decides which devices make Android offer to open the app on
 * plug-in. It must name exactly the AlphaTheta models the app has profiles for -- no generic
 * class or vendor-only entries, which would pop up for headsets, dongles, controllers or CDJs.
 */
class UsbDeviceFilterTest {
    private val entries: List<Element> by lazy {
        val file = listOf("src/main/res/xml/usb_device_filter.xml", "app/src/main/res/xml/usb_device_filter.xml")
            .map(::File).first { it.exists() }
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            .getElementsByTagName("usb-device")
        List(nodes.length) { nodes.item(it) as Element }
    }

    @Test
    fun `every entry names an AlphaTheta vendor and model and nothing broader`() {
        entries.forEach { entry ->
            assertEquals(PioneerMixerProfile.ALPHATHETA_VENDOR_ID, entry.getAttribute("vendor-id").toInt())
            assertTrue("entry without product-id matches too many devices", entry.hasAttribute("product-id"))
            listOf("class", "subclass", "protocol").forEach { attribute ->
                assertTrue("unexpected $attribute filter", !entry.hasAttribute(attribute))
            }
        }
    }

    @Test
    fun `lists exactly the mixers and all-in-ones the app has profiles for`() {
        val filtered = entries.map { it.getAttribute("product-id").toInt() }
        val profiled = PioneerMixerProfile.entries.flatMap { it.productIds } +
            AllInOneProfile.entries.map { it.productId }

        assertEquals("duplicate entries", filtered.size, filtered.toSet().size)
        assertEquals(profiled.toSortedSet(), filtered.toSortedSet())
    }
}
