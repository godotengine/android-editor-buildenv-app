package org.godotengine.godot_gradle_build_environment

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File

object Utils {
	private val TAG = Utils::class.java.simpleName

	const val EXTRA_PROJECT_PATH = "extra_project_path"
	const val EXTRA_TREE_URI = "extra_tree_uri"
	const val NOTIFICATION_CHANNEL_ID = "request_dir_access"
	const val ACTION_REQUEST_DIRECTORY_ACCESS = "action_request_dir_access"
	const val DIRECTORY_ACCESS_REQUEST = 1001

	fun createNotificationChannel(context: Context) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val channel = NotificationChannel(
				NOTIFICATION_CHANNEL_ID,
				context.getString(R.string.dir_access_notification_channel_name),
				NotificationManager.IMPORTANCE_HIGH,
			).apply {
				description = context.getString(R.string.dir_access_notification_channel_description)
			}

			val manager = context.getSystemService(NotificationManager::class.java)
			manager.createNotificationChannel(channel)
		}
	}

	@RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
	fun showDirectoryAccessNotification(context: Context, projectPath: String) {
		val intent = Intent(context, DirectoryAccessActivity::class.java).apply {
			action = ACTION_REQUEST_DIRECTORY_ACCESS
			putExtra(EXTRA_PROJECT_PATH, projectPath)
			flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
		}

		val pendingIntent = PendingIntent.getActivity(
			context,
			DIRECTORY_ACCESS_REQUEST,
			intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)

		val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
			.setSmallIcon(R.drawable.icon_rootfs_tab)
			.setContentTitle(context.getString(R.string.dir_access_notification_title))
			.setContentText(context.getString(R.string.dir_access_notification_message))
			.setPriority(NotificationCompat.PRIORITY_HIGH)
			.setContentIntent(pendingIntent)
			.setAutoCancel(true)
			.build()

		NotificationManagerCompat.from(context).notify(DIRECTORY_ACCESS_REQUEST, notification)
	}

	fun getProjectCacheDir(context: Context, projectPath: String, gradleBuildDir: String): File {
		val fullPath = File(projectPath, gradleBuildDir)
		val hash = Integer.toHexString(fullPath.absolutePath.hashCode())
		return File(AppPaths.getProjectDir(context).absolutePath, hash)
	}
}
