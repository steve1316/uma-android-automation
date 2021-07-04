package com.steve1316.uma_android_automation.bot

import android.util.Log
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.ui.settings.SettingsFragment

class Navigation(val game: Game) {
	private val TAG: String = "[${MainActivity.loggerTag}]Navigation"
	
	private val trainings: List<String> = listOf("speed", "stamina", "power", "guts", "intelligence")
	private val trainingMap: MutableMap<String, MutableMap<String, Int>> = mutableMapOf()
	private val blacklist: List<String> = SettingsFragment.getStringSharedPreference(game.myContext, "trainingBlacklist").split("|")
	
	/**
	 * Checks if the bot is at the Main screen or the screen with available options to undertake.
	 *
	 * @return True if the bot is at the Main screen. Otherwise false.
	 */
	private fun checkMainScreen(): Boolean {
		return game.imageUtils.findImage("tazuna", tries = 1).first != null
	}
	
	/**
	 * Checks if the bot is at the Training screen.
	 *
	 * @return True if the bot is at the Training screen. Otherwise false.
	 */
	private fun checkTrainingScreen(): Boolean {
		return game.imageUtils.findImage("active_training_event", tries = 1).first != null
	}
	
		game.printToLog("\n[INFO] Checking for success percentages and total stat increases for training selection.", tag = TAG)
		
		val resultMap: MutableMap<String, Map<String, Int>> = mutableMapOf()
		
	/**
	 * Find the success percentages and stat gain for each training and assign them to the MutableMap object to be shared across the whole class.
	 */
	private fun findStatsAndPercentages() {
		game.printToLog("\n[INFO] Checking for success percentages and total stat increases for training selection.", tag = TAG)
		
		// Acquire the position of the speed stat text.
		val (speedStatTextLocation, _) = game.imageUtils.findImage("speed_stat")
		if (speedStatTextLocation != null) {
			// Perform a percentage check of Speed training to see if the bot has enough energy to do training. As a result, Speed training will be the one selected for the rest of the algorithm.
			if (!game.imageUtils.confirmLocation("speed_training", tries = 1)) {
				game.gestureUtils.tap(speedStatTextLocation.x + 19, speedStatTextLocation.y + 319, "images", "stat")
			}
			
			val speedFailureChance: Int = game.imageUtils.findTrainingFailureChance()
			val overallStatsGained: Int = game.imageUtils.findTotalStatGains("speed")
			
			if (speedFailureChance < game.maximumFailureChance) {
				game.printToLog("[INFO] Percentage within acceptable range. Proceeding to acquire all other percentages and total stat increases.", tag = TAG)
				
				// Save the results to the map if Speed training is not blacklisted.
				if (!blacklist.contains("speed")) {
					trainingMap["speed"] = mutableMapOf(
						"failure" to speedFailureChance,
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
					when (training.lowercase()) {
						"stamina" -> {
							game.gestureUtils.tap(speedStatTextLocation.x + 212, speedStatTextLocation.y + 319, "images", "stat")
						}
						"power" -> {
							game.gestureUtils.tap(speedStatTextLocation.x + 402, speedStatTextLocation.y + 319, "images", "stat")
						}
						"guts" -> {
							game.gestureUtils.tap(speedStatTextLocation.x + 591, speedStatTextLocation.y + 319, "images", "stat")
							game.wait(2.0)
						}
						"intelligence" -> {
							game.gestureUtils.tap(speedStatTextLocation.x + 779, speedStatTextLocation.y + 319, "images", "stat")
						}
					}
					
					game.wait(0.5)
					
					trainingMap[training] = mutableMapOf(
						"failure" to game.imageUtils.findTrainingFailureChance(),
						"totalStatGained" to game.imageUtils.findTotalStatGains(training),
						"weight" to 0
					)
				}
				
				game.wait(0.5)
			} else {
				trainingMap.clear()
				Log.d(TAG, "Clearing the Map so now it has a size of ${trainingMap.size}")
			}
		}
	}
	
	/**
	 * Generate the weights for each training using the settings set in the app.
	 */
	private fun createWeights() {
		// Grab the preference settings.
		val statPrioritisation: List<String> = SettingsFragment.getStringSharedPreference(game.myContext, "statPrioritisation").split("|")
		
		var count = 5
		statPrioritisation.forEach { statName ->
			val weight = 10 * count
			Log.d(TAG, "Assigning a weight of $weight to $statName")
			
			trainingMap[statName]?.set("weight", weight)
			count--
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
			game.printToLog("[INFO] Successfully recovered energy.")
			true
		} else {
			game.printToLog("[WARNING] Failed to recover energy.")
			false
		}
	}
	
	fun start() {
		// If the bot is at the Main screen, that means Training and other options are available.
		if (checkMainScreen()) {
			game.printToLog("[INFO] Current location is at Main screen.", tag = TAG)
			
			// Enter the Training screen.
			game.findAndTapImage("training")
			
			// Acquire the percentages and stat gains for each training.
			findStatsAndPercentages()
			
			if (trainingMap.isEmpty()) {
				game.printToLog("[INFO] Maximum percentage of success exceeded. Recovering energy...", tag = TAG)
				
				recoverEnergy()
			} else {
				trainingMap.keys.forEach { stat ->
					game.printToLog("[INFO] $stat: ${trainingMap[stat]?.get("totalStatGained")} for ${trainingMap[stat]?.get("failure")}%")
				}
			}
		} else if (checkTrainingScreen()) {
			// Generate weights for the stats based on what settings the user set.
			createWeights()
		}
	}
}