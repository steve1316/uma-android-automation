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
import androidx.core.graphics.createBitmap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.text.replace


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
	private var confidence: Double = sharedPreferences.getInt("confidence", 80).toDouble() / 100.0
	private var customScale: Double = sharedPreferences.getInt("customScale", 100).toDouble() / 100.0
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
	private val lowerEndScales: MutableList<Double> = generateSequence(0.50) { it + 0.01 }
		.takeWhile { it <= 0.70 }
		.toMutableList()

	private val middleEndScales: MutableList<Double> = generateSequence(0.50) { it + 0.01 }
		.takeWhile { it <= 3.00 }
		.toMutableList()

	private val tabletSplitPortraitScales: MutableList<Double> = generateSequence(0.50) { it + 0.01 }
		.takeWhile { it <= 1.00 }
		.toMutableList()

	private val tabletSplitLandscapeScales: MutableList<Double> = generateSequence(0.50) { it + 0.01 }
		.takeWhile { it <= 1.00 }
		.toMutableList()

	private val tabletPortraitScales: MutableList<Double> = generateSequence(1.00) { it + 0.01 }
		.takeWhile { it <= 2.00 }
		.toMutableList()

	// TODO: Separate tablet landscape scale to non-splitscreen and splitscreen scales.

	
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

	data class ScaleConfidenceResult(
		val scale: Double,
		val confidence: Double
	)

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////

	/**
	 * Starts a test to determine what scales are working on this device by looping through some template images.
	 *
	 * @return A mapping of template image names used to test and their lists of working scales.
	 */
	fun startTemplateMatchingTest(): MutableMap<String, MutableList<ScaleConfidenceResult>> {
		val results = mutableMapOf<String, MutableList<ScaleConfidenceResult>>(
			"energy" to mutableListOf(),
			"tazuna" to mutableListOf(),
			"skill_points" to mutableListOf()
		)

		val defaultConfidence = 0.8
		val testScaleDecimalFormat = DecimalFormat("#.##")
		val testConfidenceDecimalFormat = DecimalFormat("#.##")

		for (key in results.keys) {
			val (sourceBitmap, templateBitmap) = getBitmaps(key)

			// First, try the default values of 1.0 for scale and 0.8 for confidence.
			if (match(sourceBitmap!!, templateBitmap!!, key, useSingleScale = true, customConfidence = defaultConfidence, testScale = 1.0)) {
				game.printToLog("[TEST] Initial test for $key succeeded at the default values.", tag = tag)
				results[key]?.add(ScaleConfidenceResult(1.0, defaultConfidence))
				continue // If it works, skip to the next template.
			}

			// If not, try all scale/confidence combinations.
			val scalesToTest = mutableListOf<Double>()
			var scale = 0.5
			while (scale <= 3.0) {
				scalesToTest.add(testScaleDecimalFormat.format(scale).toDouble())
				scale += 0.1
			}

			for (testScale in scalesToTest) {
				var confidence = 0.6
				while (confidence <= 1.0) {
					val formattedConfidence = testConfidenceDecimalFormat.format(confidence).toDouble()
					if (match(sourceBitmap, templateBitmap, key, useSingleScale = true, customConfidence = formattedConfidence, testScale = testScale)) {
						game.printToLog("[TEST] Test for $key succeeded at scale $testScale and confidence $formattedConfidence.", tag = tag)
						results[key]?.add(ScaleConfidenceResult(testScale, formattedConfidence))
					}
					confidence += 0.1
				}
			}
		}

		return results
	}

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	
	/**
	 * Match between the source Bitmap from /files/temp/ and the template Bitmap from the assets folder.
	 *
	 * @param sourceBitmap Bitmap from the /files/temp/ folder.
	 * @param templateBitmap Bitmap from the assets folder.
	 * @param templateName Name of the template image to use in debugging log messages.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @param useSingleScale Whether to use only the single custom scale or to use a range based off of it.
	 * @param customConfidence Specify a custom confidence. Defaults to the confidence set in the app's settings.
	 * @param testScale Scale used by testing. Defaults to 0.0 which will fallback to the other scale conditions.
	 * @return True if a match was found. False otherwise.
	 */
	private fun match(sourceBitmap: Bitmap, templateBitmap: Bitmap, templateName: String, region: IntArray = intArrayOf(0, 0, 0, 0), useSingleScale: Boolean = false, customConfidence: Double = 0.0, testScale: Double = 0.0): Boolean {
		// If a custom region was specified, crop the source screenshot.
		val srcBitmap = if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
			// Validate region bounds to prevent IllegalArgumentException with creating a crop area that goes beyond the source Bitmap.
			val x = max(0, region[0].coerceAtMost(sourceBitmap.width))
			val y = max(0, region[1].coerceAtMost(sourceBitmap.height))
			val width = region[2].coerceAtMost(sourceBitmap.width - x)
			val height = region[3].coerceAtMost(sourceBitmap.height - y)
			
			if (width > 0 && height > 0) {
				Bitmap.createBitmap(sourceBitmap, x, y, width, height)
			} else {
				game.printToLog("[ERROR] Invalid region bounds for $templateName: region=$region, bitmap=${sourceBitmap.width}x${sourceBitmap.height}", tag = tag, isError = true)
				sourceBitmap
			}
		} else {
			sourceBitmap
		}
		
		val setConfidence: Double = if (templateName == "training_rainbow") {
			game.printToLog("[INFO] For detection of rainbow training, confidence will be forcibly set to 0.9 to avoid false positives.", tag = tag)
			0.9
		} else if (customConfidence == 0.0) {
			confidence
		} else {
			customConfidence
		}
		
		// Scale images if the device is not 1080p which is supported by default.
		val scales: MutableList<Double> = when {
			testScale != 0.0 -> {
				mutableListOf(testScale)
			}
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
                templateBitmap.scale((templateBitmap.width * newScale).toInt(), (templateBitmap.height * newScale).toInt())
			} else {
				templateBitmap
			}
			
			// Create the Mats of both source and template images.
			val sourceMat = Mat()
			val templateMat = Mat()
			Utils.bitmapToMat(srcBitmap, sourceMat)
			Utils.bitmapToMat(tmp, templateMat)
			
			// Clamp template dimensions to source dimensions if template is too large.
			val clampedTemplateMat = if (templateMat.cols() > sourceMat.cols() || templateMat.rows() > sourceMat.rows()) {
				Log.d(tag, "Image sizes for match assertion failed - sourceMat: ${sourceMat.size()}, templateMat: ${templateMat.size()}")
				// Create a new Mat with clamped dimensions.
				val clampedWidth = minOf(templateMat.cols(), sourceMat.cols())
				val clampedHeight = minOf(templateMat.rows(), sourceMat.rows())
				val clampedMat = Mat(templateMat, Rect(0, 0, clampedWidth, clampedHeight))
				clampedMat
			} else {
				templateMat
			}
			
			// Make the Mats grayscale for the source and the template.
			Imgproc.cvtColor(sourceMat, sourceMat, Imgproc.COLOR_BGR2GRAY)
			Imgproc.cvtColor(clampedTemplateMat, clampedTemplateMat, Imgproc.COLOR_BGR2GRAY)
			
			// Create the result matrix.
			val resultColumns: Int = sourceMat.cols() - clampedTemplateMat.cols() + 1
			val resultRows: Int = sourceMat.rows() - clampedTemplateMat.rows() + 1
			val resultMat = Mat(resultRows, resultColumns, CvType.CV_32FC1)
			
			// Now perform the matching and localize the result.
			Imgproc.matchTemplate(sourceMat, clampedTemplateMat, resultMat, matchMethod)
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
					game.printToLog("[DEBUG] Match found for \"$templateName\" with $minVal <= ${1.0 - setConfidence} at Point $matchLocation using scale: $newScale.", tag = tag)
				}
			} else if ((matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED) && mmr.maxVal >= setConfidence) {
				matchLocation = mmr.maxLoc
				matchCheck = true
				if (debugMode) {
					game.printToLog("[DEBUG] Match found for \"$templateName\" with $maxVal >= $setConfidence at Point $matchLocation using scale: $newScale.", tag = tag)
				}
			} else {
				if (debugMode) {
					if ((matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED)) {
						game.printToLog("[DEBUG] Match not found for \"$templateName\" with $maxVal not >= $setConfidence at Point ${mmr.maxLoc} using scale $newScale.", tag = tag)
					} else {
						game.printToLog("[DEBUG] Match not found for \"$templateName\" with $minVal not <= ${1.0 - setConfidence} at Point ${mmr.minLoc} using scale $newScale.", tag = tag)
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

			if (!BotService.isRunning) {
				throw InterruptedException()
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
			// Validate region bounds to prevent IllegalArgumentException with creating a crop area that goes beyond the source Bitmap.
			val x = max(0, region[0].coerceAtMost(sourceBitmap.width))
			val y = max(0, region[1].coerceAtMost(sourceBitmap.height))
			val width = region[2].coerceAtMost(sourceBitmap.width - x)
			val height = region[3].coerceAtMost(sourceBitmap.height - y)
			
			if (width > 0 && height > 0) {
				Bitmap.createBitmap(sourceBitmap, x, y, width, height)
			} else {
				game.printToLog("[ERROR] Invalid region bounds: region=$region, bitmap=${sourceBitmap.width}x${sourceBitmap.height}", tag = tag, isError = true)
				sourceBitmap
			}
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
			confidence
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
                templateBitmap.scale((templateBitmap.width * newScale).toInt(), (templateBitmap.height * newScale).toInt())
			} else {
				templateBitmap
			}
			
			// Create the Mats of both source and template images.
			Utils.bitmapToMat(srcBitmap, sourceMat)
			Utils.bitmapToMat(tmp, templateMat)
			
			// Clamp template dimensions to source dimensions if template is too large.
			val clampedTemplateMat = if (templateMat.cols() > sourceMat.cols() || templateMat.rows() > sourceMat.rows()) {
				Log.d(tag, "Image sizes for matchAll assertion failed - sourceMat: ${sourceMat.size()}, templateMat: ${templateMat.size()}")
				// Create a new Mat with clamped dimensions.
				val clampedWidth = minOf(templateMat.cols(), sourceMat.cols())
				val clampedHeight = minOf(templateMat.rows(), sourceMat.rows())
				val clampedMat = Mat(templateMat, Rect(0, 0, clampedWidth, clampedHeight))
				clampedMat
			} else {
				templateMat
			}
			
			// Make the Mats grayscale for the source and the template.
			Imgproc.cvtColor(sourceMat, sourceMat, Imgproc.COLOR_BGR2GRAY)
			Imgproc.cvtColor(clampedTemplateMat, clampedTemplateMat, Imgproc.COLOR_BGR2GRAY)
			
			// Create the result matrix.
			val resultColumns: Int = sourceMat.cols() - clampedTemplateMat.cols() + 1
			val resultRows: Int = sourceMat.rows() - clampedTemplateMat.rows() + 1
			if (resultColumns < 0 || resultRows < 0) {
				break
			}
			
			resultMat = Mat(resultRows, resultColumns, CvType.CV_32FC1)
			
			// Now perform the matching and localize the result.
			Imgproc.matchTemplate(sourceMat, clampedTemplateMat, resultMat, matchMethod)
			val mmr: Core.MinMaxLocResult = Core.minMaxLoc(resultMat)
			
			matchLocation = Point()
			
			// Depending on which matching method was used, the algorithms determine which location was the best.
			if ((matchMethod == Imgproc.TM_SQDIFF || matchMethod == Imgproc.TM_SQDIFF_NORMED) && mmr.minVal <= (1.0 - setConfidence)) {
				matchLocation = mmr.minLoc
				matchCheck = true
				
				// Draw a rectangle around the match on the source Mat. This will prevent false positives and infinite looping on subsequent matches.
				Imgproc.rectangle(sourceMat, matchLocation, Point(matchLocation.x + clampedTemplateMat.cols(), matchLocation.y + clampedTemplateMat.rows()), Scalar(0.0, 0.0, 0.0), 20)
				
				// Center the location coordinates and then save it.
				matchLocation.x += (clampedTemplateMat.cols() / 2)
				matchLocation.y += (clampedTemplateMat.rows() / 2)
				
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
				Imgproc.rectangle(sourceMat, matchLocation, Point(matchLocation.x + clampedTemplateMat.cols(), matchLocation.y + clampedTemplateMat.rows()), Scalar(0.0, 0.0, 0.0), 20)
				
				// Center the location coordinates and then save it.
				matchLocation.x += (clampedTemplateMat.cols() / 2)
				matchLocation.y += (clampedTemplateMat.rows() / 2)
				
				// If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
				if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
					matchLocation.x = sourceBitmap.width - (sourceBitmap.width - (region[0] + matchLocation.x))
					matchLocation.y = sourceBitmap.height - (sourceBitmap.height - (region[1] + matchLocation.y))
				}
				
				matchLocations.add(matchLocation)
			}

			if (!BotService.isRunning) {
				throw InterruptedException()
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
					game.printToLog("[DEBUG] Match All found with $minVal <= ${1.0 - setConfidence} at Point $matchLocation with scale: $newScale.", tag = tag)
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
					game.printToLog("[DEBUG] Match All found with $maxVal >= $setConfidence at Point $matchLocation with scale: $newScale.", tag = tag)
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

			if (!BotService.isRunning) {
				throw InterruptedException()
			}
		}
		
		return matchLocations
	}

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	
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
				val resultFlag: Boolean = match(sourceBitmap, templateBitmap, templateName, region)
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
				val resultFlag: Boolean = match(sourceBitmap, templateBitmap, templateName, region)
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

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////

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
		match(sourceBitmap!!, energyTemplateBitmap!!, "energy")
		
		// Use the match location acquired from finding the energy text image and acquire the (x, y) coordinates of the event title container right below the location of the energy text image.
		val newX: Int
		val newY: Int
		var croppedBitmap: Bitmap = if (isTablet) {
			newX = max(0, matchLocation.x.toInt() - relWidth(250))
			newY = max(0, matchLocation.y.toInt() + relHeight(154))
			Bitmap.createBitmap(sourceBitmap, newX, newY, relWidth(746), relHeight(85))
		} else {
			newX = max(0, matchLocation.x.toInt() - relWidth(125))
			newY = max(0, matchLocation.y.toInt() + relHeight(116))
			Bitmap.createBitmap(sourceBitmap, newX, newY, relWidth(645), relHeight(65))
		}
		
		val tempImage = Mat()
		Utils.bitmapToMat(croppedBitmap, tempImage)
		if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugEventTitleText.png", tempImage)
		
		// Now see if it is necessary to shift the cropped region over by 70 pixels or not to account for certain events.
		croppedBitmap = if (match(croppedBitmap, templateBitmap!!, "shift")) {
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
		
		// Thresh the grayscale cropped image to make it black and white.
		val bwImage = Mat()
		val threshold = sharedPreferences.getInt("threshold", 230)
		Imgproc.threshold(cvImage, bwImage, threshold.toDouble() + increment, 255.0, Imgproc.THRESH_BINARY)
		if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugEventTitleText_afterThreshold.png", bwImage)
		
		// Convert the Mat directly to Bitmap and then pass it to the text reader.
		val resultBitmap = createBitmap(bwImage.cols(), bwImage.rows())
		Utils.matToBitmap(bwImage, resultBitmap)
		tessBaseAPI.setImage(resultBitmap)
		
		// Set the Page Segmentation Mode to '--psm 7' or "Treat the image as a single text line" according to https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html#page-segmentation-method
		tessBaseAPI.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
		
		var result = "empty!"
		try {
			// Finally, detect text on the cropped region.
			result = tessBaseAPI.utF8Text
			Log.d(tag, "[DEBUG] Detected text with Tesseract: $result")
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
		if (trainingSelectionLocation == null) {
			return -1
		}
		
		val croppedBitmap: Bitmap = if (isTablet) {
			Bitmap.createBitmap(sourceBitmap!!, relX(trainingSelectionLocation.x, -65), relY(trainingSelectionLocation.y, 23), relWidth(130), relHeight(50))
		} else {
			Bitmap.createBitmap(sourceBitmap!!, relX(trainingSelectionLocation.x, -45), relY(trainingSelectionLocation.y, 15), relWidth(100), relHeight(37))
		}

		val resizedBitmap = croppedBitmap.scale(croppedBitmap.width * 2, croppedBitmap.height * 2)
		
		// Save the cropped image for debugging purposes.
		val tempMat = Mat()
		Utils.bitmapToMat(resizedBitmap, tempMat)
		Imgproc.cvtColor(tempMat, tempMat, Imgproc.COLOR_BGR2GRAY)
		if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugTrainingFailureChance_afterCrop.png", tempMat)

		// Create a InputImage object for Google's ML OCR.
		val resultBitmap = createBitmap(tempMat.cols(), tempMat.rows())
		Utils.matToBitmap(tempMat, resultBitmap)
		val inputImage: InputImage = InputImage.fromBitmap(resultBitmap, 0)
		
		// Use CountDownLatch to make the async operation synchronous.
		val latch = CountDownLatch(1)
		var result = -1
		var mlkitFailed = false
		
		textRecognizer.process(inputImage)
			.addOnSuccessListener { text ->
				if (text.textBlocks.isNotEmpty()) {
					for (block in text.textBlocks) {
						try {
							game.printToLog("[INFO] Detected Training failure chance with Google MLKit: ${block.text}", tag = tag)
							result = block.text.replace("%", "").trim().toInt()
						} catch (_: NumberFormatException) {
						}
					}
				}
				latch.countDown()
			}
			.addOnFailureListener {
				game.printToLog("[ERROR] Failed to do text detection via Google's MLKit. Falling back to Tesseract.", tag = tag, isError = true)
				mlkitFailed = true
				latch.countDown()
			}
		
		// Wait for the async operation to complete.
		try {
			latch.await(5, TimeUnit.SECONDS)
		} catch (_: InterruptedException) {
			game.printToLog("[ERROR] MLKit operation timed out", tag = tag, isError = true)
		}
		
		// Fallback to Tesseract if ML Kit failed or didn't find result.
		if (mlkitFailed || result == -1) {
			tessBaseAPI.setImage(resultBitmap)
			tessBaseAPI.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE

			try {
				val detectedText = tessBaseAPI.utF8Text.replace("%", "")
				game.printToLog("[INFO] Detected training failure chance with Tesseract: $detectedText", tag = tag)
				result = detectedText.toInt()
			} catch (_: NumberFormatException) {
				game.printToLog("[ERROR] Could not convert \"${tessBaseAPI.utF8Text.replace("%", "")}\" to integer.", tag = tag, isError = true)
				result = -1
			} catch (e: Exception) {
				game.printToLog("[ERROR] Cannot perform OCR using Tesseract: ${e.stackTraceToString()}", tag = tag, isError = true)
				result = -1
			}

			tessBaseAPI.clear()
		}

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

		// Check if this is a rainbow training.
		if (findImage("training_rainbow", tries = 1, regionBottomHalf, suppressError = true).first != null) {
			game.printToLog("[INFO] Training is detected to be a rainbow.", tag = tag)
			totalStatGain += 50
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
					Bitmap.createBitmap(sourceBitmap!!, relX(energyTextLocation.x, -(260 * 1.32).toInt()), relY(energyTextLocation.y, -(140 * 1.32).toInt()), relWidth(135), relHeight(100))
				} else {
					Bitmap.createBitmap(sourceBitmap!!, relX(energyTextLocation.x, -260), relY(energyTextLocation.y, -140), relWidth(105), relHeight(75))
				}
			} else {
				if (isTablet) {
					Bitmap.createBitmap(sourceBitmap!!, relX(energyTextLocation.x, -(246 * 1.32).toInt()), relY(energyTextLocation.y, -(96 * 1.32).toInt()), relWidth(175), relHeight(116))
				} else {
					Bitmap.createBitmap(sourceBitmap!!, relX(energyTextLocation.x, -246), relY(energyTextLocation.y, -100), relWidth(140), relHeight(95))
				}
			}
			
			val resizedBitmap = croppedBitmap.scale(croppedBitmap.width * 2, croppedBitmap.height * 2)

			// Make the cropped screenshot grayscale.
			val cvImage = Mat()
			Utils.bitmapToMat(resizedBitmap, cvImage)
			Imgproc.cvtColor(cvImage, cvImage, Imgproc.COLOR_BGR2GRAY)
			if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugDayForExtraRace_afterCrop.png", cvImage)

			// Thresh the grayscale cropped image to make it black and white.
			val bwImage = Mat()
			val threshold = sharedPreferences.getInt("threshold", 230)
			Imgproc.threshold(cvImage, bwImage, threshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)
			if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugDayForExtraRace_afterThreshold.png", bwImage)

			// Create a InputImage object for Google's ML OCR.
			val resultBitmap = createBitmap(bwImage.cols(), bwImage.rows())
			Utils.matToBitmap(bwImage, resultBitmap)
			val inputImage: InputImage = InputImage.fromBitmap(resultBitmap, 0)

			// Use CountDownLatch to make the async operation synchronous.
			val latch = CountDownLatch(1)
			var mlkitFailed = false
			
			textRecognizer.process(inputImage)
				.addOnSuccessListener { text ->
					if (text.textBlocks.isNotEmpty()) {
						for (block in text.textBlocks) {
							try {
								game.printToLog("[INFO] Detected Day Number for Extra Race with Google MLKit: ${block.text}", tag = tag)
								result = block.text.toInt()
							} catch (_: NumberFormatException) {
							}
						}
					}
					latch.countDown()
				}
				.addOnFailureListener {
					game.printToLog("[ERROR] Failed to do text detection via Google's MLKit. Falling back to Tesseract.", tag = tag, isError = true)
					mlkitFailed = true
					latch.countDown()
				}
			
			// Wait for the async operation to complete.
			try {
				latch.await(5, TimeUnit.SECONDS)
			} catch (_: InterruptedException) {
				game.printToLog("[ERROR] MLKit operation timed out", tag = tag, isError = true)
			}
			
			// Fallback to Tesseract if ML Kit failed or didn't find result.
			if (mlkitFailed || result == -1) {
				tessBaseAPI.setImage(resultBitmap)
				tessBaseAPI.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE

				try {
					val detectedText = tessBaseAPI.utF8Text.replace("%", "")
					game.printToLog("[INFO] Detected day for extra racing with Tesseract: $detectedText", tag = tag)
					val cleanedResult = detectedText.replace(Regex("[^0-9]"), "")
					result = cleanedResult.toInt()
				} catch (_: NumberFormatException) {
					game.printToLog("[ERROR] Could not convert \"${tessBaseAPI.utF8Text.replace("%", "")}\" to integer.", tag = tag, isError = true)
					result = -1
				} catch (e: Exception) {
					game.printToLog("[ERROR] Cannot perform OCR using Tesseract: ${e.stackTraceToString()}", tag = tag, isError = true)
					result = -1
				}

				tessBaseAPI.clear()
			}
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
			Bitmap.createBitmap(sourceBitmap, relX(extraRaceLocation.x, -(173 * 1.34).toInt()), relY(extraRaceLocation.y, -(106 * 1.34).toInt()), relWidth(220), relHeight(125))
		} else {
			Bitmap.createBitmap(sourceBitmap, relX(extraRaceLocation.x, -173), relY(extraRaceLocation.y, -106), relWidth(163), relHeight(96))
		}
		val cvImage = Mat()
		Utils.bitmapToMat(croppedBitmap, cvImage)
		if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugExtraRacePrediction.png", cvImage)
		
		// Determine if the extra race has double star prediction.
		var predictionCheck = false
		if (match(croppedBitmap, doubleStarPredictionBitmap, "race_extra_double_prediction")) {
			predictionCheck = true
		}
		
		return if (predictionCheck) {
			if (debugMode) game.printToLog("[DEBUG] This race has double predictions. Now checking how many fans this race gives.", tag = tag)

			// Crop the source screenshot to show only the fans.
			val croppedBitmap2 = if (isTablet) {
				Bitmap.createBitmap(sourceBitmap, relX(extraRaceLocation.x, -(625 * 1.40).toInt()), relY(extraRaceLocation.y, -(75 * 1.34).toInt()), relWidth(320), relHeight(45))
			} else {
				Bitmap.createBitmap(sourceBitmap, relX(extraRaceLocation.x, -625), relY(extraRaceLocation.y, -75), relWidth(250), relHeight(35))
			}
			
			// Make the cropped screenshot grayscale.
			Utils.bitmapToMat(croppedBitmap2, cvImage)
			Imgproc.cvtColor(cvImage, cvImage, Imgproc.COLOR_BGR2GRAY)
			if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugExtraRaceFans_afterCrop.png", cvImage)

			// Convert the Mat directly to Bitmap and then pass it to the text reader.
			var resultBitmap = createBitmap(cvImage.cols(), cvImage.rows())
			Utils.matToBitmap(cvImage, resultBitmap)

			// Thresh the grayscale cropped image to make it black and white.
			val bwImage = Mat()
			val threshold = sharedPreferences.getInt("threshold", 230)
			Imgproc.threshold(cvImage, bwImage, threshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)
			if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugExtraRaceFans_afterThreshold.png", bwImage)

			resultBitmap = createBitmap(bwImage.cols(), bwImage.rows())
			Utils.matToBitmap(bwImage, resultBitmap)
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
				val cleanedResult = result.replace(Regex("[^0-9]"), "")
			} catch (_: NumberFormatException) {
				-1
			}
		} else {
			Log.d(tag, "This race has no double prediction.")
			return -1
		}
	}

	/**
	 * Convert absolute x-coordinate on 1080p to relative coordinate on different resolutions for the width.
	 *
	 * @param oldX The old absolute x-coordinate based off of the 1080p resolution.
	 * @return The new relative x-coordinate based off of the current resolution.
	 */
	fun relWidth(oldX: Int): Int {
		return if (isDefault) {
			oldX
		} else {
			(oldX.toDouble() * (displayWidth.toDouble() / 1080.0)).toInt()
		}
	}

	/**
	 * Convert absolute y-coordinate on 1080p to relative coordinate on different resolutions for the height.
	 *
	 * @param oldY The old absolute y-coordinate based off of the 1080p resolution.
	 * @return The new relative y-coordinate based off of the current resolution.
	 */
	fun relHeight(oldY: Int): Int {
		return if (isDefault) {
			oldY
		} else {
			(oldY.toDouble() * (displayHeight.toDouble() / 2340.0)).toInt()
		}
	}

	/**
	 * Helper function to calculate the x-coordinate with relative offset.
	 *
	 * @param baseX The base x-coordinate.
	 * @param offset The offset to add/subtract from the base coordinate and to make relative to.
	 * @return The calculated relative x-coordinate.
	 */
	fun relX(baseX: Double, offset: Int): Int {
		return baseX.toInt() + relWidth(offset)
	}

	/**
	 * Helper function to calculate relative y-coordinate with relative offset.
	 *
	 * @param baseY The base y-coordinate.
	 * @param offset The offset to add/subtract from the base coordinate and to make relative to.
	 * @return The calculated relative y-coordinate.
	 */
	fun relY(baseY: Double, offset: Int): Int {
		return baseY.toInt() + relHeight(offset)
	}
	
	/**
	 * Determine the number of skill points.
	 *
	 * @return Number of skill points or -1 if not found.
	 */
	fun determineSkillPoints(): Int {
		val (skillPointLocation, sourceBitmap) = findImage("skill_points", tries = 1)
		
		return if (skillPointLocation != null) {
			val croppedBitmap = if (isTablet) {
				Bitmap.createBitmap(sourceBitmap!!, relX(skillPointLocation.x, -75), relY(skillPointLocation.y, 45), relWidth(150), relHeight(70))
			} else {
				Bitmap.createBitmap(sourceBitmap!!, relX(skillPointLocation.x, -70), relY(skillPointLocation.y, 28), relWidth(135), relHeight(70))
			}

			// Make the cropped screenshot grayscale.
			val cvImage = Mat()
			Utils.bitmapToMat(croppedBitmap, cvImage)
			Imgproc.cvtColor(cvImage, cvImage, Imgproc.COLOR_BGR2GRAY)
			if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugSkillPoints_afterCrop.png", cvImage)

			// Thresh the grayscale cropped image to make it black and white.
			val bwImage = Mat()
			val threshold = sharedPreferences.getInt("threshold", 230)
			Imgproc.threshold(cvImage, bwImage, threshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)
			if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugSkillPoints_afterThreshold.png", bwImage)

			val resultBitmap = createBitmap(bwImage.cols(), bwImage.rows())
			Utils.matToBitmap(bwImage, resultBitmap)
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
			
			try {
				Log.d(tag, "Converting $result to integer for skill points")
				val cleanedResult = result.replace(Regex("[^0-9]"), "")
				cleanedResult.toInt()
			} catch (_: NumberFormatException) {
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