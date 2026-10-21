package org.godotengine.godot_gradle_build_environment.screens

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedButton
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
                        text = stringResource(R.string.update_available),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.rootfs_update_is_available_text, updateAvailable, currentVersion),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.rootfs_update_instruction_text),
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
                rootfsReadyFile,
            )
        }
    }
}

@Composable
fun RootfsInstallOrDeleteButton(
    context: Context,
    rootfsReadyFile: File,
) {
    var fileExists by remember { mutableStateOf(rootfsReadyFile.exists()) }
    var isLoading by rememberSaveable { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var progressMessages by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    
    // New state for live timer updates
    var extractionProgress by rememberSaveable { mutableStateOf("") }
    
    var commandId by remember { mutableIntStateOf(0) }

    var serviceMessenger by remember { mutableStateOf<Messenger?>(null) }
    var replyMessenger by remember { mutableStateOf<Messenger?>(null) }

    fun sendMessage(msgType: Int, localUri: Uri? = null) {
        if (serviceMessenger == null || replyMessenger == null) {
            errorMessage = "Service not connected"
            return
        }

        isLoading = true
        errorMessage = null
        progressMessages = emptyList()
        extractionProgress = "" // Reset progress on new extraction
        commandId++

        val msg = Message.obtain(null, msgType, commandId, 0)
        msg.replyTo = replyMessenger

        if (localUri != null) {
            val data = Bundle()
            data.putString(BuildEnvironmentService.EXTRA_LOCAL_ROOTFS_URI, localUri.toString())
            msg.data = data
        }

        try {
            serviceMessenger?.send(msg)
        } catch (e: Exception) {
            Log.e("RootfsScreen", "Error sending message: ${e.message}")
            isLoading = false
            errorMessage = "Failed to send command: ${e.message}"
        }
    }

    val localFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            sendMessage(BuildEnvironmentService.MSG_INSTALL_ROOTFS, uri)
        }
    }

    DisposableEffect(context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                serviceMessenger = Messenger(service)

                val handler = object : Handler(Looper.getMainLooper()) {
                    override fun handleMessage(msg: Message) {
                        when (msg.what) {
                            // Catching the new timer message from BuildEnvironmentService
                            BuildEnvironmentService.MSG_EXTRACTION_PROGRESS -> {
                                val timerText = msg.data.getString("timerText") ?: ""
                                extractionProgress = timerText
                            }
                            
                            BuildEnvironmentService.MSG_COMMAND_OUTPUT -> {
                                val line = msg.data.getString("line") ?: ""
                                progressMessages = progressMessages + line
                            }

                            BuildEnvironmentService.MSG_COMMAND_RESULT -> {
                                val result = msg.arg2
                                isLoading = false
                                extractionProgress = "" // Clear the timer when finished

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

    when {
        isLoading -> {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(20.dp))
            
            if (fileExists) {
                Text(stringResource(R.string.deleting_rootfs_message))
            } else {
                Text(stringResource(R.string.installing_rootfs_message))
                
                // Display the live timer progress if data is available
                if (extractionProgress.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = extractionProgress,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
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
            Text(stringResource(R.string.missing_rootfs_message), modifier = Modifier.padding(16.dp))
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = {
                sendMessage(BuildEnvironmentService.MSG_INSTALL_ROOTFS)
            }) {
                Text(stringResource(R.string.install_rootfs_button))
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = {
                localFilePicker.launch(arrayOf("application/x-xz"))
            }) {
                Text(stringResource(R.string.install_rootfs_local_button))
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
