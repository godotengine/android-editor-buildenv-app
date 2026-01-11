package org.godotengine.godot_gradle_build_environment

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import org.godotengine.godot_gradle_build_environment.screens.ProjectsScreen
import org.godotengine.godot_gradle_build_environment.screens.RootfsScreen
import org.godotengine.godot_gradle_build_environment.screens.SettingsScreen
import java.io.File

enum class AppTab(
    val label: String,
    val icon: ImageVector
) {
    PROJECTS("Projects", Icons.Filled.List),
    ROOTFS("Rootfs", Icons.Filled.Build),
    SETTINGS("Settings", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    context: Context,
    rootfs: File,
    rootfsReadyFile: File,
    extractRootfs: () -> Unit,
    settingsManager: SettingsManager,
) {
    val initialTab = remember(rootfsReadyFile) {
        if (rootfsReadyFile.exists()) AppTab.PROJECTS else AppTab.ROOTFS
    }
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.app_name)) }
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab }
                    )
                }
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            AppTab.PROJECTS -> ProjectsScreen(
                modifier = Modifier.padding(innerPadding)
            )
            AppTab.ROOTFS -> RootfsScreen(
                rootfs = rootfs,
                rootfsReadyFile = rootfsReadyFile,
                extractRootfs = extractRootfs,
                modifier = Modifier.padding(innerPadding)
            )
            AppTab.SETTINGS -> SettingsScreen(
                context = context,
                settingsManager = settingsManager,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}
