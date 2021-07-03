package com.steve1316.uma_android_automation.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.googlecode.tesseract.android.TessBaseAPI
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.ui.settings.SettingsFragment
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Integer.max


/**
 * Utility functions for image processing via CV like OpenCV.
 */
class ImageUtils(context: Context, private val game: Game) {
	private val TAG: String = "[${MainActivity.loggerTag}]ImageUtils"
	private var myContext = context
	
	// Initialize Google's ML OCR.
	private val textRecognizer = TextRecognition.getClient()
	
	private val matchMethod: Int = Imgproc.TM_CCOEFF_NORMED
	
	private val tessBaseAPI: TessBaseAPI
	
	private var count = 0
	
	companion object {
		private var matchFilePath: String = ""
		private lateinit var matchLocation: Point
		
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
		
		// Initialize Tesseract with the jpn.traineddata model.
		initTesseract()
		tessBaseAPI = TessBaseAPI()
		
		// Start up Tesseract.
		tessBaseAPI.init(myContext.getExternalFilesDir(null)?.absolutePath + "/tesseract/", "jpn")
		game.printToLog("[INFO] JPN Training file loaded.\n", tag = TAG)
	}
	
	/**
	 * Match between the source Bitmap from /files/temp/ and the template Bitmap from the assets folder.
	 *
	 * @param sourceBitmap Bitmap from the /files/temp/ folder.
	 * @param templateBitmap Bitmap from the assets folder.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @param useCannyAlgorithm Check whether or not to use Canny edge detection algorithm. Defaults to false.
	 * @return True if a match was found. False otherwise.
	 */
	private fun match(sourceBitmap: Bitmap, templateBitmap: Bitmap, region: IntArray = intArrayOf(0, 0, 0, 0), useCannyAlgorithm: Boolean = false): Boolean {
		// If a custom region was specified, crop the source screenshot.
		val srcBitmap = if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
			Bitmap.createBitmap(sourceBitmap, region[0], region[1], region[2], region[3])
		} else {
			sourceBitmap
		}
		
		// Create the Mats of both source and template images.
		val sourceMat = Mat()
		val templateMat = Mat()
		Utils.bitmapToMat(srcBitmap, sourceMat)
		Utils.bitmapToMat(templateBitmap, templateMat)
		
		// Make the Mats grayscale for the source and the template.
		Imgproc.cvtColor(sourceMat, sourceMat, Imgproc.COLOR_BGR2GRAY)
		Imgproc.cvtColor(templateMat, templateMat, Imgproc.COLOR_BGR2GRAY)
		
		if (useCannyAlgorithm) {
			// Blur the source and template.
			Imgproc.blur(sourceMat, sourceMat, Size(3.0, 3.0))
			Imgproc.blur(templateMat, templateMat, Size(3.0, 3.0))
			
			// Apply Canny edge detection algorithm in both source and template. Generally recommended for threshold2 to be 3 times threshold1.
			Imgproc.Canny(sourceMat, sourceMat, 100.0, 300.0)
			Imgproc.Canny(templateMat, templateMat, 100.0, 300.0)
		}
		
		// Create the result matrix.
		val resultColumns: Int = sourceMat.cols() - templateMat.cols() + 1
		val resultRows: Int = sourceMat.rows() - templateMat.rows() + 1
		val resultMat = Mat(resultRows, resultColumns, CvType.CV_32FC1)
		
		// Now perform the matching and localize the result.
		Imgproc.matchTemplate(sourceMat, templateMat, resultMat, matchMethod)
		val mmr: Core.MinMaxLocResult = Core.minMaxLoc(resultMat)
		
		matchLocation = Point()
		var matchCheck = false
		
		// Depending on which matching method was used, the algorithms determine which location was the best.
		if ((matchMethod == Imgproc.TM_SQDIFF || matchMethod == Imgproc.TM_SQDIFF_NORMED) && mmr.minVal <= 0.2) {
			matchLocation = mmr.minLoc
			matchCheck = true
		} else if ((matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED) && mmr.maxVal >= 0.8) {
			matchLocation = mmr.maxLoc
			matchCheck = true
		}
		
		if (matchCheck) {
			// Draw a rectangle around the supposed best matching location and then save the match into a file in /files/temp/ directory. This is for
			// debugging purposes to see if this algorithm found the match accurately or not.
			if (matchFilePath != "") {
				Imgproc.rectangle(sourceMat, matchLocation, Point(matchLocation.x + templateMat.cols(), matchLocation.y + templateMat.rows()), Scalar(0.0, 128.0, 0.0), 5)
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
		} else {
			return false
		}
	}
	
	/**
	 * Open the source and template image files and return Bitmaps for them.
	 *
	 * @param templateName File name of the template image.
	 * @param templateFolderName Name of the subfolder in /assets/ that the template image is in.
	 * @return A Pair of source and template Bitmaps.
	 */
	private fun getBitmaps(templateName: String, templateFolderName: String): Pair<Bitmap?, Bitmap?> {
		var sourceBitmap: Bitmap? = null
		
		while (sourceBitmap == null) {
			sourceBitmap = MediaProjectionService.takeScreenshotNow()
			
			if (sourceBitmap == null) {
				game.gestureUtils.swipe(500f, 1000f, 500f, 900f, 100L)
				game.gestureUtils.swipe(500f, 900f, 500f, 1000f, 100L)
				game.wait(0.5)
			}
		}
		
		var templateBitmap: Bitmap?
		
		// Get the Bitmap from the template image file inside the specified folder.
		myContext.assets?.open("$templateFolderName/$templateName.webp").use { inputStream ->
			// Get the Bitmap from the template image file and then start matching.
			templateBitmap = BitmapFactory.decodeStream(inputStream)
		}
		
		return if (templateBitmap != null) {
			Pair(sourceBitmap, templateBitmap)
		} else {
			game.printToLog("[ERROR] One or more of the Bitmaps are null.", tag = TAG, isError = true)
			
			Pair(sourceBitmap, templateBitmap)
		}
	}
	
	/**
	 * Finds the location of the specified image.
	 *
	 * @param templateName File name of the template image.
	 * @param tries Number of tries before failing. Defaults to 3.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @param suppressError Whether or not to suppress saving error messages to the log. Defaults to false.
	 * @return Pair object consisting of the Point object containing the location of the match and the source screenshot.
	 */
	fun findImage(templateName: String, tries: Int = 3, region: IntArray = intArrayOf(0, 0, 0, 0), suppressError: Boolean = false): Pair<Point?, Bitmap?> {
		val folderName = "images"
		var numberOfTries = tries
		
		while (numberOfTries > 0) {
			val (sourceBitmap, templateBitmap) = getBitmaps(templateName, folderName)
			
			if (sourceBitmap != null && templateBitmap != null) {
				val resultFlag: Boolean = match(sourceBitmap, templateBitmap, region)
				if (!resultFlag) {
					numberOfTries -= 1
					if (numberOfTries <= 0) {
						if (!suppressError) {
							game.printToLog("[WARNING] Failed to find the ${templateName.uppercase()} button.", tag = TAG)
						}
						
						return Pair(null, null)
					}
					
					Log.d(TAG, "Failed to find the ${templateName.uppercase()} button. Trying again...")
					game.wait(1.0)
				} else {
					game.printToLog("[SUCCESS] Found the ${templateName.uppercase()} at $matchLocation.", tag = TAG)
					return Pair(matchLocation, sourceBitmap)
				}
			}
		}
		
		return Pair(null, null)
	}
	
	/**
	 * Confirms whether or not the bot is at the specified location.
	 *
	 * @param templateName File name of the template image.
	 * @param tries Number of tries before failing. Defaults to 3.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @param suppressError Whether or not to suppress saving error messages to the log.
	 * @return True if the current location is at the specified location. False otherwise.
	 */
	fun confirmLocation(templateName: String, tries: Int = 3, region: IntArray = intArrayOf(0, 0, 0, 0), suppressError: Boolean = false): Boolean {
		val folderName = "images"
		var numberOfTries = tries
		while (numberOfTries > 0) {
			val (sourceBitmap, templateBitmap) = getBitmaps(templateName + "_header", folderName)
			
			if (sourceBitmap != null && templateBitmap != null) {
				val resultFlag: Boolean = match(sourceBitmap, templateBitmap, region)
				if (!resultFlag) {
					numberOfTries -= 1
					if (numberOfTries <= 0) {
						break
					}
					
					game.wait(1.0)
				} else {
					game.printToLog("[SUCCESS] Current location confirmed to be at ${templateName.uppercase()}.", tag = TAG)
					return true
				}
			} else {
				break
			}
		}
		
		if (!suppressError) {
			game.printToLog("[WARNING] Failed to confirm the bot location at ${templateName.uppercase()}.", tag = TAG)
		}
		
		return false
	}
	
	/**
	 * Waits for the specified image to vanish from the screen.
	 *
	 * @param templateName File name of the template image.
	 * @param timeout Amount of time to wait before timing out. Default is 5 seconds.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @param suppressError Whether or not to suppress saving error messages to the log.
	 * @return True if the specified image vanished from the screen. False otherwise.
	 */
	fun waitVanish(templateName: String, timeout: Int = 5, region: IntArray = intArrayOf(0, 0, 0, 0), suppressError: Boolean = false): Boolean {
		game.printToLog("[INFO] Now waiting for $templateName to vanish from the screen...", tag = TAG)
		
		var remaining = timeout
		if (findImage(templateName, tries = 1, region = region, suppressError = suppressError).first == null) {
			return true
		} else {
			while (findImage(templateName, tries = 1, region = region, suppressError = suppressError).first == null) {
				game.wait(1.0)
				remaining -= 1
				if (remaining <= 0) {
					return false
				}
			}
			
			return true
		}
	}
	
	/**
	 * Perform OCR text detection using Tesseract along with some image manipulation via thresholding to make the cropped screenshot black and white using OpenCV.
	 *
	 * @param increment Increments the threshold by this value. Defaults to 0.0.
	 * @param isEvent Controls cropping behavior based on whether the screen has a training event on it or not. Defaults to true.
	 * @param region Region of (x, y, width, height) to start cropping. Only works if isEvent is false. Defaults to (0, 0, 0, 0) which would be fullscreen.
	 * @return The detected String in the cropped region.
	 */
	fun findText(increment: Double = 0.0, isEvent: Boolean = true, region: IntArray = intArrayOf(0, 0, 0, 0)): String {
		if (isEvent) {
			val (sourceBitmap, templateBitmap) = getBitmaps("shift", "images")
			
			// Acquire the location of the energy text image.
			val (_, energyTemplateBitmap) = getBitmaps("energy", "images")
			match(sourceBitmap!!, energyTemplateBitmap!!)
			
			// Acquire the (x, y) coordinates of the event title container right below the location of the energy text image.
			val newX: Int = max(0, matchLocation.x.toInt() - 125)
			val newY: Int = max(0, matchLocation.y.toInt() + 116)
			var croppedBitmap: Bitmap = Bitmap.createBitmap(sourceBitmap, newX, newY, 645, 65)
			
			// Now see if it is necessary to shift the cropped region over by 70 pixels or not to account for certain events.
			croppedBitmap = if (match(croppedBitmap, templateBitmap!!)) {
				Log.d(TAG, "Shifting the region over by 70 pixels!")
				Bitmap.createBitmap(sourceBitmap, newX + 70, newY, 645 - 70, 65)
			} else {
				Log.d(TAG, "Do not need to shift.")
				croppedBitmap
			}
			
			// Make the cropped screenshot grayscale.
			val cvImage = Mat()
			Utils.bitmapToMat(croppedBitmap, cvImage)
			Imgproc.cvtColor(cvImage, cvImage, Imgproc.COLOR_BGR2GRAY)
			
			// Save the cropped image before converting it to black and white in order to troubleshoot issues related to differing device sizes and cropping.
			Imgcodecs.imwrite("$matchFilePath/pre-RESULT.png", cvImage)
			
			// Thresh the grayscale cropped image to make black and white.
			val bwImage = Mat()
			val threshold = SettingsFragment.getIntSharedPreference(myContext, "threshold")
			Imgproc.threshold(cvImage, bwImage, threshold.toDouble() + increment, 255.0, Imgproc.THRESH_BINARY)
			Imgcodecs.imwrite("$matchFilePath/RESULT.png", bwImage)
			
			game.printToLog("[INFO] Saved result image successfully named RESULT.png to internal storage inside the /files/temp/ folder.", tag = TAG)
			
			val resultBitmap = BitmapFactory.decodeFile("$matchFilePath/RESULT.png")
			tessBaseAPI.setImage(resultBitmap)
			
			// Set the Page Segmentation Mode to '--psm 7' or "Treat the image as a single text line" according to https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html#page-segmentation-method
			tessBaseAPI.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
			
			var result = "empty!"
			try {
				// Finally, detect text on the cropped region.
				result = tessBaseAPI.utF8Text
			} catch (e: Exception) {
				game.printToLog("[ERROR] Cannot perform OCR: ${e.stackTraceToString()}", tag = TAG, isError = true)
			}
			
			tessBaseAPI.clear()
			
			return result
		} else {
			val (sourceBitmap, _) = getBitmaps("shift", "images")

			// Crop the source screenshot to the custom region.
			val croppedBitmap: Bitmap = Bitmap.createBitmap(sourceBitmap!!, region[0], region[1], region[2], region[3])
			
			// Make the cropped screenshot grayscale.
			val cvImage = Mat()
			Utils.bitmapToMat(croppedBitmap, cvImage)
			Imgproc.cvtColor(cvImage, cvImage, Imgproc.COLOR_BGR2GRAY)
			
			// Save the cropped image before converting it to black and white in order to troubleshoot issues related to differing device sizes and cropping.
			Imgcodecs.imwrite("$matchFilePath/pre-RESULT.png", cvImage)
			
			// Thresh the grayscale cropped image to make black and white.
			val bwImage = Mat()
			val threshold = SettingsFragment.getIntSharedPreference(myContext, "threshold")
			Imgproc.threshold(cvImage, bwImage, threshold.toDouble() + increment, 255.0, Imgproc.THRESH_BINARY)
			Imgcodecs.imwrite("$matchFilePath/RESULT.png", bwImage)
			
			game.printToLog("[INFO] Saved result image successfully named RESULT.png to internal storage inside the /files/temp/ folder.", tag = TAG)
			
			val resultBitmap = BitmapFactory.decodeFile("$matchFilePath/RESULT.png")
			tessBaseAPI.setImage(resultBitmap)
			
			// Set the Page Segmentation Mode to '--psm 7' or "Treat the image as a single text line" according to https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html#page-segmentation-method
			tessBaseAPI.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
			
			var result = "empty!"
			try {
				// Finally, detect text on the cropped region.
				result = tessBaseAPI.utF8Text
			} catch (e: Exception) {
				game.printToLog("[ERROR] Cannot perform OCR: ${e.stackTraceToString()}", tag = TAG, isError = true)
			}
			
			tessBaseAPI.clear()
			
			return result
		}
	}
	
	/**
	 * Find the success percentage chance on the currently selected stat.
	 *
	 * @return Integer representing the percentage.
	 */
	fun findStatPercentage(): Int {
		// Crop the source screenshot to hold the success percentage only.
		val (trainingSelectionLocation, sourceBitmap) = findImage("training_selection")
		val croppedBitmap = Bitmap.createBitmap(sourceBitmap, trainingSelectionLocation!!.x.toInt(), trainingSelectionLocation.y.toInt() - 324, 100, 50)
		
		// Create a InputImage object for Google's ML OCR.
		val inputImage = InputImage.fromBitmap(croppedBitmap, 0)
		
		// Start the asynchronous operation of text detection.
		var result = 0
		textRecognizer.process(inputImage).addOnSuccessListener {
			if (it.textBlocks.size != 0) {
				for (block in it.textBlocks) {
					try {
						result = block.text.replace("%", "").trim().toInt()
					} catch (e: NumberFormatException) {
					}
				}
			}
		}.addOnFailureListener {
			game.printToLog("[ERROR] Failed to do text detection via Google's ML Kit on Bitmap.", tag = TAG, isError = true)
		}
		
		// Wait a little bit for the asynchronous operations of Google's OCR to finish. Since the cropped region is really small, the asynchronous operations should be really fast.
		game.wait(0.1)
		
		return result
	}
	
	fun findStatIncreases(currentStat: String): Int {
		val test = mutableListOf<Int>()
		
		val (speedStatTextLocation, sourceBitmap) = findImage("speed_stat")
		
		val statsToCheck = when (currentStat) {
			"speed" -> {
				arrayListOf("speed", "power")
			}
			"stamina" -> {
				arrayListOf("stamina", "guts")
			}
			"power" -> {
				arrayListOf("stamina", "power")
			}
			"guts" -> {
				arrayListOf("speed", "power", "guts")
			}
			"intelligence" -> {
				arrayListOf("speed", "intelligence")
			}
			else -> {
				arrayListOf("speed", "stamina", "power", "guts", "intelligence")
			}
		}
		
		var tries = 2
		while (tries > 0) {
			statsToCheck.forEach { stat ->
				val xOffset: Int = when (stat) {
					"speed" -> {
						-65
					}
					"stamina" -> {
						107
					}
					"power" -> {
						271
					}
					"guts" -> {
						443
					}
					"intelligence" -> {
						615
					}
					else -> {
						0
					}
				}
				
				// Crop the region.
				val croppedBitmap: Bitmap = Bitmap.createBitmap(sourceBitmap, speedStatTextLocation!!.x.toInt() + xOffset, speedStatTextLocation.y.toInt() - 92, 130, 65)
				
				// Make the cropped screenshot grayscale.
				val cvImage = Mat()
				Utils.bitmapToMat(croppedBitmap, cvImage)
				Imgproc.cvtColor(cvImage, cvImage, Imgproc.COLOR_BGR2GRAY)
				
				// Blur the cropped region.
				Imgproc.medianBlur(cvImage, cvImage, 3)
				
				// Threshold the cropped region.
				Imgproc.threshold(cvImage, cvImage, 110.0 + (5 * tries), 200.0, Imgproc.THRESH_BINARY)
				Utils.matToBitmap(cvImage, croppedBitmap)
				
				// Create a InputImage object for Google's ML OCR.
				val inputImage = InputImage.fromBitmap(croppedBitmap, 0)
				
				// Count up all of the total stat gains for this training selection.
				textRecognizer.process(inputImage).addOnSuccessListener {
					if (it.textBlocks.size != 0) {
						for (block in it.textBlocks) {
							try {
								// Regex to eliminate characters.
								val reg = Regex("[a-zA-Z]+")
								val regexResult = reg.replace(block.text, "").replace("+", "").replace("-", "").trim()
								
								Log.d(TAG, "Detected: ${block.text}")
								Log.d(TAG, "Regex applied: $regexResult")
								
								test.add(regexResult.toInt())
							} catch (e: NumberFormatException) {
							}
						}
					}
				}.addOnFailureListener {
					game.printToLog("[ERROR] Failed to do text detection via Google's ML Kit on Bitmap.", tag = TAG, isError = true)
				}
				
				// Wait a little bit for the asynchronous operations of Google's OCR to finish. Since the cropped region is really small, the asynchronous operations should be really fast.
				game.wait(0.1)
			}
			
			tries -= 1
		}
		
		// An attempt at normalizing the result to account for inaccuracies.
		var result = test.sum() / 2
		if (test.size != 0) {
			result += test.maxOrNull()?.div(2)!!
		}
		
		return result
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
				game.printToLog("[ERROR] Failed to create the /files/tesseract/tessdata/ folder.", tag = TAG, isError = true)
			} else {
				game.printToLog("[INFO] Successfully created /files/tesseract/tessdata/ folder.", tag = TAG)
			}
		} else {
			game.printToLog("[INFO] /files/tesseract/tessdata/ folder already exists.", tag = TAG)
		}
		
		// If the jpn.traineddata is not in the application folder, copy it there from assets.
		val trainedDataPath = File(tempDirectory, "jpn.traineddata")
		if (!trainedDataPath.exists()) {
			try {
				game.printToLog("[INFO] Starting Tesseract initialization.", tag = TAG)
				val input = myContext.assets.open("jpn.traineddata")
				
				val output = FileOutputStream("$tempDirectory/jpn.traineddata")
				
				val buffer = ByteArray(1024)
				var read: Int
				while (input.read(buffer).also { read = it } != -1) {
					output.write(buffer, 0, read)
				}
				
				input.close()
				output.flush()
				output.close()
				game.printToLog("[INFO] Finished Tesseract initialization.", tag = TAG)
			} catch (e: IOException) {
				game.printToLog("[ERROR] IO EXCEPTION: ${e.stackTraceToString()}", tag = TAG, isError = true)
			}
		}
	}
}