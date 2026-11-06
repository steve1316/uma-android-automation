package com.steve1316.uma_android_automation.bot.campaigns

import android.graphics.Bitmap
import android.graphics.Color
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.utils.BotHold
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.uma_android_automation.bot.Campaign
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.bot.tapTrainingOnce
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
import com.steve1316.uma_android_automation.components.IconNotificationBadge
import com.steve1316.uma_android_automation.components.LabelGrandLiveLearnable
import com.steve1316.uma_android_automation.components.LabelGrandLiveLessonCost
import com.steve1316.uma_android_automation.components.LabelGrandLiveMaxHype
import com.steve1316.uma_android_automation.components.LabelGrandLivePerformancePoints
import com.steve1316.uma_android_automation.types.Aptitude
import com.steve1316.uma_android_automation.types.StatName
import com.steve1316.uma_android_automation.utils.Anchor
import com.steve1316.uma_android_automation.utils.grayOf
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

    /** Turns without a Lessons visit before Lessons opens without its "!" badge (Scenario Overrides), as a fallback for a missed badge. */
    private val lessonRescanInterval: Int = SettingsHelper.getIntSetting("scenarioOverrides", "grandLiveLessonRescanInterval", 6)

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
     * Select a training facility for the token-gain test by tapping its button once and waiting for its header (mirrors the training analyzer's goToStat).
     *
     * @param button The facility's training selection button.
     * @param header The facility's header icon, used to confirm the facility is selected.
     * @return True once the facility is selected, false if its header did not show.
     */
    private fun goToTrainingFacility(button: ComponentInterface, header: ComponentInterface): Boolean {
        if (header.check(game.imageUtils)) return true
        return tapTrainingOnce({ button.click(game.imageUtils) }, { header.check(game.imageUtils) }, { game.wait(0.3, skipWaitingForLoading = true) })
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Scenario routing

    override fun onMainScreenEntry() {
        super.onMainScreenEntry()
        // Refresh the maxed-Hype flag off the main screen. The token totals are not read, since nothing uses them and the five reads cost about 2s a turn.
        val sourceBitmap = game.imageUtils.getSourceBitmap()
        isHypeMaxed = IconGrandLiveGreatHype.check(game.imageUtils, sourceBitmap = sourceBitmap)
        // Lessons is a free side-action (it does not consume the turn), so spend Performance Points on techniques here every
        // main-screen turn. This must live on the main-screen hook - checkCampaignSpecificConditions is not reached on a normal turn.
        if (shouldOpenLessonsThisTurn(sourceBitmap)) {
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

    override fun onResumeAfterAbort() {
        super.onResumeAfterAbort()
        // Re-open Lessons on the next main screen, since a purchase may have been cut short or made by hand while paused.
        lastLessonScanDay = -1
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
     * Whether to open Lessons this turn. The game badges the Lessons button with a "!" when a purchase is affordable, so Lessons opens on that.
     * As a fallback for a missed badge, it also opens once no visit has happened for [lessonRescanInterval] turns.
     *
     * @param sourceBitmap The Main screen screenshot.
     * @return True when the per-turn Lessons side-action should run.
     */
    private fun shouldOpenLessonsThisTurn(sourceBitmap: Bitmap): Boolean {
        val lessonsButton = ButtonGrandLiveLessons.findImageWithBitmap(game.imageUtils, sourceBitmap) ?: return false
        val badgeRegion = anchoredRect(lessonsButton, LESSONS_BADGE_REGION)
        if (IconNotificationBadge.findImageWithBitmap(game.imageUtils, sourceBitmap, region = badgeRegion, confidence = LESSONS_BADGE_CONFIDENCE) != null) return true

        // A misread date can land ahead of the real turn, which would otherwise hold the fallback closed for as many turns as the date overshot.
        if (lastLessonScanDay > date.day) {
            MessageLog.i(TAG, "[GRAND_LIVE] Last Lessons scan is dated after the current turn (day ${date.day} < $lastLessonScanDay); re-checking now.")
            lastLessonScanDay = -1
        }
        val turnsSinceVisit = if (lastLessonScanDay < 0) null else date.day - lastLessonScanDay
        if (turnsSinceVisit != null && turnsSinceVisit < lessonRescanInterval) {
            MessageLog.i(TAG, "[GRAND_LIVE] Skipping Lessons: the Lessons button has no \"!\" badge, so nothing is affordable.")
            return false
        }
        MessageLog.i(TAG, "[GRAND_LIVE] Opening Lessons without a \"!\" badge as a fallback, since the last visit was ${turnsSinceVisit?.let { "$it turn(s) ago" } ?: "never"}.")
        return true
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
        var dialogMismatches = 0
        for (iteration in 0 until MAX_LESSON_PURCHASES_PER_VISIT) {
            // On a normal turn, hold tokens if a sought-after card is still locked (and Hype is maxed with time to spare) rather than spending them on lesser cards.
            val canHoldTokens = iteration == 0 && !forceMaxHype && isHypeMaxed && !date.bIsFinaleSeason
            val scanned = scanLessons(canHoldTokens)
            if (canHoldTokens && shouldWaitForLockedLesson(scanned)) {
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
            val listBefore = lessonListGray(game.imageUtils.getSourceBitmap())
            game.tap(target.point.x, target.point.y)
            game.wait(game.dialogWaitDelay)

            val confirmSource = game.imageUtils.getSourceBitmap()
            // Only the title and energy checks below read from the header, so it is matched the first time one of them needs it.
            val dialogHeader: Point? by lazy { game.imageUtils.findImageWithBitmap(LESSON_DIALOG_HEADER_TEMPLATE, confirmSource, suppressError = true) }

            // The list can refresh under the tap and open a different card than the one scanned, so check the dialog names the chosen card.
            // An unreadable card name cannot be checked, so it falls through to the Learn button check below.
            if (choice.name.isNotBlank()) {
                // A missing header means no dialog opened, which counts as a mismatch so it is cancelled and rescanned.
                val dialogTitle = dialogHeader?.let { ocrCard(confirmSource, it, LESSON_DIALOG_TITLE_CROP, "grandlive_lesson_dialog_title") } ?: ""
                if (!isSameLessonTitle(dialogTitle, choice.name)) {
                    MessageLog.w(TAG, "[GRAND_LIVE] Purchase dialog reads \"$dialogTitle\", not '${choice.name}'. Cancelling and rescanning.")
                    cancelPurchaseDialog(confirmSource)
                    if (++dialogMismatches >= MAX_LESSON_DIALOG_MISMATCHES) break
                    continue
                }
            }

            // For an Energy card away from the final concert, read the dialog's printed "<new> / <cap>" and cancel if it would cap out (wasted energy).
            // Reading the dialog avoids relying on the trainee's energy, which is unknown when the bot is started straight on a concert screen.
            if (!isFinalConcert && parseEnergyGain(choice.effectText) != null) {
                val projected = dialogHeader?.let { readDialogProjectedEnergy(confirmSource, it) }
                if (projected != null && projected.first >= projected.second) {
                    MessageLog.i(TAG, "[GRAND_LIVE] Skipping '${choice.name}': projected energy ${projected.first}/${projected.second} would overflow.")
                    cancelPurchaseDialog(confirmSource)
                    skippedEnergyCards.add(choice.name)
                    continue
                }
            }

            val maxesHype = LabelGrandLiveMaxHype.check(game.imageUtils, sourceBitmap = confirmSource)
            if (maxesHype) {
                hypeMaxed = true
                MessageLog.i(TAG, "[GRAND_LIVE] This purchase maxes the Hype gauge.")
            }
            // The Lessons purchase confirmation uses a "Learn" button (not the generic Confirm). Without one the game refused the purchase
            // (e.g. "Not enough performance points"), so close the dialog rather than leave it covering the screen.
            if (!ButtonLearn.click(game.imageUtils, sourceBitmap = confirmSource, tries = 5)) {
                MessageLog.w(TAG, "[GRAND_LIVE] No Learn button on the purchase dialog for '${choice.name}'. Cancelling and leaving the Lessons screen.")
                cancelPurchaseDialog(confirmSource)
                break
            }
            // Buying syncs to the server ("Connecting") and repaints the list over a couple of transitions. Clear loading, then wait until the
            // bought card is gone so the next scan reads the new list. A fixed wait was sometimes too short and tapped a card that had just changed.
            game.waitForLoading()
            waitForLessonsRefresh(listBefore)
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
                val attempt = returnToMain(maxSteps = 5, bHandleDialogs = false)
                if (attempt > 0) MessageLog.i(TAG, "[GRAND_LIVE] Returned to the main screen after Lessons in ${attempt + 1} back-out attempt(s).")
                if (attempt < 0) MessageLog.w(TAG, "[GRAND_LIVE] Could not confirm a return to the main screen after Lessons.")
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
            // A leftover dialog has no Back button, so fall back to Cancel to close it.
            if (!ButtonBack.click(game.imageUtils, sourceBitmap = sourceBitmap)) ButtonCancel.click(game.imageUtils, sourceBitmap = sourceBitmap)
            game.waitForLoading()
            game.wait(game.waitDelay)
        }
        MessageLog.w(TAG, "[GRAND_LIVE] Could not confirm a return to the $screenName screen after Lessons.")
    }

    /**
     * Scan the Lessons screen by enumerating each card off its `grandlive_lesson_cost` ("Performance Point Cost") pill and reading
     * its name, kind, and effect lines. Purchasability comes from the "Learnable!" ribbon above each card, which is identical artwork on
     * every card, so the kind is read separately off the kind tag. Every card read is logged so a buy-nothing visit is always explainable.
     * A locked card is only read when it could change the purchase. See [shouldReadLessonCard].
     *
     * @param canHoldTokens Whether this scan may hold tokens for a sought-after locked card, which is the only time locked cards are read.
     * @return The [ScannedLesson]s currently on screen, top-to-bottom, each with its on-screen tap point. An unread card has a blank name and effect.
     */
    private fun scanLessons(canHoldTokens: Boolean): List<ScannedLesson> {
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
            val tapPoint = Point(game.imageUtils.relX(anchor.x, 200).toDouble(), game.imageUtils.relY(anchor.y, -150).toDouble())
            if (!shouldReadLessonCard(purchasable, ribbonYs.isNotEmpty(), canHoldTokens)) {
                MessageLog.i(TAG, "[GRAND_LIVE]   Row $rowIndex is locked and cannot change this visit's purchase, so it is not read.")
                // The kind is unread too. A locked card is never bought, so it does not matter.
                return@mapIndexed ScannedLesson(option = LessonOption(kind = LessonKind.SONG, name = "", effectText = "", purchasable = false, rowIndex = rowIndex), point = tapPoint)
            }

            val name = ocrCard(sourceBitmap, anchor, LESSON_CARD_CROPS.name, "grandlive_lesson_name_$rowIndex")
            val effect1 = ocrCard(sourceBitmap, anchor, LESSON_CARD_CROPS.effect1, "grandlive_lesson_effect1_$rowIndex")
            val effect2 = ocrCard(sourceBitmap, anchor, LESSON_CARD_CROPS.effect2, "grandlive_lesson_effect2_$rowIndex")

            // The ribbon is kind-agnostic, so the header color tells the kind, with the kind tag read only when the color is unclear.
            // An unreadable tag falls back to Song and breaks concert day.
            val kind =
                lessonKindFromHeaderHue(headerHue(sourceBitmap, anchor)) ?: run {
                    val kindText = ocrCard(sourceBitmap, anchor, LESSON_CARD_CROPS.kind, "grandlive_lesson_kind_$rowIndex")
                    val kindFromText = if (kindText.contains("tech", ignoreCase = true)) LessonKind.TECHNIQUE else LessonKind.SONG
                    if (!kindText.contains("song", ignoreCase = true) && kindFromText == LessonKind.SONG) {
                        MessageLog.w(TAG, "[GRAND_LIVE]   Row $rowIndex kind tag read as \"$kindText\"; defaulting to Song. Recheck LESSON_CARD_CROPS.kind if this repeats.")
                    }
                    kindFromText
                }
            val effectText = listOf(effect1, effect2).filter { it.isNotBlank() }.joinToString(" ")

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
     * Wait for the Lessons list to finish refreshing after a purchase: the old cards are gone, the new ones hold still, and their cost pills show,
     * which the purchase animation covers. Gives up after [LESSON_REFRESH_TIMEOUT_SECONDS] and lets the purchase dialog title check catch any stale tap.
     *
     * @param listBefore The list's gray pixels before the purchase, from [lessonListGray].
     */
    private fun waitForLessonsRefresh(listBefore: IntArray) {
        val deadline = System.currentTimeMillis() + (LESSON_REFRESH_TIMEOUT_SECONDS * 1000).toLong()
        var previous: IntArray? = null
        while (System.currentTimeMillis() < deadline) {
            val sourceBitmap = game.imageUtils.getSourceBitmap()
            val current = lessonListGray(sourceBitmap)
            if (isLessonListSettled(listBefore, previous, current) && LabelGrandLiveLessonCost.check(game.imageUtils, sourceBitmap = sourceBitmap)) return
            previous = current
            game.wait(LESSON_REFRESH_POLL_SECONDS, skipWaitingForLoading = true)
        }
        MessageLog.w(TAG, "[GRAND_LIVE] The Lessons list did not settle within ${LESSON_REFRESH_TIMEOUT_SECONDS}s of the purchase. Rescanning anyway.")
    }

    /**
     * Samples the Lessons list as gray pixels, every 4th pixel across the band the cards fill, to tell when the list changes or holds still. The
     * band follows the centered cards, so on a tall screen it stays clear of the animated stage lights and music notes above and below them.
     *
     * @param sourceBitmap The screenshot.
     * @return The sampled gray pixels.
     */
    private fun lessonListGray(sourceBitmap: Bitmap): IntArray {
        val left = (sourceBitmap.width * 0.05).toInt()
        val right = (sourceBitmap.width * 0.95).toInt()
        val top = game.imageUtils.relY(LESSON_LIST_TOP_Y, Anchor.CENTER)
        val bottom = game.imageUtils.relY(LESSON_LIST_BOTTOM_Y, Anchor.CENTER)
        val row = IntArray(right - left)
        val columns = (row.size + 3) / 4
        val gray = IntArray(((bottom - top + 3) / 4) * columns)
        for ((rowIndex, y) in (top until bottom step 4).withIndex()) {
            sourceBitmap.getPixels(row, 0, row.size, left, y, row.size, 1)
            for (column in 0 until columns) gray[rowIndex * columns + column] = grayOf(row[column * 4])
        }
        return gray
    }

    /**
     * Finds the median hue of a card header's colored pixels, which tells a Song (purple) from a Technique (green) without a text read.
     *
     * @param sourceBitmap The Lessons-screen screenshot.
     * @param anchor The card's cost-pill center.
     * @return The median hue in degrees, or null when the header has too few colored pixels to tell.
     */
    private fun headerHue(sourceBitmap: Bitmap, anchor: Point): Float? {
        val (x, y, width, height) = anchoredRect(anchor, LESSON_CARD_CROPS.name).toList()
        val header = game.imageUtils.createSafeBitmap(sourceBitmap, x, y, width, height, "grandlive_lesson_header") ?: return null
        val pixels = IntArray(header.width * header.height)
        header.getPixels(pixels, 0, header.width, 0, 0, header.width, header.height)
        val hsv = FloatArray(3)
        val hues = ArrayList<Float>()
        for (pixel in pixels) {
            Color.colorToHSV(pixel, hsv)
            if (hsv[1] > 0.25f) hues.add(hsv[0])
        }
        if (hues.size < pixels.size / 2) return null
        hues.sort()
        return hues[hues.size / 2]
    }

    /**
     * Close a Lessons purchase dialog with Cancel and let it fade before the next screenshot.
     *
     * @param sourceBitmap The purchase-dialog screenshot.
     */
    private fun cancelPurchaseDialog(sourceBitmap: Bitmap) {
        ButtonCancel.click(game.imageUtils, sourceBitmap = sourceBitmap, tries = 5)
        game.wait(game.dialogWaitDelay)
    }

    /**
     * OCR a text region on a Lessons card, relative to the matched cost-pill anchor.
     *
     * @param sourceBitmap The Lessons-screen screenshot.
     * @param anchor The point the crop is offset from: a card's `grandlive_lesson_cost` pill center, or the screen origin for the purchase dialog.
     * @param crop The crop offsets from [LESSON_CARD_CROPS].
     * @param debugName Debug label for the OCR crop dump.
     * @return The trimmed OCR text (empty when nothing was read).
     */
    private fun ocrCard(sourceBitmap: Bitmap, anchor: Point, crop: TokenCrop, debugName: String): String {
        val (x, y, width, height) = anchoredRect(anchor, crop).toList()
        return game.imageUtils.performOCROnRegion(sourceBitmap, x, y, width, height, useThreshold = false, scale = 1.5, ocrEngine = "mlkit", debugName = debugName).trim()
    }

    /**
     * Converts a crop measured at 1080p from an anchor into a screen region.
     *
     * @param anchor The matched anchor center.
     * @param crop The crop offsets from the anchor.
     * @return The region as x, y, width and height.
     */
    private fun anchoredRect(anchor: Point, crop: TokenCrop): IntArray =
        intArrayOf(game.imageUtils.relX(anchor.x, crop.dx), game.imageUtils.relY(anchor.y, crop.dy), game.imageUtils.relWidth(crop.width), game.imageUtils.relHeight(crop.height))

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
     * effect category) is currently locked, so tokens can accumulate for it to become learnable next round. Only asked when holding is
     * allowed: Hype is already maxed (no Song needed for it) and the career is not yet in its final stretch.
     *
     * @param scanned The cards on the Lessons screen this scan.
     * @return True to skip buying and save tokens this turn.
     */
    private fun shouldWaitForLockedLesson(scanned: List<ScannedLesson>): Boolean {
        return scanned.any { !it.option.purchasable && isSoughtAfter(it.option, lessonEffectPriority, lessonSongPriority) }
    }

    /**
     * Read the projected new energy the Lessons purchase confirmation prints as "<new> / <cap>" above its energy bar. Used to cancel an
     * Energy purchase that would cap out. Reads the dialog rather than the trainee's energy, which is unknown when the bot starts on a concert screen.
     *
     * @param sourceBitmap The confirmation-dialog screenshot.
     * @param dialogHeader The center of the dialog's header, which the readout is measured from.
     * @return The projected new energy and the cap it was read against, or null when the readout could not be parsed.
     */
    private fun readDialogProjectedEnergy(sourceBitmap: Bitmap, dialogHeader: Point): Pair<Int, Int>? {
        val (x, y, width, height) = anchoredRect(dialogHeader, LESSON_DIALOG_ENERGY_CROP).toList()
        val text =
            game.imageUtils.performOCROnRegion(
                sourceBitmap,
                x,
                y,
                width,
                height,
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

        // The Concert tap and its Start confirmation must land together, so a pause waits until both are done.
        val bConcertStarted =
            BotHold.deferPause {
                if (!ButtonGrandLiveConcert.click(game.imageUtils, tries = 10)) return@deferPause false
                game.wait(game.dialogWaitDelay)
                // Confirmation dialog -> Start.
                ButtonGrandLiveStart.click(game.imageUtils, tries = 10)
                true
            }
        if (!bConcertStarted) {
            MessageLog.w(TAG, "[GRAND_LIVE] Could not tap the Concert button.")
            return
        }
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
            if (Checkbox.isTicked(game.imageUtils, confirmSource, checkbox)) {
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
