package com.steve1316.uma_android_automation.bot

import android.graphics.Bitmap
import android.util.Log
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.DiscordUtils
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.components.*
import com.steve1316.uma_android_automation.types.BoundingBox
import com.steve1316.uma_android_automation.types.DateYear
import com.steve1316.uma_android_automation.types.RunningStyle
import org.opencv.core.Point

/**
 * Base class for handling various dialogs in the Umamusume game.
 *
 * This class centralizes all dialog handling logic from different bot modules
 * to provide a single, maintainable source of truth for dialog interactions.
 *
 * @param game Reference to the bot's [Game] instance for state access and utilities.
 *
 * Example usage:
 *
 * ```kotlin
 * // Basic usage
 * val (handled, dialog) = game.campaign.handleDialogs()
 *
 * // Usage with arguments
 * val args = mapOf("overrideIgnoreConsecutiveRaceWarning" to true)
 * game.campaign.handleDialogs(args = args)
 * ```
 */
open class DialogHandler(val game: Game) {
	private val TAG: String = "[${MainActivity.loggerTag}]DialogHandler"

	/**
	 * Detects and handles any dialog popups.
	 *
	 * This is the centralized implementation that handles generic, campaign,
	 * racing, and skill-related dialogs.
	 *
	 * To prevent the bot moving too fast, we add a 500ms delay to the
	 * exit of this function whenever we close the dialog.
	 * This gives the dialog time to close since there is a very short
	 * animation that plays when a dialog closes.
	 *
	 * @param dialog An optional dialog to evaluate. This allows chaining
	 * dialog handler calls for improved performance.
	 * @param args Optional arguments mapping for dialog handling.
	 *
	 * @return A pair of a boolean and a nullable DialogInterface.
	 * The boolean is true when a dialog has been handled by this function.
	 * The DialogInterface is the detected dialog, or NULL if no dialogs were found.
	 */
	open fun handleDialogs(dialog: DialogInterface? = null, args: Map<String, Any> = mapOf()): Pair<Boolean, DialogInterface?> {
		val dialog: DialogInterface? = dialog ?: DialogUtils.getDialog(imageUtils = game.imageUtils)
		if (dialog == null) {
			Log.d(TAG, "[DIALOG] No dialog found.")
			return Pair(false, null)
		}

		MessageLog.d(TAG, "[DIALOG] Handle dialog: ${dialog.name}")

		when (dialog.name) {
			// Generic Dialogs (from Game.kt)
			"connection_error" -> {
				val currTime: Long = System.currentTimeMillis()
				// If the cooldown period has lapsed, reset our count.
				if (currTime - game.lastConnectionErrorRetryTimeMs > game.connectionErrorRetryCooldownTimeMs) {
					game.connectionErrorRetryAttempts = 0
					game.lastConnectionErrorRetryTimeMs = currTime
				}

				if (game.connectionErrorRetryAttempts >= game.maxConnectionErrorRetryAttempts) {
					throw InterruptedException("Max connection error retry attempts reached. Stopping bot...")
				}

				game.connectionErrorRetryAttempts++
				dialog.ok(imageUtils = game.imageUtils)
				game.wait(0.5)
			}
			"display_settings" -> dialog.close(imageUtils = game.imageUtils)
			"help_and_glossary" -> dialog.close(imageUtils = game.imageUtils)
			"session_error" -> {
				throw InterruptedException("Session error. Stopping bot...")
			}

			// Racing Dialogs (from Racing.kt)
			"consecutive_race_warning" -> {
                val overrideIgnoreConsecutiveRaceWarning = args["overrideIgnoreConsecutiveRaceWarning"] as? Boolean ?: false
				game.racing.raceRepeatWarningCheck = true
				if (overrideIgnoreConsecutiveRaceWarning || game.racing.enableForceRacing || game.racing.ignoreConsecutiveRaceWarning) {
					MessageLog.i(TAG, "[RACE] Consecutive race warning! Racing anyway...")
					dialog.ok(imageUtils = game.imageUtils)
					// This dialog requires a little extra delay since it loads the
					// race list instead of just closing the dialog.
					game.wait(1.0, skipWaitingForLoading = true)
				} else {
					MessageLog.i(TAG, "[RACE] Consecutive race warning! Aborting racing...")
					game.racing.clearRacingRequirementFlags()
					dialog.close(imageUtils = game.imageUtils)
				}
			}
			"race_playback" -> {
				// Select portrait mode to prevent game from switching to landscape.
				RadioPortrait.click(game.imageUtils)
				// Click the checkbox to prevent this popup in the future.
				Checkbox.click(game.imageUtils)
				dialog.ok(game.imageUtils)
			}
			"runners" -> dialog.close(imageUtils = game.imageUtils)
			"strategy" -> {
				if (!game.trainee.bHasUpdatedAptitudes) {
					game.trainee.bTemporaryRunningStyleAptitudesUpdated = game.racing.updateRaceScreenRunningStyleAptitudes()
				}

				var runningStyle: RunningStyle? = null
				val runningStyleString: String = when {
					// Special case for when the bot has not been able to check the date
					// i.e. when the bot starts at the race screen.
					game.currentDate.day == 1 -> game.racing.userSelectedOriginalStrategy
					game.currentDate.year == DateYear.JUNIOR -> game.racing.juniorYearRaceStrategy
					else -> game.racing.userSelectedOriginalStrategy
				}
				when (runningStyleString.uppercase()) {
					// Do not select a strategy. Use what is already selected.
					"DEFAULT" -> {
						MessageLog.i(TAG, "[DIALOG] strategy:: Using the default running style.")
						dialog.ok(imageUtils = game.imageUtils)
						game.trainee.bHasSetRunningStyle = true
						return Pair(true, dialog)
					}
					// Auto-select the optimal running style based on trainee aptitudes.
					"AUTO" -> {
						MessageLog.i(TAG, "[DIALOG] strategy:: Auto-selecting the trainee's optimal running style.")
						runningStyle = game.trainee.runningStyle
					}
					else -> {
						MessageLog.i(TAG, "[DIALOG] strategy:: Using user-specified running style: $runningStyleString")
						runningStyle = RunningStyle.fromShortName(runningStyleString)
					}
				}

				when (runningStyle) {
					RunningStyle.FRONT_RUNNER -> ButtonRaceStrategyFront.click(imageUtils = game.imageUtils)
					RunningStyle.PACE_CHASER -> ButtonRaceStrategyPace.click(imageUtils = game.imageUtils)
					RunningStyle.LATE_SURGER -> ButtonRaceStrategyLate.click(imageUtils = game.imageUtils)
					RunningStyle.END_CLOSER -> ButtonRaceStrategyEnd.click(imageUtils = game.imageUtils)
					null -> {
						// This indicates programmer error.
						MessageLog.e(TAG, "[DIALOG] strategy:: Invalid running style: $runningStyle")
						dialog.close(imageUtils = game.imageUtils)
						game.trainee.bHasSetRunningStyle = false
						return Pair(true, dialog)
					}
				}

				// We only want to set this flag if the date has been checked.
				// Otherwise, if the day is still 1, that means we probably started
				// the bot at the racing screen. In this case, we still want to set
				// the running style the next time we get back to the race
				// selection screen after verifying the date.
				if (game.currentDate.day != 1) {
					game.trainee.bHasSetRunningStyle = true
				}
				dialog.ok(imageUtils = game.imageUtils)
			}
			"trophy_won" -> dialog.close(imageUtils = game.imageUtils)
			"try_again" -> {
				// All branches need a slight delay to allow the dialog to close
				// since the runRaceWithRetries loop handles dialogs at the start
				// of each iteration. Can cause problem where we handle one branch
				// then immediately handle dialogs again and handle a second branch
				// for the same dialog instance.
				if (game.racing.disableRaceRetries) {
					if (game.racing.enableFreeRaceRetry && IconOneFreePerDayTooltip.check(game.imageUtils)) {
						MessageLog.i(TAG, "[RACE] Failed mandatory race. Using daily free race retry...")
						game.racing.raceRetries--
						dialog.ok(game.imageUtils)
						game.wait(0.5, skipWaitingForLoading = true)
						return Pair(true, dialog)
					}
					if (game.racing.enableCompleteCareerOnFailure) {
						MessageLog.i(TAG, "[RACE] Failed a mandatory race and no retries remaining. Completing career...")
						// Manually set retries to -1 to break the race retry loop.
						game.racing.raceRetries = -1
						dialog.close(game.imageUtils)
						game.wait(0.5, skipWaitingForLoading = true)
						return Pair(true, dialog)
					}
					MessageLog.i(TAG, "\n[END] Stopping the bot due to failing a mandatory race.")
					MessageLog.i(TAG, "********************")
					game.notificationMessage = "Stopping the bot due to failing a mandatory race."
					if (DiscordUtils.enableDiscordNotifications) {
						DiscordUtils.queue.add("```diff\n- ${MessageLog.getSystemTimeString()} Stopping the bot due to failing a mandatory race.\n```")
					}
					throw IllegalStateException()
				}
				game.racing.raceRetries--
				dialog.ok(game.imageUtils)
				game.wait(0.5, skipWaitingForLoading = true)
			}
			"unlock_requirements" -> dialog.close(imageUtils = game.imageUtils)

			// Campaign Dialogs (from Campaign.kt)
			"agenda_details" -> dialog.close(imageUtils = game.imageUtils)
			"bonus_umamusume_details" -> dialog.close(imageUtils = game.imageUtils)
			"career" -> dialog.close(imageUtils = game.imageUtils)
			"career_event_details" -> dialog.close(imageUtils = game.imageUtils)
			"career_profile" -> dialog.close(imageUtils = game.imageUtils)
			"choices" -> dialog.close(imageUtils = game.imageUtils)
			"concert_skip_confirmation" -> {
				// Click the checkbox to prevent this popup in the future.
				Checkbox.click(imageUtils = game.imageUtils)
				dialog.ok(imageUtils = game.imageUtils)
			}
			"epithets" -> dialog.close(imageUtils = game.imageUtils)
			"fans" -> dialog.close(imageUtils = game.imageUtils)
			"featured_cards" -> dialog.close(imageUtils = game.imageUtils)
			"give_up" -> dialog.close(imageUtils = game.imageUtils)
			"goal_not_reached" -> {
				// We are handling the logic for when to race on our own.
				// Thus we just close this warning.
				game.racing.encounteredRacingPopup = true
				dialog.close(imageUtils = game.imageUtils)
			}
			"goals" -> dialog.close(imageUtils = game.imageUtils)
			"infirmary" -> {
				Checkbox.click(imageUtils = game.imageUtils)
				dialog.ok(imageUtils = game.imageUtils)
			}
			"insufficient_fans" -> {
				// We are handling the logic for when to race on our own.
				// Thus we just close this warning.
				game.racing.encounteredRacingPopup = true
				dialog.close(imageUtils = game.imageUtils)
			}
			"log" -> dialog.close(imageUtils = game.imageUtils)
			"menu" -> dialog.close(imageUtils = game.imageUtils)
			"mood_effect" -> dialog.close(imageUtils = game.imageUtils)
			"my_agendas" -> dialog.close(imageUtils = game.imageUtils)
			"no_retries" -> dialog.ok(imageUtils = game.imageUtils)
			"options" -> dialog.close(imageUtils = game.imageUtils)
			"perks" -> dialog.close(imageUtils = game.imageUtils)
			"placing" -> dialog.close(imageUtils = game.imageUtils)
			"purchase_alarm_clock" -> {
				throw InterruptedException("Ran out of alarm clocks. Stopping bot...")
			}
			"quick_mode_settings" -> {
				val bbox = BoundingBox(
					x = game.imageUtils.relX(0.0, 160),
					y = game.imageUtils.relY(0.0, 770),
					w = game.imageUtils.relWidth(70),
					h = game.imageUtils.relHeight(460),
				)
				val optionLocations: ArrayList<Point> = IconHorseshoe.findAll(
					imageUtils = game.imageUtils,
					region = bbox.toIntArray(),
					confidence = 0.0,
				)
				if (optionLocations.size == 4) {
					MessageLog.d(TAG, "[DIALOG] quick_mode_settings: Using findAll method.")
					val loc: Point = optionLocations[1]
					game.tap(loc.x, loc.y, IconHorseshoe.template.path)
				} else {
					MessageLog.d(TAG, "[DIALOG] quick_mode_settings: Using image OCR method.")
					// Fallback to image detection.
					RadioCareerQuickShortenAllEvents.click(imageUtils = game.imageUtils)
				}
				
				dialog.ok(imageUtils = game.imageUtils)
			}
			"race_details" -> {
				dialog.ok(imageUtils = game.imageUtils)
			}
			"race_recommendations" -> {
				ButtonRaceRecommendationsCenterStage.click(imageUtils = game.imageUtils)
				Checkbox.click(imageUtils = game.imageUtils)
				dialog.ok(imageUtils = game.imageUtils)
			}
			"recreation" -> {
				Checkbox.click(imageUtils = game.imageUtils)
				dialog.ok(imageUtils = game.imageUtils)
			}
			"rest" -> {
				Checkbox.click(imageUtils = game.imageUtils)
				dialog.ok(imageUtils = game.imageUtils)
			}
			"rest_and_recreation" -> {
				// Does not have a checkbox unlike the other rest/rec/etc.
				dialog.ok(imageUtils = game.imageUtils)
			}
			"scheduled_race_available" -> {
				MessageLog.i(TAG, "[INFO] There is a scheduled race today. Racing it now...")
				dialog.ok(imageUtils = game.imageUtils)
				if (!game.racing.handleRaceEvents(isScheduledRace = true) && game.campaign.handleRaceEventFallback()) {
					MessageLog.i(TAG, "\n[END] Stopping the bot due to failing to handle a scheduled race.")
					MessageLog.i(TAG, "********************")
					game.notificationMessage = "Stopping the bot due to failing to handle a scheduled race."
					if (DiscordUtils.enableDiscordNotifications) {
						DiscordUtils.queue.add("```diff\n- ${MessageLog.getSystemTimeString()} Stopping the bot due to failing to handle a scheduled race.\n```")
					}
					throw IllegalStateException()
				}
			}
			"scheduled_races" -> dialog.close(imageUtils = game.imageUtils)
			"schedule_settings" -> dialog.close(imageUtils = game.imageUtils)
			"skill_details" -> dialog.close(imageUtils = game.imageUtils)
			"song_acquired" -> dialog.close(imageUtils = game.imageUtils)
			"spark_details" -> dialog.close(imageUtils = game.imageUtils)
			"sparks" -> dialog.close(imageUtils = game.imageUtils)
			"team_info" -> dialog.close(imageUtils = game.imageUtils)
			"umamusume_class" -> {
				val bitmap: Bitmap = game.imageUtils.getSourceBitmap()
				val templateBitmap: Bitmap? = game.imageUtils.getBitmaps(LabelUmamusumeClassFans.template.path).second
				if (templateBitmap == null) {
					MessageLog.e(TAG, "[DIALOG] umamusume_class: Could not get template bitmap for LabelUmamusumeClassFans: ${LabelUmamusumeClassFans.template.path}.")
					dialog.close(imageUtils = game.imageUtils)
					return Pair(true, dialog)
				}
				val point: Point? = LabelUmamusumeClassFans.find(imageUtils = game.imageUtils).first
				if (point == null) {
					MessageLog.w(TAG, "[DIALOG] umamusume_class: Could not find LabelUmamusumeClassFans.")
					dialog.close(imageUtils = game.imageUtils)
					return Pair(true, dialog)
				}

				// Add a small 8px buffer to vertical component.
				val bbox = BoundingBox(
					x = game.imageUtils.relX(0.0, (point.x + (templateBitmap.width / 2)).toInt()),
					y = game.imageUtils.relY(0.0, (point.y - (templateBitmap.height / 2) - 4).toInt()),
					w = game.imageUtils.relWidth(300),
					h = game.imageUtils.relHeight(templateBitmap.height + 4),
				)

				val croppedBitmap = game.imageUtils.createSafeBitmap(
					bitmap,
					bbox.x,
					bbox.y,
					bbox.w,
					bbox.h,
					"dialog::umamusume_class: Cropped bitmap.",
				)
				if (croppedBitmap == null) {
					MessageLog.e(TAG, "[DIALOG] umamusume_class: Failed to crop bitmap.")
					dialog.close(imageUtils = game.imageUtils)
					return Pair(true, dialog)
				}
				val fans = game.imageUtils.getUmamusumeClassDialogFanCount(croppedBitmap)
				if (fans != null) {
					game.trainee.fans = fans
					game.campaign.bNeedToCheckFans = false
					MessageLog.d(TAG, "[DIALOG] umamusume_class: Updated fan count: ${game.trainee.fans}")
				} else {
					MessageLog.w(TAG, "[DIALOG] umamusume_class: getUmamusumeClassDialogFanCount returned NULL.")
				}
				
				dialog.close(imageUtils = game.imageUtils)

				// Print the trainee info with the updated fan count.
				game.trainee.logInfo()
			}
			"umamusume_details" -> {
				val prevTrackSurface = game.trainee.trackSurface
				val prevTrackDistance = game.trainee.trackDistance
				val prevRunningStyle = game.trainee.runningStyle
				game.trainee.updateAptitudes(imageUtils = game.imageUtils)
				game.trainee.bTemporaryRunningStyleAptitudesUpdated = false

				if (game.trainee.runningStyle != prevRunningStyle) {
					// Reset this flag since our preferred running style has changed.
					game.trainee.bHasSetRunningStyle = false
				}

				dialog.close(imageUtils = game.imageUtils)
			}
			"unity_cup_available" -> dialog.close(imageUtils = game.imageUtils)
			"unmet_requirements" -> dialog.close(imageUtils = game.imageUtils)

			// Skill List Dialogs (from SkillList.kt)
			"skill_list_confirmation" -> {
				dialog.ok(game.imageUtils)
				// This dialog takes longer to close than others.
				// Add an extra delay to make sure we don't skip anything.
				game.wait(1.0)
			}
			"skill_list_confirm_exit" -> dialog.ok(game.imageUtils)
			"skills_learned" -> dialog.close(game.imageUtils)

			else -> {
				Log.w(TAG, "[DIALOG] Unknown dialog \"${dialog.name}\" detected so it will not be handled.")
				return Pair(false, dialog)
			}
		}

		game.wait(0.5, skipWaitingForLoading = true)
		return Pair(true, dialog)
	}
}
