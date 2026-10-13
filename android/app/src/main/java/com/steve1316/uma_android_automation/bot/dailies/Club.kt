package com.steve1316.uma_android_automation.bot.dailies

import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.components.ButtonCancel
import com.steve1316.uma_android_automation.components.ButtonClose
import com.steve1316.uma_android_automation.components.ButtonClubItemRequest
import com.steve1316.uma_android_automation.components.ButtonClubRequestList
import com.steve1316.uma_android_automation.components.ButtonConfirm
import com.steve1316.uma_android_automation.components.ButtonDonateToAll
import com.steve1316.uma_android_automation.components.ButtonDonateToAllConfirm
import com.steve1316.uma_android_automation.components.ButtonHomeClub
import com.steve1316.uma_android_automation.components.ButtonItemRequestSashTab
import com.steve1316.uma_android_automation.components.ButtonItemRequestShoesTab
import com.steve1316.uma_android_automation.components.LabelClubMenu
import com.steve1316.uma_android_automation.components.LabelItemRequestExpired
import com.steve1316.uma_android_automation.components.LabelItemRequestHeld
import com.steve1316.uma_android_automation.components.LabelItemRequestSelectPrompt
import com.steve1316.uma_android_automation.utils.createDialogScrollList
import org.opencv.core.Point

// Offsets below are measured on the 1080x1920 screenshots, from the centre of a row's "Held" pill.

/** The item title box (x, y, w, h). It starts well left of the title, since ML Kit drops a first letter that sits right on the crop edge. */
private val ROW_TITLE = intArrayOf(-90, -79, 720, 45)

/** The spot to tap to pick the row. */
private const val ROW_TAP_DX = 130
private const val ROW_TAP_DY = -57

/**
 * Collects an expired Club item request, issues a new one, and donates to everyone else's requests.
 *
 * @property game The bot's [Game] instance.
 * @property requestEnabled Whether to issue a new item request.
 * @property item The item to request, such as "Dirt Racing Shoes".
 * @property donateEnabled Whether to Donate to All.
 */
class Club(private val game: Game, private val requestEnabled: Boolean, private val item: String, private val donateEnabled: Boolean) : DailyPart {
    companion object {
        private val TAG: String = "[${MainActivity.loggerTag}]Club"
    }

    override val name: String = "Club"
    override val enabled: Boolean = requestEnabled || donateEnabled

    override fun start(): PartOutcome {
        openClub(game.waitForAny(ButtonHomeClub)?.second ?: throw DailyStepFailed("Club button not found"))

        val notes = mutableListOf<String>()
        if (requestEnabled) notes.add(request())
        if (donateEnabled) notes.add(donate())
        return PartOutcome.Done(notes.joinToString(", "))
    }

    /**
     * Taps the Club button and waits for the Club screen.
     *
     * @param clubButton The Club button's centre.
     * @throws DailyStepFailed When the Club screen never appears.
     */
    private fun openClub(clubButton: Point) {
        game.tap(clubButton.x, clubButton.y)
        game.wait(3.0)
        game.waitForLoading()
        game.waitForAny(LabelClubMenu, timeoutSec = 20.0) ?: throw DailyStepFailed("Club screen did not appear")
        game.wait(1.0)
    }

    /**
     * Opens the item request, collects it when it expired, and issues a new one when none is running.
     *
     * @return A note for the summary.
     */
    private fun request(): String {
        val button = game.waitForAny(ButtonClubItemRequest)?.second ?: throw DailyStepFailed("Item Request button not found")
        game.tap(button.x, button.y)
        game.wait(1.0)
        // An expired or running request shows Close, but the item picker shows Cancel instead, so wait for any of the three states.
        game.waitForAny(LabelItemRequestExpired, LabelItemRequestSelectPrompt, ButtonClose) ?: throw DailyStepFailed("Item Request dialog did not appear")
        game.wait(0.5)
        val sourceBitmap = game.imageUtils.getSourceBitmap()
        return when {
            LabelItemRequestExpired.check(game.imageUtils, sourceBitmap = sourceBitmap) -> {
                MessageLog.i(TAG, "[DAILIES] The last item request expired. Collecting it.")
                ButtonClose.click(game.imageUtils)
                if (!ButtonClubItemRequest.click(game.imageUtils, tries = 30)) throw DailyStepFailed("Item Request button not found in attempt to request a new item")
                game.wait(1.0)
                pickItem()
            }

            LabelItemRequestSelectPrompt.check(game.imageUtils, sourceBitmap = sourceBitmap) -> pickItem()

            // Neither expired nor asking for a pick, so a request is still running.
            else -> {
                ButtonClose.click(game.imageUtils)
                "request still active"
            }
        }
    }

    /**
     * Picks the configured item in the item picker and confirms it.
     *
     * @return A note for the summary.
     * @throws DailyStepFailed When the picker never appears or the item is not in it.
     */
    private fun pickItem(): String {
        game.waitForAny(LabelItemRequestSelectPrompt, bHandleDialogs = false) ?: throw DailyStepFailed("Item picker did not appear")
        selectItemTab()
        if (!tapItemInList()) {
            ButtonCancel.click(game.imageUtils)
            throw DailyStepFailed("$item not found in the item picker")
        }
        confirmPick()
        return "requested $item"
    }

    /** Opens the Shoes or Winner's Sash tab for the item. Each tab template is the unselected look, so it only matches when that tab is not open. */
    private fun selectItemTab() {
        if ((if (item.endsWith("Racing Shoes")) ButtonItemRequestShoesTab else ButtonItemRequestSashTab).click(game.imageUtils)) game.wait(1.0)
    }

    /**
     * Scrolls the item list and taps the item's row. Each row is found by its "Held" pill, and its title sits just above-left of it.
     *
     * @return True when the item's row was found and tapped.
     * @throws DailyStepFailed When the picker's list is not on screen.
     */
    private fun tapItemInList(): Boolean {
        val list = createDialogScrollList(game) ?: throw DailyStepFailed("Item picker list not found")
        var picked = false
        list.process(entryComponent = LabelItemRequestHeld, bForceComponentDetection = true) { _, entry ->
            val heldX = entry.refX ?: return@process false
            val heldY = entry.refY ?: return@process false
            val title =
                game.imageUtils.performOCRFromReference(
                    Point(heldX.toDouble(), heldY.toDouble()),
                    ROW_TITLE[0],
                    ROW_TITLE[1],
                    game.imageUtils.relWidth(ROW_TITLE[2]),
                    game.imageUtils.relHeight(ROW_TITLE[3]),
                    useThreshold = false,
                    debugName = "item_request_row",
                    bitmap = entry.bitmap,
                )
            if (!matchItemName(title, item)) return@process false
            MessageLog.i(TAG, "[DAILIES] Requesting $item.")
            game.tap(game.imageUtils.relX(heldX.toDouble(), ROW_TAP_DX).toDouble(), game.imageUtils.relY((entry.bbox.y + heldY).toDouble(), ROW_TAP_DY).toDouble())
            game.wait(1.0)
            picked = true
            true
        }
        return picked
    }

    /**
     * Confirms the pick and waits for the Club screen to come back.
     *
     * @throws DailyStepFailed When the Club screen does not come back.
     */
    private fun confirmPick() {
        ButtonConfirm.click(game.imageUtils)
        game.wait(1.0)
        game.waitForAny(LabelClubMenu) ?: throw DailyStepFailed("Club screen did not come back after the pick")
    }

    /**
     * Opens the request list and donates to every request the game allows.
     *
     * @return A note for the summary.
     */
    private fun donate(): String {
        game.waitForAny(LabelClubMenu) ?: throw DailyStepFailed("Club screen did not appear")
        game.wait(0.5)
        // Tap the tab's exact centre. A randomized tap can clip the Practice Partners tab next to it and open the wrong list.
        val requestList = ButtonClubRequestList.findImageWithBitmap(game.imageUtils, game.imageUtils.getSourceBitmap()) ?: return "nothing to donate"
        game.tap(requestList.x, requestList.y)
        game.wait(1.0)
        game.waitForAny(ButtonClose) ?: throw DailyStepFailed("Item request list did not appear")
        game.wait(0.5)

        val sourceBitmap = game.imageUtils.getSourceBitmap()
        val donate = ButtonDonateToAll.findImageWithBitmap(game.imageUtils, sourceBitmap)
        if (donate == null || ButtonDonateToAll.checkDisabled(game.imageUtils, sourceBitmap) != false) {
            ButtonClose.click(game.imageUtils, sourceBitmap = sourceBitmap)
            return "nothing to donate"
        }
        game.tap(donate.x, donate.y)
        game.wait(1.0)
        if (!ButtonDonateToAllConfirm.click(game.imageUtils, tries = 30)) throw DailyStepFailed("Confirm Donations Donate to All not found")
        game.wait(2.0)
        // Close the result popup, if one opened.
        ButtonClose.click(game.imageUtils)
        return "donated"
    }
}
