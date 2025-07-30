package com.steve1316.uma_android_automation.bot

import android.util.Log
import com.steve1316.uma_android_automation.MainActivity

/**
 * Base campaign class that contains all shared logic for campaign automation.
 * Campaign-specific logic should be implemented in subclasses by overriding the appropriate methods.
 * By default, URA Finale is handled by this base class.
 */
open class Campaign(val game: Game) {
	protected val tag: String = "[${MainActivity.Companion.loggerTag}]Normal"

	/**
	 * Campaign-specific training event handling.
	 */
	open fun handleTrainingEvent() {
		game.handleTrainingEvent()
	}

	/**
	 * Campaign-specific race event handling.
	 */
	open fun handleRaceEvents(): Boolean {
		return game.handleRaceEvents()
	}

	/**
	 * Campaign-specific checks for special screens or conditions.
	 */
	open fun checkCampaignSpecificConditions(): Boolean {
		return false
	}

	/**
	 * Main automation loop that handles all shared logic.
	 */
	fun start() {
		while (true) {
			////////////////////////////////////////////////
			// Most bot operations start at the Main screen.
			if (game.checkMainScreen()) {
				// If the required skill points has been reached, stop the bot.
				if (game.enableSkillPointCheck && game.imageUtils.determineSkillPoints() >= game.skillPointsRequired) {
					game.printToLog("\n[END] Bot has acquired the set amount of skill points. Exiting now...", tag = tag)
					game.notificationMessage = "Bot has acquired the set amount of skill points."
					break
				}

				// If the bot detected a injury, then rest.
				if (!game.failedFanCheck && game.checkInjury()) {
					game.printToLog("[INFO] A infirmary visit was attempted in order to heal an injury.", tag = tag)
					game.findAndTapImage("ok", region = game.imageUtils.regionMiddle)
					game.wait(3.0)
				} else if (!game.failedFanCheck && game.recoverMood()) {
					game.printToLog("[INFO] Mood has recovered.", tag = tag)
				} else if (!game.failedFanCheck && !game.checkExtraRaceAvailability()) {
					game.printToLog("[INFO] Training due to it not being an extra race day.", tag = tag)
					game.handleTraining()
				} else {
					game.printToLog("[INFO] Racing by default.", tag = tag)
					if (!handleRaceEvents()) {
						if (game.detectedMandatoryRaceCheck) {
							game.printToLog("\n[END] Stopping bot due to detection of Mandatory Race.", tag = tag)
							game.notificationMessage = "Stopping bot due to detection of Mandatory Race."
							break
						}

						game.printToLog("[INFO] Racing by default failed due to not detecting any eligible extra races. Training instead...", tag = tag)
						game.handleTraining()
					}
				}
			} else if (game.checkTrainingEventScreen()) {
				// If the bot is at the Training Event screen, that means there are selectable options for rewards.
				game.printToLog("[INFO] Detected a Training Event on screen.", tag = tag)
				handleTrainingEvent()
			} else if (game.handleInheritanceEvent()) {
				// If the bot is at the Inheritance screen, then accept the inheritance.
				game.printToLog("[INFO] Accepted the Inheritance.", tag = tag)
			} else if (game.checkMandatoryRacePrepScreen()) {
				game.printToLog("[INFO] There is a Mandatory race to be run.", tag = tag)
				// If the bot is at the Main screen with the button to select a race visible, that means the bot needs to handle a mandatory race.
				if (!handleRaceEvents() && game.detectedMandatoryRaceCheck) {
					game.printToLog("\n[END] Stopping bot due to detection of Mandatory Race.", tag = tag)
					game.notificationMessage = "Stopping bot due to detection of Mandatory Race."
					break
				}
			} else if (game.imageUtils.findImage("race_change_strategy", tries = 1, region = game.imageUtils.regionBottomHalf).first != null) {
				// If the bot is already at the Racing screen, then complete this standalone race.
				game.printToLog("[INFO] There is a standalone race ready to be run.", tag = tag)
				game.handleStandaloneRace()
			} else if (game.checkEndScreen()) {
				// Stop when the bot has reached the screen where it details the overall result of the run.
				game.printToLog("\n[END] Bot has reached the end of the run. Exiting now...", tag = tag)
				game.notificationMessage = "Bot has reached the end of the run"
				break
			} else if (checkCampaignSpecificConditions()) {
				game.printToLog("[INFO] Campaign-specific checks complete.", tag = tag)
				continue
			} else {
				game.printToLog("[INFO] Did not detect the bot being at the following screens: Main, Training Event, Inheritance, Mandatory Race Preparation, Racing and Career End.", tag = tag)
			}

			// Various miscellaneous checks
			if (!game.performMiscChecks()) {
				break
			}
		}
	}
}