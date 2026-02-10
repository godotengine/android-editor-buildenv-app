package org.godotengine.godot_gradle_build_environment.screens

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.godotengine.godot_gradle_build_environment.BuildEnvironment
import org.godotengine.godot_gradle_build_environment.BuildEnvironmentService
import org.godotengine.godot_gradle_build_environment.GitHubReleaseDownloader
import org.godotengine.godot_gradle_build_environment.R
import java.io.File

@Composable
fun RootfsScreen(
    context: Context,
    rootfs: File,
    rootfsReadyFile: File,
    modifier: Modifier = Modifier
) {
    var updateAvailable by remember { mutableStateOf<String>("") }
    var currentVersion by remember { mutableStateOf<String>("") }

    LaunchedEffect(rootfsReadyFile.exists()) {
        if (rootfsReadyFile.exists()) {
            withContext(Dispatchers.IO) {
                try {
                    currentVersion = if (rootfsReadyFile.exists()) {
                        rootfsReadyFile.readText().trim()
                    } else {
                        ""
                    }

                    // Only check for updates if not "custom" from asset files.
                    if (currentVersion != BuildEnvironment.ROOTFS_VERSION_CUSTOM && currentVersion != "") {
                        val latestTag = GitHubReleaseDownloader.getLatestReleaseTag(
                            BuildEnvironment.ROOTFS_GITHUB_REPO
                        )

                        if (latestTag != null && latestTag != currentVersion) {
                            withContext(Dispatchers.Main) {
                                updateAvailable = latestTag
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Silently fail - update check is non-critical
                    Log.d("RootfsScreen", "Failed to check for updates: ${e.message}")
                }
            }
        } else {
            currentVersion = ""
            updateAvailable = ""
        }
    }

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        if (updateAvailable != "") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Update Available",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "A newer rootfs version is available ($updateAvailable). Current version: $currentVersion",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "To update, delete the rootfs and reinstall it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            RootfsInstallOrDeleteButton(
                context,
                rootfs,
                rootfsReadyFile,
            )
        }
    }
}

@Composable
fun RootfsInstallOrDeleteButton(
    context: Context,
    rootfs: File,
    rootfsReadyFile: File,
) {
    var fileExists by remember { mutableStateOf(rootfsReadyFile.exists()) }
    var isLoading by rememberSaveable { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var progressMessages by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var commandId by remember { mutableIntStateOf(0) }

    var serviceMessenger by remember { mutableStateOf<Messenger?>(null) }
    var replyMessenger by remember { mutableStateOf<Messenger?>(null) }

    DisposableEffect(context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                serviceMessenger = Messenger(service)

                val handler = object : Handler(Looper.getMainLooper()) {
                    override fun handleMessage(msg: Message) {
                        when (msg.what) {
                            BuildEnvironmentService.MSG_COMMAND_OUTPUT -> {
                                val line = msg.data.getString("line") ?: ""
                                progressMessages = progressMessages + line
                            }

                            BuildEnvironmentService.MSG_COMMAND_RESULT -> {
                                val result = msg.arg2
                                isLoading = false

                                if (result == 0) {
                                    fileExists = rootfsReadyFile.exists()
                                    errorMessage = null
                                    progressMessages = emptyList()
                                } else {
                                    val error = msg.data.getString("error") ?: "Unknown error"
                                    errorMessage = error
                                }
                            }
                        }
                    }
                }
                replyMessenger = Messenger(handler)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceMessenger = null
                replyMessenger = null
            }
        }

        val intent = Intent("org.godotengine.action.BUILD_PROVIDER")
        intent.setPackage(context.packageName)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)

        onDispose {
            context.unbindService(connection)
        }
    }

    fun sendMessage(msgType: Int) {
        if (serviceMessenger == null || replyMessenger == null) {
            errorMessage = "Service not connected"
            return
        }

        isLoading = true
        errorMessage = null
        progressMessages = emptyList()
        commandId++

        val msg = Message.obtain(null, msgType, commandId, 0)
        msg.replyTo = replyMessenger

        try {
            serviceMessenger?.send(msg)
        } catch (e: Exception) {
            Log.e("RootfsScreen", "Error sending message: ${e.message}")
            isLoading = false
            errorMessage = "Failed to send command: ${e.message}"
        }
    }

    when {
        isLoading -> {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(20.dp))
            if (fileExists) {
                Text(stringResource(R.string.deleting_rootfs_message))
            } else {
                Text(stringResource(R.string.installing_rootfs_message))
            }

            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier.height(100.dp),
                contentAlignment = Alignment.TopStart
            ) {
                if (progressMessages.isNotEmpty()) {
                    Column {
                        progressMessages.takeLast(5).forEach { msg ->
                            Text(msg, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        errorMessage != null -> {
            Text("Error: $errorMessage", color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = {
                errorMessage = null
                fileExists = rootfsReadyFile.exists()
            }) {
                Text("Retry")
            }
        }

        !fileExists -> {
            Text(stringResource(R.string.missing_rootfs_message))
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = {
                sendMessage(BuildEnvironmentService.MSG_INSTALL_ROOTFS)
            }) {
                Text(stringResource(R.string.install_rootfs_button))
            }
        }

        else -> {
            Text(stringResource(R.string.rootfs_ready_message))
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = {
                sendMessage(BuildEnvironmentService.MSG_DELETE_ROOTFS)
            }) {
                Text(stringResource(R.string.delete_rootfs_button))
            }
        }
    }
}
