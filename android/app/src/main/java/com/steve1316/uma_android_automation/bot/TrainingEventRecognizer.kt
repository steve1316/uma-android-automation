package com.steve1316.uma_android_automation.bot

import android.util.Log
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.utils.CustomImageUtils
import net.ricecode.similarity.JaroWinklerStrategy
import net.ricecode.similarity.StringSimilarityServiceImpl
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Recognizes training events by performing OCR on event titles and matching them against known character and support card event data using string similarity algorithms.
 *
 * @property game The [Game] instance for interacting with the game state.
 * @property imageUtils The [CustomImageUtils] instance for image processing and OCR.
 */
class TrainingEventRecognizer(private val game: Game, private val imageUtils: CustomImageUtils) {
    /** Map of special event matching patterns used to filter false positives during detection. */
    val eventPatterns =
        mapOf(
            "New Year's Resolutions" to listOf("New Year's Resolutions", "Resolutions"),
            "New Year's Shrine Visit" to listOf("New Year's Shrine Visit", "Shrine Visit"),
            "Victory!" to listOf("Victory!"),
            "Solid Showing" to listOf("Solid Showing"),
            "Defeat" to listOf("Defeat"),
            "Get Well Soon!" to listOf("Get Well Soon"),
            "Don't Overdo It!" to listOf("Don't Overdo It"),
            "Extra Training" to listOf("Extra Training"),
            "Acupuncture (Just an Acupuncturist, No Worries! ☆)" to listOf("Acupuncture", "Just an Acupuncturist"),
            "Etsuko's Exhaustive Coverage" to listOf("Etsuko", "Exhaustive Coverage"),
            "Tutorial" to listOf("Tutorial"),
            "A Team at Last" to listOf("A Team at Last", "Team at Last"),
        )

    /** Character event data loaded from SQLite settings. This contains the mapping of character events to their options and rewards. */
    private val characterEventData: JSONObject? =
        try {
            val characterDataString = SettingsHelper.getStringSetting("trainingEvent", "characterEventData")
            if (characterDataString.isNotEmpty()) {
                val jsonObject = JSONObject(characterDataString)
                if (game.debugMode) MessageLog.d(TAG, "[DEBUG] characterEventData:: Data length from SQLite: ${jsonObject.length()}.")
                jsonObject
            } else {
                null
            }
        } catch (e: Exception) {
            if (game.debugMode) MessageLog.d(TAG, "[DEBUG] characterEventData:: Failed to load character event data from SQLite: ${e.message}")
            null
        }

    /** Support event data loaded from SQLite settings. This contains the mapping of support card events to their options and rewards. */
    private val supportEventData: JSONObject? =
        try {
            val supportDataString = SettingsHelper.getStringSetting("trainingEvent", "supportEventData")
            if (supportDataString.isNotEmpty()) {
                val jsonObject = JSONObject(supportDataString)
                if (game.debugMode) MessageLog.d(TAG, "[DEBUG] supportEventData:: Data length from SQLite: ${jsonObject.length()}.")
                jsonObject
            } else {
                null
            }
        } catch (e: Exception) {
            if (game.debugMode) MessageLog.d(TAG, "[DEBUG] supportEventData:: Failed to load support event data from SQLite: ${e.message}")
            null
        }

    /** Scenario event data loaded from SQLite settings. This contains the mapping of scenario-specific events to their options and rewards. */
    private val scenarioEventData: JSONObject? =
        try {
            val scenarioDataString = SettingsHelper.getStringSetting("trainingEvent", "scenarioEventData")
            if (scenarioDataString.isNotEmpty()) {
                val jsonObject = JSONObject(scenarioDataString)
                if (game.debugMode) MessageLog.d(TAG, "[DEBUG] scenarioEventData:: Data length from SQLite: ${jsonObject.length()}.")
                jsonObject
            } else {
                null
            }
        } catch (e: Exception) {
            if (game.debugMode) MessageLog.d(TAG, "[DEBUG] scenarioEventData:: Failed to load scenario event data from SQLite: ${e.message}")
            null
        }

    /** Whether to hide OCR comparison results in the log output. */
    private val hideComparisonResults: Boolean = SettingsHelper.getBooleanSetting("trainingEvent", "enableHideOCRComparisonResults")

    /** The minimum confidence score required for an OCR match to be accepted immediately. */
    private val minimumConfidence = SettingsHelper.getIntSetting("trainingEvent", "ocrConfidence").toDouble() / 100.0

    /** The grayscale threshold used for OCR pre-processing. */
    private val threshold = SettingsHelper.getIntSetting("debug", "ocrThreshold").toDouble()

    /** Whether to automatically retry OCR detection with different thresholds if confidence is low. */
    private val enableAutomaticRetry = SettingsHelper.getBooleanSetting("trainingEvent", "enableAutomaticOCRRetry")

    /** Service for calculating string similarity using the Jaro-Winkler algorithm. */
    private val stringSimilarityService = StringSimilarityServiceImpl(JaroWinklerStrategy())

    /** Cache used to store OCR matching results and avoid redundant string comparisons. */
    private val ocrMatchingCache = mutableMapOf<String, MatchingResult>()

    /**
     * The result of recognizing a training event.
     *
     * @property eventOptionRewards The list of reward strings for each option.
     * @property confidence The confidence score of the best match.
     * @property eventTitle The title of the matched database event.
     * @property rawOcrTitle The raw OCR'd title text before fuzzy-matching to a known event. Used to detect dynamic events (e.g. the Happy Meek duel) that are not in the event database.
     * @property characterOrSupportName The name of the character or support card that owns the matched event.
     */
    data class RecognizedEvent(
        val eventOptionRewards: ArrayList<String>,
        val confidence: Double,
        val eventTitle: String,
        val rawOcrTitle: String,
        val characterOrSupportName: String,
    )

    /**
     * Store the result of finding the most similar string in the event data.
     *
     * @property confidence The similarity score between the OCR result and the event title.
     * @property category The category of the event (either "character" or "support").
     * @property eventTitle The title of the matched event.
     * @property supportCardTitle The name of the support card if it is a support event.
     * @property eventOptionRewards The list of rewards for each option in the event.
     * @property character The name of the character if it is a character event.
     */
    private data class MatchingResult(
        val confidence: Double,
        val category: String,
        val eventTitle: String,
        val supportCardTitle: String,
        val eventOptionRewards: ArrayList<String>,
        val character: String,
    )

    companion object {
        private val TAG: String = "[${MainActivity.loggerTag}]TrainingEventRecognizer"
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Find the most similar string from the event data compared to the OCR result.
     *
     * @param ocrResult The string results from OCR detection.
     * @return A [MatchingResult] containing the best match found, or default values if no match is found.
     */
    private fun findMostSimilarString(ocrResult: String): MatchingResult {
        MessageLog.i(TAG, "[TRAINING_EVENT_RECOGNIZER] Now starting process to find most similar string to: $ocrResult")

        // Check the cache first to avoid redundant similarity calculations.
        ocrMatchingCache[ocrResult]?.let {
            MessageLog.i(TAG, "[TRAINING_EVENT_RECOGNIZER] Using cached result for: $ocrResult")
            return it
        }

        // Initialize result variables with default values.
        var confidence = 0.0
        var category = ""
        var eventTitle = ""
        var supportCardTitle = ""
        var eventOptionRewards: ArrayList<String> = arrayListOf()
        var character = ""

        // Filter false positives by checking against special event patterns first.
        var matchedSpecialEvent: String? = null
        for ((eventName, patterns) in eventPatterns) {
            if (patterns.any { pattern -> ocrResult.contains(pattern) }) {
                matchedSpecialEvent = eventName
                break
            }
        }
        val isSpecialEvent = matchedSpecialEvent != null
        if (isSpecialEvent) {
            MessageLog.i(TAG, "[TRAINING_EVENT_RECOGNIZER] Detected special event pattern: $matchedSpecialEvent. Will restrict search to this event.")
            eventTitle = matchedSpecialEvent
        }

        // Standardize the OCR result for comparison by removing progression symbols, newlines, and whitespaces.
        val processedResult = cleanTitle(ocrResult)

        // Search for the most similar string within the scenario event data specifically for the current scenario.
        scenarioEventData?.optJSONObject(game.scenario)?.let { scenarioEvents ->
            scenarioEvents.keys().forEach { eventName ->
                // Skip if this is a special event and the name does not match the detected pattern.
                if (isSpecialEvent && eventName != matchedSpecialEvent) {
                    return@forEach
                }

                val eventOptionsArray = scenarioEvents.getJSONArray(eventName)
                val eventOptions = ArrayList<String>()
                for (i in 0 until eventOptionsArray.length()) {
                    eventOptions.add(eventOptionsArray.getString(i))
                }

                // Calculate similarity score between standardized OCR result and known event name.
                val cleanedEventName = cleanTitle(eventName)
                val score = stringSimilarityService.score(processedResult, cleanedEventName)
                if (!hideComparisonResults) {
                    MessageLog.i(
                        TAG,
                        "[SCENARIO] ${game.scenario} \"${processedResult}\" vs. \"${cleanedEventName}\" (from \"${eventName}\") confidence: ${game.decimalFormat.format(score)}",
                    )
                }

                if (score >= confidence) {
                    confidence = score
                    eventTitle = eventName
                    eventOptionRewards = eventOptions
                    category = "character"
                    character = game.scenario

                    // Return early if we find a match that meets the minimum confidence criteria.
                    if (score >= minimumConfidence) {
                        val result = MatchingResult(confidence, category, eventTitle, supportCardTitle, eventOptionRewards, character)
                        ocrMatchingCache[ocrResult] = result
                        return result
                    }
                }
            }
        }

        // Search for the most similar string within the character event data.
        characterEventData?.keys()?.forEach { characterKey ->
            val characterEvents = characterEventData.getJSONObject(characterKey)
            characterEvents.keys().forEach { eventName ->
                // Skip if this is a special event and the name does not match the detected pattern.
                if (isSpecialEvent && eventName != matchedSpecialEvent) {
                    return@forEach
                }

                val eventOptionsArray = characterEvents.getJSONArray(eventName)
                val eventOptions = ArrayList<String>()
                for (i in 0 until eventOptionsArray.length()) {
                    eventOptions.add(eventOptionsArray.getString(i))
                }

                // Calculate similarity score between standardized OCR result and known event name.
                val cleanedEventName = cleanTitle(eventName)
                val score = stringSimilarityService.score(processedResult, cleanedEventName)
                if (!hideComparisonResults) {
                    MessageLog.i(TAG, "[CHARACTER] $characterKey \"${processedResult}\" vs. \"${cleanedEventName}\" (from \"${eventName}\") confidence: ${game.decimalFormat.format(score)}")
                }

                if (score >= confidence) {
                    confidence = score
                    eventTitle = eventName
                    eventOptionRewards = eventOptions
                    category = "character"
                    character = characterKey

                    // Return early if we find a match that meets the minimum confidence criteria.
                    if (score >= minimumConfidence) {
                        val result = MatchingResult(confidence, category, eventTitle, supportCardTitle, eventOptionRewards, character)
                        ocrMatchingCache[ocrResult] = result
                        return result
                    }
                }
            }
        }

        // Search for the most similar string within the support card event data.
        supportEventData?.keys()?.forEach { supportName ->
            val supportEvents = supportEventData.getJSONObject(supportName)
            supportEvents.keys().forEach { eventName ->
                // Skip if this is a special event and the name does not match the detected pattern.
                if (isSpecialEvent && eventName != matchedSpecialEvent) {
                    return@forEach
                }

                val eventOptionsArray = supportEvents.getJSONArray(eventName)
                val eventOptions = ArrayList<String>()
                for (i in 0 until eventOptionsArray.length()) {
                    eventOptions.add(eventOptionsArray.getString(i))
                }

                // Calculate similarity score between standardized OCR result and known event name.
                val cleanedEventName = cleanTitle(eventName)
                val score = stringSimilarityService.score(processedResult, cleanedEventName)
                if (!hideComparisonResults) {
                    MessageLog.i(TAG, "[SUPPORT] $supportName \"${processedResult}\" vs. \"${cleanedEventName}\" (from \"${eventName}\") confidence: $score")
                }

                if (score >= confidence) {
                    confidence = score
                    eventTitle = eventName
                    supportCardTitle = supportName
                    eventOptionRewards = eventOptions
                    category = "support"

                    // Return early if we find a match that meets the minimum confidence criteria.
                    if (score >= minimumConfidence) {
                        val result = MatchingResult(confidence, category, eventTitle, supportCardTitle, eventOptionRewards, character)
                        ocrMatchingCache[ocrResult] = result
                        return result
                    }
                }
            }
        }

        MessageLog.i(TAG, "${if (!hideComparisonResults) "\n" else ""}[TRAINING_EVENT_RECOGNIZER] Finished process to find similar string.")
        MessageLog.i(TAG, "[TRAINING_EVENT_RECOGNIZER] Event data fetched for \"${eventTitle}\".")

        // Cache the result before returning.
        val result = MatchingResult(confidence, category, eventTitle, supportCardTitle, eventOptionRewards, character)
        ocrMatchingCache[ocrResult] = result
        return result
    }

    /**
     * Standardizes the event title by removing progression symbols, newlines, and whitespaces.
     *
     * @param title The event title to clean.
     * @return The cleaned and standardized event title.
     */
    private fun cleanTitle(title: String): String {
        // Remove progression symbols like (❯), (❯❯), (❯❯❯) and their variations.
        val cleanedProgression = title.replace(Regex("""\([❯❮]+\)"""), "")
        // Remove newlines and whitespaces to standardize the result for comparison.
        return cleanedProgression.replace("\n", "").replace(" ", "").replace("\r", "")
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Start the training event recognition process.
     *
     * This method performs OCR on the event title and matches it against known event data. If the confidence is low and automatic retry is enabled, it increments the threshold and retries.
     *
     * @return A [RecognizedEvent] containing the event option rewards, confidence score, matched event title, raw OCR'd title, and character/support name.
     */
    fun start(): RecognizedEvent {
        // Initialize the best result found with default values.
        var bestResult = MatchingResult(0.0, "", "", "", arrayListOf(), "")

        // The raw OCR'd title that produced bestResult, kept so dynamic events not in the database (e.g. the Happy Meek duel) can be dispatched on the actual on-screen title.
        var bestRawTitle = ""

        var increment = 0.0

        val startTime: Long = System.currentTimeMillis()
        while (true) {
            // Perform Tesseract OCR detection on the event title region.
            val ocrResult: String =
                if ((255.0 - threshold - increment) > 0.0) {
                    imageUtils.findEventTitle(increment)
                } else {
                    break
                }

            if (ocrResult.isNotEmpty() && ocrResult != "") {
                // Attempt to find the most similar string compared to the OCR result.
                val matchingResult = findMostSimilarString(ocrResult)
                if (matchingResult.eventTitle.isNotEmpty() && eventPatterns.containsKey(matchingResult.eventTitle)) {
                    MessageLog.i(TAG, "[TRAINING_EVENT_RECOGNIZER] Special event \"${matchingResult.eventTitle}\" detected.")
                    bestResult = matchingResult
                    bestRawTitle = ocrResult
                    break
                }

                // Update the best result if the current matching result has higher confidence.
                if (matchingResult.confidence >= bestResult.confidence) {
                    bestResult = matchingResult
                    bestRawTitle = ocrResult
                }

                // Log the result of the recognition attempt.
                when (matchingResult.category) {
                    "character" -> {
                        MessageLog.i(
                            TAG,
                            "\n[TRAINING_EVENT_RECOGNIZER] Character ${matchingResult.character} Event Name = ${matchingResult.eventTitle} with confidence = ${
                                game.decimalFormat.format(
                                    matchingResult.confidence,
                                )
                            }",
                        )
                    }

                    "support" -> {
                        MessageLog.i(
                            TAG,
                            "\n[TRAINING_EVENT_RECOGNIZER] Support ${matchingResult.supportCardTitle} Event Name = ${matchingResult.eventTitle} with confidence = ${
                                game.decimalFormat.format(matchingResult.confidence)
                            }",
                        )
                    }
                }

                if (enableAutomaticRetry && !hideComparisonResults) {
                    MessageLog.i(TAG, "\n[TRAINING_EVENT_RECOGNIZER] Threshold incremented by $increment")
                }

                // Round the confidence score to two decimal places for comparison.
                val roundedConfidence = (matchingResult.confidence * 100.0).roundToInt() / 100.0
                if (roundedConfidence < minimumConfidence && enableAutomaticRetry) {
                    // Increment the threshold and retry detection if confidence is below the minimum.
                    increment += 5.0
                } else {
                    break
                }
            } else {
                // Increment the threshold and retry detection if no OCR result was found.
                increment += 5.0
            }
        }

        val endTime: Long = System.currentTimeMillis()
        Log.d(TAG, "[DEBUG] recognizeTrainingEvent:: Total Runtime for recognizing training event: ${endTime - startTime}ms")

        // Determine the name of the character or support card associated with the best result.
        val characterOrSupportName =
            when (bestResult.category) {
                "character" -> bestResult.character
                "support" -> bestResult.supportCardTitle
                else -> ""
            }

        return RecognizedEvent(bestResult.eventOptionRewards, bestResult.confidence, bestResult.eventTitle, bestRawTitle, characterOrSupportName)
    }
}
