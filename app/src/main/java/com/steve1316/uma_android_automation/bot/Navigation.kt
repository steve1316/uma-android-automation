package com.steve1316.uma_android_automation.bot

import android.util.Log
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.ui.settings.SettingsFragment
import com.steve1316.uma_android_automation.utils.BotService
import com.steve1316.uma_android_automation.utils.MediaProjectionService
import org.opencv.core.Point

class Navigation(val game: Game) {
	private val TAG: String = "[${MainActivity.loggerTag}]Navigation"
	
	private val displayWidth: Int = MediaProjectionService.displayWidth
	private val displayHeight: Int = MediaProjectionService.displayHeight
	
	// Define template matching regions of the screen.
	private val regionTopHalf: IntArray = intArrayOf(0, 0, displayWidth, displayHeight / 2)
	private val regionBottomHalf: IntArray = intArrayOf(0, displayHeight / 2, displayWidth, displayHeight / 2)
	private val regionTopOneThird: IntArray = intArrayOf(0, 0, displayWidth, displayHeight / 3)
	private val regionMiddleTwoThird: IntArray = intArrayOf(0, displayHeight / 3, displayWidth, displayHeight / 3)
	private val regionBottomThreeThird: IntArray = intArrayOf(0, displayHeight - (displayHeight / 3), displayWidth, displayHeight / 3)
	
	private val trainings: List<String> = listOf("Speed", "Stamina", "Power", "Guts", "Intelligence")
	private val trainingMap: MutableMap<String, MutableMap<String, Int>> = mutableMapOf()
	private val blacklist: List<String> = SettingsFragment.getStringSetSharedPreference(game.myContext, "trainingBlacklist").toList()
	private var statPrioritization: List<String> = SettingsFragment.getStringSharedPreference(game.myContext, "statPrioritization").split("|")
	private val enableFarmingFans = SettingsFragment.getBooleanSharedPreference(game.myContext, "enableFarmingFans")
	private val enableSkillPointCheck: Boolean = SettingsFragment.getBooleanSharedPreference(game.myContext, "enableSkillPointCheck")
	private val skillPointCheck: Int = SettingsFragment.getIntSharedPreference(game.myContext, "skillPointCheck")
	private val enablePopupCheck: Boolean = SettingsFragment.getBooleanSharedPreference(game.myContext, "enablePopupCheck")
	
	private var firstTrainingCheck = true
	private var previouslySelectedTraining = ""
	private var inheritancesDone = 0
	private var raceRetries = 3
	private var raceRepeatWarningCheck = false
	
	private val textDetection: TextDetection = TextDetection(game.myContext, game, game.imageUtils)
	
	////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////////
	// Functions to check what screen the bot is at.
	
	/**
	 * Checks if the bot is at the Main screen or the screen with available options to undertake.
	 * This will also make sure that the Main screen does not contain the option to select a race.
	 *
	 * @return True if the bot is at the Main screen. Otherwise false.
	 */
	private fun checkMainScreen(): Boolean {
		return game.imageUtils.findImage("tazuna", tries = 1, region = regionTopHalf).first != null
				&& game.imageUtils.findImage("race_select_mandatory", tries = 1, region = regionBottomThreeThird, suppressError = true).first == null
	}
	
	/**
	 * Checks if the bot is at the Training Event screen with an active event with options to select on screen.
	 *
	 * @return True if the bot is at the Training Event screen. Otherwise false.
	 */
	private fun checkTrainingEventScreen(): Boolean {
		return game.imageUtils.findImage("training_event_active", tries = 1, region = regionMiddleTwoThird).first != null
	}
	
	/**
	 * Checks if the bot is at the Main screen with a mandatory race needing to be completed.
	 *
	 * @return True if the bot is at the Main screen with a mandatory race. Otherwise false.
	 */
	private fun checkPreRaceScreen(): Boolean {
		return game.imageUtils.findImage("race_select_mandatory", tries = 1, region = regionBottomThreeThird).first != null
	}
	
	/**
	 * Checks if the bot encountered the popup that says there are not enough fans.
	 *
	 * @return True if the bot detected this popup. Otherwise false.
	 */
	private fun checkNotEnoughFans(): Boolean {
		return game.imageUtils.findImage("race_not_enough_fans", tries = 1, region = regionMiddleTwoThird).first != null
	}
	
	/**
	 * Checks if the day number is odd to be eligible to run an extra race.
	 *
	 * @return True if the day number is odd. Otherwise false.
	 */
	private fun checkExtraRaceAvailability(): Boolean {
		val dayNumber = game.imageUtils.determineDayForExtraRace()
		return enableFarmingFans && dayNumber % 3 == 0 && dayNumber < 10 && !raceRepeatWarningCheck &&
				game.imageUtils.findImage("race_select_extra_locked_uma_finals", tries = 1, region = regionBottomHalf).first == null &&
				game.imageUtils.findImage("race_select_extra_locked", tries = 1, region = regionBottomHalf).first == null
	}
	
	/**
	 * Checks if the bot is at the Ending screen detailing the overall results of the run.
	 *
	 * @return True if the bot is at the Ending screen. Otherwise false.
	 */
	private fun checkEndScreen(): Boolean {
		return game.imageUtils.findImage("end", tries = 1, region = regionBottomThreeThird).first != null
	}
	
	/**
	 * Checks if the bot has a injury.
	 *
	 * @return True if the bot has a injury. Otherwise false.
	 */
	private fun checkInjury(): Boolean {
		return if (game.findAndTapImage("recover_injury", tries = 1, region = regionBottomThreeThird, suppressError = true)) {
			game.imageUtils.confirmLocation("recover_injury", tries = 1, region = regionMiddleTwoThird, suppressError = true)
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
	private fun handleTraining() {
		game.printToLog("\n[TRAINING] Starting Training process...", tag = TAG)
		
		// Enter the Training screen.
		game.findAndTapImage("training_option", region = regionBottomThreeThird)
		
		// Acquire the percentages and stat gains for each training.
		findStatsAndPercentages()
		
		if (trainingMap.isEmpty()) {
			game.findAndTapImage("back", region = regionBottomThreeThird)
			
			if (checkMainScreen()) {
				recoverEnergy()
			} else {
				throw IllegalStateException("Could not head back to the Main screen in order to recover energy.")
			}
		} else {
			// Generate weights for the stats based on what settings the user set.
			createWeights()
			
			// Now select the training option with the highest weight.
			executeTraining()
			
			firstTrainingCheck = false
		}
		
		raceRepeatWarningCheck = false
		game.printToLog("\n[TRAINING] Training process completed.", tag = TAG)
	}
	
	/**
	 * Find the success percentages and stat gain for each training and assign them to the MutableMap object to be shared across the whole class.
	 */
	private fun findStatsAndPercentages() {
		game.printToLog("[TRAINING] Checking for success percentages and total stat increases for training selection.", tag = TAG)
		
		// Acquire the position of the speed stat text.
		val (speedStatTextLocation, _) = game.imageUtils.findImage("stat_speed", region = regionBottomThreeThird)
		if (speedStatTextLocation != null) {
			// Perform a percentage check of Speed training to see if the bot has enough energy to do training. As a result, Speed training will be the one selected for the rest of the algorithm.
			if (!game.imageUtils.confirmLocation("speed_training", tries = 1, region = regionTopOneThird)) {
				game.gestureUtils.tap(speedStatTextLocation.x + 19, speedStatTextLocation.y + 319, "images", "training_option_circular")
			}
			
			val speedFailureChance: Int = game.imageUtils.findTrainingFailureChance()
			val overallStatsGained: Int = game.imageUtils.findTotalStatGains("speed")
			
			if (speedFailureChance <= game.maximumFailureChance) {
				game.printToLog(
					"[TRAINING] $speedFailureChance% within acceptable range of ${game.maximumFailureChance}%. Proceeding to acquire all other percentages and total stat increases.", tag = TAG)
				
				// Save the results to the map if Speed training is not blacklisted.
				if (!blacklist.contains("Speed")) {
					trainingMap["Speed"] = mutableMapOf(
						"failureChance" to speedFailureChance,
						"totalStatGained" to overallStatsGained,
						"weight" to 0
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
						when (training) {
							"Stamina" -> {
								game.gestureUtils.tap(speedStatTextLocation.x + 212, speedStatTextLocation.y + 319, "images", "training_option_circular")
							}
							"Power" -> {
								game.gestureUtils.tap(speedStatTextLocation.x + 402, speedStatTextLocation.y + 319, "images", "training_option_circular")
							}
							"Guts" -> {
								game.gestureUtils.tap(speedStatTextLocation.x + 591, speedStatTextLocation.y + 319, "images", "training_option_circular")
								game.wait(1.0)
							}
							"Intelligence" -> {
								game.gestureUtils.tap(speedStatTextLocation.x + 779, speedStatTextLocation.y + 319, "images", "training_option_circular")
							}
						}
						
						game.wait(0.25)
						
						val failureChance: Int = game.imageUtils.findTrainingFailureChance()
						val totalStatGained: Int = game.imageUtils.findTotalStatGains(training)
						
						game.printToLog("[TRAINING] $training can gain ~$totalStatGained with $failureChance% to fail.", tag = TAG)
						
						trainingMap[training] = mutableMapOf(
							"failureChance" to failureChance,
							"totalStatGained" to totalStatGained,
							"weight" to 0
						)
					}
				}
				
				game.printToLog("[TRAINING] Process to determine stat gains and failure percentages completed.", tag = TAG)
			} else {
				// Clear the Training map if the bot failed to have enough energy to conduct the training.
				game.printToLog("[TRAINING] $speedFailureChance% is not within acceptable range of ${game.maximumFailureChance}%. Proceeding to recover energy.", tag = TAG)
				trainingMap.clear()
			}
		}
	}
	
	/**
	 * Generate the weights for each training using the settings set in the app.
	 */
	private fun createWeights() {
		game.printToLog("[TRAINING] Now starting process to assign weights to each prioritised stat.", tag = TAG)
		
		var priority = 5
		statPrioritization.forEach { statName ->
			if (trainingMap.containsKey(statName)) {
				val failureChance: Int = trainingMap[statName]?.get("failureChance")!!
				val totalStatGained: Int = trainingMap[statName]?.get("totalStatGained")!!
				val penaltyForRepeat: Int = if (previouslySelectedTraining == statName) {
					Log.d(TAG, "$statName already did training so applying penalty for repeating.")
					500
				} else {
					0
				}
				
				val weight: Int = (25 * priority) + (20 * totalStatGained) - (failureChance * 2) - penaltyForRepeat
				
				trainingMap[statName]?.set("weight", weight)
				priority--
			}
		}
		
		game.printToLog("[TRAINING] Process to assign weights to each prioritised stat completed.", tag = TAG)
	}
	
	/**
	 * Execute the training with the highest stat weight.
	 */
	private fun executeTraining() {
		game.printToLog("[TRAINING] Now starting process to execute training.", tag = TAG)
		var trainingSelected = ""
		var maxWeight = 0
		
		// Grab the training with the maximum weight.
		trainingMap.forEach { (statName, map) ->
			val weight = map["weight"]!!
			if ((maxWeight == 0 && trainingSelected == "") || weight > maxWeight) {
				maxWeight = weight
				trainingSelected = statName
				previouslySelectedTraining = statName
			}
		}
		
		if (trainingSelected != "") {
			printMap()
			game.printToLog("[TRAINING] Executing the $trainingSelected Training.", tag = TAG)
			game.findAndTapImage("training_${trainingSelected.lowercase()}", region = regionBottomThreeThird, taps = 3)
		}
		
		// Now reset the Training map.
		trainingMap.clear()
		
		game.printToLog("[TRAINING] Process to execute training completed.", tag = TAG)
	}
	
	////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////////
	// Functions to handle Training Events with the help of the TextDetection class.
	
	/**
	 * Start text detection to determine what Training Event it is and the event rewards for each option.
	 * It will then select the best option according to the user's preferences. By default, it will choose the first option.
	 */
	private fun handleTrainingEvent() {
		game.printToLog("\n[TRAINING-EVENT] Starting Training Event process...", tag = TAG)
		
		val (eventRewards, confidence) = textDetection.start()
		
		val regex = Regex("[a-zA-Z]+")
		var optionSelected = 0
		
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
			
			val minimumConfidence = SettingsFragment.getIntSharedPreference(game.myContext, "confidence").toDouble() / 100.0
			val resultString = if (confidence >= minimumConfidence) {
				"[TRAINING-EVENT] For this Training Event consisting of:\n$eventRewardsString\nThe bot will select Option ${optionSelected + 1}: \"${eventRewards[optionSelected]}\" with a " +
						"selection weight of $max."
			} else {
				"[TRAINING-EVENT] Since the confidence was less than the set minimum, first option will be selected."
			}
			
			game.printToLog(resultString, tag = TAG)
		} else {
			game.printToLog("[TRAINING-EVENT] First option will be selected since OCR failed to detect anything.", tag = TAG)
			optionSelected = 0
		}
		
		val trainingOptionLocations: ArrayList<Point> = game.imageUtils.findAll("training_event_active")
		val selectedLocation: Point? = if (trainingOptionLocations.isNotEmpty()) {
			// Account for the situation where it could go out of bounds if the detected event options is incorrect and gives too many results.
			try {
				trainingOptionLocations[optionSelected]
			} catch (e: IndexOutOfBoundsException) {
				// Default to the first option.
				trainingOptionLocations[0]
			}
		} else {
			game.imageUtils.findImage("training_event_active", tries = 5, region = regionMiddleTwoThird).first
		}
		
		if (selectedLocation != null) {
			game.gestureUtils.tap(selectedLocation.x + 100, selectedLocation.y, "images", "training_event_active")
		}
		
		game.printToLog("[TRAINING-EVENT] Process to handle detected Training Event completed.", tag = TAG)
	}
	
	////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////////
	// Functions to handle Race Events.
	
	/**
	 * The entry point for handling mandatory or extra races.
	 *
	 * @return True if the mandatory/extra race was completed successfully. Otherwise false.
	 */
	private fun handleRaceEvents(): Boolean {
		game.printToLog("\n[RACE] Starting Racing process...", tag = TAG)
		
		// First, check if there is a mandatory or a extra race available. If so, head into the Race Selection screen.
		if (game.findAndTapImage("race_select_mandatory", tries = 1, region = regionBottomHalf)) {
			game.printToLog("\n[RACE] Detected mandatory race.", tag = TAG)
			
			// There is a mandatory race. Now confirm the selection and the resultant popup and then wait for the game to load.
			game.wait(3.0)
			game.findAndTapImage("race_confirm", tries = 5, region = regionBottomHalf)
			game.findAndTapImage("race_confirm", tries = 5, region = regionBottomHalf)
			game.wait(3.0)
			
			// Skip the race if possible, otherwise run it manually.
			val resultCheck: Boolean = if (game.imageUtils.findImage("race_skip_locked", tries = 5, region = regionBottomHalf, suppressError = true).first == null) {
				skipRace()
			} else {
				manualRace()
			}
			
			finishRace(resultCheck, isExtra = true)
			
			game.printToLog("[RACE] Racing process for Mandatory Race is completed.", tag = TAG)
			return true
		} else if (game.findAndTapImage("race_select_extra", region = regionBottomHalf)) {
			game.printToLog("\n[RACE] Detected extra race eligibility.", tag = TAG)
			
			// If there is a popup warning about repeating races 3+ times, stop the process and do something else other than racing.
			if (game.imageUtils.findImage("race_repeat_warning").first != null) {
				raceRepeatWarningCheck = true
				game.printToLog("\n[RACE] Closing popup of repeat warning and setting flag to prevent racing for now.", tag = TAG)
				game.findAndTapImage("cancel", region = regionBottomHalf)
				return false
			}
			
			// There is a extra race.
			// Swipe up the list to get to the top and then select the first option.
			val statusLocation = game.imageUtils.findImage("status").first!!
			game.gestureUtils.swipe(statusLocation.x.toFloat(), statusLocation.y.toFloat() + 300, statusLocation.x.toFloat(), statusLocation.y.toFloat() + 888)
			game.wait(0.5)
			game.gestureUtils.tap(statusLocation.x, statusLocation.y + 325, "images", "ok")
			
			
			// Now determine the best extra race with the following parameters: highest fans and double star prediction.
			// First find the fans of only the extra races on the screen that match the double star prediction. Read only 3 extra races.
			var count = 0
			val listOfFans = mutableListOf<Int>()
			val extraRaceLocation = mutableListOf<Point>()
			val (sourceBitmap, templateBitmap) = game.imageUtils.getBitmaps("race_extra_double_prediction", "images")
			while (count < 3) {
				// Save the location of the selected extra race.
				extraRaceLocation.add(game.imageUtils.findImage("race_extra_selection", region = regionBottomHalf).first!!)
				
				// Determine its fan gain and save it.
				val fans = game.imageUtils.determineExtraRaceFans(extraRaceLocation[count], sourceBitmap!!, templateBitmap!!)
				listOfFans.add(fans)
				
				// Select the next extra race.
				if (count != 2) {
					game.gestureUtils.tap(extraRaceLocation[count].x - 100, extraRaceLocation[count].y + 150, "images", "race_extra_selection")
					game.wait(0.5)
				}
				
				count++
			}
			
			game.printToLog("[RACE] Number of fans detected for each extra race are: Extra 1. ${listOfFans[0]}, Extra 2. ${listOfFans[1]}, Extra 3. ${listOfFans[2]}", tag = TAG)
			
			// Next determine the maximum fans and select the extra race.
			val maxFans: Int? = listOfFans.maxOrNull()
			if (maxFans != null) {
				if (maxFans == -1) {
					Log.d(TAG, "Max fans was -1 so returning false...")
					game.findAndTapImage("back", tries = 1, region = regionBottomHalf)
					game.wait(2.0)
					return false
				}
				
				// Get the index of the maximum fans.
				val index = listOfFans.indexOf(maxFans)
				
				game.printToLog("[RACE] Selecting the Option ${index + 1} Extra Race.", tag = TAG)
				
				// Select the extra race that matches the double star prediction and the most fan gain.
				game.gestureUtils.tap(extraRaceLocation[index].x - 100, extraRaceLocation[index].y, "images", "race_extra_selection")
			} else {
				// If no maximum is determined, select the very first extra race.
				game.printToLog("[RACE] Selecting the first Extra Race by default.", tag = TAG)
				game.gestureUtils.tap(extraRaceLocation[0].x - 100, extraRaceLocation[0].y, "images", "race_extra_selection")
			}
			
			// Confirm the selection and the resultant popup and then wait for the game to load.
			game.findAndTapImage("race_confirm", tries = 5, region = regionBottomHalf)
			game.findAndTapImage("race_confirm", tries = 5, region = regionBottomHalf)
			afkCheck()
			game.wait(3.0)
			
			// Skip the race if possible, otherwise run it manually.
			val resultCheck: Boolean = if (game.imageUtils.findImage("race_skip_locked", tries = 5, region = regionBottomHalf).first == null) {
				skipRace()
			} else {
				manualRace()
			}
			
			finishRace(resultCheck, isExtra = true)
			
			game.printToLog("[RACE] Racing process for Extra Race is completed.", tag = TAG)
			return true
		}
		
		return false
	}
	
	/**
	 * The entry point for handling standalone races if the user started the bot on the Racing screen.
	 */
	private fun handleStandaloneRace() {
		game.printToLog("[RACE] Starting Standalone Racing process...", tag = TAG)
		
		// Skip the race if possible, otherwise run it manually.
		val resultCheck: Boolean = if (game.imageUtils.findImage("race_skip_locked", tries = 1, region = regionBottomHalf, suppressError = true).first == null) {
			skipRace()
		} else {
			manualRace()
		}
		
		finishRace(resultCheck)
		
		game.printToLog("[RACE] Racing process for Standalone Race is completed.", tag = TAG)
	}
	
	/**
	 * Skips the current race to get to the results screen.
	 *
	 * @return True if the bot completed the race with retry attempts remaining. Otherwise false.
	 */
	private fun skipRace(): Boolean {
		while (raceRetries >= 0) {
			game.printToLog("[RACE] Skipping race...", tag = TAG)
			
			// Press the skip button and then wait for your result of the race to show.
			game.findAndTapImage("race_skip", tries = 10, region = regionBottomHalf)
			game.wait(3.0)
			
			// Now tap on the screen to get to the next screen.
			game.gestureUtils.tap(500.0, 1000.0, "images", "ok")
			game.wait(2.0)
			
			// Check if the race needed to be retried.
			if (game.findAndTapImage("race_retry", tries = 3, region = regionBottomHalf)) {
				game.printToLog("[RACE] Skipped race failed. Attempting to retry...", tag = TAG)
				game.wait(5.0)
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
			game.printToLog("[RACE] Skipping manual race...", tag = TAG)
			
			// Press the manual button.
			game.findAndTapImage("race_manual", tries = 10, region = regionBottomHalf)
			game.wait(5.0)
			
			// Now press the confirm button to get past the list of participants.
			game.findAndTapImage("race_confirm", tries = 10, region = regionBottomHalf)
			game.wait(1.0)
			
			// Now skip to the end of the race.
			game.findAndTapImage("race_skip_manual", tries = 10, region = regionBottomHalf)
			game.findAndTapImage("race_skip_manual", tries = 10, region = regionBottomHalf)
			game.wait(1.0)
			game.findAndTapImage("race_skip_manual", tries = 10, region = regionBottomHalf)
			game.findAndTapImage("race_skip_manual", tries = 10, region = regionBottomHalf)
			game.findAndTapImage("race_skip_manual", tries = 10, region = regionBottomHalf)
			game.wait(3.0)
			
			// Check if the race needed to be retried.
			if (game.findAndTapImage("race_retry", tries = 3, region = regionBottomHalf)) {
				game.printToLog("[RACE] Manual race failed. Attempting to retry...", tag = TAG)
				game.wait(5.0)
				raceRetries--
			} else {
				// Check if a Trophy was acquired.
				if (game.findAndTapImage("race_accept_trophy", tries = 1, region = regionBottomHalf)) {
					game.printToLog("[RACE] Closing popup to claim trophy...", tag = TAG)
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
		game.findAndTapImage("race_confirm_result", tries = 10, region = regionBottomHalf)
		game.wait(2.0)
		
		// Now press the end button to finish the race.
		game.findAndTapImage("race_end", tries = 10, region = regionBottomHalf)
		
		if (!isExtra) {
			// Wait until the popup showing the completion of a Training Goal appears and confirm it.
			game.wait(5.0)
			game.findAndTapImage("race_confirm_result", tries = 10, region = regionBottomHalf)
			game.wait(3.0)
			
			// Now confirm the completion of a Training Goal popup.
			game.findAndTapImage("race_end", tries = 10, region = regionBottomHalf)
		} else {
			if (game.findAndTapImage("race_confirm_result", tries = 5, region = regionBottomHalf)) {
				// Now confirm the completion of a Training Goal popup.
				game.wait(2.0)
				game.findAndTapImage("race_end", tries = 10, region = regionBottomHalf)
			}
		}
		
		game.wait(3.0)
	}
	
	////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////////
	// Helper Functions
	
	/**
	 * Handles the Inheritance event if detected on the screen.
	 *
	 * @return True if the Inheritance event happened and was accepted. Otherwise false.
	 */
	private fun handleInheritanceEvent(): Boolean {
		return if (inheritancesDone < 2) {
			if (game.findAndTapImage("inheritance", tries = 1, region = regionBottomThreeThird, suppressError = true)) {
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
			game.findAndTapImage("recover_energy", tries = 1, regionBottomThreeThird) -> {
				game.findAndTapImage("ok", suppressError = true)
				game.printToLog("\n[ENERGY] Successfully recovered energy.")
				raceRepeatWarningCheck = false
				true
			}
			game.findAndTapImage("recover_energy_summer", tries = 1, regionBottomThreeThird) -> {
				game.findAndTapImage("ok", suppressError = true)
				game.printToLog("\n[ENERGY] Successfully recovered energy for the Summer.")
				raceRepeatWarningCheck = false
				true
			}
			else -> {
				game.printToLog("\n[ENERGY] Failed to recover energy.")
				false
			}
		}
	}
	
	/**
	 * Attempt to recover mood to always maintain at least Above Normal mood.
	 *
	 * @return True if the bot successfully recovered mood. Otherwise false.
	 */
	private fun recoverMood(): Boolean {
		if (!firstTrainingCheck) {
			game.printToLog("\n[MOOD] Detecting current mood.", tag = TAG)
			
			// Detect what Mood the bot is at.
			val currentMood: String = when {
				game.imageUtils.findImage("mood_above_normal", tries = 1, region = regionTopOneThird, suppressError = true).first != null -> {
					"Above Normal"
				}
				game.imageUtils.findImage("mood_great", tries = 1, region = regionTopOneThird, suppressError = true).first != null -> {
					"Great"
				}
				else -> {
					"Bad"
				}
			}
			
			// Only recover mood if its below Above Normal mood.
			return if (currentMood == "Bad" && game.imageUtils.findImage("recover_energy_summer", tries = 1, region = regionBottomHalf).first == null) {
				game.printToLog("[MOOD] Current mood is not good. Recovering mood now.", tag = TAG)
				if (!game.findAndTapImage("recover_mood", tries = 1, region = regionBottomThreeThird)) {
					game.findAndTapImage("recover_energy_summer", region = regionBottomThreeThird)
				}
				
				game.findAndTapImage("ok", region = regionMiddleTwoThird)
				
				// Wait for a while to see if the crane game appeared.
				game.wait(5.0)
				raceRepeatWarningCheck = false
				true
			} else {
				game.printToLog("[MOOD] Current mood is good enough. Moving on.", tag = TAG)
				false
			}
		}
		
		return false
	}
	
	/**
	 * Prints the training map object for informational purposes.
	 */
	private fun printMap() {
		trainingMap.keys.forEach { stat ->
			game.printToLog(
				"[INFO] Estimated Total Stat Gain of $stat Training: ${trainingMap[stat]?.get("totalStatGained")} for ${trainingMap[stat]?.get("failureChance")}% with a weight of " +
						"${trainingMap[stat]?.get("weight")}")
		}
	}
	
	/**
	 * Handle the case where the bot took too long to do anything and the AFK check came up.
	 */
	private fun afkCheck() {
		game.findAndTapImage("afk_check", tries = 1, region = regionMiddleTwoThird, suppressError = true)
	}
	
	/**
	 * Perform misc checks to potentially fix instances where the bot is stuck.
	 *
	 * @return True if the checks passed. Otherwise false if the bot encountered a warning popup and needs to exit.
	 */
	private fun performMiscChecks(): Boolean {
		afkCheck()
		
		if (enablePopupCheck && game.imageUtils.findImage("cancel", tries = 1, region = regionBottomHalf).first != null) {
			game.printToLog("\n[END] Bot may have encountered a warning popup. Exiting now...", tag = TAG)
			return false
		} else {
			game.findAndTapImage("cancel", tries = 1, region = regionBottomHalf)
		}
		
		game.findAndTapImage("back", tries = 1, region = regionBottomHalf)
		
		if (game.findAndTapImage("race_confirm_result", tries = 1, region = regionBottomHalf)) {
			// Now confirm the completion of a Training Goal popup.
			game.wait(5.0)
			game.findAndTapImage("race_end", tries = 5, region = regionBottomHalf)
			game.wait(3.0)
		}
		
		return true
	}
	
	fun start() {
		// Set default values for Stat Prioritization if its empty.
		if (statPrioritization.isEmpty() || statPrioritization[0] == "") {
			statPrioritization = listOf("Speed", "Stamina", "Power", "Guts", "Intelligence")
		}
		
		while (true) {
			if (checkMainScreen()) {
				// If the bot is at the Main screen, that means Training and other options are available.
				game.printToLog("[INFO] Current location is at Main screen.", tag = TAG)
				
				if (enableSkillPointCheck && game.imageUtils.determineSkillPoints() >= skillPointCheck) {
					game.printToLog("\n[END] Bot has acquired the set amount of skill points. Exiting now...", tag = TAG)
					break
				}
				
				if (checkInjury()) {
					// If the bot detected a injury, then rest.
					game.printToLog("\n[INFO] Detected a injury. Resting now.", tag = TAG)
					game.findAndTapImage("ok", region = regionMiddleTwoThird)
					game.wait(3.0)
				} else if (recoverMood()) {
					Log.d(TAG, "Mood recovered")
					if (game.imageUtils.findImage("crane_game", tries = 1, region = regionBottomHalf).first != null) {
						// Stop when the bot has reached the Crane Game Event.
						game.printToLog("\n[END] Bot will stop due to the detection of the Crane Game Event. Please complete it and restart the bot.", tag = TAG)
						break
					}
				} else if (!checkExtraRaceAvailability()) {
					Log.d(TAG, "Training due to not extra race day.")
					handleTraining()
				} else {
					Log.d(TAG, "Racing by default")
					if (!handleRaceEvents()) {
						Log.d(TAG, "Racing by default failed due to not detecting any eligible extra races. Training instead...")
						handleTraining()
					}
				}
			} else if (checkNotEnoughFans()) {
				Log.d(TAG, "Canceling popup of not enough fans")
				game.findAndTapImage("cancel", region = regionBottomHalf)
			} else if (checkTrainingEventScreen()) {
				// If the bot is at the Training Event screen, that means there are selectable options for rewards.
				Log.d(TAG, "Detected Training Event")
				handleTrainingEvent()
			} else if (handleInheritanceEvent()) {
				// If the bot is at the Inheritance screen, then accept the inheritance.
				game.printToLog("\n[INFO] Accepted the Inheritance.", tag = TAG)
			} else if (checkPreRaceScreen()) {
				// If the bot is at the Main screen with the button to select a race visible, that means the bot needs to handle a mandatory race.
				handleRaceEvents()
			} else if (game.imageUtils.findImage("race_change_strategy", tries = 1, region = regionBottomHalf).first != null) {
				// If the bot is already at the Racing screen, then complete this standalone race.
				handleStandaloneRace()
			} else if (!BotService.isRunning || checkEndScreen()) {
				// Stop when the bot has reached the screen where it details the overall result of the run.
				game.printToLog("\n[END] Bot has reached the end of the run. Exiting now...", tag = TAG)
				break
			} else if (raceRetries <= 0) {
				game.printToLog("\n[END] Bot has run out of retry attempts for races. Exiting now...", tag = TAG)
				break
			}
			
			// Various miscellaneous checks
			if(!performMiscChecks()) {
				break
			}
		}
	}
}