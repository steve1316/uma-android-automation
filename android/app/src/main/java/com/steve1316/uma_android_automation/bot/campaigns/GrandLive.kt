package com.steve1316.uma_android_automation.bot.campaigns

import android.graphics.Bitmap
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.uma_android_automation.bot.Campaign
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.components.ButtonBack
import com.steve1316.uma_android_automation.components.ButtonCancel
import com.steve1316.uma_android_automation.components.ButtonCareerEndSkillsMini
import com.steve1316.uma_android_automation.components.ButtonClose
import com.steve1316.uma_android_automation.components.ButtonCompleteCareer
import com.steve1316.uma_android_automation.components.ButtonGrandLiveConcert
import com.steve1316.uma_android_automation.components.ButtonGrandLiveGrandConcert
import com.steve1316.uma_android_automation.components.ButtonGrandLiveLessons
import com.steve1316.uma_android_automation.components.ButtonGrandLiveLessonsBig
import com.steve1316.uma_android_automation.components.ButtonGrandLiveStart
import com.steve1316.uma_android_automation.components.ButtonInfirmaryMini
import com.steve1316.uma_android_automation.components.ButtonLearn
import com.steve1316.uma_android_automation.components.ButtonNext
import com.steve1316.uma_android_automation.components.ButtonRaceDayMini
import com.steve1316.uma_android_automation.components.ButtonRacesMini
import com.steve1316.uma_android_automation.components.ButtonRecreationMini
import com.steve1316.uma_android_automation.components.ButtonSkip
import com.steve1316.uma_android_automation.components.Checkbox
import com.steve1316.uma_android_automation.components.ComponentInterface
import com.steve1316.uma_android_automation.components.IconGrandLiveGreatHype
import com.steve1316.uma_android_automation.components.LabelGrandLiveLearnable
import com.steve1316.uma_android_automation.components.LabelGrandLiveLessonCost
import com.steve1316.uma_android_automation.components.LabelGrandLiveMaxHype
import com.steve1316.uma_android_automation.components.LabelGrandLivePerformancePoints
import com.steve1316.uma_android_automation.types.Aptitude
import com.steve1316.uma_android_automation.types.StatName
import org.opencv.core.Point

/**
 * A learnable Lessons card paired with its on-screen tap point (derived from the matched cost-pill anchor).
 *
 * @property option The pure policy data for this card.
 * @property point The on-screen point to tap to open this card.
 */
private data class ScannedLesson(val option: LessonOption, val point: Point)

/** Where a Lessons visit returns when done: the main screen (per-turn), the concert screen, or the career-end screen. */
private enum class LessonReturn { MAIN, CONCERT, END }

/**
 * Handles the Grand Live (Our Grand Concert) scenario: the Lessons facility (technique-priority) and the
 * Promotional / Grand Live concerts, on top of the normal training-and-racing career loop.
 *
 * @property game The [Game] instance for interacting with the game state.
 */
class GrandLive(game: Game) : Campaign(game) {
    override val training: GrandLiveTraining = GrandLiveTraining(game, this)

    // Grand Live's bottom action row has four buttons (Lessons is added), so Infirmary / Recreation / Races render smaller than the standard templates and need the "_mini" crops.
    override val infirmaryButton: ComponentInterface = ButtonInfirmaryMini
    override val recreationButton: ComponentInterface = ButtonRecreationMini
    override val racesButton: ComponentInterface = ButtonRacesMini

    // The career-end screen's bottom row is Skills / Complete Career / Lessons, so the Skills button is smaller than the standard template.
    override val careerEndSkillsButton: ComponentInterface = ButtonCareerEndSkillsMini

    // The race-day bottom row is Skills / Race / Lessons, so the Race button is smaller than the standard race-day template.
    override val raceDayButton: ComponentInterface = ButtonRaceDayMini

    /** Career day of the last Lessons scan, or -1 if never scanned. Paces how often Lessons is re-opened to poll for newly-learnable cards. */
    private var lastLessonScanDay: Int = -1

    /** Whether the Hype gauge is currently maxed (read off the main screen). When maxed, no Song needs buying for Hype, so tokens can be saved for a sought-after locked Lesson. */
    private var isHypeMaxed: Boolean = false

    /** User-ranked Lesson effect categories (Scenario Overrides). Categories left unranked score zero, so they are only bought when nothing ranked is learnable. */
    private val lessonEffectPriority: List<LessonEffectCategory> =
        SettingsHelper.getStringArraySetting("scenarioOverrides", "grandLiveLessonEffectPriority").mapNotNull { LessonEffectCategory.fromDisplayName(it) }.ifEmpty { DEFAULT_LESSON_EFFECT_PRIORITY }

    /** Stat order applied to Lessons stat gains (Scenario Overrides). Falls back to the global training prioritization when the user has not set a Grand Live one. */
    private val lessonStatPriority: List<StatName> =
        SettingsHelper.getStringArraySetting("scenarioOverrides", "grandLiveLessonStatPriority").mapNotNull { StatName.fromName(it) }.ifEmpty { training.statPrioritization }

    /** User-ranked skill-hint tags (Scenario Overrides). Empty means no hint preference, which is the original behavior. */
    private val lessonHintPriority: List<LessonHintTag> =
        SettingsHelper.getStringArraySetting("scenarioOverrides", "grandLiveLessonHintPriority").mapNotNull { LessonHintTag.fromDisplayName(it) }

    /** User-ranked Song titles (Scenario Overrides). A ranked Song is bought ahead of anything else learnable. Empty means no song preference. */
    private val lessonSongPriority: List<String> = SettingsHelper.getStringArraySetting("scenarioOverrides", "grandLiveSongPriority")

    /** Turns between Lessons re-checks (Scenario Overrides). The list is static until a purchase, so polling more often than tokens grow wastes screen time. */
    private val lessonRescanInterval: Int = SettingsHelper.getIntSetting("scenarioOverrides", "grandLiveLessonRescanInterval", 2)

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Debug tests

    override fun startTests(): Boolean {
        var bDidAnyTestsRun = super.startTests()

        val fnMap: Map<String, () -> Unit> =
            mapOf(
                "debugMode_startGrandLiveTokenGainTest" to ::startGrandLiveTokenGainTest,
            )

        for ((settingName, fn) in fnMap) {
            if (SettingsHelper.getBooleanSetting("debug", settingName)) {
                fn()
                bDidAnyTestsRun = true
            }
        }

        return bDidAnyTestsRun
    }

    /**
     * Debug test: read the five Performance-Point token totals once, then select each of the five training facilities in turn
     * and read that facility's per-token gains, logging both. Values and gains are read via the shared YOLO digit reader (the same
     * model that reads training stat gains), so this also serves as the on-device calibrator for the crop offsets in [tokenCrops].
     */
    fun startGrandLiveTokenGainTest() {
        MessageLog.v(TAG, "[GRAND_LIVE][TEST] Starting the Grand Live Token Gain Test.")

        val sourceBitmap = game.imageUtils.getSourceBitmap()
        val center = LabelGrandLivePerformancePoints.findImageWithBitmap(game.imageUtils, sourceBitmap)
        if (center == null) {
            MessageLog.w(TAG, "[GRAND_LIVE][TEST] Performance Points header not found; is the Training screen open?")
            return
        }

        // Token totals are the same regardless of the selected facility, so read them once.
        val totals = readTokenTotals(sourceBitmap, center)

        // Each facility grants different token types, so select each of the five in turn and read its per-token gains.
        val facilityGains = linkedMapOf<StatName, List<Pair<String, Int?>>>()
        for (statName in StatName.entries) {
            if (!goToTrainingFacility(training.trainingButtons.getValue(statName), training.iconTrainingHeaders.getValue(statName))) {
                MessageLog.w(TAG, "[GRAND_LIVE][TEST] Could not select the ${statName.name.lowercase()} facility; skipping.")
                continue
            }

            val facilityBitmap = game.imageUtils.getSourceBitmap()
            val facilityCenter = LabelGrandLivePerformancePoints.findImageWithBitmap(game.imageUtils, facilityBitmap) ?: continue
            facilityGains[statName] =
                GRAND_LIVE_TOKEN_LABELS.mapIndexed { i, label ->
                    val (_, gainCrop) = tokenCrops(i)
                    label to readTokenNumber(game.imageUtils, facilityBitmap, facilityCenter, gainCrop, requirePlus = true, debugName = "grandlive_${statName.name.lowercase()}_${label}_gain")
                }
        }

        // Print a readable matrix: tokens as right-aligned columns, then the totals row and one row per facility's per-token gains.
        fun row(label: String, cells: List<String>): String = "  " + label.padEnd(9) + cells.joinToString("") { it.padStart(6) }
        val header = row("Facility", GRAND_LIVE_TOKEN_LABELS)
        val totalsRow = row("Totals", totals.map { it.second?.toString() ?: "?" })
        val facilityRows = facilityGains.map { (statName, gains) -> row(statName.name, gains.map { "+${it.second ?: 0}" }) }
        val summary = (listOf("[GRAND_LIVE][TEST] Token Gain Test summary:", header, totalsRow) + facilityRows).joinToString("\n")
        MessageLog.i(TAG, summary)
    }

    /**
     * Read the five Performance-Point token totals from the vertical panel, anchored to the matched header center.
     *
     * @param sourceBitmap The screenshot to read from.
     * @param center The matched `grandlive_performance_points` header center.
     * @return The five (label, total) pairs in Da/Pa/Vo/Vi/Co order.
     */
    private fun readTokenTotals(sourceBitmap: Bitmap, center: Point): List<Pair<String, Int?>> =
        GRAND_LIVE_TOKEN_LABELS.mapIndexed { i, label ->
            val (valueCrop, _) = tokenCrops(i)
            label to readTokenNumber(game.imageUtils, sourceBitmap, center, valueCrop, requirePlus = false, debugName = "grandlive_token_${label}_value")
        }

    /**
     * Read and log the Performance-Point token totals off the main screen's vertical panel (logging only - purchasability comes from
     * the Lessons screen's "Learnable" banners), and refresh [isHypeMaxed] off the same screenshot.
     */
    private fun updateTokenTotals() {
        val sourceBitmap = game.imageUtils.getSourceBitmap()
        val center = LabelGrandLivePerformancePoints.findImageWithBitmap(game.imageUtils, sourceBitmap)
        if (center == null) {
            MessageLog.w(TAG, "[GRAND_LIVE] Performance Points panel not found on the main screen; skipping token totals read.")
            return
        }
        val totals = readTokenTotals(sourceBitmap, center)
        isHypeMaxed = IconGrandLiveGreatHype.check(game.imageUtils, sourceBitmap = sourceBitmap)
        MessageLog.i(TAG, "[GRAND_LIVE] Token totals: ${totals.joinToString(", ") { "${it.first}=${it.second ?: "?"}" }} (Hype maxed: $isHypeMaxed)")
    }

    /**
     * Select a training facility for the token-gain test by tapping its button until its header appears (mirrors the training analyzer's goToStat).
     *
     * @param button The facility's training selection button.
     * @param header The facility's header icon, used to confirm the facility is selected.
     * @return True once the facility is selected, false after three failed attempts.
     */
    private fun goToTrainingFacility(button: ComponentInterface, header: ComponentInterface): Boolean {
        if (header.check(game.imageUtils)) return true
        for (attempt in 0..2) {
            button.click(game.imageUtils)
            game.wait(0.3, skipWaitingForLoading = true)
            if (header.check(game.imageUtils)) return true
        }
        return false
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Scenario routing

    override fun onMainScreenEntry() {
        super.onMainScreenEntry()
        // Log the Performance-Point token totals and refresh the maxed-Hype flag off the main screen.
        updateTokenTotals()
        // Lessons is a free side-action (it does not consume the turn), so spend Performance Points on techniques here every
        // main-screen turn. This must live on the main-screen hook - checkCampaignSpecificConditions is not reached on a normal turn.
        if (shouldOpenLessonsThisTurn()) {
            runLessonPurchases(forceMaxHype = false)
        }
    }

    override fun onEndScreenEntry() {
        super.onEndScreenEntry()
        // The career is ending, so spend the remaining Performance Points in Lessons (skipping useless Energy options via isFinalConcert)
        // before the run completes. Opened from the end screen, so back out to it.
        if (ButtonGrandLiveLessons.check(game.imageUtils)) {
            runLessonPurchases(forceMaxHype = false, isFinalConcert = true, returnTo = LessonReturn.END)
        }
    }

    override fun checkCampaignSpecificConditions(): Boolean {
        // The concert-day screen shows only the Lessons + Concert buttons (not the main screen), so it is reached here. Handle the live flow.
        // The final concert uses a distinct "Grand Concert" button and a skip-cutscene confirmation, so check it first.
        val sourceBitmap = game.imageUtils.getSourceBitmap()
        if (ButtonGrandLiveGrandConcert.check(game.imageUtils, sourceBitmap = sourceBitmap)) {
            handleGrandConcert()
            return true
        }
        if (ButtonGrandLiveConcert.check(game.imageUtils, sourceBitmap = sourceBitmap)) {
            handleConcertDay()
            return true
        }
        return false
    }

    /**
     * Whether to open Lessons this turn. The "Learnable" banners are only visible on the Lessons screen, so open on the first turn and
     * then poll every [lessonRescanInterval] turns rather than churning through the static list every turn.
     *
     * @return True when the per-turn Lessons side-action should run.
     */
    private fun shouldOpenLessonsThisTurn(): Boolean {
        // A misread date can land ahead of the real turn, which would otherwise hold the interval closed for as many turns as the date overshot.
        if (lastLessonScanDay > date.day) {
            MessageLog.i(TAG, "[GRAND_LIVE] Last Lessons scan is dated after the current turn (day ${date.day} < $lastLessonScanDay); re-checking now.")
            lastLessonScanDay = -1
        }
        if (lastLessonScanDay >= 0 && date.day - lastLessonScanDay < lessonRescanInterval) {
            MessageLog.i(TAG, "[GRAND_LIVE] Skipping Lessons: re-checked recently, next poll in ${lessonRescanInterval - (date.day - lastLessonScanDay)} turn(s).")
            return false
        }
        return ButtonGrandLiveLessons.check(game.imageUtils)
    }

    /**
     * Open Lessons and keep buying the best learnable item until the policy stops. Re-scans after each purchase because the
     * list refreshes. When `forceMaxHype`, also buys Songs until the Hype gauge is maxed.
     *
     * @param forceMaxHype Whether to ensure the Hype gauge is maxed before stopping (concert-day).
     * @param isFinalConcert Whether this is the final Grand Concert / career-end visit, where Energy gains do nothing.
     * @param returnTo Which screen to back out to when done.
     */
    private fun runLessonPurchases(forceMaxHype: Boolean, isFinalConcert: Boolean = false, returnTo: LessonReturn = LessonReturn.MAIN) {
        val lessonsButton = if (forceMaxHype) ButtonGrandLiveLessonsBig else ButtonGrandLiveLessons
        if (!lessonsButton.click(game.imageUtils, tries = 5)) {
            MessageLog.w(TAG, "[GRAND_LIVE] Could not open the Lessons screen.")
            return
        }
        // Wait for the Lessons screen to finish loading and its slide-in animation to settle before the first scan, otherwise the cards are not on screen yet.
        game.waitForLoading()
        game.wait(1.0)

        var hypeMaxed = false
        // Energy cards cancelled for overflow this visit. The list is static until a purchase, so remember them to avoid re-picking the same one.
        val skippedEnergyCards = mutableSetOf<String>()
        for (iteration in 0 until MAX_LESSON_PURCHASES_PER_VISIT) {
            val scanned = scanLessons()
            // On a normal turn, hold tokens if a sought-after card is still locked (and Hype is maxed with time to spare) rather than spending them on lesser cards.
            if (iteration == 0 && !forceMaxHype && shouldWaitForLockedLesson(scanned)) {
                MessageLog.i(TAG, "[GRAND_LIVE] Holding tokens this turn - a sought-after Lesson is still locked and Hype is maxed, so saving for it.")
                break
            }
            // Any learnable card is worth buying (spend tokens, refresh the list).
            val learnable = scanned.filter { it.option.purchasable && it.option.name !in skippedEnergyCards }
            // At the final concert, energy does nothing, so prefer non-Energy cards. But if Energy is all that is left, buy one anyway to refresh the list (a new non-Energy card may surface).
            val purchasable =
                if (isFinalConcert) {
                    val nonEnergy = learnable.filter { parseEnergyGain(it.option.effectText) == null }
                    if (nonEnergy.isNotEmpty()) nonEnergy else learnable
                } else {
                    learnable
                }
            // Prefer on-style options; a skill hint for a running style we cannot use is only bought when it is the sole affordable option.
            val onStyle = purchasable.filter { !isOffStyleHint(it.option.effectText) }
            val candidates = if (onStyle.isNotEmpty()) onStyle else purchasable
            val choice =
                chooseLessonPurchase(candidates.map { it.option }, lessonStatPriority, forceMaxHype, hypeMaxed, lessonEffectPriority, lessonHintPriority, lessonSongPriority)
            if (choice == null) {
                MessageLog.i(TAG, "[GRAND_LIVE] No learnable Lessons purchase; leaving the Lessons screen.")
                break
            }

            MessageLog.i(TAG, "[GRAND_LIVE] Buying ${choice.kind} '${choice.name}' at row ${choice.rowIndex}: '${choice.effectText}'.")
            val target = scanned[choice.rowIndex]
            game.tap(target.point.x, target.point.y)
            game.wait(game.dialogWaitDelay)

            val confirmSource = game.imageUtils.getSourceBitmap()

            // For an Energy card away from the final concert, read the dialog's printed "<new> / <cap>" and cancel if it would cap out (wasted energy).
            // Reading the dialog avoids relying on the trainee's energy, which is unknown when the bot is started straight on a concert screen.
            if (!isFinalConcert && parseEnergyGain(choice.effectText) != null) {
                val projected = readDialogProjectedEnergy(confirmSource)
                if (projected != null && projected.first >= projected.second) {
                    MessageLog.i(TAG, "[GRAND_LIVE] Skipping '${choice.name}': projected energy ${projected.first}/${projected.second} would overflow.")
                    ButtonCancel.click(game.imageUtils, sourceBitmap = confirmSource, tries = 5)
                    skippedEnergyCards.add(choice.name)
                    game.wait(game.dialogWaitDelay)
                    continue
                }
            }

            val maxesHype = LabelGrandLiveMaxHype.check(game.imageUtils, sourceBitmap = confirmSource)
            if (maxesHype) {
                hypeMaxed = true
                MessageLog.i(TAG, "[GRAND_LIVE] This purchase maxes the Hype gauge.")
            }
            // The Lessons purchase confirmation uses a "Learn" button (not the generic Confirm).
            ButtonLearn.click(game.imageUtils, sourceBitmap = confirmSource, tries = 5)
            // Buying syncs to the server ("Connecting") and repaints the list over a couple of transitions. Clear loading, then wait a
            // fixed few seconds so the next findAll runs on a settled Lessons list instead of a mid-transition frame that reads zero cards.
            // A purchase that maxes Hype plays an extra gauge animation, so give it longer to settle.
            game.waitForLoading()
            game.wait(if (maxesHype) 5.0 else 3.0)
        }

        // Record the day to pace the next re-open.
        lastLessonScanDay = date.day

        when (returnTo) {
            // Lessons was opened from the concert screen, so back out until its Concert / Grand Concert button reappears.
            LessonReturn.CONCERT ->
                backOutUntil("concert") { bitmap ->
                    ButtonGrandLiveConcert.check(game.imageUtils, sourceBitmap = bitmap) || ButtonGrandLiveGrandConcert.check(game.imageUtils, sourceBitmap = bitmap)
                }

            // Lessons was opened from the career-end screen, so back out until its Complete Career button reappears.
            LessonReturn.END -> backOutUntil("career-end") { bitmap -> ButtonCompleteCareer.check(game.imageUtils, sourceBitmap = bitmap) }

            LessonReturn.MAIN -> {
                // Normal per-turn flow: return to the main screen and confirm it. A single Back can fail to escape this screen (themed Back
                // button / a leave confirmation), so back out with the same Back/Cancel/Close combo the misc handler uses until main is detected.
                var returnedToMain = false
                for (attempt in 0 until 5) {
                    if (checkMainScreen()) {
                        returnedToMain = true
                        if (attempt > 0) MessageLog.i(TAG, "[GRAND_LIVE] Returned to the main screen after Lessons in ${attempt + 1} back-out attempt(s).")
                        break
                    }
                    // At most one of the three is on screen, so share one screenshot across them.
                    val sourceBitmap = game.imageUtils.getSourceBitmap()
                    ButtonBack.click(game.imageUtils, sourceBitmap = sourceBitmap)
                    ButtonCancel.click(game.imageUtils, sourceBitmap = sourceBitmap)
                    ButtonClose.click(game.imageUtils, sourceBitmap = sourceBitmap)
                    game.wait(game.waitDelay)
                }
                if (!returnedToMain) MessageLog.w(TAG, "[GRAND_LIVE] Could not confirm a return to the main screen after Lessons.")
            }
        }
    }

    /**
     * Back out of the Lessons screen by tapping Back until the target screen's marker appears, stopping the moment it does so the
     * target is never overshot.
     *
     * @param screenName The target screen's name for the failure log.
     * @param isAtTarget Whether the given screenshot shows the target screen.
     */
    private fun backOutUntil(screenName: String, isAtTarget: (Bitmap) -> Boolean) {
        for (attempt in 0 until 5) {
            val sourceBitmap = game.imageUtils.getSourceBitmap()
            if (isAtTarget(sourceBitmap)) {
                // The marker can match while the screen is still sliding in, so let the transition settle before the caller taps anything on it.
                game.wait(2.0)
                return
            }
            ButtonBack.click(game.imageUtils, sourceBitmap = sourceBitmap)
            game.waitForLoading()
            game.wait(game.waitDelay)
        }
        MessageLog.w(TAG, "[GRAND_LIVE] Could not confirm a return to the $screenName screen after Lessons.")
    }

    /**
     * Scan the Lessons screen by enumerating each card off its `grandlive_lesson_cost` ("Performance Point Cost") pill and reading
     * its name, kind, and effect lines. Purchasability comes from the "Learnable!" ribbon above each card, which is identical artwork on
     * every card, so the kind is read separately off the kind tag. Every card is logged so a buy-nothing visit is always explainable.
     *
     * @return The [ScannedLesson]s currently on screen, top-to-bottom, each with its on-screen tap point.
     */
    private fun scanLessons(): List<ScannedLesson> {
        val sourceBitmap = game.imageUtils.getSourceBitmap()
        val anchors = LabelGrandLiveLessonCost.findAll(game.imageUtils, sourceBitmap = sourceBitmap).sortedBy { it.y }
        if (anchors.isEmpty()) {
            // Probe a looser confidence so the log tells us whether the anchor is a threshold miss (loose > 0 -> lower the confidence) or a crop mismatch (loose == 0 -> recrop grandlive_performance_point_cost).
            val loose = LabelGrandLiveLessonCost.findAll(game.imageUtils, sourceBitmap = sourceBitmap, confidence = 0.7).size
            MessageLog.i(
                TAG,
                "[GRAND_LIVE] Lessons scan: no cost-pill anchors at default confidence ($loose at 0.7). If >0, the crop matches but the threshold is too strict; if 0, recrop grandlive_performance_point_cost.",
            )
            // Dump the exact frame the scan ran against (debug mode only) so the actual Lessons screen can be inspected.
            game.imageUtils.saveDebugScreenshot(sourceBitmap, "grandlive_lessons_scan_empty")
            return emptyList()
        }

        // A "Learnable!" ribbon above a card means it is purchasable. Match each one to the card whose cost pill sits below it.
        // The template averages the ribbon over both card colours (purple Song, green Technique) because the header tints the ribbon's lower rows.
        val ribbonYs = LabelGrandLiveLearnable.findAll(game.imageUtils, sourceBitmap = sourceBitmap, confidence = LESSON_RIBBON_CONFIDENCE).map { it.y }
        if (ribbonYs.isEmpty()) {
            // All cards locked is normal late in a run, so stay quiet unless the probe shows the ribbon is there and only the threshold rejected it.
            // Not debug-gated on purpose: a stale crop has to be visible in an ordinary run, which is how the "Songs" -> "Song" reword slipped by.
            val loose = LabelGrandLiveLearnable.findAll(game.imageUtils, sourceBitmap = sourceBitmap, confidence = 0.7).size
            if (loose > 0) {
                MessageLog.w(TAG, "[GRAND_LIVE] $loose ribbon(s) match at 0.7 but none at $LESSON_RIBBON_CONFIDENCE, so every card reads as locked. Lower the threshold or recrop grandlive_learnable.")
                game.imageUtils.saveDebugScreenshot(sourceBitmap, "grandlive_lessons_no_ribbon")
            }
        }

        MessageLog.i(TAG, "[GRAND_LIVE] Lessons scan found ${anchors.size} card(s) and ${ribbonYs.size} Learnable ribbon(s):")
        return anchors.mapIndexed { rowIndex, anchor ->
            val purchasable = hasLearnableRibbon(ribbonYs, anchor.y, game.imageUtils.relHeight(LESSON_CARD_HEIGHT))

            val name = ocrCard(sourceBitmap, anchor, LESSON_CARD_CROPS.name, "grandlive_lesson_name_$rowIndex")
            val effect1 = ocrCard(sourceBitmap, anchor, LESSON_CARD_CROPS.effect1, "grandlive_lesson_effect1_$rowIndex")
            val effect2 = ocrCard(sourceBitmap, anchor, LESSON_CARD_CROPS.effect2, "grandlive_lesson_effect2_$rowIndex")

            // The ribbon is kind-agnostic, so the kind tag is the only kind signal. An unreadable tag falls back to Song and breaks concert day.
            val kindText = ocrCard(sourceBitmap, anchor, LESSON_CARD_CROPS.kind, "grandlive_lesson_kind_$rowIndex")
            val kind = if (kindText.contains("tech", ignoreCase = true)) LessonKind.TECHNIQUE else LessonKind.SONG
            if (!kindText.contains("song", ignoreCase = true) && kind == LessonKind.SONG) {
                MessageLog.w(TAG, "[GRAND_LIVE]   Row $rowIndex kind tag read as \"$kindText\"; defaulting to Song. Recheck LESSON_CARD_CROPS.kind if this repeats.")
            }
            val effectText = listOf(effect1, effect2).filter { it.isNotBlank() }.joinToString(" ")
            val tapPoint = Point(game.imageUtils.relX(anchor.x, 200).toDouble(), game.imageUtils.relY(anchor.y, -150).toDouble())

            // A Song the shipped list does not know cannot be ranked, and a title the game reworded would otherwise go unnoticed until someone
            // wondered why their Song Priority stopped applying.
            if (kind == LessonKind.SONG && name.isNotBlank() && matchSongRank(name, GRAND_LIVE_SONGS) == null) {
                MessageLog.w(TAG, "[GRAND_LIVE]   Song \"$name\" matches no known Song, so Song Priority cannot rank it. Recheck the shipped song list if this repeats.")
            }

            val status = if (purchasable) "Learnable" else "Locked"
            val kindLabel = kind.name.lowercase().replaceFirstChar { it.uppercase() }
            MessageLog.i(TAG, "[GRAND_LIVE]   ${name.ifBlank { "?" }} ($kindLabel $status)")
            MessageLog.i(TAG, "[GRAND_LIVE]     ${effect1.ifBlank { "None" }}")
            MessageLog.i(TAG, "[GRAND_LIVE]     ${effect2.ifBlank { "None" }}")

            ScannedLesson(option = LessonOption(kind = kind, name = name, effectText = effectText, purchasable = purchasable, rowIndex = rowIndex), point = tapPoint)
        }
    }

    /**
     * OCR a text region on a Lessons card, relative to the matched cost-pill anchor.
     *
     * @param sourceBitmap The Lessons-screen screenshot.
     * @param anchor The matched `grandlive_lesson_cost` pill center for this card.
     * @param crop The crop offsets from [LESSON_CARD_CROPS].
     * @param debugName Debug label for the OCR crop dump.
     * @return The trimmed OCR text (empty when nothing was read).
     */
    private fun ocrCard(sourceBitmap: Bitmap, anchor: Point, crop: TokenCrop, debugName: String): String =
        game.imageUtils.performOCROnRegion(
            sourceBitmap,
            game.imageUtils.relX(anchor.x, crop.dx),
            game.imageUtils.relY(anchor.y, crop.dy),
            game.imageUtils.relWidth(crop.width),
            game.imageUtils.relHeight(crop.height),
            useThreshold = false,
            scale = 1.5,
            ocrEngine = "mlkit",
            debugName = debugName,
        ).trim()

    /**
     * Whether a card's effect is a skill hint tied to a running style the trainee cannot use (aptitude below C), so it should not be
     * bought over an on-style option. A non-hint effect, a distance / generic hint, or an on-style hint all return false.
     *
     * A tag the user ranked in the hint priority is never treated as off-style - an explicit choice outranks the aptitude guess.
     *
     * @param effectText The card's effect line(s).
     * @return True when the effect is an off-style skill hint.
     */
    private fun isOffStyleHint(effectText: String): Boolean {
        val tags = parseHintTags(effectText)
        if (tags.any { it in lessonHintPriority }) return false
        val style = tags.firstNotNullOfOrNull { it.runningStyle } ?: return false
        val aptitude = trainee.runningStyleAptitudes[style] ?: return false
        return aptitude < Aptitude.C
    }

    /**
     * Whether to hold tokens this turn instead of buying lesser affordable cards. True when a sought-after card (matching a top-ranked
     * effect category) is currently locked, Hype is already maxed (no Song needed for it), and the career is not yet in its final
     * stretch - so tokens can accumulate for the locked card to become learnable next round.
     *
     * @param scanned The cards on the Lessons screen this scan.
     * @return True to skip buying and save tokens this turn.
     */
    private fun shouldWaitForLockedLesson(scanned: List<ScannedLesson>): Boolean {
        if (!isHypeMaxed || date.bIsFinaleSeason) return false
        return scanned.any { !it.option.purchasable && isSoughtAfter(it.option, lessonEffectPriority, lessonSongPriority) }
    }

    /**
     * Read the projected new energy the Lessons purchase confirmation prints as "<new> / <cap>" above its energy bar. Used to cancel an
     * Energy purchase that would cap out. Reads the dialog rather than the trainee's energy, which is unknown when the bot starts on a concert screen.
     *
     * @param sourceBitmap The confirmation-dialog screenshot.
     * @return The projected new energy and the cap it was read against, or null when the readout could not be parsed.
     */
    private fun readDialogProjectedEnergy(sourceBitmap: Bitmap): Pair<Int, Int>? {
        val text =
            game.imageUtils.performOCROnRegion(
                sourceBitmap,
                game.imageUtils.relX(0.0, 555),
                game.imageUtils.relY(0.0, 600),
                game.imageUtils.relWidth(250),
                game.imageUtils.relHeight(90),
                useThreshold = true,
                useGrayscale = true,
                scale = 2.0,
                ocrEngine = "mlkit",
                debugName = "grandlive_lesson_projected_energy",
            )
        val projected = parseProjectedEnergy(text)
        if (projected == null) {
            // Worth a warning: an unparsed readout disables the overflow check for this purchase, so energy can silently be wasted.
            MessageLog.w(TAG, "[GRAND_LIVE] Projected-energy readout OCR'd as \"$text\" -> unparsed; buying without the overflow check.")
        } else {
            MessageLog.i(TAG, "[GRAND_LIVE] Projected-energy readout OCR'd as \"$text\" -> ${projected.first}/${projected.second}.")
        }
        return projected
    }

    /**
     * Drive the concert-day flow: max Hype via Lessons, start the concert, skip the performance, advance the results, and
     * close the concert-bonus dialog back on the main screen.
     */
    private fun handleConcertDay() {
        MessageLog.i(TAG, "[GRAND_LIVE] Concert day detected. Maxing Hype via Lessons, then performing.")
        runLessonPurchases(forceMaxHype = true, returnTo = LessonReturn.CONCERT)

        if (!ButtonGrandLiveConcert.click(game.imageUtils, tries = 10)) {
            MessageLog.w(TAG, "[GRAND_LIVE] Could not tap the Concert button.")
            return
        }
        game.wait(game.dialogWaitDelay)
        // Confirmation dialog -> Start.
        ButtonGrandLiveStart.click(game.imageUtils, tries = 10)
        game.wait(2.0)

        // Skip the performance (bottom-right, like the Unity Cup race path), then advance the results.
        ButtonSkip.click(game.imageUtils, tries = 30)
        game.wait(1.0)
        ButtonNext.click(game.imageUtils, tries = 30)
        game.wait(1.0)
        ButtonNext.click(game.imageUtils, tries = 30)
        game.wait(game.dialogWaitDelay)

        // Back on the main screen: close the concert-bonus dialog (no need to Confirm).
        ButtonClose.click(game.imageUtils, tries = 10)
        game.wait(game.waitDelay)
    }

    /**
     * Drive the final Grand Concert flow: max Hype via Lessons, start the Grand Concert, tick the "Skip the Grand Concert cutscene"
     * checkbox once and confirm with Start, then advance the results and close the concert-bonus dialog back on the main screen.
     */
    private fun handleGrandConcert() {
        MessageLog.i(TAG, "[GRAND_LIVE] Grand Concert (final) detected. Maxing Hype via Lessons, then performing.")
        runLessonPurchases(forceMaxHype = true, isFinalConcert = true, returnTo = LessonReturn.CONCERT)

        if (!ButtonGrandLiveGrandConcert.click(game.imageUtils, tries = 10)) {
            MessageLog.w(TAG, "[GRAND_LIVE] Could not tap the Grand Concert button.")
            return
        }
        game.wait(game.dialogWaitDelay)

        // Confirmation dialog: tick the "Skip the Grand Concert cutscene" checkbox only when it is un-ticked. The un-ticked and ticked
        // states differ only by checkmark colour (grey vs green) and template matching is grayscale, so read the colour: a green
        // checkmark is already ticked and must be left alone (a tap would un-tick it).
        val confirmSource = game.imageUtils.getSourceBitmap()
        val checkbox = Checkbox.findImageWithBitmap(game.imageUtils, confirmSource)
        if (checkbox != null) {
            val boxSize = game.imageUtils.relWidth(44)
            val ticked = game.imageUtils.isRegionGreen(confirmSource, checkbox.x.toInt() - boxSize / 2, checkbox.y.toInt() - boxSize / 2, boxSize, boxSize)
            if (ticked) {
                MessageLog.i(TAG, "[GRAND_LIVE] Skip-cutscene checkbox is already ticked; leaving it.")
            } else {
                game.tap(checkbox.x, checkbox.y)
                MessageLog.i(TAG, "[GRAND_LIVE] Ticked the skip-cutscene checkbox.")
            }
        }
        game.wait(game.dialogWaitDelay)
        ButtonGrandLiveStart.click(game.imageUtils, tries = 10)

        // After Start, the Grand Concert plays out to a result/claim screen advanced by tapping (like the Inheritance claim). Wait for
        // it to appear, then tap the screen a few times to move through it; the main loop handles whatever screen follows.
        game.wait(3.0)
        repeat(3) {
            game.tap((SharedData.displayWidth / 2).toDouble(), (SharedData.displayHeight / 2).toDouble())
            game.wait(1.0)
        }
    }
}
