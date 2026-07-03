package com.steve1316.uma_android_automation.bot

import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Campaign
import com.steve1316.uma_android_automation.bot.campaigns.DuelContestOption
import com.steve1316.uma_android_automation.bot.campaigns.DuelPrediction
import com.steve1316.uma_android_automation.bot.campaigns.chooseDuelContest
import com.steve1316.uma_android_automation.bot.campaigns.nearestDuelPrediction
import com.steve1316.uma_android_automation.bot.campaigns.parseContestStat
import com.steve1316.uma_android_automation.components.ButtonClose
import com.steve1316.uma_android_automation.components.ButtonNext
import com.steve1316.uma_android_automation.components.IconDuelBad
import com.steve1316.uma_android_automation.components.IconDuelGood
import com.steve1316.uma_android_automation.components.IconDuelGreat
import com.steve1316.uma_android_automation.components.IconTrainingEventHorseshoe
import com.steve1316.uma_android_automation.types.Mood
import com.steve1316.uma_android_automation.types.NegativeStatus
import com.steve1316.uma_android_automation.types.PositiveStatus
import net.ricecode.similarity.JaroWinklerStrategy
import net.ricecode.similarity.StringSimilarityServiceImpl
import org.json.JSONObject
import org.opencv.core.Point

/**
 * This class is responsible for detecting, analyzing, and responding to Training Events.
 *
 * @property game The [Game] instance for interacting with the game state.
 * @property campaign The [Campaign] instance for accessing campaign-specific data.
 */
class TrainingEvent(private val game: Game, private val campaign: Campaign) {
    /** Recognizer used to perform OCR and string matching for Training Events. */
    private val trainingEventRecognizer: TrainingEventRecognizer = TrainingEventRecognizer(game, game.imageUtils)

    /** Whether to prioritize options that provide energy gains. */
    private val enablePrioritizeEnergyOptions: Boolean = SettingsHelper.getBooleanSetting("trainingEvent", "enablePrioritizeEnergyOptions")

    /** Special event overrides loaded from SQLite settings. */
    private val specialEventOverrides: Map<String, EventOverride> =
        try {
            val overridesString = SettingsHelper.getStringSetting("trainingEvent", "specialEventOverrides")
            if (overridesString.isNotEmpty()) {
                val jsonObject = JSONObject(overridesString)
                val overridesMap = mutableMapOf<String, EventOverride>()
                jsonObject.keys().forEach { eventName ->
                    val eventData = jsonObject.getJSONObject(eventName)
                    overridesMap[eventName] =
                        EventOverride(
                            selectedOption = eventData.getString("selectedOption"),
                            requiresConfirmation = eventData.getBoolean("requiresConfirmation"),
                            enableEnergyBasedSelection = eventData.optBoolean("enableEnergyBasedSelection", false),
                        )
                }
                overridesMap
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            MessageLog.w(TAG, "[WARN] specialEventOverrides:: Could not parse special event overrides: ${e.message}")
            emptyMap()
        }

    /** Character event overrides loaded from SQLite settings. */
    private val characterEventOverrides: Map<String, Int> =
        try {
            val overridesString = SettingsHelper.getStringSetting("trainingEvent", "characterEventOverrides")
            if (overridesString.isNotEmpty()) {
                val jsonObject = JSONObject(overridesString)
                val overridesMap = mutableMapOf<String, Int>()
                jsonObject.keys().forEach { eventKey ->
                    overridesMap[eventKey] = jsonObject.getInt(eventKey)
                }
                overridesMap
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            MessageLog.w(TAG, "[WARN] characterEventOverrides:: Could not parse character event overrides: ${e.message}")
            emptyMap()
        }

    /** Support event overrides loaded from SQLite settings. */
    private val supportEventOverrides: Map<String, Int> =
        try {
            val overridesString = SettingsHelper.getStringSetting("trainingEvent", "supportEventOverrides")
            if (overridesString.isNotEmpty()) {
                val jsonObject = JSONObject(overridesString)
                val overridesMap = mutableMapOf<String, Int>()
                jsonObject.keys().forEach { eventKey ->
                    overridesMap[eventKey] = jsonObject.getInt(eventKey)
                }
                overridesMap
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            MessageLog.w(TAG, "[WARN] supportEventOverrides:: Could not parse support event overrides: ${e.message}")
            emptyMap()
        }

    /** Scenario event overrides loaded from SQLite settings. */
    private val scenarioEventOverrides: Map<String, Int> =
        try {
            val overridesString = SettingsHelper.getStringSetting("trainingEvent", "scenarioEventOverrides")
            if (overridesString.isNotEmpty()) {
                val jsonObject = JSONObject(overridesString)
                val overridesMap = mutableMapOf<String, Int>()
                jsonObject.keys().forEach { eventKey ->
                    overridesMap[eventKey] = jsonObject.getInt(eventKey)
                }
                overridesMap
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            MessageLog.w(TAG, "[WARN] scenarioEventOverrides:: Could not parse scenario event overrides: ${e.message}")
            emptyMap()
        }

    /**
     * Store the override settings for a special Training Event.
     *
     * @property selectedOption The name of the option to select.
     * @property requiresConfirmation Whether the selection requires a confirmation dialog.
     * @property enableEnergyBasedSelection Whether to swap to the other (unselected) option at <=20% trainee energy.
     */
    data class EventOverride(val selectedOption: String, val requiresConfirmation: Boolean, val enableEnergyBasedSelection: Boolean = false)

    companion object {
        private val TAG: String = "[${MainActivity.loggerTag}]TrainingEvent"

        /** Events that swap to the other (unselected) option when trainee energy is <=20%. */
        private val energyAwareEvents = setOf("Victory!", "Solid Showing", "Defeat")
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Check if the given event title matches any special event overrides.
     *
     * @param eventTitle The detected event title from OCR.
     * @return A [Pair] containing the option index (0-based) and whether confirmation is required, or null if no override is found.
     */
    private fun checkSpecialEventOverride(eventTitle: String): Pair<Int, Boolean>? {
        for ((eventName, patterns) in trainingEventRecognizer.eventPatterns) {
            val override = specialEventOverrides[eventName]
            if (override != null) {
                // Check if any pattern matches the event title.
                val matches = patterns.any { pattern -> eventTitle.contains(pattern) }
                if (matches) {
                    MessageLog.v(TAG, "[TRAINING_EVENT] Detected special event: $eventName")

                    // Parse the option number from the setting (e.g., "Option 5: Energy +10" -> 5).
                    val optionIndex =
                        if (override.selectedOption == "Default") {
                            MessageLog.v(TAG, "[TRAINING_EVENT] Selecting Option 1 according to special event override.")
                            0
                        } else {
                            val optionMatch = Regex("Option (\\d+)").find(override.selectedOption)
                            if (optionMatch != null) {
                                val optionNumber = optionMatch.groupValues[1].toInt()
                                MessageLog.v(TAG, "[TRAINING_EVENT] Using setting: ${override.selectedOption} (Option $optionNumber)")
                                optionNumber - 1
                            } else {
                                MessageLog.w(TAG, "[WARN] checkSpecialEventOverride:: Could not parse option number from setting: ${override.selectedOption}. Using option 1 by default.")
                                0
                            }
                        }

                    // Energy-aware swap (opt-in via setting): at <=20% energy, pick the other (unselected) option of the two.
                    if (override.enableEnergyBasedSelection && eventName in energyAwareEvents && campaign.trainee.energy <= 20) {
                        val swapped = 1 - optionIndex
                        MessageLog.v(TAG, "[TRAINING_EVENT] Energy-aware swap for $eventName: energy=${campaign.trainee.energy}%, swapping from Option ${optionIndex + 1} to Option ${swapped + 1}.")
                        return Pair(swapped, override.requiresConfirmation)
                    }

                    return Pair(optionIndex, override.requiresConfirmation)
                }
            }
        }

        return null
    }

    /**
     * Check if the given character event matches any character event overrides.
     *
     * @param characterName The detected character name.
     * @param eventTitle The detected event title from OCR.
     * @return The 0-based option index if an override is found, otherwise null.
     */
    private fun checkCharacterEventOverride(characterName: String, eventTitle: String): Int? {
        if (characterName.isEmpty()) return null

        val eventKey = "$characterName|$eventTitle"
        val override = characterEventOverrides[eventKey]
        if (override != null) {
            MessageLog.v(TAG, "[TRAINING_EVENT] Detected character event override: $eventKey -> Option ${override + 1}")
            return override
        }

        return null
    }

    /**
     * Check if the given support event matches any support event overrides.
     *
     * @param supportName The detected support card name.
     * @param eventTitle The detected event title from OCR.
     * @return The 0-based option index if an override is found, otherwise null.
     */
    private fun checkSupportEventOverride(supportName: String, eventTitle: String): Int? {
        if (supportName.isEmpty()) return null

        val eventKey = "$supportName|$eventTitle"
        val override = supportEventOverrides[eventKey]
        if (override != null) {
            MessageLog.v(TAG, "[TRAINING_EVENT] Detected support event override: $eventKey -> Option ${override + 1}")
            return override
        }

        return null
    }

    /**
     * Check if the given scenario event matches any scenario event overrides.
     *
     * @param scenarioName The detected scenario name.
     * @param eventTitle The detected event title from OCR.
     * @return The 0-based option index if an override is found, otherwise null.
     */
    private fun checkScenarioEventOverride(scenarioName: String, eventTitle: String): Int? {
        if (scenarioName.isEmpty()) return null

        val eventKey = "$scenarioName|$eventTitle"
        val override = scenarioEventOverrides[eventKey]
        if (override != null) {
            MessageLog.v(TAG, "[TRAINING_EVENT] Detected scenario event override: $eventKey -> Option ${override + 1}")
            return override
        }

        return null
    }

    /**
     * Select the team name for the Unity Cup "A Team at Last" event.
     *
     * This event is unique because it may have between zero and five options. The last option is always the default "Team Carrot", while other options are character suggestions detected via OCR.
     *
     * @param optionLocations The list of detected option locations.
     * @return The 0-based index of the option to select, defaulting to 0 if no match is found.
     */
    private fun selectUnityCupTeamNameEvent(optionLocations: ArrayList<Point>): Int {
        val numOptions = optionLocations.size
        MessageLog.v(TAG, "[TRAINING_EVENT] Handling \"A Team at Last\" event with $numOptions option(s).")

        // If zero or one options are detected, return the first option index (auto-completed or single option).
        if (numOptions <= 1) {
            MessageLog.v(TAG, "[TRAINING_EVENT] Event has $numOptions option(s). Selecting first/only option.")
            return 0
        }

        // Retrieve the user preference for team name from settings.
        val override = specialEventOverrides["A Team at Last"]
        val selectedPreference = override?.selectedOption ?: "Default"
        MessageLog.i(TAG, "[TRAINING_EVENT] User preference for team name: $selectedPreference")

        // Return the first option index if the user preference is "Default".
        if (selectedPreference == "Default") {
            MessageLog.v(TAG, "[TRAINING_EVENT] Using default preference, selecting first option.")
            return 0
        }

        // Return the last option index if the user preference is "Team Carrot (Last Option)".
        if (selectedPreference == "Team Carrot (Last Option)") {
            MessageLog.v(TAG, "[TRAINING_EVENT] Using Team Carrot preference, selecting last option.")
            return numOptions - 1
        }

        // List possible team name character suggestions (excluding "Team Carrot").
        val teamNameOptions =
            listOf(
                "Happy Hoppers, like Taiki suggested",
                "Sunny Runners, like Fukukitaru suggested",
                "Carrot Pudding, like Urara suggested",
                "Blue Bloom, like Rice Shower suggested",
            )

        // Perform OCR on each option except the last one.
        val sourceBitmap = game.imageUtils.getSourceBitmap()
        val detectedOptions = mutableListOf<Pair<Int, String>>()

        for (i in 0 until numOptions - 1) {
            val optionCenter = optionLocations[i]
            val cropX = game.imageUtils.relX(optionCenter.x, 45)
            val cropY = game.imageUtils.relY(optionCenter.y, -30)
            val cropWidth = 800
            val cropHeight = 55

            val ocrText =
                game.imageUtils.performOCROnRegion(
                    sourceBitmap,
                    cropX,
                    cropY,
                    cropWidth,
                    cropHeight,
                    useThreshold = false,
                    useGrayscale = true,
                    scale = 1.0,
                    ocrEngine = "tesseract",
                    debugName = "selectUnityCupTeamNameEvent_option_${i + 1}",
                )

            MessageLog.i(TAG, "[TRAINING_EVENT] Option ${i + 1} OCR result: \"$ocrText\"")
            if (ocrText.isNotEmpty()) {
                detectedOptions.add(Pair(i, ocrText))
            }
        }

        // Find the best match for the user preference using string similarity.
        var bestMatchIndex = 0
        var bestMatchScore = 0.0

        for ((optionIndex, ocrText) in detectedOptions) {
            for (teamName in teamNameOptions) {
                // Perform exact containment check first.
                if (ocrText.contains(teamName, ignoreCase = true) || teamName.contains(ocrText, ignoreCase = true)) {
                    if (teamName == selectedPreference) {
                        MessageLog.v(TAG, "[TRAINING_EVENT] Found exact match for \"$selectedPreference\" at option ${optionIndex + 1}.")
                        return optionIndex
                    }
                }

                // Check similarity if this team name matches the user preference.
                if (teamName == selectedPreference) {
                    val score = StringSimilarityServiceImpl(JaroWinklerStrategy()).score(ocrText.lowercase(), teamName.lowercase())

                    if (score > bestMatchScore) {
                        bestMatchScore = score
                        bestMatchIndex = optionIndex
                        MessageLog.i(TAG, "[TRAINING_EVENT] Option ${optionIndex + 1} matches preference with score: ${game.decimalFormat.format(score)}")
                    }
                }
            }
        }

        // Return the best matching index if the similarity score is high enough.
        if (bestMatchScore >= 0.8) {
            MessageLog.v(TAG, "[TRAINING_EVENT] Selected option ${bestMatchIndex + 1} based on similarity match (score: ${game.decimalFormat.format(bestMatchScore)}).")
            return bestMatchIndex
        }

        // Fallback to selecting the first option if no suitable match is found.
        MessageLog.v(TAG, "[TRAINING_EVENT] No good match found for preference. Falling back to first option.")
        return 0
    }

    /**
     * Handle the URA Finale "Happy Meek's Challenge!" duel. Each row is a "Contest of <stat>!" option with a win-prediction icon. Reads every row's stat via OCR, classifies its
     * prediction tier by template-matching the great / good / bad icons (an unmatched row is the X tier), then picks the best contest per [chooseDuelContest] and the trainee's
     * stat priorities. Falls back to the first option when no rows or prediction icons are detected.
     *
     * @param optionLocations The detected horseshoe locations for the contest rows, top to bottom.
     * @return The 0-based index of the contest row to enter, defaulting to 0.
     */
    private fun handleHappyMeekDuel(optionLocations: ArrayList<Point>): Int {
        val numOptions = optionLocations.size
        MessageLog.v(TAG, "[TRAINING_EVENT] Handling \"Happy Meek's Challenge!\" duel with $numOptions option(s).")
        if (numOptions <= 1) return 0

        val sourceBitmap = game.imageUtils.getSourceBitmap()

        // Locate every prediction icon once, tagged with its tier, so each row can be paired with the nearest icon by Y.
        val predictionMatches =
            listOf(DuelPrediction.GREAT to IconDuelGreat, DuelPrediction.GOOD to IconDuelGood, DuelPrediction.BAD to IconDuelBad).flatMap { (prediction, component) ->
                component.findAllWithBitmap(game.imageUtils, sourceBitmap).map { prediction to it.y.toInt() }
            }
        MessageLog.v(TAG, "[TRAINING_EVENT] Detected ${predictionMatches.size} duel prediction icon(s).")

        // Roughly half the row pitch is a safe tolerance for pairing a prediction icon to its row.
        val rowTolerance = game.imageUtils.relHeight(90)

        val options =
            optionLocations.mapIndexed { i, location ->
                val ocrText =
                    game.imageUtils.performOCROnRegion(
                        sourceBitmap,
                        game.imageUtils.relX(location.x, 45),
                        game.imageUtils.relY(location.y, -30),
                        600,
                        60,
                        useThreshold = false,
                        useGrayscale = true,
                        scale = 1.0,
                        ocrEngine = "tesseract",
                        debugName = "handleHappyMeekDuel_option_${i + 1}",
                    )
                val statName = parseContestStat(ocrText)
                val prediction = nearestDuelPrediction(location.y.toInt(), predictionMatches, rowTolerance)
                MessageLog.i(TAG, "[TRAINING_EVENT] Duel option ${i + 1}: \"$ocrText\" -> stat=$statName, prediction=$prediction")
                DuelContestOption(statName, prediction)
            }

        val selected = chooseDuelContest(options, campaign.training.eventChoiceStatPriority)
        MessageLog.i(TAG, "[TRAINING_EVENT] Duel pick: option ${selected + 1} (stat=${options.getOrNull(selected)?.statName}, prediction=${options.getOrNull(selected)?.prediction}).")
        return selected
    }

    /**
     * Print a formatted summary of the Training Event and the selected option.
     *
     * @param eventTitle The detected event title from OCR.
     * @param ownerName The character or support card name that owns this event.
     * @param eventRewards List of reward strings for each option.
     * @param weights List of calculated weights for each option (can be null for override cases).
     * @param selectedOption The 0-based index of the selected option.
     * @param confidence The OCR matching confidence.
     */
    private fun printEventSummary(eventTitle: String, ownerName: String, eventRewards: ArrayList<String>, weights: List<Int>?, selectedOption: Int, confidence: Double) {
        val sb = StringBuilder()
        sb.appendLine("\n========== Training Event Summary ==========")

        val ownerInfo = if (ownerName.isNotEmpty()) " ($ownerName)" else ""
        val cleanedTitle = eventTitle.replace("\n", " ").replace("\r", "")
        sb.appendLine("Event: \"$cleanedTitle\"$ownerInfo [Confidence: ${game.decimalFormat.format(confidence)}]")
        sb.appendLine("Current Date: ${campaign.date}")
        sb.appendLine("")

        sb.appendLine("Options:")

        eventRewards.forEachIndexed { index, reward ->
            // Create a condensed reward summary by joining truncated lines.
            val rewardLines = reward.split("\n").filter { it.isNotBlank() && !it.startsWith("---") }
            val condensed =
                if (rewardLines.size <= 3) {
                    rewardLines.joinToString(", ")
                } else {
                    rewardLines.take(3).joinToString(", ") + "..."
                }

            val weightInfo = if (weights != null && index < weights.size) " [Weight: ${weights[index]}]" else ""
            val selectionMarker = if (index == selectedOption) " <---- SELECTED" else ""
            sb.appendLine("  Option ${index + 1}$weightInfo: $condensed$selectionMarker")
        }

        sb.appendLine("")
        sb.appendLine("Selected: Option ${selectedOption + 1}")
        sb.appendLine("============================================")
        MessageLog.v(TAG, sb.toString())
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Scores each option of a normal training event by weighting its reward lines (energy, mood, bond, hints, statuses, skills, and prioritized stats).
     *
     * @param eventRewards The OCR-read reward text for each event option.
     * @param regex The letter-stripping regex used to normalize each reward line before parsing numbers.
     * @return A mutable list of per-option selection weights; the highest-weighted index is the best choice.
     */
    private fun computeEventSelectionWeights(eventRewards: List<String>, regex: Regex): MutableList<Int> {
        // Initialize the List for normal event processing.
        val selectionWeight = List(eventRewards.size) { 0 }.toMutableList()

        // Sum up the stat gains with additional weight applied to stats that are prioritized.
        eventRewards.forEachIndexed { rewardIndex, reward ->
            val formattedReward: List<String> = reward.split("\n")

            formattedReward.forEach { line ->
                val formattedLine: String =
                    regex
                        .replace(line, "")
                        .replace("(", "")
                        .replace(")", "")
                        .trim()
                        .lowercase()

                // Skip empty strings and divider lines (lines that are all dashes or start with 5 dashes).
                if (line.trim().isEmpty() || line.trim().length >= 5 && line.trim().substring(0, 5).all { it == '-' }) {
                    return@forEach
                }

                var priorityStatCheck = false
                if (line.lowercase().contains("can start dating")) {
                    selectionWeight[rewardIndex] += 1000
                } else if (line.lowercase().contains("event chain ended")) {
                    selectionWeight[rewardIndex] += -300
                } else if (line.lowercase().contains("(random)")) {
                    selectionWeight[rewardIndex] += -10
                } else if (line.lowercase().contains("randomly")) {
                    selectionWeight[rewardIndex] += 50
                } else if (line.lowercase().contains("energy")) {
                    val finalEnergyValue =
                        try {
                            val energyValue =
                                if (formattedLine.contains("/")) {
                                    val splits = formattedLine.split("/")
                                    var sum = 0
                                    for (split in splits) {
                                        sum +=
                                            try {
                                                split.trim().toInt()
                                            } catch (_: NumberFormatException) {
                                                20
                                            }
                                    }
                                    sum
                                } else {
                                    formattedLine.toInt()
                                }

                            if (enablePrioritizeEnergyOptions) {
                                energyValue * 100
                            } else {
                                val energyMultiplier =
                                    when {
                                        campaign.trainee.energy < 30 -> 4
                                        campaign.trainee.energy < 50 -> 3
                                        campaign.trainee.energy < 70 -> 2
                                        campaign.trainee.energy >= 90 -> 0
                                        else -> 1
                                    }
                                energyValue * energyMultiplier
                            }
                        } catch (_: NumberFormatException) {
                            20
                        }
                    selectionWeight[rewardIndex] += finalEnergyValue
                } else if (line.lowercase().contains("mood")) {
                    val moodMultiplier =
                        when (campaign.trainee.mood) {
                            Mood.AWFUL -> 150
                            Mood.BAD -> 120
                            Mood.NORMAL -> 100
                            Mood.GOOD -> 80
                            Mood.GREAT -> 0
                        }
                    val moodWeight = if (formattedLine.contains("-")) -150 else moodMultiplier
                    selectionWeight[rewardIndex] += moodWeight
                } else if (line.lowercase().contains("bond")) {
                    val bondWeight = if (formattedLine.contains("-")) -20 else 20
                    selectionWeight[rewardIndex] += bondWeight
                } else if (line.lowercase().contains("hint")) {
                    selectionWeight[rewardIndex] += 25
                } else if (PositiveStatus.names.any { status -> line.contains(status) }) {
                    selectionWeight[rewardIndex] += 25
                } else if (NegativeStatus.names.any { status -> line.contains(status) }) {
                    selectionWeight[rewardIndex] += -25
                } else if (line.lowercase().contains("skill")) {
                    val finalSkillPoints =
                        if (formattedLine.contains("/")) {
                            val splits = formattedLine.split("/")
                            var sum = 0
                            for (split in splits) {
                                sum +=
                                    try {
                                        split.trim().toInt()
                                    } catch (_: NumberFormatException) {
                                        10
                                    }
                            }
                            sum
                        } else {
                            formattedLine.toInt()
                        }
                    selectionWeight[rewardIndex] += finalSkillPoints
                } else {
                    // Apply inflated weights to the prioritized stats based on their order.
                    campaign.training.eventChoiceStatPriority.forEachIndexed { index, stat ->
                        if (line.lowercase().contains(stat.name.lowercase())) {
                            // Calculate weight bonus based on position (higher priority = higher bonus).
                            val priorityBonus =
                                when (index) {
                                    0 -> 50
                                    1 -> 40
                                    2 -> 30
                                    3 -> 20
                                    else -> 10
                                }

                            val finalStatValue =
                                try {
                                    priorityStatCheck = true
                                    if (formattedLine.contains("/")) {
                                        val splits = formattedLine.split("/")
                                        var sum = 0
                                        for (split in splits) {
                                            sum +=
                                                try {
                                                    split.trim().toInt()
                                                } catch (_: NumberFormatException) {
                                                    10
                                                }
                                        }
                                        sum + priorityBonus
                                    } else {
                                        formattedLine.toInt() + priorityBonus
                                    }
                                } catch (_: NumberFormatException) {
                                    priorityStatCheck = false
                                    10
                                }
                            selectionWeight[rewardIndex] += finalStatValue
                        }
                    }

                    // Apply normal weights to the rest of the stats.
                    if (!priorityStatCheck) {
                        val finalStatValue =
                            try {
                                if (formattedLine.contains("/")) {
                                    val splits = formattedLine.split("/")
                                    var sum = 0
                                    for (split in splits) {
                                        sum +=
                                            try {
                                                split.trim().toInt()
                                            } catch (_: NumberFormatException) {
                                                10
                                            }
                                    }
                                    sum
                                } else {
                                    formattedLine.toInt()
                                }
                            } catch (_: NumberFormatException) {
                                10
                            }
                        selectionWeight[rewardIndex] += finalStatValue
                    }
                }
            }
        }

        return selectionWeight
    }

    /**
     * Handle the active Training Event. By default, it will select the first option.
     *
     * This method performs OCR to identify the event and its associated rewards. It then evaluates the options based on user preferences and character specific overrides to select the best possible
     * outcome.
     */
    fun handleTrainingEvent() {
        MessageLog.v(TAG, "\n********************")
        MessageLog.v(TAG, "[TRAINING_EVENT] Starting Training Event process on ${campaign.date}.")

        // Check if the bot is currently at the Main Screen.
        if (campaign.checkMainScreen()) {
            MessageLog.v(TAG, "[TRAINING_EVENT] Bot is at the Main Screen. Ending the Training Event process.")
            MessageLog.v(TAG, "********************")
            return
        }

        val (eventRewards, confidence, eventTitle, rawTitle, characterOrSupportName) = trainingEventRecognizer.start()

        val regex = Regex("[a-zA-Z]+")
        var optionSelected = 0
        var specialEventHandled = false
        var isTutorialEvent = false
        var tutorialOptionCount = 0

        // Check for special event overrides first.
        val specialEventResult = checkSpecialEventOverride(eventTitle)

        // Handle Tutorial events by detecting the number of options on screen.
        if (eventTitle == "Tutorial") {
            isTutorialEvent = true
            // Detect the number of event options on the screen.
            val trainingOptionLocations: ArrayList<Point> = IconTrainingEventHorseshoe.findAll(game.imageUtils)
            tutorialOptionCount = trainingOptionLocations.size

            MessageLog.v(TAG, "[TRAINING_EVENT] Tutorial event detected for Unity Cup. Found $tutorialOptionCount option(s) on screen.")

            when (tutorialOptionCount) {
                2 -> {
                    // If 2 options detected, select the last one (index 1).
                    optionSelected = 1
                    MessageLog.v(TAG, "[TRAINING_EVENT] Selecting last option (option 2) to dismiss Tutorial.")
                }

                5 -> {
                    optionSelected = 4
                    MessageLog.v(TAG, "[TRAINING_EVENT] Selecting last option (option 5) first, then will select first option to close.")
                }

                else -> {
                    // Default to last option if count doesn't match expected values.
                    optionSelected = if (tutorialOptionCount > 0) tutorialOptionCount - 1 else 0
                    MessageLog.w(TAG, "[WARN] handleTrainingEvent:: Unexpected option count ($tutorialOptionCount). Selecting last option.")
                }
            }

            specialEventHandled = true
        } else if (eventTitle == "A Team at Last") {
            // Handle "A Team at Last" Unity Cup event specially.
            MessageLog.i(TAG, "[TRAINING_EVENT] \"A Team at Last\" event detected for Unity Cup.")
            val trainingOptionLocations: ArrayList<Point> = IconTrainingEventHorseshoe.findAll(game.imageUtils)
            optionSelected = selectUnityCupTeamNameEvent(trainingOptionLocations)
            specialEventHandled = true
        } else if (game.scenario == "URA Finale" && rawTitle.contains("Happy Meek", ignoreCase = true) && rawTitle.contains("Challenge", ignoreCase = true)) {
            // Handle the URA Finale "Happy Meek's Challenge!" duel by prediction-driven contest pick. Dispatch on the RAW OCR title, not the fuzzy-matched event name, since the duel
            // is not in the event database and would otherwise resolve to an unrelated event.
            MessageLog.i(TAG, "[TRAINING_EVENT] \"Happy Meek's Challenge!\" duel detected for URA Finale.")
            val trainingOptionLocations: ArrayList<Point> = IconTrainingEventHorseshoe.findAll(game.imageUtils)
            optionSelected = handleHappyMeekDuel(trainingOptionLocations)
            specialEventHandled = true
        } else if (specialEventResult != null) {
            val (selectedOptionIndex, _) = specialEventResult
            optionSelected = selectedOptionIndex

            // Ensure the selected option is within bounds.
            if (eventRewards.isNotEmpty() && optionSelected >= eventRewards.size) {
                MessageLog.w(TAG, "[WARN] handleTrainingEvent:: Selected special event option $optionSelected is out of bounds. Using last option.")
                optionSelected = eventRewards.size - 1
            }

            if (eventRewards.isNotEmpty()) {
                MessageLog.v(TAG, "[TRAINING_EVENT] Special event override applied: option ${optionSelected + 1}: \"${eventRewards[optionSelected]}\"")
            } else {
                MessageLog.v(TAG, "[TRAINING_EVENT] Special event override applied: option ${optionSelected + 1}")
            }
            specialEventHandled = true
        }

        if (eventRewards.isNotEmpty() && eventRewards[0] != "") {
            if (!specialEventHandled) {
                // Check for character, support, or scenario event overrides.
                val characterOverride = checkCharacterEventOverride(characterOrSupportName, eventTitle)
                val supportOverride = checkSupportEventOverride(characterOrSupportName, eventTitle)
                val scenarioOverride = checkScenarioEventOverride(characterOrSupportName, eventTitle)

                if (characterOverride != null) {
                    optionSelected = characterOverride

                    // Ensure the selected option is within bounds.
                    if (optionSelected >= eventRewards.size) {
                        MessageLog.w(TAG, "[WARN] handleTrainingEvent:: Selected character event option $optionSelected is out of bounds. Using last option.")
                        optionSelected = eventRewards.size - 1
                    }

                    MessageLog.v(TAG, "[TRAINING_EVENT] Character event override applied.")
                    printEventSummary(eventTitle, characterOrSupportName, eventRewards, null, optionSelected, confidence)
                } else if (supportOverride != null) {
                    optionSelected = supportOverride

                    // Ensure the selected option is within bounds.
                    if (optionSelected >= eventRewards.size) {
                        MessageLog.w(TAG, "[WARN] handleTrainingEvent:: Selected support event option $optionSelected is out of bounds. Using last option.")
                        optionSelected = eventRewards.size - 1
                    }

                    MessageLog.v(TAG, "[TRAINING_EVENT] Support event override applied.")
                    printEventSummary(eventTitle, characterOrSupportName, eventRewards, null, optionSelected, confidence)
                } else if (scenarioOverride != null) {
                    optionSelected = scenarioOverride

                    // Ensure the selected option is within bounds.
                    if (optionSelected >= eventRewards.size) {
                        MessageLog.w(TAG, "[WARN] handleTrainingEvent:: Selected scenario event option $optionSelected is out of bounds. Using last option.")
                        optionSelected = eventRewards.size - 1
                    }

                    MessageLog.v(TAG, "[TRAINING_EVENT] Scenario event override applied.")
                    printEventSummary(eventTitle, characterOrSupportName, eventRewards, null, optionSelected, confidence)
                } else {
                    val selectionWeight = computeEventSelectionWeights(eventRewards, regex)

                    // Select the best option that aligns with the stat prioritization made in the Training options.
                    val max: Int? = selectionWeight.maxOrNull()
                    optionSelected =
                        if (max == null) {
                            0
                        } else {
                            selectionWeight.indexOf(max)
                        }

                    // Print the selection weights.
                    printEventSummary(eventTitle, characterOrSupportName, eventRewards, selectionWeight, optionSelected, confidence)
                }
            }

            // Print summary for special event overrides (character/support overrides are handled in their branches).
            if (specialEventHandled) {
                printEventSummary(eventTitle, characterOrSupportName, eventRewards, null, optionSelected, confidence)
            }
        } else {
            if (!specialEventHandled) {
                MessageLog.w(TAG, "[WARN] handleTrainingEvent:: First option will be selected since OCR failed to match the event title and no event rewards were found.")
                optionSelected = 0
            }
        }

        // Wait briefly for the UI to fully render all option buttons.
        game.wait(0.1)

        val trainingOptionLocations: ArrayList<Point> = IconTrainingEventHorseshoe.findAll(game.imageUtils)

        // Handle Tutorial events specially.
        if (isTutorialEvent && trainingOptionLocations.isNotEmpty()) {
            if (tutorialOptionCount == 5) {
                // Determine the last option location for a 5-option Tutorial.
                val lastOptionLocation =
                    try {
                        trainingOptionLocations[4]
                    } catch (_: IndexOutOfBoundsException) {
                        trainingOptionLocations[trainingOptionLocations.size - 1]
                    }

                game.tap(lastOptionLocation.x + game.imageUtils.relWidth(100), lastOptionLocation.y, IconTrainingEventHorseshoe.template.path)
                MessageLog.i(TAG, "[TRAINING_EVENT] Selected last option (option 5) for Tutorial to back out.")

                game.wait(1.0)

                // Refresh training option locations.
                val updatedTrainingOptionLocations: ArrayList<Point> = IconTrainingEventHorseshoe.findAll(game.imageUtils)
                if (updatedTrainingOptionLocations.isNotEmpty()) {
                    // Select the first option to close the Tutorial.
                    val firstOptionLocation = updatedTrainingOptionLocations[0]
                    game.tap(firstOptionLocation.x + game.imageUtils.relWidth(100), firstOptionLocation.y, IconTrainingEventHorseshoe.template.path)
                    MessageLog.i(TAG, "[TRAINING_EVENT] Selected first option (option 1) to close Tutorial.")
                } else {
                    MessageLog.w(TAG, "[WARN] handleTrainingEvent:: Could not find Training Event options after waiting. Tutorial may have already closed.")
                }
            } else {
                // Select the determined option for standard Tutorial cases.
                val selectedLocation =
                    try {
                        trainingOptionLocations[optionSelected]
                    } catch (_: IndexOutOfBoundsException) {
                        trainingOptionLocations[trainingOptionLocations.size - 1]
                    }

                game.tap(selectedLocation.x + game.imageUtils.relWidth(100), selectedLocation.y, IconTrainingEventHorseshoe.template.path)
                MessageLog.i(TAG, "[TRAINING_EVENT] Selected option ${optionSelected + 1} for Tutorial.")
            }

            // Exclude handling for certain scenarios that do not require this logic.
            if (game.scenario != "Trackblazer") {
                // Wait three seconds before processing Next/Close buttons for the Tutorial.
                MessageLog.i(TAG, "[TRAINING_EVENT] Waiting 3 seconds before handling Next/Close buttons for Tutorial.")
                game.wait(3.0)

                // Search for and click Next buttons until the Close button is detected.
                var closeButtonFound = false
                val maxIterations = 20 // Set a limit to prevent infinite loops.
                var iterationCount = 0

                while (!closeButtonFound && iterationCount < maxIterations) {
                    iterationCount++

                    // Check for the Close button first.
                    if (ButtonClose.click(game.imageUtils)) {
                        MessageLog.i(TAG, "[TRAINING_EVENT] Close button found and clicked. Tutorial event handling complete.")
                        closeButtonFound = true
                        break
                    }

                    // Look for the Next button if the Close button is not found.
                    if (ButtonNext.click(game.imageUtils)) {
                        MessageLog.i(TAG, "[TRAINING_EVENT] Next button found and clicked. Waiting for next screen...")
                        game.wait(1.0)
                    } else {
                        // Wait briefly and retry if neither button is found.
                        MessageLog.i(TAG, "[TRAINING_EVENT] Neither Next nor Close button found. Waiting...")
                        game.wait(0.5)
                    }
                }

                if (!closeButtonFound && iterationCount >= maxIterations) {
                    MessageLog.w(TAG, "[WARN] handleTrainingEvent:: Reached maximum iterations while searching for Close button. Tutorial handling may be incomplete.")
                }
            }
        } else {
            // Proceed with normal event handling.
            val selectedLocation: Point? =
                if (trainingOptionLocations.isNotEmpty()) {
                    // Handle cases where detected options might lead to an index out of bounds.
                    try {
                        trainingOptionLocations[optionSelected]
                    } catch (_: IndexOutOfBoundsException) {
                        // Default to selecting the first option.
                        trainingOptionLocations[0]
                    }
                } else {
                    IconTrainingEventHorseshoe.find(game.imageUtils, tries = 5).first
                }

            if (selectedLocation != null) {
                game.tap(selectedLocation.x + game.imageUtils.relWidth(100), selectedLocation.y, IconTrainingEventHorseshoe.template.path)

                // Verify if a confirmation dialog is required for this special event.
                if (specialEventResult != null) {
                    val (_, requiresConfirmation) = specialEventResult
                    if (requiresConfirmation) {
                        MessageLog.i(TAG, "[TRAINING_EVENT] Special event requires confirmation, waiting for dialog...")

                        // Wait for the confirmation dialog to appear.
                        game.wait(1.0)

                        // Select the first confirmation option (Yes).
                        val confirmationLocations: ArrayList<Point> = IconTrainingEventHorseshoe.findAll(game.imageUtils)
                        if (confirmationLocations.isNotEmpty()) {
                            val confirmLocation = confirmationLocations[0]
                            game.tap(confirmLocation.x + game.imageUtils.relWidth(100), confirmLocation.y, IconTrainingEventHorseshoe.template.path)
                            MessageLog.i(TAG, "[TRAINING_EVENT] Special event confirmed.")
                        } else {
                            MessageLog.w(TAG, "[WARN] handleTrainingEvent:: Could not find confirmation options for special event.")
                        }
                    }
                }
            }
        }

        MessageLog.v(TAG, "[TRAINING_EVENT] Process to handle detected Training Event completed.")
        MessageLog.v(TAG, "********************")
    }
}
