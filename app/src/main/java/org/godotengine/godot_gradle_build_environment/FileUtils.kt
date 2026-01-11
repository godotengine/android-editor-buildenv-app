package org.godotengine.godot_gradle_build_environment

import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

object FileUtils {

    private const val TAG = "FileUtils"

    fun tryCopyFile(source: File, dest: File): Boolean {
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy ${source.absolutePath} to ${dest.absolutePath}: ${e.message}")
            return false
        }
        return true
    }

    fun tryCopyDirectory(sourceDir: File, destDir: File): Boolean {
        if (!sourceDir.isDirectory) {
            Log.e(TAG, "Source directory ${sourceDir.absolutePath} not found")
            return false
        }

        var success = true
        sourceDir.walkTopDown().forEach { source ->
            val relativePath = source.relativeTo(sourceDir)
            val target = File(destDir, relativePath.path)

            try {
                if (source.isDirectory) {
                    if (!target.exists()) {
                        target.mkdirs()
                    }
                } else {
                    if (!tryCopyFile(source, target)) {
                        success = false
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to copy ${source.absolutePath} -> ${target.absolutePath}: ${e.message}")
                success = false
            }
        }

        return success
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

}
