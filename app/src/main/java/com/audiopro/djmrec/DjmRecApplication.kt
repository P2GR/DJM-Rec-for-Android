package com.audiopro.djmrec

import android.app.Application
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

    lateinit var usbAudioManager: UsbAudioManager
        private set

    private val _recoveryNotice = MutableStateFlow<String?>(null)
    val recoveryNotice: StateFlow<String?> = _recoveryNotice.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        val recovery = RecordingOutputManager.recoverInterrupted(this)
        if (recovery.hasWork) _recoveryNotice.value = recovery.message
        usbAudioManager = UsbAudioManager(this)
        usbAudioManager.start()
    }

    fun dismissRecoveryNotice() {
        _recoveryNotice.value = null
    }
}
