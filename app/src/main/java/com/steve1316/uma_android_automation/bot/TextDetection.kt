package com.steve1316.uma_android_automation.bot

import android.content.Context
import com.steve1316.uma_android_automation.data.CharacterData
import com.steve1316.uma_android_automation.data.SkillData
import com.steve1316.uma_android_automation.data.StatusData
import com.steve1316.uma_android_automation.data.SupportData
import com.steve1316.uma_android_automation.ui.settings.SettingsFragment
import com.steve1316.uma_android_automation.utils.ImageUtils
import com.steve1316.uma_android_automation.utils.NotificationUtils
import net.ricecode.similarity.JaroWinklerStrategy
import net.ricecode.similarity.StringSimilarityServiceImpl

class TextDetection(private val myContext: Context, private val game: Game, private val imageUtils: ImageUtils) {
	private var result = ""
	private var confidence = 0.0
	private var category = ""
	private var eventTitle = ""
	private var supportCardTitle = ""
	private var eventOptionRewards: ArrayList<String> = arrayListOf()
	private var eventOptionNumber = 1
	private var eventOptionSkills: ArrayList<String> = arrayListOf()
	private var eventOptionsSkillsNumbers: ArrayList<Int> = arrayListOf()
	private var eventOptionStatus: ArrayList<String> = arrayListOf()
	private var eventOptionsStatusNumbers: ArrayList<Int> = arrayListOf()
	private var firstLine = true
	private var notificationTextBody: String = ""
	private val notificationTextArray = arrayListOf<String>()
	
	private val character = SettingsFragment.getStringSharedPreference(myContext, "character")
	private val supportCards: List<String> = SettingsFragment.getStringSharedPreference(myContext, "supportList").split("|")
	private var hideResults: Boolean = SettingsFragment.getBooleanSharedPreference(myContext, "hideResults")
	private var selectAllSupportCards: Boolean = SettingsFragment.getBooleanSharedPreference(myContext, "selectAllSupportCards")
	private var minimumConfidence = SettingsFragment.getIntSharedPreference(myContext, "confidence").toDouble() / 100.0
	
	/**
	 * Fix incorrect characters determined by OCR by replacing them with their Japanese equivalents.
	 */
	private fun fixIncorrectCharacters() {
		game.printToLog("\n[INFO] Now attempting to fix incorrect characters in: $result")
		
		if (result.last() == '/') {
			result = result.replace("/", "！")
		}
		
		result = result.replace("(", "（").replace(")", "）")
		game.printToLog("[INFO] Finished attempting to fix incorrect characters: $result")
	}
	
	/**
	 * Attempt to find the most similar string from data compared to the string returned by OCR.
	 */
	private fun findMostSimilarString() {
		if (!hideResults) {
			game.printToLog("\n[INFO] Now starting process to find most similar string to: $result\n")
		} else {
			game.printToLog("\n[INFO] Now starting process to find most similar string to: $result")
		}
		
		// Use the Jaro Winkler algorithm to compare similarities the OCR detected string and the rest of the strings inside the data classes.
		val service = StringSimilarityServiceImpl(JaroWinklerStrategy())
		
		// Attempt to find the most similar string inside the data classes starting with the Character-specific events.
		CharacterData.characters[character]?.forEach { (eventName, eventOptions) ->
			val score = service.score(result, eventName)
			if (!hideResults) {
				game.printToLog("[CHARA] $character \"${result}\" vs. \"${eventName}\" confidence: $score")
			}
			
			if (score >= confidence) {
				confidence = score
				eventTitle = eventName
				eventOptionRewards = eventOptions
				category = "character"
			}
		}
		
		// Now move on to the Character-shared events.
		CharacterData.characters["Shared"]?.forEach { (eventName, eventOptions) ->
			val score = service.score(result, eventName)
			if (!hideResults) {
				game.printToLog("[CHARA-SHARED] \"${result}\" vs. \"${eventName}\" confidence: $score")
			}
			
			if (score >= confidence) {
				confidence = score
				eventTitle = eventName
				eventOptionRewards = eventOptions
				category = "character-shared"
			}
		}
		
		// Finally, do the same with the user-selected Support Cards.
		if (!selectAllSupportCards) {
			supportCards.forEach { supportCardName ->
				SupportData.supports[supportCardName]?.forEach { (eventName, eventOptions) ->
					val score = service.score(result, eventName)
					if (!hideResults) {
						game.printToLog("[SUPPORT] $supportCardName \"${result}\" vs. \"${eventName}\" confidence: $score")
					}
					
					if (score >= confidence) {
						confidence = score
						eventTitle = eventName
						supportCardTitle = supportCardName
						eventOptionRewards = eventOptions
						category = "support"
					}
				}
			}
		} else {
			SupportData.supports.forEach { (supportName, support) ->
				support.forEach { (eventName, eventOptions) ->
					val score = service.score(result, eventName)
					if (!hideResults) {
						game.printToLog("[SUPPORT] $supportName \"${result}\" vs. \"${eventName}\" confidence: $score")
					}
					
					if (score >= confidence) {
						confidence = score
						eventTitle = eventName
						supportCardTitle = supportName
						eventOptionRewards = eventOptions
						category = "support"
					}
				}
			}
		}
		
		if (!hideResults) {
			game.printToLog("\n[INFO] Finished process to find similar string.")
		} else {
			game.printToLog("[INFO] Finished process to find similar string.")
		}
	}
	
	/**
	 * Attempt to find matches for skills and statuses from data inside the event option rewards.
	 */
	private fun checkForSkillsAndStatus() {
		// Clear the following of its contents in case automatic retry was turned on.
		eventOptionStatus.clear()
		eventOptionsStatusNumbers.clear()
		eventOptionSkills.clear()
		eventOptionsSkillsNumbers.clear()
		
		var optionNumber = 1
		eventOptionRewards.forEach { line ->
			StatusData.status.forEach { status ->
				if (status.key in line) {
					eventOptionStatus.add("${status.key};${status.value}")
					eventOptionsStatusNumbers.add(optionNumber)
				}
			}
			
			SkillData.skills.forEach { skill ->
				if (skill.key in line) {
					eventOptionSkills.add("${skill.key};${skill.value["englishName"]};${skill.value["englishDescription"]}")
					eventOptionsSkillsNumbers.add(optionNumber)
				}
			}
			
			optionNumber += 1
		}
	}
	
	/**
	 * Format the reward string if necessary with the contents of the skill and status translations.
	 */
	private fun formatResultForSkillsAndStatus(reward: String, isSingleOption: Boolean = false): String {
		var tempReward = reward
		
		// If there are multiple statuses/skills in the same string, the while loops will make sure that they are all accounted for.
		if (isSingleOption) {
			if (eventOptionStatus.size != 0 && eventOptionSkills.size != 0 && eventOptionsStatusNumbers[0] == eventOptionNumber) {
				tempReward += "\n"
			}
			
			while (eventOptionStatus.size != 0 && eventOptionsStatusNumbers[0] == eventOptionNumber) {
				val tempStatus = eventOptionStatus[0].split(";")
				eventOptionStatus.removeAt(0)
				eventOptionsStatusNumbers.removeAt(0)
				tempReward += "\n${tempStatus[0]} status: \"${tempStatus[1]}\""
			}
			
			while (eventOptionSkills.size != 0 && eventOptionsSkillsNumbers[0] == eventOptionNumber) {
				val tempSkill = eventOptionSkills[0].split(";")
				eventOptionSkills.removeAt(0)
				eventOptionsSkillsNumbers.removeAt(0)
				tempReward += "\n${tempSkill[0]} / ${tempSkill[1]}: \"${tempSkill[2]}\""
			}
		} else {
			if (eventOptionStatus.size != 0 && eventOptionSkills.size != 0 && eventOptionsStatusNumbers[0] == eventOptionNumber) {
				tempReward += "\n"
			}
			
			while (eventOptionStatus.size != 0 && eventOptionsStatusNumbers[0] == eventOptionNumber) {
				val tempStatus = eventOptionStatus[0].split(";")
				eventOptionStatus.removeAt(0)
				eventOptionsStatusNumbers.removeAt(0)
				tempReward += "\n${tempStatus[0]} status: \"${tempStatus[1]}\""
			}
			
			while (eventOptionSkills.size != 0 && eventOptionsSkillsNumbers[0] == eventOptionNumber) {
				val tempSkill = eventOptionSkills[0].split(";")
				eventOptionSkills.removeAt(0)
				eventOptionsSkillsNumbers.removeAt(0)
				tempReward += "\n${tempSkill[0]} / ${tempSkill[1]}: \"${tempSkill[2]}\""
			}
		}
		
		return tempReward
	}
	
	/**
	 * Construct the result's text body and then display it as a Notification.
	 */
	private fun constructNotification(): Boolean {
		// Now construct the text body for the Notification.
		if (confidence >= minimumConfidence) {
			game.printToLog("\n####################")
			game.printToLog("####################")
			
			// Process the resulting string from the acquired information.
			eventOptionRewards.forEach { reward ->
				if (eventOptionRewards.size == 1) {
					if (notificationTextArray.size < 9) {
						val lineList = reward.split("\n")
						lineList.forEach { line ->
							if (notificationTextArray.size < 9) {
								if (firstLine) {
									notificationTextArray.add(line)
									firstLine = false
								} else {
									notificationTextArray.add("\n$line")
								}
							}
						}
					}
					
					// Begin appending to the reward string for english translations of skills and statuses if necessary.
					val tempReward = formatResultForSkillsAndStatus(reward, isSingleOption = true)
					
					game.printToLog("\n\n$tempReward\n", isOption = true)
					eventOptionNumber += 1
				} else {
					if (notificationTextArray.size < 9) {
						val lineList = reward.split("\n")
						if (!firstLine) {
							notificationTextArray.add("\n[OPTION $eventOptionNumber]")
						} else {
							notificationTextArray.add("[OPTION $eventOptionNumber]\n")
						}
						
						lineList.forEach { line ->
							if (notificationTextArray.size < 9) {
								if (firstLine) {
									notificationTextArray.add(line)
									firstLine = false
								} else {
									notificationTextArray.add("\n$line")
								}
							}
						}
					}
					
					// Begin appending to the reward string for english translations of skills and statuses if necessary.
					val tempReward = formatResultForSkillsAndStatus(reward)
					
					game.printToLog("\n[OPTION $eventOptionNumber] \n$tempReward\n", isOption = true)
					eventOptionNumber += 1
				}
			}
			
			game.printToLog("####################")
			game.printToLog("####################\n")
			
			// Append this last line to the Notification's text body if the rest of the text was going to be cut off as the Notification's expanded mode is set only at 256dp.
			if (notificationTextArray.size >= 8) {
				notificationTextArray.removeAt(notificationTextArray.lastIndex)
				notificationTextArray.add("\n*Text is cut off. Please Tap me to see the full text!*")
			}
			
			// Now join the array into its new text body string for the Notification.
			notificationTextBody = notificationTextArray.joinToString("")
			
			// Display the information to the user as a newly updated Notification.
			NotificationUtils.updateNotification(myContext, eventTitle, notificationTextBody, confidence)
			return true
		} else {
			game.printToLog("\n####################")
			game.printToLog("####################")
			game.printToLog("[ERROR] Confidence of $confidence failed to be greater than or equal to the minimum of $minimumConfidence.", isError = true)
			game.printToLog("####################")
			game.printToLog("####################\n")
			return false
		}
	}
	
	fun start() {
		if (minimumConfidence > 1.0) {
			minimumConfidence = 0.8
		}
		
		val threshold = SettingsFragment.getIntSharedPreference(myContext, "threshold").toDouble()
		val enableIncrementalThreshold = SettingsFragment.getBooleanSharedPreference(myContext, "enableIncrementalThreshold")
		var increment = 0.0
		var flag = false
		
		while (!flag) {
			// Perform Tesseract OCR detection.
			if ((255.0 - threshold - increment) > 0.0) {
				result = imageUtils.findText(increment)
			} else {
				break
			}
			
			if (result.isNotEmpty() && result != "empty!") {
				// Make some minor improvements by replacing certain incorrect characters with their Japanese equivalents.
				fixIncorrectCharacters()
				
				// Now attempt to find the most similar string compared to the one from OCR.
				findMostSimilarString()
				
				// Insert english translations and descriptions for any skills and statuses detected in the event rewards.
				checkForSkillsAndStatus()
				
				when (category) {
					"character" -> {
						game.printToLog("\n[RESULT] Character $character Event Name = $eventTitle with confidence = $confidence")
					}
					"character-shared" -> {
						game.printToLog("\n[RESULT] Character $character Shared Event Name = $eventTitle with confidence = $confidence")
					}
					"support" -> {
						game.printToLog("\n[RESULT] Support $supportCardTitle Event Name = $eventTitle with confidence = $confidence")
					}
				}
				
				if (enableIncrementalThreshold) {
					game.printToLog("\n[RESULT] Threshold incremented by $increment")
				}
				
				// Now construct and display the Notification containing the results from OCR, whether it was successful or not.
				flag = constructNotification()
				if (!flag && enableIncrementalThreshold) {
					increment += 5.0
				} else {
					break
				}
			} else {
				increment += 5.0
			}
		}
		
		if (!flag) {
			NotificationUtils.updateNotification(
				myContext, "OCR Failed", "Confidence of $confidence failed to be greater than or equal to the minimum of $minimumConfidence.",
				confidence)
		}
	}
}