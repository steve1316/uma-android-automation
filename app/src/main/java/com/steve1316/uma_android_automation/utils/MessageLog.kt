package com.steve1316.uma_android_automation.utils

import android.content.Context
import android.util.Log
import com.steve1316.uma_android_automation.MainActivity
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * This class is in charge of holding the Message Log to which all logging messages from the bot goes to and also saves it all into a file when the bot has finished.
 */
class MessageLog {
	companion object {
		private const val TAG: String = "[${MainActivity.loggerTag}]MessageLog"
		var messageLog = arrayListOf<String>()
		
		var saveCheck = false
		
		/**
		 * Save the current Message Log into a new file inside internal storage's /logs/ folder.
		 *
		 * @param context The context for the application.
		 */
		fun saveLogToFile(context: Context) {
			cleanLogsFolder(context)
			
			if (!saveCheck) {
				Log.d(TAG, "Now beginning process to save current Message Log to internal storage...")
				
				// Generate file path to save to. All message logs will be saved to the /logs/ folder inside internal storage. Create the /logs/ folder if needed.
				val path = File(context.getExternalFilesDir(null)?.absolutePath + "/logs/")
				if (!path.exists()) {
					path.mkdirs()
				}
				
				// Generate the file name.
				val current = LocalDateTime.now()
				val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
				val fileName = "log @ ${current.format(formatter)}"
				
				// Now save the Message Log to the new text file.
				Log.d(TAG, "Now saving Message Log to file named \"$fileName\" at $path")
				val file = File(path, "$fileName.txt")
				
				if (!file.exists()) {
					file.createNewFile()
					file.printWriter().use { out ->
						messageLog.forEach {
							out.println(it)
						}
					}
					
					saveCheck = true
				}
			}
		}
		
		/**
		 * Clean up the logs folder if the amount of logs inside is greater than the specified amount.
		 *
		 * @param context The context for the application.
		 */
		private fun cleanLogsFolder(context: Context) {
			val directory = File(context.getExternalFilesDir(null)?.absolutePath + "/logs/")
			
			// Delete all logs if the amount inside is greater than 50.
			val files = directory.listFiles()
			if (files != null && files.size > 50) {
				files.forEach { file ->
					file.delete()
				}
			}
		}
	}
}