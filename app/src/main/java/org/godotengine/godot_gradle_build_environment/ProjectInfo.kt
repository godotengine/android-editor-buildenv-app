package org.godotengine.godot_gradle_build_environment

import android.content.Context
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import androidx.documentfile.provider.DocumentFile

@Serializable
data class ProjectInfo(
    val projectPath: String,
    val gradleBuildDir: String,
    val projectTreeUri: String,
    val projectName: String
) {
    companion object {
        private const val PROJECT_INFO_FILENAME = ".gabe_project_info.json"

        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

        fun writeToDirectory(context: Context, directory: File, projectPath: String, gradleBuildDir: String, projectTreeUri: Uri) {
            val name = DocumentFile.fromTreeUri(context, projectTreeUri)?.name ?: "Unknown Project"
            val projectInfo = ProjectInfo(projectPath, gradleBuildDir, projectTreeUri.toString(), name)
            val jsonString = json.encodeToString(projectInfo)
            val file = File(directory, PROJECT_INFO_FILENAME)
            file.writeText(jsonString)
        }

        fun readFromDirectory(directory: File): ProjectInfo? {
            val file = File(directory, PROJECT_INFO_FILENAME)
            return if (file.exists()) {
                try {
                    json.decodeFromString<ProjectInfo>(file.readText())
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
        }

        fun getAllCachedProjects(projectsDirectory: File): List<CachedProject> {
            if (!projectsDirectory.exists() || !projectsDirectory.isDirectory) {
                return emptyList()
            }

            return projectsDirectory.listFiles { file -> file.isDirectory }
                ?.mapNotNull { dir ->
                    readFromDirectory(dir)?.let { info ->
                        CachedProject(dir, info)
                    }
                }
                ?.sortedBy { it.info.projectName }
                ?: emptyList()
        }
    }
}

data class CachedProject(
    val cacheDirectory: File,
    val info: ProjectInfo
)
