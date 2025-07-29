package com.steve1316.uma_android_automation.bot

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.data.CharacterData
import com.steve1316.uma_android_automation.data.SupportData
import com.steve1316.uma_android_automation.utils.ImageUtils
import net.ricecode.similarity.JaroWinklerStrategy
import net.ricecode.similarity.StringSimilarityServiceImpl

class TextDetection(private val game: Game, private val imageUtils: ImageUtils) {
	private val tag: String = "[${MainActivity.loggerTag}]TextDetection"
	
	private var sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(game.myContext)
	
	private var result = ""
	private var confidence = 0.0
	private var category = ""
	private var eventTitle = ""
	private var supportCardTitle = ""
	private var eventOptionRewards: ArrayList<String> = arrayListOf()
	
	private var character = sharedPreferences.getString("character", "")!!
	private val supportCards: List<String> = sharedPreferences.getString("supportList", "")!!.split("|")
	private val hideComparisonResults: Boolean = sharedPreferences.getBoolean("hideComparisonResults", false)
	private val selectAllCharacters: Boolean = sharedPreferences.getBoolean("selectAllCharacters", true)
	private val selectAllSupportCards: Boolean = sharedPreferences.getBoolean("selectAllSupportCards", true)
	private var minimumConfidence = sharedPreferences.getInt("ocrConfidence", 80).toDouble() / 100.0
	private val threshold = sharedPreferences.getInt("threshold", 230).toDouble()
	private val enableAutomaticRetry = sharedPreferences.getBoolean("enableAutomaticRetry", false)
	
	/**
	 * Fix incorrect characters determined by OCR by replacing them with their Japanese equivalents.
	 */
	private fun fixIncorrectCharacters() {
		game.printToLog("\n[TEXT-DETECTION] Now attempting to fix incorrect characters in: $result", tag = tag)
		
		if (result.last() == '/') {
			result = result.replace("/", "！")
		}
		
		result = result.replace("(", "（").replace(")", "）")
		game.printToLog("[TEXT-DETECTION] Finished attempting to fix incorrect characters: $result", tag = tag)
	}
	
	/**
	 * Attempt to find the most similar string from data compared to the string returned by OCR.
	 */
	private fun findMostSimilarString() {
		if (!hideComparisonResults) {
			game.printToLog("\n[TEXT-DETECTION] Now starting process to find most similar string to: $result\n", tag = tag)
		} else {
			game.printToLog("\n[TEXT-DETECTION] Now starting process to find most similar string to: $result", tag = tag)
		}
		
		// Remove any detected whitespaces.
		result = result.replace(" ", "")
		
		// Use the Jaro Winkler algorithm to compare similarities the OCR detected string and the rest of the strings inside the data classes.
		val service = StringSimilarityServiceImpl(JaroWinklerStrategy())
		
		// Attempt to find the most similar string inside the data classes starting with the Character-specific events.
		if (selectAllCharacters) {
			CharacterData.characters.keys.forEach { characterKey ->
				CharacterData.characters[characterKey]?.forEach { (eventName, eventOptions) ->
					val score = service.score(result, eventName)
					if (!hideComparisonResults) {
						game.printToLog("[CHARA] $characterKey \"${result}\" vs. \"${eventName}\" confidence: $score", tag = tag)
					}
					
					if (score >= confidence) {
						confidence = score
						eventTitle = eventName
						eventOptionRewards = eventOptions
						category = "character"
						character = characterKey
					}
				}
			}
		} else {
			CharacterData.characters[character]?.forEach { (eventName, eventOptions) ->
				val score = service.score(result, eventName)
				if (!hideComparisonResults) {
					game.printToLog("[CHARA] $character \"${result}\" vs. \"${eventName}\" confidence: $score", tag = tag)
				}
				
				if (score >= confidence) {
					confidence = score
					eventTitle = eventName
					eventOptionRewards = eventOptions
					category = "character"
				}
			}
		}
		
		// Now move on to the Character-shared events.
		CharacterData.characters["Shared"]?.forEach { (eventName, eventOptions) ->
			val score = service.score(result, eventName)
			if (!hideComparisonResults) {
				game.printToLog("[CHARA-SHARED] \"${result}\" vs. \"${eventName}\" confidence: $score", tag = tag)
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
					if (!hideComparisonResults) {
						game.printToLog("[SUPPORT] $supportCardName \"${result}\" vs. \"${eventName}\" confidence: $score", tag = tag)
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
					if (!hideComparisonResults) {
						game.printToLog("[SUPPORT] $supportName \"${result}\" vs. \"${eventName}\" confidence: $score", tag = tag)
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
		
		if (!hideComparisonResults) {
			game.printToLog("\n[TEXT-DETECTION] Finished process to find similar string.", tag = tag)
		} else {
			game.printToLog("[TEXT-DETECTION] Finished process to find similar string.", tag = tag)
		}
	}
	
	fun start(): Pair<ArrayList<String>, Double> {
		if (minimumConfidence > 1.0) {
			minimumConfidence = 0.8
		}
		
		// Reset to default values.
		result = ""
		confidence = 0.0
		category = ""
		eventTitle = ""
		supportCardTitle = ""
		eventOptionRewards.clear()
		
		var increment = 0.0
		
		val startTime: Long = System.currentTimeMillis()
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
						if (!hideComparisonResults) {
							game.printToLog("\n[RESULT] Character $character Event Name = $eventTitle with confidence = $confidence", tag = tag)
						}
					}
					"character-shared" -> {
						if (!hideComparisonResults) {
							game.printToLog("\n[RESULT] Character Shared Event Name = $eventTitle with confidence = $confidence", tag = tag)
						}
					}
					"support" -> {
						if (!hideComparisonResults) {
							game.printToLog("\n[RESULT] Support $supportCardTitle Event Name = $eventTitle with confidence = $confidence", tag = tag)
						}
					}
				}
				
				if (enableAutomaticRetry && !hideComparisonResults) {
					game.printToLog("\n[RESULT] Threshold incremented by $increment", tag = tag)
				}
				
				if (confidence < minimumConfidence && enableAutomaticRetry) {
					increment += 5.0
				} else {
					break
				}
			} else {
				increment += 5.0
			}
		}
		
		val endTime: Long = System.currentTimeMillis()
		Log.d(tag, "Total Runtime for detecting Text: ${endTime - startTime}ms")
		
		return Pair(eventOptionRewards, confidence)
	}
}