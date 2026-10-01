package com.audiopro.djmrec.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultitrackLayoutTest {
    @Test
    fun `wire channels split into stereo pairs with a trailing mono track`() {
        assertEquals(
            listOf(TrackChannels(0, 0, 2), TrackChannels(1, 2, 2), TrackChannels(2, 4, 1)),
            MultitrackLayout.tracks(5)
        )
        assertEquals(6, MultitrackLayout.tracks(12).size)
        assertEquals("USB 11/12", MultitrackLayout.tracks(12).last().usbLabel)
        assertEquals("USB 5", MultitrackLayout.tracks(5).last().usbLabel)
    }

    @Test
    fun `layout is capped at the native track bus size`() {
        val tracks = MultitrackLayout.tracks(40)
        assertEquals(MultitrackLayout.MAX_TRACKS, tracks.size)
        assertEquals(30, tracks.last().firstChannel)
    }

    @Test
    fun `stereo and mono devices have nothing to record next to the master`() {
        assertFalse(MultitrackLayout.isAvailable(1))
        assertFalse(MultitrackLayout.isAvailable(2))
        assertTrue(MultitrackLayout.isAvailable(3))
    }

    @Test
    fun `master track follows the master pair offset`() {
        val tracks = MultitrackLayout.tracks(10)
        assertEquals(4, MultitrackLayout.masterTrack(8, tracks))
        assertEquals(0, MultitrackLayout.masterTrack(0, tracks))
        assertEquals(-1, MultitrackLayout.masterTrack(-1, tracks))
        assertEquals(-1, MultitrackLayout.masterTrack(3, tracks))
    }

    @Test
    fun `native levels map to tracks and a mono track mirrors its channel`() {
        val raw = floatArrayOf(
            -6f, -12f, 0f, // ch1
            -7f, -13f, 1f, // ch2 clipped
            -20f, -30f, 0f // ch3
        )
        val levels = MultitrackLayout.trackLevels(raw, MultitrackLayout.tracks(3))
        assertEquals(ChannelLevel(-6f, -12f, false), levels[0].left)
        assertEquals(ChannelLevel(-7f, -13f, true), levels[0].right)
        assertEquals(levels[1].left, levels[1].right)
        assertEquals(-20f, levels[1].left.peakDb)
    }

    @Test
    fun `missing level data reads as silence`() {
        val levels = MultitrackLayout.trackLevels(FloatArray(0), MultitrackLayout.tracks(4))
        assertEquals(-60f, levels[1].right.peakDb)
    }

    @Test
    fun `storage estimate adds every armed track channel to the master`() {
        val tracks = MultitrackLayout.tracks(5)
        // 96 kHz, 24-bit: master 2 ch + stereo track 2 ch + mono track 1 ch = 5 ch * 3 bytes.
        assertEquals(96_000L * 5 * 3, MultitrackLayout.bytesPerSecond(96_000, 24, 2, listOf(tracks[1], tracks[2])))
        assertEquals(288_000L, MultitrackLayout.bytesPerSecond(48_000, 24, 2, emptyList()))
    }
}
