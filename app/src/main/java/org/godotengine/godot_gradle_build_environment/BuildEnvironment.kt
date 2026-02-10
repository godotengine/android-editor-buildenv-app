package org.godotengine.godot_gradle_build_environment

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
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

    }

    private var currentProcess: Process? = null

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
        Log.i(TAG, "ExitCode: $exitCode")

        currentProcess = null
        return exitCode
    }

    fun cleanProject(projectCacheDirHash: String) {
        val workDir = File(projectRoot, projectCacheDirHash)
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
            "/storage/emulated/0:/storage/emulated/0",
            "${workDir.absolutePath}:/project",
            "${gradleCache.absolutePath}:/project/?",
        )

        return executeCommand(path, args, binds, "/project", outputHandler)
    }

    private fun isRootfsReady(): Boolean {
        return AppPaths.getRootfsReadyFile(File(rootfs)).exists()
    }

    fun executeGradle(rawGradleArgs: List<String>, rawProjectPath: String, gradleBuildDir: String, outputHandler: (Int, String) -> Unit): Int {
        if (!isRootfsReady()) {
            outputHandler(OUTPUT_STDERR, "Rootfs isn't installed. Install it in the Godot Gradle Build Environment app.")
            return 255
        }

        val projectTreeUri: Uri
        val projectPath = rawProjectPath.trimEnd('/')

        val hash = Integer.toHexString(projectPath.hashCode())
        val workDir = File(projectRoot, hash)
        if (workDir.exists()) {
            val info = ProjectInfo.readFromDirectory(workDir)
            if (info != null) {
                projectTreeUri = info.projectTreeUri.toUri()
            } else {
                outputHandler(OUTPUT_STDERR, "Unable to setup project: could not get projectTreeUri.")
                return 255
            }
        } else {
            outputHandler(OUTPUT_STDERR, "Unable to setup project: $projectPath is not accessible. Please give GABE app access to this directory.")
            showDirectoryAccessNotification(projectPath)
            return 255
        }

        try {
            outputHandler(OUTPUT_INFO, "> Importing project via SAF...")
            SafProjectImporter.importAndroidProject(context, projectTreeUri, workDir)
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

        val gradleArgs = rawGradleArgs.map { arg ->
            when {
                arg.startsWith("-Pdebug_keystore_file=") -> "-Pdebug_keystore_file=/project/.android/debug.keystore"
                arg.startsWith("-Prelease_keystore_file=") -> "-Prelease_keystore_file=/project/.android/release.keystore"
                arg.startsWith("-Paddons_directory=") -> "-Paddons_directory=/project/addons"

                arg.startsWith("-Pplugins_local_binaries=") -> {
                    val prefix = "-Pplugins_local_binaries="
                    val value = arg.removePrefix(prefix)
                    val normalizeProjectPath = projectPath.trimEnd('/')
                    val updated = value.replace(
                        "$normalizeProjectPath/addons",
                        "/project/addons"
                    )
                    prefix + updated
                }
                else -> arg
            }
        }

        var result = executeGradleInternal(gradleArgs, workDir, captureOutputHandler)

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
            val stderr = stderrBuilder.toString()
            if (result == 0 && stderr.contains("BUILD FAILED")) {
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

    private fun showDirectoryAccessNotification(projectPath: String) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "ACTION_REQUEST_DIRECTORY_ACCESS"
            putExtra("projectPath", projectPath)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, "CHANNEL_ID")
            .setSmallIcon(R.drawable.icon_rootfs_tab)
            .setContentTitle("Directory access required")
            .setContentText("Tap to grant access to the project directory")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(1001, notification)
    }
}

object SafProjectImporter {
    fun importAndroidProject(context: Context, projectTreeUri: Uri, destDir: File) {
        val gradleBuildDir = "android/build"
        val root = DocumentFile.fromTreeUri(context, projectTreeUri)
            ?: throw IOException("Invalid tree uri")

        val androidDir = findDirByPath(root, gradleBuildDir)
            ?: throw IOException("Gradle build dir not found: $gradleBuildDir")

        val addonsDir = root.listFiles().firstOrNull {
            it.isDirectory && it.name == "addons"
        }

        if (destDir.exists()) {
            val apkAssetsDir = File(destDir, "src/main/assets")
            if (apkAssetsDir.exists()) apkAssetsDir.deleteRecursively()

            val aabAssetsDir = File(destDir, "assetPackInstallTime/src/main/assets")
            if (aabAssetsDir.exists()) aabAssetsDir.deleteRecursively()
        } else {
            destDir.mkdir()
        }

        copyDirectoryMerge(context, androidDir, destDir)

        if (addonsDir != null) {
            val localAddons = File(destDir, "addons")
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
}
