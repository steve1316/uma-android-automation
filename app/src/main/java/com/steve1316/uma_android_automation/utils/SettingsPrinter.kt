package com.steve1316.uma_android_automation.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Utility class for printing SharedPreferences settings in a consistent format.
 * Can be used by both HomeFragment and Game.kt to display current bot configuration.
 */
object SettingsPrinter {
	
	/**
	 * Print all current SharedPreferences settings for debugging purposes.
	 * 
	 * @param context The application context
	 * @param printToLog Function to handle logging
	 */
	fun printCurrentSettings(context: Context, printToLog: ((String) -> Unit)? = null): String {
		val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
		
		// Main Settings
		val campaign: String = sharedPreferences.getString("campaign", "")!!
		val enableFarmingFans = sharedPreferences.getBoolean("enableFarmingFans", false)
		val daysToRunExtraRaces: Int = sharedPreferences.getInt("daysToRunExtraRaces", 4)
		val enableSkillPointCheck: Boolean = sharedPreferences.getBoolean("enableSkillPointCheck", false)
		val skillPointCheck: Int = sharedPreferences.getInt("skillPointCheck", 750)
		val enablePopupCheck: Boolean = sharedPreferences.getBoolean("enablePopupCheck", false)
		val enableStopOnMandatoryRace: Boolean = sharedPreferences.getBoolean("enableStopOnMandatoryRace", false)
		val enablePrioritizeEnergyOptions: Boolean = sharedPreferences.getBoolean("enablePrioritizeEnergyOptions", false)
		
		// Training Settings
		val trainingBlacklist: Set<String> = sharedPreferences.getStringSet("trainingBlacklist", setOf<String>()) as Set<String>
		var statPrioritization: List<String> = sharedPreferences.getString("statPrioritization", "")!!.split("|")
		val maximumFailureChance: Int = sharedPreferences.getInt("maximumFailureChance", 15)
		
		// Training Event Settings
		val character = sharedPreferences.getString("character", "Please select one in the Training Event Settings")!!
		val selectAllCharacters = sharedPreferences.getBoolean("selectAllCharacters", true)
		val supportList = sharedPreferences.getString("supportList", "")?.split("|")!!
		val selectAllSupportCards = sharedPreferences.getBoolean("selectAllSupportCards", true)
		
		// OCR Optimization Settings
		val threshold: Int = sharedPreferences.getInt("threshold", 230)
		val enableAutomaticRetry: Boolean = sharedPreferences.getBoolean("enableAutomaticRetry", true)
		val ocrConfidence: Int = sharedPreferences.getInt("ocrConfidence", 80)
		
		// Debug Options
		val debugMode: Boolean = sharedPreferences.getBoolean("debugMode", false)
		val confidence: Int = sharedPreferences.getInt("confidence", 80)
		val customScale: Int = sharedPreferences.getInt("customScale", 100)
		val debugModeStartTemplateMatchingTest: Boolean = sharedPreferences.getBoolean("debugMode_startTemplateMatchingTest", false)
		val debugModeStartSingleTrainingFailureOCRTest: Boolean = sharedPreferences.getBoolean("debugMode_startSingleTrainingFailureOCRTest", false)
		val debugModeStartComprehensiveTrainingFailureOCRTest: Boolean = sharedPreferences.getBoolean("debugMode_startComprehensiveTrainingFailureOCRTest", false)
		val hideComparisonResults: Boolean = sharedPreferences.getBoolean("hideComparisonResults", true)

		// Set default values if this is the user's first time.
		if (statPrioritization.isEmpty() || statPrioritization[0] == "") {
			statPrioritization = listOf("Speed", "Stamina", "Power", "Guts", "Wit")
		}

		////////////////////////////////////////////////////////////////////////////////////////////////////
		////////////////////////////////////////////////////////////////////////////////////////////////////
		
		// Construct display strings.
		val campaignString: String = if (campaign != "") {
			"🎯 $campaign"
		} else {
			"⚠️ Please select one in the Select Campaign option"
		}
		
		val characterString: String = if (selectAllCharacters) {
			"👥 All Characters Selected"
		} else if (character == "" || character.contains("Please select")) {
			"⚠️ Please select one in the Training Event Settings"
		} else {
			"👤 $character"
		}
		
		val supportCardListString: String = if (selectAllSupportCards) {
			"🃏 All Support Cards Selected"
		} else if (supportList.isEmpty() || supportList[0] == "") {
			"⚠️ None Selected"
		} else {
			"�� ${supportList.joinToString(", ")}"
		}
		
		val trainingBlacklistString: String = if (trainingBlacklist.isEmpty()) {
			"✅ No Trainings blacklisted"
		} else {
			val defaultTrainingOrder = listOf("Speed", "Stamina", "Power", "Guts", "Wit")
			val sortedBlacklist = trainingBlacklist.sortedBy { defaultTrainingOrder.indexOf(it) }
			"🚫 ${sortedBlacklist.joinToString(", ")}"
		}
		
		val statPrioritizationString: String = if (statPrioritization.isEmpty() || statPrioritization[0] == "") {
			"�� Using Default Stat Prioritization: Speed, Stamina, Power, Guts, Wit"
		} else {
			"📊 Stat Prioritization: ${statPrioritization.joinToString(", ")}"
		}

		////////////////////////////////////////////////////////////////////////////////////////////////////
		////////////////////////////////////////////////////////////////////////////////////////////////////
		
		// Build the settings string.
		val settingsString = buildString {
			appendLine("Campaign Selected: $campaignString")
			appendLine()
			appendLine("---------- Training Event Options ----------")
			appendLine("Character Selected: $characterString")
			appendLine("Support(s) Selected: $supportCardListString")
			appendLine()
			appendLine("---------- Training Options ----------")
			appendLine("Training Blacklist: $trainingBlacklistString")
			appendLine(statPrioritizationString)
			appendLine("Maximum Failure Chance Allowed: $maximumFailureChance%")
			appendLine()
			appendLine("---------- Tesseract OCR Optimization ----------")
			appendLine("OCR Threshold: $threshold")
			appendLine("Enable Automatic OCR retry: ${if (enableAutomaticRetry) "✅" else "❌"}")
			appendLine("Minimum OCR Confidence: $ocrConfidence")
			appendLine()
			appendLine("---------- Misc Options ----------")
			appendLine("Prioritize Farming Fans: ${if (enableFarmingFans) "✅" else "❌"}")
			appendLine("Modulo Days to Farm Fans: ${if (enableFarmingFans) "📅 $daysToRunExtraRaces days" else "❌"}")
			appendLine("Skill Point Check: ${if (enableSkillPointCheck) "✅ Stop on $skillPointCheck Skill Points or more" else "❌"}")
			appendLine("Popup Check: ${if (enablePopupCheck) "✅" else "❌"}")
			appendLine("Stop on Mandatory Race: ${if (enableStopOnMandatoryRace) "✅" else "❌"}")
			appendLine("Prioritize Energy Options: ${if (enablePrioritizeEnergyOptions) "✅" else "❌"}")
			appendLine()
			appendLine("---------- Debug Options ----------")
			appendLine("Debug Mode: ${if (debugMode) "✅" else "❌"}")
			appendLine("Minimum Template Match Confidence: $confidence")
			appendLine("Custom Scale: ${customScale.toDouble() / 100.0}")
			appendLine("Start Template Matching Test: ${if (debugModeStartTemplateMatchingTest) "✅" else "❌"}")
			appendLine("Start Single Training Failure OCR Test: ${if (debugModeStartSingleTrainingFailureOCRTest) "✅" else "❌"}")
			appendLine("Start Comprehensive Training Failure OCR Test: ${if (debugModeStartComprehensiveTrainingFailureOCRTest) "✅" else "❌"}")
			appendLine("Hide String Comparison Results: ${if (hideComparisonResults) "✅" else "❌"}")
		}

		////////////////////////////////////////////////////////////////////////////////////////////////////
		////////////////////////////////////////////////////////////////////////////////////////////////////
		
		// Use the provided printToLog function if available. Otherwise return the string.
		if (printToLog != null) {
			printToLog("\n[SETTINGS] Current Bot Configuration:")
			printToLog("=====================================")
			settingsString.split("\n").forEach { line ->
				if (line.isNotEmpty()) {
					printToLog(line)
				}
			}
			printToLog("=====================================\n")
		}

		return settingsString
	}
	
	/**
	 * Get the formatted settings string for display in UI components.
	 * 
	 * @param context The application context
	 * @return Formatted string containing all current settings
	 */
	fun getSettingsString(context: Context): String {
		return printCurrentSettings(context)
	}
} 