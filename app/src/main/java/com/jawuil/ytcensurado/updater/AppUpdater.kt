package com.jawuil.ytcensurado.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.jawuil.ytcensurado.config.ConfigManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val tagName: String,
    val title: String,
    val notes: String,
    val apkDownloadUrl: String,
    val apkName: String,
    val isNewer: Boolean
)

object AppUpdater {

    const val DEFAULT_GITHUB_REPO = "Jawuilp/kizentube"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun getCurrentVersionName(context: Context): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    /**
     * Checks GitHub Releases API (default: Jawuilp/kizentube) for the latest APK release.
     */
    fun checkLatestRelease(context: Context): ReleaseInfo? {
        val repo = ConfigManager.updateRepo.trim().ifBlank { DEFAULT_GITHUB_REPO }
        val apiUrl = if (repo.startsWith("http://") || repo.startsWith("https://")) {
            repo
        } else {
            "https://api.github.com/repos/$repo/releases/latest"
        }

        return try {
            val req = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "KizenTube-Updater/1.0")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return null
                if (!resp.isSuccessful) return null
                val json = JSONObject(body)
                val tag = json.optString("tag_name", "").trim()
                val title = json.optString("name", tag).trim()
                val notes = json.optString("body", "").trim()

                var apkUrl = ""
                var apkName = "kizentube.apk"
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        val name = a.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = a.optString("browser_download_url", "")
                            apkName = name
                            break
                        }
                    }
                }
                if (apkUrl.isBlank()) return null

                val current = getCurrentVersionName(context)
                val isNewer = compareSemVer(tag.removePrefix("v"), current.removePrefix("v")) > 0
                ReleaseInfo(
                    tagName = tag,
                    title = title,
                    notes = notes,
                    apkDownloadUrl = apkUrl,
                    apkName = apkName,
                    isNewer = isNewer
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getUpdateApkFile(context: Context): File {
        val dir = File(context.cacheDir, "updates")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "kizentube-update.apk")
    }

    /**
     * Downloads an APK from a direct URL into cacheDir/updates/kizentube-update.apk.
     */
    fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (Int) -> Unit
    ): File? {
        return try {
            val req = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "KizenTube-Updater/1.0")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                val totalBytes = body.contentLength()
                val outFile = getUpdateApkFile(context)

                body.byteStream().use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var copied = 0L
                        var lastPct = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (totalBytes > 0) {
                                val pct = ((copied * 100) / totalBytes).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
                outFile
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Launches Android's native package installer for the downloaded or PC-uploaded APK.
     */
    fun launchApkInstaller(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists() || apkFile.length() < 100_000L) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            return false
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
        return true
    }

    private fun compareSemVer(remote: String, local: String): Int {
        val rParts = remote.split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
        val lParts = local.split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val rv = rParts.getOrElse(i) { 0 }
            val lv = lParts.getOrElse(i) { 0 }
            if (rv != lv) return rv.compareTo(lv)
        }
        return 0
    }
}
