package org.godotengine.godot_gradle_build_environment

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.Message
import android.os.Messenger
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

                val msg = Message.obtain(null, BuildEnvironmentService.MSG_RESUME_PENDING_BUILD)
                msg.data = Bundle().apply {
                    putString("tree_uri", uri.toString())
                }
                serviceMessenger?.send(msg)
            }

            // Finish and remove from recents screen too
            finishAndRemoveTask()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Toast.makeText(this, "Permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permission not granted", Toast.LENGTH_SHORT).show()
            }
        }

    private var serviceMessenger: Messenger? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceMessenger = Messenger(binder)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessenger = null
        }
    }
    private var isServiceBound = false


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
            bindToBuildService()

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

    private fun bindToBuildService() {
        if (isServiceBound) return

        val intent = Intent("org.godotengine.action.BUILD_PROVIDER").apply {
            setPackage(packageName)
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        isServiceBound = true
    }

    private fun unbindFromBuildService() {
        if (!isServiceBound) return
        unbindService(connection)
        isServiceBound = false
    }

    override fun onDestroy() {
        unbindFromBuildService()
        super.onDestroy()
    }
}
