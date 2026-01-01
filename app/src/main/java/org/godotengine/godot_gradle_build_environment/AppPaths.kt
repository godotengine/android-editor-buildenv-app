package org.godotengine.godot_gradle_build_environment

import android.content.Context
import java.io.File

object AppPaths {

    const val ROOTFS_DIR = "rootfs/alpine-android-35-jdk17"
    const val ROOTFS_READY_FILENAME = ".ready"
    const val PROJECTS_DIR = "projects"
    const val GRADLE_CACHE_DIR = "global-gradle-cache"
    const val PROOT_TMP_DIR = "proot-tmp"

    fun getRootfs(context: Context): File =
        File(context.filesDir, ROOTFS_DIR)

    fun getRootfsReadyFile(rootfs: File): File =
        File(rootfs, ROOTFS_READY_FILENAME)

    fun getRootfsReadyFile(context: Context): File =
        getRootfsReadyFile(getRootfs(context))

    fun getProjectDir(context: Context): File =
        File(context.filesDir, PROJECTS_DIR)

    fun getGlobalGradleCache(context: Context): File =
        File(context.filesDir, GRADLE_CACHE_DIR)

    fun getProotTmpDir(context: Context): File =
        File(context.filesDir, PROOT_TMP_DIR)

}
