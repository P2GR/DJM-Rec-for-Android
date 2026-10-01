package com.audiopro.djmrec.audio

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [notice] explains an automatic stop (e.g. low storage or silence); null for a normal save.
 * [alsoSaved] names an extra file written with the set, such as the MP3 copy.
 * [tracksSaved] multitrack files written next to the set, in [tracksFolder].
 */
data class SavedRecording(
    val uri: Uri,
    val name: String,
    val durationMillis: Long,
    val notice: String? = null,
    val alsoSaved: String? = null,
    val tracksSaved: Int = 0,
    val tracksFolder: String? = null
)

/** Process-owned UI events. Capture never depends on an Activity remaining alive. */
class SessionEvents {
    val closeRequested = MutableStateFlow(false)
    val lastSaved = MutableStateFlow<SavedRecording?>(null)
}
