package com.steve1316.uma_android_automation.bot

import android.content.Context
import android.util.Log
import com.steve1316.uma_android_automation.utils.ImageUtils
import com.steve1316.uma_android_automation.utils.MessageLog
import java.util.concurrent.TimeUnit

/**
 * Main driver for bot activity and navigation.
 */
class Game(private val myContext: Context) {
	private val TAG: String = "UATH_Game"
	
	private val imageUtils: ImageUtils = ImageUtils(myContext, this)
	private val textDetection: TextDetection = TextDetection(myContext, this, imageUtils)
	
	private val startTime: Long = System.currentTimeMillis()
	
	/**
	 * Returns a formatted string of the elapsed time since the bot started as HH:MM:SS format.
	 *
	 * Source is from https://stackoverflow.com/questions/9027317/how-to-convert-milliseconds-to-hhmmss-format/9027379
	 *
	 * @return String of HH:MM:SS format of the elapsed time.
	 */
	private fun printTime(): String {
		val elapsedMillis: Long = System.currentTimeMillis() - startTime
		
		return String.format(
			"%02d:%02d:%02d",
			TimeUnit.MILLISECONDS.toHours(elapsedMillis),
			TimeUnit.MILLISECONDS.toMinutes(elapsedMillis) - TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(elapsedMillis)),
			TimeUnit.MILLISECONDS.toSeconds(elapsedMillis) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(elapsedMillis))
		)
	}
	
	/**
	 * Print the specified message to debug console and then saves the message to the log.
	 *
	 * @param message Message to be saved.
	 * @param MESSAGE_TAG TAG to distinguish between messages for where they came from. Defaults to Game's TAG.
	 * @param isError Flag to determine whether to display log message in console as debug or error.
	 * @param isOption Flag to determine whether to append a newline right after the time in the string.
	 */
	fun printToLog(message: String, MESSAGE_TAG: String = TAG, isError: Boolean = false, isOption: Boolean = false) {
		if (!isError) {
			Log.d(MESSAGE_TAG, message)
		} else {
			Log.e(MESSAGE_TAG, message)
		}
		
		// Remove the newline prefix if needed and place it where it should be.
		if (message.startsWith("\n")) {
			val newMessage = message.removePrefix("\n")
			if (isOption) {
				MessageLog.messageLog.add("\n" + printTime() + "\n" + newMessage)
			} else {
				MessageLog.messageLog.add("\n" + printTime() + " " + newMessage)
			}
		} else {
			if (isOption) {
				MessageLog.messageLog.add(printTime() + "\n" + message)
			} else {
				MessageLog.messageLog.add(printTime() + " " + message)
			}
		}
	}
	
	/**
	 * Bot will begin automation here.
	 *
	 * @return True if all automation goals have been met. False otherwise.
	 */
	fun start(): Boolean {
		val startTime: Long = System.currentTimeMillis()
		
		val endTime: Long = System.currentTimeMillis()
		
		Log.d(TAG, "Total Runtime: ${endTime - startTime}ms")
		
		return true
	}
}