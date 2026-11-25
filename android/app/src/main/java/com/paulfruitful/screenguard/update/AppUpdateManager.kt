package com.paulfruitful.screenguard.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.paulfruitful.screenguard.BuildConfig
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val updateAvailable: Boolean,
    val releaseTitle: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val assetName: String,
    val assetSizeBytes: Long,
    val releaseUrl: String,
    val publishedAt: String
)

data class UpdateInstallOutcome(
    val status: String, // "INSTALLER_LAUNCHED" | "NEEDS_INSTALL_PERMISSION"
    val message: String,
    val filePath: String
)

/**
 * On-device GitHub Releases OTA updater.
 *
 * Queries GitHub's public Releases API for `paulfruitful/ScreenGuard`, compares semantic versions
 * against [BuildConfig.VERSION_NAME], downloads the release APK into app-private cache storage
 * with live progress callbacks, and hands it off to Android's official Package Installer via
 * [FileProvider].
 */
object AppUpdateManager {

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/paulfruitful/ScreenGuard/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 30_000

    fun checkForUpdate(): AppUpdateInfo {
        val currentVersion = normalizeVersion(BuildConfig.VERSION_NAME)
        val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ScreenGuard-Android/$currentVersion")
        }

        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("GitHub Releases API returned HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "").trim()
            val latestVersion = normalizeVersion(tagName)
            val releaseTitle = json.optString("name", tagName).ifBlank { "Screen Guard v$latestVersion" }
            val releaseNotes = json.optString("body", "").trim()
            val releaseUrl = json.optString("html_url", "https://github.com/paulfruitful/ScreenGuard/releases")
            val publishedAt = json.optString("published_at", "")

            var downloadUrl = ""
            var assetName = ""
            var assetSizeBytes = 0L

            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        assetName = name
                        downloadUrl = asset.optString("browser_download_url", "")
                        assetSizeBytes = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            val hasNewer = downloadUrl.isNotBlank() && isNewerVersion(latestVersion, currentVersion)

            return AppUpdateInfo(
                currentVersion = currentVersion,
                latestVersion = latestVersion.ifBlank { currentVersion },
                updateAvailable = hasNewer,
                releaseTitle = releaseTitle,
                releaseNotes = releaseNotes,
                downloadUrl = downloadUrl,
                assetName = assetName,
                assetSizeBytes = assetSizeBytes,
                releaseUrl = releaseUrl,
                publishedAt = publishedAt
            )
        } finally {
            conn.disconnect()
        }
    }

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallUnknownAppsSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        assetName: String,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): UpdateInstallOutcome {
        require(downloadUrl.startsWith("https://")) {
            "Only HTTPS GitHub release URLs are allowed for updates."
        }
        val appCtx = context.applicationContext
        val updatesDir = File(appCtx.cacheDir, "updates").apply { mkdirs() }
        val safeFileName = assetName
            .ifBlank { "ScreenGuard-update.apk" }
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val targetFile = File(updatesDir, safeFileName)
        val tempFile = File(updatesDir, "$safeFileName.part")

        // Download APK (following GitHub CDN redirects)
        var currentUrl = downloadUrl
        var redirects = 0
        var conn: HttpURLConnection? = null
        while (redirects < 6) {
            val c = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "ScreenGuard-Android/${BuildConfig.VERSION_NAME}")
            }
            val code = c.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = c.getHeaderField("Location")
                c.disconnect()
                if (location.isNullOrBlank()) {
                    throw IllegalStateException("Redirect missing Location header")
                }
                currentUrl = location
                redirects++
            } else {
                conn = c
                break
            }
        }

        val activeConn = conn ?: throw IllegalStateException("Too many redirects downloading update")
        try {
            val code = activeConn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Update download failed with HTTP $code")
            }
            val totalBytes = activeConn.contentLengthLong.coerceAtLeast(0L)

            // Clean up any older cached APKs first so cache stays small
            updatesDir.listFiles()?.forEach { existing ->
                if (existing.name != safeFileName && existing.name != tempFile.name) {
                    existing.delete()
                }
            }

            if (targetFile.exists() && totalBytes > 0L && targetFile.length() == totalBytes) {
                onProgress(100, totalBytes, totalBytes)
            } else {
                BufferedInputStream(activeConn.inputStream).use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var downloaded = 0L
                        var lastReportedPct = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val pct = if (totalBytes > 0L) {
                                ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            if (pct != lastReportedPct) {
                                lastReportedPct = pct
                                onProgress(pct, downloaded, totalBytes)
                            }
                        }
                        output.flush()
                    }
                }
                if (targetFile.exists()) targetFile.delete()
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
                onProgress(100, targetFile.length(), maxOf(totalBytes, targetFile.length()))
            }
        } finally {
            activeConn.disconnect()
        }

        if (!canRequestPackageInstalls(appCtx)) {
            openInstallUnknownAppsSettings(appCtx)
            return UpdateInstallOutcome(
                status = "NEEDS_INSTALL_PERMISSION",
                message = "Allow 'Install unknown apps' for Screen Guard, then tap Update Now again.",
                filePath = targetFile.absolutePath
            )
        }

        launchPackageInstaller(appCtx, targetFile)
        return UpdateInstallOutcome(
            status = "INSTALLER_LAUNCHED",
            message = "Android installer opened. Tap Update to finish installing.",
            filePath = targetFile.absolutePath
        )
    }

    private fun launchPackageInstaller(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
    }

    internal fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V")

    internal fun isNewerVersion(latestRaw: String, currentRaw: String): Boolean {
        val latest = normalizeVersion(latestRaw).split(".", "-").mapNotNull { it.toIntOrNull() }
        val current = normalizeVersion(currentRaw).split(".", "-").mapNotNull { it.toIntOrNull() }
        if (latest.isEmpty()) return false
        val maxLen = maxOf(latest.size, current.size)
        for (i in 0 until maxLen) {
            val l = latest.getOrElse(i) { 0 }
            val c = current.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}
