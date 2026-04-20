package org.godotengine.godot_gradle_build_environment

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import org.godotengine.godot_gradle_build_environment.ui.theme.GodotGradleBuildEnvironmentTheme
import androidx.core.net.toUri

class MainActivity : ComponentActivity() {
    private val permissionRequestLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Toast.makeText(this, "Permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permission not granted", Toast.LENGTH_SHORT).show()
            }
        }

    @RequiresApi(Build.VERSION_CODES.R)
    private val manageStorageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(this, "Storage Access Granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Storage Access Denied", Toast.LENGTH_SHORT).show()
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Utils.createNotificationChannel(this)

        if (BuildConfig.FLAVOR == "picoos" || BuildConfig.FLAVOR == "horizonos") {
            requestManageStoragePermission()
        } else {
            requestNotificationPermission()
        }

        setContent {
            GodotGradleBuildEnvironmentTheme {
                MainScreen(
                    this,
                    AppPaths.getRootfs(this),
                    AppPaths.getRootfsReadyFile(this),
                    SettingsManager(this),
                )
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.notification_perm_request_title)
                    .setMessage(R.string.notification_perm_request_message)
                    .setPositiveButton(R.string.perm_request_positive_btn) { _, _ ->
                        permissionRequestLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    .setNegativeButton(R.string.perm_request_negative_btn, null)
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = "package:${packageName}".toUri()
                }
                manageStorageLauncher.launch(intent)
            }
        } else {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionRequestLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }
}
