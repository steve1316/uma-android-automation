package com.steve1316.uma_android_automation.bot

import android.content.Context
import com.steve1316.uma_android_automation.data.CharacterData
import com.steve1316.uma_android_automation.data.SkillData
import com.steve1316.uma_android_automation.data.StatusData
import com.steve1316.uma_android_automation.data.SupportData
import com.steve1316.uma_android_automation.ui.settings.SettingsFragment
import com.steve1316.uma_android_automation.utils.ImageUtils
import net.ricecode.similarity.JaroWinklerStrategy
import net.ricecode.similarity.StringSimilarityServiceImpl

class TextDetection(private val myContext: Context, private val game: Game, private val imageUtils: ImageUtils) {
	private var result = ""
	private var confidence = 0.0
	private var category = ""
	private var eventTitle = ""
	private var supportCardTitle = ""
	private var eventOptionRewards: ArrayList<String> = arrayListOf()
	private var eventOptionSkills: ArrayList<String> = arrayListOf()
	private var eventOptionsSkillsNumbers: ArrayList<Int> = arrayListOf()
	private var eventOptionStatus: ArrayList<String> = arrayListOf()
	private var eventOptionsStatusNumbers: ArrayList<Int> = arrayListOf()
	
	private val character = SettingsFragment.getStringSharedPreference(myContext, "character")
	private val supportCards: List<String> = SettingsFragment.getStringSharedPreference(myContext, "supportList").split("|")
	private var hideResults: Boolean = SettingsFragment.getBooleanSharedPreference(myContext, "hideResults")
	private var selectAllSupportCards: Boolean = SettingsFragment.getBooleanSharedPreference(myContext, "selectAllSupportCards")
	private var minimumConfidence = SettingsFragment.getIntSharedPreference(myContext, "confidence").toDouble() / 100.0
	private val threshold = SettingsFragment.getIntSharedPreference(myContext, "threshold").toDouble()
	private val enableIncrementalThreshold = SettingsFragment.getBooleanSharedPreference(myContext, "enableIncrementalThreshold")
	
	/**
	 * Fix incorrect characters determined by OCR by replacing them with their Japanese equivalents.
	 */
	private fun fixIncorrectCharacters() {
		game.printToLog("\n[TEXT-DETECTION] Now attempting to fix incorrect characters in: $result")
		
		if (result.last() == '/') {
			result = result.replace("/", "！")
		}
		
		result = result.replace("(", "（").replace(")", "）")
		game.printToLog("[TEXT-DETECTION] Finished attempting to fix incorrect characters: $result")
	}
	
	/**
	 * Attempt to find the most similar string from data compared to the string returned by OCR.
	 */
	private fun findMostSimilarString() {
		if (!hideResults) {
			game.printToLog("\n[TEXT-DETECTION] Now starting process to find most similar string to: $result\n")
		} else {
			game.printToLog("\n[TEXT-DETECTION] Now starting process to find most similar string to: $result")
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
			game.printToLog("\n[TEXT-DETECTION] Finished process to find similar string.")
		} else {
			game.printToLog("[TEXT-DETECTION] Finished process to find similar string.")
		}
	}
	
	fun start(): ArrayList<String> {
		if (minimumConfidence > 1.0) {
			minimumConfidence = 0.8
		}
		
		var increment = 0.0
		
		while (true) {
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
				
				if (enableIncrementalThreshold && !hideResults) {
					game.printToLog("\n[RESULT] Threshold incremented by $increment")
				}
				
				if (confidence < minimumConfidence && enableIncrementalThreshold) {
					increment += 5.0
				} else if (confidence >= minimumConfidence) {
					break
				}
			} else if (enableIncrementalThreshold) {
				increment += 5.0
			}
		}
		
		return eventOptionRewards
	}
}