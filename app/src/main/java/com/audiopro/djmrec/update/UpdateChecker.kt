package com.audiopro.djmrec.update

import android.content.Context
import com.audiopro.djmrec.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class AppUpdate(
    val tag: String,
    val version: String,
    val releaseUrl: String,
    val apkUrl: String,
    val checksumUrl: String,
    val apkName: String,
    val apkSize: Long
)

sealed interface UpdateCheckResult {
    data class Available(val update: AppUpdate) : UpdateCheckResult
    data object Current : UpdateCheckResult
    data class Failed(val message: String) : UpdateCheckResult
}

object UpdateChecker {
    private const val RELEASE_API = "https://api.github.com/repos/P2GR/DJM-Rec-for-Android/releases/latest"
    private const val PREFS = "app_updates"
    private const val CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L
    private const val DEFER_INTERVAL_MS = 24L * 60L * 60L * 1000L

    suspend fun check(context: Context): AppUpdate? = withContext(Dispatchers.IO) {
        if (BuildConfig.PROTOCOL_RESEARCH) return@withContext null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val cached = cachedUpdate(context)
        if (now - prefs.getLong("last_check", 0L) < CHECK_INTERVAL_MS) {
            return@withContext cached?.takeUnless { isDeferred(context, it.tag, now) }
        }

        val remote = runCatching { fetchLatestRelease() }.getOrNull()
        prefs.edit().putLong("last_check", now).apply()
        if (remote != null) cacheUpdate(context, remote)
        val update = remote ?: cached
        update
            ?.takeIf { isNewer(it.version, BuildConfig.VERSION_NAME) }
            ?.takeUnless { isDeferred(context, it.tag, now) }
    }

    suspend fun checkNow(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (BuildConfig.PROTOCOL_RESEARCH) return@withContext UpdateCheckResult.Failed("Experimental builds come from the experimental workflow, not stable releases.")
        runCatching { fetchLatestRelease() }.fold(
            onSuccess = { remote ->
                cacheUpdate(context, remote)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong("last_check", System.currentTimeMillis())
                    .apply()
                if (isNewer(remote.version, BuildConfig.VERSION_NAME)) {
                    UpdateCheckResult.Available(remote)
                } else {
                    UpdateCheckResult.Current
                }
            },
            onFailure = { error ->
                UpdateCheckResult.Failed(error.message ?: "Could not check for updates")
            }
        )
    }

    fun defer(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("deferred_tag", tag)
            .putLong("deferred_until", System.currentTimeMillis() + DEFER_INTERVAL_MS)
            .apply()
    }

    private fun fetchLatestRelease(): AppUpdate {
        val connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            setRequestProperty("User-Agent", "DJMRec-Android/${BuildConfig.VERSION_NAME}")
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Update check failed (HTTP ${connection.responseCode})")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) {
                throw IOException("Latest GitHub release is not installable")
            }
            val tag = json.optString("tag_name")
            val version = tag.removePrefix("v")
            val releaseUrl = json.optString("html_url")
            if (tag.isBlank() || !isTrustedReleaseUrl(releaseUrl)) {
                throw IOException("GitHub returned invalid release details")
            }
            val assets = json.optJSONArray("assets")
                ?: throw IOException("Latest release has no downloadable APK")
            val apk = (0 until assets.length())
                .mapNotNull(assets::optJSONObject)
                .firstOrNull { isReleaseApkName(it.optString("name")) }
                ?: throw IOException("Latest release has no release APK")
            val apkName = apk.optString("name")
            val apkUrl = apk.optString("browser_download_url")
            val checksum = (0 until assets.length())
                .mapNotNull(assets::optJSONObject)
                .firstOrNull { it.optString("name") == "$apkName.sha256" }
                ?: throw IOException("Latest release has no APK checksum")
            val checksumUrl = checksum.optString("browser_download_url")
            if (!isTrustedAssetUrl(apkUrl) || !isTrustedAssetUrl(checksumUrl)) {
                throw IOException("GitHub returned an untrusted download address")
            }
            AppUpdate(tag, version, releaseUrl, apkUrl, checksumUrl, apkName, apk.optLong("size"))
        } finally {
            connection.disconnect()
        }
    }

    private fun cacheUpdate(context: Context, update: AppUpdate) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("cached_tag", update.tag)
            .putString("cached_version", update.version)
            .putString("cached_url", update.releaseUrl)
            .putString("cached_apk_url", update.apkUrl)
            .putString("cached_checksum_url", update.checksumUrl)
            .putString("cached_apk_name", update.apkName)
            .putLong("cached_apk_size", update.apkSize)
            .apply()
    }

    private fun cachedUpdate(context: Context): AppUpdate? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tag = prefs.getString("cached_tag", null) ?: return null
        val version = prefs.getString("cached_version", null) ?: return null
        val url = prefs.getString("cached_url", null) ?: return null
        val apkUrl = prefs.getString("cached_apk_url", null) ?: return null
        val checksumUrl = prefs.getString("cached_checksum_url", null) ?: return null
        val apkName = prefs.getString("cached_apk_name", null) ?: return null
        if (!isTrustedReleaseUrl(url) || !isTrustedAssetUrl(apkUrl) ||
            !isTrustedAssetUrl(checksumUrl) || !isReleaseApkName(apkName) ||
            !isNewer(version, BuildConfig.VERSION_NAME)) return null
        return AppUpdate(tag, version, url, apkUrl, checksumUrl, apkName,
            prefs.getLong("cached_apk_size", 0L))
    }

    private fun isDeferred(context: Context, tag: String, now: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("deferred_tag", null) == tag &&
            now < prefs.getLong("deferred_until", 0L)
    }

    internal fun isTrustedReleaseUrl(value: String): Boolean = trustedGitHubUrl(
        value,
        "/P2GR/DJM-Rec-for-Android/releases/tag/"
    )

    internal fun isTrustedAssetUrl(value: String): Boolean = trustedGitHubUrl(
        value,
        "/P2GR/DJM-Rec-for-Android/releases/download/"
    )

    internal fun isReleaseApkName(value: String): Boolean =
        value.startsWith("DJM-Rec-for-Android-v") &&
            value.endsWith("-release.apk") &&
            '/' !in value && '\\' !in value

    private fun trustedGitHubUrl(value: String, pathPrefix: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("github.com", ignoreCase = true) &&
            uri.port == -1 && uri.userInfo == null && uri.query == null &&
            uri.path.startsWith(pathPrefix)
    }

    internal fun isNewer(remote: String, current: String): Boolean {
        val remoteParts = versionParts(remote)
        val currentParts = versionParts(current)
        val count = maxOf(remoteParts.size, currentParts.size)
        for (index in 0 until count) {
            val remotePart = remoteParts.getOrElse(index) { 0 }
            val currentPart = currentParts.getOrElse(index) { 0 }
            if (remotePart != currentPart) return remotePart > currentPart
        }
        return false
    }

    private fun versionParts(value: String): List<Int> = value
        .removePrefix("v")
        .substringBefore('-')
        .split('.')
        .map { it.toIntOrNull() ?: 0 }
}
