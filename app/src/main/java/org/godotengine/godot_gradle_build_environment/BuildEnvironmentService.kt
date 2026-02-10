package org.godotengine.godot_gradle_build_environment

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import androidx.core.net.toUri
import java.util.concurrent.LinkedBlockingQueue

class BuildEnvironmentService : Service() {

    companion object {
        private val TAG = BuildEnvironmentService::class.java.simpleName

        const val MSG_EXECUTE_GRADLE = 1
        const val MSG_COMMAND_RESULT = 2
        const val MSG_COMMAND_OUTPUT = 3
        const val MSG_CANCEL_COMMAND = 4
        const val MSG_CLEAN_PROJECT = 5
        const val MSG_CLEAN_GLOBAL_CACHE = 6
        const val MSG_INSTALL_ROOTFS = 7
        const val MSG_DELETE_ROOTFS = 8
        const val MSG_BUILD_DIR_ACCESS_GRANTED = 9
    }

    private lateinit var mMessenger: Messenger
    private val mBuildEnvironment: BuildEnvironment by lazy {
        BuildEnvironment(
            applicationContext,
            AppPaths.getRootfs(applicationContext).absolutePath,
            AppPaths.getProjectDir(applicationContext).absolutePath
        )
    }
    private val mSettingsManager: SettingsManager by lazy { SettingsManager(applicationContext)}
    private val mWorkThread = HandlerThread("BuildEnvironmentServiceWorker")
    private val mWorkHandler: Handler by lazy { Handler(mWorkThread.looper) }

    internal data class WorkItem(val msg: Message, val id: Int)
    private val queue = LinkedBlockingQueue<WorkItem>()
    private var currentItem: WorkItem? = null

    override fun onCreate() {
        super.onCreate()

        mWorkThread.start()

        val incomingHandler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                // Copy because the message may be recycled after this method returns.
                val copy = Message.obtain()
                copy.copyFrom(msg)

                when (msg.what) {
                    MSG_EXECUTE_GRADLE -> queueWork(WorkItem(copy, msg.arg1))
                    MSG_CANCEL_COMMAND -> cancelWork(msg.arg1)
                    MSG_CLEAN_PROJECT -> queueWork(WorkItem(copy, msg.arg1))
                    MSG_CLEAN_GLOBAL_CACHE -> queueWork(WorkItem(copy, msg.arg1))
                    MSG_INSTALL_ROOTFS -> queueWork(WorkItem(copy, msg.arg1))
                    MSG_DELETE_ROOTFS -> queueWork(WorkItem(copy, msg.arg1))
                    MSG_BUILD_DIR_ACCESS_GRANTED -> {
                        val uri = msg.data.getString(Utils.EXTRA_TREE_URI)?.toUri()
                        if (uri != null) {
                            mBuildEnvironment.onDirectoryAccessGranted(uri)
                        }
                    }
                }
            }
        }

        mMessenger = Messenger(incomingHandler)

        mWorkHandler.post(::workerLoop)
    }

    override fun onDestroy() {
        super.onDestroy()
        mWorkThread.quitSafely()
    }

    override fun onBind(intent: Intent?): IBinder? = mMessenger.binder

    private fun queueWork(item: WorkItem) {
        queue.put(item)
    }

    private fun cancelWork(id: Int) {
        // We only except ids greater than 0.
        if (id < 0) {
            return
        }

        Log.i(TAG, "Canceling command: $id")

        if (currentItem?.id == id && currentItem?.msg?.what == MSG_EXECUTE_GRADLE) {
            mBuildEnvironment.killCurrentProcess()
        }
        queue.removeAll { it.id == id && it.msg.what == MSG_EXECUTE_GRADLE }
    }

    private fun workerLoop() {
        while (true) {
            val work: WorkItem = queue.take()

            currentItem = work
            handleMessage(work.msg)
            currentItem = null
        }
    }

    private fun handleMessage(msg: Message) {
        try {
            when (msg.what) {
                MSG_EXECUTE_GRADLE -> executeGradle(msg)
                MSG_CLEAN_PROJECT -> cleanProject(msg)
                MSG_CLEAN_GLOBAL_CACHE -> cleanGlobalCache(msg)
                MSG_INSTALL_ROOTFS -> installRootfs(msg)
                MSG_DELETE_ROOTFS -> deleteRootfs(msg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: ${e.message}")
        }
    }

    private fun executeGradle(msg: Message) {
        val id = msg.arg1

        val data = msg.data
        val args = data.getStringArrayList("arguments")
        val projectPath = data.getString("project_path")
        val gradleBuildDir = data.getString("gradle_build_directory")

        var result = 255

        if (args != null && projectPath != null && gradleBuildDir != null) {
            result = mBuildEnvironment.executeGradle(args, projectPath, gradleBuildDir) { type, line ->
                val reply = Message.obtain(null, MSG_COMMAND_OUTPUT, id, type)
                val replyData = Bundle()
                replyData.putString("line", line)
                reply.data = replyData

                try {
                    msg.replyTo.send(reply)
                } catch (e: RemoteException) {
                    Log.e(TAG, "Error send command output to client: ${e.message}")
                }
            }
        }

        val reply = Message.obtain(null, MSG_COMMAND_RESULT, id, result)
        try {
            msg.replyTo.send(reply)
        } catch (e: RemoteException) {
            Log.e(TAG, "Error sending result to client: ${e.message}")
        }
    }

    private fun cleanProject(msg: Message) {
        val data = msg.data
        val projectPath = data.getString("project_path")
        val gradleBuildDir = data.getString("gradle_build_directory")
        val forceClean = data.getBoolean("force_clean", false)

        if (projectPath != null && gradleBuildDir != null && (forceClean || mSettingsManager.clearCacheAfterBuild)) {
            mBuildEnvironment.cleanProject(projectPath, gradleBuildDir)
        }

        val reply = Message.obtain(null, MSG_COMMAND_RESULT, msg.arg1, 0)
        try {
            msg.replyTo.send(reply)
        } catch (e: RemoteException) {
            Log.e(TAG, "Error sending result to client: ${e.message}")
        }
    }

    private fun cleanGlobalCache(msg: Message) {
        mBuildEnvironment.cleanGlobalCache()

        val reply = Message.obtain(null, MSG_COMMAND_RESULT, msg.arg1, 0)
        try {
            msg.replyTo.send(reply)
        } catch (e: RemoteException) {
            Log.e(TAG, "Error sending result to client: ${e.message}")
        }
    }

    private fun installRootfs(msg: Message) {
        val id = msg.arg1
        var result = 0
        var errorMessage: String? = null

        try {
            mBuildEnvironment.installRootfs { type, line ->
                val outputMsg = Message.obtain(null, MSG_COMMAND_OUTPUT, id, type)
                val outputData = Bundle()
                outputData.putString("line", line)
                outputMsg.data = outputData

                try {
                    msg.replyTo.send(outputMsg)
                } catch (e: RemoteException) {
                    Log.e(TAG, "Error sending output to client: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error installing rootfs: ${e.message}", e)
            result = 1
            errorMessage = e.message
        }

        val reply = Message.obtain(null, MSG_COMMAND_RESULT, id, result)
        val replyData = Bundle()
        if (errorMessage != null) {
            replyData.putString("error", errorMessage)
        }
        reply.data = replyData

        try {
            msg.replyTo.send(reply)
        } catch (e: RemoteException) {
            Log.e(TAG, "Error sending result to client: ${e.message}")
        }
    }

    private fun deleteRootfs(msg: Message) {
        val id = msg.arg1
        var result = 0
        var errorMessage: String? = null

        try {
            mBuildEnvironment.deleteRootfs()
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting rootfs: ${e.message}", e)
            result = 1
            errorMessage = e.message
        }

        val reply = Message.obtain(null, MSG_COMMAND_RESULT, id, result)
        val replyData = Bundle()
        if (errorMessage != null) {
            replyData.putString("error", errorMessage)
        }
        reply.data = replyData

        try {
            msg.replyTo.send(reply)
        } catch (e: RemoteException) {
            Log.e(TAG, "Error sending result to client: ${e.message}")
        }
    }

}
