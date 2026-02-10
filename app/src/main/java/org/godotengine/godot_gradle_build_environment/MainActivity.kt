package org.godotengine.godot_gradle_build_environment

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import org.godotengine.godot_gradle_build_environment.ui.theme.GodotGradleBuildEnvironmentTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private var tempProjectPath: String? = null
    private var TAG = MainActivity::class.java.simpleName

    private val directoryPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

                if (tempProjectPath != null) {
                    val hash = Integer.toHexString(tempProjectPath.hashCode())
                    val cacheDir = File(AppPaths.getProjectDir(this).absolutePath, hash)
                    if (cacheDir.exists()) cacheDir.deleteRecursively()
                    cacheDir.mkdir()
                    ProjectInfo.writeToDirectory(this, cacheDir, uri, hash)
                    tempProjectPath = null
                } else {
                    Log.e(TAG, "Failed to add project: tempProjectPath is null. This should never happen under normal conditions. Please report this issue.")
                }
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Toast.makeText(this, "Permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permission not granted", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        createNotificationChannel()
        handleIntent(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "CHANNEL_ID",
                "Project Access",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Requests access to project directories"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "ACTION_REQUEST_DIRECTORY_ACCESS") {
            tempProjectPath = intent.getStringExtra("projectPath")
            var uri: Uri? = null
            if (tempProjectPath != null) {
                val externalStorageRoot = Environment.getExternalStorageDirectory().absolutePath
                if (tempProjectPath!!.startsWith(externalStorageRoot)) {
                    val relativePath = tempProjectPath!!.replaceFirst(externalStorageRoot, "").trim('/')
                    uri = Uri.Builder()
                        .scheme("content")
                        .authority("com.android.externalstorage.documents")
                        .appendPath("document")
                        .appendPath("primary:$relativePath")
                        .build()
                }
            }
            directoryPickerLauncher.launch(uri)
        }
    }
}
