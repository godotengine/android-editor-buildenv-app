package org.godotengine.godot_gradle_build_environment.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.godotengine.godot_gradle_build_environment.R
import java.io.File

@Composable
fun RootfsScreen(
    rootfs: File,
    rootfsReadyFile: File,
    extractRootfs: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RootfsInstallOrDeleteButton(
            rootfs,
            rootfsReadyFile,
            extractRootfs,
        )
    }
}

@Composable
fun RootfsInstallOrDeleteButton(
    rootfs: File,
    rootfsReadyFile: File,
    extractRootfs: () -> Unit,
) {
    var fileExists by remember { mutableStateOf(rootfsReadyFile.exists()) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    when {
        isLoading -> {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(20.dp))
            if (fileExists) {
                Text(stringResource(R.string.deleting_rootfs_message))
            } else {
                Text(stringResource(R.string.installing_rootfs_message))
            }
        }

        !fileExists -> {
            Text(stringResource(R.string.missing_rootfs_message))
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = {
                isLoading = true
                scope.launch(Dispatchers.IO) {
                    try {
                        extractRootfs()

                        // Update UI state on main thread
                        withContext(Dispatchers.Main) {
                            isLoading = false
                            fileExists = true
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { isLoading = false }
                    }
                }
            }) {
                Text(stringResource(R.string.install_rootfs_button))
            }
        }

        else -> {
            Text(stringResource(R.string.rootfs_ready_message))
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = {
                isLoading = true
                scope.launch(Dispatchers.IO) {
                    try {
                        rootfs.deleteRecursively()

                        withContext(Dispatchers.Main) {
                            isLoading = false
                            fileExists = false
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { isLoading = false }
                    }
                }
            }) {
                Text(stringResource(R.string.delete_rootfs_button))
            }
        }
    }
}
