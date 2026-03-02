package com.steve1316.uma_android_automation.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.steve1316.automation_library.utils.BotService
import com.steve1316.automation_library.utils.ImageUtils
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.components.Region
import com.steve1316.uma_android_automation.types.StatName
import com.steve1316.uma_android_automation.types.Aptitude
import com.steve1316.uma_android_automation.types.BoundingBox
import com.steve1316.uma_android_automation.types.SkillData
import com.steve1316.uma_android_automation.components.*
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.lang.Integer.max
import androidx.core.graphics.scale
import androidx.core.graphics.createBitmap
import com.steve1316.automation_library.data.SharedData
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.math.sqrt
import kotlin.text.replace
import java.util.concurrent.ConcurrentHashMap


/**
 * Utility functions for image processing via CV like OpenCV.
 */
class CustomImageUtils(context: Context, private val game: Game) : ImageUtils(context) {
	private val TAG: String = "[${MainActivity.loggerTag}]CustomImageUtils"

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// SQLite Settings
	private val threshold: Int = SettingsHelper.getIntSetting("ocr", "ocrThreshold")
	override var debugMode: Boolean = SettingsHelper.getBooleanSetting("debug", "enableDebugMode")
	override var confidence: Double = SettingsHelper.getStringSetting("debug", "templateMatchConfidence").toDouble()
	override var customScale: Double = SettingsHelper.getStringSetting("debug", "templateMatchCustomScale").toDouble()
    private val manualStatCap: Int = SettingsHelper.getIntSetting("training", "manualStatCap")

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////

	data class RaceDetails (
		val fans: Int,
		val hasDoublePredictions: Boolean
	)

    data class StatBlock(
        val name: String,
        val point: Point,
        val trainerName: String? = null,
    )

	data class StatGainRowConfig(
		val startX: Int,
		val startY: Int,
		val width: Int,
		val height: Int,
		val rowName: String,
		val templateSuffix: String = ""
	)

	data class BarFillResult(
        val statName: StatName,
		val fillPercent: Double,
		val filledSegments: Int,
		val dominantColor: String,
        val statBlock: StatBlock? = null,
	) {
        val isRainbow: Boolean
            get() = statBlock != null && statBlock.name == statName.name && dominantColor == "orange"

        val isTrainerSupport: Boolean
            get() = statBlock != null && statBlock.name == "trainer_support"

        val trainerName: String?
            get() = statBlock?.trainerName
    }

	data class SpiritGaugeResult(
		val numGaugesCanFill: Int,
		val numGaugesReadyToBurst: Int
	)

	data class StatGainResult(
		val statGains: Map<StatName, Int>,
		val rowValuesMap: Map<StatName, List<Int>>
	)

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////

	init {
		initTesseract("eng.traineddata")
		SharedData.templateSubfolderPathName = "images/"
	}

	/**
	 * Find all occurrences of the specified image in the images folder using a provided source bitmap. Useful for parallel processing to avoid exceeding the maxImages buffer.
	 *
	 * @param templateName File name of the template image.
	 * @param sourceBitmap The source bitmap to search in.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @param customConfidence Override the default confidence threshold for template matching. Defaults to 0.0 which uses the default confidence.
	 * @return An ArrayList of Point objects containing all the occurrences of the specified image or null if not found.
	 */
	fun findAllWithBitmap(templateName: String, sourceBitmap: Bitmap, region: IntArray = intArrayOf(0, 0, 0, 0), customConfidence: Double = 0.0): ArrayList<Point> {
		var templateBitmap: Bitmap?
		context.assets?.open("images/$templateName.png").use { inputStream ->
			templateBitmap = BitmapFactory.decodeStream(inputStream)
		}

		if (templateBitmap != null) {
			val matchLocations = matchAll(sourceBitmap, templateBitmap, region = region, customConfidence = customConfidence)
			
			// Sort the match locations by ascending x and y coordinates.
			matchLocations.sortBy { it.x }
			matchLocations.sortBy { it.y }

			if (debugMode) {
				MessageLog.d(TAG, "Found match locations for $templateName: $matchLocations.")
			} else {
				Log.d(TAG, "[DEBUG] Found match locations for $templateName: $matchLocations.")
			}

			return matchLocations
		}
		
		return arrayListOf()
	}

	/**
	 * Find a single occurrence of the specified image in the images folder using a provided source bitmap. Useful for parallel processing to avoid exceeding the maxImages buffer.
	 *
	 * @param templateName File name of the template image.
	 * @param sourceBitmap The source bitmap to search in.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @param customConfidence Set a custom confidence for the template matching. Defaults to 0.0 which will use the confidence set in the app.
     * @param suppressError Whether to suppress error logging if image is not found. Defaults to false.
	 * @return A Point object containing the location of the first occurrence, or null if not found.
	 */
	fun findImageWithBitmap(templateName: String, sourceBitmap: Bitmap, region: IntArray = intArrayOf(0, 0, 0, 0), customConfidence: Double = 0.0, suppressError: Boolean = false): Point? {
		var templateBitmap: Bitmap?
		context.assets?.open("images/$templateName.png").use { inputStream ->
			templateBitmap = BitmapFactory.decodeStream(inputStream)
		}

		if (templateBitmap != null) {
            val matchLocation = match(sourceBitmap, templateBitmap, templateName, region = region, customConfidence = customConfidence).second
            if (matchLocation == null && !suppressError) {
                if (debugMode) MessageLog.d(TAG, "Could not find $templateName in the provided source bitmap.")
                else Log.d(TAG, "[DEBUG] Could not find $templateName in the provided source bitmap.")
            }
            return matchLocation
		}
		return null
	}

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////

	/**
	 * Perform OCR text detection on the training event title using Tesseract along with some image manipulation via thresholding to make the cropped screenshot black and white using OpenCV.
	 *
	 * @param increment Increments the threshold by this value. Defaults to 0.0.
	 * @return The detected event title in the cropped region.
	 */
	fun findEventTitle(increment: Double = 0.0): String {
		val (sourceBitmap, templateBitmap) = getBitmaps("shift")

		// Acquire the location of the energy text image.
		val (_, energyTemplateBitmap) = getBitmaps("energy")
		val (_, matchLocation) = match(sourceBitmap, energyTemplateBitmap!!, "energy")
		if (matchLocation == null) {
			MessageLog.w(TAG, "Could not proceed with OCR text detection due to not being able to find the energy template on the source image.")
			return ""
		}

		// Use the match location acquired from finding the energy text image and acquire the (x, y) coordinates of the event title container right below the location of the energy text image.
		val newX: Int = max(0, matchLocation.x.toInt() - relWidth(125))
		val newY: Int = max(0, matchLocation.y.toInt() + relHeight(116))
		var croppedBitmap: Bitmap? = createSafeBitmap(sourceBitmap, newX, newY, relWidth(645), relHeight(65), "findEventTitle crop")
		if (croppedBitmap == null) {
			MessageLog.e(TAG, "Failed to create cropped bitmap for text detection")
			return ""
		}

		val tempImage = Mat()
		Utils.bitmapToMat(croppedBitmap, tempImage)
		if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugEventTitleText.png", tempImage)

		// Now see if it is necessary to shift the cropped region over by 70 pixels or not to account for certain events.
		val (shiftMatch, _) = match(croppedBitmap, templateBitmap!!, "shift")
		croppedBitmap = if (shiftMatch) {
			Log.d(TAG, "Shifting the region over by 70 pixels!")
			createSafeBitmap(sourceBitmap, relX(newX.toDouble(), 70), newY, 645 - 70, 65, "findEventTitle shifted crop") ?: croppedBitmap
		} else {
			Log.d(TAG, "Do not need to shift.")
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
		Imgproc.threshold(cvImage, bwImage, threshold.toDouble() + increment, 255.0, Imgproc.THRESH_BINARY)
		if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugEventTitleText_afterThreshold.png", bwImage)

		// Convert the Mat directly to Bitmap and then pass it to the text reader.
		val resultBitmap = createBitmap(bwImage.cols(), bwImage.rows())
		Utils.matToBitmap(bwImage, resultBitmap)
		tessBaseAPI.setImage(resultBitmap)

		var result = ""
		try {
			// Finally, detect text on the cropped region.
			result = tessBaseAPI.utF8Text
			MessageLog.i(TAG, "Detected text with Tesseract: $result")
		} catch (e: Exception) {
			MessageLog.e(TAG, "Cannot perform OCR: ${e.stackTraceToString()}")
		}

		tessBaseAPI.clear()
		tempImage.release()
		cvImage.release()
		bwImage.release()

		return result
	}

	/**
	 * Find the success percentage chance on the currently selected stat. Parameters are optional to allow for thread-safe operations.
	 *
	 * @param sourceBitmap Bitmap of the source image separately taken. Defaults to null.
	 * @param trainingSelectionLocation Point location of the template image separately taken. Defaults to null.
     * @param tries The number of retry attempts to make when detecting failure chance.
     *              Only applies when sourceBitmap and trainingSelectionLocation are NULL.
	 *
	 * @return Integer representing the percentage. On failed detection, returns -1.
	 */
	fun findTrainingFailureChance(sourceBitmap: Bitmap? = null, trainingSelectionLocation: Point? = null, tries: Int = 1): Int {
        fun detectTrainingFailureChance(sourceBitmap: Bitmap? = null, trainingSelectionLocation: Point? = null): Int {
            // Crop the source screenshot to hold the success percentage only.
            val (trainingSelectionLocation, sourceBitmap) = if (sourceBitmap == null && trainingSelectionLocation == null) {
                findImage("training_failure_chance")
            } else {
                Pair(trainingSelectionLocation, sourceBitmap)
            }

            if (trainingSelectionLocation == null) {
                return -1
            }

		// Determine crop region.
		val (offsetX, offsetY, width, height) = listOf(-45, 15, relWidth(100), relHeight(37))

            // Perform OCR with 2x scaling and no thresholding.
            val detectedText = performOCROnRegion(
                sourceBitmap!!,
                relX(trainingSelectionLocation.x, offsetX),
                relY(trainingSelectionLocation.y, offsetY),
                width,
                height,
                useThreshold = false,
                useGrayscale = true,
                scale = 2.0,
                ocrEngine = "mlkit",
                debugName = "TrainingFailureChance"
            )

            // Parse the result.
            return try {
                val cleanedResult = detectedText.replace("%", "").replace(Regex("[^0-9]"), "").trim()
                var value = cleanedResult.toInt()

                // Correct the OCR error if failure chance exceeds 100% and strip the last digit.
                if (value > 100 && cleanedResult.length > 2) {
                    val correctedResult = cleanedResult.dropLast(1)
                    val correctedValue = correctedResult.toInt()
                    Log.w(TAG, "Failure chance $value% exceeds 100%, correcting to $correctedValue%.")
                    value = correctedValue
                }

                value
            } catch (_: NumberFormatException) {
                MessageLog.e(TAG, "Could not convert \"$detectedText\" to integer for training failure chance.")
                -1
            }
        }

        val tries: Int = maxOf(1, tries)
        var result: Int = -1
        for (i in 1..tries) {
            // We only use the passed parameters on the first iteration since if
            // we have to retry, then we want a new source bitmap.
            if (i == 1) {
                result = detectTrainingFailureChance(sourceBitmap, trainingSelectionLocation)
            } else {
                result = detectTrainingFailureChance()
            }

            if (result == -1) {
                MessageLog.w(TAG, "Failed to detect training failure chance (attempt $i of $tries)")
            }
        }

        if (debugMode) {
            MessageLog.d(TAG, "Failure chance detected to be at $result%.")
        } else {
            Log.d(TAG, "Failure chance detected to be at $result%.")
        }
		return result
	}

	/**
	 * Determines the turn number of the "X turn(s) left" text at the top left corner of the screen.
	 *
	 * @return The remaining turn number.
	 */
	fun determineTurnsRemainingBeforeNextGoal(): Int {
		val (energyTextLocation, sourceBitmap) = findImage("energy", tries = 1, region = regionTopHalf)

		if (energyTextLocation != null) {
			// Determine crop region based on campaign.
			val (offsetX, offsetY, width, height) = if (game.scenario == "Unity Cup") {
				listOf(-260, -137, relWidth(100), relHeight(80))
			} else {
				listOf(-246, -100, relWidth(140), relHeight(95))
			}

			// Perform OCR with 2x scaling.
			val detectedText = performOCROnRegion(
				sourceBitmap,
				relX(energyTextLocation.x, offsetX),
				relY(energyTextLocation.y, offsetY),
				width,
				height,
				useThreshold = true,
				useGrayscale = true,
				scale = 2.0,
				ocrEngine = "tesseract_digits",
				debugName = "DayForExtraRace"
			)

			// Parse the result.
			val result = try {
				val cleanedResult = detectedText.replace(Regex("[^0-9]"), "")
				MessageLog.i(TAG, "Detected day for extra racing: $detectedText")
				cleanedResult.toInt()
			} catch (_: NumberFormatException) {
				MessageLog.e(TAG, "Could not convert \"$detectedText\" to integer for the turns remaining.")
				-1
			}

			return result
		}

		return -1
	}

	/**
	 * Extract the race name from the extra race selection screen using OCR.
	 *
	 * @param extraRaceLocation Point object of the extra race's location.
	 * @return The race name as detected by OCR, or empty string if not found.
	 */
	fun extractRaceName(extraRaceLocation: Point): String {
		try {
			val detectedText = performOCRFromReference(
				referencePoint = extraRaceLocation,
				offsetX = -455,
				offsetY = -105,
				width = relWidth(585),
				height = relHeight(45),
				useThreshold = true,
				useGrayscale = true,
				scale = 2.0,
				ocrEngine = "mlkit",
				debugName = "extractRaceName"
			)
			
			MessageLog.i(TAG, "Extracted race name: \"$detectedText\"")
			return detectedText
		} catch (e: Exception) {
			MessageLog.e(TAG, "Exception during race name extraction: ${e.message}")
			return ""
		}
	}

	/**
	 * Determines the agenda header text associated with each Load List button.
	 * Uses the relative offset between button positions and header text regions
	 * to crop and OCR the "Agenda #" text.
	 *
	 * @param sourceBitmap The source screenshot.
	 * @param loadListButtonLocations List of Point locations for each "race_load_list" button.
	 * @return A map of Point (button location) to String (agenda text like "Agenda 1").
	 */
	fun determineAgendaHeaderMappings(sourceBitmap: Bitmap, loadListButtonLocations: ArrayList<Point>): Map<Point, String> {
		val mappings = mutableMapOf<Point, String>()
		
		// Offset from button to header text (relative to 1080x2340 baseline).
		val offsetX = -830
		val offsetY = -190
		val cropWidth = relWidth(135)
		val cropHeight = relHeight(35)
		
		for ((index, buttonLocation) in loadListButtonLocations.withIndex()) {
			val headerX = relX(buttonLocation.x, offsetX)
			val headerY = relY(buttonLocation.y, offsetY)
			
			// Perform OCR on the header region.
			val detectedText = performOCROnRegion(
				sourceBitmap,
				headerX,
				headerY,
				cropWidth,
				cropHeight,
				useThreshold = true,
				useGrayscale = true,
				scale = 2.0,
				ocrEngine = "mlkit",
				debugName = "AgendaHeader${index + 1}"
			)
			
			// Clean up the detected text and remove any OCR noise characters like '|' or '!'.
			var cleanedText = detectedText.replace("|", "").replace("!", "").trim()

			// Handle OCR edge case: "Agenda I" should be "Agenda 1".
			if (cleanedText.equals("Agenda I", ignoreCase = true)) {
				cleanedText = "Agenda 1"
			}
			
			if (cleanedText.isNotEmpty()) {
				mappings[buttonLocation] = cleanedText
				if (debugMode) {
					MessageLog.d(TAG, "Agenda header #${index + 1} at button ($buttonLocation): \"$cleanedText\"")
				} else {
					Log.d(TAG, "[DEBUG] Agenda header #${index + 1} at button ($buttonLocation): \"$cleanedText\"")
				}
			}
		}
		
		return mappings
	}

	/**
	 * Determine the amount of fans that the extra race will give only if it matches the double star prediction.
	 *
	 * @param extraRaceLocation Point object of the extra race's location.
	 * @param sourceBitmap Bitmap of the source screenshot.
	 * @param doubleStarPredictionBitmap Bitmap of the double star prediction template image.
	 * @param forceRacing Flag to allow the extra race to forcibly pass double star prediction check. Defaults to false.
	 * @return Number of fans to be gained from the extra race or -1 if not found as an object.
	 */
	fun determineExtraRaceFans(extraRaceLocation: Point, sourceBitmap: Bitmap, doubleStarPredictionBitmap: Bitmap, forceRacing: Boolean = false): RaceDetails {
		// Crop the source screenshot to show only the fan amount and the predictions.
		val croppedBitmap = createSafeBitmap(sourceBitmap, relX(extraRaceLocation.x, -173), relY(extraRaceLocation.y, -106), relWidth(163), relHeight(96), "determineExtraRaceFans prediction")
		if (croppedBitmap == null) {
			MessageLog.e(TAG, "Failed to create cropped bitmap for extra race prediction detection.")
			return RaceDetails(-1, false)
		}

		val cvImage = Mat()
		Utils.bitmapToMat(croppedBitmap, cvImage)
		if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugExtraRacePrediction.png", cvImage)

		// Determine if the extra race has double star prediction.
		val (predictionCheck, _) = match(croppedBitmap, doubleStarPredictionBitmap, "race_extra_double_prediction")

		return if (forceRacing || predictionCheck) {
			if (debugMode && !forceRacing) MessageLog.d(TAG, "This race has double predictions. Now checking how many fans this race gives.")
			else if (debugMode) MessageLog.d(TAG, "Check for double predictions was skipped due to the force racing flag being enabled. Now checking how many fans this race gives.")

			// Crop the source screenshot to show only the fans.
			val croppedBitmap2 = createSafeBitmap(sourceBitmap, relX(extraRaceLocation.x, -625), relY(extraRaceLocation.y, -75), relWidth(250), relHeight(35), "determineExtraRaceFans fans")
			if (croppedBitmap2 == null) {
				MessageLog.e(TAG, "Failed to create cropped bitmap for extra race fans detection.")
				return RaceDetails(-1, predictionCheck)
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
			Imgproc.threshold(cvImage, bwImage, threshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)
			if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugExtraRaceFans_afterThreshold.png", bwImage)

			resultBitmap = createBitmap(bwImage.cols(), bwImage.rows())
			Utils.matToBitmap(bwImage, resultBitmap)
			tessDigitsBaseAPI.setImage(resultBitmap)

			var result = ""
			try {
				// Finally, detect text on the cropped region.
				result = tessDigitsBaseAPI.utF8Text
			} catch (e: Exception) {
				MessageLog.e(TAG, "Cannot perform OCR with Tesseract: ${e.stackTraceToString()}")
			}

			tessDigitsBaseAPI.clear()
			cvImage.release()
			bwImage.release()

			// Format the string to be converted to an integer.
			MessageLog.i(TAG, "Detected number of fans from Tesseract before formatting: $result")
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
				Log.d(TAG, "Converting $result to integer for fans")
				val cleanedResult = result.replace(Regex("[^0-9]"), "")
				RaceDetails(cleanedResult.toInt(), predictionCheck)
			} catch (_: NumberFormatException) {
				RaceDetails(-1, predictionCheck)
			}
		} else {
			Log.d(TAG, "This race has no double prediction.")
			return RaceDetails(-1, false)
		}
	}

	/**
	 * Determine the number of skill points. Parameters are optional to allow for thread-safe operations.
	 *
	 * @param sourceBitmap Bitmap of the source image separately taken. Defaults to null.
	 * @param skillPointsLocation Point location of the skill points template image separately taken. Defaults to null.
	 * @return Number of skill points or -1 if not found.
	 */
	fun determineSkillPoints(sourceBitmap: Bitmap? = null, skillPointsLocation: Point? = null): Int {
		val (skillPointsLocation, sourceBitmap) = if (skillPointsLocation == null) {
			findImage("skill_points", tries = 1)
        } else if (sourceBitmap == null) {
            Pair(skillPointsLocation, getSourceBitmap())
        } else {
			Pair(skillPointsLocation, sourceBitmap)
		}

        if (skillPointsLocation == null) {
            MessageLog.e(TAG, "determineSkillPoints: skillPointsLocation is NULL.")
            return -1
        }

        // Determine crop region.
        val (offsetX, offsetY, width, height) = listOf(-70, 28, relWidth(135), relHeight(70))

        // Perform OCR with thresholding.
        val detectedText = performOCROnRegion(
            sourceBitmap,
            relX(skillPointsLocation.x, offsetX),
            relY(skillPointsLocation.y, offsetY),
            width,
            height,
            useThreshold = true,
            useGrayscale = true,
            scale = 1.0,
            ocrEngine = "mlkit",
            debugName = "SkillPoints"
        )

        // Parse the result.
        Log.d(TAG, "Detected number of skill points before formatting: $detectedText")
        return try {
            Log.d(TAG, "Converting $detectedText to integer for skill points")
            val cleanedResult = detectedText.replace(Regex("[^0-9]"), "")
            cleanedResult.toInt()
        } catch (_: NumberFormatException) {
            -1
        }
	}

	/**
	 * Analyze the relationship bars on the Training screen for the currently selected training. Parameter is optional to allow for thread-safe operations.
	 *
	 * @param sourceBitmap Bitmap of the source image separately taken. Defaults to null.
	 * @param statName The stat name of the currently selected training.
	 * @param scenario The current game scenario (e.g., "URA Finale", "Unity Cup"). Used to determine which trainer supports to search for.
	 *
	 * @return A list of the results for each relationship bar.
	 */
	fun analyzeRelationshipBars(sourceBitmap: Bitmap? = null, statName: StatName, scenario: String? = null): ArrayList<BarFillResult> {
		// Take a single screenshot first to avoid buffer overflow.
		val sourceBitmap = sourceBitmap ?: getSourceBitmap()

		val latch = CountDownLatch(6)

        var allStatBlocks: MutableList<StatBlock> = mutableListOf()
        val blockMap = ConcurrentHashMap<String, ArrayList<Point>>()
        val threads = mutableListOf<Thread>()

        for (name in StatName.entries) {
            val thread = Thread {
                try {
                    blockMap[name.name] = findAllWithBitmap(
                        "stat_${name.name.lowercase()}_block",
                        sourceBitmap,
                        region=Region.topRightThird,
                    )
                } catch (_: InterruptedException) {
                } finally {
                    latch.countDown()
                }
            }.apply { isDaemon = true }
            threads.add(thread)
            thread.start()
        }

        // Unique block for trainer supports.
        val thread = Thread {
            try {
                blockMap["trainer"] = findAllWithBitmap(
                    "stat_trainer_block",
                    sourceBitmap,
                    region=Region.topRightThird,
                )
            } catch (_: InterruptedException) {
                // Gracefully handle interruption when bot is stopped.
            } finally {
                latch.countDown()
            }
		}
        threads.add(thread)
        thread.start()

		// Wait for all threads to complete.
		try {
			latch.await(10, TimeUnit.SECONDS)
		} catch (_: InterruptedException) {
			MessageLog.e(TAG, "Parallel findAll operations timed out.")
		}

        threads.forEach { it.join() }

        // Combine all results.
		for ((blockName, blocks) in blockMap) {
            blocks.forEach { block ->
                allStatBlocks.add(StatBlock(blockName, block))
            }
        }

		// Check for scenario-specific trainer supports that do NOT show up with stat_trainer_block.
		// At most one trainer support can appear per training option.
		val foundTrainerBlock = blockMap["trainer"]?.isNotEmpty() == true
		if (scenario != null) {
			// Define scenario-specific trainer support assets.
			val trainerSupportAssets: List<Triple<String, String, Boolean>> = when (scenario) {
				"URA Finale" -> listOf(
					Triple("stat_support_etsuko_otonashi", "Etsuko Otonashi", false),
					Triple("stat_support_yayoi_akikawa", "Yayoi Akikawa", false)
				)
				"Unity Cup" -> listOf(
					Triple("stat_support_etsuko_otonashi", "Etsuko Otonashi", false),
					// Riko Kashimoto can also show up as a support card support with stat_trainer_block.
					Triple("stat_support_riko_kashimoto", "Riko Kashimoto", true)
				)
				else -> emptyList()
			}

			// Filter out trainers that also show with stat_trainer_block if one was already found.
			val trainersToSearch = if (foundTrainerBlock) {
				trainerSupportAssets.filter { !it.third }
			} else {
				trainerSupportAssets
			}

			// Search for trainer supports. At most one can appear at a time for any one training option.
			for ((assetName, trainerName, _) in trainersToSearch) {
				val trainerLocation = findImageWithBitmap(assetName, sourceBitmap, region = Region.topRightThird, suppressError = true)
				if (trainerLocation != null) {
					// Store the actual center location. The processing loop will use a different offset for trainer_support.
					allStatBlocks.add(StatBlock("trainer_support", trainerLocation, trainerName))

					// Only one trainer support can appear per training option.
					break
				}
			}
		}

        // Filter out duplicates based on exact coordinate matches.
		allStatBlocks = allStatBlocks.distinctBy {
            "${it.point.x},${it.point.y}"
        }.toMutableList()

		// Sort the combined stat blocks by ascending y-coordinate.
		allStatBlocks.sortBy { it.point.y }

		// Define HSV color ranges.
		val blueLower = Scalar(10.0, 150.0, 150.0)
		val blueUpper = Scalar(25.0, 255.0, 255.0)
		val greenLower = Scalar(40.0, 150.0, 150.0)
		val greenUpper = Scalar(80.0, 255.0, 255.0)
		val orangeLower = Scalar(100.0, 150.0, 150.0)
		val orangeUpper = Scalar(130.0, 255.0, 255.0)

		val (_, maxedTemplateBitmap) = getBitmaps("stat_maxed")
		val results = arrayListOf<BarFillResult>()

		for ((index, statBlock) in allStatBlocks.withIndex()) {
			if (debugMode) MessageLog.d(TAG, "Processing stat block #${index + 1} (${statBlock.name}) at position: (${statBlock.point.x}, ${statBlock.point.y})")

			// Use different offsets based on block type.
			// Stat blocks: relationship bar is at offset (-9, 107) from detected location.
			// Trainer supports: relationship bar is at offset (-50, 55) from icon center.
			val (offsetX, offsetY) = if (statBlock.name == "trainer_support") {
				Pair(-50, 55)
			} else {
				Pair(-9, 107)
			}

			val croppedBitmap = createSafeBitmap(sourceBitmap, relX(statBlock.point.x, offsetX), relY(statBlock.point.y, offsetY), 111, 13, "analyzeRelationshipBars stat block ${index + 1}")
			if (croppedBitmap == null) {
				MessageLog.e(TAG, "Failed to create cropped bitmap for stat block #${index + 1}.")
				continue
			}

			val (isMaxed, _) = match(croppedBitmap, maxedTemplateBitmap!!, "stat_maxed")
			if (isMaxed) {
				// Skip if the relationship bar is already maxed.
				if (debugMode) {
                    MessageLog.d(TAG, "Relationship bar #${index + 1} is full.")
                }
				results.add(BarFillResult(statName, 100.0, 5, "orange", statBlock))
				continue
			}

			val barMat = Mat()
			Utils.bitmapToMat(croppedBitmap, barMat)

			// Convert to RGB and then to HSV for better color detection.
			val rgbMat = Mat()
			Imgproc.cvtColor(barMat, rgbMat, Imgproc.COLOR_BGR2RGB)
			if (debugMode) Imgcodecs.imwrite("$matchFilePath/debug_relationshipBar${index + 1}AfterRGB.png", rgbMat)
			val hsvMat = Mat()
			Imgproc.cvtColor(rgbMat, hsvMat, Imgproc.COLOR_RGB2HSV)

			val blueMask = Mat()
			val greenMask = Mat()
			val orangeMask = Mat()

			// Count the pixels for each color.
			Core.inRange(hsvMat, blueLower, blueUpper, blueMask)
			Core.inRange(hsvMat, greenLower, greenUpper, greenMask)
			Core.inRange(hsvMat, orangeLower, orangeUpper, orangeMask)
			val bluePixels = Core.countNonZero(blueMask)
			val greenPixels = Core.countNonZero(greenMask)
			val orangePixels = Core.countNonZero(orangeMask)

			// Sum the colored pixels.
			val totalColoredPixels = bluePixels + greenPixels + orangePixels
			val totalPixels = barMat.rows() * barMat.cols()

			// Estimate the fill percentage based on the total colored pixels.
			val fillPercent = if (totalPixels > 0) {
				(totalColoredPixels.toDouble() / totalPixels.toDouble()) * 100.0
			} else 0.0

			// Estimate the filled segments (each segment is about 20% of the whole bar).
			val filledSegments = (fillPercent / 20).coerceAtMost(5.0).toInt()

			// Determine dominant color, but normalize to "none" if fill is essentially 0%.
			val dominantColor = if (fillPercent < 1.0) {
				"none"
			} else when {
				orangePixels > greenPixels && orangePixels > bluePixels -> "orange"
				greenPixels > bluePixels -> "green"
				bluePixels > 0 -> "blue"
				else -> "unknown"
			}

			blueMask.release()
			greenMask.release()
			orangeMask.release()
			hsvMat.release()
			barMat.release()

			if (debugMode) {
                MessageLog.d(TAG, "Relationship bar #${index + 1} is ${decimalFormat.format(fillPercent)}% filled with $filledSegments filled segments and the dominant color is $dominantColor")
            }
			results.add(BarFillResult(statName, fillPercent, filledSegments, dominantColor, statBlock))
		}

		return results
	}

	/**
	 * Analyze the Spirit Explosion Gauges for the currently selected Unity Cup training. Parameter is optional to allow for thread-safe operations.
	 *
	 * A training can have multiple gauges with varying fill rates. This function analyzes all gauges and returns:
	 * - Number of gauges that can be filled (not at 100% yet) by executing this training.
	 * - Number of gauges that are ready to burst.
	 *
	 * @param sourceBitmap Bitmap of the source image separately taken. Defaults to null.
	 *
	 * @return A SpiritGaugeResult for the currently selected training, or null if no gauges found.
	 */
	fun analyzeSpiritExplosionGauges(sourceBitmap: Bitmap? = null): SpiritGaugeResult? {
		// Take a single screenshot first to avoid buffer overflow.
		var currentBitmap = sourceBitmap ?: getSourceBitmap()

		// Find all Spirit Training icons (there may be multiple for the currently selected training).
		var spiritTrainingIcons = findAllWithBitmap("unitycup_spirit_training", currentBitmap, region = Region.topRightThird, customConfidence = 0.90)
		
		// If no gauges detected, try one more time after a short delay just in case the icon was bouncing.
		if (spiritTrainingIcons.isEmpty()) {
			try {
				Thread.sleep(150)
			} catch (e: InterruptedException) {
				Thread.currentThread().interrupt()
				return null
			}
			
			// Take a new screenshot for the retry.
			currentBitmap = getSourceBitmap()
			
			spiritTrainingIcons = findAllWithBitmap("unitycup_spirit_training", currentBitmap, region = Region.topRightThird, customConfidence = 0.90)
			if (spiritTrainingIcons.isEmpty()) {
				return null
			}
		}

		// Find all Spirit Explosion icons to determine burst readiness.
		val spiritExplosionIcons = findAllWithBitmap("unitycup_spirit_explosion", currentBitmap, region = Region.topRightThird, customConfidence = 0.90)

		// Analyze all gauges for all spirit training icons to count how many can be filled.
		var numGaugesCanFill = 0
		for ((index, iconLocation) in spiritTrainingIcons.withIndex()) {
			// Gauge is located to the left of the icon. Analyze the gauge region.
			// The gauge is gray inside (same gray as relationship bars), no dividers.
			// We need to calculate the percentage fill: gray pixels vs other colors (white, blue, etc.).
			val gaugeStartX = relX(iconLocation.x, -175)
			val gaugeStartY = relY(iconLocation.y, 85)
			val gaugeWidth = relWidth(30)
			val gaugeHeight = relHeight(40)
            Log.d(TAG, "[DEBUG] Spirit Training icon location: (${iconLocation.x}, ${iconLocation.y}), gauge starting at ($gaugeStartX, $gaugeStartY), width: $gaugeWidth, height: $gaugeHeight")

			val gaugeBitmap = createSafeBitmap(currentBitmap, gaugeStartX, gaugeStartY, gaugeWidth, gaugeHeight, "analyzeSpiritExplosionGauges")
			if (gaugeBitmap == null) {
				continue
			}

			val gaugeMat = Mat()
			Utils.bitmapToMat(gaugeBitmap, gaugeMat)
			if (debugMode) Imgcodecs.imwrite("$matchFilePath/debug_spiritExplosionGauge${index + 1}.png", gaugeMat)

			// Convert to RGB and then to HSV for better color detection.
			val rgbMat = Mat()
			Imgproc.cvtColor(gaugeMat, rgbMat, Imgproc.COLOR_BGR2RGB)
			val hsvMat = Mat()
			Imgproc.cvtColor(rgbMat, hsvMat, Imgproc.COLOR_RGB2HSV)

			// Define gray color range (same as relationship bars gray).
			// Gray typically has low saturation and medium value.
			val grayLower = Scalar(0.0, 0.0, 50.0)
			val grayUpper = Scalar(180.0, 50.0, 200.0)

			val grayMask = Mat()
			Core.inRange(hsvMat, grayLower, grayUpper, grayMask)
			val grayPixels = Core.countNonZero(grayMask)

			val totalPixels = gaugeMat.rows() * gaugeMat.cols()
			// Gray pixels represent the unfilled portion, so filled pixels = total - gray.
			val filledPixels = totalPixels - grayPixels
			val fillPercent = if (totalPixels > 0) {
				(filledPixels.toDouble() / totalPixels.toDouble()) * 100.0
			} else {
				0.0
			}

			// Round to nearest threshold: 0%, 25%, 50%, 75%, 100%.
			val roundedFillPercent = when {
				fillPercent < 12.5 -> 0.0
				fillPercent < 37.5 -> 25.0
				fillPercent < 62.5 -> 50.0
				fillPercent < 87.5 -> 75.0
				else -> 100.0
			}

			// Count gauges that can be filled.
			if (roundedFillPercent < 100.0) {
				numGaugesCanFill++
			}

            Log.d(TAG, "[DEBUG] Spirit Explosion Gauge at (${iconLocation.x}, ${iconLocation.y}): ${decimalFormat.format(roundedFillPercent)}% filled")

			grayMask.release()
			hsvMat.release()
			rgbMat.release()
			gaugeMat.release()
		}

		return SpiritGaugeResult(numGaugesCanFill, spiritExplosionIcons.size)
	}

	/**
	 * Reads a single stat value on the Main screen. Parameters are optional to allow for thread-safe operations.
	 *
	 * @param statName The stat to read.
	 * @param sourceBitmap Bitmap of the source image separately taken. Defaults to null.
	 * @param skillPointsLocation Point location of the skill points template image separately taken. Defaults to null.
	 * @return The integer value of the stat, or -1 if not found.
	 */
	fun determineSingleStatValue(statName: StatName, sourceBitmap: Bitmap? = null, skillPointsLocation: Point? = null): Int {
		val (finalSkillPointsLocation, finalSourceBitmap) = if (sourceBitmap == null && skillPointsLocation == null) {
			findImage("skill_points")
		} else {
			Pair(skillPointsLocation, sourceBitmap)
		}

		if (finalSkillPointsLocation == null || finalSourceBitmap == null) {
			MessageLog.e(TAG, "Could not start the process of detecting stat value for $statName.")
			return -1
		}

		// Each stat is evenly spaced at 170 pixel intervals starting at offset -862.
		val index = statName.ordinal
		val offsetX = -862 + (index * 170)

		// Perform OCR with no thresholding (stats are on solid background).
		val text = performOCROnRegion(
			finalSourceBitmap,
			relX(finalSkillPointsLocation.x, offsetX),
			relY(finalSkillPointsLocation.y, 25),
			relWidth(98),
			relHeight(42),
			useThreshold = false,
			useGrayscale = true,
			scale = 1.0,
			ocrEngine = "tesseract_digits",
			debugName = "${statName}StatValue"
		)

		// Parse the text.
		Log.d(TAG, "Detected number of stats for $statName from Tesseract before formatting: $text")
		if (text.lowercase().contains("max") || text.lowercase().contains("ax")) {
			Log.d(TAG, "$statName seems to be maxed out. Setting it to ${manualStatCap}.")
			return manualStatCap
		} else {
			try {
				Log.d(TAG, "Converting $text to integer for $statName stat value")
				val cleanedText = text.replace(Regex("[^0-9]"), "")
				return cleanedText.toInt().coerceIn(0, manualStatCap)
			} catch (_: NumberFormatException) {
				return -1
			}
		}
	}

	/**
	 * Reads the 5 stat values on the Main screen. Parameters are optional to allow for thread-safe operations.
	 *
	 * @param sourceBitmap Bitmap of the source image separately taken. Defaults to null.
	 * @param skillPointsLocation Point location of the skill points template image separately taken. Defaults to null.
	 * @return The mapping of all 5 stats names to their respective integer values.
	 */
	fun determineStatValues(sourceBitmap: Bitmap? = null, skillPointsLocation: Point? = null): Map<StatName, Int> {
		val (finalSkillPointsLocation, finalSourceBitmap) = if (sourceBitmap == null && skillPointsLocation == null) {
			findImage("skill_points")
		} else {
			Pair(skillPointsLocation, sourceBitmap)
		}
    
        val result: MutableMap<StatName, Int> = mutableMapOf()

		if (finalSkillPointsLocation != null && finalSourceBitmap != null) {
			// Process all stats at once using the mapping.
			StatName.values().forEachIndexed { index, statName ->
				// Each stat is evenly spaced at 170 pixel intervals starting at offset -862.
				val offsetX = -862 + (index * 170)

				// Perform OCR with no thresholding (stats are on solid background).
				val text = performOCROnRegion(
					finalSourceBitmap,
					relX(finalSkillPointsLocation.x, offsetX),
					relY(finalSkillPointsLocation.y, 25),
					relWidth(98),
					relHeight(42),
					useThreshold = false,
					useGrayscale = true,
					scale = 1.0,
					ocrEngine = "tesseract_digits",
					debugName = "${statName}StatValue"
				)

				// Parse the text.
				Log.d(TAG, "Detected number of stats for $statName from Tesseract before formatting: $text")
				if (text.lowercase().contains("max") || text.lowercase().contains("ax")) {
					Log.d(TAG, "$statName seems to be maxed out. Setting it to ${manualStatCap}.")
					result[statName] = manualStatCap
				} else {
					try {
						Log.d(TAG, "Converting $text to integer for $statName stat value")
						val cleanedText = text.replace(Regex("[^0-9]"), "")
						result[statName] = cleanedText.toInt().coerceIn(0, manualStatCap)
					} catch (_: NumberFormatException) {
						result[statName] = -1
					}
				}
			}
		} else {
			MessageLog.e(TAG, "Could not start the process of detecting stat values.")
		}

		return result.toMap()
	}

	/**
	 * Performs OCR on the date region from either the Race List screen or the Main screen to extract the current date string.
	 *
	 * @param isOnMainScreen If true, skip the check to see if the game is at the Race List screen.
	 * @return The detected date string from the game screen, or empty string if detection fails.
	 */
	fun determineDayString(isOnMainScreen: Boolean = false): String {
		var result = ""

		// Skip this check if we know we're on the Main screen.
		if (!isOnMainScreen) {
			val (raceStatusLocation, sourceBitmap) = findImage("race_status", tries = 1)
			if (raceStatusLocation != null) {
				// Perform OCR with thresholding (date text is on solid white background).
				MessageLog.i(TAG, "Detecting date from the Race List screen.")
				result = performOCROnRegion(
					sourceBitmap,
					relX(raceStatusLocation.x, -170),
					relY(raceStatusLocation.y, 105),
					relWidth(640),
					relHeight(70),
					useThreshold = true,
					useGrayscale = true,
					scale = 1.0,
					ocrEngine = "mlkit",
					debugName = "dateString"
				)
				if (result != "") {
					MessageLog.i(TAG, "Detected date: $result")
					
					if (debugMode) {
						MessageLog.d(TAG, "Date string detected to be at \"$result\".")
					} else {
						Log.d(TAG, "Date string detected to be at \"$result\".")
					}

					return result
				}
			}
		}

		// Main screen detection path.
		val (energyLocation, sourceBitmap) = findImage("energy")
		val offsetX = if (game.scenario == "Unity Cup") {
			-40
		} else {
			-268
		}

		if (energyLocation != null) {
			// Perform OCR with no thresholding (date text is on moving background).
			MessageLog.i(TAG, "Detecting date from the Main screen.")
			result = performOCROnRegion(
				sourceBitmap,
				relX(energyLocation.x, offsetX),
				relY(energyLocation.y, -180),
				relWidth(308),
				relHeight(35),
				useThreshold = false,
				useGrayscale = true,
				scale = 1.0,
				ocrEngine = "mlkit",
				debugName = "dateString"
			)
		}

		if (result != "") {
			MessageLog.i(TAG, "Detected date: $result")
			
			if (debugMode) {
				MessageLog.d(TAG, "Date string detected to be at \"$result\".")
			} else {
				Log.d(TAG, "Date string detected to be at \"$result\".")
			}

			return result
		} else {
			MessageLog.e(TAG, "Could not start the process of detecting the date string.")
		}

		return ""
	}

	/**
	 * Determines the stat gain values from training. Parameters are optional to allow for thread-safe operations.
	 *
	 * This function uses template matching to find individual digits and the "+" symbol in the
	 * stat gain area of the training screen. It processes templates for digits 0-9 and the "+"
	 * symbol, then constructs the final integer value by analyzing the spatial arrangement
	 * of detected matches.
	 *
	 * @param trainingName Name of the currently selected training to determine which stats to read.
	 * @param sourceBitmap Bitmap of the source image separately taken. Defaults to null.
	 * @param skillPointsLocation Point location of the template image separately taken. Defaults to null.
	 *
	 * @return StatGainResult containing the stat gains array and row values map for logging.
	 */
	fun determineStatGainFromTraining(trainingName: StatName, sourceBitmap: Bitmap? = null, skillPointsLocation: Point? = null): StatGainResult {
        // Scenario-specific checks.
		val isUnityCup = game.scenario == "Unity Cup"

		// Determine all template suffixes needed for this scenario.
		val templateSuffixes = if (isUnityCup) {
			listOf("_mini", "_mini_bold")
		} else {
			listOf("")
		}
		val baseTemplates = listOf("+", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9")
		// Define a mapping of training types to their stat indices
		val trainingStatMap = mapOf(
			StatName.SPEED to listOf(StatName.SPEED, StatName.POWER),
			StatName.STAMINA to listOf(StatName.STAMINA, StatName.GUTS),
			StatName.POWER to listOf(StatName.STAMINA, StatName.POWER),
			StatName.GUTS to listOf(StatName.SPEED, StatName.POWER, StatName.GUTS),
			StatName.WIT to listOf(StatName.SPEED, StatName.WIT),
		)

		val (skillPointsLocation, sourceBitmap) = if (sourceBitmap == null && skillPointsLocation == null) {
			findImage("skill_points")
		} else {
			Pair(skillPointsLocation, sourceBitmap)
		}

		val threadSafeResults = ConcurrentHashMap<StatName, Int>()
		// Initialize all stat keys with default value 0 to ensure map completeness even if threads return early.
		StatName.entries.forEach { statName ->
			threadSafeResults[statName] = 0
		}
		// Store row values to log them sequentially after threads complete.
		val rowValuesMap = Collections.synchronizedMap(mutableMapOf<StatName, List<Int>>())

		if (skillPointsLocation != null) {
			// Pre-load all template bitmaps for all suffixes to avoid thread contention.
			val templateBitmaps = mutableMapOf<String, Bitmap?>()
			for (suffix in templateSuffixes) {
				for (baseTemplate in baseTemplates) {
					val templateName = baseTemplate + suffix
					context.assets?.open("images/$templateName.png").use { inputStream ->
						templateBitmaps[templateName] = BitmapFactory.decodeStream(inputStream)
					}
				}
			}

			// Process all stats in parallel using threads.
			val statLatch = CountDownLatch(5)
			for (statName in StatName.entries) {
				Thread {
					var sourceMat: Mat? = null
					var sourceGray: Mat? = null
					var workingMat: Mat? = null
					try {
						// Stop the Thread early if the selected Training would not offer stats for the stat to be checked.
						// Speed gives Speed and Power
						// Stamina gives Stamina and Guts
						// Power gives Stamina and Power
						// Guts gives Speed, Power and Guts
						// Wits gives Speed and Wits
                        val validStatNames: List<StatName> = trainingStatMap[statName] ?: return@Thread
                        if (statName !in validStatNames) {
                            return@Thread
                        }

						// Check if bot is still running before starting work.
						if (!BotService.isRunning) {
							return@Thread
						}

                        // All stats are evenly spaced at 180 pixel intervals.
						val xOffset = statName.ordinal * 180

						// Determine crop regions based on campaign.
						val firstRowStartX = relX(skillPointsLocation.x, -934 + xOffset)
						val firstRowStartY = if (isUnityCup) {
                            relY(skillPointsLocation.y, -65)
                        } else {
                            relY(skillPointsLocation.y, -103)
                        }

						// Declare croppedBitmap variable for debug visualization (used in URA Finale path).
						var croppedBitmap: Bitmap? = null

						// Build the row configurations based on the current scenario.
						val rows = if (isUnityCup) {
							// For Unity Cup, stats are in two rows on top of each other.
							// First row uses "_mini" suffix, second row uses "_mini_bold" suffix.
							val secondRowStartY = relY(firstRowStartY.toDouble(), -55)
							listOf(
								StatGainRowConfig(firstRowStartX, firstRowStartY, relWidth(150), relHeight(55), "row1", "_mini"),
                                StatGainRowConfig(firstRowStartX, secondRowStartY, relWidth(150), relHeight(55), "row2", "_mini_bold")
							)
						} else {
							// Default: single row.
							listOf(
								StatGainRowConfig(firstRowStartX, firstRowStartY, relWidth(150), relHeight(82), "", "")
							)
						}

						// Track all Mat objects for cleanup.
						val matObjects = mutableListOf<Mat>()
						var processingFailed = false
						// Track row information and matches for debug visualization.
						data class RowDebugInfo(val bitmap: Bitmap, val config: StatGainRowConfig, val matches: MutableMap<String, MutableList<Point>>)
						val rowDebugInfo = mutableListOf<RowDebugInfo>()

						try {
							// Process each row.
							for (row in rows) {
								if (!BotService.isRunning) {
									processingFailed = true
									break
								}

								// Create bitmap for this row.
								val rowBitmap = createSafeBitmap(sourceBitmap!!, row.startX, row.startY, row.width, row.height, "determineStatGainFromTraining $statName ${row.rowName}".trim())
								if (rowBitmap == null) {
									Log.e(TAG, "[ERROR] Failed to create cropped bitmap for $statName stat gain detection from $trainingName training ${row.rowName}.")
									threadSafeResults[statName] = 0
									processingFailed = true
									return@Thread
								}

								// Set croppedBitmap for non-Unity Cup path (used in debug visualization).
								if (!isUnityCup) {
									croppedBitmap = rowBitmap
								}

								// Initialize row-specific matches for debug visualization.
								// Use templates with the row's specific suffix.
								val rowTemplates = baseTemplates.map { it + row.templateSuffix }
								val rowMatches = mutableMapOf<String, MutableList<Point>>()
								rowTemplates.forEach { template ->
									rowMatches[template] = mutableListOf()
								}

								// Check again before expensive operations.
								if (!BotService.isRunning) {
									processingFailed = true
									return@Thread
								}

								// Convert to Mat and then turn it to grayscale.
								val rowMat = Mat()
								Utils.bitmapToMat(rowBitmap, rowMat)
								matObjects.add(rowMat)

								val rowGray = Mat()
								Imgproc.cvtColor(rowMat, rowGray, Imgproc.COLOR_BGR2GRAY)
								matObjects.add(rowGray)

								val rowWorking = Mat()
								rowGray.copyTo(rowWorking)
								matObjects.add(rowWorking)

								// Check again before starting template processing loop.
								if (!BotService.isRunning) {
									threadSafeResults[statName] = 0
									processingFailed = true
									return@Thread
								}

								// Process templates for this row using the row's specific suffix.
								for (templateName in rowTemplates) {
									// Check before each template processing operation.
									if (!BotService.isRunning) {
										processingFailed = true
										break
									}
									val templateBitmap = templateBitmaps[templateName]
									if (templateBitmap != null) {
										val processedMatches = processStatGainTemplateWithTransparency(templateName, templateBitmap, rowWorking, mutableMapOf<String, MutableList<Point>>().apply {
											rowTemplates.forEach { t -> this[t] = mutableListOf() }
										})
										// Store original matches for this row (for debug visualization).
										processedMatches[templateName]?.forEach { point ->
											rowMatches[templateName]?.add(point)
										}
									} else {
										Log.e(TAG, "[ERROR] Could not load template \"$templateName\" to process stat gains for $trainingName training.")
									}
								}

								// Store row bitmap, config, and matches for debug visualization.
								rowDebugInfo.add(RowDebugInfo(rowBitmap, row, rowMatches))
							}
						} finally {
							// Clean up all Mat objects.
							matObjects.forEach { it.release() }
						}

						if (processingFailed) {
							return@Thread
						}

						// Analyze results and construct the final integer value for this region.
						val finalValue = if (rows.size > 1) {
							// For Unity Cup with multiple rows, sum the values from each row.
							val rowValues = rowDebugInfo.mapIndexed { index, rowInfo ->
								constructIntegerFromMatches(rowInfo.matches)
							}
                            // Store row values for sequential logging after threads complete.
                            rowValuesMap[statName] = rowValues
							rowValues.sum()
						} else {
							// For single row scenarios, use the existing behavior.
							constructIntegerFromMatches(rowDebugInfo[0].matches)
						}
						threadSafeResults[statName] = finalValue

						// Draw final visualization with all matches for this region.
						if (debugMode) {
							// Save separate debug images for each row.
							for ((rowIndex, rowInfo) in rowDebugInfo.withIndex()) {
								val resultMat = Mat()
								Utils.bitmapToMat(rowInfo.bitmap, resultMat)

								// Draw matches for this row using the stored row-specific matches.
								// Use the row's template suffix to get the correct templates.
								val rowTemplates = baseTemplates.map { it + rowInfo.config.templateSuffix }
								rowTemplates.forEach { templateName ->
									rowInfo.matches[templateName]?.forEach { point ->
										val templateBitmap = templateBitmaps[templateName]
										if (templateBitmap != null) {
											val templateWidth = templateBitmap.width
											val templateHeight = templateBitmap.height

											// Calculate the bounding box coordinates.
											val x1 = (point.x - templateWidth/2).toInt()
											val y1 = (point.y - templateHeight/2).toInt()
											val x2 = (point.x + templateWidth/2).toInt()
											val y2 = (point.y + templateHeight/2).toInt()

											// Draw the bounding box.
											Imgproc.rectangle(resultMat, Point(x1.toDouble(), y1.toDouble()), Point(x2.toDouble(), y2.toDouble()), Scalar(0.0, 0.0, 0.0), 2)

											// Add text label.
											Imgproc.putText(resultMat, templateName, point, Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, Scalar(0.0, 0.0, 0.0), 1)
										}
									}
								}

								// Generate filename with row identifier if multiple rows exist.
								val rowSuffix = if (rows.size > 1) {
									if (rowInfo.config.rowName.isNotEmpty()) {
										"_${rowInfo.config.rowName}"
									} else {
										"_row${rowIndex + 1}"
									}
								} else {
									""
								}
								Imgcodecs.imwrite("$matchFilePath/debug_${trainingName}TrainingStatGain_${statName}${rowSuffix}.png", resultMat)
								resultMat.release()
							}
						}
					} catch (_: InterruptedException) {
					} catch (e: Exception) {
						Log.e(TAG, "[ERROR] Error processing stat $statName for $trainingName training: ${e.stackTraceToString()}")
						threadSafeResults[statName] = 0
					} finally {
						// Always clean up resources, even if interrupted.
						sourceMat?.release()
						sourceGray?.release()
						workingMat?.release()
						statLatch.countDown()
					}
				}.apply { isDaemon = true }.start()
			}

			// Wait for all threads to complete.
			try {
				statLatch.await(30, TimeUnit.SECONDS)
			} catch (_: InterruptedException) {
				MessageLog.e(TAG, "Stat processing timed out for $trainingName training.")
			}

			// Check if bot is still running before applying boost.
			if (!BotService.isRunning) {
				return StatGainResult(threadSafeResults.toMap(), rowValuesMap.toMap())
			}

			// Apply artificial boost to main stat gains if they appear lower than side-effect stats.
			val boostedResults = applyStatGainBoost(trainingName, threadSafeResults, trainingStatMap)
			// Return results with row values map for logging in Training.kt after threads complete.
			return StatGainResult(boostedResults, rowValuesMap.toMap())
		} else {
			MessageLog.e(TAG, "Could not find the skill points location to start determining stat gains for $trainingName training.")
		}

		return StatGainResult(threadSafeResults.toMap(), emptyMap())
	}

	/**
	 * Applies artificial boost to main stat gains when they appear lower than side-effect stats due to OCR failure.
	 * 
	 * @param trainingName Name of the training type (Speed, Stamina, Power, Guts, Wit).
	 * @param statGains Mapping of stat name to their stat gain.
	 * @param trainingToStatIndices Mapping of training types to their affected stats.
	 * @return Mapping of stat names to their gains with potential artificial boost applied to main stat.
	 */
	private fun applyStatGainBoost(trainingName: StatName, statGains: Map<StatName, Int>, trainingToAffectedStatNames: Map<StatName, List<StatName>>): Map<StatName, Int> {
		val boostedResults: MutableMap<StatName, Int> = statGains.toMutableMap()
		
		// Get the stat indices affected by this training type and filter out the main stat to get side-effects.
		val affectedStats: List<StatName> = trainingToAffectedStatNames[trainingName] ?: return boostedResults
		val sideEffectStats: List<StatName> = affectedStats.filter { it != trainingName }
        val sideEffectStatGains: Map<StatName, Int> = boostedResults.filterKeys { it in sideEffectStats }
		
		val mainStatGain = boostedResults[trainingName] ?: 0
		
		// Check if any side-effect stat has a higher gain than the main stat.
		val maxSideEffectGain: Int = sideEffectStatGains.maxOfOrNull { it.value } ?: 0
		
		if (mainStatGain in 1..<maxSideEffectGain) {
			// Set main stat to be 10 points higher than the highest side-effect stat.
			val originalGain = boostedResults[trainingName]
			boostedResults[trainingName] = maxSideEffectGain + 10
			Log.d(TAG,
				"[DEBUG] Artificially increased $trainingName stat gain from $originalGain to ${boostedResults[trainingName]} due to possible OCR failure. " +
				"Side-effect stats had higher gains: $sideEffectStats"
			)
		}

		// If the side-effect stat gains were zeroes, boost them to half of the main stat gain.
		val boostedMainStatGain = boostedResults[trainingName] ?: 0
        for (statName in sideEffectStats) {
			if ((boostedResults[statName] ?: 0) == 0 && boostedMainStatGain > 0) {
				boostedResults[statName] = boostedMainStatGain / 2
				Log.d(TAG, "[DEBUG] Artificially increased $statName side-effect stat gain to ${boostedResults[statName]} because it was 0 due to possible OCR failure. " +
						"Based on half of boosted $trainingName = $boostedMainStatGain."
				)
			}
		}
		
		return boostedResults.toMap()
	}

	/**
	 * Processes a single template with transparency to find all valid matches in the working matrix through a multi-stage algorithm.
	 *
	 * The algorithm uses two validation criteria:
	 * - Pixel match ratio: Ensures sufficient pixel-level similarity.
	 * - Correlation coefficient: Validates statistical correlation between template and matched region.
	 *
	 * @param templateName Name of the template being processed (used for logging and debugging).
	 * @param templateBitmap Bitmap of the template image (must have 4-channel RGBA format with transparency).
	 * @param workingMat Working matrix to search in (grayscale source image).
	 * @param matchResults Map to store match results, organized by template name.
	 *
	 * @return The modified matchResults mapping containing all valid matches found for this template
	 */
	private fun processStatGainTemplateWithTransparency(templateName: String, templateBitmap: Bitmap, workingMat: Mat, matchResults: MutableMap<String, MutableList<Point>>): MutableMap<String, MutableList<Point>> {
		// These values have been tested for the best results against the dynamic background.
		val matchConfidence = 0.9
		val minPixelMatchRatio = 0.1
		val minPixelCorrelation = 0.85

		// Convert template to Mat and then to grayscale.
		val templateMat = Mat()
		val templateGray = Mat()
		Utils.bitmapToMat(templateBitmap, templateMat)
		Imgproc.cvtColor(templateMat, templateGray, Imgproc.COLOR_BGR2GRAY)

		// Check if template has an alpha channel (transparency).
		if (templateMat.channels() != 4) {
			Log.e(TAG, "[ERROR] Template \"$templateName\" is not transparent and is a requirement.")
			templateMat.release()
			templateGray.release()
			return matchResults
		}

		// Extract alpha channel for the alpha mask.
		val alphaChannels = ArrayList<Mat>()
		Core.split(templateMat, alphaChannels)
		val alphaMask = alphaChannels[3] // Alpha channel is the 4th channel.

		// Create binary mask for non-transparent pixels.
		val validPixels = Mat()
		Core.compare(alphaMask, Scalar(0.0), validPixels, Core.CMP_GT)

		// Check transparency ratio.
		val nonZeroPixels = Core.countNonZero(alphaMask)
		val totalPixels = alphaMask.rows() * alphaMask.cols()
		val transparencyRatio = nonZeroPixels.toDouble() / totalPixels
		if (transparencyRatio < 0.1) {
			Log.w(TAG, "[DEBUG] Template \"$templateName\" appears to be mostly transparent!")
			alphaChannels.forEach { it.release() }
			validPixels.release()
			alphaMask.release()
			templateMat.release()
			templateGray.release()
			return matchResults
		}

		////////////////////////////////////////////////////////////////////
		////////////////////////////////////////////////////////////////////

		var continueSearching = true
		var searchMat = Mat()
		var xOffset = 0
		workingMat.copyTo(searchMat)

		try {
			while (continueSearching) {
				var failedPixelMatchRatio = false
				var failedPixelCorrelation = false

				// Template match with the alpha mask.
				val result = Mat()
				Imgproc.matchTemplate(searchMat, templateGray, result, Imgproc.TM_CCORR_NORMED, alphaMask)
				val mmr = Core.minMaxLoc(result)
				val matchVal = mmr.maxVal
				val matchLocation = mmr.maxLoc

				if (matchVal >= matchConfidence) {
					val x = matchLocation.x.toInt()
					val y = matchLocation.y.toInt()
					val h = templateGray.rows()
					val w = templateGray.cols()

					// Validate that the match location is within bounds.
					if (x >= 0 && y >= 0 && x + w <= searchMat.cols() && y + h <= searchMat.rows()) {
						// Extract the matched region from the source image.
						val matchedRegion = Mat(searchMat, Rect(x, y, w, h))

						// Create masked versions of the template and matched region using only non-transparent pixels.
						val templateValid = Mat()
						val regionValid = Mat()
						templateGray.copyTo(templateValid, validPixels)
						matchedRegion.copyTo(regionValid, validPixels)

						// For the first test, compare pixel-by-pixel equality between the matched region and template to calculate match ratio.
						val templateComparison = Mat()
						Core.compare(matchedRegion, templateGray, templateComparison, Core.CMP_EQ)
						val matchingPixels = Core.countNonZero(templateComparison)
						val pixelMatchRatio = matchingPixels.toDouble() / (w * h)
						if (pixelMatchRatio < minPixelMatchRatio) {
							failedPixelMatchRatio = true
						}

						// Extract pixel values into double arrays for correlation calculation.
						val templateValidMat = Mat()
						val regionValidMat = Mat()
						templateValid.convertTo(templateValidMat, CvType.CV_64F)
						regionValid.convertTo(regionValidMat, CvType.CV_64F)
						val templateArray = DoubleArray(templateValid.total().toInt())
						val regionArray = DoubleArray(regionValid.total().toInt())
						templateValidMat.get(0, 0, templateArray)
						regionValidMat.get(0, 0, regionArray)

						// For the second test, validate the match quality by performing correlation calculation.
						val pixelCorrelation = calculateCorrelation(templateArray, regionArray)
						if (pixelCorrelation < minPixelCorrelation) {
							failedPixelCorrelation = true
						}

						// If both tests passed, then the match is valid.
						if (!failedPixelMatchRatio && !failedPixelCorrelation) {
							val centerX = (x + xOffset) + (w / 2)
							val centerY = y + (h / 2)

							// Check for overlap with existing matches within 10 pixels on both axes.
							val hasOverlap = matchResults.values.flatten().any { existingPoint ->
								val existingX = existingPoint.x
								val existingY = existingPoint.y

								// Check if the new match overlaps with existing match within 10 pixels.
								val xOverlap = kotlin.math.abs(centerX - existingX) < 10
								val yOverlap = kotlin.math.abs(centerY - existingY) < 10

								xOverlap && yOverlap
							}

							if (!hasOverlap) {
								Log.d(TAG, "[DEBUG] Found valid match for template \"$templateName\" at ($centerX, $centerY).")
								matchResults[templateName]?.add(Point(centerX.toDouble(), centerY.toDouble()))

                                // If it found the + symbol, then there is no need to look for additional pluses.
                                if (templateName in listOf("+", "+_mini")) {
                                    continueSearching = false
                                }
							}
						}

						// Draw a box to prevent re-detection in the next loop iteration.
						Imgproc.rectangle(searchMat, Point(x.toDouble(), y.toDouble()), Point((x + w).toDouble(), (y + h).toDouble()), Scalar(0.0, 0.0, 0.0), 10)

						templateComparison.release()
						matchedRegion.release()
						templateValid.release()
						regionValid.release()
						templateValidMat.release()
						regionValidMat.release()

						// Crop the Mat horizontally to exclude the supposed matched area.
						val cropX = x + w
						val remainingWidth = searchMat.cols() - cropX
						when {
							remainingWidth < templateGray.cols() -> {
								continueSearching = false
							}
							else -> {
								val newSearchMat = Mat(searchMat, Rect(cropX, 0, remainingWidth, searchMat.rows()))
								searchMat.release()
								searchMat = newSearchMat
								xOffset += cropX
							}
						}
					} else {
						// Stop searching when the source has been traversed.
						continueSearching = false
					}
				} else {
					// No match found above threshold, stop searching for this template.
					continueSearching = false
				}

				result.release()

				// Safety check to prevent infinite loops.
				if ((matchResults[templateName]?.size ?: 0) > 10) {
					continueSearching = false
				}
				if (!BotService.isRunning) {
					throw InterruptedException()
				}
			}
		} finally {
			// Always clean up resources, even if InterruptedException is thrown.
			searchMat.release()
			alphaChannels.forEach { it.release() }
			validPixels.release()
			alphaMask.release()
			templateMat.release()
			templateGray.release()
		}

		////////////////////////////////////////////////////////////////////
		////////////////////////////////////////////////////////////////////

		return matchResults
	}

	/**
	 * Constructs the final integer value from matched template locations of numbers by analyzing spatial arrangement.
	 *
	 * The function is designed for OCR-like scenarios where individual character templates
	 * are matched separately and need to be reconstructed into a complete number.
	 *
	 * If matchResults contains: {"+" -> [(10, 20)], "1" -> [(15, 20)], "2" -> [(20, 20)]}, it returns: 12 (from string "+12").
	 *
	 * @param matchResults Map of template names (e.g., "0", "1", "2", "+") to their match locations.
	 *
	 * @return The constructed integer value or -1 if it failed.
	 */
	private fun constructIntegerFromMatches(matchResults: Map<String, MutableList<Point>>): Int {
		// Collect all matches with their template names.
		val allMatches = mutableListOf<Pair<String, Point>>()
		matchResults.forEach { (templateName, points) ->
			points.forEach { point ->
				allMatches.add(Pair(templateName, point))
			}
		}

		if (allMatches.isEmpty()) {
			Log.d(TAG, "[WARNING] No matches found to construct integer value.")
			return 0
		}

		// Sort matches by x-coordinate (left to right).
		allMatches.sortBy { it.second.x }
		Log.d(TAG, "[DEBUG] Sorted matches: ${allMatches.map { "${it.first}@(${it.second.x}, ${it.second.y})" }}")

		// Construct the string representation by extracting the character part from template names (removing suffixes like "_mini").
		// Template names can be "+", "0"-"9" or "+_mini", "0_mini"-"9_mini", so we extract the first character.
		val constructedString = allMatches.joinToString("") { it.first[0].toString() }
		Log.d(TAG, "[DEBUG] Constructed string: \"$constructedString\".")

		// Extract the numeric part and convert to integer.
		return try {
            if (constructedString == "+") {
                Log.w(TAG, "[WARNING] Constructed string was just the plus sign. Setting the result to 0.")
                return 0
            }

			val numericPart = if (constructedString.startsWith("+") && constructedString.substring(1).isNotEmpty()) {
				constructedString.substring(1)
			} else {
				constructedString
			}

			val result = numericPart.toInt()

			// Correct stat gains that exceed +100 by dropping the third digit.
			// The max stat gain per training is +100, so higher values indicate a false 3rd digit detection.
			val correctedResult = if (result > 100) {
				val corrected = result / 10
				Log.d(TAG, "[DEBUG] Corrected stat gain from $result to $corrected (dropped false 3rd digit).")
				corrected
			} else {
				result
			}

			Log.d(TAG, "[DEBUG] Successfully constructed integer value: $correctedResult from \"$constructedString\".")
			correctedResult
		} catch (e: NumberFormatException) {
			Log.e(TAG, "[ERROR] Could not convert \"$constructedString\" to integer for stat gain: ${e.stackTraceToString()}")
			0
		}
	}

	/**
	 * Calculates the Pearson correlation coefficient between two arrays of pixel values.
	 *
	 * The Pearson correlation coefficient measures the linear correlation between two variables,
	 * ranging from -1 (perfect negative correlation) to +1 (perfect positive correlation).
	 * A value of 0 indicates no linear correlation.
	 *
	 * @param array1 First array of pixel values from the template image.
	 * @param array2 Second array of pixel values from the matched region.
	 * @return Correlation coefficient between -1.0 and +1.0, or 0.0 if arrays are invalid
	 */
	private fun calculateCorrelation(array1: DoubleArray, array2: DoubleArray): Double {
		if (array1.size != array2.size || array1.isEmpty()) {
			return 0.0
		}

		val n = array1.size
		val sum1 = array1.sum()
		val sum2 = array2.sum()
		val sum1Sq = array1.sumOf { it * it }
		val sum2Sq = array2.sumOf { it * it }
		val pSum = array1.zip(array2).sumOf { it.first * it.second }

		// Calculate the numerator: n*Σ(xy) - Σx*Σy
		val num = pSum - (sum1 * sum2 / n)
		// Calculate the denominator: sqrt((n*Σx² - (Σx)²) * (n*Σy² - (Σy)²))
		val den = sqrt((sum1Sq - sum1 * sum1 / n) * (sum2Sq - sum2 * sum2 / n))

		// Return the correlation coefficient, handling division by zero.
		return if (den == 0.0) 0.0 else num / den
	}

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// Helper functions for OCR operations.

	/**
	 * Performs OCR on a cropped region of a source bitmap with optional preprocessing.
	 * 
	 * @param sourceBitmap The source image to crop from.
	 * @param x The x-coordinate of the crop region.
	 * @param y The y-coordinate of the crop region.
	 * @param width The width of the crop region.
	 * @param height The height of the crop region.
	 * @param useThreshold Whether to apply binary thresholding. Defaults to true.
	 * @param useGrayscale Whether to convert to grayscale first. Defaults to true.
	 * @param scale Scale factor to apply to the processed image. Values > 1 scale up, values < 1 scale down. Defaults to 1.0 (no scaling).
	 * @param ocrEngine The OCR engine to use ("tesseract", "mlkit", or "tesseract_digits"). Defaults to "tesseract".
	 * @param debugName Optional name for debug image saving.
	 * 
	 * @return The detected text string or empty string if OCR fails.
	 */
	fun performOCROnRegion(
		sourceBitmap: Bitmap,
		x: Int,
		y: Int,
		width: Int,
		height: Int,
		useThreshold: Boolean = true,
		useGrayscale: Boolean = true,
		scale: Double = 1.0,
		ocrEngine: String = "tesseract",
		debugName: String = ""
	): String {
		// Perform OCR using findText() from ImageUtils.
		return findText(
			cropRegion = intArrayOf(x, y, width, height),
			grayscale = useGrayscale,
			thresh = useThreshold,
			threshold = threshold.toDouble(),
			thresholdMax = 255.0,
			scale = scale,
			sourceBitmap = sourceBitmap,
			detectDigitsOnly = ocrEngine == "tesseract_digits",
            debugName = debugName
		)
	}

	/**
	 * Performs OCR on a custom region using a reference point.
	 * 
	 * @param referencePoint The point to base the crop region on.
	 * @param offsetX Offset from reference point x-coordinate.
	 * @param offsetY Offset from reference point y-coordinate.
	 * @param width Width of the crop region.
	 * @param height Height of the crop region.
	 * @param useThreshold Whether to apply binary thresholding. Defaults to true.
	 * @param useGrayscale Whether to convert to grayscale first. Defaults to true.
	 * @param scale Scale factor to apply to the processed image. Values > 1 scale up, values < 1 scale down. Defaults to 1.0 (no scaling).
	 * @param ocrEngine The OCR engine to use. Defaults to "tesseract".
	 * @param debugName Optional name for debug image saving.
	 * 
	 * @return The detected text string or empty string if OCR fails.
	 */
	fun performOCRFromReference(
		referencePoint: Point,
		offsetX: Int,
		offsetY: Int,
		width: Int,
		height: Int,
		useThreshold: Boolean = true,
		useGrayscale: Boolean = true,
		scale: Double = 1.0,
		ocrEngine: String = "tesseract",
		debugName: String = ""
	): String {
		val sourceBitmap = getSourceBitmap()
		val finalX = relX(referencePoint.x, offsetX)
		val finalY = relY(referencePoint.y, offsetY)
		
		return performOCROnRegion(
			sourceBitmap,
			finalX,
			finalY,
			width,
			height,
			useThreshold,
			useGrayscale,
			scale,
			ocrEngine,
			debugName
		)
	}

    /** Detects the number of fans from the Umamusume Class dialog.
     *
     * @param bitmap The source bitmap to analyze.
     * @return The number of fans if successful, or NULL if detection failed.
     */
    fun getUmamusumeClassDialogFanCount(bitmap: Bitmap): Int? {
        val cvImage = Mat()
		Utils.bitmapToMat(bitmap, cvImage)
        // Convert to grayscale.
        Utils.bitmapToMat(bitmap, cvImage)
        Imgproc.cvtColor(cvImage, cvImage, Imgproc.COLOR_BGR2GRAY)
        if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugGetUmamusumeClassDialogFanCount_afterCrop.png", cvImage)

        // Convert the Mat directly to Bitmap and then pass it to the text reader.
        var resultBitmap = createBitmap(cvImage.cols(), cvImage.rows())
        Utils.matToBitmap(cvImage, resultBitmap)

        // Thresh the grayscale cropped image to make it black and white.
        val bwImage = Mat()
        Imgproc.threshold(cvImage, bwImage, threshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)
        if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugGetUmamusumeClassDialogFanCount_afterThreshold.png", bwImage)

        resultBitmap = createBitmap(bwImage.cols(), bwImage.rows())
        Utils.matToBitmap(bwImage, resultBitmap)
        tessDigitsBaseAPI.setImage(resultBitmap)

        var result = "empty!"
        try {
            // Finally, detect text on the cropped region.
            result = tessDigitsBaseAPI.utF8Text
        } catch (e: Exception) {
            MessageLog.e(TAG, "Cannot perform OCR with Tesseract: ${e.stackTraceToString()}")
        }

        tessDigitsBaseAPI.clear()
        cvImage.release()
        bwImage.release()

        // Format the string to be converted to an integer.
        MessageLog.i(TAG, "Detected number of fans from Tesseract before formatting: $result")
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
            Log.d(TAG, "Converting $result to integer for fans")
            val cleanedResult = result.replace(Regex("[^0-9]"), "").toInt()
            return cleanedResult
        } catch (_: NumberFormatException) {
            return null
        }
    }

    /**
    * Gets the filled percentage of the energy bar.
    *
    * @return If energy bar is detected, returns the filled percentage, else returns null.
    */
    fun analyzeEnergyBar(): Int? {
        val (sourceBitmap, templateBitmap) = getBitmaps("energy")
        if (templateBitmap == null) {
            MessageLog.e(TAG, "[ERROR] Failed to find template bitmap for \"energy\".")
            return null
        }
        val energyTextLocation = findImage("energy", tries = 1, region = regionTopHalf).first
        if (energyTextLocation == null) {
            MessageLog.e(TAG, "[ERROR] Failed to find the text location of the energy bar.")
            return null
        }

        // Get top right of energyText.
        var x: Int = relX(energyTextLocation.x, templateBitmap.width / 2)
        var y: Int = relY(energyTextLocation.y, -(templateBitmap.height / 2))
        var w: Int = relWidth(700)
        var h: Int = relHeight(75)

        // Crop just the energy bar in the image.
        // This crop extends to the right beyond the energy bar a bit
        // since the bar is able to grow.
        var croppedBitmap = createSafeBitmap(sourceBitmap, x, y, w, h, "analyzeEnergyBar:: Crop energy bar.")
        if (croppedBitmap == null) {
            MessageLog.e(TAG, "[ERROR] Failed to crop the bitmap of the energy bar.")
            return null
        }

        // Now find the left and right brackets of the energy bar
        // to refine our cropped region.

        val energyBarLeftPartTemplateBitmap: Bitmap? = getBitmaps("energy_bar_left_part").second
        if (energyBarLeftPartTemplateBitmap == null) {
            MessageLog.e(TAG, "[ERROR] Failed to find the template bitmap for the left part of the energy bar.")
            return null
        }

        val leftPartLocation: Point? = match(croppedBitmap, energyBarLeftPartTemplateBitmap, "energy_bar_left_part").second
        if (leftPartLocation == null) {
            MessageLog.e(TAG, "[ERROR] Failed to find the location of the left part of the energy bar.")
            return null
        }

        // The right side of the energy bar looks very different depending on whether
        // the max energy has been increased. Thus we need to look for one of two bitmaps.
        var energyBarRightPartTemplateBitmap: Bitmap? = getBitmaps("energy_bar_right_part_0").second
        var rightPartLocation: Point?
        if (energyBarRightPartTemplateBitmap == null) {
            energyBarRightPartTemplateBitmap = getBitmaps("energy_bar_right_part_1").second
            if (energyBarRightPartTemplateBitmap == null) {
                MessageLog.e(TAG, "[ERROR] Failed to find the template bitmap for the right part of the energy bar.")
                return null
            }
            rightPartLocation = match(croppedBitmap, energyBarRightPartTemplateBitmap, "energy_bar_right_part_1").second
        } else {
            rightPartLocation = match(croppedBitmap, energyBarRightPartTemplateBitmap, "energy_bar_right_part_0").second
        }

        if (rightPartLocation == null) {
            MessageLog.e(TAG, "[ERROR] Failed to find the location of the right part of the energy bar.")
            return null
        }

        // Crop the energy bar further to refine the cropped region so that
        // we can measure the length of the bar.
        // This crop is just a single pixel high line at the center of the
        // bounding region.
        val left: Int = relX(leftPartLocation.x, energyBarLeftPartTemplateBitmap.width / 2)
        val right: Int = relX(rightPartLocation.x, -(energyBarRightPartTemplateBitmap.width / 2))
        x = left
        y = relHeight(croppedBitmap.height / 2)
        w = relWidth(right - left)
        h = 1

        croppedBitmap = createSafeBitmap(croppedBitmap, x, y, w, h, "analyzeEnergyBar:: Refine cropped energy bar.")
        if (croppedBitmap == null) {
            MessageLog.e(TAG, "[ERROR] Failed to refine the cropped bitmap region of the energy bar.")
            return null
        }

        // HSV color range for gray portion of energy bar.
        val grayLower = Scalar(0.0, 0.0, 116.0)
        val grayUpper = Scalar(180.0, 255.0, 118.0)
        val colorLower = Scalar(5.0, 0.0, 120.0)
        val colorUpper = Scalar(180.0, 255.0, 255.0)

        // Convert the cropped region to HSV
        val barMat = Mat()
        Utils.bitmapToMat(croppedBitmap, barMat)
        val hsvMat = Mat()
        Imgproc.cvtColor(barMat, hsvMat, Imgproc.COLOR_BGR2HSV)

        // Create masks for the gray and color portions of the image.
        val grayMask = Mat()
        val colorMask = Mat()
        Core.inRange(hsvMat, grayLower, grayUpper, grayMask)
        Core.inRange(hsvMat, colorLower, colorUpper, colorMask)

        // Calculate ratio of color and gray pixels.
        val grayPixels = Core.countNonZero(grayMask)
        val colorPixels = Core.countNonZero(colorMask)
        val totalPixels = grayPixels + colorPixels

        var fillPercent = 0.0
        if (totalPixels > 0) {
            fillPercent = (colorPixels.toDouble() / totalPixels.toDouble()) * 100.0
        }
        val result: Int = fillPercent.toInt().coerceIn(0, 100)

        barMat.release()
        hsvMat.release()
        grayMask.release()
        colorMask.release()

        Log.d(TAG, "[DEBUG] Results of energy bar analysis: Gray pixels=$grayPixels, Color pixels=$colorPixels, Energy=$result")
        return result
    }

    fun getGoalText(): String {
        val bbox = BoundingBox(
            x = relX(0.0, 365),
            y = relY(0.0, 110),
            w = relWidth(550),
            h = relHeight(40), //relHeight(90),
        )
        val sourceBitmap = getSourceBitmap()

		// Perform OCR with 2x scaling and no thresholding.
		val result = performOCROnRegion(
			sourceBitmap!!,
			bbox.x,
            bbox.y,
            bbox.w,
            bbox.h,
			useThreshold = false,
			useGrayscale = true,
			scale = 1.0,
			ocrEngine = "mlkit",
			debugName = "GoalText",
		)

		if (debugMode) {
			MessageLog.d(TAG, "getGoalText:: Detected text: $result")
		} else {
			Log.d(TAG, "getGoalText:: Detected text: $result")
		}

		return result
    }

    /** Saves a bitmap for debugging purposes.
     *
     * @param bitmap The optional bitmap to save.
     * If not specified, then a screenshot is taken and saved instead.
     * @param filename The filename for the saved bitmap.
     */
    fun saveBitmap(bitmap: Bitmap? = null, filename: String, fullRes: Boolean = false) {
        val bitmap = bitmap ?: getSourceBitmap()
        val tempImage = Mat()
        Utils.bitmapToMat(bitmap, tempImage)
        if (fullRes) {
            val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 100)
            Imgcodecs.imwrite("$matchFilePath/$filename.png", tempImage, params)
            params.release()
        } else {
            Imgcodecs.imwrite("$matchFilePath/$filename.png", tempImage)
        }
        tempImage.release()
    }

    /** Saves a bitmap for debugging purposes.
     *
     * @param bitmap The optional bitmap to save.
     * If not specified, then a screenshot is taken and saved instead.
     * @param filename The filename for the saved bitmap.
     * @param bbox A bounding region to crop the [bitmap] to before saving.
     */
    fun saveBitmap(bitmap: Bitmap? = null, filename: String, bbox: BoundingBox) {
        val bitmap = bitmap ?: getSourceBitmap()
        val croppedBitmap = createSafeBitmap(
            bitmap,
            bbox.x,
            bbox.y,
            bbox.w,
            bbox.h,
            "saveBitmap(filename=$filename, bbox=$bbox)",
        )
        saveBitmap(bitmap = croppedBitmap, filename = filename)
    }

    /** Crops a bitmap to the specified region.
     *
     * This is a wrapper around [ImageUtils::createSafeBitmap] that allows
     * us to pass a BoundingBox for the cropping region instead of passing
     * each parameter separately.
     *
     * @param sourceBitmap The bitmap to crop.
     * @param bbox The BoundingBox specifying the crop region.
     * @param context The debugging context string.
     *
     * @return The cropped bitmap.
     */
    fun createSafeBitmap(sourceBitmap: Bitmap, bbox: BoundingBox, context: String): Bitmap? {
        return createSafeBitmap(
            sourceBitmap,
            bbox.x,
            bbox.y,
            bbox.w,
            bbox.h,
            context,
        )
    }

    /** Takes a screenshot of the specified region on the screen.
     *
     * This is a wrapper around [ImageUtils::getRegionBitmap] that allows
     * us to pass a BoundingBox for the cropping region instead of passing
     * each parameter separately.
     *
     * This is faster than calling [getSourceBitmap] since it crops the
     * screenshot prior to converting it to a Bitmap.
     *
     * @param bbox The BoundingBox specifying the crop region.
     *
     * @return The cropped screenshot.
     */
    fun getRegionBitmap(bbox: BoundingBox): Bitmap {
        return getRegionBitmap(
            x = bbox.x,
            y = bbox.y,
            w = bbox.w,
            h = bbox.h,
        )
    }

    /** Compares two bitmaps using Structural Similarity Index (SSIM).
     *
     * @param bitmap1 The first bitmap to compare.
     * @param bitmap2 The bitmap to compare against.
     *
     * @return The similarity score between the two bitmaps between 0.0 and 1.0.
     * Higher values are more similar.
     */
    fun compareBitmapsSSIM(bitmap1: Bitmap, bitmap2: Bitmap): Double {
        // Ensure bitmaps are same size for SSIM comparison
        if (bitmap1.width != bitmap2.width || bitmap1.height != bitmap2.height) {
            // Handle error or resize
            return 0.0
        }

        val mat1 = Mat()
        val mat2 = Mat()
        Utils.bitmapToMat(bitmap1, mat1)
        Utils.bitmapToMat(bitmap2, mat2)

        val grayMat1 = Mat()
        val grayMat2 = Mat()
        Imgproc.cvtColor(mat1, grayMat1, Imgproc.COLOR_BGR2GRAY)
        Imgproc.cvtColor(mat2, grayMat2, Imgproc.COLOR_BGR2GRAY)

        // A direct SSIM function isn't readily available in the core Java/Kotlin bindings,
        // so you'd need a custom implementation or use a different metric like MSE
        // for a quick calculation. A simplified pixel difference is shown below.

        val diff = Mat()
        org.opencv.core.Core.absdiff(grayMat1, grayMat2, diff)
        val nonZeroPixels = org.opencv.core.Core.countNonZero(diff)

        val totalPixels = grayMat1.rows() * grayMat1.cols()
        val similarityScore = 1.0 - (nonZeroPixels.toDouble() / totalPixels.toDouble())

        // A score near 1.0 means very similar.

        mat1.release()
        mat2.release()
        grayMat1.release()
        grayMat2.release()
        diff.release()

        return similarityScore
    }

    /** Converts ConvexHull indices to actual points.
     *
     * @param contour The contour to apply this translation to.
     * @param hullIndices The indices to convert.
     *
     * @return The MatOfPoint containing the converted points.
     */
    private fun getHullFromIndices(
        contour: MatOfPoint,
        hullIndices: MatOfInt,
    ): MatOfPoint {
        val points = contour.toArray()
        val hullPoints = hullIndices.toArray().map { points[it] }
        return MatOfPoint(*hullPoints.toTypedArray())
    }

    /** Detects rectangles with rounded corners on the screen.
     *
     * This uses traditional image processing algorithms to detect
     * all rectangles with rounded corners on the screen.
     *
     * For this to work, the rectangle must either have an outline or
     * be easily differentiable from the background color. Play around with
     * the `src/data/imageDetection.py` test script to see what parameters
     * work best for the items you're trying to detect.
     *
     * @param bitmap Optional bitmap containing the rectangles you want to detect.
     * If NULL, then a screenshot will be used.
     * @param region A bounding box region to limit the detection to.
     * If not specified, then the entire bitmap will be used.
     * @param minArea Filters out any rectangles with an area smaller than this parameter.
     * If not specified, then no lower bound filter will be applied.
     * @param maxArea Filters out any rectangles with an area larger than this parameter.
     * If not specified, then no upper bound filter will be applied.
     * @param epsilonScalar A small value used to scale the detection of rounded
     * corners on the rectangles. Defaults to 0.02 which works in most cases.
     * @param cannyLowerThreshold First threshold for hysteresis procedure for
     * Canny edge detection. Not applicable if [bUseAdaptiveThreshold] is enabled.
     * @param cannyUpperThreshold Second threshold for hysteresis procedure for
     * Canny edge detection. Not applicable if [bUseAdaptiveThreshold] is enabled.
     * @param blurSize The size of the kernel to use for the gaussian blur.
     * Must be a positive and odd integer. A value of 1 will effectively be no blur.
     * @param bUseAdaptiveThreshold Whether to use an adaptive threshold instead
     * of Canny edge detection. Can provide better results in some cases.
     * @param adaptiveThresholdBlockSize The size of a pixel neighborhood that is used
     * to calculate the threshold value. Must be an odd integer with a value of
     * 3 or higher.
     * @param adaptiveThresholdConstant A constant that is subtracted from the mean
     * or weighted mean. Can be any real number.
     *
     * @return A list of BoundingBox objects for detected rectangles, sorted by their
     * y-position in the bitmap (from top to bottom on screen).
     */
    fun detectRoundedRectangles(
        bitmap: Bitmap? = null,
        region: BoundingBox? = null,
        minArea: Int? = null,
        maxArea: Int? = null,
        blurSize: Int = 5,
        epsilonScalar: Double = 0.02,
        cannyLowerThreshold: Int = 30,
        cannyUpperThreshold: Int = 50,
        bUseAdaptiveThreshold: Boolean = false,
        adaptiveThresholdBlockSize: Int = 11,
        adaptiveThresholdConstant: Double = 2.0,
    ): List<BoundingBox> {
        val bitmap: Bitmap = if (region == null) {
            bitmap ?: getSourceBitmap()
        } else if (bitmap == null) {
            createSafeBitmap(
                getSourceBitmap(),
                region,
                "detectRoundedRectangles"
            )!!
        } else {
            createSafeBitmap(bitmap, region, "detectRoundedRectangles") ?: getSourceBitmap()
        }

        // Input sanitization

        val cannyLowerThreshold: Double = cannyLowerThreshold.coerceIn(0, 255).toDouble()
        val cannyUpperThreshold: Double = cannyUpperThreshold.coerceIn(0, 255).toDouble()

        val screenArea: Int = SharedData.displayWidth * SharedData.displayHeight
        val minArea: Int = (minArea ?: 0).coerceIn(0, screenArea)
        val maxArea: Int = (maxArea ?: screenArea).coerceIn(minArea, screenArea)

        if (minArea > maxArea) {
            throw IllegalArgumentException("minArea ($minArea) > maxArea ($maxArea)")
        }

        if (blurSize <= 0 || blurSize % 2 == 0) {
            throw IllegalArgumentException("blurSize must be a positive odd integer. Got: $blurSize.")
        }

        val blurKernel = Size(blurSize.toDouble(), blurSize.toDouble())

        val result: MutableList<BoundingBox> = mutableListOf()

        val srcImage = Mat()
		Utils.bitmapToMat(bitmap, srcImage)

        val image = Mat()
        Imgproc.cvtColor(srcImage, image, Imgproc.COLOR_RGB2GRAY)
        Imgproc.GaussianBlur(image, image, blurKernel, 0.0)
        if (bUseAdaptiveThreshold) {
            Imgproc.adaptiveThreshold(
                image,
                image,
                255.0, // maxValue
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                adaptiveThresholdBlockSize, // blockSize (must be odd)
                adaptiveThresholdConstant, // C (constant to subtract)
            )
        } else {
            Imgproc.Canny(
                image,
                image,
                cannyLowerThreshold,
                cannyUpperThreshold,
                3,
                false,
            )
        }

        if (debugMode) {
            val resultBitmap = createBitmap(image.cols(), image.rows())
            Utils.matToBitmap(image, resultBitmap)
            saveBitmap(resultBitmap, "detectRoundedRectangles_canny", fullRes = true)
        }

        val contours: MutableList<MatOfPoint> = mutableListOf()
        val hierarchy = Mat()
        Imgproc.findContours(
            image,
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE,
        )

        for (cnt in contours) {
            val area = Imgproc.contourArea(cnt)

            // Filter out contours with invalid areas.
            if (area < minArea || area > maxArea) {
                continue
            }

            // Use convex hull to ignore rounded corners.
            val hullPoints = MatOfInt()
            Imgproc.convexHull(cnt, hullPoints)
            // Convert hull indices back to MatOfPoint
            val hullContour = getHullFromIndices(cnt, hullPoints)

            // Approximate shape.
            val approx = MatOfPoint2f()
            val cnt2f = MatOfPoint2f(*hullContour.toArray())
            val peri = Imgproc.arcLength(cnt2f, true)
            Imgproc.approxPolyDP(cnt2f, approx, epsilonScalar * peri, true)

            // Check for four vertices.
            if (approx.total() == 4L) {
                val rect = Imgproc.boundingRect(cnt)
                if (debugMode) {
                    Imgproc.rectangle(srcImage, rect.tl(), rect.br(), Scalar(0.0, 255.0, 0.0), 2)
                }
                result.add(BoundingBox(rect.x, rect.y, rect.width, rect.height))
            }

            // Free memory for each mat.
            hullPoints.release()
            hullContour.release()
            approx.release()
            cnt2f.release()
        }

        if (debugMode) {
            val resultBitmap = createBitmap(srcImage.cols(), srcImage.rows())
            Imgproc.cvtColor(srcImage, srcImage, Imgproc.COLOR_BGR2RGB)
            Utils.matToBitmap(srcImage, resultBitmap)
            saveBitmap(resultBitmap, "detectRoundedRectangles", fullRes = true)
        }

        // Free memory for each mat.
        contours.forEach { it.release() }
        contours.clear()
        hierarchy.release()
        image.release()
        srcImage.release()

        return result.toList()
    }

    /** Detects rectangles with on the screen.
     *
     * A more robust version of [detectRoundedRectangles] with slightly less
     * accurate rectangle boundaries in favor of higher chance of detection.
     *
     * This uses traditional image processing algorithms to detect
     * all rectangles on the screen.
     *
     * For this to work, the rectangle must either have an outline or
     * be easily differentiable from the background color. Play around with
     * the `src/data/imageDetection.py` test script to see what parameters
     * work best for the items you're trying to detect.
     *
     * @param bitmap Optional bitmap containing the rectangles you want to detect.
     * If NULL, then a screenshot will be used.
     * @param region A bounding box region to limit the detection to.
     * If not specified, then the entire bitmap will be used.
     * @param minArea Filters out any rectangles with an area smaller than this parameter.
     * If not specified, then no lower bound filter will be applied.
     * @param maxArea Filters out any rectangles with an area larger than this parameter.
     * If not specified, then no upper bound filter will be applied.
     * @param blurSize The size of the kernel to use for the gaussian blur.
     * Must be a positive and odd integer. A value of 1 will effectively be no blur.
     * @param epsilonScalar A small value used to scale the detection of rounded
     * corners on the rectangles. Defaults to 0.02 which works in most cases.
     * @param fillSeedPoint The location within the [bitmap] or [region] that is part
     * of the background on which the rectangles are located. Think of this like
     * the fill (paint bucket) tool in image editing software. So if the rectangles
     * are all against a white background, then [fillSeedPoint] should be the location
     * of one of the white pixels in the background.
     * @param fillLoDiffValue Maximum lower brightness/color difference for fill.
     * Higher values cause the [fillSeedPoint] color fill to spread further.
     * @param fillUpDiffValue Maximum upper brightness/color difference for fill.
     * Higher values cause the [fillSeedPoint] color fill to spread further.
     * @param morphKernelSize The size of the kernel used for the opening morphology
     * operation. Higher values will cause detected rectangles to be more accurate,
     * but too high of values can cause rectangles to not be detected at all.
     * @param bIgnoreOverflowYAxis Whether to discard any results whose region extends
     * outside of the [region] y-axis.
     * @param bIgnoreOverflowXAxis Whether to discard any results whose region extends
     * outside of the [region] x-axis.
     *
     * @return A list of BoundingBox objects for detected rectangles, sorted by their
     * y-position in the bitmap (from top to bottom on screen).
     */
    fun detectRectanglesGeneric(
        bitmap: Bitmap? = null,
        region: BoundingBox? = null,
        minArea: Int? = null,
        maxArea: Int? = null,
        blurSize: Int = 7,
        epsilonScalar: Double = 0.02,
        fillSeedPoint: Point = Point(10.0, 10.0),
        fillLoDiffValue: Int = 1,
        fillUpDiffValue: Int = 1,
        morphKernelSize: Int = 100,
        bIgnoreOverflowYAxis: Boolean = true,
        bIgnoreOverflowXAxis: Boolean = true,
    ): List<BoundingBox> {
        val bitmap: Bitmap = if (region == null) {
            bitmap ?: getSourceBitmap()
        } else if (bitmap == null) {
            createSafeBitmap(
                getSourceBitmap(),
                region,
                "detectRectanglesGeneric"
            )!!
        } else {
            createSafeBitmap(bitmap, region, "detectRectanglesGeneric") ?: getSourceBitmap()
        }

        // Input sanitization

        val screenArea: Int = SharedData.displayWidth * SharedData.displayHeight
        val minArea: Int = (minArea ?: 0).coerceIn(0, screenArea)
        val maxArea: Int = (maxArea ?: screenArea).coerceIn(minArea, screenArea)

        if (minArea > maxArea) {
            throw IllegalArgumentException("minArea ($minArea) > maxArea ($maxArea)")
        }

        if (blurSize <= 0 || blurSize % 2 == 0) {
            throw IllegalArgumentException("blurSize must be a positive odd integer. Got: $blurSize.")
        }

        val blurKernel = Size(blurSize.toDouble(), blurSize.toDouble())

        val fillLoDiffValue: Int = fillLoDiffValue.coerceIn(0, 255)
        val loDiff = Scalar(fillLoDiffValue.toDouble(), fillLoDiffValue.toDouble(), fillLoDiffValue.toDouble())

        val fillUpDiffValue: Int = fillUpDiffValue.coerceIn(0, 255)
        val upDiff = Scalar(fillUpDiffValue.toDouble(), fillUpDiffValue.toDouble(), fillUpDiffValue.toDouble())

        val morphKernelSize: Int = morphKernelSize.coerceIn(0, 250)
        val morphKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(morphKernelSize.toDouble(), morphKernelSize.toDouble()),
        )

        val result: MutableList<BoundingBox> = mutableListOf()

        val srcImage = Mat()
		Utils.bitmapToMat(bitmap, srcImage)

        val image = Mat()
        Imgproc.cvtColor(srcImage, image, Imgproc.COLOR_RGB2GRAY)
        Imgproc.GaussianBlur(image, image, blurKernel, 0.0)
        
        val rect = Rect()
        val fillColor = Scalar(0.0, 0.0, 0.0)
        Imgproc.floodFill(
            image,
            Mat(),
            fillSeedPoint,
            fillColor,
            rect,
            loDiff,
            upDiff,
        )

        if (debugMode) {
            val resultBitmap = createBitmap(image.cols(), image.rows())
            Utils.matToBitmap(image, resultBitmap)
            saveBitmap(resultBitmap, "detectRectanglesGeneric_floodFill", fullRes = true)
        }

        // Set all non-black pixels to white.
        val blackMask = Mat()
        Core.compare(image, fillColor, blackMask, Core.CMP_EQ)
        val nonBlackMask = Mat()
        Core.bitwise_not(blackMask, nonBlackMask)
        image.setTo(Scalar(255.0, 255.0, 255.0), nonBlackMask)
        blackMask.release()
        nonBlackMask.release()

        if (debugMode) {
            val resultBitmap = createBitmap(image.cols(), image.rows())
            Utils.matToBitmap(image, resultBitmap)
            saveBitmap(resultBitmap, "detectRectanglesGeneric_masked", fullRes = true)
        }

        Imgproc.morphologyEx(image, image, Imgproc.MORPH_OPEN, morphKernel)

        if (debugMode) {
            val resultBitmap = createBitmap(image.cols(), image.rows())
            Utils.matToBitmap(image, resultBitmap)
            saveBitmap(resultBitmap, "detectRectanglesGeneric_opened", fullRes = true)
        }

        // Invert binary image.
        //Core.bitwise_not(image, image)

        val contours: MutableList<MatOfPoint> = mutableListOf()
        val hierarchy = Mat()
        Imgproc.findContours(
            image,
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE,
        )

        for (cnt in contours) {
            val area = Imgproc.contourArea(cnt)

            // Filter out contours with invalid areas.
            if (area < minArea || area > maxArea) {
                continue
            }

            // Use convex hull to ignore rounded corners.
            val hullPoints = MatOfInt()
            Imgproc.convexHull(cnt, hullPoints)
            // Convert hull indices back to MatOfPoint
            val hullContour = getHullFromIndices(cnt, hullPoints)

            // Approximate shape.
            val approx = MatOfPoint2f()
            val cnt2f = MatOfPoint2f(*hullContour.toArray())
            val peri = Imgproc.arcLength(cnt2f, true)
            Imgproc.approxPolyDP(cnt2f, approx, epsilonScalar * peri, true)

            // Check for four vertices.
            if (approx.total() == 4L) {
                val rect = Imgproc.boundingRect(cnt)

                // Do not include any rects that are touching the bounding region.
                if (bIgnoreOverflowYAxis) {
                    if (rect.y <= 0 || rect.y + rect.height >= bitmap.height - 1) {
                        continue
                    }
                }

                if (bIgnoreOverflowXAxis) {
                    if (rect.x <= 0 || rect.x + rect.width >= bitmap.width - 1) {
                        continue
                    }
                }

                if (debugMode) {
                    Imgproc.rectangle(srcImage, rect.tl(), rect.br(), Scalar(0.0, 255.0, 0.0), 2)
                }
                result.add(BoundingBox(rect.x, rect.y, rect.width, rect.height))
            }

            // Free memory for each mat.
            hullPoints.release()
            hullContour.release()
            approx.release()
            cnt2f.release()
        }

        if (debugMode) {
            val resultBitmap = createBitmap(srcImage.cols(), srcImage.rows())
            Imgproc.cvtColor(srcImage, srcImage, Imgproc.COLOR_BGR2RGB)
            Utils.matToBitmap(srcImage, resultBitmap)
            saveBitmap(resultBitmap, "detectRectanglesGeneric_result", fullRes = true)
        }

        // Free memory for each mat.
        contours.forEach { it.release() }
        contours.clear()
        hierarchy.release()
        image.release()
        srcImage.release()

        return result.toList()
    }

    /** Converts an RGB hex string to an HSV
     *
     * The string should be a color hex string of the format:
     *      #RRGGBB
     *
     * @return A Scalar with HSV values.
     * H: 0-179
     * S: 0-255
     * V: 0-255
     */
    fun String.hexRGBToHSVScalar(): Scalar {
        val colorInt = Color.parseColor(this)
        val r = Color.red(colorInt)
        val g = Color.green(colorInt)
        val b = Color.blue(colorInt)

        val bgrColor = Scalar(b.toDouble(), g.toDouble(), r.toDouble())

        val bgrMat = Mat(1, 1, CvType.CV_8UC3, bgrColor)
        val hsvMat = Mat()

        Imgproc.cvtColor(bgrMat, hsvMat, Imgproc.COLOR_BGR2HSV)
        val res = Scalar(hsvMat.get(0, 0))

        bgrMat.release()
        hsvMat.release()

        return res
    }

    /** Converts a standardized HSV value range to OpenCV's HSV value range.
     *
     * OpenCV likes making everything harder for no reason, so we need to manually
     * convert typical HSV values into the value range that OpenCV is expecting:
     *          Standard        OpenCV
     *      H   0 - 360 (deg)   0 - 179
     *      S   0 - 100 (%)     0 - 255
     *      V   0 - 100 (%)     0 - 255
     *
     * @param h The standardized H value.
     * @param s The standardized S value.
     * @param v The standardized V value.
     *
     * @return An HSV Scalar containing the converted values in OpenCV's HSV range.
     */
    fun standardHsvToOpenCvHsvScalar(h: Int, s: Int, v: Int): Scalar {
        val newH: Int = (h / 2.0).toInt().coerceIn(0, 179)
        val newS: Int = ((s / 100.0) * 255.0).toInt().coerceIn(0, 255)
        val newV: Int = ((v / 100.0) * 255.0).toInt().coerceIn(0, 255)

        return Scalar(newH.toDouble(), newS.toDouble(), newV.toDouble())
    }


    /** Detects a scrollbar on the screen.
     *
     * @param bitmap Optional bitmap containing the rectangles you want to detect.
     * If NULL, then a screenshot will be used.
     * @param region A bounding box region to limit the detection to.
     * If not specified, then the entire bitmap will be used.
     * @param minArea Filters out any rectangles with an area smaller than this parameter.
     * If not specified, then no lower bound filter will be applied.
     * @param maxArea Filters out any rectangles with an area larger than this parameter.
     * If not specified, then no upper bound filter will be applied.
     * @param morphCloseKernelSize The size of the kernel used when applying a
     * closing morphology to the image. Higher values improve detection of scrollbars
     * at the cost of reduced location accuracy.
     *
     * @return On success, a pair where the first value is the full scrollbar's
     * BoundingBox and the second value is just the scrollbar's thumb's BoundingBox.
     * If either of these could not be found, then we return NULL.
     */
    fun detectScrollBar(
        bitmap: Bitmap? = null,
        region: BoundingBox? = null,
        minArea: Int? = null,
        maxArea: Int? = null,
        morphCloseKernelSize: Int = 10,
    ): Pair<BoundingBox, BoundingBox>? {
        val bitmap: Bitmap = if (region == null) {
            bitmap ?: getSourceBitmap()
        } else if (bitmap == null) {
            createSafeBitmap(
                getSourceBitmap(),
                region,
                "detectScrollBar"
            )!!
        } else {
            createSafeBitmap(bitmap, region, "detectScrollBar") ?: getSourceBitmap()
        }

        // Input sanitization

        val screenArea: Int = SharedData.displayWidth * SharedData.displayHeight
        val minArea: Int = (minArea ?: 0).coerceIn(0, screenArea)
        val maxArea: Int = (maxArea ?: screenArea).coerceIn(minArea, screenArea)

        if (minArea > maxArea) {
            throw IllegalArgumentException("minArea ($minArea) > maxArea ($maxArea)")
        }

        val morphCloseKernelSize: Int = morphCloseKernelSize.coerceIn(0, 250)
        val morphCloseKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(morphCloseKernelSize.toDouble(), morphCloseKernelSize.toDouble()),
        )

        val morphOpenKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))

        val srcImage = Mat()
		Utils.bitmapToMat(bitmap, srcImage)

        val image = Mat()
        Imgproc.cvtColor(srcImage, image, Imgproc.COLOR_RGB2GRAY)

        val hsvImage = Mat()
        Imgproc.cvtColor(srcImage, hsvImage, Imgproc.COLOR_RGB2HSV)

        if (debugMode) {
            val resultBitmap = createBitmap(hsvImage.cols(), hsvImage.rows())
            Utils.matToBitmap(hsvImage, resultBitmap)
            saveBitmap(resultBitmap, "detectScrollBar_hsvImage", fullRes = true)
        }

        val thumbColorRange: Pair<Scalar, Scalar> = Pair(
            standardHsvToOpenCvHsvScalar(252, 14, 52), // approx #787388
            standardHsvToOpenCvHsvScalar(254, 16, 56), // approx #7d788e
        )

        val barColorRange: Pair<Scalar, Scalar> = Pair(
            standardHsvToOpenCvHsvScalar(251, 4, 85), // approx #d3d1db
            standardHsvToOpenCvHsvScalar(253, 5, 86), // approx #d3d1db
        )

        val combinedColorRange: List<Pair<Scalar, Scalar>> = listOf(
            thumbColorRange,
            barColorRange,
        )

        /** Generates a mask from an HSV image using the given color range.
         *
         * @param hsvImage The HSV image used to gernerate the mask.
         * @param colorRanges A list of pairs of RGB hex color strings.
         * The first item in each pair is the lower bound and the second item is
         * the upper bound. Colors within this range in [hsvImage] will be masked.
         *
         * @return The generated mask Mat. Make sure to release this Mat when done.
         */
        fun extractMask(hsvImage: Mat, colorRanges: List<Pair<Scalar, Scalar>>): Mat {
            val mask = Mat.zeros(hsvImage.size(), CvType.CV_8UC1)
            for ((lower, upper) in colorRanges) {
                val tmpMask = Mat()
                Core.inRange(hsvImage, lower, upper, tmpMask)
                Core.bitwise_or(mask, tmpMask, mask)
                tmpMask.release()
            }

            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, morphOpenKernel)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, morphCloseKernel)
            return mask
        }

        val barMask: Mat = extractMask(
            hsvImage,
            combinedColorRange,
        )
        if (debugMode) {
            val resultBitmap = createBitmap(barMask.cols(), barMask.rows())
            Utils.matToBitmap(barMask, resultBitmap)
            saveBitmap(resultBitmap, "detectScrollBar_barMask", fullRes = true)
        }

        val thumbMask: Mat = extractMask(
            hsvImage,
            listOf<Pair<Scalar, Scalar>>(thumbColorRange),
        )
        if (debugMode) {
            val resultBitmap = createBitmap(thumbMask.cols(), thumbMask.rows())
            Utils.matToBitmap(thumbMask, resultBitmap)
            saveBitmap(resultBitmap, "detectScrollBar_thumbMask", fullRes = true)
        }

        /** Detects part of a scrollbar in the given mask.
         *
         * @param mask The masked image to find a scrollbar within.
         * @param minArea The smallest area allowed for the scrollbar.
         * @param maxArea The largest area allowed for the scrollbar.
         * @param debugString String used for debugging and saving debug images.
         *
         * @return The BoundingBox of the detected scrollbar on success.
         * Otherwise, NULL.
         */
        fun detectFromMask(
            mask: Mat,
            minArea: Int,
            maxArea: Int,
            debugString: String = "",
        ): BoundingBox? {
            val debugImage = Mat()
            Utils.bitmapToMat(bitmap, debugImage)

            val contours: MutableList<MatOfPoint> = mutableListOf()
            val hierarchy = Mat()
            Imgproc.findContours(
                mask,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )

            val result: MutableList<Pair<BoundingBox, Double>> = mutableListOf()
            for (cnt in contours) {
                val area = Imgproc.contourArea(cnt)

                // Filter out contours with invalid areas.
                if (area < minArea || area > maxArea) {
                    continue
                }

                val rect = Imgproc.boundingRect(cnt)

                // Do not include any rects that are touching the bounding region.
                if (rect.x <= 0 ||
                    rect.y <= 0 ||
                    rect.x + rect.width >= bitmap.width - 1 ||
                    rect.y + rect.height >= bitmap.height - 1
                ) {
                    continue
                }

                if (debugMode) {
                    Imgproc.rectangle(debugImage, rect.tl(), rect.br(), Scalar(0.0, 255.0, 0.0), 2)
                }

                result.add(Pair(
                    BoundingBox(rect.x, rect.y, rect.width, rect.height),
                    area,
                ))
            }

            if (debugMode) {
                val resultBitmap = createBitmap(debugImage.cols(), debugImage.rows())
                Imgproc.cvtColor(debugImage, debugImage, Imgproc.COLOR_BGR2RGB)
                Utils.matToBitmap(debugImage, resultBitmap)
                saveBitmap(resultBitmap, "detectScrollBar_$debugString", fullRes = true)
            }

            contours.forEach { it.release() }
            contours.clear()
            hierarchy.release()
            debugImage.release()

            return result.maxByOrNull { it.second }?.first ?: null
        }

        val bboxBar: BoundingBox? = detectFromMask(
            barMask,
            minArea = minArea,
            maxArea = maxArea,
            debugString = "bar",
        )
        if (bboxBar == null) {
            MessageLog.i(TAG, "No scrollbar detected.")
            val resultBitmap = createBitmap(srcImage.cols(), srcImage.rows())
            Imgproc.cvtColor(srcImage, srcImage, Imgproc.COLOR_BGR2RGB)
            Utils.matToBitmap(srcImage, resultBitmap)
            saveBitmap(resultBitmap, "detectScrollBar_FAILED", fullRes = true)
        }

        val bboxThumb: BoundingBox? = detectFromMask(
            thumbMask,
            minArea = 100,
            maxArea = maxArea,
            debugString = "thumb",
        )
        if (bboxThumb == null) {
            MessageLog.i(TAG, "No scrollbar thumb detected.")
        }

        // Free memory for each mat.
        barMask.release()
        thumbMask.release()
        hsvImage.release()
        image.release()
        srcImage.release()

        if (bboxThumb == null || bboxBar == null) {
            return null
        }

        MessageLog.d(TAG, "Detected ScrollBar: $bboxBar, ScrollBarThumb: $bboxThumb")
        return Pair(bboxBar, bboxThumb)
    }
}
