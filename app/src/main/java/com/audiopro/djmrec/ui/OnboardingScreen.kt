package com.audiopro.djmrec.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.audiopro.djmrec.DjmRecApplication
import com.audiopro.djmrec.ui.theme.AccentAmber
import com.audiopro.djmrec.ui.theme.AccentGreen
import com.audiopro.djmrec.ui.theme.DjmRecMotion
import com.audiopro.djmrec.ui.theme.rememberReducedMotion
import com.audiopro.djmrec.ui.theme.SurfaceDark
import com.audiopro.djmrec.ui.theme.SurfaceVariantDark
import com.audiopro.djmrec.ui.theme.TextPrimary
import com.audiopro.djmrec.ui.theme.TextSecondary

/**
 * First-run setup stepper. Collects every permission DJM REC needs (microphone, notifications,
 * camera), the two special-access grants that only Android Settings can give (Do Not Disturb
 * access and battery-optimisation exclusion for background capture), and finally the per-device
 * USB access prompt for the mixer.
 *
 * The stepper is hand-rolled on Material 3 to match the app's existing dark styling; each step
 * is either `required` (Continue stays disabled until granted, with "Skip for now" as the escape
 * hatch) or recommended. Progress persists via MainViewModel so this screen never shows twice.
 */
private enum class OnboardingStep(
    val title: String,
    val detail: String,
    val icon: ImageVector,
    val required: Boolean,
    val actionLabel: String?
) {
    WELCOME(
        title = "Welcome to DJM REC",
        detail = "Record studio-quality sets straight from your DJ mixer over USB. " +
            "A few quick steps grant the permissions DJM REC needs. You can change all of them " +
            "later in Android settings.",
        icon = Icons.Filled.GraphicEq,
        required = false,
        actionLabel = null
    ),
    MICROPHONE(
        title = "Microphone & audio access",
        detail = "Android requires microphone access to capture audio from USB audio interfaces " +
            "and to show live levels. Nothing is recorded until you press Record, and your takes " +
            "never leave the device.",
        icon = Icons.Filled.Mic,
        required = true,
        actionLabel = "Allow microphone"
    ),
    NOTIFICATIONS(
        title = "Recording notification",
        detail = "A persistent notification keeps capture alive while your screen is off and shows " +
            "recording status with Save & close. Without it Android 13+ can silently stop " +
            "recording during a long set.",
        icon = Icons.Filled.Notifications,
        required = true,
        actionLabel = "Allow notifications"
    ),
    CAMERA(
        title = "Camera for live video",
        detail = "Optional. Only used if you stream a camera angle while going live. " +
            "Recording is unaffected if you skip this.",
        icon = Icons.Filled.Videocam,
        required = false,
        actionLabel = "Allow camera"
    ),
    DND(
        title = "Do Not Disturb access",
        detail = "DJM REC can put your phone into Do Not Disturb while you record so notification " +
            "sounds can't leak into your set. Android only allows this through special access " +
            "called Notification policy access.",
        icon = Icons.Filled.DoNotDisturbOn,
        required = false,
        actionLabel = "Open DND access settings"
    ),
    BACKGROUND(
        title = "Uninterrupted background capture",
        detail = "Long sets record with the screen off. Allow background usage (battery " +
            "optimisation exclusion) so Android can't sleep the capture mid-recording.",
        icon = Icons.Filled.BatterySaver,
        required = false,
        actionLabel = "Allow background usage"
    ),
    USB(
        title = "USB mixer access",
        detail = "DJM REC reads audio from your mixer's USB interface (local USB audio devices). " +
            "When a mixer is connected, Android shows a USB access prompt \u2014 choose Allow, and " +
            "tick \"always use\" to skip future prompts.",
        icon = Icons.Filled.Usb,
        required = false,
        actionLabel = "Allow USB access"
    )
}

private class GrantState(
    val microphone: Boolean,
    val notifications: Boolean,
    val camera: Boolean,
    val dnd: Boolean,
    val background: Boolean
) {
    fun isGranted(step: OnboardingStep): Boolean = when (step) {
        OnboardingStep.WELCOME -> true
        OnboardingStep.MICROPHONE -> microphone
        OnboardingStep.NOTIFICATIONS -> notifications
        OnboardingStep.CAMERA -> camera
        OnboardingStep.DND -> dnd
        OnboardingStep.BACKGROUND -> background
        OnboardingStep.USB -> true // Per-device prompt; handled by its own step content.
    }
}

private fun hasRuntimePermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun currentGrants(context: Context): GrantState = GrantState(
    microphone = hasRuntimePermission(context, Manifest.permission.RECORD_AUDIO),
    notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        hasRuntimePermission(context, Manifest.permission.POST_NOTIFICATIONS),
    camera = hasRuntimePermission(context, Manifest.permission.CAMERA),
    dnd = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        .isNotificationPolicyAccessGranted,
    background = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .isIgnoringBatteryOptimizations(context.packageName)
)

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val steps = OnboardingStep.entries
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }

    // Bumped whenever a grant may have changed (permission/settings results, activity resume),
    // so the status chips re-read the real system state instead of trusting a cached flag.
    var refreshTick by remember { mutableIntStateOf(0) }
    val grants = remember(refreshTick) { currentGrants(context) }

    // Re-check after returning from Android Settings (DND access, battery optimisation).
    DisposableEffect(context) {
        val activity = context as? ComponentActivity
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshTick++ }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshTick++ }

    val step = steps[stepIndex]
    val isLast = stepIndex == steps.lastIndex
    val stepGranted = grants.isGranted(step)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        StepHeader(stepIndex = stepIndex, stepCount = steps.size)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center
        ) {
            val reducedMotion = rememberReducedMotion()
            AnimatedContent(
                targetState = stepIndex,
                transitionSpec = {
                    DjmRecMotion.pageTransform(forward = targetState >= initialState, vertical = true, reduced = reducedMotion)
                },
                label = "onboardingStep",
                modifier = Modifier.fillMaxWidth()
            ) { index ->
            // Shadow the outer locals so the outgoing copy fades its own step content.
            val step = steps[index]
            val stepGranted = grants.isGranted(step)
            val isLast = index == steps.lastIndex
            Column(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp) {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = SurfaceVariantDark,
                            modifier = Modifier.size(72.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = step.icon,
                                    contentDescription = null,
                                    tint = AccentGreen,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                        Text(
                            text = step.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = TextPrimary,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = step.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        if (step == OnboardingStep.NOTIFICATIONS && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Text(
                                text = "Notifications don't need a runtime permission on this Android version.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }

                        GrantStatusChip(granted = stepGranted, step = step)

                        when (step) {
                            OnboardingStep.MICROPHONE,
                            OnboardingStep.NOTIFICATIONS,
                            OnboardingStep.CAMERA -> {
                                val permission = when (step) {
                                    OnboardingStep.MICROPHONE -> Manifest.permission.RECORD_AUDIO
                                    OnboardingStep.NOTIFICATIONS -> Manifest.permission.POST_NOTIFICATIONS
                                    else -> Manifest.permission.CAMERA
                                }
                                if (!stepGranted && step.actionLabel != null) {
                                    OutlinedButton(
                                        onClick = { runtimeLauncher.launch(arrayOf(permission)) },
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    ) { Text(step.actionLabel) }
                                }
                            }

                            OnboardingStep.DND -> {
                                if (!stepGranted) OutlinedButton(
                                    onClick = {
                                        settingsLauncher.launch(
                                            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                ) { Text(step.actionLabel ?: "Open settings") }
                            }

                            OnboardingStep.BACKGROUND -> {
                                if (!stepGranted) OutlinedButton(
                                    onClick = {
                                        val request = Intent(
                                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        runCatching { settingsLauncher.launch(request) }
                                            .onFailure {
                                                settingsLauncher.launch(
                                                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                                )
                                            }
                                    },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                ) { Text(step.actionLabel ?: "Allow background usage") }
                            }

                            OnboardingStep.USB -> UsbAccessContent()

                            OnboardingStep.WELCOME -> Unit
                        }
                    }
                }

                if (isLast) PendingSummary(grants = grants)
            }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (stepIndex > 0) {
                TextButton(onClick = { stepIndex-- }) { Text("Back") }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Spacer(Modifier.weight(1f))
            if (!stepGranted && step != OnboardingStep.USB && step != OnboardingStep.WELCOME) {
                TextButton(onClick = { stepIndex++ }) { Text("Skip for now") }
            }
            Button(
                onClick = {
                    if (isLast) onFinished() else stepIndex++
                },
                enabled = stepGranted || !step.required,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text(if (isLast) "Start using DJM REC" else "Continue") }
        }
    }
}

@Composable
private fun StepHeader(stepIndex: Int, stepCount: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "DJM REC SETUP",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        Text(
            text = "Step ${stepIndex + 1} of $stepCount",
            style = MaterialTheme.typography.labelSmall,
            color = AccentGreen,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        LinearProgressIndicator(
            progress = { (stepIndex + 1) / stepCount.toFloat() },
            modifier = Modifier.fillMaxWidth(),
            color = AccentGreen,
            trackColor = SurfaceVariantDark
        )
    }
}

@Composable
private fun GrantStatusChip(granted: Boolean, step: OnboardingStep) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = if (granted) AccentGreen else AccentAmber,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = when {
                granted -> "Granted"
                step == OnboardingStep.NOTIFICATIONS && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> "Not required"
                step.required -> "Required \u2014 grant or skip to continue"
                else -> "Recommended"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (granted) AccentGreen else AccentAmber,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
}

/**
 * USB access is per-device (Android shows its own prompt), so this step reports live device
 * state from [com.audiopro.djmrec.usb.UsbAudioManager] and triggers its permission/scan flow
 * instead of a single runtime grant.
 */
@Composable
private fun UsbAccessContent() {
    val context = LocalContext.current
    val usbAudioManager = (context.applicationContext as DjmRecApplication).usbAudioManager
    val inputs by usbAudioManager.inputs.collectAsState()
    val notice by usbAudioManager.connectionNotice.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (inputs.isEmpty()) {
            Text(
                text = "No USB device is connected right now. Plug in your mixer with a data " +
                    "(not charge-only) cable \u2014 Android will ask for USB access when it appears.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        } else {
            inputs.forEach { input ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (input.hasPermission) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
                        contentDescription = null,
                        tint = if (input.hasPermission) AccentGreen else AccentAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = input.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = when {
                                input.hasPermission -> "USB access allowed"
                                input.captureCandidate -> "Waiting for USB access"
                                else -> "Connected, but exposes no audio input"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
        notice?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        val needsPrompt = inputs.any { !it.hasPermission }
        OutlinedButton(
            onClick = { usbAudioManager.scanForConnectedMixer("onboarding-usb-step") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) { Text(if (needsPrompt) "Allow USB access" else "Check for mixer") }
    }
}

/** Compact reminder of still-missing grants, shown on the final step. */
@Composable
private fun PendingSummary(grants: GrantState) {
    val pending = buildList {
        if (!grants.microphone) add("Microphone")
        if (!grants.notifications) add("Notifications")
        if (!grants.dnd) add("Do Not Disturb access")
        if (!grants.background) add("Background usage")
    }
    if (pending.isEmpty()) return
    Surface(shape = RoundedCornerShape(20.dp), color = SurfaceDark) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = AccentAmber,
                modifier = Modifier.size(18.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Still not granted",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Text(
                    text = pending.joinToString(" \u00b7 ") +
                        ". Grant these later in Android settings for full functionality.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}
