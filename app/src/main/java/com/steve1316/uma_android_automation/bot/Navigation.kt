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
	private val statPrioritization: List<String> = SettingsFragment.getStringSharedPreference(game.myContext, "statPrioritization").split("|")
	
	private var previouslySelectedTraining = ""
	private var inheritancesDone = 0
	
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

//	/**
//	 * Checks if the bot is at the Ending screen detailing the overall results of the run.
//	 *
//	 * @return True if the bot is at the Ending screen. Otherwise false.
//	 */
//	private fun checkEndScreen(): Boolean {
//		return game.imageUtils.findImage("end", tries = 1).first != null
//	}
	
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
		
		// Initialize the List.
		val selectionWeight = mutableListOf<Int>()
		for (i in 1..(eventRewards.size)) {
			selectionWeight.add(0)
		}
		
		if (eventRewards.isNotEmpty() && eventRewards[0] != "") {
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
		}
		
		// Select the best option that aligns with the stat prioritization made in the Training options.
		var max: Int? = selectionWeight.maxOrNull()
		if (max == null) {
			max = 0
			optionSelected = 0
		} else {
			optionSelected = selectionWeight.indexOf(max)
		}
		
		game.printToLog(
			"[TRAINING-EVENT] For this Training Event consisting of $eventRewards, the bot will select the option \"${eventRewards[optionSelected]}\" with a selection weight of $max.", tag = TAG)
		
		val trainingOptionLocations: ArrayList<Point> = game.imageUtils.findAll("training_event_active")
		val selectedLocation = if (trainingOptionLocations.isNotEmpty()) {
			trainingOptionLocations[optionSelected]
		} else {
			game.imageUtils.findImage("training_event_active").first!!
		}
		
		game.gestureUtils.tap(selectedLocation.x + 100, selectedLocation.y, "images", "training_event_active")
	}
	
	////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////////
	// Functions to handle Race Events.
	
	/**
	 * Handles and completes the mandatory race event.
	 */
	private fun handleMandatoryRaceEvent() {
		game.printToLog("\n[MANDATORY-RACE] Encountered a mandatory race. Proceeding to complete it now...", tag = TAG)
		
		// Navigate the bot to the Race Selection screen.
		game.findAndTapImage("race_select")
		
		// The confirmation button will show up twice.
		game.findAndTapImage("race_confirm")
		game.findAndTapImage("race_confirm")
		
		game.wait(1.0)
		
		if (game.imageUtils.findImage("race_skip", tries = 1).first != null) {
			// Skip the race.
			game.findAndTapImage("race_skip")
			
			// TODO: Handle the case where the user has not run this particular race yet so the skip button will be locked. The bot will need to manually run the race.
			
			game.wait(1.0)
			
			// Now interact with the screen to confirm the choice.
			game.gestureUtils.swipe(500f, 1000f, 500f, 900f, 100L)
			game.wait(0.5)
			
			game.findAndTapImage("race_confirm_result")
			game.gestureUtils.swipe(500f, 1000f, 500f, 900f, 100L)
			game.wait(0.5)
			
			// TODO: Handle the case where the bot failed to get a good enough position and needs to retry the race.
			
			game.findAndTapImage("race_end")
			
			// Now finalize the result by tapping on this button 2 times to complete a Training Goal for the Character.
			game.findAndTapImage("race_confirm_result")
			game.findAndTapImage("race_confirm_result")
			
			game.printToLog("[MANDATORY-RACE] Process to complete a mandatory race completed.", tag = TAG)
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
		return if (game.findAndTapImage("recover_energy")) {
			game.findAndTapImage("ok", tries = 1, suppressError = true)
			game.printToLog("\n[ENERGY] Successfully recovered energy.")
			true
		} else {
			game.printToLog("\n[ENERGY] Failed to recover energy.")
			false
		}
	}
	
	/**
	 * Prints the training map object for informational purposes.
	 */
	private fun printMap() {
		trainingMap.keys.forEach { stat ->
			game.printToLog(
				"[INFO] Estimated Stat Gain of $stat: ${trainingMap[stat]?.get("totalStatGained")} for ${trainingMap[stat]?.get("failureChance")}% with a weight of ${trainingMap[stat]?.get("weight")}")
		}
	}
	
	fun start() {
		while (true) {
			if (checkMainScreen()) {
				// If the bot is at the Main screen, that means Training and other options are available.
				game.printToLog("[INFO] Current location is at Main screen.", tag = TAG)
				
				// Enter the Training screen.
				game.findAndTapImage("training_option")
				
				// Acquire the percentages and stat gains for each training.
				findStatsAndPercentages()
				
				if (trainingMap.isEmpty()) {
					game.printToLog("[INFO] Maximum percentage of success exceeded. Recovering energy...", tag = TAG)
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
			} else if (!BotService.isRunning) {
				// Stop when the bot has reached the screen where it details the overall result of the run.
				break
			}
			
			// Handle the case where the bot took too long to do anything and the AFK check came up.
			game.findAndTapImage("afk_check", tries = 1, suppressError = true)
			
			game.wait(1.0)
		}
	}
}