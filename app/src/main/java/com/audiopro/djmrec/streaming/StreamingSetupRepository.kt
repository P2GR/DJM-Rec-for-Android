package com.audiopro.djmrec.streaming

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit

internal const val YOUTUBE_CDN_RESOLUTION = "variable"
internal const val YOUTUBE_CDN_FRAME_RATE = "variable"

object StreamingSetupRepository {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val YOUTUBE_POLL_INTERVAL_MS = 2_000L
    private const val YOUTUBE_INGEST_TIMEOUT_MS = 90_000L
    private const val YOUTUBE_TRANSITION_TIMEOUT_MS = 60_000L

    /**
     * Set by the UI layer: silently returns a fresh Google access token (Identity Authorization
     * API). Recovers API calls from 401s during long sets, when the token captured at connect
     * time has expired (Google access tokens live about an hour).
     */
    @Volatile
    var accessTokenRefresher: (suspend () -> String?)? = null

    suspend fun prepareYouTubeLive(
        accessToken: String,
        title: String,
        privacy: YouTubePrivacy
    ): PreparedYouTubeLive = withContext(Dispatchers.IO) {
        require(title.isNotBlank()) { "YouTube title is required" }
        val headers = mapOf("Authorization" to "Bearer $accessToken")
        val scheduledStart = Instant.now().plus(1, ChronoUnit.MINUTES).toString()
        var broadcastId = ""
        var streamId = ""
        try {
            val broadcastBody = JSONObject()
                .put("snippet", JSONObject()
                    .put("title", title.trim())
                    .put("scheduledStartTime", scheduledStart))
                .put("status", JSONObject()
                    .put("privacyStatus", privacy.apiValue)
                    .put("selfDeclaredMadeForKids", false))
                .put("contentDetails", JSONObject()
                    .put("enableAutoStart", true)
                    .put("enableAutoStop", true)
                    .put("recordFromStart", true)
                    .put("monitorStream", JSONObject().put("enableMonitorStream", false)))
            val broadcast = requestJson(
                "POST",
                "https://www.googleapis.com/youtube/v3/liveBroadcasts" +
                    "?part=snippet,status,contentDetails",
                headers,
                broadcastBody.toString()
            )
            requireSuccess(broadcast, "YouTube broadcast creation")
            broadcastId = broadcast.json.optString("id")
            if (broadcastId.isBlank()) throw IOException("YouTube did not return a broadcast ID")

            val streamBody = JSONObject()
                .put("snippet", JSONObject().put("title", "DJM REC - ${title.trim()}"))
                .put("cdn", JSONObject()
                    .put("frameRate", YOUTUBE_CDN_FRAME_RATE)
                    .put("ingestionType", "rtmp")
                    .put("resolution", YOUTUBE_CDN_RESOLUTION))
                .put("contentDetails", JSONObject().put("isReusable", false))
            val stream = requestJson(
                "POST",
                "https://www.googleapis.com/youtube/v3/liveStreams?part=snippet,cdn,contentDetails",
                headers,
                streamBody.toString()
            )
            requireSuccess(stream, "YouTube stream creation")
            streamId = stream.json.optString("id")
            val ingestion = stream.json.optJSONObject("cdn")?.optJSONObject("ingestionInfo")
            val server = ingestion?.optString("rtmpsIngestionAddress")
                ?.takeIf(String::isNotBlank)
                ?: ingestion?.optString("ingestionAddress").orEmpty()
            val key = ingestion?.optString("streamName").orEmpty()
            if (streamId.isBlank() || server.isBlank() || key.isBlank()) {
                throw IOException("YouTube returned incomplete ingestion settings")
            }

            val bind = requestJson(
                "POST",
                "https://www.googleapis.com/youtube/v3/liveBroadcasts/bind" +
                    "?part=id,contentDetails&id=${encode(broadcastId)}&streamId=${encode(streamId)}",
                headers
            )
            requireSuccess(bind, "YouTube stream binding")
            val session = YouTubeLiveSession(accessToken, broadcastId, streamId)
            PreparedYouTubeLive(
                credentials = StreamCredentials(
                    LivePlatform.YOUTUBE,
                    server,
                    key,
                    destinationUrl = session.studioUrl,
                    shareUrl = session.watchUrl
                ),
                session = session
            )
        } catch (error: Exception) {
            // Avoid filling the channel with unusable setup objects after partial API failure.
            if (streamId.isNotBlank()) runCatching {
                requestJson(
                    "DELETE",
                    "https://www.googleapis.com/youtube/v3/liveStreams?id=${encode(streamId)}",
                    headers
                )
            }
            if (broadcastId.isNotBlank()) runCatching {
                requestJson(
                    "DELETE",
                    "https://www.googleapis.com/youtube/v3/liveBroadcasts?id=${encode(broadcastId)}",
                    headers
                )
            }
            throw error
        }
    }

    suspend fun startYouTubeBroadcast(session: YouTubeLiveSession) = withContext(Dispatchers.IO) {
        val ingestDeadline = SystemClock.elapsedRealtime() + YOUTUBE_INGEST_TIMEOUT_MS
        while (true) {
            val stream = requestAuthed(
                session,
                "GET",
                "https://www.googleapis.com/youtube/v3/liveStreams" +
                    "?part=status&id=${encode(session.streamId)}"
            )
            requireSuccess(stream, "YouTube ingest status")
            val status = stream.json.getJSONArray("items").optJSONObject(0)?.optJSONObject("status")
                ?: throw IOException("YouTube stream no longer exists")
            when (status.optString("streamStatus")) {
                "active" -> break
                "error" -> throw IOException(youtubeHealthError(status))
            }
            if (SystemClock.elapsedRealtime() >= ingestDeadline) {
                throw IOException("YouTube did not detect the RTMP feed within 90 seconds")
            }
            delay(YOUTUBE_POLL_INTERVAL_MS)
        }

        val currentStatus = youtubeBroadcastStatus(session)
        if (currentStatus != "live" && currentStatus != "liveStarting") {
            val transition = requestAuthed(
                session,
                "POST",
                "https://www.googleapis.com/youtube/v3/liveBroadcasts/transition" +
                    "?part=status&id=${encode(session.broadcastId)}&broadcastStatus=live"
            )
            val reason = youtubeErrorReason(transition)
            val autoStartRace = reason == "redundantTransition" || reason == "invalidTransition"
            if (transition.code !in 200..299 && !autoStartRace) {
                requireSuccess(transition, "YouTube broadcast start")
            }
        }

        val transitionDeadline = SystemClock.elapsedRealtime() + YOUTUBE_TRANSITION_TIMEOUT_MS
        while (youtubeBroadcastStatus(session) != "live") {
            if (SystemClock.elapsedRealtime() >= transitionDeadline) {
                throw IOException("YouTube broadcast did not become live within 60 seconds")
            }
            delay(YOUTUBE_POLL_INTERVAL_MS)
        }
    }

    suspend fun finishYouTubeBroadcast(session: YouTubeLiveSession): YouTubeFinishResult =
        withContext(Dispatchers.IO) {
            when (youtubeBroadcastStatus(session)) {
                "live", "liveStarting", "testing", "testStarting" -> {
                    val transition = requestAuthed(
                        session,
                        "POST",
                        "https://www.googleapis.com/youtube/v3/liveBroadcasts/transition" +
                            "?part=status&id=${encode(session.broadcastId)}&broadcastStatus=complete"
                    )
                    if (transition.code !in 200..299 &&
                        youtubeErrorReason(transition) != "redundantTransition") {
                        requireSuccess(transition, "YouTube broadcast completion")
                    }
                    YouTubeFinishResult.COMPLETED
                }
                "complete" -> YouTubeFinishResult.COMPLETED
                else -> {
                    // A stream stopped before going live should not remain as a planned event.
                    val deleteBroadcast = requestAuthed(
                        session,
                        "DELETE",
                        "https://www.googleapis.com/youtube/v3/liveBroadcasts" +
                            "?id=${encode(session.broadcastId)}"
                    )
                    requireSuccess(deleteBroadcast, "YouTube planned broadcast cleanup")
                    requestAuthed(
                        session,
                        "DELETE",
                        "https://www.googleapis.com/youtube/v3/liveStreams?id=${encode(session.streamId)}"
                    )
                    YouTubeFinishResult.DELETED
                }
            }
        }

    /** Audience size and YouTube-reported ingest health for the live broadcast, polled by the UI. */
    suspend fun fetchYouTubeLiveStats(session: YouTubeLiveSession): YouTubeLiveStats =
        withContext(Dispatchers.IO) {
            val video = requestAuthed(
                session,
                "GET",
                "https://www.googleapis.com/youtube/v3/liveVideos" +
                    "?part=liveStreamingDetails&id=${encode(session.broadcastId)}"
            )
            val viewers = video.json.getJSONArray("items").optJSONObject(0)
                ?.optJSONObject("liveStreamingDetails")?.optString("concurrentViewers")
                ?.takeIf(String::isNotBlank)?.toIntOrNull()
            val stream = requestAuthed(
                session,
                "GET",
                "https://www.googleapis.com/youtube/v3/liveStreams" +
                    "?part=status&id=${encode(session.streamId)}"
            )
            val health = stream.json.getJSONArray("items").optJSONObject(0)
                ?.optJSONObject("status")?.optJSONObject("healthStatus")
            val label = health?.optString("status")?.takeIf(String::isNotBlank)
            val issues = health?.optJSONArray("configurationIssues")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.optString("description")?.takeIf(String::isNotBlank)
                }
            }.orEmpty()
            YouTubeLiveStats(viewers, label, issues)
        }

    private suspend fun youtubeBroadcastStatus(session: YouTubeLiveSession): String {
        val response = requestAuthed(
            session,
            "GET",
            "https://www.googleapis.com/youtube/v3/liveBroadcasts" +
                "?part=status&id=${encode(session.broadcastId)}"
        )
        requireSuccess(response, "YouTube broadcast status")
        return response.json.getJSONArray("items").optJSONObject(0)
            ?.optJSONObject("status")?.optString("lifeCycleStatus")
            ?.takeIf(String::isNotBlank)
            ?: throw IOException("YouTube broadcast no longer exists")
    }

    private fun youtubeHeaders(session: YouTubeLiveSession): Map<String, String> =
        mapOf("Authorization" to "Bearer ${session.accessToken}")

    /** [requestJson] with YouTube auth and a single silent re-auth retry on 401. */
    private suspend fun requestAuthed(
        session: YouTubeLiveSession,
        method: String,
        url: String,
        body: String? = null
    ): JsonResponse {
        var response = requestJson(method, url, youtubeHeaders(session), body)
        if (response.code == 401) {
            val fresh = accessTokenRefresher?.invoke()
            if (!fresh.isNullOrBlank()) {
                session.accessToken = fresh
                response = requestJson(method, url, youtubeHeaders(session), body)
            }
        }
        return response
    }

    private fun youtubeHealthError(status: JSONObject): String {
        val issues = status.optJSONObject("healthStatus")?.optJSONArray("configurationIssues")
        val details = issues?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.optString("description")?.takeIf(String::isNotBlank)
            }.joinToString("; ")
        }.orEmpty()
        return if (details.isBlank()) "YouTube rejected the incoming RTMP feed"
        else "YouTube rejected the incoming RTMP feed: $details"
    }

    private fun youtubeErrorReason(response: JsonResponse): String =
        response.json.optJSONObject("error")?.optJSONArray("errors")
            ?.optJSONObject(0)?.optString("reason").orEmpty()

    private data class JsonResponse(val code: Int, val json: JSONObject)

    private fun requestJson(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null
    ): JsonResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    if (body.startsWith("{")) "application/json; charset=utf-8"
                    else "application/x-www-form-urlencoded; charset=utf-8"
                )
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return JsonResponse(code, if (text.isBlank()) JSONObject() else JSONObject(text))
        } finally {
            connection.disconnect()
        }
    }

    private fun requireSuccess(response: JsonResponse, operation: String) {
        if (response.code !in 200..299) throw apiError(operation, response)
    }

    private fun apiError(operation: String, response: JsonResponse): IOException {
        val providerMessage = response.json.optJSONObject("error")?.optString("message")
            ?.takeIf(String::isNotBlank)
            ?: response.json.optString("message").takeIf(String::isNotBlank)
        return IOException(providerMessage?.let { "$operation failed: $it" }
            ?: "$operation failed (HTTP ${response.code})")
    }

    private fun formBody(vararg values: Pair<String, String>): String =
        values.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
