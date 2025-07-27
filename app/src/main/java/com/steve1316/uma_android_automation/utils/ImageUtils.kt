package com.steve1316.uma_android_automation.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.preference.PreferenceManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Game
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Integer.max
import java.text.DecimalFormat
import androidx.core.graphics.scale
import androidx.core.graphics.get


/**
 * Utility functions for image processing via CV like OpenCV.
 */
class ImageUtils(context: Context, private val game: Game) {
	private val tag: String = "[${MainActivity.loggerTag}]ImageUtils"
	private var myContext = context
	private val matchMethod: Int = Imgproc.TM_CCOEFF_NORMED
	private val decimalFormat = DecimalFormat("#.###")
	private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	private val tessBaseAPI: TessBaseAPI
	private val tesseractLanguages = arrayListOf("eng")
	
	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// SharedPreferences
	private var sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
	private val campaign: String = sharedPreferences.getString("campaign", "")!!
	private var customScale: Double = sharedPreferences.getString("customScale", "1.0")!!.toDouble()
	private val debugMode: Boolean = sharedPreferences.getBoolean("debugMode", false)
	
	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// Device configuration
	private val displayWidth: Int = MediaProjectionService.displayWidth
	private val displayHeight: Int = MediaProjectionService.displayHeight
	private val isLowerEnd: Boolean = (displayWidth == 720)
	private val isDefault: Boolean = (displayWidth == 1080)
	val isTablet: Boolean = (displayWidth == 1600 && displayHeight == 2560) || (displayWidth == 2560 && displayHeight == 1600) // Galaxy Tab S7 1600x2560 Portrait Mode
	private val isLandscape: Boolean = (displayWidth == 2560 && displayHeight == 1600) // Galaxy Tab S7 1600x2560 Landscape Mode
	private val isSplitScreen: Boolean = false // Uma Musume Pretty Derby is only playable in Portrait mode.
	
	// Scales
	private val lowerEndScales: MutableList<Double> = mutableListOf(0.60, 0.61, 0.62, 0.63, 0.64, 0.65, 0.67, 0.68, 0.69, 0.70)
	private val middleEndScales: MutableList<Double> = mutableListOf(
		0.70, 0.71, 0.72, 0.73, 0.74, 0.75, 0.76, 0.77, 0.78, 0.79, 0.80, 0.81, 0.82, 0.83, 0.84, 0.85, 0.87, 0.88, 0.89, 0.90, 0.91, 0.92, 0.93, 0.94, 0.95, 0.96, 0.97, 0.98, 0.99
	)
	private val tabletSplitPortraitScales: MutableList<Double> = mutableListOf(0.70, 0.71, 0.72, 0.73, 0.74, 0.75)
	private val tabletSplitLandscapeScales: MutableList<Double> = mutableListOf(0.55, 0.56, 0.57, 0.58, 0.59, 0.60)
	
	// TODO: Separate tablet landscape scale to non-splitscreen and splitscreen scales.
	private val tabletPortraitScales: MutableList<Double> = mutableListOf(1.28, 1.29, 1.30, 1.31, 1.32, 1.33)
	
	// Define template matching regions of the screen.
	val regionTopHalf: IntArray = intArrayOf(0, 0, displayWidth, displayHeight / 2)
	val regionBottomHalf: IntArray = intArrayOf(0, displayHeight / 2, displayWidth, displayHeight / 2)
	val regionMiddle: IntArray = intArrayOf(0, displayHeight / 4, displayWidth, displayHeight / 2)
	
	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	
	companion object {
		private var matchFilePath: String = ""
		private lateinit var matchLocation: Point
		private var matchLocations: ArrayList<Point> = arrayListOf()
		
		/**
		 * Saves the file path to the saved match image file for debugging purposes.
		 *
		 * @param filePath File path to where to store the image containing the location of where the match was found.
		 */
		private fun updateMatchFilePath(filePath: String) {
			matchFilePath = filePath
		}
	}
	
	init {
		// Set the file path to the /files/temp/ folder.
		val matchFilePath: String = myContext.getExternalFilesDir(null)?.absolutePath + "/temp"
		updateMatchFilePath(matchFilePath)
		
		// Initialize Tesseract with the traineddata model.
		initTesseract()
		tessBaseAPI = TessBaseAPI()
		
		// Start up Tesseract.
		tessBaseAPI.init(myContext.getExternalFilesDir(null)?.absolutePath + "/tesseract/", "eng")
		game.printToLog("[INFO] Training file loaded.\n", tag = tag)
	}
	
	/**
	 * Match between the source Bitmap from /files/temp/ and the template Bitmap from the assets folder.
	 *
	 * @param sourceBitmap Bitmap from the /files/temp/ folder.
	 * @param templateBitmap Bitmap from the assets folder.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @param useSingleScale Whether to use only the single custom scale or to use a range based off of it.
	 * @param customConfidence Specify a custom confidence. Defaults to the confidence set in the app's settings.
	 * @return True if a match was found. False otherwise.
	 */
	private fun match(sourceBitmap: Bitmap, templateBitmap: Bitmap, region: IntArray = intArrayOf(0, 0, 0, 0), useSingleScale: Boolean = false, customConfidence: Double = 0.0): Boolean {
		// If a custom region was specified, crop the source screenshot.
		val srcBitmap = if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
			Bitmap.createBitmap(sourceBitmap, region[0], region[1], region[2], region[3])
		} else {
			sourceBitmap
		}
		
		val setConfidence: Double = if (customConfidence == 0.0) {
			0.8
		} else {
			customConfidence
		}
		
		// Scale images if the device is not 1080p which is supported by default.
		val scales: MutableList<Double> = when {
			customScale != 1.0 && !useSingleScale -> {
				mutableListOf(customScale - 0.02, customScale - 0.01, customScale, customScale + 0.01, customScale + 0.02, customScale + 0.03, customScale + 0.04)
			}
			customScale != 1.0 && useSingleScale -> {
				mutableListOf(customScale)
			}
			isLowerEnd -> {
				lowerEndScales.toMutableList()
			}
			!isLowerEnd && !isDefault && !isTablet -> {
				middleEndScales.toMutableList()
			}
			isTablet && isSplitScreen && isLandscape -> {
				tabletSplitLandscapeScales.toMutableList()
			}
			isTablet && isSplitScreen && !isLandscape -> {
				tabletSplitPortraitScales.toMutableList()
			}
			isTablet && !isSplitScreen && !isLandscape -> {
				tabletPortraitScales.toMutableList()
			}
			else -> {
				mutableListOf(1.0)
			}
		}
		
		while (scales.isNotEmpty()) {
			val newScale: Double = decimalFormat.format(scales.removeAt(0)).toDouble()
			
			val tmp: Bitmap = if (newScale != 1.0) {
				Bitmap.createScaledBitmap(templateBitmap, (templateBitmap.width * newScale).toInt(), (templateBitmap.height * newScale).toInt(), true)
			} else {
				templateBitmap
			}
			
			// Create the Mats of both source and template images.
			val sourceMat = Mat()
			val templateMat = Mat()
			Utils.bitmapToMat(srcBitmap, sourceMat)
			Utils.bitmapToMat(tmp, templateMat)
			
			// Make the Mats grayscale for the source and the template.
			Imgproc.cvtColor(sourceMat, sourceMat, Imgproc.COLOR_BGR2GRAY)
			Imgproc.cvtColor(templateMat, templateMat, Imgproc.COLOR_BGR2GRAY)
			
			// Create the result matrix.
			val resultColumns: Int = sourceMat.cols() - templateMat.cols() + 1
			val resultRows: Int = sourceMat.rows() - templateMat.rows() + 1
			val resultMat = Mat(resultRows, resultColumns, CvType.CV_32FC1)
			
			// Now perform the matching and localize the result.
			Imgproc.matchTemplate(sourceMat, templateMat, resultMat, matchMethod)
			val mmr: Core.MinMaxLocResult = Core.minMaxLoc(resultMat)
			
			matchLocation = Point()
			var matchCheck = false
			
			// Format minVal or maxVal.
			val minVal: Double = decimalFormat.format(mmr.minVal).toDouble()
			val maxVal: Double = decimalFormat.format(mmr.maxVal).toDouble()
			
			// Depending on which matching method was used, the algorithms determine which location was the best.
			if ((matchMethod == Imgproc.TM_SQDIFF || matchMethod == Imgproc.TM_SQDIFF_NORMED) && mmr.minVal <= (1.0 - setConfidence)) {
				matchLocation = mmr.minLoc
				matchCheck = true
				if (debugMode) {
					game.printToLog("[DEBUG] Match found with $minVal <= ${1.0 - setConfidence} at Point $matchLocation using scale: $newScale.", tag = tag)
				}
			} else if ((matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED) && mmr.maxVal >= setConfidence) {
				matchLocation = mmr.maxLoc
				matchCheck = true
				if (debugMode) {
					game.printToLog("[DEBUG] Match found with $maxVal >= $setConfidence at Point $matchLocation using scale: $newScale.", tag = tag)
				}
			} else {
				if (debugMode) {
					if ((matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED)) {
						game.printToLog("[DEBUG] Match not found with $maxVal not >= $setConfidence at Point ${mmr.maxLoc} using scale $newScale.", tag = tag)
					} else {
						game.printToLog("[DEBUG] Match not found with $minVal not <= ${1.0 - setConfidence} at Point ${mmr.minLoc} using scale $newScale.", tag = tag)
					}
				}
			}
			
			if (matchCheck) {
				if (debugMode && matchFilePath != "") {
					// Draw a rectangle around the supposed best matching location and then save the match into a file in /files/temp/ directory. This is for debugging purposes to see if this
					// algorithm found the match accurately or not.
					Imgproc.rectangle(sourceMat, matchLocation, Point(matchLocation.x + templateMat.cols(), matchLocation.y + templateMat.rows()), Scalar(0.0, 0.0, 0.0), 10)
					Imgcodecs.imwrite("$matchFilePath/match.png", sourceMat)
				}
				
				// Center the coordinates so that any tap gesture would be directed at the center of that match location instead of the default
				// position of the top left corner of the match location.
				matchLocation.x += (templateMat.cols() / 2)
				matchLocation.y += (templateMat.rows() / 2)
				
				// If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
				if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
					matchLocation.x = sourceBitmap.width - (sourceBitmap.width - (region[0] + matchLocation.x))
					matchLocation.y = sourceBitmap.height - (sourceBitmap.height - (region[1] + matchLocation.y))
				}
				
				return true
			}
		}
		
		return false
	}
	
	/**
	 * Search through the whole source screenshot for all matches to the template image.
	 *
	 * @param sourceBitmap Bitmap from the /files/temp/ folder.
	 * @param templateBitmap Bitmap from the assets folder.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @param customConfidence Specify a custom confidence. Defaults to the confidence set in the app's settings.
	 * @return ArrayList of Point objects that represents the matches found on the source screenshot.
	 */
	private fun matchAll(sourceBitmap: Bitmap, templateBitmap: Bitmap, region: IntArray = intArrayOf(0, 0, 0, 0), customConfidence: Double = 0.0): java.util.ArrayList<Point> {
		// If a custom region was specified, crop the source screenshot.
		val srcBitmap = if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
			Bitmap.createBitmap(sourceBitmap, region[0], region[1], region[2], region[3])
		} else {
			sourceBitmap
		}
		
		// Scale images if the device is not 1080p which is supported by default.
		val scales: MutableList<Double> = when {
			customScale != 1.0 -> {
				mutableListOf(customScale - 0.02, customScale - 0.01, customScale, customScale + 0.01, customScale + 0.02, customScale + 0.03, customScale + 0.04)
			}
			isLowerEnd -> {
				lowerEndScales.toMutableList()
			}
			!isLowerEnd && !isDefault && !isTablet -> {
				middleEndScales.toMutableList()
			}
			isTablet && isSplitScreen && isLandscape -> {
				tabletSplitLandscapeScales.toMutableList()
			}
			isTablet && isSplitScreen && !isLandscape -> {
				tabletSplitPortraitScales.toMutableList()
			}
			isTablet && !isSplitScreen && !isLandscape -> {
				tabletPortraitScales.toMutableList()
			}
			else -> {
				mutableListOf(1.0)
			}
		}
		
		val setConfidence: Double = if (customConfidence == 0.0) {
			0.8
		} else {
			customConfidence
		}
		
		var matchCheck = false
		var newScale = 0.0
		val sourceMat = Mat()
		val templateMat = Mat()
		var resultMat = Mat()
		
		// Set templateMat at whatever scale it found the very first match for the next while loop.
		while (!matchCheck && scales.isNotEmpty()) {
			newScale = decimalFormat.format(scales.removeAt(0)).toDouble()
			
			val tmp: Bitmap = if (newScale != 1.0) {
				Bitmap.createScaledBitmap(templateBitmap, (templateBitmap.width * newScale).toInt(), (templateBitmap.height * newScale).toInt(), true)
			} else {
				templateBitmap
			}
			
			// Create the Mats of both source and template images.
			Utils.bitmapToMat(srcBitmap, sourceMat)
			Utils.bitmapToMat(tmp, templateMat)
			
			// Make the Mats grayscale for the source and the template.
			Imgproc.cvtColor(sourceMat, sourceMat, Imgproc.COLOR_BGR2GRAY)
			Imgproc.cvtColor(templateMat, templateMat, Imgproc.COLOR_BGR2GRAY)
			
			// Create the result matrix.
			val resultColumns: Int = sourceMat.cols() - templateMat.cols() + 1
			val resultRows: Int = sourceMat.rows() - templateMat.rows() + 1
			if (resultColumns < 0 || resultRows < 0) {
				break
			}
			
			resultMat = Mat(resultRows, resultColumns, CvType.CV_32FC1)
			
			// Now perform the matching and localize the result.
			Imgproc.matchTemplate(sourceMat, templateMat, resultMat, matchMethod)
			val mmr: Core.MinMaxLocResult = Core.minMaxLoc(resultMat)
			
			matchLocation = Point()
			
			// Depending on which matching method was used, the algorithms determine which location was the best.
			if ((matchMethod == Imgproc.TM_SQDIFF || matchMethod == Imgproc.TM_SQDIFF_NORMED) && mmr.minVal <= (1.0 - setConfidence)) {
				matchLocation = mmr.minLoc
				matchCheck = true
				
				// Draw a rectangle around the match on the source Mat. This will prevent false positives and infinite looping on subsequent matches.
				Imgproc.rectangle(sourceMat, matchLocation, Point(matchLocation.x + templateMat.cols(), matchLocation.y + templateMat.rows()), Scalar(0.0, 0.0, 0.0), 20)
				
				// Center the location coordinates and then save it.
				matchLocation.x += (templateMat.cols() / 2)
				matchLocation.y += (templateMat.rows() / 2)
				
				// If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
				if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
					matchLocation.x = sourceBitmap.width - (sourceBitmap.width - (region[0] + matchLocation.x))
					matchLocation.y = sourceBitmap.height - (sourceBitmap.height - (region[1] + matchLocation.y))
				}
				
				matchLocations.add(matchLocation)
			} else if ((matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED) && mmr.maxVal >= setConfidence) {
				matchLocation = mmr.maxLoc
				matchCheck = true
				
				// Draw a rectangle around the match on the source Mat. This will prevent false positives and infinite looping on subsequent matches.
				Imgproc.rectangle(sourceMat, matchLocation, Point(matchLocation.x + templateMat.cols(), matchLocation.y + templateMat.rows()), Scalar(0.0, 0.0, 0.0), 20)
				
				// Center the location coordinates and then save it.
				matchLocation.x += (templateMat.cols() / 2)
				matchLocation.y += (templateMat.rows() / 2)
				
				// If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
				if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
					matchLocation.x = sourceBitmap.width - (sourceBitmap.width - (region[0] + matchLocation.x))
					matchLocation.y = sourceBitmap.height - (sourceBitmap.height - (region[1] + matchLocation.y))
				}
				
				matchLocations.add(matchLocation)
			}
		}
		
		// Loop until all other matches are found and break out when there are no more to be found.
		while (matchCheck) {
			// Now perform the matching and localize the result.
			Imgproc.matchTemplate(sourceMat, templateMat, resultMat, matchMethod)
			val mmr: Core.MinMaxLocResult = Core.minMaxLoc(resultMat)
			
			// Format minVal or maxVal.
			val minVal: Double = decimalFormat.format(mmr.minVal).toDouble()
			val maxVal: Double = decimalFormat.format(mmr.maxVal).toDouble()
			
			if ((matchMethod == Imgproc.TM_SQDIFF || matchMethod == Imgproc.TM_SQDIFF_NORMED) && mmr.minVal <= (1.0 - setConfidence)) {
				val tempMatchLocation: Point = mmr.minLoc
				
				// Draw a rectangle around the match on the source Mat. This will prevent false positives and infinite looping on subsequent matches.
				Imgproc.rectangle(sourceMat, tempMatchLocation, Point(tempMatchLocation.x + templateMat.cols(), tempMatchLocation.y + templateMat.rows()), Scalar(0.0, 0.0, 0.0), 20)
				
				if (debugMode) {
					game.printToLog("[DEBUG] Match found with $minVal <= ${1.0 - setConfidence} at Point $matchLocation with scale: $newScale.", tag = tag)
					Imgcodecs.imwrite("$matchFilePath/matchAll.png", sourceMat)
				}
				
				// Center the location coordinates and then save it.
				tempMatchLocation.x += (templateMat.cols() / 2)
				tempMatchLocation.y += (templateMat.rows() / 2)
				
				// If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
				if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
					tempMatchLocation.x = sourceBitmap.width - (sourceBitmap.width - (region[0] + tempMatchLocation.x))
					tempMatchLocation.y = sourceBitmap.height - (sourceBitmap.height - (region[1] + tempMatchLocation.y))
				}
				
				if (!matchLocations.contains(tempMatchLocation) && !matchLocations.contains(Point(tempMatchLocation.x + 1.0, tempMatchLocation.y)) &&
					!matchLocations.contains(Point(tempMatchLocation.x, tempMatchLocation.y + 1.0)) && !matchLocations.contains(Point(tempMatchLocation.x + 1.0, tempMatchLocation.y + 1.0))) {
					matchLocations.add(tempMatchLocation)
				}
			} else if ((matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED) && mmr.maxVal >= setConfidence) {
				val tempMatchLocation: Point = mmr.maxLoc
				
				// Draw a rectangle around the match on the source Mat. This will prevent false positives and infinite looping on subsequent matches.
				Imgproc.rectangle(sourceMat, tempMatchLocation, Point(tempMatchLocation.x + templateMat.cols(), tempMatchLocation.y + templateMat.rows()), Scalar(0.0, 0.0, 0.0), 20)
				
				if (debugMode) {
					game.printToLog("[DEBUG] Match found with $maxVal >= $setConfidence at Point $matchLocation with scale: $newScale.", tag = tag)
					Imgcodecs.imwrite("$matchFilePath/matchAll.png", sourceMat)
				}
				
				// Center the location coordinates and then save it.
				tempMatchLocation.x += (templateMat.cols() / 2)
				tempMatchLocation.y += (templateMat.rows() / 2)
				
				// If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
				if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
					tempMatchLocation.x = sourceBitmap.width - (sourceBitmap.width - (region[0] + tempMatchLocation.x))
					tempMatchLocation.y = sourceBitmap.height - (sourceBitmap.height - (region[1] + tempMatchLocation.y))
				}
				
				if (!matchLocations.contains(tempMatchLocation) && !matchLocations.contains(Point(tempMatchLocation.x + 1.0, tempMatchLocation.y)) &&
					!matchLocations.contains(Point(tempMatchLocation.x, tempMatchLocation.y + 1.0)) && !matchLocations.contains(Point(tempMatchLocation.x + 1.0, tempMatchLocation.y + 1.0))) {
					matchLocations.add(tempMatchLocation)
				}
			} else {
				break
			}
		}
		
		return matchLocations
	}
	
	
	/**
	 * Open the source and template image files and return Bitmaps for them.
	 *
	 * @param templateName File name of the template image.
	 * @return A Pair of source and template Bitmaps.
	 */
	fun getBitmaps(templateName: String): Pair<Bitmap?, Bitmap?> {
		var sourceBitmap: Bitmap? = null
		
		while (sourceBitmap == null) {
			sourceBitmap = MediaProjectionService.takeScreenshotNow(saveImage = debugMode)
		}
		
		var templateBitmap: Bitmap?
		
		// Get the Bitmap from the template image file inside the specified folder.
		myContext.assets?.open("images/$templateName.png").use { inputStream ->
			// Get the Bitmap from the template image file and then start matching.
			templateBitmap = BitmapFactory.decodeStream(inputStream)
		}
		
		return if (templateBitmap != null) {
			Pair(sourceBitmap, templateBitmap)
		} else {
			if (debugMode) {
				game.printToLog("[ERROR] One or more of the Bitmaps are null.", tag = tag, isError = true)
			}
			
			Pair(sourceBitmap, templateBitmap)
		}
	}
	
	/**
	 * Acquire the Bitmap for only the source screenshot.
	 *
	 * @return Bitmap of the source screenshot.
	 */
	private fun getSourceBitmap(): Bitmap {
		var sourceBitmap: Bitmap? = null
		while (sourceBitmap == null) {
			sourceBitmap = MediaProjectionService.takeScreenshotNow(saveImage = debugMode)
		}
		
		return sourceBitmap
	}
	
	/**
	 * Finds the location of the specified image from the /images/ folder inside assets.
	 *
	 * @param templateName File name of the template image.
	 * @param tries Number of tries before failing. Defaults to 5.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @param suppressError Whether or not to suppress saving error messages to the log. Defaults to false.
	 * @return Pair object consisting of the Point object containing the location of the match and the source screenshot. Can be null.
	 */
	fun findImage(templateName: String, tries: Int = 5, region: IntArray = intArrayOf(0, 0, 0, 0), suppressError: Boolean = false): Pair<Point?, Bitmap?> {
		var numberOfTries = tries
		
		if (debugMode) {
			game.printToLog("\n[DEBUG] Starting process to find the ${templateName.uppercase()} button image...", tag = tag)
		}
		
		var (sourceBitmap, templateBitmap) = getBitmaps(templateName)
		
		while (numberOfTries > 0) {
			if (sourceBitmap != null && templateBitmap != null) {
				val resultFlag: Boolean = match(sourceBitmap, templateBitmap, region, useSingleScale = true)
				if (!resultFlag) {
					numberOfTries -= 1
					if (numberOfTries <= 0) {
						if (debugMode && !suppressError) {
							game.printToLog("[WARNING] Failed to find the ${templateName.uppercase()} button.", tag = tag)
						}
						
						break
					}
					
					Log.d(tag, "Failed to find the ${templateName.uppercase()} button. Trying again...")
					game.wait(0.1)
					sourceBitmap = getSourceBitmap()
				} else {
					game.printToLog("[SUCCESS] Found the ${templateName.uppercase()} at $matchLocation.", tag = tag)
					return Pair(matchLocation, sourceBitmap)
				}
			}
		}
		
		return Pair(null, sourceBitmap)
	}
	
	/**
	 * Confirms whether or not the bot is at the specified location from the /headers/ folder inside assets.
	 *
	 * @param templateName File name of the template image.
	 * @param tries Number of tries before failing. Defaults to 5.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @param suppressError Whether or not to suppress saving error messages to the log.
	 * @return True if the current location is at the specified location. False otherwise.
	 */
	fun confirmLocation(templateName: String, tries: Int = 5, region: IntArray = intArrayOf(0, 0, 0, 0), suppressError: Boolean = false): Boolean {
		var numberOfTries = tries
		
		if (debugMode) {
			game.printToLog("\n[DEBUG] Starting process to find the ${templateName.uppercase()} header image...", tag = tag)
		}
		
		var (sourceBitmap, templateBitmap) = getBitmaps(templateName + "_header")
		
		while (numberOfTries > 0) {
			if (sourceBitmap != null && templateBitmap != null) {
				val resultFlag: Boolean = match(sourceBitmap, templateBitmap, region)
				if (!resultFlag) {
					numberOfTries -= 1
					if (numberOfTries <= 0) {
						break
					}
					
					game.wait(0.1)
					sourceBitmap = getSourceBitmap()
				} else {
					game.printToLog("[SUCCESS] Current location confirmed to be at ${templateName.uppercase()}.", tag = tag)
					return true
				}
			} else {
				break
			}
		}
		
		if (debugMode && !suppressError) {
			game.printToLog("[WARNING] Failed to confirm the bot location at ${templateName.uppercase()}.", tag = tag)
		}
		
		return false
	}
	
	/**
	 * Finds all occurrences of the specified image in the images folder.
	 *
	 * @param templateName File name of the template image.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @return An ArrayList of Point objects containing all the occurrences of the specified image or null if not found.
	 */
	fun findAll(templateName: String, region: IntArray = intArrayOf(0, 0, 0, 0)): ArrayList<Point> {
		val (sourceBitmap, templateBitmap) = getBitmaps(templateName)
		
		// Clear the ArrayList first before attempting to find all matches.
		matchLocations.clear()
		
		if (sourceBitmap != null && templateBitmap != null) {
			matchAll(sourceBitmap, templateBitmap, region = region)
		}
		
		// Sort the match locations by ascending x and y coordinates.
		matchLocations.sortBy { it.x }
		matchLocations.sortBy { it.y }
		
		if (debugMode) {
			game.printToLog("[DEBUG] Found match locations for $templateName: $matchLocations.", tag = tag)
		} else {
			Log.d(tag, "[DEBUG] Found match locations for $templateName: $matchLocations.")
		}
		
		return matchLocations
	}

	/**
	 * Check if the color at the specified coordinates matches the given RGB value.
	 *
	 * @param x X coordinate to check.
	 * @param y Y coordinate to check.
	 * @param rgb Expected RGB values as red, blue and green (0-255).
	 * @param tolerance Tolerance for color matching (0-255). Defaults to 0 for exact match.
	 * @return True if the color at the coordinates matches the expected RGB values within tolerance, false otherwise.
	 */
	fun checkColorAtCoordinates(x: Int, y: Int, rgb: IntArray, tolerance: Int = 0): Boolean {
		val sourceBitmap = getSourceBitmap()

		// Check if coordinates are within bounds.
		if (x < 0 || y < 0 || x >= sourceBitmap.width || y >= sourceBitmap.height) {
			if (debugMode) {
				game.printToLog("[WARNING] Coordinates ($x, $y) are out of bounds for bitmap size ${sourceBitmap.width}x${sourceBitmap.height}", tag = tag)
			}
			return false
		}

		// Get the pixel color at the specified coordinates.
		val pixel = sourceBitmap[x, y]

		// Extract RGB values from the pixel.
		val actualRed = android.graphics.Color.red(pixel)
		val actualGreen = android.graphics.Color.green(pixel)
		val actualBlue = android.graphics.Color.blue(pixel)

		// Check if the colors match within the specified tolerance.
		val redMatch = kotlin.math.abs(actualRed - rgb[0]) <= tolerance
		val greenMatch = kotlin.math.abs(actualGreen - rgb[1]) <= tolerance
		val blueMatch = kotlin.math.abs(actualBlue - rgb[2]) <= tolerance

		if (debugMode) {
			game.printToLog("[DEBUG] Color check at ($x, $y): Expected RGB(${rgb[0]}, ${rgb[1]}, ${rgb[2]}), Actual RGB($actualRed, $actualGreen, $actualBlue), Match: ${redMatch && greenMatch && blueMatch}", tag = tag)
		}

		return redMatch && greenMatch && blueMatch
	}

	/**
	 * Perform OCR text detection using Tesseract along with some image manipulation via thresholding to make the cropped screenshot black and white using OpenCV.
	 *
	 * @param increment Increments the threshold by this value. Defaults to 0.0.
	 * @return The detected String in the cropped region.
	 */
	fun findText(increment: Double = 0.0): String {
		val (sourceBitmap, templateBitmap) = getBitmaps("shift")
		
		// Acquire the location of the energy text image.
		val (_, energyTemplateBitmap) = getBitmaps("energy")
		match(sourceBitmap!!, energyTemplateBitmap!!)
		
		// Use the match location acquired from finding the energy text image and acquire the (x, y) coordinates of the event title container right below the location of the energy text image.
		val newX: Int
		val newY: Int
		var croppedBitmap: Bitmap = if (isTablet) {
			newX = max(0, matchLocation.x.toInt() - (250).toInt())
			newY = max(0, matchLocation.y.toInt() + (154).toInt())
			Bitmap.createBitmap(sourceBitmap, newX, newY, 746, 85)
		} else {
			newX = max(0, matchLocation.x.toInt() - 125)
			newY = max(0, matchLocation.y.toInt() + 116)
			Bitmap.createBitmap(sourceBitmap, newX, newY, 645, 65)
		}
		
		val tempImage = Mat()
		Utils.bitmapToMat(croppedBitmap, tempImage)
		if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugEventTitleText.png", tempImage)
		
		// Now see if it is necessary to shift the cropped region over by 70 pixels or not to account for certain events.
		croppedBitmap = if (match(croppedBitmap, templateBitmap!!)) {
			Log.d(tag, "Shifting the region over by 70 pixels!")
			Bitmap.createBitmap(sourceBitmap, newX + 70, newY, 645 - 70, 65)
		} else {
			Log.d(tag, "Do not need to shift.")
			croppedBitmap
		}
		
		// Make the cropped screenshot grayscale.
		val cvImage = Mat()
		Utils.bitmapToMat(croppedBitmap, cvImage)
		Imgproc.cvtColor(cvImage, cvImage, Imgproc.COLOR_BGR2GRAY)
		
		// Save the cropped image before converting it to black and white in order to troubleshoot issues related to differing device sizes and cropping.
		if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugEventTitleText_afterCrop.png", cvImage)
		
		// Thresh the grayscale cropped image to make black and white.
		val bwImage = Mat()
		val threshold = sharedPreferences.getInt("threshold", 230)
		Imgproc.threshold(cvImage, bwImage, threshold.toDouble() + increment, 255.0, Imgproc.THRESH_BINARY)
		if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugEventTitleText_afterThreshold.png", bwImage)
		
		val resultBitmap = BitmapFactory.decodeFile("$matchFilePath/debugEventTitleText_afterThreshold.png")
		tessBaseAPI.setImage(resultBitmap)
		
		// Set the Page Segmentation Mode to '--psm 7' or "Treat the image as a single text line" according to https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html#page-segmentation-method
		tessBaseAPI.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
		
		var result = "empty!"
		try {
			// Finally, detect text on the cropped region.
			result = tessBaseAPI.utF8Text
		} catch (e: Exception) {
			game.printToLog("[ERROR] Cannot perform OCR: ${e.stackTraceToString()}", tag = tag, isError = true)
		}
		
		tessBaseAPI.clear()
		
		return result
	}
	
	/**
	 * Find the success percentage chance on the currently selected stat.
	 *
	 * @return Integer representing the percentage.
	 */
	fun findTrainingFailureChance(): Int {
		// Crop the source screenshot to hold the success percentage only.
		val (trainingSelectionLocation, sourceBitmap) = findImage("training_failure_chance")
		
		val croppedBitmap: Bitmap = if (isTablet) {
			Bitmap.createBitmap(sourceBitmap!!, trainingSelectionLocation!!.x.toInt() - 65, trainingSelectionLocation.y.toInt() + 23, 130, 50)
		} else {
			Bitmap.createBitmap(sourceBitmap!!, trainingSelectionLocation!!.x.toInt() - 45, trainingSelectionLocation.y.toInt() + 15, 100, 37)
		}
		
		// Save the cropped image for debugging purposes.
		val tempMat = Mat()
		Utils.bitmapToMat(croppedBitmap, tempMat)
		Imgproc.cvtColor(tempMat, tempMat, Imgproc.COLOR_BGR2GRAY)
		if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugTrainingFailureChance.png", tempMat)
		
		// Create a InputImage object for Google's ML OCR.
		val inputImage: InputImage = InputImage.fromBitmap(croppedBitmap, 0)
		
		// Start the asynchronous operation of text detection.
		var result = 0
		textRecognizer.process(inputImage).addOnSuccessListener {
			if (it.textBlocks.size != 0) {
				for (block in it.textBlocks) {
					result = try {
						block.text.replace("%", "").trim().toInt()
					} catch (e: NumberFormatException) {
						0
					}
				}
			}
		}.addOnFailureListener {
			game.printToLog("[ERROR] Failed to do text detection via Google's ML Kit on Bitmap.", tag = tag, isError = true)
		}
		
		// Wait a little bit for the asynchronous operations of Google's OCR to finish. Since the cropped region is really small, the asynchronous operations should be really fast.
		game.wait(0.1)
		
		if (debugMode) {
			game.printToLog("[DEBUG] Failure chance detected to be at $result%.")
		} else {
			Log.d(tag, "Failure chance detected to be at $result%.")
		}
		
		return result
	}
	
	/**
	 * Determine the initial stat weight by factoring in estimated stat gains and others like Ao Haru's modifiers for the selected Training option.
	 *
	 * @param currentStat The stat that the bot already selected.
	 * @return The initial stat weight.
	 */
	fun findInitialStatWeight(currentStat: String): Int {
		val customRegion = intArrayOf(displayWidth - (displayWidth / 3), 0, (displayWidth / 3), displayHeight - (displayHeight / 3))
		val numberOfSpeed = findAll("stat_speed_block", region = customRegion).size
		val numberOfStamina = findAll("stat_stamina_block", region = customRegion).size
		val numberOfPower = findAll("stat_power_block", region = customRegion).size
		val numberOfGuts = findAll("stat_guts_block", region = customRegion).size
		val numberOfWit = findAll("stat_wit_block", region = customRegion).size
		var totalStatGain = 0
		
		// This is assuming Great Mood with +20% stat modifier.
		when (currentStat) {
			"Speed" -> {
				totalStatGain += (numberOfSpeed * 20) + (numberOfStamina * 10) + (numberOfPower * 10) + (numberOfGuts * 10) + (numberOfWit * 10) + 10
			}
			"Stamina" -> {
				totalStatGain += (numberOfSpeed * 10) + (numberOfStamina * 20) + (numberOfPower * 10) + (numberOfGuts * 10) + (numberOfWit * 10) + 10
			}
			"Power" -> {
				totalStatGain += (numberOfSpeed * 10) + (numberOfStamina * 10) + (numberOfPower * 20) + (numberOfGuts * 10) + (numberOfWit * 10) + 10
			}
			"Guts" -> {
				totalStatGain += (numberOfSpeed * 10) + (numberOfStamina * 10) + (numberOfPower * 10) + (numberOfGuts * 20) + (numberOfWit * 10) + 10
			}
			"Wit" -> {
				totalStatGain += (numberOfSpeed * 10) + (numberOfStamina * 10) + (numberOfPower * 10) + (numberOfGuts * 10) + (numberOfWit * 20) + 10
			}
		}
		
		// TODO: Have an option to have skill hints factor into the weight.
		
		if (campaign == "Ao Haru") {
			totalStatGain += (findAll("aoharu_special_training", region = customRegion).size * 10)
			totalStatGain += (findAll("aoharu_spirit_explosion", region = customRegion).size * 20)
		}
		
		return totalStatGain
	}
	
	/**
	 * Determines the day number to see if today is eligible for doing an extra race.
	 *
	 * @return Number of the day.
	 */
	fun determineDayForExtraRace(): Int {
		var result = -1
		val (energyTextLocation, sourceBitmap) = findImage("energy", tries = 1, region = regionTopHalf)
		
		if (energyTextLocation != null) {
			// Crop the source screenshot to only contain the day number.
			val croppedBitmap: Bitmap = if (campaign == "Ao Haru") {
				if (isTablet) {
					Bitmap.createBitmap(sourceBitmap!!, energyTextLocation.x.toInt() - (260 * 1.32).toInt(), energyTextLocation.y.toInt() - (140 * 1.32).toInt(), 135, 100)
				} else {
					Bitmap.createBitmap(sourceBitmap!!, energyTextLocation.x.toInt() - 260, energyTextLocation.y.toInt() - 140, 105, 75)
				}
			} else {
				if (isTablet) {
					Bitmap.createBitmap(sourceBitmap!!, energyTextLocation.x.toInt() - (246 * 1.32).toInt(), energyTextLocation.y.toInt() - (96 * 1.32).toInt(), 175, 116)
				} else {
					Bitmap.createBitmap(sourceBitmap!!, energyTextLocation.x.toInt() - 246, energyTextLocation.y.toInt() - 100, 140, 100)
				}
			}
			
			val resizedBitmap = croppedBitmap.scale(croppedBitmap.width / 2, croppedBitmap.height / 2)

			val cvImage = Mat()
			Utils.bitmapToMat(resizedBitmap, cvImage)
			Imgproc.cvtColor(cvImage, cvImage, Imgproc.COLOR_BGR2GRAY)
			if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugDayForExtraRace.png", cvImage)
			
			// Create a InputImage object for Google's ML OCR.
			val inputImage: InputImage = InputImage.fromBitmap(resizedBitmap, 0)
			
			// Count up all of the total stat gains for this training selection.
			textRecognizer.process(inputImage).addOnSuccessListener {
				if (it.textBlocks.size != 0) {
					for (block in it.textBlocks) {
						try {
							Log.d(tag, "Detected Day Number for Extra Race: ${block.text}")
							result = block.text.toInt()
						} catch (e: NumberFormatException) {
						}
					}
				}
			}.addOnFailureListener {
				game.printToLog("[ERROR] Failed to do text detection via Google's ML Kit on Bitmap.", tag = tag, isError = true)
			}
			
			// Wait a little bit for the asynchronous operations of Google's OCR to finish. Since the cropped region is really small, the asynchronous operations should be really fast.
			game.wait(0.25)
		}
		
		return result
	}
	
	/**
	 * Determine the amount of fans that the extra race will give only if it matches the double star prediction.
	 *
	 * @param extraRaceLocation Point object of the extra race's location.
	 * @param sourceBitmap Bitmap of the source screenshot.
	 * @param doubleStarPredictionBitmap Bitmap of the double star prediction template image.
	 * @return Number of fans to be gained from the extra race or -1 if not found.
	 */
	fun determineExtraRaceFans(extraRaceLocation: Point, sourceBitmap: Bitmap, doubleStarPredictionBitmap: Bitmap): Int {
		// Crop the source screenshot to show only the fan amount and the predictions.
		val croppedBitmap = if (isTablet) {
			Bitmap.createBitmap(sourceBitmap, extraRaceLocation.x.toInt() - (173 * 1.34).toInt(), extraRaceLocation.y.toInt() - (106 * 1.34).toInt(), 220, 125)
		} else {
			Bitmap.createBitmap(sourceBitmap, extraRaceLocation.x.toInt() - 173, extraRaceLocation.y.toInt() - 106, 163, 96)
		}
		val cvImage = Mat()
		Utils.bitmapToMat(croppedBitmap, cvImage)
		if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugExtraRacePrediction.png", cvImage)
		
		// Determine if the extra race has double star prediction.
		var predictionCheck = false
		if (match(croppedBitmap, doubleStarPredictionBitmap)) {
			predictionCheck = true
		}
		
		return if (predictionCheck) {
			// Crop the source screenshot to show only the fans.
			val croppedBitmap2 = if (isTablet) {
				Bitmap.createBitmap(sourceBitmap, extraRaceLocation.x.toInt() - (534 * 1.40).toInt(), extraRaceLocation.y.toInt() - (75 * 1.34).toInt(), 221, 40)
			} else {
				Bitmap.createBitmap(sourceBitmap, extraRaceLocation.x.toInt() - 534, extraRaceLocation.y.toInt() - 75, 150, 30)
			}
			
			// Make the cropped screenshot grayscale.
			Utils.bitmapToMat(croppedBitmap2, cvImage)
			Imgproc.cvtColor(cvImage, cvImage, Imgproc.COLOR_BGR2GRAY)
			
			// Save the cropped image before converting it to black and white in order to troubleshoot issues related to differing device sizes and cropping.
			if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugExtraRaceFans.png", cvImage)
			
			val resultBitmap = BitmapFactory.decodeFile("$matchFilePath/debugExtraRaceFans.png")
			tessBaseAPI.setImage(resultBitmap)
			
			// Set the Page Segmentation Mode to '--psm 7' or "Treat the image as a single text line" according to https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html#page-segmentation-method
			tessBaseAPI.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
			
			var result = "empty!"
			try {
				// Finally, detect text on the cropped region.
				result = tessBaseAPI.utF8Text
			} catch (e: Exception) {
				game.printToLog("[ERROR] Cannot perform OCR: ${e.stackTraceToString()}", tag = tag, isError = true)
			}
			
			tessBaseAPI.clear()
			
			// Format the string to be converted to an integer.
			Log.d(tag, "Detected number of fans before formatting: $result")
			result = result
				.replace(",", "")
				.replace(".", "")
				.replace("+", "")
				.replace("-", "")
				.replace(">", "")
				.replace("<", "")
				.replace("(", "")
				.replace("人", "")
				.replace("ォ", "")
				.replace("fans", "").trim()
			
			try {
				Log.d(tag, "Converting $result to integer for fans")
				result.toInt()
			} catch (e: NumberFormatException) {
				-1
			}
		} else {
			Log.d(tag, "This race has no double prediction.")
			return -1
		}
	}
	
	/**
	 * Convert absolute coordinates on 1080p to relative coordinates on different resolutions.
	 *
	 * @param old The old absolute coordinate based off of the 1080p resolution.
	 * @return The new relative coordinate based off of the current resolution.
	 */
	fun rel(old: Int): Int {
		return if (isDefault) {
			old
		} else {
			(old.toDouble() * (displayWidth.toDouble() / 1080.0)).toInt()
		}
	}
	
	/**
	 * Determine the number of skill points.
	 *
	 * @return Number of skill points or -1 if not found.
	 */
	fun determineSkillPoints(): Int {
		val (skillPointLocation, sourceBitmap) = findImage("skill_points")
		
		return if (skillPointLocation != null) {
			val croppedBitmap = if (isTablet) {
				Bitmap.createBitmap(sourceBitmap!!, skillPointLocation.x.toInt() - 75, skillPointLocation.y.toInt() + 45, 150, 70)
			} else {
				val new = Pair(skillPointLocation.x.toInt() - rel(70), skillPointLocation.y.toInt() + rel(28))
				Bitmap.createBitmap(sourceBitmap!!, new.first, new.second, rel(135), rel(70))
			}
			
			val cvImage = Mat()
			Utils.bitmapToMat(croppedBitmap, cvImage)
			Imgproc.cvtColor(cvImage, cvImage, Imgproc.COLOR_BGR2GRAY)
			if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugSkillPoints.png", cvImage)
			
			tessBaseAPI.setImage(croppedBitmap)
			
			// Set the Page Segmentation Mode to '--psm 7' or "Treat the image as a single text line" according to https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html#page-segmentation-method
			tessBaseAPI.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
			
			var result = "empty!"
			try {
				// Finally, detect text on the cropped region.
				result = tessBaseAPI.utF8Text
			} catch (e: Exception) {
				game.printToLog("[ERROR] Cannot perform OCR: ${e.stackTraceToString()}", tag = tag, isError = true)
			}
			
			tessBaseAPI.clear()
			
			try {
				Log.d(tag, "Converting $result to integer for skill points")
				result.toInt()
			} catch (e: NumberFormatException) {
				-1
			}
		} else {
			game.printToLog("[ERROR] Could not start the process of detecting skill points.", tag = tag, isError = true)
			-1
		}
	}
	
	/**
	 * Initialize Tesseract for future OCR operations. Make sure to put your .traineddata inside the root of the /assets/ folder.
	 */
	private fun initTesseract() {
		val externalFilesDir: File? = myContext.getExternalFilesDir(null)
		val tempDirectory: String = externalFilesDir?.absolutePath + "/tesseract/tessdata/"
		val newTempDirectory = File(tempDirectory)
		
		// If the /files/temp/ folder does not exist, create it.
		if (!newTempDirectory.exists()) {
			val successfullyCreated: Boolean = newTempDirectory.mkdirs()
			
			// If the folder was not able to be created for some reason, log the error and stop the MediaProjection Service.
			if (!successfullyCreated) {
				game.printToLog("[ERROR] Failed to create the /files/tesseract/tessdata/ folder.", tag = tag, isError = true)
			} else {
				game.printToLog("[INFO] Successfully created /files/tesseract/tessdata/ folder.", tag = tag)
			}
		} else {
			game.printToLog("[INFO] /files/tesseract/tessdata/ folder already exists.", tag = tag)
		}
		
		// If the traineddata is not in the application folder, copy it there from assets.
		tesseractLanguages.forEach { lang ->
			val trainedDataPath = File(tempDirectory, "$lang.traineddata")
			if (!trainedDataPath.exists()) {
				try {
					game.printToLog("[INFO] Starting Tesseract initialization.", tag = tag)
					val input = myContext.assets.open("$lang.traineddata")
					
					val output = FileOutputStream("$tempDirectory/$lang.traineddata")
					
					val buffer = ByteArray(1024)
					var read: Int
					while (input.read(buffer).also { read = it } != -1) {
						output.write(buffer, 0, read)
					}
					
					input.close()
					output.flush()
					output.close()
					game.printToLog("[INFO] Finished Tesseract initialization.", tag = tag)
				} catch (e: IOException) {
					game.printToLog("[ERROR] IO EXCEPTION: ${e.stackTraceToString()}", tag = tag, isError = true)
				}
			}
		}
	}
}