package com.steve1316.uma_android_automation.types

import android.graphics.Bitmap
import android.util.Log
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Campaign
import com.steve1316.uma_android_automation.bot.DialogHandlerResult
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.components.*
import com.steve1316.uma_android_automation.types.BoundingBox
import com.steve1316.uma_android_automation.types.RunningStyle
import com.steve1316.uma_android_automation.types.SkillData
import com.steve1316.uma_android_automation.types.SkillListEntry
import com.steve1316.uma_android_automation.types.TrackDistance
import com.steve1316.uma_android_automation.types.TrackSurface
import com.steve1316.uma_android_automation.utils.ScrollList
import com.steve1316.uma_android_automation.utils.ScrollListEntry
import org.opencv.core.Point
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A callback that fires whenever we detect an entry in the skill list. */
fun interface OnEntryDetectedCallback {
    /**
     * Executes when an entry is detected in the skill list.
     *
     * @param skillList A reference to the [SkillList] instance which fired this callback.
     * @param entry The [SkillListEntry] instance which was detected.
     * @param skillUpButtonLocation The screen location of the [ButtonSkillUp] for this entry.
     *
     * @return Early exit flag. A value of True is used to exit from the entry detection
     * loop early.
     */
    fun onEntryDetected(skillList: SkillList, entry: SkillListEntry, skillUpButtonLocation: Point): Boolean
}

/**
 * Strips a trailing rank glyph that OCR misread as a letter ("O"/"x") or left as stray noise, returning the base name.
 * Glyphs are stored in the database as " <glyph>" with a leading space, so the base must have the misread char removed.
 *
 * @param name The raw skill name that may carry a trailing misread glyph.
 * @return The base skill name with the trailing glyph noise removed.
 */
fun stripTrailingGlyphNoise(name: String): String {
    var s: String = name.trimEnd()
    // Glued misread glyph, e.g. "SomethingO".
    if (s.length >= 2 && (s.endsWith("O") || s.endsWith("x")) && s[s.length - 2] != ' ') {
        s = s.dropLast(1)
    }
    // Standalone trailing letter/digit preceded by a space, e.g. "Something O".
    if (s.isNotEmpty() && s.last().isLetterOrDigit() && (s.length == 1 || s[s.length - 2] == ' ')) {
        s = s.dropLast(1).trimEnd()
    }
    return s
}

/** Number of skill rows visible at once on the Umamusume Details "Skills" tab before it scrolls. */
private const val VISIBLE_SKILL_ROWS = 5

/** Columns in the Skills-tab grid (left and right). */
private const val SKILL_COLUMNS = 2

/** Maximum number of scroll passes when reading the Skills tab (bounds trainees with many skills). */
private const val MAX_SKILL_SCROLLS = 10

/**
 * Filled cells the Skills tab must show before it is treated as a scrollable list. The grid holds VISIBLE_SKILL_ROWS x SKILL_COLUMNS = 10 cells, so strictly only a full page can overflow,
 * but this sits below that on purpose. Should the occupancy probe ever undercount a full page (a dim tile reading as empty), a 10-cell gate would skip the scroll and silently drop every
 * skill below the fold. The margin costs a couple of wasted swipes for a trainee holding exactly 8 or 9 skills and buys safety against that far worse failure.
 */
private const val MIN_SKILL_CELLS_FOR_SCROLL = 8

/** Left edge of each Skills-tab grid column, on the 1080-wide reference. */
private const val SKILL_CELL_X_LEFT = 118
private const val SKILL_CELL_X_RIGHT = 622

/** Top of the first Skills-tab row and the pitch between rows, on the 1920-tall reference. */
private const val SKILL_GRID_TOP_Y = 1036
private const val SKILL_ROW_PITCH_Y = 112

/**
 * Luminance range within a Skills-tab cell above which the cell is judged to hold a skill. An occupied cell carries an icon and dark name text over a light tile so its luminance spans a
 * wide range, while an empty slot below the last skill is flat panel background. Estimated - widen or narrow it from the per-cell ranges logged in debug mode.
 */
private const val SKILL_CELL_OCCUPIED_LUMINANCE_RANGE = 60

/**
 * The result of reading the Umamusume Details "Skills" tab.
 *
 * @property skillNames The canonical database names of the trainee's currently-owned skills.
 * @property uniqueLevel The unique skill's level read from the first cell, or 0 when unread.
 */
data class DetailsSkillsResult(val skillNames: List<String>, val uniqueLevel: Int)

/**
 * Handles all interactions with the skill list screen and manages the [Trainee]'s skill data.
 *
 * This class provides functionality to detect available skills, parse their details (name, price),
 * purchase skills, and filter the skill list based on various criteria like running style or track aptitude.
 *
 * @param game Reference to the bot's core [Game] instance.
 * @param campaign Reference to the current training scenario [Campaign] instance.
 */
class SkillList(private val game: Game, private val campaign: Campaign) {
    companion object {
        private val TAG: String = "[${MainActivity.loggerTag}]SkillList"
    }

    /** A mapping of skill names to their corresponding [SkillListEntry] objects. */
    private var entries: Map<String, SkillListEntry> = generateSkillListEntries()

    /** The current amount of skill points available to spend. */
    var skillPoints: Int = 0
        private set

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Debug Tests

    /**
     * Populates the skill list with mock data for testing and debugging.
     *
     * This data mimics a real skill list state, including obtained status and prices.
     *
     * @return A mapping of skill names to [SkillListEntry] objects.
     */
    fun parseMockSkillListEntries(): Map<String, SkillListEntry> {
        val mockSkills: Map<String, Int> =
            mapOf(
                "Warning Shot!" to -1,
                "Triumphant Pulse" to 120,
                "Kyoto Racecourse ○" to 63,
                "Standard Distance ○" to 63,
                "Summer Runner ○" to 81,
                "Cloudy Days ○" to 81,
                "Professor of Curvature" to 279,
                "Corner Adept ○" to 117,
                "Swinging Maestro" to 323,
                "Corner Recovery ○" to 170,
                "Straightaway Acceleration" to 119,
                "Calm in a Crowd" to 153,
                "Nimble Navigator" to 135,
                "Homestretch Haste" to 153,
                "Up-Tempo" to 104,
                "Steadfast" to 144,
                "Extra Tank" to 96,
                "Frenzied Pace Chasers" to 104,
                "Medium Straightaways ○" to 60,
                "Keeping the Lead" to 128,
                "Pressure" to 128,
                "Pace Chaser Corners ○" to 91,
                "Straight Descent" to 78,
                "Hydrate" to 144,
                "Late Surger Straightaways ○" to 84,
                "Fighter" to 84,
                "I Can See Right Through You" to 110,
                "Highlander" to 128,
                "Uma Stan" to 160,
                "Ignited Spirit SPD" to 180,
            )

        // Validate mock names against the database.
        val fixedSkills: MutableMap<String, Int> = mutableMapOf()
        for ((name, price) in mockSkills) {
            val fixedName: String? = game.skillDatabase.checkSkillName(name, fuzzySearch = true)
            if (fixedName == null) {
                Log.e(TAG, "[ERROR] parseMockSkillListEntries:: Skill \"$name\" not found in database.")
                return emptyMap()
            }
            // Ensure the entry exists in our current map.
            val entry: SkillListEntry? = entries[fixedName]
            if (entry == null) {
                Log.e(TAG, "[ERROR] parseMockSkillListEntries:: Skill \"$name\" not found in initialized entries.")
                return emptyMap()
            }
            fixedSkills[fixedName] = price
        }

        // Build the result map with updated entry states.
        val result: MutableMap<String, SkillListEntry> = mutableMapOf()
        for ((name, price) in fixedSkills) {
            val entry = entries[name]!!
            // Update the entry's availability.
            entry.bIsObtained = price <= 0
            entry.bIsVirtual = false
            // Update price based on mock data.
            entry.updateScreenPrice(price)
            result[name] = entry
        }

        return result.toMap()
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Creates a mapping of all possible skill names to their corresponding [SkillListEntry] objects.
     *
     * This function populates the initial skill mapping using the skill database. It ensures that
     * skill upgrade chains (e.g., "Hanshin Racecourse ○" -> "Hanshin Racecourse ◎") are correctly linked using
     * [SkillListEntry.prev] and `next` pointers to facilitate automated upgrade logic.
     *
     * All entries created here are initially marked as "virtual" until they are detected on-screen.
     *
     * @return A mapping of skill names to [SkillListEntry] objects.
     */
    private fun generateSkillListEntries(): Map<String, SkillListEntry> {
        // Retrieve the set of unique skill upgrade chains from the database.
        val upgradeChains: List<List<String>> = game.skillDatabase.skillUpgradeChains.values.toList().toSet().toList()

        val result: MutableMap<String, SkillListEntry> = mutableMapOf()

        for (chain in upgradeChains) {
            var prevEntry: SkillListEntry? = null
            for (name in chain) {
                // Skip if this skill name has already been processed in another chain context.
                if (name in result) {
                    continue
                }

                val skillData: SkillData? = game.skillDatabase.getSkillData(name)
                if (skillData == null) {
                    MessageLog.e(TAG, "[ERROR] generateSkillListEntries:: Failed to get skill data for \"$name\".")
                    continue
                }

                // Instantiate the entry. Since we haven't scanned the UI yet, it is marked as virtual.
                // We pass prevEntry to establish the link in the upgrade chain.
                val entry = SkillListEntry(game, campaign, skillData, bIsVirtual = true, prev = prevEntry)

                // Add to our mapping for quick lookup by name.
                result[name] = entry

                // Set this as the previous entry for the next skill in the chain.
                prevEntry = entry
            }
        }
        return result
    }

    /**
     * Extracts text from a specific bitmap region using OCR.
     *
     * @param bitmap The bitmap to perform OCR on.
     * @return The extracted text string, or an empty string if detection fails.
     */
    private fun extractText(bitmap: Bitmap): String {
        try {
            // Perform OCR using the ML Kit engine.
            val detectedText =
                game.imageUtils.performOCROnRegion(
                    bitmap,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height,
                    useThreshold = false,
                    useGrayscale = true,
                    scale = 2.0,
                    ocrEngine = "mlKit",
                    debugName = "analyzeSkillListEntry::extractText",
                )
            return detectedText
        } catch (e: Exception) {
            MessageLog.e(TAG, "[ERROR] extractText:: Exception during text extraction: ${e.message}")
            return ""
        }
    }

    /**
     * Detects the current skill points from the Skill List screen.
     *
     * @param bitmap Optional [Bitmap] used for detection. If null, a new screenshot is taken.
     * @return The detected skill points as an Integer, or null if detection fails.
     */
    fun detectSkillPoints(bitmap: Bitmap? = null): Int? {
        val srcBitmap: Bitmap = bitmap ?: game.imageUtils.getSourceBitmap()

        // Load the template for the Skill Points label.
        val templateBitmap: Bitmap? = LabelSkillListScreenSkillPoints.template.getBitmap(game.imageUtils)
        if (templateBitmap == null) {
            MessageLog.e(TAG, "[ERROR] detectSkillPoints:: Failed to load template bitmap for LabelSkillListScreenSkillPoints.")
            return null
        }

        // Find the label on the screen.
        val point: Point? = LabelSkillListScreenSkillPoints.findImageWithBitmap(game.imageUtils, srcBitmap)
        if (point == null) {
            MessageLog.e(TAG, "[ERROR] detectSkillPoints:: Failed to find LabelSkillListScreenSkillPoints.")
            return null
        }

        // Define the region containing the points number next to the label.
        val bbox =
            BoundingBox(
                x = (point.x + templateBitmap.width).toInt(),
                y = (point.y - templateBitmap.height).toInt(),
                w = (templateBitmap.width * 1.5).toInt(),
                h = (templateBitmap.height * 2),
            )

        // Crop the points region and perform OCR.
        val skillPointsBitmap: Bitmap? = game.imageUtils.createSafeBitmap(srcBitmap, bbox, "skillPointsBitmap")
        if (skillPointsBitmap == null) {
            MessageLog.e(TAG, "[ERROR] detectSkillPoints:: Failed to createSafeBitmap for skill points.")
            return null
        }

        val skillPointsString: String = extractText(skillPointsBitmap)
        // Clean up the string to keep only digits and parse to Int.
        val tmpSkillPoints: Int? = skillPointsString.replace("[^0-9]".toRegex(), "").toIntOrNull()

        if (tmpSkillPoints != null) {
            skillPoints = tmpSkillPoints
        }
        return skillPoints
    }

    /** Confirms all skill purchases and exits the [SkillList] screen back to the training screen. */
    fun confirmAndExit() {
        ButtonConfirm.click(game.imageUtils)
        game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)

        // Two dialogs typically appear upon purchase:
        // 1. Purchase confirmation.
        campaign.handleDialogs()
        // 2. Skills Learned summary.
        campaign.handleDialogs()

        // Final click to return to the previous screen.
        ButtonBack.click(game.imageUtils)
    }

    /** Resets all unconfirmed skill purchases and exits the [SkillList] screen. */
    fun cancelAndExit() {
        // Reset selections to prevent a popup from appearing when exiting.
        ButtonReset.click(game.imageUtils)
        ButtonBack.click(game.imageUtils)
        game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)

        // Handle any remaining dialogs as a failsafe.
        campaign.handleDialogs()
    }

    /**
     * Opens the full stats dialog and parses it to update [Trainee] aptitudes.
     *
     * This ensures the bot knows the current running style and track aptitudes, which are
     * crucial for correctly evaluating the utility of specific skills.
     */
    fun checkStats() {
        ButtonSkillListFullStats.click(game.imageUtils)
        game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)
        campaign.handleDialogs()
    }

    /**
     * Extracts the skill name (title) from a cropped skill list entry [Bitmap].
     *
     * This function uses OCR to read the skill name and also checks for special icons
     * ([IconSkillTitleDoubleCircle], [IconSkillTitleCircle], [IconSkillTitleX]) that might be present at the end of the title.
     *
     * @param bitmap A [Bitmap] containing a single cropped skill list entry.
     * @param debugString Identifier string for debugging files.
     * @return The detected skill name, or null if detection fails.
     */
    fun getSkillListEntryTitle(bitmap: Bitmap? = null, debugString: String = ""): String? {
        val srcBitmap: Bitmap = bitmap ?: game.imageUtils.getSourceBitmap()

        // Define the region within the entry where the title is located.
        val bbox =
            BoundingBox(
                x = (srcBitmap.width * 0.142).toInt(),
                y = 0,
                w = (srcBitmap.width * 0.57).toInt(),
                h = (srcBitmap.height * 0.338).toInt(),
            )

        // Crop the title region and perform OCR.
        val croppedTitle = game.imageUtils.createSafeBitmap(srcBitmap, bbox, "bboxTitle_$debugString")
        if (croppedTitle == null) {
            Log.e(TAG, "[ERROR] getSkillListEntryTitle:: createSafeBitmap for croppedTitle returned null.")
            return null
        }
        if (game.debugMode) {
            game.imageUtils.saveBitmap(croppedTitle, filename = "bboxTitle_$debugString")
        }

        var skillName: String = extractText(croppedTitle)
        if (skillName == "") {
            Log.e(TAG, "[ERROR] getSkillListEntryTitle:: Failed to extract skill name string via OCR.")
            return null
        }

        // Handle cases where the capital "I" is misread as a lowercase "l".
        skillName = skillName.replace(Regex("\\bl\\b"), "I")

        // Strip the "Remove" prefix used for negative skill titles before glyph handling so the base name is clean.
        // The database stores the base skill name without this prefix.
        if (skillName.startsWith("remove", ignoreCase = true)) {
            skillName = skillName.drop("remove".length).trim()
        }

        // Detect special icons (double-circle, single-circle, cross) that indicate skill levels or status.
        val componentsToCheck: List<ComponentInterface> =
            listOf(
                IconSkillTitleDoubleCircle,
                IconSkillTitleCircle,
                IconSkillTitleX,
            )
        var match: ComponentInterface? = null
        for (component in componentsToCheck) {
            val point: Point? = component.findImageWithBitmap(game.imageUtils, croppedTitle)
            if (point != null) {
                match = component
                break
            }
        }

        // Map the detected icon to its corresponding Unicode character.
        var iconChar: String =
            when (match) {
                IconSkillTitleDoubleCircle -> "◎"
                IconSkillTitleCircle -> "○"
                IconSkillTitleX -> "×"
                else -> ""
            }

        // The base skill name with any trailing misread-glyph letter removed. The database stores glyphs as " <glyph>".
        val baseName: String = stripTrailingGlyphNoise(skillName)

        // Template match found no glyph, but OCR left a trailing letter that is really a misread rank glyph.
        // Single-circle reads as "O"/"0"; cross reads as "x"/"X". Double-circle never appears in a scan, so it is not a case here.
        // Only accept the recovery when "<base> <glyph>" is a real skill so a legit name ending in a letter is not mangled.
        if (iconChar.isEmpty()) {
            val candidateGlyph: String =
                when (skillName.trimEnd().lastOrNull()) {
                    'O', '0' -> "○"
                    'x', 'X' -> "×"
                    else -> ""
                }
            if (candidateGlyph.isNotEmpty() && baseName.isNotEmpty() && game.skillDatabase.checkSkillName("$baseName $candidateGlyph") != null) {
                iconChar = candidateGlyph
            }
        }

        // If a glyph was resolved, re-append it with a leading space to match the database formatting.
        if (iconChar.isNotEmpty()) {
            skillName = "$baseName $iconChar"
        }

        return skillName
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Umamusume Details "Skills" tab reader (owned skills for the estimated rank)

    /**
     * Reads the trainee's currently-owned skills from the Umamusume Details dialog's "Skills" tab. Assumes the dialog is already open (header stats and aptitudes were read on the
     * default Conditions tab). Switches to the Skills tab, OCRs each cell of the 2-column grid, fuzzy-matches names to the skill database, scrolls for trainees with 10+ skills,
     * and reads the unique skill's level from the first cell. Cell geometry is fractions of the display, measured from a 1080x1920 capture.
     *
     * @return The owned skill names and the unique level.
     */
    fun parseDetailsSkillsTab(): DetailsSkillsResult {
        // Switch from the default Conditions tab to the Skills tab (a fixed position in the modal).
        game.tap(SharedData.displayWidth * 0.736, SharedData.displayHeight * 0.496, "details_skills_tab")
        game.wait(0.5, skipWaitingForLoading = true)

        val ownedNames = LinkedHashSet<String>()
        var uniqueLevel = 0
        var emptyPasses = 0
        for (pass in 0..MAX_SKILL_SCROLLS) {
            val bitmap = game.imageUtils.getSourceBitmap()
            var newFound = 0
            for (row in 0 until VISIBLE_SKILL_ROWS) {
                for (col in 0 until SKILL_COLUMNS) {
                    val name = readDetailsSkillCell(bitmap, row, col, pass) ?: continue
                    if (ownedNames.add(name)) newFound++
                }
            }
            if (pass == 0) {
                uniqueLevel = readUniqueSkillLevel(bitmap)
                // A first page that does not fill the grid cannot overflow, so there is nothing below to scroll to and every swipe from here is wasted (the loop would otherwise always
                // burn two). Count OCCUPIED cells rather than successfully-read names: a cell whose OCR failed is still a skill, and treating it as absent would skip the scroll on a full
                // page and silently drop every skill below the fold - far worse than the seconds this saves.
                val cellRanges = (0 until VISIBLE_SKILL_ROWS).flatMap { r -> (0 until SKILL_COLUMNS).map { c -> skillCellLuminanceRange(bitmap, r, c) } }
                val occupiedCells = cellRanges.count { it >= SKILL_CELL_OCCUPIED_LUMINANCE_RANGE }
                MessageLog.d(
                    TAG,
                    "[DEBUG] parseDetailsSkillsTab:: Cell luminance ranges: $cellRanges (occupied at >= $SKILL_CELL_OCCUPIED_LUMINANCE_RANGE, so $occupiedCells filled).",
                )
                if (occupiedCells < MIN_SKILL_CELLS_FOR_SCROLL) {
                    MessageLog.i(TAG, "[INFO] Skills tab filled only $occupiedCells/$MIN_SKILL_CELLS_FOR_SCROLL cells, so the list cannot scroll. Reading it in one pass.")
                    break
                }
            }
            // Stop only after two consecutive passes reveal nothing new, so a single fling that settles mid-row (its cells straddle the grid and read as empty) does not end the scan
            // early and drop the rows still below it.
            emptyPasses = if (newFound == 0) emptyPasses + 1 else 0
            if (pass > 0 && emptyPasses >= 2) break
            if (pass < MAX_SKILL_SCROLLS) scrollSkillsPanel()
        }

        MessageLog.i(TAG, "[INFO] Read ${ownedNames.size} owned skills (unique Lvl $uniqueLevel): ${ownedNames.joinToString(", ")}")
        return DetailsSkillsResult(ownedNames.toList(), uniqueLevel)
    }

    /**
     * OCRs and database-matches one skill cell of the Details "Skills" tab.
     *
     * @param bitmap The current screen bitmap.
     * @param row The 0-indexed grid row.
     * @param col The grid column (0 = left, 1 = right).
     * @return The canonical skill name, or null when the cell is empty or unmatched.
     */
    private fun readDetailsSkillCell(bitmap: Bitmap, row: Int, col: Int, pass: Int): String? {
        val w = SharedData.displayWidth.toDouble()
        val h = SharedData.displayHeight.toDouble()
        val x0 = if (col == 0) SKILL_CELL_X_LEFT else SKILL_CELL_X_RIGHT
        // The unique skill (row 0, col 0) shows "Lvl N" on the right, so its name region stops short to avoid reading the level.
        val x1 =
            when {
                col == 0 && row == 0 -> 410
                col == 0 -> 505
                else -> 1010
            }
        // Page 0 sits on the grid, but after a swipe the list settles ~40px low, so shift only the scrolled passes down to pull a wrapped name's second line into the crop without
        // clipping the tops of the aligned first-page rows.
        val yTop = SKILL_GRID_TOP_Y + row * SKILL_ROW_PITCH_Y + (if (pass == 0) 0 else 40)
        val bbox =
            BoundingBox(
                x = (w * x0 / 1080.0).toInt(),
                y = (h * yTop / 1920.0).toInt(),
                w = (w * (x1 - x0) / 1080.0).toInt(),
                // Tall enough to capture a name that wraps to two lines (e.g. "Pace Chaser Straightaways").
                h = (h * 100 / 1920.0).toInt(),
            )
        val crop = game.imageUtils.createSafeBitmap(bitmap, bbox, "detailsSkill_${row}_$col") ?: return null
        if (game.debugMode) game.imageUtils.saveBitmap(crop, "detailsSkill_${row}_$col")

        // Collapse the newline between wrapped lines into a single space so a two-line name matches its one-line database key.
        val text = extractText(crop).trim().replace(Regex("\\s+"), " ").replace(Regex("\\bl\\b"), "I")
        if (text.length < 2) return null
        val base = stripTrailingGlyphNoise(text.replace(Regex("[○◎×]"), " ").trim())
        if (base.length < 2) return null
        var name = game.skillDatabase.checkSkillName(base, fuzzySearch = true) ?: return null
        // The database stores +/-/upgraded variants as "<name> ○ / ◎ / ×". A skill on the Skills tab is positive unless it sits on a purple (negative) tile, so a non-purple
        // tile must never resolve to the "×" (negative) variant just because the base fuzzy-matched it. Default the positive correction to the base "○" (upgraded "◎" is rarer).
        if (name.trimEnd().endsWith("×") && !isNegativeSkillCell(bitmap, row, col)) {
            name = game.skillDatabase.checkSkillName("$base ○", fuzzySearch = true)
                ?: game.skillDatabase.checkSkillName("$base ◎", fuzzySearch = true)
                ?: name
        }
        // Reject a truncated read: if the OCR base is a strict prefix of a longer matched skill (5+ chars shorter), the cell's wrapped second line was clipped and the fuzzy match
        // grabbed a shorter same-prefix skill (e.g. "Pace Chaser" -> "Pace Chaser Savvy"). Returning null lets a better-aligned scroll pass read the full name instead of locking in
        // the wrong one.
        val matchedBase = stripTrailingGlyphNoise(name.replace(Regex("[○◎×]"), " ").trim())
        if (base.length + 5 <= matchedBase.length && matchedBase.startsWith(base, ignoreCase = true)) return null
        return name
    }

    /**
     * The spread between the darkest and brightest pixel of a Skills-tab cell on the un-scrolled page-0 grid. An occupied cell carries an icon and dark name text over a light tile, so its
     * luminance spans a wide range; an empty slot below the last skill is flat panel background and spans almost none. Used to tell "a skill is here" from "nothing is here" without caring
     * which tile colour it is, and - unlike the OCR - it still reports a cell as occupied when its name fails to read.
     *
     * @param bitmap The current screen bitmap.
     * @param row The 0-indexed grid row.
     * @param col The grid column (0 = left, 1 = right).
     * @return The cell's luminance range (0-255), or 0 when the cell falls outside the bitmap.
     */
    private fun skillCellLuminanceRange(bitmap: Bitmap, row: Int, col: Int): Int {
        val w = SharedData.displayWidth.toDouble()
        val h = SharedData.displayHeight.toDouble()
        // Span the cell's icon + name, sharing readDetailsSkillCell's origin and row pitch (page-0 grid, so no scrolled-pass offset).
        val sx = (w * (if (col == 0) SKILL_CELL_X_LEFT else SKILL_CELL_X_RIGHT) / 1080.0).toInt()
        val sy = (h * (SKILL_GRID_TOP_Y + row * SKILL_ROW_PITCH_Y) / 1920.0).toInt()
        // Clamp the sample area to the bitmap once rather than bounds-checking every pixel. A cell that falls entirely off the bitmap then simply samples nothing.
        val regionW = minOf((w * 390 / 1080.0).toInt(), bitmap.width - sx)
        val regionH = minOf((h * 96 / 1920.0).toInt(), bitmap.height - sy)
        var minLuminance = 255
        var maxLuminance = 0
        for (y in 0 until regionH step 4) {
            for (x in 0 until regionW step 4) {
                val pixel = bitmap.getPixel(sx + x, sy + y)
                // Rec. 601 luma, kept in integer math - a flat-vs-textured test does not need the precision of a float conversion per pixel.
                val luminance = ((((pixel shr 16) and 0xFF) * 299) + (((pixel shr 8) and 0xFF) * 587) + ((pixel and 0xFF) * 114)) / 1000
                if (luminance < minLuminance) minLuminance = luminance
                if (luminance > maxLuminance) maxLuminance = luminance
            }
        }
        // Nothing sampled leaves the bounds crossed - report no spread rather than a negative one.
        return if (maxLuminance < minLuminance) 0 else maxLuminance - minLuminance
    }

    /**
     * Detects whether a Skills-tab cell holds a negative skill, identified by its purple tile (positive skills use gold / silver / rainbow tiles).
     *
     * @param bitmap The current screen bitmap.
     * @param row The 0-indexed grid row.
     * @param col The grid column (0 = left, 1 = right).
     * @return True when the cell background reads as purple.
     */
    private fun isNegativeSkillCell(bitmap: Bitmap, row: Int, col: Int): Boolean {
        val w = SharedData.displayWidth.toDouble()
        val h = SharedData.displayHeight.toDouble()
        // The patch sits on the tile's right edge, inset from the cell origin, and steps by the same row pitch as the rest of the grid.
        val sx = (w * (if (col == 0) 470 else 990) / 1080.0).toInt()
        val sy = (h * (1050 + row * SKILL_ROW_PITCH_Y) / 1920.0).toInt()
        val patchW = (w * 40 / 1080.0).toInt()
        val patchH = (h * 40 / 1920.0).toInt()
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (y in 0 until patchH step 3) {
            for (x in 0 until patchW step 3) {
                if (sx + x < bitmap.width && sy + y < bitmap.height) {
                    val px = bitmap.getPixel(sx + x, sy + y)
                    r += (px shr 16) and 0xFF
                    g += (px shr 8) and 0xFF
                    b += px and 0xFF
                    n++
                }
            }
        }
        if (n == 0) return false
        val avgR = (r / n).toInt()
        val avgG = (g / n).toInt()
        val avgB = (b / n).toInt()
        // A negative tile is violet: blue and red both sit clearly above green. Gold, silver, and rainbow positive tiles do not.
        return avgB > avgR && avgR > avgG && (avgB - avgG) > 45
    }

    /**
     * OCRs the unique skill's level from the "Lvl N" region of the first Skills-tab cell.
     *
     * @param bitmap The current screen bitmap.
     * @return The unique skill level, or 0 when unread.
     */
    private fun readUniqueSkillLevel(bitmap: Bitmap): Int {
        val w = SharedData.displayWidth.toDouble()
        val h = SharedData.displayHeight.toDouble()
        // The unique level is a single digit shown as "Lvl N"; crop tightly on just the number and read it at high scale so ML Kit does not drop it.
        val text =
            game.imageUtils.performOCROnRegion(
                bitmap,
                (w * 493 / 1080.0).toInt(),
                (h * 1052 / 1920.0).toInt(),
                (w * 46 / 1080.0).toInt(),
                (h * 48 / 1920.0).toInt(),
                useThreshold = false,
                useGrayscale = true,
                scale = 3.0,
                ocrEngine = "mlKit",
                debugName = "detailsUniqueLevel",
            )
        // Levels are 1-6, so take the last digit to shrug off any stray "Lvl" characters that bled into the crop.
        return text.filter { it.isDigit() }.lastOrNull()?.digitToIntOrNull() ?: 0
    }

    /** Swipes up within the skills panel to reveal the next page of skills (for trainees with 10+ skills). */
    private fun scrollSkillsPanel() {
        val cx = (SharedData.displayWidth / 2).toFloat()
        // A short, slow swipe (~1.7 rows) reduces fling and heavily overlaps the previous page, so every skill passes through the visible area at several sub-row offsets across
        // passes - on at least one of which a two-line name is aligned enough to read fully rather than clipped.
        game.gestureUtils.swipe(cx, (SharedData.displayHeight * 0.66).toFloat(), cx, (SharedData.displayHeight * 0.56).toFloat(), 700L)
        game.wait(0.5, skipWaitingForLoading = true)
    }

    /**
     * Extracts the skill price from a cropped skill list entry [Bitmap].
     *
     * @param bitmap A [Bitmap] containing a single cropped skill list entry.
     * @param debugString Identifier string for debugging files.
     * @return The extracted price as an Integer, or null if detection fails.
     */
    fun getSkillListEntryPrice(bitmap: Bitmap? = null, debugString: String = ""): Int? {
        val srcBitmap: Bitmap = bitmap ?: game.imageUtils.getSourceBitmap()

        // Define the region within the entry where the price value is located.
        val bbox =
            BoundingBox(
                x = (srcBitmap.width * 0.7935).toInt(),
                y = (srcBitmap.height * 0.372).toInt(),
                w = (srcBitmap.width * 0.1068).toInt(),
                h = (srcBitmap.height * 0.251).toInt(),
            )

        // Crop the price region and perform OCR.
        val croppedPrice = game.imageUtils.createSafeBitmap(srcBitmap, bbox, "bboxPrice_$debugString")
        if (croppedPrice == null) {
            Log.e(TAG, "[ERROR] getSkillListEntryPrice:: createSafeBitmap for croppedPrice returned null.")
            return null
        }

        if (game.debugMode) {
            game.imageUtils.saveBitmap(croppedPrice, filename = "bboxPrice_$debugString")
        }

        // Extract text and parse the integer value.
        val price: Int? = extractText(croppedPrice).replace("[^0-9]".toRegex(), "").toIntOrNull()

        if (price == null) {
            Log.e(TAG, "[ERROR] getSkillListEntryPrice:: Failed to extract skill price from string.")
            return null
        }

        return price
    }

    /**
     * Extracts and processes all information for a single skill list entry.
     *
     * This function uses parallel threads to perform OCR on the skill name and price
     * simultaneously, using a [CountDownLatch] to synchronize the results.
     *
     * @param bitmap A [Bitmap] containing a single cropped skill list entry.
     * @param bIsObtained Whether the skill has already been purchased.
     * @param debugString Identifier string for debugging files.
     * @param cachedTitle Optional pre-detected title to avoid redundant OCR.
     * @return The updated [SkillListEntry] object, or null if analysis fails.
     */
    fun analyzeSkillListEntry(bitmap: Bitmap, bIsObtained: Boolean, debugString: String = "", cachedTitle: String? = null): SkillListEntry? {
        val latch = CountDownLatch(if (cachedTitle == null) 2 else 1)
        var skillPrice: Int? = null
        var skillName: String? = null

        // Start thread for title extraction if not cached.
        if (cachedTitle == null) {
            Thread {
                try {
                    val tmpSkillName: String? = getSkillListEntryTitle(bitmap, debugString)
                    if (tmpSkillName == null) {
                        Log.e(TAG, "[ERROR] analyzeSkillListEntry:: getSkillListEntryTitle() returned null.")
                        return@Thread
                    }
                    // Validate and potentially fix the name using the database (fuzzy matching).
                    skillName = game.skillDatabase.checkSkillName(tmpSkillName, fuzzySearch = true)
                } catch (e: Exception) {
                    Log.e(TAG, "[ERROR] analyzeSkillListEntry:: Error processing skill name: ${e.stackTraceToString()}")
                } finally {
                    latch.countDown()
                }
            }.apply { isDaemon = true }.start()
        } else {
            // Correct the cached title too, mirroring the non-cached path, so a cached OCR misread still resolves.
            skillName = game.skillDatabase.checkSkillName(cachedTitle, fuzzySearch = true)
        }

        // Start thread for price extraction.
        Thread {
            try {
                // If the skill is already obtained, the price is effectively 0 for the purpose of purchase logic.
                val tmpSkillPrice: Int? = if (bIsObtained) 0 else getSkillListEntryPrice(bitmap, debugString)
                if (tmpSkillPrice == null) {
                    Log.e(TAG, "[ERROR] analyzeSkillListEntry:: getSkillListEntryPrice() returned null.")
                    return@Thread
                }
                skillPrice = tmpSkillPrice
            } catch (e: Exception) {
                Log.e(TAG, "[ERROR] analyzeSkillListEntry:: Error processing skill price: ${e.stackTraceToString()}")
            } finally {
                latch.countDown()
            }
        }.apply { isDaemon = true }.start()

        // Wait for both extraction operations to complete or timeout.
        try {
            latch.await(3, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Log.e(TAG, "[ERROR] analyzeSkillListEntry:: Parallel analysis timed out.")
        }

        // Validate results.
        if (skillName == null) {
            MessageLog.e(TAG, "[ERROR] analyzeSkillListEntry:: Failed to parse skillName.")
            return null
        }

        if (skillPrice == null) {
            MessageLog.e(TAG, "[ERROR] analyzeSkillListEntry:: Failed to detect skillPrice.")
            return null
        }

        // Lookup the resulting entry in our mapping.
        val entry: SkillListEntry? = entries[skillName]
        if (entry == null) {
            MessageLog.e(TAG, "[ERROR] analyzeSkillListEntry:: Failed to find \"$skillName\" in entries mapping.")
            return null
        }

        // Update the entry's status and detected price.
        entry.bIsObtained = bIsObtained
        entry.bIsVirtual = false
        entry.updateScreenPrice(skillPrice)

        return entry
    }

    /**
     * Extracts and processes information for a single skill list entry in a thread-safe (synchronous) manner.
     *
     * This version is used when the caller is already running in a background thread
     * and requires a synchronous result.
     *
     * @param bitmap A [Bitmap] containing a single cropped skill list entry.
     * @param bIsObtained Whether the skill has already been purchased.
     * @param debugString Identifier string for debugging files.
     * @return The updated [SkillListEntry] object, or null if analysis fails.
     */
    fun analyzeSkillListEntryThreadSafe(bitmap: Bitmap, bIsObtained: Boolean, debugString: String = ""): SkillListEntry? {
        // Synchronously extract the skill name.
        var skillName: String? = getSkillListEntryTitle(bitmap, debugString)
        if (skillName == null) {
            MessageLog.e(TAG, "[ERROR] analyzeSkillListEntryThreadSafe:: getSkillListEntryTitle() returned null.")
            return null
        }
        skillName = game.skillDatabase.checkSkillName(skillName, fuzzySearch = true)

        // Synchronously extract the skill price if not already obtained.
        val skillPrice: Int? = if (bIsObtained) 0 else getSkillListEntryPrice(bitmap, debugString)
        if (skillPrice == null) {
            MessageLog.e(TAG, "[ERROR] analyzeSkillListEntryThreadSafe:: getSkillListEntryPrice() returned null.")
            return null
        }

        val entry: SkillListEntry? = entries[skillName]
        if (entry == null) {
            MessageLog.e(TAG, "[ERROR] analyzeSkillListEntryThreadSafe:: Failed to find \"$skillName\" in entries mapping.")
            return null
        }

        // Update the entry with detected data.
        entry.bIsObtained = bIsObtained
        entry.bIsVirtual = false
        entry.updateScreenPrice(skillPrice)

        return entry
    }

    /**
     * Processes a single entry detected by the [ScrollList].
     *
     * This function locates the [ButtonSkillUp] or [IconObtainedPill] within the entry's [Bitmap]
     * to refine the bounding box and extract the skill's details.
     *
     * @param entry The [ScrollListEntry] object containing the detected entry's [Bitmap] and bounding box.
     * @param cachedTitle Optional pre-detected title to avoid redundant OCR.
     * @return A Pair containing the processed [SkillListEntry] and its screen-space [Point] location.
     */
    private fun onScrollListEntry(entry: ScrollListEntry, cachedTitle: String? = null): Pair<SkillListEntry, Point>? {
        // Search for the Skill Up (+) button.
        val skillUpLoc: Point? = ButtonSkillUp.findImageWithBitmap(game.imageUtils, sourceBitmap = entry.bitmap)
        // Search for the "Obtained" pill icon.
        val obtainedPillLoc: Point? = IconObtainedPill.findImageWithBitmap(game.imageUtils, sourceBitmap = entry.bitmap)

        // If neither is found, the entry bitmap is likely invalid or misaligned.
        if (skillUpLoc == null && obtainedPillLoc == null) {
            MessageLog.e(TAG, "[ERROR] onScrollListEntry:: Could not find SkillUp or ObtainedPill in bitmap for entry #${entry.index}.")
            if (game.debugMode) {
                game.imageUtils.saveBitmap(entry.bitmap, "SkillList_${entry.index}")
            }
            return null
        }

        val bIsObtained: Boolean = obtainedPillLoc != null
        // Get the local coordinates relative to the entry's own bitmap.
        val localPoint: Point = skillUpLoc ?: obtainedPillLoc ?: throw IllegalStateException("onScrollListEntry:: SkillUp and ObtainedPill locations are both null.")

        // Refine the bounding box for the skill info region.
        // We use known offsets from the detected button/icon locations to crop precisely.
        val bboxSkillBox =
            if (bIsObtained) {
                BoundingBox(
                    x = (localPoint.x - (SharedData.displayWidth * 0.77)).toInt(),
                    y = (localPoint.y - (SharedData.displayHeight * 0.0599)).toInt(),
                    w = (SharedData.displayWidth * 0.91).toInt(),
                    h = (SharedData.displayHeight * 0.12).toInt(),
                )
            } else {
                BoundingBox(
                    x = (localPoint.x - (SharedData.displayWidth * 0.86)).toInt(),
                    y = (localPoint.y - (SharedData.displayHeight * 0.0583)).toInt(),
                    w = (SharedData.displayWidth * 0.91).toInt(),
                    h = (SharedData.displayHeight * 0.12).toInt(),
                )
            }

        // Crop the refined skill box for analysis.
        val croppedSkillBox = game.imageUtils.createSafeBitmap(entry.bitmap, bboxSkillBox, "bboxSkillBox_${entry.index}")
        if (croppedSkillBox == null) {
            MessageLog.e(TAG, "[ERROR] onScrollListEntry:: createSafeBitmap for skillBoxBitmap returned null.")
            return null
        }
        if (game.debugMode) {
            game.imageUtils.saveBitmap(croppedSkillBox, filename = "bboxSkillBox_${entry.index}")
        }

        // Analyze the entry to extract name, price, and status.
        val skillListEntry: SkillListEntry? = analyzeSkillListEntry(croppedSkillBox, bIsObtained, "${entry.index}", cachedTitle)
        if (skillListEntry == null) {
            MessageLog.e(TAG, "[ERROR] onScrollListEntry:: (${entry.index}) analysis returned null SkillListEntry.")
            return null
        }

        // Translate the local bitmap point back to global screen space coordinates.
        val point = Point(localPoint.x + entry.bbox.x, localPoint.y + entry.bbox.y)

        return Pair(skillListEntry, point)
    }

    /**
     * Parses the entire skill list on the screen to detect all available entries.
     *
     * This function uses a [ScrollList] to iterate through the UI, extracting titles
     * and prices for each visible skill.
     *
     * @param bUseMockData If True, returns predefined mock skill data instead of scanning the screen.
     * @param onEntry Optional callback fired for each detected entry during the scan.
     * @return A mapping of all detected skill names to their [SkillListEntry] objects.
     */
    fun parseSkillListEntries(bUseMockData: Boolean = false, onEntry: OnEntryDetectedCallback? = null): Map<String, SkillListEntry> {
        if (bUseMockData) {
            Log.d(TAG, "[DEBUG] parseSkillListEntries:: Using mock skill list entries.")
            return parseMockSkillListEntries()
        }

        // Initialize the ScrollList helper.
        val list: ScrollList? = ScrollList.create(game)
        if (list == null) {
            MessageLog.e(TAG, "[ERROR] parseSkillListEntries:: Failed to instantiate ScrollList.")
            return emptyMap()
        }

        // Cache titles during the scan to optimize performance.
        val skillTitleMap = mutableMapOf<Int, String>()
        list.process(
            keyExtractor = { entry ->
                val title = getSkillListEntryTitle(entry.bitmap)
                if (title != null) skillTitleMap[entry.index] = title
                title
            },
        ) { _, entry: ScrollListEntry ->
            // Process each entry bitmap found by the ScrollList.
            val res: Pair<SkillListEntry, Point>? = onScrollListEntry(entry, skillTitleMap[entry.index])
            // Fire the callback if provided.
            if (onEntry != null && res != null) onEntry.onEntryDetected(this, res.first, res.second) else false
        }

        return entries
    }

    /**
     * Checks whether the current screen is the [SkillList] screen.
     *
     * @param bitmap Optional [Bitmap] used for detection. If null, a screenshot is taken.
     * @return True if on the [SkillList] screen, False otherwise.
     */
    fun checkSkillListScreen(bitmap: Bitmap? = null): Boolean {
        val srcBitmap: Bitmap = bitmap ?: game.imageUtils.getSourceBitmap()

        // Verify the presence of key UI elements.
        if (ButtonSkillListFullStats.check(game.imageUtils, sourceBitmap = srcBitmap) && LabelSkillListScreenSkillPoints.check(game.imageUtils, sourceBitmap = srcBitmap)) {
            return true
        }

        // Try to handle any blocking dialogs that might be active on this screen.
        if (campaign.handleDialogs() !is DialogHandlerResult.Handled) {
            return false
        }

        // Re-check if we are at the SkillList screen after handling dialogs.
        return (
            ButtonSkillListFullStats.check(game.imageUtils) &&
                LabelSkillListScreenSkillPoints.check(game.imageUtils)
        )
    }

    /**
     * Checks whether the current screen is the [SkillList] screen at the end of career completion.
     *
     * This screen looks identical but might lack certain UI buttons like the message log.
     *
     * @param bitmap Optional [Bitmap] used for detection. If null, a screenshot is taken.
     * @return True if on the career completion [SkillList] screen, False otherwise.
     */
    fun checkCareerCompleteSkillListScreen(bitmap: Bitmap? = null): Boolean {
        val srcBitmap: Bitmap = bitmap ?: game.imageUtils.getSourceBitmap()
        return (!ButtonLog.check(game.imageUtils, sourceBitmap = srcBitmap) && checkSkillListScreen(srcBitmap))
    }

    /**
     * Executes the purchase of a skill.
     *
     * @param name The name of the skill to purchase.
     * @param skillUpButtonLocation The screen location where the [ButtonSkillUp] was detected.
     * @return The updated [SkillListEntry] if successful, or null if name not found or points insufficient.
     */
    fun buySkill(name: String, skillUpButtonLocation: Point): SkillListEntry? {
        val entry: SkillListEntry? = entries[name]
        if (entry == null) {
            MessageLog.w(TAG, "[WARN] buySkill:: Skill \"$name\" not found in initialized entries mapping.")
            return null
        }

        // Check if we have enough points to afford the purchase.
        if (entry.screenPrice > skillPoints) {
            MessageLog.w(TAG, "[WARN] buySkill:: Insufficient skill points (${skillPoints}pt) to buy \"$name\" (${entry.screenPrice}pt).")
            return null
        }

        // Perform the click operation.
        entry.buy(skillUpButtonLocation)
        // Deduct the price from our local tracking of skill points.
        skillPoints -= entry.screenPrice

        return entry
    }

    /** Resets all skill selections in the UI, effectively "selling" any unconfirmed purchases. */
    fun sellAllSkills() {
        for ((_, entry) in getObtainedSkills()) {
            entry.sell()
        }
    }

    /**
     * Retrieves all skills known to the bot.
     *
     * This includes skills that are currently available in the UI as well as virtual skills
     * (not yet detected but known to exist in the database).
     *
     * @return A mapping of skill names to [SkillListEntry] objects.
     */
    fun getAllSkills(): Map<String, SkillListEntry> {
        return entries
    }

    /**
     * Retrieves all skills that are currently available for purchase in the skill list.
     *
     * @return A mapping of available skill names to [SkillListEntry] objects.
     */
    fun getAvailableSkills(): Map<String, SkillListEntry> {
        return entries.filterValues { it.bIsAvailable }
    }

    /**
     * Retrieves all virtual skills (skills not currently present in the UI list).
     *
     * @return A mapping of virtual skill names to [SkillListEntry] objects.
     */
    fun getVirtualSkills(): Map<String, SkillListEntry> {
        return getUnobtainedSkills(includeVirtual = true).filterValues { it.bIsVirtual }
    }

    /**
     * Retrieves all skills that have been successfully purchased.
     *
     * @return A mapping of obtained skill names to [SkillListEntry] objects.
     */
    fun getObtainedSkills(): Map<String, SkillListEntry> {
        return getAllSkills().filterValues { it.bIsObtained }
    }

    /**
     * Retrieves all skills that have not yet been purchased.
     *
     * @param includeVirtual Whether to include virtual skills in the results.
     * @return A mapping of unobtained skill names to [SkillListEntry] objects.
     */
    fun getUnobtainedSkills(includeVirtual: Boolean = false): Map<String, SkillListEntry> {
        val src: Map<String, SkillListEntry> = if (includeVirtual) getAllSkills() else getAvailableSkills()
        return src.filterValues { !it.bIsObtained }
    }

    /**
     * Retrieves all negative (purple) skills.
     *
     * @param includeVirtual Whether to include virtual skills in the results.
     * @return A mapping of negative skill names to [SkillListEntry] objects.
     */
    fun getNegativeSkills(includeVirtual: Boolean = false): Map<String, SkillListEntry> {
        val src: Map<String, SkillListEntry> = if (includeVirtual) getAllSkills() else getAvailableSkills()
        return src.filterValues { it.bIsNegative }
    }

    /**
     * Retrieves all inherited unique skills.
     *
     * @param includeVirtual Whether to include virtual skills in the results.
     * @return A mapping of inherited unique skill names to [SkillListEntry] objects.
     */
    fun getInheritedUniqueSkills(includeVirtual: Boolean = false): Map<String, SkillListEntry> {
        val src: Map<String, SkillListEntry> = if (includeVirtual) getAllSkills() else getAvailableSkills()
        return src.filterValues { it.bIsInheritedUnique }
    }

    /**
     * Retrieves all skills that are not dependent on specific [Trainee] aptitudes.
     *
     * Aptitude-dependent skills are those that only activate for specific [RunningStyle] choices,
     * track distances, or track surfaces (e.g., "Front Runner Savvy ○").
     *
     * @param runningStyle The optional [RunningStyle] to use when filtering out inferred skills.
     * @param includeVirtual Whether to include virtual skills in the results.
     * @return A mapping of aptitude-independent skill names to [SkillListEntry] objects.
     */
    fun getAptitudeIndependentSkills(runningStyle: RunningStyle? = null, includeVirtual: Boolean = false): Map<String, SkillListEntry> {
        val src: Map<String, SkillListEntry> = if (includeVirtual) getAllSkills() else getAvailableSkills()
        val inferredRunningStyleSkills: Map<String, SkillListEntry> = getInferredRunningStyleSkills(runningStyle, includeVirtual)
        return src.filterValues {
            it.runningStyle == null &&
                it.trackDistance == null &&
                it.trackSurface == null &&
                it.name !in inferredRunningStyleSkills
        }
    }

    /**
     * Retrieves all skills restricted to a specific [RunningStyle].
     *
     * @param runningStyle The optional [RunningStyle] to filter by. If null, returns all skills with ANY running style restricted.
     * @param includeVirtual Whether to include virtual skills in the results.
     * @return A mapping of running style restricted skill names to [SkillListEntry] objects.
     */
    fun getRunningStyleSkills(runningStyle: RunningStyle? = null, includeVirtual: Boolean = false): Map<String, SkillListEntry> {
        val src: Map<String, SkillListEntry> = if (includeVirtual) getAllSkills() else getAvailableSkills()
        // If null, then we want to return all skills that have any running style.
        if (runningStyle == null) {
            return src.filterValues { it.runningStyle != null }
        }
        return src.filterValues { it.runningStyle == runningStyle }
    }

    /**
     * Retrieves all skills restricted to a specific [TrackDistance].
     *
     * @param trackDistance The optional [TrackDistance] to filter by. If null, returns all skills with ANY track distance restricted.
     * @param includeVirtual Whether to include virtual skills in the results.
     * @return A mapping of track distance restricted skill names to [SkillListEntry] objects.
     */
    fun getTrackDistanceSkills(trackDistance: TrackDistance? = null, includeVirtual: Boolean = false): Map<String, SkillListEntry> {
        val src: Map<String, SkillListEntry> = if (includeVirtual) getAllSkills() else getAvailableSkills()
        // If null, then we want to return all skills that have any track distance.
        if (trackDistance == null) {
            return src.filterValues { it.trackDistance != null }
        }
        return src.filterValues { it.trackDistance == trackDistance }
    }

    /**
     * Retrieves all skills restricted to a specific [TrackSurface].
     *
     * @param trackSurface The optional [TrackSurface] to filter by. If null, returns all skills with ANY track surface restricted.
     * @param includeVirtual Whether to include virtual skills in the results.
     * @return A mapping of track surface restricted skill names to [SkillListEntry] objects.
     */
    fun getTrackSurfaceSkills(trackSurface: TrackSurface? = null, includeVirtual: Boolean = false): Map<String, SkillListEntry> {
        val src: Map<String, SkillListEntry> = if (includeVirtual) getAllSkills() else getAvailableSkills()
        // If null, then we want to return all skills that have any track surface.
        if (trackSurface == null) {
            return src.filterValues { it.trackSurface != null }
        }
        return src.filterValues { it.trackSurface == trackSurface }
    }

    /**
     * Retrieves all skills that have an inferred [RunningStyle].
     *
     * Inferred running style skills are those that activate based on race positioning (e.g., being in the lead).
     * While technically available to any style, they are primarily useful for specific [RunningStyle] choices.
     *
     * @param runningStyle The optional [RunningStyle] to filter by. If null, returns all skills with ANY inferred style.
     * @param includeVirtual Whether to include virtual skills in the results.
     * @return A mapping of inferred running style skill names to [SkillListEntry] objects.
     */
    fun getInferredRunningStyleSkills(runningStyle: RunningStyle? = null, includeVirtual: Boolean = false): Map<String, SkillListEntry> {
        val src: Map<String, SkillListEntry> = if (includeVirtual) getAllSkills() else getAvailableSkills()

        // Filter out skills that already have an explicit running style restriction.
        val runningStyleSkills: Map<String, SkillListEntry> = getRunningStyleSkills(runningStyle, includeVirtual)

        // If null, then we want to return all skills that have any inferred running style.
        if (runningStyle == null) {
            return src
                .filterValues { it.inferredRunningStyles.isNotEmpty() }
                .filterKeys { it !in runningStyleSkills }
        }
        return src
            .filterValues { runningStyle in it.inferredRunningStyles }
            .filterKeys { it !in runningStyleSkills }
    }

    /**
     * Retrieves all available skills along with their potential virtual upgrades.
     *
     * This expands the available skill list to include virtual entries that represent legitimate
     * next-steps in the upgrade chain for available skills.
     *
     * @return A mapping of skill names to [SkillListEntry] objects.
     */
    fun getAvailableSkillsWithVirtualUpgrades(): Map<String, SkillListEntry> {
        val result: MutableMap<String, SkillListEntry> = getAvailableSkills().toMutableMap()
        val entriesToAdd: MutableMap<String, SkillListEntry> = mutableMapOf()

        for (entry in result.values) {
            val upgrades: List<SkillListEntry> = entry.getUpgrades()
            for (upgrade in upgrades) {
                entriesToAdd[upgrade.name] = upgrade
            }
        }
        return result + entriesToAdd
    }

    /**
     * Retrieves a single [SkillListEntry] by its name.
     *
     * @param name The name of the skill to look up.
     * @return The [SkillListEntry] if found, otherwise null.
     */
    fun getEntry(name: String): SkillListEntry? {
        val result: SkillListEntry? = entries[name]
        if (result == null) {
            MessageLog.w(TAG, "[WARN] getEntry:: No entry found for \"$name\".")
        }
        return result
    }

    /**
     * Prints the details of all skills currently in the list to the [MessageLog].
     *
     * @param skillListEntries Optional custom mapping to print. If null, defaults to available skills.
     * @param verbose If True, prints comprehensive entry details. Otherwise, only names and prices.
     */
    fun printSkillListEntries(skillListEntries: Map<String, SkillListEntry>? = null, verbose: Boolean = false) {
        val entriesToPrint: Map<String, SkillListEntry> = skillListEntries ?: getAvailableSkills()
        MessageLog.v(TAG, "============== Skill List Entries =============")
        for ((name, entry) in entriesToPrint) {
            val entryString: String =
                if (verbose) {
                    "$entry"
                } else {
                    val virtualFlag: String = if (entry.bIsVirtual) " (virtual)" else ""
                    "${entry.price}$virtualFlag"
                }
            MessageLog.v(TAG, "\t$name: $entryString")
        }
        MessageLog.v(TAG, "===============================================")
    }
}
