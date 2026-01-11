package org.godotengine.godot_gradle_build_environment

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ProjectInfo(
    val projectPath: String,
    val gradleBuildDir: String
) {
    fun getProjectName(): String {
        return File(projectPath).name
    }

    companion object {
        private const val PROJECT_INFO_FILENAME = ".gabe_project_info.json"

        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

        fun writeToDirectory(directory: File, projectPath: String, gradleBuildDir: String) {
            val projectInfo = ProjectInfo(projectPath, gradleBuildDir)
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
                ?.sortedBy { it.info.getProjectName() }
                ?: emptyList()
        }
    }
}

data class CachedProject(
    val cacheDirectory: File,
    val info: ProjectInfo
)
