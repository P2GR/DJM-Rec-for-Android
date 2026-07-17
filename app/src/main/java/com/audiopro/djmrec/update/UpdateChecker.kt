package com.audiopro.djmrec.update

import android.content.Context
import android.net.Uri
import com.audiopro.djmrec.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class AppUpdate(
    val tag: String,
    val version: String,
    val releaseUrl: String
)

object UpdateChecker {
    private const val RELEASE_API = "https://api.github.com/repos/P2GR/DJM-Rec-for-Android/releases/latest"
    private const val PREFS = "app_updates"
    private const val CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L
    private const val DEFER_INTERVAL_MS = 24L * 60L * 60L * 1000L

    suspend fun check(context: Context): AppUpdate? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val cached = cachedUpdate(context)
        if (now - prefs.getLong("last_check", 0L) < CHECK_INTERVAL_MS) {
            return@withContext cached?.takeUnless { isDeferred(context, it.tag, now) }
        }

        val remote = runCatching { fetchLatestRelease() }.getOrNull()
        prefs.edit().putLong("last_check", now).apply()
        if (remote != null) {
            prefs.edit()
                .putString("cached_tag", remote.tag)
                .putString("cached_version", remote.version)
                .putString("cached_url", remote.releaseUrl)
                .apply()
        }
        val update = remote ?: cached
        update
            ?.takeIf { isNewer(it.version, BuildConfig.VERSION_NAME) }
            ?.takeUnless { isDeferred(context, it.tag, now) }
    }

    fun defer(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("deferred_tag", tag)
            .putLong("deferred_until", System.currentTimeMillis() + DEFER_INTERVAL_MS)
            .apply()
    }

    private fun fetchLatestRelease(): AppUpdate? {
        val connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            setRequestProperty("User-Agent", "DJMRec-Android/${BuildConfig.VERSION_NAME}")
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val tag = json.optString("tag_name")
            val version = tag.removePrefix("v")
            val releaseUrl = json.optString("html_url")
            if (tag.isBlank() || !isTrustedReleaseUrl(releaseUrl)) return null
            AppUpdate(tag = tag, version = version, releaseUrl = releaseUrl)
        } finally {
            connection.disconnect()
        }
    }

    private fun cachedUpdate(context: Context): AppUpdate? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tag = prefs.getString("cached_tag", null) ?: return null
        val version = prefs.getString("cached_version", null) ?: return null
        val url = prefs.getString("cached_url", null) ?: return null
        if (!isTrustedReleaseUrl(url) || !isNewer(version, BuildConfig.VERSION_NAME)) return null
        return AppUpdate(tag, version, url)
    }

    private fun isDeferred(context: Context, tag: String, now: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("deferred_tag", null) == tag &&
            now < prefs.getLong("deferred_until", 0L)
    }

    private fun isTrustedReleaseUrl(value: String): Boolean {
        val uri = Uri.parse(value)
        return uri.scheme == "https" && uri.host == "github.com"
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
