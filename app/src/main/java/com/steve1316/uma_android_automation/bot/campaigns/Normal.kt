package com.steve1316.uma_android_automation.bot.campaigns

import android.util.Log
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.utils.BotService

class Normal(private val game: Game) {
	private val tag: String = "[${MainActivity.loggerTag}]Normal"
	
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
				game.handleTrainingEvent()
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