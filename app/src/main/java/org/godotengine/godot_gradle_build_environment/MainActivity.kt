package org.godotengine.godot_gradle_build_environment

import android.Manifest
import android.app.AlertDialog
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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import org.godotengine.godot_gradle_build_environment.ui.theme.GodotGradleBuildEnvironmentTheme

class MainActivity : ComponentActivity() {
    private val TAG = MainActivity::class.java.simpleName

    private val directoryPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                val msg = Message.obtain(null, BuildEnvironmentService.MSG_BUILD_DIR_ACCESS_GRANTED)
                msg.data = Bundle().apply {
                    putString(Utils.EXTRA_TREE_URI, uri.toString())
                }
                serviceMessenger?.send(msg)
            }

            finish()
        }

    private val permissionRequestLauncher =
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
        Utils.createNotificationChannel(this)
        handleIntent(intent)

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == Utils.ACTION_REQUEST_DIRECTORY_ACCESS) {
            bindToBuildService()

            val tempProjectPath = intent.getStringExtra(Utils.EXTRA_PROJECT_PATH)
            var initialUri: Uri? = null
            if (tempProjectPath != null) {
                val externalStorageRoot = Environment.getExternalStorageDirectory().absolutePath
                if (tempProjectPath.startsWith(externalStorageRoot)) {
                    val relativePath = tempProjectPath.replaceFirst(externalStorageRoot, "").trim('/')
                    initialUri = Uri.Builder()
                        .scheme("content")
                        .authority("com.android.externalstorage.documents")
                        .appendPath("document")
                        .appendPath("primary:$relativePath")
                        .build()
                }
            }
            directoryPickerLauncher.launch(initialUri)
        }
    }

    private fun bindToBuildService() {
        if (isServiceBound) return

        val intent = Intent(this, BuildEnvironmentService::class.java)
        bindService(intent, connection, BIND_AUTO_CREATE)
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
