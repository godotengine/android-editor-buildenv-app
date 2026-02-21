package org.godotengine.godot_gradle_build_environment

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import androidx.core.content.edit
import androidx.core.net.toUri

object FileUtils {

    private val TAG = FileUtils::class.java.simpleName
    private const val PREF_NAME = "tree_uri_prefs"

    const val ADDONS_DIR_NAME = "addons"

    fun tryCopyFile(source: File, dest: File): Boolean {
        try {
            source.copyTo(dest)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy ${source.absolutePath} to ${dest.absolutePath}: ${e.message}", e)
            return false
        }
        return true
    }

    /**
     * Recursively calculates the total size of a directory in bytes.
     */
    fun calculateDirectorySize(directory: File): Long {
        if (!directory.exists() || !directory.isDirectory) {
            return 0L
        }

        var size = 0L
        val files = directory.listFiles() ?: return 0L

        for (file in files) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (Files.isSymbolicLink(file.toPath())) {
                    continue
                }
            }

            size += if (file.isDirectory) {
                calculateDirectorySize(file)
            } else {
                file.length()
            }
        }

        return size
    }

    /**
     * Formats a byte size into a human-readable string with appropriate units.
     * For example: "1.5 KB", "234.6 MB", "2.3 GB"
     */
    fun formatSize(bytes: Long): String {
        if (bytes < 1024) {
            return "$bytes B"
        }

        val units = arrayOf("KB", "MB", "GB", "TB")
        val exp = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
        val unitIndex = min(exp - 1, units.size - 1)
        val value = bytes / 1024.0.pow((unitIndex + 1).toDouble())

        return String.format("%.1f %s", value, units[unitIndex])
    }

    fun importAndroidProject(context: Context, projectTreeUri: Uri, gradleBuildDir: String, destDir: File) {
        val root = DocumentFile.fromTreeUri(context, projectTreeUri)
            ?: throw IOException("Invalid tree uri")

        val gradleDir = findDirByPath(root, gradleBuildDir)
            ?: throw IOException("Gradle build dir not found: $gradleBuildDir")

        val addonsDir = root.listFiles().firstOrNull {
            it.isDirectory && it.name == ADDONS_DIR_NAME
        }

        if (destDir.exists()) {
            val apkAssetsDir = File(destDir, "src/main/assets")
            if (apkAssetsDir.exists()) apkAssetsDir.deleteRecursively()

            val aabAssetsDir = File(destDir, "assetPackInstallTime/src/main/assets")
            if (aabAssetsDir.exists()) aabAssetsDir.deleteRecursively()
        } else {
            destDir.mkdirs()
        }

        copyDirectoryMerge(context, gradleDir, destDir)

        if (addonsDir != null) {
            val localAddons = File(destDir, ADDONS_DIR_NAME)
            if (localAddons.exists()) {
                localAddons.deleteRecursively()
            }
            localAddons.mkdirs()
            copyDirectoryMerge(context, addonsDir, localAddons)
        }
    }

    private fun findDirByPath(parent: DocumentFile, relativePath: String): DocumentFile? {
        var current: DocumentFile? = parent

        val parts = relativePath.trim('/').split('/')

        for (part in parts) {
            current = current?.listFiles()?.firstOrNull {
                it.isDirectory && it.name == part
            } ?: return null
        }

        return current
    }

    private fun copyDirectoryMerge(context: Context, src: DocumentFile, dest: File) {
        src.listFiles().forEach { file ->
            val name = file.name ?: return@forEach

            if (file.isDirectory) {
                val newDir = File(dest, name)
                if (!newDir.exists()) newDir.mkdirs()
                copyDirectoryMerge(context, file, newDir)
            } else {
                val outFile = File(dest, name)
                context.contentResolver.openInputStream(file.uri).use { input ->
                    FileOutputStream(outFile, false).use { output ->
                        input?.copyTo(output)
                    }
                }
            }
        }
    }

    fun saveProjectTreeUri(context: Context, projectPath: String, projectTreeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            projectTreeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit { putString(projectPath, projectTreeUri.toString()) }
    }

    fun deleteProjectTreeUri(context: Context, projectPath: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val projectTreeUri = prefs.getString(projectPath, null) ?: return

        context.contentResolver.releasePersistableUriPermission(
            projectTreeUri.toUri(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.edit { remove(projectPath) }
    }

    fun getProjectTreeUri(context: Context, projectPath: String): Uri? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val uriString = prefs.getString(projectPath, null)
        return uriString?.toUri()
    }
}
