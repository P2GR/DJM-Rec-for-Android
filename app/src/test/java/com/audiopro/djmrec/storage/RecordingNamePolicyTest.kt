package com.audiopro.djmrec.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RecordingNamePolicyTest {
    @Test fun preservesExtension() {
        assertEquals("Club night.wav", RecordingNamePolicy.displayName(" Club night ", "wav"))
        assertEquals("Club night.flac", RecordingNamePolicy.displayName("Club night.flac", "flac"))
    }
    @Test fun rejectsPathsAndEmptyNames() {
        listOf("", " ", ".", "..", "../mix", "folder\\set", "line\nfeed").forEach {
            assertFailsWith<IllegalArgumentException> { RecordingNamePolicy.displayName(it, "wav") }
        }
    }
}
