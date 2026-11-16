package com.vpnapp.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

class UpdateFileProvider : FileProvider()

internal data class GitHubRelease(
    val version: String, val notes: String, val url: String, val bytes: Long, val sha256: String
)

internal object GitHubUpdate {
    private const val repository = "morezaGeek/SecureVPN-Android"
    private const val maxApkBytes = 256L * 1024 * 1024
    private val tagPattern = Regex("v([0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6})")
    private val digestPattern = Regex("sha256:[a-fA-F0-9]{64}")

    suspend fun latest(installed: String): GitHubRelease? = withContext(Dispatchers.IO) {
        val url = URL("https://api.github.com/repos/$repository/releases/latest")
        val connection = open(url, "application/vnd.github+json")
        try {
            if (connection.responseCode != 200) throw IOException("GitHub returned HTTP ${connection.responseCode}. Try again later.")
            val body = connection.inputStream.bufferedReader().use {
                val buffer = CharArray(4096)
                buildString {
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        if (length + count > 512 * 1024) throw IOException("Release details are too large.")
                        append(buffer, 0, count)
                    }
                }
            }
            parseRelease(JSONObject(body), installed, Build.SUPPORTED_ABIS.toList())
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseRelease(json: JSONObject, installed: String, abis: List<String>): GitHubRelease? {
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
        val tag = json.optString("tag_name")
        val version = tagPattern.matchEntire(tag)?.groupValues?.get(1) ?: return null
        if (!isVersionNewer(version, installed)) return null

        val assets = json.optJSONArray("assets") ?: throw IOException("No APK is available for this release.")
        val byName = (0 until assets.length()).mapNotNull(assets::optJSONObject).associateBy { it.optString("name") }
        val names = abis.filter { it in listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
            .map { "SecureVPN-$version-$it.apk" } + "SecureVPN-$version.apk"
        for (name in names) {
            val asset = byName[name] ?: continue
            val url = "https://github.com/$repository/releases/download/$tag/$name"
            val size = asset.optLong("size")
            val digest = asset.optString("digest")
            if (asset.optString("state") == "uploaded" && asset.optString("browser_download_url") == url &&
                size in 1..maxApkBytes && digestPattern.matches(digest)
            ) {
                return GitHubRelease(version, json.optString("body").take(16_000), url, size, digest.removePrefix("sha256:").lowercase())
            }
        }
        throw IOException("No verified APK is available for this device.")
    }

    internal fun isVersionNewer(version: String, installed: String): Boolean {
        val next = tagPattern.matchEntire("v$version")?.groupValues?.get(1) ?: return false
        val current = tagPattern.matchEntire("v${installed.substringBefore('-')}")?.groupValues?.get(1) ?: return false
        val order = next.split('.').map(String::toInt).zip(current.split('.').map(String::toInt))
        return order.firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
    }

    suspend fun download(context: Context, release: GitHubRelease, progress: (Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "updates").apply { mkdirs() }
            val file = File.createTempFile("update-", ".apk", directory)
            var verified = false
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                var reported = 0L
                val connection = openDownload(URL(release.url))
                try {
                    connection.inputStream.buffered().use { input ->
                        file.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                bytes += count
                                if (bytes > release.bytes) throw IOException("The APK size does not match the release.")
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                                if (bytes - reported >= 256 * 1024) {
                                    progress(bytes)
                                    reported = bytes
                                }
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                if (bytes != release.bytes || digest.digest().joinToString("") { "%02x".format(it) } != release.sha256) {
                    throw IOException("The APK download is damaged. Please try again.")
                }
                currentCoroutineContext().ensureActive()
                progress(bytes)
                verifyApk(context, file, release.version)
                currentCoroutineContext().ensureActive()
                verified = true
                file
            } finally {
                if (!verified) file.delete()
            }
        }

    @Suppress("DEPRECATION")
    private fun verifyApk(context: Context, file: File, version: String) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: throw IOException("Android could not read the APK.")
        val installed = pm.getPackageInfo(context.packageName, flags)
        fun code(info: android.content.pm.PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        fun signers(info: android.content.pm.PackageInfo) =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)?.toSet().orEmpty()
        if (archive.packageName != context.packageName || archive.versionName != version || code(archive) <= code(installed)) {
            throw IOException("This APK is not a newer SecureVPN build.")
        }
        val trusted = signers(installed)
        if (trusted.isEmpty() || signers(archive) != trusted) throw IOException("This APK uses a different signing certificate.")
    }

    fun installer(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.update-files", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun open(url: URL, accept: String): HttpsURLConnection =
        (url.openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 12_000
            readTimeout = 8_000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "SecureVPN-Android")
        }

    private fun openDownload(initial: URL): HttpsURLConnection {
        var url = initial
        repeat(6) {
            if (url.protocol != "https" || url.userInfo != null || url.port !in listOf(-1, 443) ||
                url.host !in listOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
            ) throw IOException("GitHub returned an unexpected download address.")
            val connection = open(url, "application/octet-stream")
            try {
                val status = connection.responseCode
                if (status == 200) return connection
                val next = connection.getHeaderField("Location")
                if (status !in listOf(301, 302, 303, 307, 308) || next.isNullOrBlank()) {
                    throw IOException("Download failed (HTTP $status).")
                }
                url = URL(url, next)
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        throw IOException("Too many download redirects.")
    }
}
