package com.sacca.dt2s

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateInstaller {

    class InstallException(message: String) : Exception(message)

    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    private const val MAX_REDIRECTS = 5
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    fun canRequestInstalls(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun manageUnknownSourcesIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}")
    )

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun download(
        context: Context,
        url: String,
        version: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File {
        val target = apkFile(context, version)
        clearCache(context)
        checkNotNull(target.parentFile).mkdirs()
        val partial = File(target.parentFile, "${target.name}.part")
        val connection = openWithRedirects(url)
        try {
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
            if (total > 0 && partial.length() != total) {
                throw InstallException("Incomplete download")
            }
            validate(context, partial, version)
            check(partial.renameTo(target))
            return target
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    private fun apkFile(context: Context, version: String): File =
        File(context.cacheDir, "updates/dt2s-$version.apk")

    private fun clearCache(context: Context) {
        File(context.cacheDir, "updates").listFiles()?.forEach(File::delete)
    }

    private fun openWithRedirects(url: String): HttpURLConnection {
        var current = URL(url)
        repeat(MAX_REDIRECTS + 1) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "DoubleTap2Sleep")
            }
            val code = connection.responseCode
            when {
                code == HttpURLConnection.HTTP_OK -> return connection
                code in REDIRECT_CODES -> {
                    val location = connection.getHeaderField("Location")
                        ?: throw InstallException("Redirect without a location")
                    connection.disconnect()
                    current = URL(current, location)
                }
                else -> {
                    connection.disconnect()
                    throw InstallException("Download failed with HTTP $code")
                }
            }
        }
        throw InstallException("Too many redirects")
    }

    @Suppress("DEPRECATION")
    private fun validate(context: Context, apk: File, expectedVersion: String) {
        val packageManager = context.packageManager
        val info = packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: throw InstallException("Downloaded file is not a valid APK")
        if (info.packageName != context.packageName.removeSuffix(".debug")) {
            throw InstallException("Downloaded APK targets ${info.packageName}")
        }
        if (info.versionName != expectedVersion) {
            throw InstallException("Downloaded APK is ${info.versionName}, expected $expectedVersion")
        }
        // If the APK would replace this very installation, the signing keys must match,
        // otherwise the system installer would fail with an opaque error later on.
        if (info.packageName == context.packageName) {
            val currentSigner = signer(
                packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            )
            val updateSigner = signer(
                packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            )
            if (currentSigner != null && updateSigner != null && currentSigner != updateSigner) {
                throw InstallException("Update is signed with a different key")
            }
        }
    }

    private fun signer(info: PackageInfo?): String? =
        info?.signingInfo?.apkContentsSigners?.firstOrNull()?.toCharsString()
}
