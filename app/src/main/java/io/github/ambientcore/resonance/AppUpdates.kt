package io.github.ambientcore.resonance

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdate(val version: String, val apkUrl: String)
data class UpdateUi(val checking: Boolean = false, val available: AppUpdate? = null, val error: String? = null)

private val stableVersion = Regex("""v?(\d+)\.(\d+)\.(\d+)""")

fun isNewerVersion(candidate: String, installed: String): Boolean {
    fun parts(value: String): List<Int> = stableVersion.matchEntire(value.substringBefore('-'))?.groupValues
        ?.drop(1)?.map { it.toInt() } ?: throw IllegalArgumentException("Invalid release version: $value")
    val remote = parts(candidate)
    val current = parts(installed)
    for (index in remote.indices) {
        if (remote[index] != current[index]) return remote[index] > current[index]
    }
    return false
}

internal fun appUpdateFromJson(json: JSONObject, installedVersion: String): AppUpdate? {
    if (json.getBoolean("draft") || json.getBoolean("prerelease")) return null
    val tag = json.getString("tag_name")
    if (!stableVersion.matches(tag)) throw IOException("GitHub returned an invalid stable version")
    if (!isNewerVersion(tag, installedVersion)) return null
    val version = tag.removePrefix("v")
    val fileName = "murmurr_$version.apk"
    val assets = json.getJSONArray("assets").objects().filter { it.getString("name") == fileName }
    if (assets.size != 1) throw IOException("Latest release does not have a unique Android APK")
    val asset = assets.single()
    if (asset.getString("state") != "uploaded" || asset.getLong("size") <= 0) throw IOException("Release APK is not ready")
    val url = asset.getString("browser_download_url")
    if (url != "https://github.com/AMBIENTCORE/murrmurr/releases/download/$tag/$fileName") {
        throw IOException("Release APK is not hosted in the murrmurr GitHub repository")
    }
    return AppUpdate(version, url)
}

internal fun latestAppUpdate(
    installedVersion: String = BuildConfig.VERSION_NAME,
    endpoint: URL = URL("https://api.github.com/repos/AMBIENTCORE/murrmurr/releases/latest"),
): AppUpdate? {
    val connection = endpoint.openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "murrmurr/${BuildConfig.VERSION_NAME}")
        if (connection.responseCode != 200) throw IOException("GitHub update check returned HTTP ${connection.responseCode}")
        val bytes = connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > 512 * 1024) throw IOException("GitHub release response is too large")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return appUpdateFromJson(JSONObject(bytes.toString(Charsets.UTF_8)), installedVersion)
    } finally { connection.disconnect() }
}
