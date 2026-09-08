package com.audiopro.djmrec.audio

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

data class SavedRecording(val uri: Uri, val name: String, val durationMillis: Long)

/** Process-owned UI events. Capture never depends on an Activity remaining alive. */
class SessionEvents {
    val closeRequested = MutableStateFlow(false)
    val lastSaved = MutableStateFlow<SavedRecording?>(null)
    val markerCount = MutableStateFlow(0)
}
