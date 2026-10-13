package com.steve1316.uma_android_automation.bot.dailies

import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.components.ButtonBack
import com.steve1316.uma_android_automation.components.ButtonClose
import com.steve1316.uma_android_automation.components.ButtonCollectAll
import com.steve1316.uma_android_automation.components.ButtonHomeMissionsIcon
import com.steve1316.uma_android_automation.components.ButtonHomePresentsIcon
import com.steve1316.uma_android_automation.components.LabelMissionsHeader

/**
 * Collects finished mission rewards from the Missions screen.
 *
 * @property game The bot's [Game] instance.
 * @property enabled Whether the user turned this part on.
 */
class Missions(private val game: Game, override val enabled: Boolean) : DailyPart {
    override val name: String = "Missions"

    override fun start(): PartOutcome {
        openMissions()
        val outcome = if (collectAll()) PartOutcome.Done("rewards collected") else PartOutcome.Skipped("nothing to collect")
        ButtonBack.click(game.imageUtils)
        game.wait(1.0)
        return outcome
    }

    /**
     * Opens the Missions screen from Home.
     *
     * @throws DailyStepFailed When the button or the screen never appears.
     */
    private fun openMissions() {
        if (!ButtonHomeMissionsIcon.click(game.imageUtils, tries = 30)) throw DailyStepFailed("Missions button not found")
        game.wait(2.0)
        game.waitForAny(LabelMissionsHeader) ?: throw DailyStepFailed("Missions screen did not appear")
        game.wait(0.5)
    }

    /**
     * Taps Collect All when it is enabled and closes the Rewards Collected popup.
     *
     * @return True when there was something to collect.
     * @throws DailyStepFailed When the popup's Close never appears.
     */
    private fun collectAll(): Boolean {
        val sourceBitmap = game.imageUtils.getSourceBitmap()
        val collect = ButtonCollectAll.findImageWithBitmap(game.imageUtils, sourceBitmap)
        if (collect == null || ButtonCollectAll.checkDisabled(game.imageUtils, sourceBitmap) != false) return false
        game.tap(collect.x, collect.y)
        game.wait(1.0)
        game.waitForLoading()
        if (!ButtonClose.click(game.imageUtils, tries = 30)) throw DailyStepFailed("Rewards Collected close not found")
        game.task.tryHandleAllDialogs(timeoutMs = 3000)
        return true
    }
}

/**
 * Collects everything in the Presents box.
 *
 * @property game The bot's [Game] instance.
 * @property enabled Whether the user turned this part on.
 */
class Presents(private val game: Game, override val enabled: Boolean) : DailyPart {
    override val name: String = "Presents"

    override fun start(): PartOutcome {
        openPresents()
        if (!collectAll()) return PartOutcome.Skipped("nothing to collect")
        closePopups()
        return PartOutcome.Done("presents collected")
    }

    /**
     * Opens the Presents dialog from Home.
     *
     * @throws DailyStepFailed When the button or the dialog never appears.
     */
    private fun openPresents() {
        if (!ButtonHomePresentsIcon.click(game.imageUtils, tries = 30)) throw DailyStepFailed("Presents button not found")
        game.wait(2.0)
        game.waitForAny(ButtonCollectAll) ?: throw DailyStepFailed("Presents dialog did not appear")
        game.wait(0.5)
    }

    /**
     * Taps Collect All when it is enabled. Closes the dialog when there is nothing to collect.
     *
     * @return True when there was something to collect.
     */
    private fun collectAll(): Boolean {
        val sourceBitmap = game.imageUtils.getSourceBitmap()
        val collect = ButtonCollectAll.findImageWithBitmap(game.imageUtils, sourceBitmap)
        if (collect == null || ButtonCollectAll.checkDisabled(game.imageUtils, sourceBitmap) != false) {
            ButtonClose.click(game.imageUtils, sourceBitmap = sourceBitmap)
            return false
        }
        game.tap(collect.x, collect.y)
        game.wait(2.0)
        return true
    }

    /** Closes the result popups, then the dialog itself once its Collect All greys out. */
    private fun closePopups() {
        repeat(3) {
            val screen = game.imageUtils.getSourceBitmap()
            val emptied = ButtonCollectAll.checkDisabled(game.imageUtils, screen) == true
            ButtonClose.click(game.imageUtils, sourceBitmap = screen)
            game.wait(1.0)
            if (emptied) return
        }
    }
}
