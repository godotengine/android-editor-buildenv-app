package org.godotengine.godot_gradle_build_environment.screens

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.godotengine.godot_gradle_build_environment.AppPaths
import org.godotengine.godot_gradle_build_environment.BuildEnvironmentService
import org.godotengine.godot_gradle_build_environment.CachedProject
import org.godotengine.godot_gradle_build_environment.FileUtils
import org.godotengine.godot_gradle_build_environment.ProjectInfo
import org.godotengine.godot_gradle_build_environment.R

@Composable
fun ProjectsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var projects by remember { mutableStateOf(loadCachedProjects(context)) }
    val sizeCache = remember { mutableStateMapOf<String, Long>() }
    val deletingProjects = rememberSaveable { mutableStateListOf<String>() }
    val refreshingProjects = rememberSaveable { mutableStateListOf<String>() }
    val refreshTriggers = remember { mutableStateMapOf<String, Int>() }
    var serviceMessenger by remember { mutableStateOf<Messenger?>(null) }
    var replyMessenger by remember { mutableStateOf<Messenger?>(null) }

    DisposableEffect(context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                serviceMessenger = Messenger(service)

                val handler = object : Handler(Looper.getMainLooper()) {
                    override fun handleMessage(msg: Message) {
                        if (msg.what == BuildEnvironmentService.MSG_COMMAND_RESULT) {
                            // Deletion completed, reload projects list
                            projects = loadCachedProjects(context)
                            deletingProjects.clear()
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "Project Caches",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (projects.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "No project caches",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(projects, key = { it.cacheDirectory.absolutePath }) { project ->
                    ProjectItem(
                        project = project,
                        sizeCache = sizeCache,
                        refreshTrigger = refreshTriggers[project.cacheDirectory.absolutePath] ?: 0,
                        isDeleting = deletingProjects.contains(project.cacheDirectory.absolutePath),
                        isRefreshing = refreshingProjects.contains(project.cacheDirectory.absolutePath),
                        onDelete = {
                            deletingProjects.add(project.cacheDirectory.absolutePath)
                            deleteProject(serviceMessenger, replyMessenger, project)
                        },
                        onRefresh = {
                            val path = project.cacheDirectory.absolutePath
                            refreshingProjects.add(path)
                            sizeCache.remove(path)
                            refreshTriggers[path] = (refreshTriggers[path] ?: 0) + 1
                        },
                        onRefreshComplete = {
                            refreshingProjects.remove(project.cacheDirectory.absolutePath)
                        }
                    )
                }
            }
        }
    }
}

private fun deleteProject(
    serviceMessenger: Messenger?,
    replyMessenger: Messenger?,
    project: CachedProject
) {
    if (serviceMessenger == null || replyMessenger == null) return

    val msg = Message.obtain(null, BuildEnvironmentService.MSG_CLEAN_PROJECT, 0, 0)
    msg.replyTo = replyMessenger

    val data = Bundle()
    data.putString("project_path", project.info.projectPath)
    data.putString("gradle_build_directory", project.info.gradleBuildDir)
    data.putBoolean("force_clean", true)
    msg.data = data

    try {
        serviceMessenger.send(msg)
    } catch (e: Exception) {
        Log.e("ProjectsScreen", "Error sending delete message for project ${project.info.projectName}: ${e.message}")
    }
}

@Composable
private fun ProjectItem(
    project: CachedProject,
    sizeCache: MutableMap<String, Long>,
    refreshTrigger: Int,
    isDeleting: Boolean,
    isRefreshing: Boolean,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
    onRefreshComplete: () -> Unit
) {
    val cacheKey = project.cacheDirectory.absolutePath
    var sizeText by remember { mutableStateOf<String?>(null) }

    // Load size asynchronously and cache it
    LaunchedEffect(cacheKey, refreshTrigger) {
        if (sizeText != null && refreshTrigger == 0) return@LaunchedEffect
        val cachedSize = sizeCache[cacheKey]
        if (cachedSize != null) {
            sizeText = FileUtils.formatSize(cachedSize)
            onRefreshComplete()
        } else {
            // Calculate size in background thread
            val size = withContext(Dispatchers.IO) {
                FileUtils.calculateDirectorySize(project.cacheDirectory)
            }
            sizeCache[cacheKey] = size
            sizeText = FileUtils.formatSize(size)
            onRefreshComplete()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = project.info.projectName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = project.info.projectPath,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = "${stringResource(R.string.gradle_build_dir_label)}: ${project.info.gradleBuildDir}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = sizeText ?: "Calculating...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.icon_refresh),
                            contentDescription = "Refresh size",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.icon_delete),
                            contentDescription = "Delete project cache",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

private fun loadCachedProjects(context: Context): List<CachedProject> {
    val projectsDir = AppPaths.getProjectDir(context)
    return ProjectInfo.getAllCachedProjects(projectsDir)
}
