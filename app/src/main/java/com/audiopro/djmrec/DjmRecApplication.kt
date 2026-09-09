package com.audiopro.djmrec

import android.app.Application
import com.audiopro.djmrec.diagnostics.RemoteDiagnostics
import kotlinx.coroutines.*
import com.audiopro.djmrec.storage.RecordingOutputManager
import com.audiopro.djmrec.usb.UsbAudioManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns app-wide singletons. [UsbAudioManager] must observe attach/detach for the whole app
 * lifetime (not just while an Activity is visible), since a mixer could be plugged in while
 * the app is backgrounded and the user expects the notification/UI to reflect it immediately
 * when they return.
 */
class DjmRecApplication : Application() {
    val youtubeCoordinator = com.audiopro.djmrec.streaming.YouTubeBroadcastCoordinator()
    val sessionEvents = com.audiopro.djmrec.audio.SessionEvents()
    val djLink by lazy { com.audiopro.djmrec.prolink.ProLinkClient(this) }

    lateinit var usbAudioManager: UsbAudioManager
        private set

    private val _recoveryNotice = MutableStateFlow<String?>(null)
    val recoveryNotice: StateFlow<String?> = _recoveryNotice.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        RemoteDiagnostics.start(this)
        val recovery = RecordingOutputManager.recoverInterrupted(this)
        if (recovery.hasWork) _recoveryNotice.value = recovery.message
        usbAudioManager = UsbAudioManager(this)
        usbAudioManager.start()
        val diagnosticsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        diagnosticsScope.launch { usbAudioManager.deviceState.collect { RemoteDiagnostics.device(it) } }
        diagnosticsScope.launch { usbAudioManager.connectionNotice.collect { notice ->
            notice?.let { RemoteDiagnostics.event("UsbConnection", it) }
        } }
        diagnosticsScope.launch { sessionEvents.lastSaved.collect { saved ->
            saved?.let { RemoteDiagnostics.event("RecordingSaved", "Published successfully; duration_ms=${it.durationMillis}") }
        } }
        if (recovery.hasWork) RemoteDiagnostics.event("Recovery", recovery.message)
    }

    fun dismissRecoveryNotice() {
        _recoveryNotice.value = null
    }
}
