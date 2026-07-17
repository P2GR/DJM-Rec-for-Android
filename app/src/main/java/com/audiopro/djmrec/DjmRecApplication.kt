package com.audiopro.djmrec

import android.app.Application
import com.audiopro.djmrec.usb.UsbAudioManager

/**
 * Owns app-wide singletons. [UsbAudioManager] must observe attach/detach for the whole app
 * lifetime (not just while an Activity is visible), since a mixer could be plugged in while
 * the app is backgrounded and the user expects the notification/UI to reflect it immediately
 * when they return.
 */
class DjmRecApplication : Application() {

    lateinit var usbAudioManager: UsbAudioManager
        private set
    override fun onCreate() {
        super.onCreate()
        usbAudioManager = UsbAudioManager(this)
        usbAudioManager.start()
    }
}
