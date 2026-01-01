package org.godotengine.godot_gradle_build_environment

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.godotengine.godot_gradle_build_environment.ui.theme.GodotGradleBuildEnvironmentTheme
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        private const val REQUEST_MANAGE_EXTERNAL_STORAGE_REQ_CODE = 2002
    }

    private fun extractRootfs() {
        val rootfs = AppPaths.getRootfs(this)
        if (rootfs.exists()) {
            rootfs.deleteRecursively()
        }
        rootfs.mkdirs()
        TarXzExtractor.extractAssetTarXz(this, "linux-rootfs/alpine-android-35-jdk17.tar.xz", rootfs)

        // Docker doesn't let us write resolv.conf and so we take this extra unpacking step.
        val resolveConf = File(rootfs, "etc/resolv.conf")
        val resolveConfOverride = File(rootfs, "etc/resolv.conf.override")
        if (resolveConfOverride.exists()) {
            if (FileUtils.tryCopyFile(resolveConfOverride, resolveConf)) {
                resolveConfOverride.delete()
            }
        }

        AppPaths.getRootfsReadyFile(this).createNewFile()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivityForResult(intent, REQUEST_MANAGE_EXTERNAL_STORAGE_REQ_CODE)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivityForResult(intent, REQUEST_MANAGE_EXTERNAL_STORAGE_REQ_CODE)
                }
            }
        }

        setContent {
            GodotGradleBuildEnvironmentTheme {
                MainScreen(
                    this,
                    AppPaths.getRootfs(this),
                    AppPaths.getRootfsReadyFile(this),
                    { extractRootfs() },
                    SettingsManager(this),
                )
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_MANAGE_EXTERNAL_STORAGE_REQ_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "Permission granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Permission NOT granted", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
