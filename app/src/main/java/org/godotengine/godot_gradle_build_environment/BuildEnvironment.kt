package org.godotengine.godot_gradle_build_environment

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat.checkSelfPermission
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class BuildEnvironment(private val context: Context, private val rootfs: String, private val projectRoot: String) {

    companion object {
        private val TAG = BuildEnvironment::class.java.simpleName
        private val STDOUT_TAG = "$TAG-Stdout"
        private val STDERR_TAG = "$TAG-Stderr"

        const val OUTPUT_INFO = 0;
        const val OUTPUT_STDOUT = 1;
        const val OUTPUT_STDERR = 2;

        const val ROOTFS_GITHUB_REPO = "godotengine/android-editor-buildenv-rootfs"
        const val ROOTFS_VERSION_CUSTOM = "custom"

        private const val ROOTFS_FILENAME = "alpine-android-35-jdk17.tar.xz"
        private const val ROOTFS_ASSET_PATH = "linux-rootfs/$ROOTFS_FILENAME"

        private const val DIR_ACCESS_WAIT_DURATION = 120_000L // in milliseconds

    }

    private var currentProcess: Process? = null

    private val accessLock = ReentrantLock()
    private val accessLockCondition = accessLock.newCondition()
    private val grantedTreeUri = AtomicReference<Uri>()

    private fun getDefaultEnv(): List<String> {
        return try {
            File(rootfs, "env").readLines()
        } catch (e: IOException) {
            Log.i(TAG, "Unable to read default environment from $rootfs/env: $e")
            emptyList()
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
            addAll(getDefaultEnv())
            add("GRADLE_OPTS=-Djava.io.tmpdir=/alt-tmp")
            add(path)
            addAll(args)
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Cmd: " + sanitizeCommand(cmd))
        }

        try {
            currentProcess = ProcessBuilder(cmd).apply {
                directory(context.filesDir)
                environment().putAll(env)
            }.start()
        } catch (e: Exception) {
            outputHandler(OUTPUT_STDERR, "> CRITICAL ERROR: Android OS blocked the Linux environment from starting!")
            outputHandler(OUTPUT_STDERR, "> Details: ${e.message}")
            return 255
        }

        val stdoutThread = logAndCaptureStream(BufferedReader(InputStreamReader(currentProcess?.inputStream))) { line ->
            Log.i(STDOUT_TAG, line)
            outputHandler(OUTPUT_STDOUT, line)
        }
        val stderrThread = logAndCaptureStream(BufferedReader(InputStreamReader(currentProcess?.errorStream))) { line ->
            Log.i(STDERR_TAG, line)
            outputHandler(OUTPUT_STDERR, line)
        }

        stdoutThread.start()
        stderrThread.start()

        stdoutThread.join()
        stderrThread.join()

        val exitCode = currentProcess?.waitFor() ?: 255
        Log.i(TAG, "ExitCode: $exitCode")
        
        if (exitCode != 0) {
            outputHandler(OUTPUT_STDERR, "> Linux Environment crashed instantly with Exit Code: $exitCode")
        }

        currentProcess = null
        return exitCode
    }
    
    private fun setupProject(
        projectPath: String, 
        gradleBuildDir: String, 
        isPackagingPhase: Boolean, 
        outputHandler: (Int, String) -> Unit
    ): File {
        val workDir = Utils.getProjectCacheDir(context, projectPath, gradleBuildDir)
        val actionText = if (isPackagingPhase) "Packaging" else "Importing"

        if (BuildConfig.FLAVOR == "picoos" || BuildConfig.FLAVOR == "horizonos") {
            val sourceDir = File(projectPath, gradleBuildDir)

            if (workDir.exists()) {
                val apkAssetsDir = File(workDir, "src/main/assets")
                if (apkAssetsDir.exists()) apkAssetsDir.deleteRecursively()

                val aabAssetsDir = File(workDir, "assetPackInstallTime/src/main/assets")
                if (aabAssetsDir.exists()) aabAssetsDir.deleteRecursively()
            } else {
                workDir.mkdirs()
            }

            outputHandler(OUTPUT_INFO, "> $actionText project files...")
            if (!FileUtils.tryCopyDirectory(sourceDir, workDir)) {
                throw IOException("Failed to copy $sourceDir to $workDir")
            }

            ProjectInfo.writeToDirectoryXR(workDir, projectPath, gradleBuildDir)
            return workDir
        }

        var projectTreeUri = FileUtils.getProjectTreeUri(context, projectPath)
        if (projectTreeUri == null || !FileUtils.isProjectTreeUriPersisted(context, projectTreeUri)) {
            val notificationPerm = checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            if (notificationPerm == PackageManager.PERMISSION_GRANTED) {
                outputHandler(
                    OUTPUT_STDERR, "Project path \"$projectPath\" is not accessible. " +
                        "Click on the build notification and give " +
                        "${context.getString(R.string.app_launcher_name)} app access to the project directory."
                )
                Utils.showDirectoryAccessNotification(context, projectPath)
            } else {
                throw SecurityException(
                    "POST_NOTIFICATIONS permission not granted. " +
                        "Please grant POST_NOTIFICATIONS permission for " +
                        "${context.getString(R.string.app_launcher_name)} app and retry."
                )
            }

            projectTreeUri = waitForDirectoryAccess(DIR_ACCESS_WAIT_DURATION)
                ?: throw Exception("Directory access not granted in time. Build canceled.")

            if (!FileUtils.isValidDirSelected(context, projectTreeUri)) {
                throw Exception("The selected folder is not a valid project directory. Please try exporting again and select $projectPath." +
                    "\nIf the problem persists, please create a bug report at [color=#3182CE][url]https://github.com/godotengine/android-editor-buildenv-app/issues[/url][/color]")
            }

            outputHandler(OUTPUT_INFO, "Access granted for project directory. Starting Gradle build...")
            FileUtils.saveProjectTreeUri(context, projectPath, projectTreeUri)

            // Notify user if limit is reached so they can clear older projects.
            val persistedCount = context.contentResolver.persistedUriPermissions.size
            val limit = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) 128 else 512
            if (persistedCount == limit) {
                outputHandler(
                    OUTPUT_INFO, "Warning: Persisted directory access limit reached." +
                        "This build will continue, but new projects would require " +
                        "clearing older ones in ${context.getString(R.string.app_launcher_name)} app"
                )
            }
        }

        if (!workDir.exists()) {
            workDir.mkdirs()
            ProjectInfo.writeToDirectory(context, workDir, projectPath, gradleBuildDir, projectTreeUri)
        }

        outputHandler(OUTPUT_INFO, "> $actionText project files...")
        
        // Dynamically intercept the strings coming from FileUtils and replace them if we are packaging!
        FileUtils.importAndroidProject(context, projectTreeUri, gradleBuildDir, workDir) { fileName ->
            val finalMsg = if (isPackagingPhase) fileName.replace("Importing", "Packaging") else fileName
            outputHandler(OUTPUT_INFO, finalMsg)
        }
        
        val gradlewFile = File(workDir, "gradlew")
        if (gradlewFile.exists()) {
            gradlewFile.setExecutable(true, false)
        }
        
        val finishText = if (isPackagingPhase) "Packaged" else "Imported"
        outputHandler(OUTPUT_INFO, "> Project $finishText!")
        return workDir
    }

    private fun fixGradleArgs(projectPath: String, rawGradleArgs: List<String>): List<String> {
        val normalizedProjectPath = projectPath.trimEnd('/')
        val verboseArgs = rawGradleArgs.toMutableList()

        return verboseArgs.map { arg ->
            when {
                arg.startsWith("-Pdebug_keystore_file=") -> "-Pdebug_keystore_file=/project/.android/debug.keystore"
                arg.startsWith("-Prelease_keystore_file=") -> "-Prelease_keystore_file=/project/.android/release.keystore"
                arg.startsWith("-Paddons_directory=") -> "-Paddons_directory=/project/${FileUtils.ADDONS_DIR_NAME}"

                arg.startsWith("-Pplugins_local_binaries=") -> {
                    val prefix = "-Pplugins_local_binaries="
                    val value = arg.removePrefix(prefix)
                    
                    // Godot sends a comma-separated list of absolute Android OS paths.
                    // Since FileUtils.kt now dumps all V1 plugins directly into the PRoot workspace,
                    // we just isolate the file name and point Gradle to /project/FileName.aar
                    val updatedPaths = value.split(",").map { rawPath ->
                        val cleanPath = rawPath.trim().removeSurrounding("\"").removeSurrounding("'")
                        val fileName = File(cleanPath).name
                        "/project/$fileName"
                    }.joinToString(",")
                    
                    prefix + updatedPaths
                }

                else -> arg
            }
        }
    }

    fun cleanProject(projectPath: String, gradleBuildDir: String) {
        val workDir = Utils.getProjectCacheDir(context, projectPath, gradleBuildDir)
        if (workDir.exists()) {
            workDir.deleteRecursively()
        }
        FileUtils.deleteProjectTreeUri(context, projectPath)
    }

    fun cleanGlobalCache() {
        val gradleCache = AppPaths.getGlobalGradleCache(context)
        if (gradleCache.exists()) {
            gradleCache.deleteRecursively()
        }
    }

    fun installRootfs(localUri: Uri? = null, outputHandler: (Int, String) -> Unit) {
        val rootfs = File(this.rootfs)

        if (rootfs.exists()) {
            outputHandler(OUTPUT_INFO, "> Removing existing rootfs...")
            rootfs.deleteRecursively()
        }

        rootfs.mkdirs()

        val progressCallback: (Int, Long) -> Unit = { count, elapsedMs ->
            val totalSeconds = (elapsedMs / 1000).toInt()
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val timeString = String.format("%02d:%02d", minutes, seconds)
            
            outputHandler(99, "Extracted: $count files ($timeString)") 
        }

        val version: String
        if (localUri != null) {
            outputHandler(OUTPUT_INFO, "> Extracting rootfs from local file...")
            TarXzExtractor.extractLocalTarXz(context, localUri, rootfs, progressCallback)
            version = ROOTFS_VERSION_CUSTOM
        } else {
            val hasAsset = try {
                context.assets.list("linux-rootfs")?.contains(ROOTFS_FILENAME) == true
            } catch (e: Exception) {
                false
            }
            if (hasAsset) {
                outputHandler(OUTPUT_INFO, "> Extracting rootfs from assets...")
                TarXzExtractor.extractAssetTarXz(context, ROOTFS_ASSET_PATH, rootfs, progressCallback)
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
                    TarXzExtractor.extractFileTarXz(tempFile, rootfs, progressCallback)
                } finally {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
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
            append(gradleArgs.joinToString(" ") { "\"$it\"" })
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

    fun executeGradle(
        rawGradleArgs: List<String>,
        projectPath: String,
        gradleBuildDir: String,
        outputHandler: (Int, String) -> Unit
    ): Int {
        if (!isRootfsReady()) {
            outputHandler(OUTPUT_STDERR, "Rootfs isn't installed. Install it in the Godot Gradle Build Environment app.")
            return 255
        }

        // Check if Godot is running the final build/packaging tasks
        val isPackagingPhase = !rawGradleArgs.any { it.contains("assemble") || it.contains("bundle") }

        val workDir = try {
            setupProject(projectPath, gradleBuildDir, isPackagingPhase, outputHandler)
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
        
        val baseArgs =  if (BuildConfig.FLAVOR == "picoos" || BuildConfig.FLAVOR == "horizonos") {
            rawGradleArgs
        } else {
            fixGradleArgs(projectPath, rawGradleArgs) 
        }
        
        val gradleArgs = baseArgs.toMutableList()
        // Let the Linux shell dynamically find the exact path to AAPT2 (e.g., /opt/sdk/build-tools/36.1.0/aapt2)
        // Since the gradleCmd string uses double quotes, bash will evaluate $(which aapt2) perfectly!
        gradleArgs.add("-Pandroid.aapt2FromMavenOverride=\$(which aapt2)")
        
        var result = executeGradleInternal(gradleArgs, workDir, captureOutputHandler)

        
        /**val gradleArgs =  if (BuildConfig.FLAVOR == "picoos" || BuildConfig.FLAVOR == "horizonos") {
            // GABE has full storage access on XR devices, so we are not pulling addons dir, or keystore files.
            rawGradleArgs
        } else {
            fixGradleArgs(projectPath, rawGradleArgs)
        }
        var result = executeGradleInternal(gradleArgs, workDir, captureOutputHandler)**/

        val stderr = stderrBuilder.toString()
        if (result == 0 && stderr.contains("BUILD FAILED")) {
            // Sometimes Gradle builds fail, but it still gives an exit code of 0.
            result = 1
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
            val stderr2 = stderrBuilder.toString()
            if (result == 0 && stderr2.contains("BUILD FAILED")) {
                result = 1
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

    fun waitForDirectoryAccess(timeoutMs: Long): Uri? {
        accessLock.withLock {
            var remainingTimeInNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (grantedTreeUri.get() == null && remainingTimeInNanos > 0) {
                try {
                    remainingTimeInNanos = accessLockCondition.awaitNanos(remainingTimeInNanos)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            // We reset the value to `null` after returning it to avoid returning a previous value when this method is
            // invoked again.
            return grantedTreeUri.getAndSet(null)
        }
    }

    fun onDirectoryAccessGranted(uri: Uri) {
        accessLock.withLock {
            grantedTreeUri.set(uri)
            accessLockCondition.signalAll()
        }
    }
}
