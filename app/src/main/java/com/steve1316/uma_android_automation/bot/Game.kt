package com.steve1316.uma_android_automation.bot

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.campaigns.AoHaru
import com.steve1316.uma_android_automation.utils.BotService
import com.steve1316.uma_android_automation.utils.ImageUtils
import com.steve1316.uma_android_automation.utils.MediaProjectionService
import com.steve1316.uma_android_automation.utils.MessageLog
import com.steve1316.uma_android_automation.utils.MyAccessibilityService
import com.steve1316.uma_android_automation.utils.SettingsPrinter
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.opencv.core.Point
import java.util.concurrent.TimeUnit

/**
 * Main driver for bot activity and navigation.
 */
class Game(val myContext: Context) {
	private val tag: String = "[${MainActivity.loggerTag}]Game"
	var notificationMessage: String = ""
	val imageUtils: ImageUtils = ImageUtils(myContext, this)
	val gestureUtils: MyAccessibilityService = MyAccessibilityService.getInstance()
	private val textDetection: TextDetection = TextDetection(this, imageUtils)
	
	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// SharedPreferences
	private var sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(myContext)
	private val campaign: String = sharedPreferences.getString("campaign", "")!!
	private val debugMode: Boolean = sharedPreferences.getBoolean("debugMode", false)
	
	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// Training
	private val trainings: List<String> = listOf("Speed", "Stamina", "Power", "Guts", "Wit")
	private val trainingMap: MutableMap<String, MutableMap<String, Int>> = mutableMapOf()
	private val blacklist: List<String> = sharedPreferences.getStringSet("trainingBlacklist", setOf())!!.toList()
	private var statPrioritization: List<String> = sharedPreferences.getString("statPrioritization", "")!!.split("|")
	private val enablePrioritizeEnergyOptions: Boolean = sharedPreferences.getBoolean("enablePrioritizeEnergyOptions", false)
	private val maximumFailureChance: Int = sharedPreferences.getInt("maximumFailureChance", 15)
	private var firstTrainingCheck = true
	private var previouslySelectedTraining = ""
	
	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// Racing
	private val enableFarmingFans = sharedPreferences.getBoolean("enableFarmingFans", false)
	private val daysToRunExtraRaces: Int = sharedPreferences.getInt("daysToRunExtraRaces", 4)
	private var raceRetries = 3
	private var raceRepeatWarningCheck = false
	var failedFanCheck = false
	
	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// Stops
	val enableSkillPointCheck: Boolean = sharedPreferences.getBoolean("enableSkillPointCheck", false)
	val skillPointsRequired: Int = sharedPreferences.getInt("skillPointCheck", 750)
	private val enablePopupCheck: Boolean = sharedPreferences.getBoolean("enablePopupCheck", false)
	private val enableStopOnMandatoryRace: Boolean = sharedPreferences.getBoolean("enableStopOnMandatoryRace", false)
	var detectedMandatoryRaceCheck = false
	
	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// Misc
	private var inheritancesDone = 0
	private val startTime: Long = System.currentTimeMillis()
	
	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	
	/**
	 * Returns a formatted string of the elapsed time since the bot started as HH:MM:SS format.
	 *
	 * Source is from https://stackoverflow.com/questions/9027317/how-to-convert-milliseconds-to-hhmmss-format/9027379
	 *
	 * @return String of HH:MM:SS format of the elapsed time.
	 */
	@SuppressLint("DefaultLocale")
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
	 * @param tag Distinguishes between messages for where they came from. Defaults to Game's TAG.
	 * @param isError Flag to determine whether to display log message in console as debug or error.
	 * @param isOption Flag to determine whether to append a newline right after the time in the string.
	 */
	fun printToLog(message: String, tag: String = this.tag, isError: Boolean = false, isOption: Boolean = false) {
		if (!isError) {
			Log.d(tag, message)
		} else {
			Log.e(tag, message)
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
	 * Wait the specified seconds to account for ping or loading.
	 * It also checks for interruption every 100ms to allow faster interruption and checks if the game is still in the middle of loading.
	 *
	 * @param seconds Number of seconds to pause execution.
	 * @param skipWaitingForLoading If true, then it will skip the loading check. Defaults to false.
	 */
	fun wait(seconds: Double, skipWaitingForLoading: Boolean = false) {
		val totalMillis = (seconds * 1000).toLong()
		// Check for interruption every 100ms.
		val checkInterval = 100L
		
		var remainingMillis = totalMillis
		while (remainingMillis > 0) {
			if (!BotService.isRunning) {
				throw InterruptedException()
			}
			
			val sleepTime = minOf(checkInterval, remainingMillis)
			runBlocking {
				delay(sleepTime)
			}
			remainingMillis -= sleepTime
		}

		if (!skipWaitingForLoading) {
			// Check if the game is still loading as well.
			waitForLoading()
		}
	}

	/**
	 * Wait for the game to finish loading.
	 */
	fun waitForLoading() {
		while (checkLoading()) {
			// Avoid an infinite loop by setting the flag to true.
			wait(0.5, skipWaitingForLoading = true)
		}
	}
	
	/**
	 * Find and tap the specified image.
	 *
	 * @param imageName Name of the button image file in the /assets/images/ folder.
	 * @param tries Number of tries to find the specified button. Defaults to 3.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @param taps Specify the number of taps on the specified image. Defaults to 1.
	 * @param suppressError Whether or not to suppress saving error messages to the log in failing to find the button. Defaults to false.
	 * @return True if the button was found and clicked. False otherwise.
	 */
	fun findAndTapImage(imageName: String, tries: Int = 3, region: IntArray = intArrayOf(0, 0, 0, 0), taps: Int = 1, suppressError: Boolean = false): Boolean {
		if (debugMode) {
			printToLog("[DEBUG] Now attempting to find and click the \"$imageName\" button.")
		}
		
		val tempLocation: Point? = imageUtils.findImage(imageName, tries = tries, region = region, suppressError = suppressError).first
		
		return if (tempLocation != null) {
			Log.d(tag, "Found and going to tap: $imageName")
			tap(tempLocation.x, tempLocation.y, imageName, taps = taps)
			true
		} else {
			false
		}
	}

	/**
	 * Performs a tap on the screen at the coordinates and then will wait until the game processes the server request and gets a response back.
	 *
	 * @param x The x-coordinate.
	 * @param y The y-coordinate.
	 * @param imageName The template image name to use for tap location randomization.
	 * @param taps The number of taps.
	 */
	fun tap(x: Double, y: Double, imageName: String, taps: Int = 1) {
		// Perform the tap.
		gestureUtils.tap(x, y, imageName, taps = taps)

		// Now check if the game is waiting for a server response from the tap and wait if necessary.
		wait(0.20)
		waitForLoading()
	}

	/**
	 * Handles the test to perform template matching to determine what the best scale will be for the device.
	 */
	fun startTemplateMatchingTest() {
		printToLog("\n[DEBUG] Now beginning basic template match test on the Home screen.")
		printToLog("[DEBUG] Template match confidence setting will be overridden for the test.\n")
		val results = imageUtils.startTemplateMatchingTest()
		printToLog("\n[INFO] Basic template match test complete.")

		// Print all scale/confidence combinations that worked for each template.
		for ((templateName, scaleConfidenceResults) in results) {
			if (scaleConfidenceResults.isNotEmpty()) {
				printToLog("[INFO] All working scale/confidence combinations for $templateName:")
				for (result in scaleConfidenceResults) {
					printToLog("[INFO]	Scale: ${result.scale}, Confidence: ${result.confidence}")
				}
			} else {
				printToLog("[WARNING] No working scale/confidence combinations found for $templateName")
			}
		}

		// Then print the median scales and confidences.
		val medianScales = mutableListOf<Double>()
		val medianConfidences = mutableListOf<Double>()
		for ((templateName, scaleConfidenceResults) in results) {
			if (scaleConfidenceResults.isNotEmpty()) {
				val sortedScales = scaleConfidenceResults.map { it.scale }.sorted()
				val sortedConfidences = scaleConfidenceResults.map { it.confidence }.sorted()
				val medianScale = sortedScales[sortedScales.size / 2]
				val medianConfidence = sortedConfidences[sortedConfidences.size / 2]
				medianScales.add(medianScale)
				medianConfidences.add(medianConfidence)
				printToLog("[INFO] Median scale for $templateName: $medianScale")
				printToLog("[INFO] Median confidence for $templateName: $medianConfidence")
			}
		}
		
		if (medianScales.isNotEmpty()) {
			printToLog("\n[INFO] The following are the recommended scales to set (pick one as a whole number value): $medianScales.")
			printToLog("[INFO] The following are the recommended confidences to set (pick one as a whole number value): $medianConfidences.")
		} else {
			printToLog("\n[ERROR] No median scale/confidence can be found.", isError = true)
		}
	}

	/**
	 * Handles the test to perform OCR on the training failure chance for the current training on display.
	 */
	fun startSingleTrainingFailureOCRTest() {
		printToLog("\n[DEBUG] Now beginning Single Training Failure OCR test on the Training screen for the current training on display.")
		printToLog("[DEBUG] Note that this test is dependent on having the correct scale.")
		printToLog("[DEBUG] Forcing confidence setting to be 0.8 for the test.\n")
		val failureChance: Int = imageUtils.findTrainingFailureChance()
		if (failureChance == -1) {
			printToLog("[ERROR] Training Failure Chance detection failed.", isError = true)
		} else {
			printToLog("[INFO] Training Failure Chance: $failureChance")
		}
	}

	/**
	 * Handles the test to perform OCR on training failure chances for all 5 of the trainings on display.
	 */
	fun startComprehensiveTrainingFailureOCRTest() {
		printToLog("\n[DEBUG] Now beginning Comprehensive Training Failure OCR test on the Training screen for all 5 trainings on display.")
		printToLog("[DEBUG] Note that this test is dependent on having the correct scale.")
		printToLog("[DEBUG] Forcing confidence setting to be 0.8 for the test.\n")
		findStatsAndPercentages(test = true)
		printMap()
	}
	
	////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////////
	// Helper functions to be shared amongst the various Campaigns.
	
	////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////////
	// Functions to check what screen the bot is at.
	
	/**
	 * Checks if the bot is at the Main screen or the screen with available options to undertake.
	 * This will also make sure that the Main screen does not contain the option to select a race.
	 *
	 * @return True if the bot is at the Main screen. Otherwise false.
	 */
	fun checkMainScreen(): Boolean {
		printToLog("[INFO] Checking if the bot is sitting at the Main screen.")
		return if (imageUtils.findImage("tazuna", tries = 1, region = imageUtils.regionTopHalf).first != null &&
			imageUtils.findImage("race_select_mandatory", tries = 1, region = imageUtils.regionBottomHalf, suppressError = true).first == null) {
			printToLog("\n[INFO] Current bot location is at Main screen.")
			true
		} else if (!enablePopupCheck && imageUtils.findImage("cancel", tries = 1, region = imageUtils.regionBottomHalf).first != null &&
			imageUtils.findImage("race_confirm", tries = 1, region = imageUtils.regionBottomHalf).first != null) {
			// This popup is most likely the insufficient fans popup. Force an extra race to catch up on the required fans.
			printToLog("[INFO] There is a possible insufficient fans or maiden race popup.")
			failedFanCheck = true
			true
		} else {
			false
		}
	}
	
	/**
	 * Checks if the bot is at the Training Event screen with an active event with options to select on screen.
	 *
	 * @return True if the bot is at the Training Event screen. Otherwise false.
	 */
	fun checkTrainingEventScreen(): Boolean {
		printToLog("[INFO] Checking if the bot is sitting on the Training Event screen.")
		return if (imageUtils.findImage("training_event_active", tries = 1, region = imageUtils.regionMiddle).first != null) {
			printToLog("\n[INFO] Current bot location is at Training Event screen.")
			true
		} else {
			false
		}
	}
	
	/**
	 * Checks if the bot is at the preparation screen with a mandatory race needing to be completed.
	 *
	 * @return True if the bot is at the Main screen with a mandatory race. Otherwise false.
	 */
	fun checkMandatoryRacePrepScreen(): Boolean {
		printToLog("[INFO] Checking if the bot is sitting on the Race Preparation screen.")
		return if (imageUtils.findImage("race_select_mandatory", tries = 1, region = imageUtils.regionBottomHalf).first != null) {
			printToLog("\n[INFO] Current bot location is at the preparation screen with a mandatory race ready to be completed.")
			true
		} else if (imageUtils.findImage("race_select_mandatory_goal", tries = 1, region = imageUtils.regionMiddle).first != null) {
			// Most likely the user started the bot here so a delay will need to be placed to allow the start banner of the Service to disappear.
			wait(2.0)
			printToLog("\n[INFO] Current bot location is at the Race Selection screen with a mandatory race needing to be selected.")
			// Walk back to the preparation screen.
			findAndTapImage("back", tries = 1, region = imageUtils.regionBottomHalf)
			wait(1.0)
			true
		} else {
			false
		}
	}

	/**
	 * Checks if the bot is at the Racing screen waiting to be skipped or done manually.
	 *
	 * @return True if the bot is at the Racing screen. Otherwise, false.
	 */
	fun checkRacingScreen(): Boolean {
		printToLog("[INFO] Checking if the bot is sitting on the Racing screen.")
		return if (imageUtils.findImage("race_change_strategy", tries = 1, region = imageUtils.regionBottomHalf).first != null) {
			printToLog("\n[INFO] Current bot location is at the Racing screen waiting to be skipped or done manually.")
			true
		} else {
			false
		}
	}
	
	/**
	 * Checks if the day number is odd to be eligible to run an extra race, excluding Summer where extra racing is not allowed.
	 *
	 * @return True if the day number is odd. Otherwise false.
	 */
	fun checkExtraRaceAvailability(): Boolean {
		val dayNumber = imageUtils.determineDayForExtraRace()
		printToLog("\n[INFO] Current remaining number of days before the next mandatory race: $dayNumber.")
		
		return enableFarmingFans && dayNumber % daysToRunExtraRaces == 0 && !raceRepeatWarningCheck &&
				imageUtils.findImage("race_select_extra_locked_uma_finals", tries = 1, region = imageUtils.regionBottomHalf).first == null &&
				imageUtils.findImage("race_select_extra_locked", tries = 1, region = imageUtils.regionBottomHalf).first == null &&
				imageUtils.findImage("recover_energy_summer", tries = 1, region = imageUtils.regionBottomHalf).first == null
	}
	
	/**
	 * Checks if the bot is at the Ending screen detailing the overall results of the run.
	 *
	 * @return True if the bot is at the Ending screen. Otherwise false.
	 */
	fun checkEndScreen(): Boolean {
		return if (imageUtils.findImage("complete_career", tries = 1, region = imageUtils.regionBottomHalf).first != null) {
			printToLog("\n[END] Bot has reached the End screen.")
			true
		} else {
			false
		}
	}
	
	/**
	 * Checks if the bot has a injury.
	 *
	 * @return True if the bot has a injury. Otherwise false.
	 */
	fun checkInjury(): Boolean {
		val recoverInjuryLocation = imageUtils.findImage("recover_injury", tries = 1, region = imageUtils.regionBottomHalf).first
		return if (recoverInjuryLocation != null && imageUtils.checkColorAtCoordinates(
				recoverInjuryLocation.x.toInt(),
				recoverInjuryLocation.y.toInt() + 15,
				intArrayOf(151, 105, 243),
				10
		)) {
			if (findAndTapImage("recover_injury", tries = 1, region = imageUtils.regionBottomHalf)) {
				wait(0.3)
				if (imageUtils.confirmLocation("recover_injury", tries = 1, region = imageUtils.regionMiddle)) {
					printToLog("\n[INFO] Injury detected and attempted to heal.")
					true
				} else {
					false
				}
			} else {
				printToLog("\n[WARNING] Injury detected but attempt to rest failed.")
				false
			}
		} else {
			printToLog("\n[INFO] No injury detected.")
			false
		}
	}

	/**
	 * Checks if the bot is at a "Now Loading..." screen or if the game is awaiting for a server response. This may cause significant delays in normal bot processes.
	 *
	 * @return True if the game is still loading or is awaiting for a server response. Otherwise, false.
	 */
	fun checkLoading(): Boolean {
		printToLog("[INFO] Now checking if the game is still loading...")
		return if (imageUtils.findImage("connecting", tries = 1, region = imageUtils.regionTopHalf, suppressError = true).first != null) {
			printToLog("[INFO] Detected that the game is awaiting a response from the server from the \"Connecting\" text at the top of the screen. Waiting...")
			true
		} else if (imageUtils.findImage("now_loading", tries = 1, region = imageUtils.regionBottomHalf, suppressError = true).first != null) {
			printToLog("[INFO] Detected that the game is still loading from the \"Now Loading\" text at the bottom of the screen. Waiting...")
			true
		} else {
			false
		}
	}
	
	////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////////
	// Functions to execute Training by determining failure percentages, overall stat gains and stat weights.
	
	/**
	 * The entry point for handling Training.
	 */
	fun handleTraining() {
		printToLog("\n[TRAINING] Starting Training process...")
		
		// Enter the Training screen.
		if (findAndTapImage("training_option", region = imageUtils.regionBottomHalf)) {
			// Acquire the percentages and stat gains for each training.
			findStatsAndPercentages()
			
			if (trainingMap.isEmpty()) {
				printToLog("[TRAINING] Backing out of Training and returning on the Main screen.")
				findAndTapImage("back", region = imageUtils.regionBottomHalf)
				wait(1.0)
				
				if (checkMainScreen()) {
					printToLog("[TRAINING] Will recover energy due to either failure chance was high enough to do so or no failure chances were detected via OCR.")
					recoverEnergy()
				} else {
					printToLog("[ERROR] Could not head back to the Main screen in order to recover energy.")
				}
			} else {
				// Now select the training option with the highest weight.
				executeTraining()
				
				firstTrainingCheck = false
			}
			
			raceRepeatWarningCheck = false
			printToLog("\n[TRAINING] Training process completed.")
		} else {
			printToLog("[ERROR] Cannot start the Training process. Moving on...", isError = true)
		}
	}
	
	/**
	 * Find the success percentages and stat gain for each training and assign them to the MutableMap object to be shared across the whole class.
	 *
	 * @param test Flag that forces the failure chance through even if it is not in the acceptable range for testing purposes.
	 */
	private fun findStatsAndPercentages(test: Boolean = false) {
		printToLog("\n[TRAINING] Checking for success percentages and total stat increases for training selection...")
		
		// Acquire the position of the speed stat text.
		val (speedStatTextLocation, _) = if (campaign == "Ao Haru") {
			imageUtils.findImage("aoharu_stat_speed", region = imageUtils.regionBottomHalf)
		} else {
			imageUtils.findImage("stat_speed", region = imageUtils.regionBottomHalf)
		}
		
		if (speedStatTextLocation != null) {
			// Perform a percentage check of Speed training to see if the bot has enough energy to do training. As a result, Speed training will be the one selected for the rest of the algorithm.
			if (!imageUtils.confirmLocation("speed_training", tries = 5, region = imageUtils.regionTopHalf, suppressError = true)) {
				findAndTapImage("training_speed")
			}
			
			var failureChance: Int = imageUtils.findTrainingFailureChance()
			if (failureChance == -1) {
				printToLog("[WARNING] Skipping training due to not being able to confirm whether or not the bot is at the Training screen.")
				return
			}
			
			if (test || failureChance <= maximumFailureChance) {
				printToLog("[TRAINING] $failureChance% within acceptable range of ${maximumFailureChance}%. Proceeding to acquire all other percentages and total stat increases...")
				
				var initialStatWeight: Int = imageUtils.findInitialStatWeight("Speed")
				
				// Save the results to the map if Speed training is not blacklisted.
				if (!blacklist.contains("Speed")) {
					trainingMap["Speed"] = mutableMapOf(
						"failureChance" to failureChance,
						"statWeight" to initialStatWeight,
					)
				}
				
				// Get all trainings not blacklisted.
				val whitelistedTrainings: MutableList<String> = mutableListOf()
				trainings.forEach { training ->
					if (!blacklist.contains(training)) {
						whitelistedTrainings.add(training)
					}
				}
				
				// Iterate through every training after Speed training that is not blacklisted.
				whitelistedTrainings.forEach { training ->
					if (training != "Speed") {
						val newY = 319
						val newX: Double = when (training) {
							"Stamina" -> {
								280.0
							}
							"Power" -> {
								402.0
							}
							"Guts" -> {
								591.0
							}
							else -> {
								779.0
							}
						}
						
						if (imageUtils.isTablet) {
							if (training == "Stamina") {
								tap(speedStatTextLocation.x + imageUtils.relWidth((newX * 1.05).toInt()), speedStatTextLocation.y + imageUtils.relHeight((newY * 1.50).toInt()), "training_option_circular")
							} else {
								tap(speedStatTextLocation.x + imageUtils.relWidth((newX * 1.36).toInt()), speedStatTextLocation.y + imageUtils.relHeight((newY * 1.50).toInt()), "training_option_circular")
							}
						} else {
							tap(speedStatTextLocation.x + imageUtils.relWidth(newX.toInt()), speedStatTextLocation.y + imageUtils.relHeight(newY), "training_option_circular")
						}
						
						failureChance = imageUtils.findTrainingFailureChance()
						initialStatWeight = imageUtils.findInitialStatWeight(training)
						
						printToLog("[TRAINING] $training can gain ~$initialStatWeight with $failureChance% to fail.")
						
						trainingMap[training] = mutableMapOf(
							"failureChance" to failureChance,
							"statWeight" to initialStatWeight,
						)
					}
				}
				
				printToLog("[TRAINING] Process to determine stat gains and failure percentages completed.")
			} else {
				// Clear the Training map if the bot failed to have enough energy to conduct the training.
				printToLog("[TRAINING] $failureChance% is not within acceptable range of ${maximumFailureChance}%. Proceeding to recover energy.")
				trainingMap.clear()
			}
		}
	}
	
	/**
	 * Execute the training with the highest stat weight.
	 */
	private fun executeTraining() {
		printToLog("\n[TRAINING] Now starting process to execute training...")
		var trainingSelected = ""
		var maxWeight = -1
		
		// Grab the training with the maximum weight.
		trainingMap.forEach { (statName, map) ->
			val weight = map["statWeight"]!!
			if (weight > maxWeight) {
				maxWeight = weight
				trainingSelected = statName
				previouslySelectedTraining = statName
			}
		}
		
		if (trainingSelected != "") {
			printMap()
			printToLog("[TRAINING] Executing the $trainingSelected Training.")
			findAndTapImage("training_${trainingSelected.lowercase()}", region = imageUtils.regionBottomHalf, taps = 3)
		}
		
		// Now reset the Training map.
		trainingMap.clear()
		
		printToLog("[TRAINING] Process to execute training completed.")
	}
	
	////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////////
	// Functions to handle Training Events with the help of the TextDetection class.
	
	/**
	 * Start text detection to determine what Training Event it is and the event rewards for each option.
	 * It will then select the best option according to the user's preferences. By default, it will choose the first option.
	 */
	fun handleTrainingEvent() {
		printToLog("\n[TRAINING-EVENT] Starting Training Event process...")
		
		val (eventRewards, confidence) = textDetection.start()
		
		val regex = Regex("[a-zA-Z]+")
		var optionSelected = 0
		
		// Double check if the bot is at the Main screen or not.
		if (checkMainScreen()) {
			return
		}
		
		if (eventRewards.isNotEmpty() && eventRewards[0] != "") {
			// Initialize the List.
			val selectionWeight = List(eventRewards.size) { 0 }.toMutableList()
			
			// Sum up the stat gains with additional weight applied to stats that are prioritized.
			eventRewards.forEach { reward ->
				val formattedReward: List<String> = reward.split("\n")
				
				formattedReward.forEach { line ->
					val formattedLine: String = regex
						.replace(line, "")
						.replace("(", "")
						.replace(")", "")
						.trim()
						.lowercase()

					printToLog("[TRAINING-EVENT] Original line is \"$line\".")
					printToLog("[TRAINING-EVENT] Formatted line is \"$formattedLine\".")

					var priorityStatCheck = false
					if (line.lowercase().contains("energy")) {
						val finalEnergyValue = try {
							val energyValue = if (formattedLine.contains("/")) {
								val splits = formattedLine.split("/")
								var sum = 0
								for (split in splits) {
									sum += try {
										split.trim().toInt()
									} catch (_: NumberFormatException) {
										printToLog("[WARNING] Could not convert $formattedLine to a number for energy with a forward slash.")
										20
									}
								}
								sum
							} else {
								formattedLine.toInt()
							}

							if (enablePrioritizeEnergyOptions) {
								energyValue * 100
							} else {
								energyValue * 3
							}
						} catch (_: NumberFormatException) {
							printToLog("[WARNING] Could not convert $formattedLine to a number for energy.")
							20
						}
						printToLog("[TRAINING-EVENT] Adding weight for option #${optionSelected + 1} of $finalEnergyValue for energy.")
						selectionWeight[optionSelected] += finalEnergyValue
					} else if (line.lowercase().contains("bond")) {
						printToLog("[TRAINING-EVENT] Adding weight for option #${optionSelected + 1} of 20 for bond.")
						selectionWeight[optionSelected] += 20
					} else if (line.lowercase().contains("event chain ended")) {
						printToLog("[TRAINING-EVENT] Adding weight for option #${optionSelected + 1} of -50 for event chain ending.")
						selectionWeight[optionSelected] += -50
					} else if (line.lowercase().contains("(random)")) {
						printToLog("[TRAINING-EVENT] Adding weight for option #${optionSelected + 1} of -50 for random reward.")
						selectionWeight[optionSelected] += -50
					} else if (line.lowercase().contains("randomly")) {
						printToLog("[TRAINING-EVENT] Adding weight for option #${optionSelected + 1} of 50 for random options.")
						selectionWeight[optionSelected] += 50
					} else if (line.lowercase().contains("hint")) {
						printToLog("[TRAINING-EVENT] Adding weight for option #${optionSelected + 1} of 25 for skill hint(s).")
						selectionWeight[optionSelected] += 25
					} else if (line.lowercase().contains("skill")) {
						val finalSkillPoints = if (formattedLine.contains("/")) {
							val splits = formattedLine.split("/")
							var sum = 0
							for (split in splits) {
								sum += try {
									split.trim().toInt()
								} catch (_: NumberFormatException) {
									printToLog("[WARNING] Could not convert $formattedLine to a number for skill points with a forward slash.")
									10
								}
							}
							sum
						} else {
							formattedLine.toInt()
						}
						printToLog("[TRAINING-EVENT] Adding weight for option #${optionSelected + 1} of $finalSkillPoints for skill points.")
						selectionWeight[optionSelected] += finalSkillPoints
					} else {
						// Apply inflated weights to the prioritized stats based on their order.
						statPrioritization.forEachIndexed { index, stat ->
							if (line.contains(stat)) {
								// Calculate weight bonus based on position (higher priority = higher bonus).
								val priorityBonus = when (index) {
									0 -> 50
									1 -> 40
									2 -> 30
									3 -> 20
									else -> 10
								}

								val finalStatValue = try {
									priorityStatCheck = true
									if (formattedLine.contains("/")) {
										val splits = formattedLine.split("/")
										var sum = 0
										for (split in splits) {
											sum += try {
												split.trim().toInt()
											} catch (_: NumberFormatException) {
												printToLog("[WARNING] Could not convert $formattedLine to a number for a priority stat with a forward slash.")
												10
											}
										}
										sum + priorityBonus
									} else {
										formattedLine.toInt() + priorityBonus
									}
								} catch (_: NumberFormatException) {
									printToLog("[WARNING] Could not convert $formattedLine to a number for a priority stat.")
									priorityStatCheck = false
									10
								}
								printToLog("[TRAINING-EVENT] Adding weight for option #${optionSelected + 1} of $finalStatValue for prioritized stat.")
								selectionWeight[optionSelected] += finalStatValue
							}
						}
						
						// Apply normal weights to the rest of the stats.
						if (!priorityStatCheck) {
							val finalStatValue = try {
								if (formattedLine.contains("/")) {
									val splits = formattedLine.split("/")
									var sum = 0
									for (split in splits) {
										sum += try {
											split.trim().toInt()
										} catch (_: NumberFormatException) {
											printToLog("[WARNING] Could not convert $formattedLine to a number for non-prioritized stat with a forward slash.")
											10
										}
									}
									sum
								} else {
									formattedLine.toInt()
								}
							} catch (_: NumberFormatException) {
								printToLog("[WARNING] Could not convert $formattedLine to a number for non-prioritized stat.")
								10
							}
							printToLog("[TRAINING-EVENT] Adding weight for option #${optionSelected + 1} of $finalStatValue for non-prioritized stat.")
							selectionWeight[optionSelected] += finalStatValue
						}
					}

					printToLog("[TRAINING-EVENT] Final weight for option #${optionSelected + 1} is: ${selectionWeight[optionSelected]}.")
				}
				
				optionSelected++
			}
			
			// Select the best option that aligns with the stat prioritization made in the Training options.
			var max: Int? = selectionWeight.maxOrNull()
			if (max == null) {
				max = 0
				optionSelected = 0
			} else {
				optionSelected = selectionWeight.indexOf(max)
			}

			// Print the selection weights.
			printToLog("[TRAINING-EVENT] Selection weights for each option:")
			selectionWeight.forEachIndexed { index, weight ->
				printToLog("Option ${index + 1}: $weight")
			}
			
			// Format the string to display each option's rewards.
			var eventRewardsString = ""
			var optionNumber = 1
			eventRewards.forEach { reward ->
				eventRewardsString += "Option $optionNumber: \"$reward\"\n"
				optionNumber += 1
			}
			
			val minimumConfidence = sharedPreferences.getInt("confidence", 80).toDouble() / 100.0
			val resultString = if (confidence >= minimumConfidence) {
				"[TRAINING-EVENT] For this Training Event consisting of:\n$eventRewardsString\nThe bot will select Option ${optionSelected + 1}: \"${eventRewards[optionSelected]}\" with a " +
						"selection weight of $max."
			} else {
				"[TRAINING-EVENT] Since the confidence was less than the set minimum, first option will be selected."
			}
			
			printToLog(resultString)
		} else {
			printToLog("[TRAINING-EVENT] First option will be selected since OCR failed to detect anything.")
			optionSelected = 0
		}
		
		val trainingOptionLocations: ArrayList<Point> = imageUtils.findAll("training_event_active")
		val selectedLocation: Point? = if (trainingOptionLocations.isNotEmpty()) {
			// Account for the situation where it could go out of bounds if the detected event options is incorrect and gives too many results.
			try {
				trainingOptionLocations[optionSelected]
			} catch (_: IndexOutOfBoundsException) {
				// Default to the first option.
				trainingOptionLocations[0]
			}
		} else {
			imageUtils.findImage("training_event_active", tries = 5, region = imageUtils.regionMiddle).first
		}
		
		if (selectedLocation != null) {
			tap(selectedLocation.x + imageUtils.relWidth(100), selectedLocation.y, "training_event_active")
		}
		
		printToLog("[TRAINING-EVENT] Process to handle detected Training Event completed.")
	}
	
	////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////////
	// Functions to handle Race Events.
	
	/**
	 * The entry point for handling mandatory or extra races.
	 *
	 * @return True if the mandatory/extra race was completed successfully. Otherwise false.
	 */
	fun handleRaceEvents(): Boolean {
		printToLog("\n[RACE] Starting Racing process...")
		if (failedFanCheck) {
			// Dismiss the insufficient fans popup here and head to the Race Selection screen.
			findAndTapImage("race_confirm", tries = 1, region = imageUtils.regionBottomHalf, suppressError = true)
			failedFanCheck = false
			wait(1.0)
		}
		
		// First, check if there is a mandatory or a extra race available. If so, head into the Race Selection screen.
		// Note: If there is a mandatory race, the bot would be on the Home screen.
		// Otherwise, it would have found itself at the Race Selection screen already (by way of the insufficient fans popup).
		if (findAndTapImage("race_select_mandatory", tries = 1, region = imageUtils.regionBottomHalf)) {
			printToLog("\n[RACE] Starting process for handling a mandatory race.")
			
			if (enableStopOnMandatoryRace) {
				detectedMandatoryRaceCheck = true
				return false
			}
			
			// There is a mandatory race. Now confirm the selection and the resultant popup and then wait for the game to load.
			wait(2.0)
			printToLog("[RACE] Confirming the mandatory race selection.")
			findAndTapImage("race_confirm", tries = 3, region = imageUtils.regionBottomHalf)
			wait(1.0)
			printToLog("[RACE] Confirming any popup from the mandatory race selection.")
			findAndTapImage("race_confirm", tries = 3, region = imageUtils.regionBottomHalf)
			wait(2.0)

			waitForLoading()
			
			// Skip the race if possible, otherwise run it manually.
			val resultCheck: Boolean = if (imageUtils.findImage("race_skip_locked", tries = 5, region = imageUtils.regionBottomHalf).first == null) {
				skipRace()
			} else {
				manualRace()
			}
			
			finishRace(resultCheck)
			
			printToLog("[RACE] Racing process for Mandatory Race is completed.")
			return true
		} else if (findAndTapImage("race_select_extra", tries = 1, region = imageUtils.regionBottomHalf)) {
			printToLog("\n[RACE] Starting process for handling a extra race.")
			
			// If there is a popup warning about repeating races 3+ times, stop the process and do something else other than racing.
			if (imageUtils.findImage("race_repeat_warning").first != null) {
				raceRepeatWarningCheck = true
				printToLog("\n[RACE] Closing popup warning of doing more than 3+ races and setting flag to prevent racing for now.")
				findAndTapImage("cancel", region = imageUtils.regionBottomHalf)
				return false
			}
			
			// There is a extra race.
			// Swipe up the list to get to the top and then select the first option.
			val statusLocation = imageUtils.findImage("race_status").first
			if (statusLocation == null) {
				printToLog("[WARNING] Unable to determine existence of list of extra races.")
				return false
			}
			gestureUtils.swipe(statusLocation.x.toFloat(), statusLocation.y.toFloat() + 300, statusLocation.x.toFloat(), statusLocation.y.toFloat() + 888)
			wait(0.5)
			
			// Now determine the best extra race with the following parameters: highest fans and double star prediction.
			// First find the fans of only the extra races on the screen that match the double star prediction. Read only 3 extra races.
			var count = 0
			val maxCount = imageUtils.findAll("race_selection_fans", region = imageUtils.regionBottomHalf).size
			if (maxCount == 0) {
				printToLog("[WARNING] Was unable to find any extra races to select. Moving on...")
				return false
			} else {
				printToLog("[RACE] There are $maxCount extra race options currently on screen.")
			}
			val listOfFans = mutableListOf<Int>()
			val extraRaceLocation = mutableListOf<Point>()
			val (sourceBitmap, templateBitmap) = imageUtils.getBitmaps("race_extra_double_prediction")
			while (count < maxCount - 1) {
				// Save the location of the selected extra race.
				val selectedExtraRace = imageUtils.findImage("race_extra_selection", region = imageUtils.regionBottomHalf).first
				if (selectedExtraRace == null) {
					printToLog("[ERROR] Unable to find the location of the selected extra race. Will skip racing...", isError = true)
					break
				}
				extraRaceLocation.add(selectedExtraRace)
				
				// Determine its fan gain and save it.
				val fans = imageUtils.determineExtraRaceFans(extraRaceLocation[count], sourceBitmap!!, templateBitmap!!)
				if (count == 0 && fans == -1) {
					// If the fans were unable to be fetched or the race does not have double predictions for the first attempt, skip racing altogether.
					listOfFans.add(fans)
					break
				}
				listOfFans.add(fans)
				
				// Select the next extra race.
                if (imageUtils.isTablet) {
                    tap(extraRaceLocation[count].x - imageUtils.relWidth((100 * 1.36).toInt()), extraRaceLocation[count].y + imageUtils.relHeight((150 * 1.50).toInt()), "race_extra_selection")
                } else {
                    tap(extraRaceLocation[count].x - imageUtils.relWidth(100), extraRaceLocation[count].y + imageUtils.relHeight(150), "race_extra_selection")
                }

                wait(0.5)

                count++
			}
			
			val fansList = listOfFans.joinToString(", ") { it.toString() }
			printToLog("[RACE] Number of fans detected for each extra race are: $fansList")
			
			// Next determine the maximum fans and select the extra race.
			val maxFans: Int? = listOfFans.maxOrNull()
			if (maxFans != null) {
				if (maxFans == -1) {
					printToLog("[WARNING] Max fans was returned as -1.")
					findAndTapImage("back", tries = 5, region = imageUtils.regionBottomHalf)
					return false
				}
				
				// Get the index of the maximum fans.
				val index = listOfFans.indexOf(maxFans)
				
				printToLog("[RACE] Selecting the extra race at option #${index + 1}.")

				// Select the extra race that matches the double star prediction and the most fan gain.
				tap(extraRaceLocation[index].x - imageUtils.relWidth((100 * 1.36).toInt()), extraRaceLocation[index].y - imageUtils.relHeight(70), "race_extra_selection")
			} else if (extraRaceLocation.isNotEmpty()) {
				// If no maximum is determined, select the very first extra race.
				printToLog("[RACE] Selecting the first extra race on the list by default.")
				tap(extraRaceLocation[0].x - imageUtils.relWidth((100 * 1.36).toInt()), extraRaceLocation[0].y - imageUtils.relHeight(70), "race_extra_selection")
			} else {
				printToLog("[WARNING] No extra races detected and thus no fan maximums were calculated.")
				findAndTapImage("back", tries = 5, region = imageUtils.regionBottomHalf)
				return false
			}
			
			// Confirm the selection and the resultant popup and then wait for the game to load.
			findAndTapImage("race_confirm", tries = 30, region = imageUtils.regionBottomHalf)
			findAndTapImage("race_confirm", tries = 10, region = imageUtils.regionBottomHalf)
			wait(2.0)
			
			// Skip the race if possible, otherwise run it manually.
			val resultCheck: Boolean = if (imageUtils.findImage("race_skip_locked", tries = 5, region = imageUtils.regionBottomHalf).first == null) {
				skipRace()
			} else {
				manualRace()
			}
			
			finishRace(resultCheck, isExtra = true)
			
			printToLog("[RACE] Racing process for Extra Race is completed.")
			return true
		}
		
		return false
	}
	
	/**
	 * The entry point for handling standalone races if the user started the bot on the Racing screen.
	 */
	fun handleStandaloneRace() {
		printToLog("\n[RACE] Starting Standalone Racing process...")
		
		// Skip the race if possible, otherwise run it manually.
		val resultCheck: Boolean = if (imageUtils.findImage("race_skip_locked", tries = 5, region = imageUtils.regionBottomHalf).first == null) {
			skipRace()
		} else {
			manualRace()
		}
		
		finishRace(resultCheck)
		
		printToLog("[RACE] Racing process for Standalone Race is completed.")
	}
	
	/**
	 * Skips the current race to get to the results screen.
	 *
	 * @return True if the bot completed the race with retry attempts remaining. Otherwise false.
	 */
	private fun skipRace(): Boolean {
		while (raceRetries >= 0) {
			printToLog("[RACE] Skipping race...")
			
			// Press the skip button and then wait for your result of the race to show.
			wait(2.0)
			if (findAndTapImage("race_skip", tries = 30, region = imageUtils.regionBottomHalf)) {
				printToLog("[RACE] Race was able to be skipped.")
			}
			wait(2.0)

			// Now tap on the screen to get past the Race Result screen.
			tap(350.0, 450.0, "ok", taps = 3)
			
			// Check if the race needed to be retried.
			if (findAndTapImage("race_retry", tries = 5, region = imageUtils.regionBottomHalf, suppressError = true)) {
				printToLog("[RACE] The skipped race failed and needs to be run again. Attempting to retry...")
				wait(5.0)
				raceRetries--
			} else {
				return true
			}
		}
		
		return false
	}
	
	/**
	 * Manually runs the current race to get to the results screen.
	 *
	 * @return True if the bot completed the race with retry attempts remaining. Otherwise false.
	 */
	private fun manualRace(): Boolean {
		while (raceRetries >= 0) {
			printToLog("[RACE] Skipping manual race...")
			
			// Press the manual button.
			if (findAndTapImage("race_manual", tries = 30, region = imageUtils.regionBottomHalf)) {
				printToLog("[RACE] Started the manual race.")
			}
			wait(2.0)

			// Confirm the Race Playback popup if it appears.
			if (findAndTapImage("ok", tries = 1, region = imageUtils.regionMiddle, suppressError = true)) {
				printToLog("[RACE] Confirmed the Race Playback popup.")
				wait(5.0)
			}

			waitForLoading()
			
			// Now press the confirm button to get past the list of participants.
			if (findAndTapImage("race_confirm", tries = 30, region = imageUtils.regionBottomHalf)) {
				printToLog("[RACE] Dismissed the list of participants.")
			}
			waitForLoading()
			wait(1.0)
			waitForLoading()
			wait(1.0)

			// Skip the part where it reveals the name of the race.
			if (findAndTapImage("race_skip_manual", tries = 30, region = imageUtils.regionBottomHalf)) {
				printToLog("[RACE] Skipped the name reveal of the race.")
			}
			// Skip the walkthrough of the starting gate.
			if (findAndTapImage("race_skip_manual", tries = 30, region = imageUtils.regionBottomHalf)) {
				printToLog("[RACE] Skipped the walkthrough of the starting gate.")
			}
			wait(3.0)
			// Skip the start of the race.
			if (findAndTapImage("race_skip_manual", tries = 30, region = imageUtils.regionBottomHalf)) {
				printToLog("[RACE] Skipped the start of the race.")
			}
			// Skip the lead up to the finish line.
			if (findAndTapImage("race_skip_manual", tries = 30, region = imageUtils.regionBottomHalf)) {
				printToLog("[RACE] Skipped the lead up to the finish line.")
			}
			wait(2.0)
			// Skip the result screen.
			if (findAndTapImage("race_skip_manual", tries = 30, region = imageUtils.regionBottomHalf)) {
				printToLog("[RACE] Skipped the results screen.")
			}
			wait(2.0)

			waitForLoading()
			wait(1.0)
			
			// Check if the race needed to be retried.
			if (findAndTapImage("race_retry", tries = 5, region = imageUtils.regionBottomHalf, suppressError = true)) {
				printToLog("[RACE] Manual race failed and needs to be run again. Attempting to retry...")
				wait(5.0)
				raceRetries--
			} else {
				// Check if a Trophy was acquired.
				if (findAndTapImage("race_accept_trophy", tries = 5, region = imageUtils.regionBottomHalf)) {
					printToLog("[RACE] Closing popup to claim trophy...")
				}
				
				return true
			}
		}
		
		return false
	}
	
	/**
	 * Finishes up and confirms the results of the race and its success.
	 *
	 * @param resultCheck Flag to see if the race was completed successfully. Throws an IllegalStateException if it did not.
	 * @param isExtra Flag to determine the following actions to finish up this mandatory or extra race.
	 */
	private fun finishRace(resultCheck: Boolean, isExtra: Boolean = false) {
		printToLog("\n[RACE] Now performing cleanup and finishing the race.")
		if (!resultCheck) {
			notificationMessage = "Bot has run out of retry attempts for racing. Stopping the bot now..."
			throw IllegalStateException()
		}

		tap(450.0, 850.0, "ok", taps = 3)
		
		// Bot will be at the screen where it shows the final positions of all participants.
		// Press the confirm button and wait to see the triangle of fans.
		printToLog("[RACE] Now attempting to confirm the final positions of all participants and number of gained fans")
		if (findAndTapImage("next", tries = 30, region = imageUtils.regionBottomHalf)) {
			wait(0.5)

			// Now tap on the screen to get to the next screen.
			tap(350.0, 750.0, "ok", taps = 3)
			
			// Now press the end button to finish the race.
			findAndTapImage("race_end", tries = 30, region = imageUtils.regionBottomHalf)
			
			if (!isExtra) {
				printToLog("[RACE] Seeing if a Training Goal popup will appear.")
				// Wait until the popup showing the completion of a Training Goal appears and confirm it.
				// There will be dialog before it so the delay should be longer.
				wait(5.0)
				if (findAndTapImage("next", tries = 10, region = imageUtils.regionBottomHalf)) {
					wait(2.0)

					// Now confirm the completion of a Training Goal popup.
					printToLog("[RACE] There was a Training Goal popup. Confirming it now.")
					findAndTapImage("race_end", tries = 10, region = imageUtils.regionBottomHalf)
				}
			} else if (findAndTapImage("next", tries = 10, region = imageUtils.regionBottomHalf)) {
				// Same as above but without the longer delay.
				wait(2.0)
				findAndTapImage("race_end", tries = 10, region = imageUtils.regionBottomHalf)
			}
		} else {
			printToLog("[ERROR] Cannot start the cleanup process for finishing the race. Moving on...", isError = true)
		}
	}
	
	////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////////
	// Helper Functions
	
	/**
	 * Handles the Inheritance event if detected on the screen.
	 *
	 * @return True if the Inheritance event happened and was accepted. Otherwise false.
	 */
	fun handleInheritanceEvent(): Boolean {
		return if (inheritancesDone < 2) {
			if (findAndTapImage("inheritance", tries = 1, region = imageUtils.regionBottomHalf)) {
				inheritancesDone++
				true
			} else {
				false
			}
		} else {
			false
		}
	}
	
	/**
	 * Attempt to recover energy.
	 *
	 * @return True if the bot successfully recovered energy. Otherwise false.
	 */
	private fun recoverEnergy(): Boolean {
		printToLog("\n[ENERGY] Now starting attempt to recover energy.")
		return when {
			findAndTapImage("recover_energy", tries = 1, imageUtils.regionBottomHalf) -> {
				findAndTapImage("ok")
				printToLog("[ENERGY] Successfully recovered energy.")
				raceRepeatWarningCheck = false
				true
			}
			findAndTapImage("recover_energy_summer", tries = 1, imageUtils.regionBottomHalf) -> {
				findAndTapImage("ok")
				printToLog("[ENERGY] Successfully recovered energy for the Summer.")
				raceRepeatWarningCheck = false
				true
			}
			else -> {
				printToLog("[ENERGY] Failed to recover energy. Moving on...")
				false
			}
		}
	}
	
	/**
	 * Attempt to recover mood to always maintain at least Above Normal mood.
	 *
	 * @return True if the bot successfully recovered mood. Otherwise false.
	 */
	fun recoverMood(): Boolean {
		printToLog("\n[MOOD] Detecting current mood.")

		// Detect what Mood the bot is at.
		val currentMood: String = when {
			imageUtils.findImage("mood_normal", tries = 1, region = imageUtils.regionTopHalf, suppressError = true).first != null -> {
				"Normal"
			}
			imageUtils.findImage("mood_good", tries = 1, region = imageUtils.regionTopHalf, suppressError = true).first != null -> {
				"Good"
			}
			imageUtils.findImage("mood_great", tries = 1, region = imageUtils.regionTopHalf, suppressError = true).first != null -> {
				"Great"
			}
			else -> {
				"Bad/Awful"
			}
		}

		printToLog("[MOOD] Detected mood to be $currentMood.")

		// Only recover mood if its below Good mood and its not Summer.
		return if (firstTrainingCheck && currentMood == "Normal" && imageUtils.findImage("recover_energy_summer", tries = 1, region = imageUtils.regionBottomHalf, suppressError = true).first == null) {
			printToLog("[MOOD] Current mood is Normal. Not recovering mood due to firstTrainingCheck flag being active. Will need to complete a training first before being allowed to recover mood.")
			false
		} else if ((currentMood == "Bad/Awful" || currentMood == "Normal") && imageUtils.findImage("recover_energy_summer", tries = 1, region = imageUtils.regionBottomHalf, suppressError = true).first == null) {
			printToLog("[MOOD] Current mood is not good. Recovering mood now.")
			if (!findAndTapImage("recover_mood", tries = 1, region = imageUtils.regionBottomHalf, suppressError = true)) {
				findAndTapImage("recover_energy_summer", tries = 1, region = imageUtils.regionBottomHalf, suppressError = true)
			}

			// Do the date if it is unlocked.
			if (findAndTapImage("recover_mood_date", tries = 1, region = imageUtils.regionMiddle, suppressError = true)) {
				wait(1.0)
			}

			findAndTapImage("ok", region = imageUtils.regionMiddle, suppressError = true)
			raceRepeatWarningCheck = false
			true
		} else {
			printToLog("[MOOD] Current mood is good enough or its the Summer event. Moving on...")
			false
		}
	}
	
	/**
	 * Prints the training map object for informational purposes.
	 */
	private fun printMap() {
		printToLog("\n[INFO] Calculated Stat Weight by Training:")
		trainingMap.keys.forEach { stat ->
			printToLog("\n$stat: ${trainingMap[stat]?.get("statWeight")} for ${trainingMap[stat]?.get("failureChance")}%")
		}
	}
	
	/**
	 * Perform misc checks to potentially fix instances where the bot is stuck.
	 *
	 * @return True if the checks passed. Otherwise false if the bot encountered a warning popup and needs to exit.
	 */
	fun performMiscChecks(): Boolean {
		printToLog("\n[INFO] Beginning check for misc cases...")

		if (enablePopupCheck && imageUtils.findImage("cancel", tries = 1, region = imageUtils.regionBottomHalf).first != null &&
			imageUtils.findImage("recover_mood_date", tries = 1, region = imageUtils.regionMiddle).first == null) {
			printToLog("\n[END] Bot may have encountered a warning popup. Exiting now...")
			notificationMessage = "Bot may have encountered a warning popup"
			return false
		} else if (findAndTapImage("next", tries = 1, region = imageUtils.regionBottomHalf)) {
			// Now confirm the completion of a Training Goal popup.
			wait(2.0)
			findAndTapImage("next", tries = 1, region = imageUtils.regionBottomHalf)
			wait(1.0)
		} else if (imageUtils.findImage("crane_game", tries = 1, region = imageUtils.regionBottomHalf).first != null) {
			// Stop when the bot has reached the Crane Game Event.
			printToLog("\n[END] Bot will stop due to the detection of the Crane Game Event. Please complete it and restart the bot.")
			notificationMessage = "Bot will stop due to the detection of the Crane Game Event. Please complete it and restart the bot."
			return false
		} else if (findAndTapImage("race_retry", tries = 1, region = imageUtils.regionBottomHalf, suppressError = true)) {
			printToLog("[INFO] There is a race retry popup.")
			wait(5.0)
		} else if (findAndTapImage("race_accept_trophy", tries = 1, region = imageUtils.regionBottomHalf, suppressError = true)) {
			printToLog("[INFO] There is a possible popup to accept a trophy.")
			finishRace(true, isExtra = true)
		} else if (findAndTapImage("race_end", tries = 1, region = imageUtils.regionBottomHalf, suppressError = true)) {
			printToLog("[INFO] Ended a leftover race.")
		} else if (imageUtils.findImage("connection_error", tries = 1, region = imageUtils.regionMiddle, suppressError = true).first != null) {
			printToLog("\n[END] Bot will stop due to detecting a connection error.")
			notificationMessage = "Bot will stop due to detecting a connection error."
			return false
		} else if (!BotService.isRunning) {
			throw InterruptedException()
		} else {
			printToLog("[INFO] Did not detect any popups or the Crane Game on the screen. Moving on...")
		}
		
		return true
	}
	
	/**
	 * Bot will begin automation here.
	 *
	 * @return True if all automation goals have been met. False otherwise.
	 */
	fun start(): Boolean {
		// Set default values for Stat Prioritization if its empty.
		if (statPrioritization.isEmpty() || statPrioritization[0] == "") {
			statPrioritization = listOf("Speed", "Stamina", "Power", "Guts", "Wit")
		}
		
		// Print current app settings at the start of the run.
		SettingsPrinter.printCurrentSettings(myContext) { message ->
			printToLog(message)
		}
		
		// If debug mode is off, then it is necessary to wait a few seconds for the Toast message to disappear from the screen to prevent it obstructing anything beneath it.
		if (!debugMode) {
			wait(3.0)
		}

		// Print device and version information.
		printToLog("[INFO] Device Information: ${MediaProjectionService.displayWidth}x${MediaProjectionService.displayHeight}, DPI ${MediaProjectionService.displayDPI}")
		if (MediaProjectionService.displayWidth != 1080) printToLog("[WARNING] ⚠️ Bot performance will be severely degraded since display width is not 1080p unless an appropriate scale is set for your device.")
		if (debugMode) printToLog("[WARNING] ⚠️ Debug Mode is enabled. All bot operations will be significantly slower as a result.")
		if (sharedPreferences.getInt("customScale", 100).toDouble() / 100.0 != 1.0) printToLog("[INFO] Manual scale has been set to ${sharedPreferences.getInt("customScale", 100).toDouble() / 100.0}")
		val packageInfo = myContext.packageManager.getPackageInfo(myContext.packageName, 0)
		printToLog("[INFO] Bot version: ${packageInfo.versionName} (${packageInfo.versionCode})\n\n")
		
		val startTime: Long = System.currentTimeMillis()

		// Start debug tests here if enabled.
		if (sharedPreferences.getBoolean("debugMode_startTemplateMatchingTest", false)) {
			startTemplateMatchingTest()
		} else if (sharedPreferences.getBoolean("debugMode_startSingleTrainingFailureOCRTest", false)) {
			startSingleTrainingFailureOCRTest()
		} else if (sharedPreferences.getBoolean("debugMode_startComprehensiveTrainingFailureOCRTest", false)) {
			startComprehensiveTrainingFailureOCRTest()
		}
		// Otherwise, proceed with regular bot operations.
		else if (campaign == "Ao Haru") {
			val aoHaruCampaign = AoHaru(this)
			aoHaruCampaign.start()
		} else {
			val uraFinaleCampaign = Campaign(this)
			uraFinaleCampaign.start()
		}
		
		val endTime: Long = System.currentTimeMillis()
		Log.d(tag, "Total Runtime: ${endTime - startTime}ms")
		
		return true
	}
}