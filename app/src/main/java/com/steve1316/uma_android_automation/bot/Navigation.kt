package com.steve1316.uma_android_automation.bot

import com.steve1316.uma_android_automation.MainActivity

class Navigation(val game: Game) {
	private val TAG: String = "[${MainActivity.loggerTag}]Navigation"
	
	/**
	 * Checks if the bot is at the Main screen or the screen with available options to undertake.
	 *
	 * @return True if the chibi Tazuna icon at the top right corner of the screen was found. Otherwise false.
	 */
	private fun checkMainScreen(): Boolean {
		return game.imageUtils.findImage("tazuna", tries = 1).first != null
	}
	
	/**
	 * Find the success percentages and stat gain for each training.
	 *
	 * @return A MutableMap containing either success percentages and stat gain for each training or nothing if the bot does not have enough energy to conduct training.
	 */
	private fun findStatsAndPercentages(): MutableMap<String, Map<String, Int>> {
		// Check stat increases
		// 136, 1741 -> 53, 1650 with a region of 162x61
		
		game.printToLog("\n[INFO] Checking for success percentages and total stat increases for training selection.", tag = TAG)
		
		val resultMap: MutableMap<String, Map<String, Int>> = mutableMapOf()
		
		// Acquire the position of the speed stat text.
		val (speedStatTextLocation, _) = game.imageUtils.findImage("speed_stat")
		if (speedStatTextLocation != null) {
			// Perform a percentage check of Speed training to see if the bot has enough energy to do training. As a result, Speed training will be the one selected for the rest of the algorithm.
			if (!game.imageUtils.confirmLocation("speed_training", tries = 1)) {
				game.gestureUtils.tap(speedStatTextLocation.x + 19, speedStatTextLocation.y + 319, "images", "stat")
			}
			
			val speedSuccessPercentage: Int = game.imageUtils.findStatPercentage()
			val overallStatsGained: Int = game.imageUtils.findStatIncreases("speed")
			
			if (speedSuccessPercentage < game.maximumPercentage) {
				game.printToLog("[INFO] Percentage within acceptable range. Proceeding to acquire all other percentages and total stat increases.", tag = TAG)
				
				// Save the results to the map.
				resultMap["speed"] = mapOf(
					"success" to speedSuccessPercentage,
					"totalStatGained" to overallStatsGained
				)
				
				// Iterate through every stat after Speed.
				arrayListOf("stamina", "power", "guts", "intelligence").forEach { stat ->
					when (stat.lowercase()) {
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
					
					resultMap[stat] = mapOf(
						"success" to game.imageUtils.findStatPercentage(),
						"totalStatGained" to game.imageUtils.findStatIncreases(stat)
					)
				}
				
				game.wait(0.5)
			}
		}
		
		return resultMap
	}
	
	fun start() {
		// If the bot is at the Main screen, that means Training and other options are available.
		if (checkMainScreen()) {
			game.printToLog("[INFO] Current location is at Main screen.", tag = TAG)
			
			// Enter the Training screen.
			game.findAndTapImage("training")
			
			// Acquire the percentages and stat gains for each training.
			val statAndPercentages: MutableMap<String, Map<String, Int>> = findStatsAndPercentages()
			
			if (statAndPercentages.isEmpty()) {
				game.printToLog("[INFO] Maximum percentage of success exceeded. Recovering energy...", tag = TAG)
			} else {
				statAndPercentages.keys.forEach { stat ->
					game.printToLog("[INFO] $stat: ${statAndPercentages[stat]?.get("totalStatGained")} for ${statAndPercentages[stat]?.get("success")}%")
				}
			}
		}
	}
}