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
import java.io.File
import java.util.LinkedList

private const val MSG_EXECUTE_GRADLE = 1
private const val MSG_COMMAND_RESULT = 2
private const val MSG_COMMAND_OUTPUT = 3
private const val MSG_CANCEL_COMMAND = 4
private const val MSG_CLEAN_PROJECT = 5

class BuildEnvironmentService : Service() {

    companion object {
        private const val TAG = "BuildEnvironmentService"
    }

    private lateinit var mMessenger: Messenger
    private lateinit var mBuildEnvironment: BuildEnvironment
    private lateinit var mWorkThread: HandlerThread
    private lateinit var mWorkHandler: Handler

    internal class WorkItem(public val msg: Message, public val id: Int)
    private val queue = LinkedList<WorkItem>()
    private var currentItem: WorkItem? = null

    private val lock = Object()

    override fun onCreate() {
        super.onCreate()

        val rootfs = AppPaths.getRootfs(this).absolutePath
        val projectDir = AppPaths.getProjectDir(this).absolutePath
        mBuildEnvironment = BuildEnvironment(this, rootfs, projectDir)

        mWorkThread = HandlerThread("BuildEnvironmentServiceWorker")
        mWorkThread.start()
        mWorkHandler = Handler(mWorkThread.looper)

        val incomingHandler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                // Copy because the message may be recycled after this method returns.
                val copy = Message.obtain()
                copy.copyFrom(msg)

                when (msg.what) {
                    MSG_EXECUTE_GRADLE -> queueWork(WorkItem(copy, msg.arg1))
                    MSG_CANCEL_COMMAND -> cancelWork(msg.arg1)
                    MSG_CLEAN_PROJECT -> queueWork(WorkItem(copy, msg.arg1))
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
        synchronized(lock) {
            queue.add(item)
            lock.notifyAll()
        }
    }

    private fun cancelWork(id: Int) {
        // We only except ids greater than 0.
        if (id < 0) {
            return
        }

        Log.i(TAG, "Canceling command: ${id}")
        
        synchronized(lock) {
            if (currentItem?.id == id && currentItem?.msg?.what == MSG_EXECUTE_GRADLE) {
                mBuildEnvironment.killCurrentProcess()
            }
            queue.removeAll { it.id == id && it.msg.what == MSG_EXECUTE_GRADLE }
        }
    }

    private fun workerLoop() {
        while (true) {
            val work: WorkItem = synchronized(lock) {
                while (queue.isEmpty()) lock.wait()
                queue.removeFirst()
            }

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
            result = mBuildEnvironment.executeGradle(args, projectPath, gradleBuildDir, { type, line ->
                val reply = Message.obtain(null, MSG_COMMAND_OUTPUT, id, type)
                val replyData = Bundle()
                replyData.putString("line", line)
                reply.data = replyData

                try {
                    msg.replyTo.send(reply)
                } catch (e: RemoteException) {
                    Log.e(TAG, "Error send command output to client: ${e.message}")
                }
            })
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

        if (projectPath != null && gradleBuildDir != null) {
            mBuildEnvironment.cleanProject(projectPath, gradleBuildDir)
        }

        val reply = Message.obtain(null, MSG_COMMAND_RESULT, msg.arg1, 0)
        try {
            msg.replyTo.send(reply)
        } catch (e: RemoteException) {
            Log.e(TAG, "Error sending result to client: ${e.message}")
        }
    }

}
