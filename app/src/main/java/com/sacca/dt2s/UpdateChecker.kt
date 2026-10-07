package com.sacca.dt2s

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

data class ReleaseCheck(
    val latestVersion: String,
    val releaseUrl: String,
    val apkUrl: String?,
    val updateAvailable: Boolean
)

object UpdateChecker {
    const val PREFS_NAME = "update_check"
    const val PREF_CHECKED_AT = "checked_at"
    const val PREF_LATEST_VERSION = "latest_version"
    const val PREF_RELEASE_URL = "release_url"
    const val PREF_APK_URL = "apk_url"
    const val PREF_UPDATE_AVAILABLE = "update_available"

    private const val RELEASE_API = "https://api.github.com/repos/sacca97/dt2s/releases/latest"

    fun check(context: Context): ReleaseCheck {
        val connection = URL(RELEASE_API).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "DoubleTap2Sleep")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                error("GitHub returned HTTP ${connection.responseCode}")
            }

            val release = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val tag = release.getString("tag_name")
            val latestVersion = tag.removePrefix("v")
            return ReleaseCheck(
                latestVersion = latestVersion,
                releaseUrl = release.getString("html_url"),
                apkUrl = findApkUrl(release),
                updateAvailable = compareVersions(latestVersion, currentVersion(context)) > 0
            )
        } finally {
            connection.disconnect()
        }
    }

    fun save(context: Context, check: ReleaseCheck) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putLong(PREF_CHECKED_AT, System.currentTimeMillis())
            putString(PREF_LATEST_VERSION, check.latestVersion)
            putString(PREF_RELEASE_URL, check.releaseUrl)
            if (check.apkUrl != null) {
                putString(PREF_APK_URL, check.apkUrl)
            } else {
                remove(PREF_APK_URL)
            }
            putBoolean(PREF_UPDATE_AVAILABLE, check.updateAvailable)
        }
    }

    private fun findApkUrl(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            if (asset.optString("name").endsWith(".apk")) {
                return asset.getString("browser_download_url")
            }
        }
        return null
    }

    private fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"

    private fun compareVersions(left: String, right: String): Int {
        fun parts(version: String) = version.split('.').map { it.toInt() }
        val leftParts = parts(left)
        val rightParts = parts(right)
        require(leftParts.size in 2..3 && rightParts.size in 2..3)
        return (0..2).firstNotNullOfOrNull { index ->
            val difference = (leftParts.getOrElse(index) { 0 } - rightParts.getOrElse(index) { 0 })
            difference.takeIf { it != 0 }
        } ?: 0
    }
}

class UpdateCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        UpdateChecker.save(applicationContext, UpdateChecker.check(applicationContext))
        Result.success()
    } catch (_: Exception) {
        // Fail silently: skip this run, the next daily check will try again.
        Result.failure()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "daily_update_check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
