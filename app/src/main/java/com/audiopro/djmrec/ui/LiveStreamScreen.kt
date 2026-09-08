package com.audiopro.djmrec.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.audiopro.djmrec.BuildConfig
import com.audiopro.djmrec.audio.RecordingState
import com.audiopro.djmrec.streaming.LivePlatform
import com.audiopro.djmrec.streaming.LiveStreamConfig
import com.audiopro.djmrec.streaming.LiveStreamState
import com.audiopro.djmrec.streaming.LiveStreamStatus
import com.audiopro.djmrec.streaming.LiveVideoMode
import com.audiopro.djmrec.streaming.StreamSetupStatus
import com.audiopro.djmrec.streaming.YouTubePrivacy
import com.audiopro.djmrec.streaming.YouTubeBroadcastStatus
import com.audiopro.djmrec.ui.theme.AccentAmber
import com.audiopro.djmrec.ui.theme.AccentGreen
import com.audiopro.djmrec.ui.theme.AccentRed
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LiveStreamScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val recordingState by viewModel.recordingState.collectAsState()
    val liveState by viewModel.liveStreamState.collectAsState()
    val setupState by viewModel.streamSetupState.collectAsState()
    val youtubeBroadcast by viewModel.youtubeBroadcastState.collectAsState()
    var platform by rememberSaveable { mutableStateOf(LivePlatform.YOUTUBE) }
    var serverUrl by rememberSaveable { mutableStateOf(LivePlatform.YOUTUBE.defaultServerUrl) }
    var streamKey by viewModel.liveStreamKey
    var videoMode by rememberSaveable { mutableStateOf(LiveVideoMode.BACK_CAMERA) }
    var step by rememberSaveable { mutableStateOf(0) }
    var showBroadcastOptions by rememberSaveable { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    var authorizing by remember { mutableStateOf(false) }
    var portrait by rememberSaveable { mutableStateOf(false) }
    val artworkPreferences = remember(context) {
        context.getSharedPreferences("livestream", Context.MODE_PRIVATE)
    }
    var artworkUri by rememberSaveable {
        mutableStateOf(artworkPreferences.getString("custom_artwork_uri", null))
    }
    var localError by remember { mutableStateOf<String?>(null) }
    var pendingCameraConfig by remember { mutableStateOf<LiveStreamConfig?>(null) }
    var youtubeTitle by rememberSaveable {
        mutableStateOf("DJ Set ${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}")
    }
    var youtubePrivacy by rememberSaveable { mutableStateOf(YouTubePrivacy.UNLISTED) }
    var destinationUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var openedVerificationUrl by remember { mutableStateOf<String?>(null) }

    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { localError = "No browser is available" }
    }

    fun shareBroadcast(url: String) {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, youtubeTitle)
            putExtra(Intent.EXTRA_TEXT, "Watch my DJ set live: $url")
        }
        runCatching { context.startActivity(Intent.createChooser(share, "Share broadcast")) }
            .onFailure { localError = "No sharing app is available" }
    }

    fun acceptYouTubeToken(token: String?) {
        authorizing = false
        if (token.isNullOrBlank()) {
            viewModel.setStreamSetupError(LivePlatform.YOUTUBE, "Google did not return an access token")
        } else {
            viewModel.prepareYouTubeDestination(token, youtubeTitle, youtubePrivacy)
        }
    }

    val googleAuthorizationClient = remember(context) { Identity.getAuthorizationClient(context) }
    val googleAuthorization = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        try {
            acceptYouTubeToken(
                googleAuthorizationClient.getAuthorizationResultFromIntent(result.data).accessToken
            )
        } catch (error: ApiException) {
            authorizing = false
            viewModel.setStreamSetupError(
                LivePlatform.YOUTUBE,
                googleAuthorizationError(context, error)
            )
        }
    }

    fun connectYouTube() {
        val activity = context as? Activity
        if (activity == null) {
            viewModel.setStreamSetupError(LivePlatform.YOUTUBE, "YouTube authorization is unavailable")
            return
        }
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/youtube.force-ssl")))
            .build()
        authorizing = true
        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent != null) {
                        googleAuthorization.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                    } else {
                        authorizing = false
                        viewModel.setStreamSetupError(LivePlatform.YOUTUBE, "Google could not open sign-in. Try again.")
                    }
                } else {
                    acceptYouTubeToken(result.accessToken)
                }
            }
            .addOnFailureListener { error ->
                authorizing = false
                viewModel.setStreamSetupError(
                    LivePlatform.YOUTUBE,
                    if (error is ApiException) googleAuthorizationError(context, error)
                    else error.localizedMessage ?: "Google authorization failed"
                )
            }
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val config = pendingCameraConfig
        pendingCameraConfig = null
        if (granted && config != null) {
            viewModel.startLiveStream(config)
        } else if (!granted) {
            localError = "Camera permission was denied. Choose Custom artwork or allow Camera."
        }
    }

    val artworkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            artworkUri?.takeIf { it != uri.toString() }?.let { previous ->
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(previous),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            artworkUri = uri.toString()
            artworkPreferences.edit().putString("custom_artwork_uri", uri.toString()).apply()
            localError = null
        }
    }

    val captureReady = recordingState is RecordingState.Monitoring ||
        recordingState is RecordingState.Recording || recordingState is RecordingState.Paused

    LaunchedEffect(platform) {
        viewModel.cancelStreamSetup()
        destinationUrl = null
        openedVerificationUrl = null
    }

    LaunchedEffect(setupState.credentials) {
        val credentials = setupState.credentials ?: return@LaunchedEffect
        if (credentials.platform == platform) {
            serverUrl = credentials.serverUrl
            streamKey = credentials.streamKey
            destinationUrl = credentials.destinationUrl
            localError = null
            viewModel.consumeStreamCredentials()
            step = 1
        }
    }

    LaunchedEffect(setupState.verificationUrl) {
        val url = setupState.verificationUrl ?: return@LaunchedEffect
        if (openedVerificationUrl != url) {
            openedVerificationUrl = url
            openUrl(url)
        }
    }

    if (liveState.isActive && liveState.usesCamera) {
        CameraLiveScreen(viewModel)
        return
    }

    LaunchedEffect(youtubeBroadcast.status) {
        if (platform == LivePlatform.YOUTUBE && youtubeBroadcast.status == YouTubeBroadcastStatus.COMPLETE) {
            streamKey = ""
            destinationUrl = null
            step = 0
        }
    }

    val destinationReady = runCatching {
        LiveStreamConfig(platform, serverUrl, streamKey, videoMode, portrait).endpoint()
    }.isSuccess
    val pictureReady = videoMode != LiveVideoMode.ARTWORK || !artworkUri.isNullOrBlank()
    val health by viewModel.recordingHealth.collectAsState()
    val device by viewModel.deviceState.collectAsState()

    fun goLive() {
        val config = LiveStreamConfig(platform, serverUrl, streamKey, videoMode, portrait, artworkUri)
        localError = runCatching { config.endpoint() }.exceptionOrNull()?.message
        if (localError != null) { step = 0; return }
        if (!pictureReady) { step = 1; return }
        if (videoMode != LiveVideoMode.ARTWORK && ContextCompat.checkSelfPermission(context,
                Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingCameraConfig = config
            cameraPermission.launch(Manifest.permission.CAMERA)
        } else viewModel.startLiveStream(config)
    }

    // The key remains memory-only so a local encoder/camera failure can be retried.
    // Changing provider clears it; no draft key is written to preferences or saved state.
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (liveState.isActive) {
                LiveStatusCard(liveState, captureReady)
                Text("Your artwork and mixer audio are streaming. You can keep recording locally.")
                youtubeBroadcast.watchUrl?.let { url ->
                    OutlinedButton(onClick = { shareBroadcast(url) }) { Text("Share broadcast") }
                }
            } else {
                Text("Share your set", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Connect a destination, choose the picture, then check your mixer.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Connect", "Picture", "Go live").forEachIndexed { index, label ->
                        FilterChip(selected = step == index,
                            onClick = { step = index }, enabled = index == 0 || destinationReady && (index == 1 || pictureReady),
                            label = { Text("${index + 1} $label") })
                    }
                }
                when (step) {
                    0 -> {
                        Text("Where are you streaming?", style = MaterialTheme.typography.titleMedium)
                        LivePlatform.entries.forEach { option ->
                            OutlinedButton(onClick = {
                                if (platform != option) {
                                    viewModel.cancelStreamSetup()
                                    platform = option; serverUrl = option.defaultServerUrl
                                    streamKey = ""; destinationUrl = null; localError = null
                                }
                            }, enabled = !setupState.isBusy && !authorizing, modifier = Modifier.fillMaxWidth()) {
                                Text((if (platform == option) "Selected: " else "") + option.label)
                            }
                        }
                        when (platform) {
                            LivePlatform.YOUTUBE -> {
                                Text("Sign in with Google. DJM Rec creates the broadcast and fills in the stream address for you.")
                                if (destinationReady) {
                                    Text("YouTube destination ready", color = AccentGreen)
                                    destinationUrl?.let { url -> TextButton(onClick = { openUrl(url) }) { Text("Open broadcast") } }
                                } else {
                                    TextButton(onClick = { showBroadcastOptions = !showBroadcastOptions }, enabled = !setupState.isBusy && !authorizing) {
                                        Text(if (showBroadcastOptions) "Hide broadcast details" else "$youtubeTitle / ${youtubePrivacy.name.lowercase().replaceFirstChar { it.uppercase() }}")
                                    }
                                    if (showBroadcastOptions) {
                                        OutlinedTextField(youtubeTitle, { youtubeTitle = it }, enabled = !setupState.isBusy && !authorizing,
                                            label = { Text("Broadcast title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            YouTubePrivacy.entries.forEach { option ->
                                                FilterChip(youtubePrivacy == option, { youtubePrivacy = option }, enabled = !setupState.isBusy && !authorizing,
                                                    label = { Text(option.label) })
                                            }
                                        }
                                    }
                                }
                            }
                            LivePlatform.MIXCLOUD -> {
                                Text("Open Mixcloud, sign in, then copy your stream key here. Mixcloud Pro is required.")
                                OutlinedButton(onClick = { platform.setupUrl?.let(::openUrl) }) { Text("Open Mixcloud setup") }
                                OutlinedTextField(streamKey, { streamKey = it; localError = null }, label = { Text("Mixcloud stream key") },
                                    singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                            }
                            LivePlatform.CUSTOM -> {
                                Text("Copy the server and stream key from your streaming service.")
                                OutlinedTextField(serverUrl, { serverUrl = it; localError = null }, label = { Text("RTMP / RTMPS server") },
                                    singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(streamKey, { streamKey = it; localError = null }, label = { Text("Stream key") },
                                    singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                            }
                        }
                        if (setupState.isBusy || authorizing) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(if (authorizing) "Connecting to Google..." else setupState.message)
                        } else if (setupState.status == StreamSetupStatus.ERROR) {
                            Text(setupState.message, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    1 -> {
                        Text("What will viewers see?", style = MaterialTheme.typography.titleMedium)
                        LiveVideoMode.entries.forEach { option ->
                            OutlinedButton(onClick = { videoMode = option; localError = null }, modifier = Modifier.fillMaxWidth()) {
                                Text((if (videoMode == option) "Selected: " else "") + option.label)
                            }
                        }
                        if (videoMode == LiveVideoMode.ARTWORK) {
                            CustomArtworkPicker(artworkUri, true, { artworkPicker.launch(arrayOf("image/*")) }, {
                                artworkUri = null
                                artworkPreferences.edit().remove("custom_artwork_uri").apply()
                            })
                            Text("Still artwork uses less power than the camera.", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (portrait) "Portrait (vertical)" else "Landscape (horizontal)")
                            Switch(portrait, { portrait = it })
                        }
                        Text("Meters, timers and controls stay on your phone. Viewers see only your camera or artwork.")
                    }
                    else -> {
                        Text("Ready for your audience?", style = MaterialTheme.typography.titleMedium)
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(platform.label, fontWeight = FontWeight.Bold)
                                Text("${videoMode.label} / ${if (portrait) "Portrait" else "Landscape"}")
                                Text(device?.productName ?: "No mixer selected")
                                Text(if (captureReady) health.message else "Connect and arm your mixer on the Record page.",
                                    color = if (captureReady && health.level == com.audiopro.djmrec.audio.RecordingHealthLevel.GOOD) AccentGreen else AccentAmber)
                                StreamSetupMeters(viewModel)
                            }
                        }
                        Text("Play music and check both meters before starting. Your phone microphone is never used.")
                        Text("Livestreaming remains experimental. Check the service's preview after connecting.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (liveState.status == LiveStreamStatus.ERROR) Text(liveState.message, color = MaterialTheme.colorScheme.error)
                localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        Surface(shadowElevation = 8.dp) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (!liveState.isActive && step > 0) TextButton(onClick = { step-- }) { Text("Back") }
                Button(onClick = {
                    if (liveState.isActive) confirmEnd = true
                    else when (step) {
                        0 -> if (platform == LivePlatform.YOUTUBE && !destinationReady) connectYouTube() else step = 1
                        1 -> step = 2
                        else -> goLive()
                    }
                }, enabled = liveState.isActive || when (step) {
                    0 -> !setupState.isBusy && !authorizing && (destinationReady || platform == LivePlatform.YOUTUBE && youtubeTitle.isNotBlank())
                    1 -> destinationReady && pictureReady
                    else -> destinationReady && pictureReady && captureReady
                }, modifier = Modifier.weight(1f).height(56.dp)) {
                    Text(if (liveState.isActive) "End stream" else when (step) {
                        0 -> if (platform == LivePlatform.YOUTUBE && !destinationReady) "Connect with Google" else "Continue"
                        1 -> "Check mixer"
                        else -> "Go live on ${platform.label}"
                    })
                }
            }
        }
    }
    if (confirmEnd) AlertDialog(onDismissRequest = { confirmEnd = false }, title = { Text("End livestream?") },
        text = { Text("Viewers will disconnect. Any local recording continues.") },
        confirmButton = { TextButton(onClick = { confirmEnd = false; viewModel.stopLiveStream() }) { Text("End stream") } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep streaming") } })
}

@Composable
private fun StreamSetupMeters(viewModel: MainViewModel) {
    val levels by viewModel.levels.collectAsState()
    com.audiopro.djmrec.ui.components.StereoVuMeter(levels)
}

@Composable
private fun CustomArtworkPicker(
    artworkUri: String?,
    enabled: Boolean,
    onChoose: () -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    LaunchedEffect(artworkUri) {
        previewLoading = !artworkUri.isNullOrBlank()
        preview = if (artworkUri.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            decodeArtworkPreview(context, artworkUri)
        }
        previewLoading = false
    }
    DisposableEffect(preview) {
        val activeBitmap = preview
        onDispose { activeBitmap?.recycle() }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!artworkUri.isNullOrBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Black
                ) {
                    val bitmap = preview
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Selected livestream artwork",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (previewLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Artwork unavailable. Choose another image.")
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onChoose, enabled = enabled, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null)
                        Text("Change", modifier = Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(onClick = onRemove, enabled = enabled) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = null)
                        Text("Remove", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            } else {
                Text("Choose an image shown behind your mixer audio. No default artwork is used.")
                OutlinedButton(
                    onClick = onChoose,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null)
                    Text("Choose artwork", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

private fun decodeArtworkPreview(context: Context, artworkUri: String): Bitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(artworkUri))) {
            decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longestSide = maxOf(info.size.width, info.size.height)
        decoder.setTargetSampleSize((longestSide / 1_280).coerceAtLeast(1))
    }
}.getOrNull()

private fun googleAuthorizationError(context: Context, error: ApiException): String {
    val unregistered = error.statusCode == CommonStatusCodes.DEVELOPER_ERROR ||
        error.message.orEmpty().contains("UNREGISTERED_ON_API_CONSOLE", ignoreCase = true)
    if (!unregistered) return error.localizedMessage ?: "Google authorization failed"

    val sha1 = appSigningSha1(context).ifBlank { "unknown" }
    return "Google OAuth client missing. Register Android package ${BuildConfig.APPLICATION_ID} " +
        "with signing SHA-1 $sha1 against client ${BuildConfig.GOOGLE_OAUTH_CLIENT_ID} in " +
        "Google Cloud, then enable YouTube Data API v3."
}

@Suppress("DEPRECATION")
private fun appSigningSha1(context: Context): String = runCatching {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES
        )
    } else {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
    }
    val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()
    } else {
        packageInfo.signatures?.firstOrNull()
    } ?: return@runCatching ""
    MessageDigest.getInstance("SHA-1")
        .digest(signature.toByteArray())
        .joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
}.getOrDefault("")

@Composable
private fun LiveStatusCard(liveState: LiveStreamState, captureReady: Boolean) {
    val color = when (liveState.status) {
        LiveStreamStatus.LIVE -> AccentGreen
        LiveStreamStatus.PREPARING,
        LiveStreamStatus.CONNECTING,
        LiveStreamStatus.RECONNECTING -> AccentAmber
        LiveStreamStatus.ERROR -> AccentRed
        LiveStreamStatus.IDLE -> if (captureReady) AccentGreen else MaterialTheme.colorScheme.onSurfaceVariant
    }
    val message = if (liveState.status == LiveStreamStatus.IDLE && !captureReady) {
        "Connect mixer and wait for USB signal"
    } else liveState.message
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = color.copy(alpha = 0.10f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.background(color, CircleShape).padding(5.dp))
                Text(
                    liveState.status.name.replace('_', ' '),
                    color = color,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                if (liveState.status == LiveStreamStatus.PREPARING ||
                    liveState.status == LiveStreamStatus.CONNECTING ||
                    liveState.status == LiveStreamStatus.RECONNECTING) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp, color = color)
                }
            }
            Text(message, style = MaterialTheme.typography.titleMedium)
            if (liveState.isActive) {
                Text(
                    if (liveState.audioPcmBytes == 0L) "Mixer audio: waiting for PCM"
                    else String.format(
                        Locale.US,
                        "Mixer audio: %.1f dBFS",
                        liveState.audioPeakDb
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (liveState.audioPcmBytes == 0L) AccentAmber
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (liveState.status == LiveStreamStatus.LIVE) {
                val mbps = liveState.bitrateBitsPerSecond / 1_000_000f
                Text(
                    String.format(
                        Locale.US,
                        "Upload %.2f Mbps / audio and video sending",
                        mbps
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (liveState.usesCamera) {
                    Text(
                        "Camera ${if (liveState.cameraOpened) "open" else "opening"} | " +
                            "captured ${liveState.cameraFramesCaptured} frames",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (liveState.droppedAudioFrames > 0 || liveState.droppedVideoFrames > 0) Text(
                    "Dropped audio ${liveState.droppedAudioFrames} | video ${liveState.droppedVideoFrames}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
