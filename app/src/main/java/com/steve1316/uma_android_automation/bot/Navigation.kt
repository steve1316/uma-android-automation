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
	private val regionBottomHalf: IntArray = intArrayOf(0, displayHeight / 2, displayWidth, displayHeight / 2)
	private val regionTopOneThird: IntArray = intArrayOf(0, 0, displayWidth, displayHeight / 3)
	private val regionMiddleTwoThird: IntArray = intArrayOf(0, displayHeight / 3, displayWidth, displayHeight / 3)
	private val regionBottomThreeThird: IntArray = intArrayOf(0, displayHeight - (displayHeight / 3), displayWidth, displayHeight / 3)
	
	private val trainings: List<String> = listOf("Speed", "Stamina", "Power", "Guts", "Intelligence")
	private val trainingMap: MutableMap<String, MutableMap<String, Int>> = mutableMapOf()
	private val blacklist: List<String> = SettingsFragment.getStringSetSharedPreference(game.myContext, "trainingBlacklist").toList()
	private var statPrioritization: List<String> = SettingsFragment.getStringSharedPreference(game.myContext, "statPrioritization").split("|")
	
	private var firstTrainingCheck = true
	private var previouslySelectedTraining = ""
	private var inheritancesDone = 0
	private var raceRetries = 3
	
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
		return game.imageUtils.findImage("tazuna", tries = 1, region = regionTopOneThird).first != null
				&& game.imageUtils.findImage("race_select", tries = 1, region = regionBottomThreeThird, suppressError = true).first == null
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
		return game.imageUtils.findImage("race_select", tries = 1, region = regionBottomThreeThird).first != null
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
	 * Checks if the bot encountered the popup that warns about repeatedly doing races 3+ times.
	 *
	 * @return True if the bot detected this popup. Otherwise false.
	 */
	private fun checkRaceRepeatWarning(): Boolean {
		return game.imageUtils.findImage("race_repeat_warning", tries = 1, region = regionMiddleTwoThird).first != null
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
	 * Find the success percentages and stat gain for each training and assign them to the MutableMap object to be shared across the whole class.
	 */
	private fun findStatsAndPercentages() {
		game.printToLog("\n[TRAINING] Checking for success percentages and total stat increases for training selection.", tag = TAG)
		
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
						
						game.wait(0.5)
						
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
		game.printToLog("\n[TRAINING] Now starting process to assign weights to each prioritised stat.", tag = TAG)
		
		var count = 5
		statPrioritization.forEach { statName ->
			if (trainingMap.containsKey(statName)) {
				val failureChance: Int = trainingMap[statName]?.get("failureChance")!!
				val totalStatGained: Int = trainingMap[statName]?.get("totalStatGained")!!
				val penaltyForRepeat: Int = if (previouslySelectedTraining == statName) {
					Log.d(TAG, "$statName already did training so applying penalty for repeating.")
					250
				} else {
					0
				}
				
				val weight: Int = (50 * count) + (10 * totalStatGained) - (failureChance * 2) - penaltyForRepeat
				
				trainingMap[statName]?.set("weight", weight)
				count--
			}
		}
		
		game.printToLog("[TRAINING] Process to assign weights to each prioritised stat completed.", tag = TAG)
	}
	
	/**
	 * Execute the training with the highest stat weight.
	 */
	private fun executeTraining() {
		game.printToLog("\n[TRAINING] Now starting process to execute training.", tag = TAG)
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
		game.printToLog("\n[TRAINING-EVENT] Now starting process to handle detected Training Event.", tag = TAG)
		
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
					var statCheck = false
					val formattedLine: String = regex.replace(line, "").replace("+", "").replace("(", "").replace(")", "").trim()
					
					statPrioritization.forEach { stat ->
						if (formattedLine.contains(stat)) {
							selectionWeight[optionSelected] += try {
								statCheck = true
								formattedLine.toInt() * 2
							} catch (e: NumberFormatException) {
								statCheck = false
								0
							}
						}
					}
					
					if (!statCheck) {
						selectionWeight[optionSelected] += try {
							formattedLine.toInt()
						} catch (e: NumberFormatException) {
							0
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
			trainingOptionLocations[optionSelected]
		} else {
			game.imageUtils.findImage("training_event_active", region = regionMiddleTwoThird).first
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
	 * Handles and completes the mandatory race event.
	 */
	private fun handleMandatoryRaceEvent() {
		game.printToLog("\n[RACE] Encountered a mandatory race. Proceeding to complete it now...", tag = TAG)
		
		// Navigate the bot to the Race Selection screen.
		game.findAndTapImage("race_select", region = regionBottomThreeThird)
		
		// Check for the popup warning against repeatedly doing 3+ runs.
		if (checkRaceRepeatWarning()) {
			game.findAndTapImage("ok", region = regionMiddleTwoThird)
		}
		
		// Make sure to select the only the first race.
		game.findAndTapImage("race_mandatory_selection", region = regionMiddleTwoThird)
		
		afkCheck()
		
		// Confirm the race selection and then confirm it again.
		game.findAndTapImage("race_confirm", region = regionBottomThreeThird)
		game.findAndTapImage("race_confirm", region = regionBottomHalf)
		game.wait(5.0)
		
		// The bot will arrive at the Race Setup screen where you can skip the race, run it manually, and/or change strategies.
		var successCheck = false
		while (!successCheck && raceRetries > 0) {
			if (game.findAndTapImage("race_skip", region = regionBottomThreeThird)) {
				successCheck = if (!game.imageUtils.waitVanish("race_skip", timeout = 5, suppressError = true)) {
					runRaceManually()
				} else {
					skipRace()
				}
			}
		}
		
		finishRace()
		
		game.printToLog("[RACE] Process to complete a mandatory race completed.", tag = TAG)
	}
	
	/**
	 * Handles and completes an extra race.
	 */
	private fun handleExtraRace() {
		game.printToLog("\n[EXTRA-RACE] Starting process to complete a extra race now.", tag = TAG)
		
		val listOfFans = mutableListOf<Int>()
		
		// Confirm the popup.
		game.findAndTapImage("race_manual", region = regionMiddleTwoThird)
		
		// Check for the popup warning against repeatedly doing 3+ runs.
		if (checkRaceRepeatWarning()) {
			game.findAndTapImage("ok", region = regionMiddleTwoThird)
		}
		
		afkCheck()
		
		// Now determine the best extra race with the following parameters: highest fans and double star prediction.
		// First find the fans of only the extra races on the screen that match the double star prediction. Read only 3 extra races.
		var count = 0
		val extraRaceLocation = mutableListOf<Point>()
		val (sourceBitmap, templateBitmap) = game.imageUtils.getBitmaps("race_extra_double_prediction", "images")
		while (count < 3) {
			extraRaceLocation.add(game.imageUtils.findImage("race_extra_selection", region = regionMiddleTwoThird).first!!)
			
			val fans = game.imageUtils.determineExtraRaceFans(extraRaceLocation[count], sourceBitmap!!, templateBitmap!!)
			listOfFans.add(fans)
			
			// Move to the next extra race.
			if (count != 2) {
				game.gestureUtils.tap(extraRaceLocation[count].x - 100, extraRaceLocation[count].y + 150, "images", "race_extra_selection")
			}
			
			count++
		}
		
		// Next determine the maximum fans and select the extra race.
		val maxFans: Int? = listOfFans.maxOrNull()
		if (maxFans != null) {
			// Get the index of the maximum fans.
			val index = listOfFans.indexOf(maxFans)
			
			game.printToLog("[EXTRA-RACE] Selecting the #${index + 1} Extra Race.", tag = TAG)
			
			// Select the extra race that matches the double star prediction and the most fan gain.
			game.gestureUtils.tap(extraRaceLocation[index].x - 100, extraRaceLocation[index].y, "images", "race_extra_selection")
		} else {
			// If no maximum is determined, select the very first extra race.
			game.printToLog("[EXTRA-RACE] Selecting the first Extra Race by default.", tag = TAG)
			game.gestureUtils.tap(extraRaceLocation[0].x - 100, extraRaceLocation[0].y, "images", "race_extra_selection")
		}
		
		// Now that the extra race is selected, confirm the race and run it.
		game.findAndTapImage("race_confirm", region = regionMiddleTwoThird)
		game.findAndTapImage("race_confirm", region = regionMiddleTwoThird)
		game.wait(5.0)
		
		var successCheck = false
		while (!successCheck && raceRetries > 0) {
			if (game.findAndTapImage("race_skip", region = regionBottomThreeThird)) {
				successCheck = if (!game.imageUtils.waitVanish("race_skip", timeout = 5, suppressError = true)) {
					runRaceManually()
				} else {
					skipRace()
				}
			}
		}
		
		finishRace(isExtra = true)
		
		game.printToLog("[RACE] Process to complete a extra race completed.", tag = TAG)
	}
	
	/**
	 * Finishes up a race by finalizing the results.
	 *
	 * @param isExtra Flag to indicate whether the current race is an extra or mandatory to determine whether to press additional buttons.
	 */
	private fun finishRace(isExtra: Boolean = false) {
		Log.d(TAG, "Race has finished.")
		
		// Skip the screen that shows the accumulation of new fans and then confirm the end of the race.
		game.gestureUtils.tap(500.0, 500.0, "images", "ok", taps = 5)
		game.findAndTapImage("race_end", region = regionBottomThreeThird)
		
		if (!isExtra) {
			// Now finalize the result by tapping on this button to complete a Training Goal for the Character.
			game.wait(5.0)
			game.findAndTapImage("race_confirm_result", region = regionBottomThreeThird)
			game.wait(5.0)
			game.findAndTapImage("race_confirm_result", region = regionBottomThreeThird)
		}
	}
	
	/**
	 * Handles skipping the race.
	 *
	 * @return True if the race was completed successfully. False if the race needs to be retried.
	 */
	private fun skipRace(): Boolean {
		game.printToLog("[RACE] Successfully skipped race.", tag = TAG)
		
		// Tap multiple times to skip to the screen where it shows the final positions of all of the participants.
		game.gestureUtils.tap(500.0, 500.0, "images", "ok", taps = 5)
		game.wait(2.0)
		
		// Automatically retry if failed the race.
		return if (game.findAndTapImage("race_retry", tries = 1, region = regionBottomHalf, suppressError = true)) {
			game.wait(3.0)
			raceRetries--
			false
		} else {
			game.findAndTapImage("race_confirm_result", region = regionBottomThreeThird)
			true
		}
	}
	
	/**
	 * Handles running the race manually.
	 *
	 * @return True if the race was completed successfully. False if the race needs to be retried.
	 */
	private fun runRaceManually(): Boolean {
		game.printToLog("[RACE] Race must be locked. Proceeding to running it manually.", tag = TAG)
		
		// Start the race manually and wait for the game to load.
		game.findAndTapImage("race_manual", region = regionBottomThreeThird)
		game.wait(8.0)
		
		// After the game loaded in the race, press the confirm button.
		game.findAndTapImage("race_confirm", region = regionBottomThreeThird)
		
		// Now press the skip button 4 times.
		game.findAndTapImage("race_skip_manual", region = regionBottomThreeThird)
		game.findAndTapImage("race_skip_manual", region = regionBottomThreeThird)
		game.wait(2.0)
		game.findAndTapImage("race_skip_manual", region = regionBottomThreeThird)
		game.findAndTapImage("race_skip_manual", region = regionBottomThreeThird)
		game.wait(8.0)
		
		// Automatically retry if failed the race.
		return if (game.findAndTapImage("race_retry", tries = 1, region = regionBottomHalf)) {
			game.wait(3.0)
			raceRetries--
			false
		} else {
			game.findAndTapImage("race_confirm_result", region = regionBottomThreeThird)
			true
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
				true
			}
			game.findAndTapImage("recover_energy_summer", tries = 1, regionBottomThreeThird) -> {
				game.findAndTapImage("ok", suppressError = true)
				game.printToLog("\n[ENERGY] Successfully recovered energy for the Summer.")
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
			
			return if (currentMood == "Bad" && game.imageUtils.findImage("recover_energy_summer", tries = 1, region = regionMiddleTwoThird, suppressError = true).first == null) {
				game.printToLog("[MOOD] Current mood is not good. Recovering mood now.", tag = TAG)
				game.findAndTapImage("recover_mood", region = regionBottomThreeThird)
				game.findAndTapImage("ok", region = regionMiddleTwoThird)
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
	
	fun start() {
		// Set default values for Stat Prioritization if its empty.
		if (statPrioritization.isEmpty() || statPrioritization[0] == "") {
			statPrioritization = listOf("Speed", "Stamina", "Power", "Guts", "Intelligence")
		}
		
		while (true) {
			if (checkNotEnoughFans()) {
				// If the bot detected the popup that says there are not enough fans, run an extra race.
				handleExtraRace()
			} else if (checkTrainingEventScreen()) {
				// If the bot is at the Training Event screen, that means there are selectable options for rewards.
				handleTrainingEvent()
			} else if (checkMainScreen()) {
				// If the bot is at the Main screen, that means Training and other options are available.
				game.printToLog("[INFO] Current location is at Main screen.", tag = TAG)
				
				if (checkInjury()) {
					// If the bot detected a injury, then rest.
					game.printToLog("\n[INFO] Detected a injury. Resting now.", tag = TAG)
					game.findAndTapImage("ok", region = regionMiddleTwoThird)
					game.wait(3.0)
				} else if (!recoverMood()) {
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
				}
			} else if (handleInheritanceEvent()) {
				// If the bot is at the Inheritance screen, then accept the inheritance.
				game.printToLog("\n[INFO] Accepted the Inheritance.", tag = TAG)
			} else if (checkPreRaceScreen()) {
				// If the bot is at the Main screen with the button to select a race visible, that means the bot needs to handle a mandatory race.
				handleMandatoryRaceEvent()
			} else if (game.imageUtils.findImage("crane_game", tries = 1, region = regionBottomHalf, suppressError = true).first != null) {
				// Stop when the bot has reached the Crane Game Event.
				game.printToLog("\n[END] Bot will stop due to the detection of the Crane Game Event. Please complete it and restart the bot.", tag = TAG)
				break
			} else if (!BotService.isRunning || checkEndScreen()) {
				// Stop when the bot has reached the screen where it details the overall result of the run.
				game.printToLog("\n[END] Bot has reached the end of the run.", tag = TAG)
				break
			}
			
			afkCheck()
		}
	}
}