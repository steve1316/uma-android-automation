package com.steve1316.uma_android_automation.bot.campaigns

import android.util.Log
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.utils.BotService
import org.opencv.core.Point

class AoHaru(private val game: Game) {
	private val tag: String = "[${MainActivity.loggerTag}]AoHaru"
	private var tutorialChances = 3
	private var aoHaruRaceFirstTime: Boolean = true
	
	/**
	 * Checks for Ao Haru's tutorial first before handling a Training Event.
	 */
	fun handleTrainingEventAoHaru() {
		if (tutorialChances > 0) {
			if (game.imageUtils.confirmLocation("aoharu_tutorial", tries = 2)) {
				// If the tutorial is detected, select the second option to close it.
				val trainingOptionLocations: ArrayList<Point> = game.imageUtils.findAll("training_event_active")
				game.gestureUtils.tap(trainingOptionLocations[1].x, trainingOptionLocations[1].y,  "training_event_active")
				tutorialChances = 0
			} else {
				tutorialChances -= 1
				game.handleTrainingEvent()
			}
		} else {
			game.handleTrainingEvent()
		}
	}
	
	fun handleRaceEventsAoHaru() {
		if (aoHaruRaceFirstTime && game.imageUtils.confirmLocation("aoharu_set_initial_team", tries = 5)) {
			game.findAndTapImage("race_accept_trophy")
		}
		
		aoHaruRaceFirstTime = false
		
		if (game.imageUtils.confirmLocation("aoharu_race_header", tries = 5)) {
			// Head to the next screen with the 3 racing options.
			game.findAndTapImage("aoharu_race")
			game.wait(1.0)
			
			// Run the first option if it has more than 3 double circles and if not, run the second option.
			val racingOptions = game.imageUtils.findAll("aoharu_race_option")
			game.gestureUtils.tap(racingOptions[0].x, racingOptions[0].y, "aoharu_race_option")
			game.findAndTapImage("aoharu_select_race")
			game.wait(2.0)
			val doubleCircles = game.imageUtils.findAll("race_prediction_double_circle")
			if (doubleCircles.size >= 3) {
				game.findAndTapImage("aoharu_select_race")
			} else {
				game.findAndTapImage("cancel")
				game.wait(0.5)
				game.gestureUtils.tap(racingOptions[1].x, racingOptions[1].y, "aoharu_race_option")
				game.findAndTapImage("aoharu_select_race")
				game.wait(2.0)
				game.findAndTapImage("aoharu_select_race")
			}
			
			game.wait(3.0)
			
			// Now run the race and skip to the end.
			game.findAndTapImage("aoharu_run_race", tries = 10)
			game.wait(1.0)
			game.findAndTapImage("race_skip_manual", tries = 10)
			game.wait(3.0)
			
			game.findAndTapImage("race_end", tries = 10)
		}
	}
	
	fun start() {
		while (true) {
			////////////////////////////////////////////////
			// Most bot operations start at the Main screen.
			if (game.checkMainScreen()) {
				// If the required skill points has been reached, stop the bot.
				if (game.enableSkillPointCheck && game.imageUtils.determineSkillPoints() >= game.skillPointsRequired) {
					game.printToLog("\n[END] Bot has acquired the set amount of skill points. Exiting now...", tag = tag)
					break
				}
				
				// If the bot detected a injury, then rest.
				if (game.checkInjury()) {
					game.findAndTapImage("ok", region = game.imageUtils.regionMiddle)
					game.wait(3.0)
				} else if (game.recoverMood()) {
					Log.d(tag, "Mood recovered.")
				} else if (!game.checkExtraRaceAvailability()) {
					Log.d(tag, "Training due to not extra race day.")
					game.handleTraining()
				} else {
					Log.d(tag, "Racing by default.")
					if (!game.handleRaceEvents()) {
						if (game.detectedMandatoryRaceCheck) {
							game.printToLog("\n[INFO] Stopping bot due to detection of Mandatory Race.", tag = tag)
							break
						}
						
						Log.d(tag, "Racing by default failed due to not detecting any eligible extra races. Training instead...")
						game.handleTraining()
					}
				}
			} else if (game.checkTrainingEventScreen()) {
				// If the bot is at the Training Event screen, that means there are selectable options for rewards.
				Log.d(tag, "Detected Training Event")
				handleTrainingEventAoHaru()
			} else if (game.handleInheritanceEvent()) {
				// If the bot is at the Inheritance screen, then accept the inheritance.
				game.printToLog("\n[INFO] Accepted the Inheritance.", tag = tag)
			} else if (game.checkMandatoryRacePrepScreen()) {
				// If the bot is at the Main screen with the button to select a race visible, that means the bot needs to handle a mandatory race.
				game.handleRaceEvents()
				
				if (game.detectedMandatoryRaceCheck) {
					game.printToLog("\n[INFO] Stopping bot due to detection of Mandatory Race.", tag = tag)
					break
				}
			} else if (game.imageUtils.findImage("race_change_strategy", tries = 1, region = game.imageUtils.regionBottomHalf).first != null) {
				// If the bot is already at the Racing screen, then complete this standalone race.
				game.handleStandaloneRace()
			} else if (!BotService.isRunning || game.checkEndScreen()) {
				// Stop when the bot has reached the screen where it details the overall result of the run.
				game.printToLog("\n[END] Bot has reached the end of the run. Exiting now...", tag = tag)
				break
			}
			
			// Various miscellaneous checks
			if (!game.performMiscChecks()) {
				break
			}
		}
	}
}