package org.godotengine.godot_gradle_build_environment

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class BuildEnvironment(
    private val context: Context,
    private val rootfs: String,
    private val projectRoot: String,
) {

    companion object {
        private const val TAG = "BuildEnvironment"
        private const val STDOUT_TAG = "BuildEnvironment-Stdout"
        private const val STDERR_TAG = "BuildEnvironment-Stderr"

        const val OUTPUT_INFO = 0;
        const val OUTPUT_STDOUT = 1;
        const val OUTPUT_STDERR = 2;

        const val ROOTFS_GITHUB_REPO = "godotengine/android-editor-buildenv-rootfs"
        const val ROOTFS_VERSION_CUSTOM = "custom"

        private const val ROOTFS_FILENAME = "alpine-android-35-jdk17.tar.xz"
        private const val ROOTFS_ASSET_PATH = "linux-rootfs/$ROOTFS_FILENAME"

    }

    private val defaultEnv: List<String>
    private var currentProcess: Process? = null

    init {
        defaultEnv = try {
            File(rootfs, "env").readLines()
        } catch (e: IOException) {
            Log.i(TAG, "Unable to read default environment from $rootfs/env: $e")
            emptyList<String>()
        }
    }

    private fun logAndCaptureStream(reader: BufferedReader, handler: (String) -> Unit): Thread {
        return Thread {
            try {
                reader.useLines { lines ->
                    lines.forEach { line ->
                        handler(line)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading stream: ${e.message}");
                return@Thread
            }
        }
    }

    private fun sanitizeCommand(cmd: List<String>): String {
        val cmdString = cmd.toString()
        val pattern = Regex("""-P(debug|release)_keystore_[a-z_]+=[^,\s\]"]+""")
        return pattern.replace(cmdString) { matchResult ->
            val prefix = matchResult.value.substringBefore('=')
            "$prefix=<REDACTED>"
        }
    }

    fun executeCommand(
        path: String,
        args: List<String>,
        binds: List<String>,
        workDir: String,
        outputHandler: (Int, String) -> Unit,
    ): Int {
        if (currentProcess != null) {
            Log.e(TAG, "Cannot run a new process when there's already one running")
            return 255
        }

        val libDir = context.applicationInfo.nativeLibraryDir
        val proot = File(libDir, "libproot.so").absolutePath

        val prootTmpDir = AppPaths.getProotTmpDir(context)
        prootTmpDir.mkdirs()

        val env = HashMap(System.getenv())
        env["PROOT_TMP_DIR"] = prootTmpDir.absolutePath
        env["PROOT_LOADER"] = File(libDir, "libproot-loader.so").absolutePath
        env["PROOT_LOADER_32"] = File(libDir, "libproot-loader32.so").absolutePath
        //env["PROOT_NO_SECCOMP"] = "1"
        //env["PROOT_VERBOSE"] = "9"

        //val qemu = File(libDir, "libqemu-x86_64.so")

        val cmd = buildList {
            addAll(
                listOf(
                    proot,
                    //"-0",
                    "-R", rootfs,
                    "-w", workDir,
                    //"-q", qemu.absolutePath,
                    // Stuff to try:
                    //"--link2symlink", "-L", "--tcsetsf2tcsets",
                )
            )
            for (bind in binds) {
                addAll(listOf("-b", bind))
            }
            addAll(
                listOf(
                    "/usr/bin/env", "-i",
                )
            )
            addAll(defaultEnv)
            add("GRADLE_OPTS=-Djava.io.tmpdir=/alt-tmp")
            add(path)
            addAll(args)
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Cmd: " + sanitizeCommand(cmd))
        }

        currentProcess = ProcessBuilder(cmd).apply {
            directory(context.filesDir)
            environment().putAll(env)
        }.start()

        val stdoutThread = logAndCaptureStream(BufferedReader(InputStreamReader(currentProcess?.inputStream)), { line ->
            Log.i(STDOUT_TAG, line)
            outputHandler(OUTPUT_STDOUT, line)
        })
        val stderrThread = logAndCaptureStream(BufferedReader(InputStreamReader(currentProcess?.errorStream)), { line ->
            Log.i(STDERR_TAG, line)
            outputHandler(OUTPUT_STDERR, line)
        })

        stdoutThread.start()
        stderrThread.start()

        stdoutThread.join()
        stderrThread.join()

        val exitCode = currentProcess?.waitFor() ?: 255
        Log.i(TAG, "ExitCode: " + exitCode.toString())

        currentProcess = null
        return exitCode
    }

    private fun setupProject(projectPath: String, gradleBuildDir: String): File {
        val fullPath = File(projectPath, gradleBuildDir)
        val hash = Integer.toHexString(fullPath.absolutePath.hashCode())
        val workDir = File(projectRoot, hash)

        // Clean up assets from a previous export.
        if (workDir.exists()) {
            val apkAssetsDir = File(workDir, "src/main/assets")
            if (apkAssetsDir.exists()) {
                apkAssetsDir.deleteRecursively()
            }

            val aabAssetsDir = File(workDir, "assetPackInstallTime/src/main/assets")
            if (aabAssetsDir.exists()) {
                aabAssetsDir.deleteRecursively()
            }
        }

        if (!FileUtils.tryCopyDirectory(fullPath, workDir)) {
            throw IOException("Failed to copy $fullPath to $workDir")
        }

        ProjectInfo.writeToDirectory(workDir, projectPath, gradleBuildDir)

        return workDir
    }

    fun cleanProject(projectPath: String, gradleBuildDir: String) {
        val fullPath = File(projectPath, gradleBuildDir)
        val hash = Integer.toHexString(fullPath.absolutePath.hashCode())
        val workDir = File(projectRoot, hash)

        if (workDir.exists()) {
            workDir.deleteRecursively()
        }
    }

    fun cleanGlobalCache() {
        val gradleCache = AppPaths.getGlobalGradleCache(context)
        if (gradleCache.exists()) {
            gradleCache.deleteRecursively()
        }
    }

    fun installRootfs(outputHandler: (Int, String) -> Unit) {
        val rootfs = File(this.rootfs)

        if (rootfs.exists()) {
            outputHandler(OUTPUT_INFO, "> Removing existing rootfs...")
            rootfs.deleteRecursively()
        }

        rootfs.mkdirs()

        val hasAsset = try {
            context.assets.list("linux-rootfs")?.contains(ROOTFS_FILENAME) == true
        } catch (e: Exception) {
            false
        }

        val version: String
        if (hasAsset) {
            outputHandler(OUTPUT_INFO, "> Extracting rootfs from assets...")
            TarXzExtractor.extractAssetTarXz(context, ROOTFS_ASSET_PATH, rootfs)
            version = ROOTFS_VERSION_CUSTOM
        } else {
            val tempFile = File(context.cacheDir, ROOTFS_FILENAME)
            try {
                val releaseTag = GitHubReleaseDownloader.downloadLatestReleaseAsset(
                    ROOTFS_GITHUB_REPO,
                    ROOTFS_FILENAME,
                    tempFile
                ) { message ->
                    outputHandler(OUTPUT_INFO, message)
                }
                version = releaseTag

                outputHandler(OUTPUT_INFO, "> Extracting rootfs...")
                TarXzExtractor.extractFileTarXz(tempFile, rootfs)
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }

        val resolveConf = File(rootfs, "etc/resolv.conf")
        val resolveConfOverride = File(rootfs, "etc/resolv.conf.override")
        if (resolveConfOverride.exists()) {
            if (FileUtils.tryCopyFile(resolveConfOverride, resolveConf)) {
                resolveConfOverride.delete()
            }
        }

        val readyFile = AppPaths.getRootfsReadyFile(File(this.rootfs))
        FileOutputStream(readyFile).use { fos ->
            fos.write(version.toByteArray())
            fos.fd.sync()
        }
        outputHandler(OUTPUT_INFO, "> Rootfs installation complete!")
    }

    fun deleteRootfs() {
        val rootfs = File(this.rootfs)
        if (rootfs.exists()) {
            rootfs.deleteRecursively()
        }
    }

    fun getRootfsVersion(): String? {
        val readyFile = AppPaths.getRootfsReadyFile(File(this.rootfs))
        return if (readyFile.exists()) {
            readyFile.readText().trim()
        } else {
            null
        }
    }

    private fun findAapt2Jars(root: File): List<File> {
        val regex = Regex("""aapt2-.*-linux\.jar""")
        return root.walkTopDown()
            .filter { it.isFile && regex.matches(it.name) }
            .toList()
    }

    /**
     * Patches AAPT2 JAR files in the specified directory by replacing the aapt2 binary
     * with the one bundled in the rootfs.
     *
     * @param hostDir The directory on the host filesystem to search for AAPT2 JARs
     * @param boundPath The path where hostDir is bound inside the proot environment
     * @param outputHandler Handler for output messages
     * @return true if all patches succeeded or no JARs found; otherwise, false if any patch failed
     */
    private fun patchAapt2Jars(hostDir: File, boundPath: String, outputHandler: (Int, String) -> Unit): Boolean {
        val jarFiles = findAapt2Jars(hostDir)
        if (jarFiles.isEmpty()) {
            return true
        }

        outputHandler(OUTPUT_INFO, "> Patching ${jarFiles.size} AAPT2 JAR(s) in $boundPath...")

        for (jarFile in jarFiles) {
            Log.d(TAG, "Found jar file: ${jarFile.absolutePath}")
            val jarFileRelative = jarFile.relativeTo(hostDir)
            val args = listOf(
                "-c",
                "jar -u -f $boundPath/${jarFileRelative.path} -C $(dirname $(which aapt2)) aapt2",
            )
            val jarUpdateResult = executeCommand(
                "/bin/bash",
                args,
                listOf("${hostDir.absolutePath}:$boundPath"),
                boundPath,
                outputHandler
            )
            if (jarUpdateResult != 0) {
                outputHandler(OUTPUT_STDERR, "Failed to patch ${jarFile.name}")
                return false
            }
        }

        return true
    }

    private fun executeGradleInternal(gradleArgs: List<String>, workDir: File, outputHandler: (Int, String) -> Unit): Int {
        val gradleCache = AppPaths.getGlobalGradleCache(context)
        gradleCache.mkdirs()

        val gradleCmd = buildString {
            append("bash gradlew ")
            append(gradleArgs.joinToString(" ") { "\"$it\""})
            if ("--no-daemon" !in gradleArgs) {
                append(" --no-daemon")
            }
        }

        val path = "/bin/bash"
        val args = listOf(
            "-c",
            gradleCmd,
        )
        val binds = listOf(
            Environment.getExternalStorageDirectory().absolutePath,
            "${workDir.absolutePath}:/project",
            "${gradleCache.absolutePath}:/project/?",
        )

        return executeCommand(path, args, binds, "/project", outputHandler)
    }

    private fun isRootfsReady(): Boolean {
        return AppPaths.getRootfsReadyFile(File(rootfs)).exists()
    }

    fun executeGradle(gradleArgs: List<String>, projectPath: String, gradleBuildDir: String, outputHandler: (Int, String) -> Unit): Int {
        if (!isRootfsReady()) {
            outputHandler(OUTPUT_STDERR, "Rootfs isn't installed. Install it in the Godot Gradle Build Environment app.")
            return 255
        }

        val workDir = try {
            setupProject(projectPath, gradleBuildDir)
        } catch (e: Exception) {
            outputHandler(OUTPUT_STDERR, "Unable to setup project: ${e.message}")
            return 255
        }

        val stderrBuilder = StringBuilder()
        val captureOutputHandler: (Int, String) -> Unit = { type, line ->
            if (type == OUTPUT_STDERR) {
                synchronized(stderrBuilder) {
                    stderrBuilder.appendLine(line)
                }
            }
            outputHandler(type, line)
        }

        var result = executeGradleInternal(gradleArgs, workDir, captureOutputHandler)

        val stderr = stderrBuilder.toString()
        if (result == 0 && stderr.contains("BUILD FAILED")) {
            // Sometimes Gradle builds fail, but it still gives an exit code of 0.
            result = 1;
        }
        stderrBuilder.clear()

        // Detect if we hit the AAPT2 issue.
        if (result != 0 && stderr.contains(Regex("""AAPT2 aapt2.*Daemon startup failed"""))) {
            outputHandler(OUTPUT_INFO, "> Detected AAPT2 issue - attempting to patch the JAR files...")

            // Patch AAPT2 JARs in both the project directory and the global gradle cache
            val gradleCache = AppPaths.getGlobalGradleCache(context)
            val patchSuccess = patchAapt2Jars(workDir, "/project", outputHandler) &&
                               patchAapt2Jars(gradleCache, "/project/?", outputHandler)

            if (!patchSuccess) {
                // If patching failed, there's not much else we can do.
                return 1
            }

            // Now, try running Gradle again!
            outputHandler(OUTPUT_INFO, "> Retrying Gradle build...")
            result = executeGradleInternal(gradleArgs, workDir, captureOutputHandler)
            val stderr = stderrBuilder.toString()
            if (result == 0 && stderr.contains("BUILD FAILED")) {
                result = 1;
            }
        }

        return result
    }

    fun killCurrentProcess() {
        currentProcess?.let { process ->
            currentProcess = null
            process.destroy()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                process.waitFor(500, TimeUnit.MILLISECONDS)
                process.destroyForcibly()
            }
        }
    }

}