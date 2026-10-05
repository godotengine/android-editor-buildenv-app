package org.godotengine.godot_gradle_build_environment

import android.content.Context
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

object TarXzExtractor {
    
    fun extractLocalTarXz(
        context: Context, 
        localTarXzUri: Uri, 
        destDir: File, 
        onProgress: ((Int, Long) -> Unit)? = null
    ) {
        context.contentResolver.openInputStream(localTarXzUri).use { inputStream ->
            if (inputStream == null) {
                throw IOException("Failed to open local rootfs file")
            }
            extractTarXz(inputStream, destDir, onProgress)
        }
    }

    fun extractAssetTarXz(
        context: Context, 
        assetTarXz: String, 
        destDir: File, 
        onProgress: ((Int, Long) -> Unit)? = null
    ) {
        context.assets.open(assetTarXz).use { inputStream ->
            extractTarXz(inputStream, destDir, onProgress)
        }
    }

    fun extractFileTarXz(
        sourceFile: File, 
        destDir: File, 
        onProgress: ((Int, Long) -> Unit)? = null
    ) {
        sourceFile.inputStream().use { inputStream ->
            extractTarXz(inputStream, destDir, onProgress)
        }
    }

    private fun extractTarXz(
        inputStream: InputStream, 
        destDir: File, 
        onProgress: ((Int, Long) -> Unit)?
    ) {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw IllegalStateException("Could not create destination dir: ${destDir.absolutePath}")
        }

        val destRoot = destDir.canonicalFile
        val sharedBuffer = ByteArray(128 * 1024)
        
        val startTime = System.currentTimeMillis()
        var lastUpdateTime = startTime
        var fileCount = 0

        // NEW: We define a reusable function to check the time and update the UI
        val tickProgress = {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastUpdateTime >= 50) {
                lastUpdateTime = currentTime
                onProgress?.invoke(fileCount, currentTime - startTime)
            }
        }

        BufferedInputStream(inputStream).use { buf ->
            XZCompressorInputStream(buf).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        fileCount++
                        
                        // Check time before starting a new file
                        tickProgress()

                        val outFile = File(destDir, entry.name)
                        val outCanonical = outFile.canonicalFile

                        if (!outCanonical.path.startsWith(destRoot.path + File.separator)) {
                            entry = tar.nextTarEntry
                            continue
                        }

                        when {
                            entry.isDirectory -> {
                                if (!outCanonical.exists() && !outCanonical.mkdirs()) {
                                    throw IllegalStateException("Could not create dir: ${outCanonical.absolutePath}")
                                }
                                applyMode(outCanonical, entry.mode)
                                applyMtime(outCanonical, entry.modTime.time)
                            }

                            entry.isSymbolicLink -> {
                                outCanonical.parentFile?.mkdirs()
                                try {
                                    Os.symlink(entry.linkName, outCanonical.path)
                                } catch (e: ErrnoException) {
                                    throw IllegalStateException("Failed to create symlink ${outCanonical.path} -> ${entry.linkName}: ${e.errno}", e)
                                }
                                applyMtime(outCanonical, entry.modTime.time)
                            }

                            entry.isLink -> {
                                outCanonical.parentFile?.mkdirs()
                                val target = File(destDir, entry.linkName).canonicalPath
                                try {
                                    Os.link(target, outCanonical.path)
                                } catch (e: ErrnoException) {
                                    // Pass the tickProgress function down
                                    copyFromFile(File(target), outCanonical, sharedBuffer, tickProgress)
                                }
                                applyMode(outCanonical, entry.mode)
                                applyMtime(outCanonical, entry.modTime.time)
                            }

                            else -> {
                                outCanonical.parentFile?.let { if (!it.exists()) it.mkdirs() }
                                FileOutputStream(outCanonical).use { fos ->
                                    // Pass the tickProgress function down
                                    copyStream(tar, fos, sharedBuffer, tickProgress)
                                }
                                applyMode(outCanonical, entry.mode)
                                applyMtime(outCanonical, entry.modTime.time)
                            }
                        }

                        entry = tar.nextTarEntry
                    }
                }
            }
        }
        
        // Final UI update when 100% complete
        val finalElapsedMs = System.currentTimeMillis() - startTime
        onProgress?.invoke(fileCount, finalElapsedMs)
    }

    // NEW: Accepts tickProgress and calls it while looping through large files
    private fun copyStream(
        input: TarArchiveInputStream, 
        output: FileOutputStream, 
        buffer: ByteArray,
        tickProgress: () -> Unit
    ) {
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            tickProgress() // Update UI during massive file writes!
        }
        output.flush()
    }

    // NEW: Accepts tickProgress and calls it while looping through large files
    private fun copyFromFile(
        src: File, 
        dst: File, 
        buffer: ByteArray,
        tickProgress: () -> Unit
    ) {
        src.inputStream().use { `in` ->
            dst.outputStream().use { out ->
                while (true) {
                    val n = `in`.read(buffer)
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                    tickProgress() // Update UI during massive file copies!
                }
            }
        }
    }

    private fun applyMtime(f: File, epochMillis: Long) {
        @Suppress("ResultOfMethodCallIgnored")
        f.setLastModified(epochMillis)
    }

    private fun applyMode(f: File, mode: Int) {
        try {
            Os.chmod(f.path, mode)
            return
        } catch (_: Throwable) {
            // Fall through.
        }

        val ownerRead = (mode and 0b100_000_000) != 0
        val ownerWrite = (mode and 0b010_000_000) != 0
        val ownerExec = (mode and 0b001_000_000) != 0

        f.setReadable(ownerRead, true)
        f.setWritable(ownerWrite, true)
        f.setExecutable(ownerExec, true)
    }
}
