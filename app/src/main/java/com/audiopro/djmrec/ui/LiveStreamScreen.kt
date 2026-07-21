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
import android.view.SurfaceView
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
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
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
import androidx.compose.ui.viewinterop.AndroidView
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
    var platform by rememberSaveable { mutableStateOf(LivePlatform.MIXCLOUD) }
    var serverUrl by rememberSaveable { mutableStateOf(LivePlatform.MIXCLOUD.defaultServerUrl) }
    var streamKey by remember { mutableStateOf("") }
    var videoMode by rememberSaveable { mutableStateOf(LiveVideoMode.ARTWORK) }
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
    var destinationUrl by remember { mutableStateOf<String?>(null) }
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
        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent != null) {
                        googleAuthorization.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                    }
                } else {
                    acceptYouTubeToken(result.accessToken)
                }
            }
            .addOnFailureListener { error ->
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
            streamKey = ""
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
    val controlsEnabled = !liveState.isActive

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
        }
    }

    LaunchedEffect(setupState.verificationUrl) {
        val url = setupState.verificationUrl ?: return@LaunchedEffect
        if (openedVerificationUrl != url) {
            openedVerificationUrl = url
            openUrl(url)
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.detachLivePreview() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        LiveStatusCard(liveState = liveState, captureReady = captureReady)

        // WIP banner
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = AccentAmber.copy(alpha = 0.12f),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text = "WORK IN PROGRESS \u2014 Livestreaming is under active development and may not work reliably yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = AccentAmber,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
            )
        }

        if (liveState.isActive && liveState.usesCamera) {
            Surface(
                modifier = Modifier.fillMaxWidth().aspectRatio(if (portrait) 9f / 16f else 16f / 9f),
                shape = RoundedCornerShape(22.dp),
                color = Color.Black
            ) {
                AndroidView(
                    factory = { SurfaceView(it).also(viewModel::attachLivePreview) },
                    update = viewModel::attachLivePreview,
                    modifier = Modifier.fillMaxSize()
                )
            }
            OutlinedButton(
                onClick = viewModel::switchLiveCamera,
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(Icons.Filled.Cameraswitch, contentDescription = null)
                Text("Switch camera", modifier = Modifier.padding(start = 8.dp))
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "DESTINATION",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LivePlatform.entries.forEach { option ->
                        FilterChip(
                            selected = platform == option,
                            enabled = controlsEnabled,
                            onClick = {
                                platform = option
                                serverUrl = option.defaultServerUrl
                                streamKey = ""
                                destinationUrl = null
                                localError = null
                            },
                            label = { Text(option.label) }
                        )
                    }
                }
                Text(
                    platform.setupHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                when (platform) {
                    LivePlatform.YOUTUBE -> {
                        OutlinedTextField(
                            value = youtubeTitle,
                            onValueChange = { youtubeTitle = it },
                            enabled = controlsEnabled && !setupState.isBusy,
                            label = { Text("Broadcast title") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            YouTubePrivacy.entries.forEach { option ->
                                FilterChip(
                                    selected = youtubePrivacy == option,
                                    enabled = controlsEnabled && !setupState.isBusy,
                                    onClick = { youtubePrivacy = option },
                                    label = { Text(option.label) }
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = ::connectYouTube,
                            enabled = controlsEnabled && youtubeTitle.isNotBlank() && !setupState.isBusy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Link, contentDescription = null)
                            Text("Connect YouTube", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    LivePlatform.MIXCLOUD -> OutlinedButton(
                        onClick = { platform.setupUrl?.let(::openUrl) },
                        enabled = controlsEnabled,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.OpenInBrowser, contentDescription = null)
                        Text("Open ${platform.label} setup", modifier = Modifier.padding(start = 8.dp))
                    }
                    LivePlatform.CUSTOM -> Unit
                }

                if (setupState.platform == platform && setupState.status != StreamSetupStatus.IDLE) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (setupState.isBusy) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                }
                                Text(
                                    setupState.message,
                                    color = if (setupState.status == StreamSetupStatus.ERROR) {
                                        MaterialTheme.colorScheme.error
                                    } else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            setupState.userCode?.let { Text("Authorization code: $it", fontWeight = FontWeight.Bold) }
                            setupState.verificationUrl?.let { url ->
                                OutlinedButton(onClick = { openUrl(url) }) { Text("Open authorization") }
                            }
                        }
                    }
                }

                if (platform == LivePlatform.YOUTUBE &&
                    youtubeBroadcast.status != YouTubeBroadcastStatus.IDLE) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "YOUTUBE ${youtubeBroadcast.status.name.replace('_', ' ')}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (youtubeBroadcast.status == YouTubeBroadcastStatus.ERROR) {
                                    MaterialTheme.colorScheme.error
                                } else MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(youtubeBroadcast.message, style = MaterialTheme.typography.bodyMedium)
                            youtubeBroadcast.watchUrl?.let { url ->
                                OutlinedButton(
                                    onClick = { shareBroadcast(url) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.Share, contentDescription = null)
                                    Text("Share broadcast", modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it; localError = null },
                    enabled = controlsEnabled,
                    label = { Text("RTMP / RTMPS server URL") },
                    placeholder = { Text("rtmps://server.example.com/app") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = streamKey,
                    onValueChange = { streamKey = it; localError = null },
                    enabled = controlsEnabled,
                    label = { Text("Stream key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Stream key is kept in memory only. It is never saved or included in diagnostics.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                destinationUrl?.let { url ->
                    OutlinedButton(onClick = { openUrl(url) }) {
                        Icon(Icons.Filled.OpenInBrowser, contentDescription = null)
                        Text("Open broadcast control", modifier = Modifier.padding(start = 8.dp))
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                Text(
                    "VIDEO",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LiveVideoMode.entries.forEach { option ->
                        FilterChip(
                            selected = videoMode == option,
                            enabled = controlsEnabled,
                            onClick = { videoMode = option; localError = null },
                            label = { Text(option.label) }
                        )
                    }
                }
                if (videoMode == LiveVideoMode.ARTWORK) {
                    CustomArtworkPicker(
                        artworkUri = artworkUri,
                        enabled = controlsEnabled,
                        onChoose = { artworkPicker.launch(arrayOf("image/*")) },
                        onRemove = {
                            artworkUri?.let { selected ->
                                runCatching {
                                    context.contentResolver.releasePersistableUriPermission(
                                        Uri.parse(selected),
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    )
                                }
                            }
                            artworkUri = null
                            artworkPreferences.edit().remove("custom_artwork_uri").apply()
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Portrait stream", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Best for vertical YouTube Live and Shorts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = portrait,
                        enabled = controlsEnabled,
                        onCheckedChange = { portrait = it }
                    )
                }
            }
        }

        localError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        if (liveState.isActive) {
            Button(
                onClick = viewModel::stopLiveStream,
                colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Text("STOP LIVESTREAM", modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = {
                    val config = LiveStreamConfig(
                        platform = platform,
                        serverUrl = serverUrl,
                        streamKey = streamKey,
                        videoMode = videoMode,
                        portrait = portrait,
                        artworkUri = artworkUri
                    )
                    if (videoMode == LiveVideoMode.ARTWORK && artworkUri.isNullOrBlank()) {
                        localError = "Choose custom artwork before going live"
                        return@Button
                    }
                    localError = runCatching { config.endpoint() }.exceptionOrNull()?.message
                    if (localError != null) return@Button
                    val needsCamera = videoMode != LiveVideoMode.ARTWORK
                    val hasCameraPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    if (needsCamera && !hasCameraPermission) {
                        pendingCameraConfig = config
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    } else {
                        viewModel.startLiveStream(config)
                        streamKey = ""
                    }
                },
                enabled = captureReady && serverUrl.isNotBlank() && streamKey.isNotBlank() &&
                    (videoMode != LiveVideoMode.ARTWORK || !artworkUri.isNullOrBlank()),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Icon(Icons.Filled.Videocam, contentDescription = null)
                Text("GO LIVE", modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
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
                        "Mixer PCM: %.1f dBFS | %.1f KB fed to AAC",
                        liveState.audioPeakDb,
                        liveState.audioPcmBytes / 1024f
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
                        "Upload %.2f Mbps | encoded audio %d | video %d",
                        mbps,
                        liveState.audioFramesSent,
                        liveState.videoFramesSent
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
                Text(
                    "Dropped audio ${liveState.droppedAudioFrames} | video ${liveState.droppedVideoFrames}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
