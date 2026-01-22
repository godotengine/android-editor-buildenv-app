package org.godotengine.godot_gradle_build_environment

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object TarXzExtractor {
    fun extractAssetTarXz(context: Context, assetTarXz: String, destDir: File) {
        context.assets.open(assetTarXz).use { inputStream ->
            extractTarXz(inputStream, destDir)
        }
    }

    fun extractFileTarXz(sourceFile: File, destDir: File) {
        sourceFile.inputStream().use { inputStream ->
            extractTarXz(inputStream, destDir)
        }
    }

    private fun extractTarXz(inputStream: InputStream, destDir: File) {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw IllegalStateException("Could not create destination dir: ${destDir.absolutePath}")
        }

        val destRoot = destDir.canonicalFile

        BufferedInputStream(inputStream).use { buf ->
            XZCompressorInputStream(buf).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    var entry = tar.nextTarEntry
                    while (entry != null) {
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
                                    copyFromFile(File(target), outCanonical)
                                }
                                applyMode(outCanonical, entry.mode)
                                applyMtime(outCanonical, entry.modTime.time)
                            }

                            else -> {
                                outCanonical.parentFile?.let { if (!it.exists()) it.mkdirs() }
                                FileOutputStream(outCanonical).use { fos ->
                                    copyStream(tar, fos)
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
    }

    private fun copyStream(input: TarArchiveInputStream, output: FileOutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
        output.flush()
    }

    private fun copyFromFile(src: File, dst: File) {
        src.inputStream().use { `in` ->
            dst.outputStream().use { out ->
                val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val n = `in`.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
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
