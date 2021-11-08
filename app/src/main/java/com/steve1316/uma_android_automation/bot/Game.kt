package com.steve1316.uma_android_automation.bot

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.campaigns.AoHaru
import com.steve1316.uma_android_automation.bot.campaigns.Normal
import com.steve1316.uma_android_automation.utils.ImageUtils
import com.steve1316.uma_android_automation.utils.MessageLog
import com.steve1316.uma_android_automation.utils.MyAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.opencv.core.Point
import java.util.concurrent.TimeUnit

/**
 * Main driver for bot activity and navigation.
 */
class Game(val myContext: Context) {
	private val tag: String = "[${MainActivity.loggerTag}]Game"
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
	private val trainings: List<String> = listOf("Speed", "Stamina", "Power", "Guts", "Intelligence")
	private val trainingMap: MutableMap<String, MutableMap<String, Int>> = mutableMapOf()
	private val blacklist: List<String> = sharedPreferences.getStringSet("trainingBlacklist", setOf())!!.toList()
	private var statPrioritization: List<String> = sharedPreferences.getString("statPrioritization", "")!!.split("|")
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
	 *
	 * @param seconds Number of seconds to pause execution.
	 */
	fun wait(seconds: Double) {
		runBlocking {
			delay((seconds * 1000).toLong())
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
			gestureUtils.tap(tempLocation.x, tempLocation.y, imageName, taps = taps)
			true
		} else {
			false
		}
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
		return if (imageUtils.findImage("tazuna", tries = 5, region = imageUtils.regionTopHalf).first != null &&
			imageUtils.findImage("race_select_mandatory", tries = 5, region = imageUtils.regionBottomHalf).first == null) {
			printToLog("\n[INFO] Current bot location is at Main screen.")
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
		return if (imageUtils.findImage("training_event_active", tries = 1, region = imageUtils.regionMiddle).first != null) {
			printToLog("\n[INFO] Current bot location is at Training Event screen.")
			true
		} else {
			false
		}
	}
	
	/**
	 * Checks if the bot is at the preparatory screen with a mandatory race needing to be completed.
	 *
	 * @return True if the bot is at the Main screen with a mandatory race. Otherwise false.
	 */
	fun checkMandatoryRacePrepScreen(): Boolean {
		return if (imageUtils.findImage("race_select_mandatory", tries = 1, region = imageUtils.regionBottomHalf).first != null) {
			printToLog("\n[INFO] Current bot location is at the preparatory screen with a mandatory race ready to be completed.")
			true
		} else {
			false
		}
	}
	
	/**
	 * Checks if the day number is odd to be eligible to run an extra race.
	 *
	 * @return True if the day number is odd. Otherwise false.
	 */
	fun checkExtraRaceAvailability(): Boolean {
		val dayNumber = imageUtils.determineDayForExtraRace()
		printToLog("\n[INFO] Current Day number is: $dayNumber.")
		
		return enableFarmingFans && dayNumber % daysToRunExtraRaces == 0 && !raceRepeatWarningCheck &&
				imageUtils.findImage("race_select_extra_locked_uma_finals", tries = 1, region = imageUtils.regionBottomHalf).first == null &&
				imageUtils.findImage("race_select_extra_locked", tries = 1, region = imageUtils.regionBottomHalf).first == null
	}
	
	/**
	 * Checks if the bot is at the Ending screen detailing the overall results of the run.
	 *
	 * @return True if the bot is at the Ending screen. Otherwise false.
	 */
	fun checkEndScreen(): Boolean {
		return if (imageUtils.findImage("end", tries = 1, region = imageUtils.regionBottomHalf).first != null) {
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
		return if (findAndTapImage("recover_injury", tries = 1, region = imageUtils.regionBottomHalf)) {
			if (imageUtils.confirmLocation("recover_injury", tries = 1, region = imageUtils.regionMiddle)) {
				printToLog("\n[INFO] Injury detected and attempted to heal.")
				true
			} else {
				printToLog("\n[INFO] No injury detected.")
				false
			}
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
				findAndTapImage("back", region = imageUtils.regionBottomHalf)
				wait(1.0)
				
				if (checkMainScreen()) {
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
	 */
	private fun findStatsAndPercentages() {
		printToLog("\n[TRAINING] Checking for success percentages and total stat increases for training selection...")
		
		// Acquire the position of the speed stat text.
		val (speedStatTextLocation, _) = if (campaign == "Ao Haru") {
			imageUtils.findImage("aoharu_stat_speed", region = imageUtils.regionBottomHalf)
		} else {
			imageUtils.findImage("stat_speed", region = imageUtils.regionBottomHalf)
		}
		
		if (speedStatTextLocation != null) {
			// Perform a percentage check of Speed training to see if the bot has enough energy to do training. As a result, Speed training will be the one selected for the rest of the algorithm.
			if (!imageUtils.confirmLocation("speed_training", tries = 5, region = imageUtils.regionTopHalf)) {
				findAndTapImage("training_speed")
			}
			
			var failureChance: Int = imageUtils.findTrainingFailureChance()
			
			if (failureChance <= maximumFailureChance) {
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
								gestureUtils.tap(speedStatTextLocation.x + (newX * 1.05), speedStatTextLocation.y + (newY * 1.50), "training_option_circular")
							} else {
								gestureUtils.tap(speedStatTextLocation.x + (newX * 1.36), speedStatTextLocation.y + (newY * 1.50), "training_option_circular")
							}
						} else {
							gestureUtils.tap(speedStatTextLocation.x + newX, speedStatTextLocation.y + newY, "training_option_circular")
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
		printToLog("[TRAINING] Now starting process to execute training...")
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
			val selectionWeight = mutableListOf<Int>()
			for (i in 1..(eventRewards.size)) {
				selectionWeight.add(0)
			}
			
			// Sum up the stat gains with additional weight applied to stats that are prioritized.
			eventRewards.forEach { reward ->
				val formattedReward: List<String> = reward.split("\n")
				
				formattedReward.forEach { line ->
					val formattedLine: String = regex.replace(line, "").replace("+", "").replace("(", "").replace(")", "").trim()
					
					var statCheck = false
					if (!line.contains("Skill")) {
						// Apply inflated weights to the prioritized stats.
						statPrioritization.forEach { stat ->
							if (line.contains(stat)) {
								selectionWeight[optionSelected] += try {
									statCheck = true
									formattedLine.toInt() * 4
								} catch (e: NumberFormatException) {
									statCheck = false
									0
								}
							}
						}
						
						// Apply normal weights to the rest of the stats.
						if (!statCheck) {
							selectionWeight[optionSelected] += try {
								formattedLine.toInt() * 2
							} catch (e: NumberFormatException) {
								0
							}
						}
					} else if (line.contains("Energy")) {
						selectionWeight[optionSelected] += try {
							formattedLine.toInt()
						} catch (e: NumberFormatException) {
							10
						}
					} else if (line.lowercase().contains("event chain ended")) {
						selectionWeight[optionSelected] += -50
					} else if (line.lowercase().contains("one of these will be selected at random")) {
						selectionWeight[optionSelected] += 50
					}
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
			} catch (e: IndexOutOfBoundsException) {
				// Default to the first option.
				trainingOptionLocations[0]
			}
		} else {
			imageUtils.findImage("training_event_active", tries = 5, region = imageUtils.regionMiddle).first
		}
		
		if (selectedLocation != null) {
			gestureUtils.tap(selectedLocation.x + 100, selectedLocation.y, "training_event_active")
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
		
		// First, check if there is a mandatory or a extra race available. If so, head into the Race Selection screen.
		if (findAndTapImage("race_select_mandatory", tries = 1, region = imageUtils.regionBottomHalf)) {
			printToLog("\n[RACE] Detected mandatory race.")
			
			if (enableStopOnMandatoryRace) {
				detectedMandatoryRaceCheck = true
				return false
			}
			
			// There is a mandatory race. Now confirm the selection and the resultant popup and then wait for the game to load.
			wait(1.0)
			findAndTapImage("race_confirm", tries = 5, region = imageUtils.regionBottomHalf)
			findAndTapImage("race_confirm", tries = 5, region = imageUtils.regionBottomHalf)
			wait(5.0)
			
			// Skip the race if possible, otherwise run it manually.
			val resultCheck: Boolean = if (imageUtils.findImage("race_skip_locked", region = imageUtils.regionBottomHalf).first == null) {
				skipRace()
			} else {
				manualRace()
			}
			
			finishRace(resultCheck, isExtra = true)
			
			printToLog("[RACE] Racing process for Mandatory Race is completed.")
			return true
		} else if (findAndTapImage("race_select_extra", region = imageUtils.regionBottomHalf)) {
			printToLog("\n[RACE] Detected extra race eligibility.")
			
			// If there is a popup warning about repeating races 3+ times, stop the process and do something else other than racing.
			if (imageUtils.findImage("race_repeat_warning").first != null) {
				raceRepeatWarningCheck = true
				printToLog("\n[RACE] Closing popup of repeat warning and setting flag to prevent racing for now.")
				findAndTapImage("cancel", region = imageUtils.regionBottomHalf)
				return false
			}
			
			// There is a extra race.
			// Swipe up the list to get to the top and then select the first option.
			val statusLocation = imageUtils.findImage("status").first!!
			gestureUtils.swipe(statusLocation.x.toFloat(), statusLocation.y.toFloat() + 300, statusLocation.x.toFloat(), statusLocation.y.toFloat() + 888)
			wait(0.5)
			
			if (imageUtils.isTablet) {
				gestureUtils.tap(statusLocation.x, statusLocation.y + (325 * 1.36), "ok")
			} else {
				gestureUtils.tap(statusLocation.x, statusLocation.y + 325, "ok")
			}
			
			// Now determine the best extra race with the following parameters: highest fans and double star prediction.
			// First find the fans of only the extra races on the screen that match the double star prediction. Read only 3 extra races.
			var count = 0
			val maxCount = if (imageUtils.isTablet) 2 else 3 // Do not bother with checking for the 3rd extra race. Easier to just check the first two if on tablet.
			val listOfFans = mutableListOf<Int>()
			val extraRaceLocation = mutableListOf<Point>()
			val (sourceBitmap, templateBitmap) = imageUtils.getBitmaps("race_extra_double_prediction")
			while (count < maxCount) {
				// Save the location of the selected extra race.
				extraRaceLocation.add(imageUtils.findImage("race_extra_selection", region = imageUtils.regionBottomHalf).first!!)
				
				// Determine its fan gain and save it.
				val fans = imageUtils.determineExtraRaceFans(extraRaceLocation[count], sourceBitmap!!, templateBitmap!!)
				listOfFans.add(fans)
				
				if (imageUtils.isTablet && count == 1) {
					break
				}
				
				// Select the next extra race.
				if (count != 2) {
					if (imageUtils.isTablet) {
						gestureUtils.tap(extraRaceLocation[count].x - (100 * 1.36), extraRaceLocation[count].y + (150 * 1.50), "race_extra_selection")
					} else {
						gestureUtils.tap(extraRaceLocation[count].x - 100, extraRaceLocation[count].y + 150, "race_extra_selection")
					}
					
					wait(0.5)
				}
				
				count++
			}
			
			if (imageUtils.isTablet) {
				printToLog("[RACE] Number of fans detected for each extra race are: Extra 1. ${listOfFans[0]}, Extra 2. ${listOfFans[1]}")
			} else {
				printToLog("[RACE] Number of fans detected for each extra race are: Extra 1. ${listOfFans[0]}, Extra 2. ${listOfFans[1]}, Extra 3. ${listOfFans[2]}")
			}
			
			// Next determine the maximum fans and select the extra race.
			val maxFans: Int? = listOfFans.maxOrNull()
			if (maxFans != null) {
				if (maxFans == -1) {
					Log.d(tag, "Max fans was -1 so returning false...")
					findAndTapImage("back", tries = 5, region = imageUtils.regionBottomHalf)
					return false
				}
				
				// Get the index of the maximum fans.
				val index = listOfFans.indexOf(maxFans)
				
				printToLog("[RACE] Selecting the Option ${index + 1} Extra Race.")
				
				// Select the extra race that matches the double star prediction and the most fan gain.
				gestureUtils.tap(extraRaceLocation[index].x - (100 * 1.36), extraRaceLocation[index].y, "race_extra_selection")
			} else {
				// If no maximum is determined, select the very first extra race.
				printToLog("[RACE] Selecting the first Extra Race by default.")
				gestureUtils.tap(extraRaceLocation[0].x - (100 * 1.36), extraRaceLocation[0].y, "race_extra_selection")
			}
			
			// Confirm the selection and the resultant popup and then wait for the game to load.
			findAndTapImage("race_confirm", tries = 30, region = imageUtils.regionBottomHalf)
			findAndTapImage("race_confirm", tries = 10, region = imageUtils.regionBottomHalf)
			afkCheck()
			wait(1.0)
			
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
		printToLog("[RACE] Starting Standalone Racing process...")
		
		// Skip the race if possible, otherwise run it manually.
		val resultCheck: Boolean = if (imageUtils.findImage("race_skip_locked", tries = 10, region = imageUtils.regionBottomHalf).first == null) {
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
			wait(3.0)
			findAndTapImage("race_skip", tries = 30, region = imageUtils.regionBottomHalf)
			wait(3.0)
			
			// Now tap on the screen to get to the next screen.
			gestureUtils.tap(500.0, 1000.0, "ok")
			wait(0.3)
			gestureUtils.tap(500.0, 1000.0, "ok")
			wait(0.3)
			gestureUtils.tap(500.0, 1000.0, "ok")
			wait(2.0)
			
			// Check if the race needed to be retried.
			if (findAndTapImage("race_retry", tries = 10, region = imageUtils.regionBottomHalf)) {
				printToLog("[RACE] Skipped race failed. Attempting to retry...")
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
			findAndTapImage("race_manual", tries = 30, region = imageUtils.regionBottomHalf)
			wait(2.0)
			
			// Now press the confirm button to get past the list of participants.
			findAndTapImage("race_confirm", tries = 30, region = imageUtils.regionBottomHalf)
			wait(1.0)
			
			// Now skip to the end of the race.
			findAndTapImage("race_skip_manual", tries = 30, region = imageUtils.regionBottomHalf)
			findAndTapImage("race_skip_manual", tries = 30, region = imageUtils.regionBottomHalf)
			wait(1.0)
			findAndTapImage("race_skip_manual", tries = 30, region = imageUtils.regionBottomHalf)
			findAndTapImage("race_skip_manual", tries = 30, region = imageUtils.regionBottomHalf)
			findAndTapImage("race_skip_manual", tries = 30, region = imageUtils.regionBottomHalf)
			wait(2.0)
			
			// Check if the race needed to be retried.
			if (findAndTapImage("race_retry", tries = 10, region = imageUtils.regionBottomHalf)) {
				printToLog("[RACE] Manual race failed. Attempting to retry...")
				wait(2.0)
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
		if (!resultCheck) {
			throw IllegalStateException("Bot has run out of retry attempts for racing. Stopping the bot now...")
		}
		
		// Bot will be at the screen where it shows the final positions of all participants.
		// Press the confirm button and wait to see the triangle of fans.
		if (findAndTapImage("race_confirm_result", tries = 30, region = imageUtils.regionBottomHalf)) {
			wait(3.0)
			
			// Now press the end button to finish the race.
			findAndTapImage("race_end", tries = 30, region = imageUtils.regionBottomHalf)
			
			if (!isExtra) {
				// Wait until the popup showing the completion of a Training Goal appears and confirm it.
				wait(2.0)
				findAndTapImage("race_confirm_result", tries = 30, region = imageUtils.regionBottomHalf)
				wait(2.0)
				
				// Now confirm the completion of a Training Goal popup.
				findAndTapImage("race_end", tries = 30, region = imageUtils.regionBottomHalf)
			} else if (findAndTapImage("race_confirm_result", tries = 10, region = imageUtils.regionBottomHalf)) {
				// Now confirm the completion of a Training Goal popup.
				wait(2.0)
				findAndTapImage("race_end", tries = 30, region = imageUtils.regionBottomHalf)
			}
			
			wait(1.0)
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
		return when {
			findAndTapImage("recover_energy", tries = 1, imageUtils.regionBottomHalf) -> {
				findAndTapImage("ok")
				printToLog("\n[ENERGY] Successfully recovered energy.")
				raceRepeatWarningCheck = false
				true
			}
			findAndTapImage("recover_energy_summer", tries = 1, imageUtils.regionBottomHalf) -> {
				findAndTapImage("ok")
				printToLog("\n[ENERGY] Successfully recovered energy for the Summer.")
				raceRepeatWarningCheck = false
				true
			}
			else -> {
				printToLog("\n[ENERGY] Failed to recover energy. Moving on...")
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
		if (!firstTrainingCheck) {
			printToLog("\n[MOOD] Detecting current mood.")
			
			// Detect what Mood the bot is at.
			val currentMood: String = when {
				imageUtils.findImage("mood_above_normal", tries = 1, region = imageUtils.regionTopHalf).first != null -> {
					"Above Normal"
				}
				imageUtils.findImage("mood_great", tries = 1, region = imageUtils.regionTopHalf).first != null -> {
					"Great"
				}
				else -> {
					"Bad"
				}
			}
			
			// Only recover mood if its below Above Normal mood.
			return if (currentMood == "Bad" && imageUtils.findImage("recover_energy_summer", tries = 1, region = imageUtils.regionBottomHalf).first == null) {
				printToLog("[MOOD] Current mood is not good. Recovering mood now.")
				if (!findAndTapImage("recover_mood", tries = 5, region = imageUtils.regionBottomHalf)) {
					findAndTapImage("recover_energy_summer", tries = 5, region = imageUtils.regionBottomHalf)
				}
				
				// Do the date if it is unlocked.
				findAndTapImage("recover_mood_date", region = imageUtils.regionMiddle)
				
				findAndTapImage("ok", region = imageUtils.regionMiddle)
				raceRepeatWarningCheck = false
				true
			} else {
				printToLog("[MOOD] Current mood is good enough. Moving on...")
				false
			}
		}
		
		return false
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
	 * Handle the case where the bot took too long to do anything and the AFK check came up.
	 *
	 * @return True if the AFK check was completed. Otherwise false if no AFK check is encountered.
	 */
	private fun afkCheck(): Boolean {
		return findAndTapImage("afk_check", tries = 1, region = imageUtils.regionMiddle)
	}
	
	/**
	 * Perform misc checks to potentially fix instances where the bot is stuck.
	 *
	 * @return True if the checks passed. Otherwise false if the bot encountered a warning popup and needs to exit.
	 */
	fun performMiscChecks(): Boolean {
		printToLog("\n[INFO] Beginning check for misc cases...")
		
		if (afkCheck()) {
			return true
		} else if (enablePopupCheck && imageUtils.findImage("cancel", tries = 1, region = imageUtils.regionBottomHalf).first != null &&
			imageUtils.findImage("recover_mood_date", region = imageUtils.regionMiddle).first == null) {
			printToLog("\n[END] Bot may have encountered a warning popup. Exiting now...")
			return false
		} else if (findAndTapImage("race_confirm_result", tries = 1, region = imageUtils.regionBottomHalf)) {
			// Now confirm the completion of a Training Goal popup.
			wait(2.0)
			findAndTapImage("race_end", tries = 30, region = imageUtils.regionBottomHalf)
			wait(1.0)
		} else if (imageUtils.findImage("crane_game", tries = 1, region = imageUtils.regionBottomHalf).first != null) {
			// Stop when the bot has reached the Crane Game Event.
			printToLog("\n[END] Bot will stop due to the detection of the Crane Game Event. Please complete it and restart the bot.")
			return false
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
			statPrioritization = listOf("Speed", "Stamina", "Power", "Guts", "Intelligence")
		}
		
		// If debug mode is off, then it is necessary to wait a few seconds for the Toast message to disappear from the screen to prevent it obstructing anything beneath it.
		if (!debugMode) {
			wait(2.0)
		}
		
		val startTime: Long = System.currentTimeMillis()
		
		if (campaign == "Ao Haru") {
			val aoHaruCampaign = AoHaru(this)
			aoHaruCampaign.start()
		} else {
			val normalCampaign = Normal(this)
			normalCampaign.start()
		}
		
		val endTime: Long = System.currentTimeMillis()
		Log.d(tag, "Total Runtime: ${endTime - startTime}ms")
		
		return true
	}
}