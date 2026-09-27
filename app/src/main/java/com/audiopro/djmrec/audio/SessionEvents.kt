package com.audiopro.djmrec.audio

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

/** [notice] explains an automatic stop (e.g. low storage); null for a normal save. */
data class SavedRecording(
    val uri: Uri,
    val name: String,
    val durationMillis: Long,
    val notice: String? = null
)

/** Process-owned UI events. Capture never depends on an Activity remaining alive. */
class SessionEvents {
    val closeRequested = MutableStateFlow(false)
    val lastSaved = MutableStateFlow<SavedRecording?>(null)
}
