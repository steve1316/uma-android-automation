/**
 * Defines Dialog components.
 *
 * A dialog is just any pop-up window on the screen. These typically have one or two buttons.
 *
 * Adding a New Dialog:
 *
 * After creating your DialogInterface object, you must add this object to the [DialogObjects.items] list. Please add it in alphabetical order for readability.
 *
 * Example usage:
 *
 * // Call the centralized handler through the campaign or game. val result: DialogHandlerResult = campaign.handleDialogs()
 *
 * // Or pass arguments via the map. campaign.handleDialogs(args = mapOf("overrideIgnoreConsecutiveRaceWarning" to true))
 *
 * Example usage:
 * ```
 * // Call the centralized handler through the campaign or game.
 * val result: DialogHandlerResult = campaign.handleDialogs()
 *
 * // Or pass arguments via the map.
 * campaign.handleDialogs(args = mapOf("overrideIgnoreConsecutiveRaceWarning" to true))
 *
 * // Example of how logic is implemented within a DialogHandler:
 * open fun handleDialogs(dialog: DialogInterface? = null, args: Map<String, Any> = mapOf()): DialogHandlerResult {
 *     val dialog = dialog ?: DialogUtils.getDialog(game.imageUtils) ?: return DialogHandlerResult.NoDialogDetected
 *     when (dialog.name) {
 *         "open_soon" -> {
 *             game.notificationMessage = "open_soon"
 *             MessageLog.i(TAG, "\n[DIALOG] Open Soon!")
 *             dialog.close(game.imageUtils)
 *         }
 *         "continue_career" -> {
 *             dialog.close(imageUtils=game.imageUtils)
 *             MessageLog.i(TAG, "\n[DIALOG] Continue Career")
 *         }
 *         else -> {
 *             MessageLog.i(TAG, "\n[DIALOG] ${dialog.name}")
 *             dialog.close(imageUtils=game.imageUtils)
 *             return DialogHandlerResult.Unhandled(dialog)
 *         }
 *     }
 *     return DialogHandlerResult.Handled(dialog)
 * }
 * ```
 */

package com.steve1316.uma_android_automation.components

import android.graphics.Bitmap
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.TextUtils
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.components.BaseComponentInterface
import com.steve1316.uma_android_automation.types.BoundingBox
import com.steve1316.uma_android_automation.utils.CustomImageUtils
import org.opencv.core.Point

/** Define the key components and functions for interacting with Dialogs. */
interface DialogInterface {
    /** A unique name used to identify this dialog. */
    val name: String

    /** The on-screen title of the dialog. Multiple dialogs may have the same title. */
    val title: String

    /** List of all the button components within the dialog. If there is a button used to close the dialog, then it MUST be the first entry in this list. */
    val buttons: List<BaseComponentInterface>

    /** The button used primarily to close the dialog. If not specified, the first button in the buttons list will be used. */
    val closeButton: BaseComponentInterface?

    /** The button typically used to accept the dialog. If there is only one button in the dialog, then this may be set to that button. */
    val okButton: BaseComponentInterface?

    /**
     * Close the dialog by clicking the close button.
     *
     * If no close button is specified, then the first button in the [buttons] list is treated as the close button and is clicked.
     *
     * @param imageUtils A reference to a CustomImageUtils instance.
     * @param tries The number of attempts when searching for the button.
     * @return True if the close button was found and clicked.
     */
    fun close(imageUtils: CustomImageUtils, tries: Int = 1): Boolean {
        if (closeButton == null) {
            return buttons.getOrNull(0)?.click(imageUtils = imageUtils, tries = tries) ?: false
        }
        return closeButton?.click(imageUtils = imageUtils, tries = tries) ?: false
    }

    /**
     * Close the dialog by clicking the OK button.
     *
     * If no OK button is defined for this dialog, then the [close] function is called instead.
     *
     * @param imageUtils A reference to a CustomImageUtils instance.
     * @param tries The number of attempts when searching for the button.
     * @return True if the OK button was found and clicked.
     */
    fun ok(imageUtils: CustomImageUtils, tries: Int = 1): Boolean {
        if (okButton == null) {
            return if (buttons.size == 1) {
                close(imageUtils = imageUtils, tries = tries)
            } else {
                false
            }
        }
        return okButton?.click(imageUtils = imageUtils, tries = tries) ?: false
    }
}

/** Utility class for detecting and handling dialogs in the game. */
object DialogUtils {
    private val TAG: String = "[${MainActivity.loggerTag}]DialogUtils"

    /** List of templates used to detect the title bar gradient of a dialog. */
    private val titleGradientTemplates: List<String> =
        listOf(
            "components/dialog/dialog_title_gradient_0",
            "components/dialog/dialog_title_gradient_1",
        )

    /** List of dialogs that are considered dangerous because they may involve real-world purchases. Detection of these dialogs will cause the bot to stop immediately. */
    private val dangerousDialogs: List<DialogInterface> =
        listOf(
            DialogAgeConfirmation,
            DialogPurchaseCarats,
        )

    /**
     * Check if any dialog is currently displayed on the screen.
     *
     * @param imageUtils The CustomImageUtils instance used to find the dialog.
     * @param tries The number of times to attempt to find the image. Ignored when a bitmap is passed.
     * @param sourceBitmap Optional screenshot to search instead of capturing new ones.
     * @return True if a dialog was detected, false otherwise.
     */
    fun check(imageUtils: CustomImageUtils, tries: Int = 1, sourceBitmap: Bitmap? = null): Boolean {
        var loc: Point? = null
        for (template in titleGradientTemplates) {
            // Search for the dialog title gradient templates.
            loc =
                if (sourceBitmap != null) {
                    imageUtils.findImageWithBitmap(template, sourceBitmap = sourceBitmap, suppressError = true)
                } else {
                    imageUtils.findImage(template, tries = tries, suppressError = true).first
                }
            if (loc != null) {
                break
            }
        }
        return loc != null
    }

    /**
     * Get the title bar text of any dialog currently on the screen.
     *
     * @param imageUtils The CustomImageUtils instance used to find the dialog.
     * @param bitmap Optional bitmap to use when looking for a dialog. If not specified, a screenshot will be taken and used instead.
     * @return The text of the dialog's title bar if one was found, else NULL.
     */
    fun getTitle(imageUtils: CustomImageUtils, bitmap: Bitmap? = null): String? {
        val bitmap: Bitmap = bitmap ?: imageUtils.getSourceBitmap()
        var templateBitmap: Bitmap? = null
        var titleLocation: Point? = null
        for (template in titleGradientTemplates) {
            // Find the location of the title gradient in the given bitmap.
            titleLocation =
                imageUtils.findImageWithBitmap(
                    template,
                    sourceBitmap = bitmap,
                    suppressError = true,
                )
            if (titleLocation != null) {
                // Retrieve the template bitmap to calculate the bounding box.
                templateBitmap = imageUtils.getTemplateBitmap(template.substringAfterLast('/'), "images/" + template.substringBeforeLast('/'))
                break
            }
        }

        // Return null if the title location could not be determined.
        if (titleLocation == null) {
            return null
        }

        // Return null if we failed to find the template bitmap for calculations.
        if (templateBitmap == null) {
            return null
        }

        // Calculate the top-left coordinates and the bounding box of the title.
        val x = titleLocation.x - (templateBitmap.width / 2.0)
        val y = titleLocation.y - (templateBitmap.height / 2.0)

        val bbox =
            BoundingBox(
                imageUtils.relX(x, 0),
                imageUtils.relY(y, 0),
                imageUtils.relWidth((SharedData.displayWidth - (x * 2)).toInt()),
                imageUtils.relHeight(templateBitmap.height),
            )

        // Perform OCR on the identified title region.
        val text: String =
            imageUtils.performOCROnRegion(
                bitmap,
                bbox.x,
                bbox.y,
                bbox.w,
                bbox.h,
                useThreshold = true,
                useGrayscale = true,
                scale = 1.0,
                ocrEngine = "mlkit",
                debugName = "dialogTitle",
            )

        if (text == "") {
            return null
        }

        // Perform fuzzy matching against known dialog titles.
        val match: String? = TextUtils.matchStringInList(text, DialogObjects.items.map { it.title })

        // Attempt to find known titles with different fonts if detection fails.
        if (match == null) {
            val croppedBitmap: Bitmap? =
                imageUtils.createSafeBitmap(
                    bitmap,
                    bbox.x,
                    bbox.y,
                    bbox.w,
                    bbox.h,
                    "Dialog::getTitle cropped",
                )
            if (croppedBitmap == null) {
                return null
            }

            // Check for the "Trophy Won" dialog as a special case.
            if (LabelTrophyWonDialogTitle.check(imageUtils, sourceBitmap = croppedBitmap)) {
                return DialogTrophyWon.title
            }

            MessageLog.e(TAG, "[ERROR] getTitle:: Failed to match any dialogs to the extracted title: $text")
            return null
        }

        return match
    }

    /**
     * Stop the bot if the specified dialog is in the hardcoded [dangerousDialogs] list.
     *
     * Some dialogs are dangerous because they could lead to real-world purchases. This function throws an InterruptedException to immediately stop the bot if a dangerous dialog is detected.
     *
     * @param dialog The dialog to check.
     */
    private fun handleDangerousDialogs(dialog: DialogInterface) {
        if (dialog in dangerousDialogs) {
            throw InterruptedException("Stopping bot due to a dangerous dialog: ${dialog.name}")
        }
    }

    /**
     * Detect and return the [DialogInterface] currently visible on the screen.
     *
     * @param imageUtils The CustomImageUtils instance used to find the dialog.
     * @param bitmap Optional bitmap to use when looking for a dialog. If not specified, a screenshot will be taken and used instead.
     * @return The [DialogInterface] if one was found, else NULL.
     */
    fun getDialog(imageUtils: CustomImageUtils, bitmap: Bitmap? = null): DialogInterface? {
        val bitmap: Bitmap = bitmap ?: imageUtils.getSourceBitmap()
        val title: String = getTitle(imageUtils, bitmap) ?: return null

        // Filter the list of known dialogs for any matches by title.
        val matches: List<DialogInterface> = DialogObjects.items.filter { it.title == title }

        if (matches.isEmpty()) {
            // Throw an exception if getTitle returns a title that is not in our known list.
            throw IllegalStateException("getTitle returned an invalid title: $title")
        }

        if (matches.size == 1) {
            // Handle dangerous dialogs before returning the single match.
            handleDangerousDialogs(matches[0])
            return matches[0]
        }

        // Handle duplicates by checking if the dialog's buttons match what is on screen.
        if (matches.size > 1) {
            for (dialog in matches) {
                if (dialog.buttons.all { button -> button.check(imageUtils, sourceBitmap = bitmap) }) {
                    handleDangerousDialogs(dialog)
                    return dialog
                }
            }
        }

        MessageLog.e(TAG, "[ERROR] getDialog:: Multiple dialogs match the detected title ($title). However, we failed to match any of them to the buttons in the dialog on the screen.")
        return null
    }
}

/** Store the list of all dialog objects and a mapping of them for easy access. */
object DialogObjects {
    /** List of all [DialogInterface] objects. */
    val items: List<DialogInterface> =
        listOf(
            DialogAccountLink,
            DialogAgeConfirmation,
            DialogAgendaDetails,
            DialogAutoFill,
            DialogAutoSelect,
            DialogAllRewardsEarned,
            DialogBonusUmamusumeDetails,
            DialogBorrowCard,
            DialogBorrowCardConfirmation,
            DialogCareer,
            DialogCareerComplete,
            DialogCareerEventDetails,
            DialogCareerProfile,
            DialogChoices,
            DialogChooseRecreationPartner,
            DialogCompleteCareer,
            DialogConcertSkipConfirmation,
            DialogConfirmAutoSelect,
            DialogConfirmExchange,
            DialogConfirmRestoreRP,
            DialogConfirmUse,
            DialogConnectionError,
            DialogConsecutiveRaceWarning,
            DialogContinueCareer,
            DialogDailySale,
            DialogDateChanged,
            DialogDisplaySettings,
            DialogDownloadError,
            DialogEpithet,
            DialogEpithets,
            DialogExchangeComplete,
            DialogExternalLink,
            DialogFans,
            DialogFeaturedCards,
            DialogFinalConfirmation,
            DialogFollowTrainer,
            DialogGiveUp,
            DialogGoalNotReached,
            DialogGoals,
            DialogHelpAndGlossary,
            DialogInfirmary,
            DialogInsufficientFans,
            DialogInsufficientGoalRaceResultPts,
            DialogItemsSelected,
            DialogLog,
            DialogMenu,
            DialogMoodEffect,
            DialogMyAgendas,
            DialogNoRetries,
            DialogNotices,
            DialogOpenSoon,
            DialogOptions,
            DialogOverwrite,
            DialogPerks,
            DialogPlacing,
            DialogPresents,
            DialogPurchaseAlarmClock,
            DialogPurchaseCarats,
            DialogPurchaseDailyRaceTicket,
            DialogRaceDetails,
            DialogRacePlayback,
            DialogRaceRecommendations,
            DialogRecreation,
            DialogRegistrationComplete,
            DialogRequestFulfilled,
            DialogRest,
            DialogRestAndRecreation,
            DialogRewardsCollected,
            DialogRunners,
            DialogScheduleRace,
            DialogScheduleCancellation,
            DialogScheduledRaceAvailable,
            DialogScheduledRaces,
            DialogScheduleSettings,
            DialogSessionError,
            DialogShop,
            DialogSkillDetails,
            DialogSkillListConfirmation,
            DialogSkillListConfirmExit,
            DialogSkillsLearned,
            DialogSongAcquired,
            DialogSparkDetails,
            DialogSparks,
            DialogSpecialMissions,
            DialogStrategy,
            DialogStoryUnlocked,
            DialogTeamInfo,
            DialogTrophyWon,
            DialogTrainingItems,
            DialogTryAgain,
            DialogUmamusumeClass,
            DialogUmamusumeDetails,
            DialogUnityCupAvailable,
            DialogUnityCupConfirmation,
            DialogUnlockRequirements,
            DialogUnmetRequirements,
            DialogViewStory,
        )

    /** Mapping of each [DialogInterface]'s name to the interface object. */
    val map: Map<String, DialogInterface> = items.associateBy { it.name }
}

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Dialog Objects

/**
 * Builds a [DialogInterface] from its identifying [name], on-screen [title], and component buttons.
 *
 * @param name Unique identifier for this dialog.
 * @param title On-screen title text used to recognize the dialog.
 * @param buttons All button components within the dialog. If a close button is used, it must be the first entry.
 * @param closeButton Button used to close the dialog, or null to fall back to the first entry in [buttons].
 * @param okButton Button typically used to accept the dialog, or null if there is none.
 * @return A [DialogInterface] exposing the supplied metadata.
 */
private fun dialog(
    name: String,
    title: String,
    buttons: List<BaseComponentInterface> = emptyList(),
    closeButton: BaseComponentInterface? = null,
    okButton: BaseComponentInterface? = null,
): DialogInterface =
    object : DialogInterface {
        override val name: String = name
        override val title: String = title
        override val buttons: List<BaseComponentInterface> = buttons
        override val closeButton: BaseComponentInterface? = closeButton
        override val okButton: BaseComponentInterface? = okButton
    }

// Dialogs are declared below with the dialog() factory. The few that override close()/ok() to
// choose among several buttons at runtime remain full object declarations instead.

/** Title Screen.
 *
 * This dialog also has an "Account Link" button, but we never want to allow the bot to click that, so we won't add it.
 */
val DialogAccountLink = dialog("account_link", "Account Link", buttons = listOf(ButtonLater))

/** Anywhere (ALWAYS THROW ERROR).
 *
 * This dialog has two different OK buttons: ButtonEnter and ButtonOk.
 * However since we never want to handle those buttons, we won't even add them in here.
 */
val DialogAgeConfirmation = dialog("age_confirmation", "Age Confirmation", buttons = listOf(ButtonCancel))

/** Career */
val DialogAgendaDetails = dialog("agenda_details", "Agenda Details", buttons = listOf(ButtonClose))

/** Career (Unity Cup) */
val DialogAutoFill = dialog("auto_fill", "Auto-Fill", buttons = listOf(ButtonClose, ButtonEditTeam), okButton = ButtonEditTeam)

/** Career Selection */
val DialogAutoSelect = dialog("auto_select", "Auto-Select", buttons = listOf(ButtonCancel, ButtonOk, Checkbox), okButton = ButtonOk)

/** Career (event only) */
val DialogAllRewardsEarned = dialog("all_rewards_earned", "ALL REWARDS EARNED!", buttons = listOf(ButtonClose))

/** Career -> Career Profile dialog. */
val DialogBonusUmamusumeDetails = dialog("bonus_umamusume_details", "Bonus Umamusume Details", buttons = listOf(ButtonClose))

/** Career Selection */
val DialogBorrowCard = dialog("borrow_card", "Borrow Card", buttons = listOf(ButtonClose))

/** Career Selection */
val DialogBorrowCardConfirmation = dialog("borrow_card_confirmation", "Confirmation", buttons = listOf(ButtonClose, ButtonOk), okButton = ButtonOk)

/** Career */
val DialogCareer = dialog("career", "Career", buttons = listOf(ButtonClose))

/** Career */
object DialogCareerComplete : DialogInterface {
    override val name: String = "career_complete"
    override val title: String = "Career Complete"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonEditTeam
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonToHome,
            ButtonClose,
            ButtonEditTeam,
        )

    // This dialog is unique in that there are two versions of it.
    // The dialog's close button can be one of two different buttons:
    // "To Home" and "Close"
    override fun close(imageUtils: CustomImageUtils, tries: Int): Boolean {
        if (ButtonToHome.click(imageUtils = imageUtils, tries = tries)) {
            return true
        }

        return ButtonClose.click(imageUtils = imageUtils, tries = tries)
    }
}

/** Career (training event effects). */
val DialogChoices = dialog("choices", "Choices", buttons = listOf(ButtonClose))

/** Career */
val DialogChooseRecreationPartner = dialog("choose_recreation_partner", "Choose Recreation Partner", buttons = listOf(ButtonCancel), closeButton = ButtonCancel)

/** Career (yes this is different from above...). */
val DialogCompleteCareer = dialog("complete_career", "Complete Career", buttons = listOf(ButtonCancel, ButtonFinish), okButton = ButtonFinish)

/** Career */
val DialogConcertSkipConfirmation = dialog("concert_skip_confirmation", "Confirmation", buttons = listOf(ButtonCancel, ButtonOk, Checkbox), okButton = ButtonOk)

/** Career Selection */
val DialogConfirmAutoSelect = dialog("confirm_auto_select", "Confirm Auto-Select", buttons = listOf(ButtonCancel, ButtonOk, Checkbox), okButton = ButtonOk)

/** Main Screen */
val DialogConfirmExchange = dialog("confirm_exchange", "Confirm Exchange", buttons = listOf(ButtonClose))

/** Career (Trackblazer) */
val DialogConfirmUse = dialog("confirm_use", "Confirm Use", buttons = listOf(ButtonCancel, ButtonUseTrainingItems), okButton = ButtonUseTrainingItems)

/** Anywhere */
object DialogConnectionError : DialogInterface {
    override val name: String = "connection_error"
    override val title: String = "Connection Error"
    override val closeButton = null
    override val okButton = ButtonRetry
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonTitleScreen,
            ButtonRetry,
        )

    // This dialog is unique in that there are two versions of it.
    // The dialog can have either a single button ("Title Screen") or
    // two buttons ("Title Screen" and "Retry").
    override fun ok(imageUtils: CustomImageUtils, tries: Int): Boolean {
        if (ButtonRetry.click(imageUtils = imageUtils, tries = tries)) {
            return true
        }

        return ButtonTitleScreen.click(imageUtils = imageUtils, tries = tries)
    }
}

/** Career */
val DialogConsecutiveRaceWarning = dialog("consecutive_race_warning", "Warning", buttons = listOf(ButtonCancel, ButtonOk), okButton = ButtonOk)

/** Main Screen */
val DialogContinueCareer = dialog("continue_career", "Continue Career", buttons = listOf(ButtonCancel, ButtonResume), okButton = ButtonResume)

/** Team Trials */
val DialogConfirmRestoreRP = dialog("confirm_restore_rp", "Confirm", buttons = listOf(ButtonNo, ButtonRestore), okButton = ButtonRestore)

/** Team Trials, Special Events, Daily Races. */
val DialogDailySale = dialog("daily_sale", "Daily Sale", buttons = listOf(ButtonCancel, ButtonShop), okButton = ButtonShop)

/** Anywhere */
val DialogDateChanged = dialog("date_changed", "Date Changed", buttons = listOf(ButtonOk))

/** Anywhere */
val DialogDisplaySettings = dialog("display_settings", "Display Settings", buttons = listOf(ButtonCancel, ButtonOk), okButton = ButtonOk)

/** Title Screen (only?) */
val DialogDownloadError = dialog("download_error", "Download Error", buttons = listOf(ButtonTitleScreen, ButtonRetry), okButton = ButtonRetry)

/** Career End */
val DialogEpithet = dialog("epithet", "Epithet", buttons = listOf(ButtonConfirmExclamation, Checkbox))

// This is the dialog opened from the Epithets button in DialogMenu.

/** Career DialogMenu -> Epithets button. */
val DialogEpithets = dialog("epithets", "Epithets", buttons = listOf(ButtonClose))

/** Career (Trackblazer) */
val DialogExchangeComplete = dialog("exchange_complete", "Exchange Complete", buttons = listOf(ButtonClose, ButtonConfirmUse), closeButton = ButtonClose, okButton = ButtonConfirmUse)

/** Main Screen */
val DialogExternalLink = dialog("external_link", "External Link", buttons = listOf(ButtonCancel, ButtonOk), okButton = ButtonOk)

/** Career DialogGoals */
val DialogFans = dialog("fans", "Fans", buttons = listOf(ButtonClose))

/** Career */
val DialogFeaturedCards = dialog("featured_cards", "Featured Cards", buttons = listOf(ButtonClose))

/** Career Selection */
val DialogFinalConfirmation = dialog("final_confirmation", "Final Confirmation", buttons = listOf(ButtonCancel, ButtonStartCareer), okButton = ButtonStartCareer)

/** Career */
val DialogFollowTrainer = dialog("follow_trainer", "Follow Trainer", buttons = listOf(ButtonCancel, ButtonFollow), okButton = ButtonFollow)

/** Career */
val DialogGiveUp = dialog("give_up", "Give Up", buttons = listOf(ButtonCancel, ButtonGiveUp), okButton = ButtonGiveUp)

/** Career */
val DialogGoalNotReached = dialog("goal_not_reached", "Goal Not Reached", buttons = listOf(ButtonCancel, ButtonRace), okButton = ButtonRace)

/** Career */
val DialogGoals = dialog("goals", "Goals", buttons = listOf(ButtonClose))

/** Anywhere (from options dialog). */
val DialogHelpAndGlossary = dialog("help_and_glossary", "Help & Glossary", buttons = listOf(ButtonClose))

/** Career */
val DialogInfirmary = dialog("infirmary", "Infirmary", buttons = listOf(ButtonCancel, ButtonOk, Checkbox), okButton = ButtonOk)

/** Career */
val DialogInsufficientFans = dialog("insufficient_fans", "Insufficient Fans", buttons = listOf(ButtonCancel, ButtonRace), okButton = ButtonRace)

/** Career (Trackblazer) */
val DialogInsufficientGoalRaceResultPts =
    dialog("insufficient_goal_race_result_pts", "Insufficient Goal Race Result Pts", buttons = listOf(ButtonCancel, ButtonRace), closeButton = ButtonCancel, okButton = ButtonRace)

/** Team Trials, Special Events, Daily Races. */
val DialogItemsSelected = dialog("items_selected", "Items Selected", buttons = listOf(ButtonCancel, ButtonRaceExclamationShiftedUp), okButton = ButtonRaceExclamationShiftedUp)

/** Career */
val DialogLog = dialog("log", "Log", buttons = listOf(ButtonClose))

/** Career */
val DialogMenu = dialog("menu", "Menu", buttons = listOf(ButtonClose, ButtonOptions, ButtonSaveAndExit, ButtonGiveUp))

/** Career */
val DialogMoodEffect = dialog("mood_effect", "Mood Effect", buttons = listOf(ButtonClose))

/** Career */
val DialogMyAgendas = dialog("my_agendas", "My Agendas", buttons = listOf(ButtonClose))

/** Career */
val DialogNoRetries = dialog("no_retries", "No Retries", buttons = listOf(ButtonEndCareer))

/** Main Screen */
val DialogNotices = dialog("notices", "Notices", buttons = listOf(ButtonClose))

/** Shop (only when clicking inactive daily sales button). */
val DialogOpenSoon = dialog("open_soon", "Open Soon!", buttons = listOf(ButtonClose))

/** Card details */
val DialogCareerEventDetails = dialog("career_event_details", "Career Event Details", buttons = listOf(ButtonClose))

/** Career */
val DialogCareerProfile = dialog("career_profile", "Career Profile", buttons = listOf(ButtonClose))

/** Anywhere */
val DialogOptions = dialog("options", "Options", buttons = listOf(ButtonCancel, ButtonSave), okButton = ButtonSave)

/** Career -> Agenda */
val DialogOverwrite = dialog("overwrite", "Overwrite", okButton = ButtonOverwrite)

/** Career -> Career Profile dialog. */
val DialogPerks = dialog("perks", "Perks", buttons = listOf(ButtonClose))

/** Career -> DialogTryAgain */
val DialogPlacing = dialog("placing", "Placing", buttons = listOf(ButtonClose))

/** Main Screen (I think?). */
val DialogPresents = dialog("presents", "Presents", buttons = listOf(ButtonClose, ButtonCollectAll), okButton = ButtonCollectAll)

/**
 * Career.
 *
 * If the player has 0 carats, then this dialog shows a "Purchase Carats" button instead of ButtonOk. We don't even want to humor this as an option, so that button will not be added.
 *
 * The other, less scary option is it will have a ButtonOk button which will attempt to buy a clock using carats. Again, we don't want to even give the bot a chance to do this, so we just won't even
 * add that button in here.
 */
val DialogPurchaseAlarmClock = dialog("purchase_alarm_clock", "Purchase Alarm Clock", buttons = listOf(ButtonCancel))

/** Anywhere (ALWAYS THROW ERROR). */
val DialogPurchaseCarats = dialog("purchase_carats", "Purchase Carats", buttons = listOf(ButtonClose))

/** Daily Races */
val DialogPurchaseDailyRaceTicket = dialog("purchase_daily_race_ticket", "Purchase Daily Race Ticket", buttons = listOf(ButtonCancel, ButtonOk), okButton = ButtonOk)

/** Daily Races, Special Events, and Career. */
object DialogRaceDetails : DialogInterface {
    override val name: String = "race_details"
    override val title: String = "Race Details"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonRace,
            ButtonRaceExclamation,
        )

    // This dialog is unique in that there are three variants of it.
    // The normal race details dialog has a "Race!" button whereas
    // the career version just has a "Race" button. There is also
    // an informational version that only has a "Close" button.
    override fun ok(imageUtils: CustomImageUtils, tries: Int): Boolean {
        if (ButtonRaceExclamation.click(imageUtils = imageUtils, tries = tries)) {
            return true
        }

        if (ButtonRace.click(imageUtils = imageUtils, tries = tries)) {
            return true
        }

        return ButtonClose.click(imageUtils = imageUtils, tries = tries)
    }
}

/** Career */
val DialogRacePlayback = dialog("race_playback", "Race Playback", buttons = listOf(ButtonCancel, ButtonOk, Checkbox, RadioLandscape, RadioPortrait), okButton = ButtonOk)

/** Career */
val DialogRaceRecommendations =
    dialog(
        "race_recommendations",
        "Race Recommendations",
        buttons = listOf(ButtonConfirm, ButtonRaceRecommendationsCenterStage, ButtonRaceRecommendationsPathToFame, ButtonRaceRecommendationsForgeYourOwnPath, Checkbox),
        okButton = ButtonConfirm,
    )

/** Career */
val DialogRecreation = dialog("recreation", "Recreation", buttons = listOf(ButtonCancel, ButtonOk, Checkbox), okButton = ButtonOk)

/** Anywhere */
val DialogRegistrationComplete = dialog("registration_complete", "Registration Complete", buttons = listOf(ButtonClose))

/** Transfer Requests */
val DialogRequestFulfilled = dialog("request_fulfilled", "REQUEST FULFILLED", buttons = listOf(ButtonClose))

/** Career */
val DialogRest = dialog("rest", "Rest", buttons = listOf(ButtonCancel, ButtonOk, Checkbox), okButton = ButtonOk)

/** Career */
val DialogRestAndRecreation = dialog("rest_and_recreation", "Rest & Recreation", buttons = listOf(ButtonCancel, ButtonOk), okButton = ButtonOk)

/** Main Screen, Special Events. */
val DialogRewardsCollected = dialog("rewards_collected", "Rewards Collected", buttons = listOf(ButtonClose))

/** Career -> Race screens. */
val DialogRunners = dialog("runners", "Runners", buttons = listOf(ButtonClose))

/** Career -> Agenda */
val DialogScheduleRace = dialog("schedule_race", "Schedule Race")

/** Career -> Agenda */
val DialogScheduleCancellation = dialog("schedule_cancellation", "Schedule Cancellation")

/** Career */
val DialogScheduledRaceAvailable = dialog("scheduled_race_available", "Scheduled Race Available", buttons = listOf(ButtonClose, ButtonRace), okButton = ButtonRace)

/** Career */
val DialogScheduledRaces = dialog("scheduled_races", "Scheduled Races", buttons = listOf(ButtonClose))

/** Career */
val DialogScheduleSettings = dialog("schedule_settings", "Schedule Settings", buttons = listOf(ButtonCancel, ButtonSaveSchedule), okButton = ButtonSaveSchedule)

/** Anywhere */
val DialogSessionError = dialog("session_error", "Session Error", buttons = listOf(ButtonTitleScreen))

/** Career (Trackblazer) */
val DialogShop = dialog("shop", "Shop", buttons = listOf(ButtonClose, ButtonShop), okButton = ButtonShop)

/** Anywhere */
val DialogSkillDetails = dialog("skill_details", "Skill Details", buttons = listOf(ButtonClose))

/** Career */
val DialogSkillListConfirmation = dialog("skill_list_confirmation", "Confirmation", buttons = listOf(ButtonCancel, ButtonLearn), okButton = ButtonLearn)

/** Career */
val DialogSkillListConfirmExit = dialog("skill_list_confirm_exit", "Confirm", buttons = listOf(ButtonCancel, ButtonOk), okButton = ButtonOk)

/** Career */
val DialogSkillsLearned = dialog("skills_learned", "Skills Learned", buttons = listOf(ButtonClose))

/** Career */
val DialogSongAcquired = dialog("song_acquired", "Song Acquired", buttons = listOf(ButtonClose))

/** Career (legacy uma details). */
val DialogSparkDetails = dialog("spark_details", "Spark Details", buttons = listOf(ButtonClose))

/** Career -> Career Profile dialog. */
val DialogSparks = dialog("sparks", "Sparks", buttons = listOf(ButtonClose))

/** Main Screen, Special Events. */
val DialogSpecialMissions = dialog("special_missions", "Special Missions", buttons = listOf(ButtonOk, ButtonCollectAll), okButton = ButtonCollectAll)

/** Race Screen */
val DialogStrategy =
    dialog(
        "strategy",
        "Strategy",
        buttons = listOf(ButtonCancel, ButtonConfirm, ButtonRaceStrategyFront, ButtonRaceStrategyPace, ButtonRaceStrategyLate, ButtonRaceStrategyEnd),
        okButton = ButtonConfirm,
    )

/** Main Screen, end of career. */
val DialogStoryUnlocked = dialog("story_unlocked", "Story Unlocked", buttons = listOf(ButtonToHome))

/** Career (Unity Cup) */
val DialogTeamInfo = dialog("team_info", "Team Info", buttons = listOf(ButtonClose, ButtonEditTeam), okButton = ButtonEditTeam)

/** Career */
val DialogTrophyWon = dialog("trophy_won", "TROPHY WON!", buttons = listOf(ButtonClose))

/** Career */
val DialogTryAgain = dialog("try_again", "Try Again", buttons = listOf(ButtonCancel, ButtonTryAgain), okButton = ButtonTryAgain)

/** Career */
val DialogUmamusumeClass = dialog("umamusume_class", "Umamusume Class", buttons = listOf(ButtonClose))

/** Career */
val DialogUmamusumeDetails = dialog("umamusume_details", "Umamusume Details", buttons = listOf(ButtonClose))

/** Career (Unity Cup) */
val DialogUnityCupAvailable = dialog("unity_cup_available", "Unity Cup Available", buttons = listOf(ButtonClose))

/** Career (Unity Cup) */
val DialogUnityCupConfirmation = dialog("unity_cup_confirmation", "Confirmation", buttons = listOf(ButtonCancel, ButtonBeginShowdown), okButton = ButtonBeginShowdown)

/** Race Screen */
val DialogUnlockRequirements = dialog("unlock_requirements", "Unlock Requirements", buttons = listOf(ButtonClose))

val DialogUnmetRequirements = dialog("unmet_requirements", "Unmet Requirements", buttons = listOf(ButtonCancel, ButtonRace), okButton = ButtonRace)

/** Main Screen, end of career. */
val DialogViewStory = dialog("view_story", "View Story", buttons = listOf(ButtonCancel, ButtonOk, RadioLandscape, RadioPortrait, RadioVoiceOff), okButton = ButtonOk)

/** Trackblazer */
val DialogTrainingItems = dialog("training_items", "Training Items", buttons = listOf(ButtonClose, ButtonConfirmUse), closeButton = ButtonClose, okButton = ButtonConfirmUse)
