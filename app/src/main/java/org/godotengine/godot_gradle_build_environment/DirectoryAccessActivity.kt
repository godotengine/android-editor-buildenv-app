package org.godotengine.godot_gradle_build_environment

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class DirectoryAccessActivity : ComponentActivity() {

	private var serviceMessenger: Messenger? = null
	private var isServiceBound = false

	private val directoryPickerLauncher =
		registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
			if (uri != null) {
				val msg = Message.obtain(null, BuildEnvironmentService.MSG_BUILD_DIR_ACCESS_GRANTED)
				msg.data = Bundle().apply {
					putString(Utils.EXTRA_TREE_URI, uri.toString())
				}
				serviceMessenger?.send(msg)
			}
			// Finish and remove from recents screen too
			finishAndRemoveTask()
		}

	private val connection = object : ServiceConnection {
		override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
			serviceMessenger = Messenger(binder)
		}

		override fun onServiceDisconnected(name: ComponentName?) {
			serviceMessenger = null
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		handleIntent(intent)
	}

	private fun handleIntent(intent: Intent) {
		if (intent.action == Utils.ACTION_REQUEST_DIRECTORY_ACCESS) {
			bindToBuildService()

			val tempProjectPath = intent.getStringExtra(Utils.EXTRA_PROJECT_PATH)
			var initialUri: Uri? = null
			if (tempProjectPath != null) {
				val externalStorageRoot = Environment.getExternalStorageDirectory().absolutePath
				if (tempProjectPath.startsWith(externalStorageRoot)) {
					val relativePath = tempProjectPath.replaceFirst(externalStorageRoot, "").trim('/')
					initialUri = Uri.Builder()
						.scheme("content")
						.authority("com.android.externalstorage.documents")
						.appendPath("document")
						.appendPath("primary:$relativePath")
						.build()
				}
			}
			directoryPickerLauncher.launch(initialUri)
		}
	}

	private fun bindToBuildService() {
		val intent = Intent(this, BuildEnvironmentService::class.java)
		bindService(intent, connection, BIND_AUTO_CREATE)
		isServiceBound = true
	}

	override fun onDestroy() {
		if (isServiceBound) {
			unbindService(connection)
		}
		super.onDestroy()
	}
}
