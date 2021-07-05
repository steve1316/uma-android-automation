package com.steve1316.uma_android_automation.bot

import android.util.Log
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.ui.settings.SettingsFragment
import com.steve1316.uma_android_automation.utils.BotService
import org.opencv.core.Point

class Navigation(val game: Game) {
	private val TAG: String = "[${MainActivity.loggerTag}]Navigation"
	
	private val trainings: List<String> = listOf("Speed", "Stamina", "Power", "Guts", "Intelligence")
	private val trainingMap: MutableMap<String, MutableMap<String, Int>> = mutableMapOf()
	private val blacklist: List<String> = SettingsFragment.getStringSetSharedPreference(game.myContext, "trainingBlacklist").toList()
	private var statPrioritization: List<String> = SettingsFragment.getStringSharedPreference(game.myContext, "statPrioritization").split("|")
	
	private var craneGameCheck = false
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
		return game.imageUtils.findImage("tazuna", tries = 1).first != null && game.imageUtils.findImage("race_select", tries = 1, suppressError = true).first == null
	}
	
	/**
	 * Checks if the bot is at the Training Event screen with an active event with options to select on screen.
	 *
	 * @return True if the bot is at the Training Event screen. Otherwise false.
	 */
	private fun checkTrainingEventScreen(): Boolean {
		return game.imageUtils.findImage("training_event_active", tries = 1).first != null
	}
	
	/**
	 * Checks if the bot is at the Main screen with a mandatory race needing to be completed.
	 *
	 * @return True if the bot is at the Main screen with a mandatory race. Otherwise false.
	 */
	private fun checkPreRaceScreen(): Boolean {
		return game.imageUtils.findImage("race_select", tries = 1).first != null
	}
	
	/**
	 * Checks if the bot is at the Ending screen detailing the overall results of the run.
	 *
	 * @return True if the bot is at the Ending screen. Otherwise false.
	 */
	private fun checkEndScreen(): Boolean {
		return game.imageUtils.findImage("end", tries = 1).first != null
	}
	
	/**
	 * Checks if the bot has a injury.
	 *
	 * @return True if the bot has a injury. Otherwise false.
	 */
	private fun checkInjury(): Boolean {
		return if (game.findAndTapImage("recover_injury", tries = 1, suppressError = true)) {
			game.imageUtils.confirmLocation("recover_injury", tries = 1, suppressError = true)
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
		val (speedStatTextLocation, _) = game.imageUtils.findImage("stat_speed")
		if (speedStatTextLocation != null) {
			// Perform a percentage check of Speed training to see if the bot has enough energy to do training. As a result, Speed training will be the one selected for the rest of the algorithm.
			if (!game.imageUtils.confirmLocation("speed_training", tries = 1)) {
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
				
				val weight: Int = (100 * count) + (10 * totalStatGained) - (failureChance * 2) - penaltyForRepeat
				
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
			game.findAndTapImage("training_${trainingSelected.lowercase()}", taps = 3)
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
		
		val eventRewards: ArrayList<String> = textDetection.start()
		
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
			
			val trainingOptionLocations: ArrayList<Point> = game.imageUtils.findAll("training_event_active")
			val selectedLocation = if (trainingOptionLocations.isNotEmpty()) {
				trainingOptionLocations[optionSelected]
			} else {
				game.imageUtils.findImage("training_event_active").first!!
			}
			
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
		game.findAndTapImage("race_select")
		
		// Confirm the race selection and then confirm it again.
		game.findAndTapImage("race_confirm")
		game.findAndTapImage("race_confirm")
		game.wait(3.0)
		
		// The bot will arrive at the Race Setup screen where you can skip the race, run it manually, and/or change strategies.
		var successCheck = false
		while (!successCheck && raceRetries > 0) {
			if (game.findAndTapImage("race_skip")) {
				successCheck = if (!game.imageUtils.waitVanish("race_skip", timeout = 3, suppressError = true)) {
					runRaceManually()
				} else {
					skipRace()
				}
			}
		}
		
		Log.d(TAG, "Race has finished.")
		
		// Skip the screen that shows the accumulation of new fans and then confirm the end of the race.
		game.gestureUtils.tap(500.0, 500.0, "images", "ok", taps = 3)
		game.findAndTapImage("race_end")
		
		// Now finalize the result by tapping on this button to complete a Training Goal for the Character.
		game.findAndTapImage("race_confirm_result")
		
		game.printToLog("[RACE] Process to complete a mandatory race completed.", tag = TAG)
	}
	
	/**
	 * Handles skipping the race.
	 *
	 * @return True if the race was completed successfully. False if the race needs to be retried.
	 */
	private fun skipRace(): Boolean {
		game.printToLog("[RACE] Successfully skipped race.", tag = TAG)
		
		// Tap multiple times to skip to the screen where it shows the final positions of all of the participants.
		game.gestureUtils.tap(500.0, 500.0, "images", "ok", taps = 3)
		game.wait(1.0)
		
		// Automatically retry if failed the race.
		return if (game.findAndTapImage("race_retry", tries = 1, suppressError = true)) {
			game.wait(3.0)
			raceRetries--
			false
		} else {
			game.findAndTapImage("race_confirm_result")
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
		game.findAndTapImage("race_manual")
		game.wait(5.0)
		
		// After the game loaded in the race, press the confirm button.
		game.findAndTapImage("race_confirm")
		
		// Now press the skip button 4 times.
		game.findAndTapImage("race_skip_manual")
		game.wait(1.0)
		game.findAndTapImage("race_skip_manual")
		game.wait(3.0)
		game.findAndTapImage("race_skip_manual")
		game.wait(1.0)
		game.findAndTapImage("race_skip_manual")
		game.wait(5.0)
		
		// Automatically retry if failed the race.
		return if (game.findAndTapImage("race_retry", tries = 1, suppressError = true)) {
			game.wait(3.0)
			raceRetries--
			false
		} else {
			game.findAndTapImage("race_confirm_result")
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
			if (game.findAndTapImage("inheritance", tries = 1, suppressError = true)) {
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
		return if (game.findAndTapImage("recover_energy", tries = 1)) {
			game.findAndTapImage("ok", tries = 1, suppressError = true)
			game.printToLog("\n[ENERGY] Successfully recovered energy.")
			true
		} else if (game.findAndTapImage("recover_energy_summer", tries = 1)) {
			game.findAndTapImage("ok", tries = 1, suppressError = true)
			game.printToLog("\n[ENERGY] Successfully recovered energy for the Summer.")
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
				game.imageUtils.findImage("mood_above_normal", tries = 1, suppressError = true).first != null -> {
					"Above Normal"
				}
				game.imageUtils.findImage("mood_great", tries = 1, suppressError = true).first != null -> {
					"Great"
				}
				else -> {
					"Bad"
				}
			}
			
			return if (currentMood == "Bad") {
				game.printToLog("[MOOD] Current mood is not good. Recovering mood now.", tag = TAG)
				game.findAndTapImage("recover_mood")
				game.findAndTapImage("ok")
				
				// Check if recovering mood caused the Crane Game Event occurred.
				if (game.imageUtils.findImage("crane_game", tries = 1, suppressError = true).first != null) {
					craneGameCheck = true
				}
				
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
	
	fun start() {
		// Set default values for Stat Prioritization if its empty.
		if (statPrioritization.isEmpty() || statPrioritization[0] == "") {
			statPrioritization = listOf("Speed", "Stamina", "Power", "Guts", "Intelligence")
		}
		
		while (true) {
			if (checkMainScreen()) {
				// If the bot is at the Main screen, that means Training and other options are available.
				game.printToLog("[INFO] Current location is at Main screen.", tag = TAG)
				
				// Enter the Training screen.
				game.findAndTapImage("training_option")
				
				// Acquire the percentages and stat gains for each training.
				findStatsAndPercentages()
				
				if (trainingMap.isEmpty()) {
					game.findAndTapImage("back")
					
					if (checkMainScreen()) {
						recoverEnergy()
					} else {
						throw IllegalStateException("Could not head back to the Main screen in order to recover energy.")
					}
				} else {
					// Generate weights for the stats based on what settings the user set.
					createWeights()
					
					// Now select the training option with the highest weight. TODO: Might need more revision.
					executeTraining()
				}
			} else if (checkTrainingEventScreen()) {
				// If the bot is at the Training Event screen, that means there are selectable options for rewards.
				handleTrainingEvent()
			} else if (handleInheritanceEvent()) {
				// If the bot is at the Inheritance screen, then accept the inheritance.
				game.printToLog("\n[INFO] Accepted the Inheritance.", tag = TAG)
			} else if (checkPreRaceScreen()) {
				// If the bot is at the Main screen with the button to select a race visible, that means the bot needs to handle a mandatory race.
				handleMandatoryRaceEvent()
			} else if (!BotService.isRunning || craneGameCheck || checkEndScreen()) {
				// Stop when the bot has reached the screen where it details the overall result of the run.
				break
			}
			
			// Handle the case where the bot took too long to do anything and the AFK check came up.
			game.findAndTapImage("afk_check", tries = 1, suppressError = true)
		}
	}
}