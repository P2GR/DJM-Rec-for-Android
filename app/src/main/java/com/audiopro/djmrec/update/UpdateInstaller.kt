package com.audiopro.djmrec.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UpdateInstaller {
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val MAX_APK_BYTES = 250L * 1024L * 1024L

    suspend fun download(context: Context, update: AppUpdate): File = withContext(Dispatchers.IO) {
        require(UpdateChecker.isTrustedAssetUrl(update.apkUrl)) { "Untrusted APK address" }
        require(UpdateChecker.isTrustedAssetUrl(update.checksumUrl)) { "Untrusted checksum address" }
        require(UpdateChecker.isReleaseApkName(update.apkName)) { "Invalid APK name" }

        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(directory, update.apkName)
        downloadFile(update.apkUrl, apk, update.apkSize)

        val expectedHash = downloadText(update.checksumUrl)
            .trim().substringBefore(' ').lowercase()
        if (!expectedHash.matches(Regex("[0-9a-f]{64}")) || sha256(apk) != expectedHash) {
            apk.delete()
            throw IOException("Downloaded APK failed checksum verification")
        }
        verifyPackage(context.packageManager, apk, update.version)
        apk
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }

    private fun downloadFile(url: String, destination: File, declaredSize: Long) {
        if (declaredSize > MAX_APK_BYTES) throw IOException("Release APK is unexpectedly large")
        val connection = open(url, readTimeout = 60_000)
        try {
            val responseSize = connection.contentLengthLong
            if (responseSize > MAX_APK_BYTES) throw IOException("Release APK is unexpectedly large")
            connection.inputStream.use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_APK_BYTES) throw IOException("Release APK is unexpectedly large")
                        output.write(buffer, 0, count)
                    }
                }
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadText(url: String): String {
        val connection = open(url, readTimeout = 15_000)
        return try {
            connection.inputStream.bufferedReader().use { it.readText().take(4_096) }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, readTimeout: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            this.readTimeout = readTimeout
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", "DJMRec-Android")
            if (responseCode !in 200..299) {
                disconnect()
                throw IOException("Update download failed (HTTP $responseCode)")
            }
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(packageManager: PackageManager, apk: File, expectedVersion: String) {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        } ?: throw IOException("Downloaded file is not a valid APK")
        if (info.packageName != "com.audiopro.djmrec" || info.versionName != expectedVersion) {
            apk.delete()
            throw IOException("Downloaded APK does not match DJM REC $expectedVersion")
        }
    }
}
