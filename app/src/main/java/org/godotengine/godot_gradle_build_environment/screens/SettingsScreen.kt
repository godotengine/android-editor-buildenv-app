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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.godotengine.godot_gradle_build_environment.AppPaths
import org.godotengine.godot_gradle_build_environment.BuildEnvironmentService
import org.godotengine.godot_gradle_build_environment.FileUtils
import org.godotengine.godot_gradle_build_environment.SettingsManager

@Composable
fun SettingsScreen(
    context: Context,
    settingsManager: SettingsManager,
    modifier: Modifier = Modifier
) {
    var clearCacheAfterBuild by remember { mutableStateOf(settingsManager.clearCacheAfterBuild) }

    var serviceMessenger by remember { mutableStateOf<Messenger?>(null) }
    var replyMessenger by remember { mutableStateOf<Messenger?>(null) }

    var cacheSize by remember { mutableStateOf<String?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    DisposableEffect(context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                serviceMessenger = Messenger(service)

                val handler = object : Handler(Looper.getMainLooper()) {
                    override fun handleMessage(msg: Message) {
                        if (msg.what == BuildEnvironmentService.MSG_COMMAND_RESULT) {
                            isDeleting = false
                            cacheSize = null
                            refreshTrigger++
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

    LaunchedEffect(refreshTrigger) {
        if (cacheSize == null) {
            isRefreshing = true
            val size = withContext(Dispatchers.IO) {
                val gradleCache = AppPaths.getGlobalGradleCache(context)
                FileUtils.calculateDirectorySize(gradleCache)
            }
            cacheSize = FileUtils.formatSize(size)
            isRefreshing = false
        }
    }

    fun onRefresh() {
        cacheSize = null
        refreshTrigger++
    }

    fun onDelete() {
        if (serviceMessenger == null || replyMessenger == null) return

        isDeleting = true
        val msg = Message.obtain(null, BuildEnvironmentService.MSG_CLEAN_GLOBAL_CACHE, 0, 0)
        msg.replyTo = replyMessenger

        try {
            serviceMessenger?.send(msg)
        } catch (e: Exception) {
            Log.e("SettingsScreen", "Error sending delete message: ${e.message}")
            isDeleting = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Clear project cache when build is finished",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(end = 16.dp)
            )
            Switch(
                checked = clearCacheAfterBuild,
                onCheckedChange = { checked ->
                    clearCacheAfterBuild = checked
                    settingsManager.clearCacheAfterBuild = checked
                }
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Global Gradle Cache",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Size: ${cacheSize ?: "Calculating..."}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(onClick = { onRefresh() }) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = "Refresh cache size",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        if (isDeleting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Button(
                                onClick = { onDelete() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("Delete Cache")
                            }
                        }
                    }
                }
            }
        }
    }
}
