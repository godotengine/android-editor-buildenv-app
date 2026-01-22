package org.godotengine.godot_gradle_build_environment

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object GitHubReleaseDownloader {

    fun downloadLatestReleaseAsset(
        repo: String,
        filename: String,
        destFile: File,
        progressCallback: (String) -> Unit
    ): String {
        val apiUrl = "https://api.github.com/repos/$repo/releases/latest"

        progressCallback("> Fetching latest release info from GitHub...")
        val releaseJson = fetchUrl(apiUrl)

        val tag = parseReleaseTag(releaseJson)
            ?: throw IOException("Could not parse release tag from latest release")

        val assetUrl = parseGitHubReleaseForAsset(releaseJson, filename)
            ?: throw IOException("Asset '$filename' not found in latest release")

        progressCallback("> Downloading $filename...")
        downloadFile(assetUrl, destFile, progressCallback)

        return tag
    }

    fun getLatestReleaseTag(repo: String): String? {
        val apiUrl = "https://api.github.com/repos/$repo/releases/latest"

        return try {
            val releaseJson = fetchUrl(apiUrl)
            parseReleaseTag(releaseJson)
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchUrl(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                throw IOException("HTTP error $responseCode: ${connection.responseMessage}")
            }

            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseReleaseTag(json: String): String? {
        return try {
            val jsonObject = JSONObject(json)
            jsonObject.optString("tag_name").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseGitHubReleaseForAsset(json: String, filename: String): String? {
        return try {
            val jsonObject = JSONObject(json)
            val assets = jsonObject.optJSONArray("assets") ?: return null

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val assetName = asset.optString("name", "")
                if (assetName == filename) {
                    return asset.optString("browser_download_url").takeIf { it.isNotEmpty() }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun downloadFile(url: String, destFile: File, progressCallback: (String) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                throw IOException("HTTP error $responseCode: ${connection.responseMessage}")
            }

            connection.inputStream.use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytes = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead

                        if (totalBytes % (1024 * 1024) == 0L) {
                            val mb = totalBytes / (1024 * 1024)
                            progressCallback("> Downloaded ${mb}MB...")
                        }
                    }
                }
            }

            val totalMB = destFile.length() / (1024 * 1024)
            progressCallback("> Download complete: ${totalMB}MB")
        } finally {
            connection.disconnect()
        }
    }
}
