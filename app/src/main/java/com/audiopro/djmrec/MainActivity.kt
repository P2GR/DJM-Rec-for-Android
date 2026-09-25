package com.audiopro.djmrec

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.view.WindowManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.audiopro.djmrec.audio.RecordingState
import com.audiopro.djmrec.ui.MainScreen
import com.audiopro.djmrec.ui.MainViewModel
import com.audiopro.djmrec.ui.OnboardingScreen
import com.audiopro.djmrec.ui.theme.DjmRecTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.ensureLiveMonitoring() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val events = (application as DjmRecApplication).sessionEvents
        events.closeRequested.value = false
        lifecycleScope.launch { events.closeRequested.collect { if (it) finishAndRemoveTask() } }
        intent?.data?.host?.let { viewModel.pendingRoute.value = it }
        // First launch: the onboarding stepper collects every permission itself. Later launches
        // keep the legacy silent re-request in case a required grant was revoked meanwhile.
        if (viewModel.onboardingComplete.value) requestRuntimePermissions()

        setContent {
            DjmRecTheme {
                // A recording must never die because the device dozed off or auto-locked: while
                // recording or paused, keep the device awake on EVERY screen (not just Record).
                // Monitoring only stays awake when the user opted in, and the power-save overlay
                // always needs the screen to remain on to be meaningful. FLAG_KEEP_SCREEN_ON is
                // the supported replacement for the deprecated FULL_WAKE_LOCK and blocks both
                // screen sleep and keyguard auto-lock while this window is visible. If the user
                // locks manually, capture continues in the foreground service (wake lock held).
                val recordingState by viewModel.recordingState.collectAsState()
                val keepScreenOnSetting by viewModel.keepScreenOn.collectAsState()
                val powerSaveActive = viewModel.powerSaveActive.value
                val keepAwake = recordingState is RecordingState.Recording ||
                    recordingState is RecordingState.Paused ||
                    powerSaveActive ||
                    (keepScreenOnSetting && recordingState is RecordingState.Monitoring)
                DisposableEffect(keepAwake) {
                    if (keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }
                val onboardingComplete by viewModel.onboardingComplete.collectAsState()
                if (onboardingComplete) {
                    MainScreen(viewModel = viewModel)
                } else {
                    OnboardingScreen(onFinished = {
                        viewModel.completeOnboarding()
                        viewModel.rescanUsbDevices()
                    })
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setUiVisible(true)
    }

    override fun onStop() {
        viewModel.setUiVisible(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // USB attach can precede Activity creation/resume. Reconcile the framework device list
        // here so monitoring does not depend on opening the Mixer USB picker first.
        viewModel.rescanUsbDevices()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.host?.let { viewModel.pendingRoute.value = it }
        viewModel.rescanUsbDevices()
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        requestPermissions.launch(permissions.toTypedArray())
    }
}
