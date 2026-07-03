package com.steve1316.uma_android_automation.types

import android.graphics.Bitmap
import android.util.Log
import com.steve1316.automation_library.utils.BotService
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.components.ComponentInterface
import com.steve1316.uma_android_automation.components.IconMoodAwful
import com.steve1316.uma_android_automation.components.IconMoodBad
import com.steve1316.uma_android_automation.components.IconMoodGood
import com.steve1316.uma_android_automation.components.IconMoodGreat
import com.steve1316.uma_android_automation.components.IconMoodNormal
import com.steve1316.uma_android_automation.components.LabelStatAptitudeA
import com.steve1316.uma_android_automation.components.LabelStatAptitudeB
import com.steve1316.uma_android_automation.components.LabelStatAptitudeC
import com.steve1316.uma_android_automation.components.LabelStatAptitudeD
import com.steve1316.uma_android_automation.components.LabelStatAptitudeE
import com.steve1316.uma_android_automation.components.LabelStatAptitudeF
import com.steve1316.uma_android_automation.components.LabelStatAptitudeG
import com.steve1316.uma_android_automation.components.LabelStatAptitudeS
import com.steve1316.uma_android_automation.components.LabelStatDistance
import com.steve1316.uma_android_automation.components.LabelStatStyle
import com.steve1316.uma_android_automation.components.LabelStatTrackSurface
import com.steve1316.uma_android_automation.types.Aptitude
import com.steve1316.uma_android_automation.types.FanCountClass
import com.steve1316.uma_android_automation.types.Mood
import com.steve1316.uma_android_automation.types.RunningStyle
import com.steve1316.uma_android_automation.types.StatName
import com.steve1316.uma_android_automation.types.TrackDistance
import com.steve1316.uma_android_automation.types.TrackSurface
import com.steve1316.uma_android_automation.utils.CustomImageUtils
import com.steve1316.uma_scoring.RankResult
import org.opencv.core.Point
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.enums.enumEntries
import kotlin.math.abs

/** Guide-derived stamina-target multipliers by running style. Front Runners need the most stamina to hold the lead, End Closers the least. */
val DEFAULT_RUNNING_STYLE_STAMINA_FACTORS: Map<RunningStyle, Double> =
    mapOf(
        RunningStyle.FRONT_RUNNER to 1.15,
        RunningStyle.PACE_CHASER to 1.0,
        RunningStyle.LATE_SURGER to 0.9,
        RunningStyle.END_CLOSER to 0.85,
    )

/**
 * Scales the stamina entry of a stat-target map by the running-style factor, since required stamina varies sharply by running style at the same distance. Every other stat is left
 * unchanged. A null style (not yet detected) or a neutral (1.0) factor returns the targets untouched, so this is safe to call before the trainee's style is known.
 *
 * @param targets The base per-stat target map.
 * @param style The trainee's running style, or null when not yet detected.
 * @param factors Per-style stamina multipliers. Defaults to [DEFAULT_RUNNING_STYLE_STAMINA_FACTORS].
 * @return The target map with stamina scaled by the style factor, or the original map when no adjustment applies.
 */
fun applyRunningStyleStaminaFactor(
    targets: Map<StatName, Int>,
    style: RunningStyle?,
    factors: Map<RunningStyle, Double> = DEFAULT_RUNNING_STYLE_STAMINA_FACTORS,
): Map<StatName, Int> {
    val factor = factors[style] ?: return targets
    if (factor == 1.0) return targets
    val stamina = targets[StatName.STAMINA] ?: return targets
    return targets + (StatName.STAMINA to (stamina * factor).toInt().coerceAtLeast(1))
}

/**
 * Defines the state and properties of a trainee (Uma Musume).
 *
 * This class serves as the central data structure for tracking a trainee's progress throughout the bot's runtime. It maintains real-time information about stats, aptitudes, fan counts, mood, and
 * status effects.
 */
class Trainee {
    companion object {
        const val TAG: String = "[${MainActivity.loggerTag}]Trainee"

        /** Reference (1080p) width of a single aptitude grade cell crop. */
        const val APTITUDE_CELL_REF_WIDTH: Int = 176

        /** Reference (1080p) height of a single aptitude grade cell crop. */
        const val APTITUDE_CELL_REF_HEIGHT: Int = 52

        /** Mapping of [Aptitude] levels to their corresponding UI label components. */
        val aptitudeComponentMap: Map<Aptitude, ComponentInterface> =
            mapOf(
                Aptitude.A to LabelStatAptitudeA,
                Aptitude.B to LabelStatAptitudeB,
                Aptitude.C to LabelStatAptitudeC,
                Aptitude.D to LabelStatAptitudeD,
                Aptitude.E to LabelStatAptitudeE,
                Aptitude.F to LabelStatAptitudeF,
                Aptitude.G to LabelStatAptitudeG,
                Aptitude.S to LabelStatAptitudeS,
            )

        /** Container for the trainee's five stat values. */
        data class Stats(var speed: Int = -1, var stamina: Int = -1, var power: Int = -1, var guts: Int = -1, var wit: Int = -1) {
            /**
             * Sets a specific stat value using its enum identifier.
             *
             * @param statName The [StatName] to update.
             * @param value The new integer value for the stat.
             */
            fun setStat(statName: StatName, value: Int) {
                when (statName) {
                    StatName.SPEED -> speed = value
                    StatName.STAMINA -> stamina = value
                    StatName.POWER -> power = value
                    StatName.GUTS -> guts = value
                    StatName.WIT -> wit = value
                }
            }

            override fun toString(): String {
                return "Spd=$speed, Sta=$stamina, Pow=$power, Gut=$guts, Wit=$wit"
            }

            /**
             * Returns the stat values as an [IntArray] in a fixed order.
             *
             * @return An array of stat values in order: Speed, Stamina, Power, Guts, Wit.
             */
            fun toIntArray(): IntArray {
                return intArrayOf(speed, stamina, power, guts, wit)
            }

            /**
             * Returns a copy of the current stat values as a [Map].
             *
             * @return A map keyed by [StatName].
             */
            fun asMap(): Map<StatName, Int> {
                return mapOf(
                    StatName.SPEED to speed,
                    StatName.STAMINA to stamina,
                    StatName.POWER to power,
                    StatName.GUTS to guts,
                    StatName.WIT to wit,
                )
            }
        }
    }

    /** The user-defined Classic and Senior milestone percentages for training thresholds (defaults: 33% and 66%). */
    private val classicMilestonePct: Int = SettingsHelper.getIntSetting("training", "classicMilestonePercent", 33)
    private val seniorMilestonePct: Int = SettingsHelper.getIntSetting("training", "seniorMilestonePercent", 66)

    /** The user-defined preferred track distance override from settings. */
    private val preferredDistanceOverride: String = SettingsHelper.getStringSetting("training", "preferredDistanceOverride")

    /** Mapping of [TrackDistance] types to their specific stat target thresholds. */
    private val statTargetsByDistance = mutableMapOf<TrackDistance, Stats>()

    /** The trainee's current stat values (Speed, Stamina, Power, Guts, Wit). */
    val stats: Stats = Stats()

    /** Live per-stat caps OCR'd from the "/NNNN" denominators on the main / training screen (raised by sparks, inheritance, duels, extreme bursts). Only stats with a plausible read appear; the rest fall back to the per-scenario cap table. */
    val statCaps: MutableMap<StatName, Int> = mutableMapOf()

    /** Mapping of [TrackSurface] types to the trainee's [Aptitude]. */
    val trackSurfaceAptitudes: MutableMap<TrackSurface, Aptitude> =
        mutableMapOf(
            TrackSurface.TURF to Aptitude.G,
            TrackSurface.DIRT to Aptitude.G,
        )

    /** Mapping of [TrackDistance] types to the trainee's [Aptitude]. */
    val trackDistanceAptitudes: MutableMap<TrackDistance, Aptitude> =
        mutableMapOf(
            TrackDistance.SPRINT to Aptitude.G,
            TrackDistance.MILE to Aptitude.G,
            TrackDistance.MEDIUM to Aptitude.G,
            TrackDistance.LONG to Aptitude.G,
        )

    /** Mapping of [RunningStyle] types to the trainee's [Aptitude]. */
    val runningStyleAptitudes: MutableMap<RunningStyle, Aptitude> =
        mutableMapOf(
            RunningStyle.FRONT_RUNNER to Aptitude.G,
            RunningStyle.PACE_CHASER to Aptitude.G,
            RunningStyle.LATE_SURGER to Aptitude.G,
            RunningStyle.END_CLOSER to Aptitude.G,
        )

    /** The trainee's current pool of skill points. */
    var skillPoints: Int = 120

    /** Names of skills the trainee currently owns, used to score the estimated rank. Seeded from the Details "Skills" tab and augmented as the bot buys skills. */
    val ownedSkillNames: MutableSet<String> = mutableSetOf()

    /** The trainee's unique-skill level read from the Skills tab, or 0 when unknown. */
    var uniqueSkillLevel: Int = 0

    /** The most recently computed estimated overall rank, or null before the first computation. */
    var estimatedRank: RankResult? = null

    /** The trainee's current total fan count. Starts at 0 until the first fan-count OCR reading. */
    var fans: Int = 0

    /** The trainee's current [Mood] level. */
    var mood: Mood = Mood.NORMAL

    /** The name of the trainee detected from the UI. */
    var name: String = ""

    /** The screen-space location of the track surface label used as an OCR reference point. */
    var statTrackLocation: Point? = null

    /** The list of currently active positive statuses for the trainee. */
    val currentPositiveStatuses = mutableListOf<String>()

    /** The list of currently active negative statuses for the trainee. */
    val currentNegativeStatuses = mutableListOf<String>()

    /** Whether the trainee's aptitudes have been successfully synchronized. */
    var bHasUpdatedAptitudes: Boolean = false

    /** Whether the trainee's current stats have been successfully synchronized. */
    var bHasUpdatedStats: Boolean = false

    /** Whether the current skill point count has been successfully synchronized. */
    var bHasUpdatedSkillPoints: Boolean = false

    /** True if the bot has read running style aptitudes from the race prep screen but hasn't finalized them in the Main Details dialog. */
    var bTemporaryRunningStyleAptitudesUpdated: Boolean = false

    /** Whether the trainee's preferred [RunningStyle] has been locked in on the race prep screen. */
    var bHasSetRunningStyle: Boolean = false

    /** The trainee's approximate energy percentage (0-100). */
    var energy: Int = 100

    /** The remaining duration (in turns) of an active megaphone training item. */
    var megaphoneTurnCounter: Int = 0

    /** The trainee's ranking category ([FanCountClass]) based on their current fan total. */
    var fanCountClass: FanCountClass = FanCountClass.DEBUT

    /** Tracks consecutive mismatches for each stat to recover from OCR misreads. */
    private val mismatchCounts: MutableMap<StatName, Int> = mutableMapOf()

    /** Stores the last recorded mismatch value for verification. */
    private val lastMismatchedValues: MutableMap<StatName, Int> = mutableMapOf()

    /** True once aptitudes, stats, and skill points have all been updated at least once. */
    val bIsInitialized: Boolean
        get() = bHasUpdatedAptitudes && bHasUpdatedStats && bHasUpdatedSkillPoints

    /** True if the trainee has progressed past the maiden race debut. */
    val bHasCompletedMaidenRace: Boolean
        get() = fanCountClass.ordinal > FanCountClass.MAIDEN.ordinal

    /** The trainee's calculated or overridden preferred [TrackSurface]. */
    val trackSurface: TrackSurface
        get() =
            getMaxAptitude<TrackSurface>(
                aptitudeMap = trackSurfaceAptitudes,
                defaultMaxKey = TrackSurface.TURF,
            )

    /** The trainee's calculated or overridden preferred [TrackDistance]. */
    val trackDistance: TrackDistance
        get() =
            TrackDistance.fromName(preferredDistanceOverride) ?: getMaxAptitude<TrackDistance>(
                aptitudeMap = trackDistanceAptitudes,
                defaultMaxKey = TrackDistance.MEDIUM,
            )

    /** The trainee's calculated preferred [RunningStyle]. */
    val runningStyle: RunningStyle
        get() =
            getMaxAptitude<RunningStyle>(
                aptitudeMap = runningStyleAptitudes,
                defaultMaxKey = RunningStyle.FRONT_RUNNER,
            )

    init {
        setStatTargetsByDistances()
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Calculates the highest-priority enum key based on current aptitude levels.
     *
     * This logic determines the "best" fit for a trainee among multiple options. It checks two conditions:
     * 1. The highest [Aptitude] value (e.g., S > A > B).
     * 2. If aptitudes are equal, the key with the lowest ordinal value (highest priority) is selected.
     *
     * This is primarily used to determine the trainee's "preferred" track distance or running style.
     *
     * @param aptitudeMap A mapping of the generic enum [T] to the trainee's current [Aptitude].
     * @param defaultMaxKey Output value if no better aptitudes are found.
     * @return The key [T] representing the trainee's strongest aptitude.
     */
    inline fun <reified T : Enum<T>> getMaxAptitude(aptitudeMap: MutableMap<T, Aptitude>, defaultMaxKey: T): T {
        var maxKey = defaultMaxKey
        var maxVal: Aptitude = Aptitude.G

        for ((key, aptitude) in aptitudeMap) {
            if (aptitude > maxVal) {
                // Select the key if its aptitude is strictly higher.
                maxKey = key
                maxVal = aptitude
            } else if (aptitude == maxVal && key < maxKey) {
                // If aptitudes match, select the key with higher internal priority (lower ordinal).
                maxKey = key
                maxVal = aptitude
            }
        }

        return maxKey
    }

    /**
     * Retrieves a specific stat value using its enum identifier.
     *
     * @param statName The [StatName] to retrieve.
     * @return The current integer value of the specified stat.
     */
    fun getStat(statName: StatName): Int {
        return when (statName) {
            StatName.SPEED -> stats.speed
            StatName.STAMINA -> stats.stamina
            StatName.POWER -> stats.power
            StatName.GUTS -> stats.guts
            StatName.WIT -> stats.wit
        }
    }

    /**
     * Retrieves the target stat thresholds for a specific race distance.
     *
     * These targets are used by the bot to prioritize training sessions.
     *
     * @param distance The [TrackDistance] to query. If null, the trainee's [trackDistance] (preferred distance) is used as the default.
     * @return A map of [StatName] to their desired target values.
     */
    fun getStatTargetsByDistance(distance: TrackDistance? = null): Map<StatName, Int> {
        // If distance is null, we want to use the calculated preferred track distance.
        val distance: TrackDistance = distance ?: trackDistance

        // Return a default set of stat targets if the distance does not exist in the mapping.
        if (distance !in statTargetsByDistance) {
            return mapOf(
                StatName.SPEED to 600,
                StatName.STAMINA to 600,
                StatName.POWER to 600,
                StatName.GUTS to 300,
                StatName.WIT to 300,
            )
        }

        return statTargetsByDistance[distance]!!.asMap()
    }

    /**
     * Retrieves stat targets scaled to the appropriate training year milestone.
     *
     * Instead of targeting the full end-of-game statline in all three years, this method
     * returns a phase-scaled subset so the bot paces itself across the three training years:
     * - Junior Year:  [classicMilestonePercent]% of the primary target (default 33%)
     * - Classic Year: [seniorMilestonePercent]% of the primary target (default 66%)
     * - Senior Year:  100% (the full primary target, unchanged)
     *
     * The milestone percentages are user-configurable via the "scenarioOverrides" settings.
     *
     * @param year The current [DateYear] to determine which milestone to apply.
     * @param distance The [TrackDistance] to query. If null, uses the trainee's preferred distance.
     * @return A map of [StatName] to their milestone-scaled target values for the current year.
     */
    fun getPhaseStatTargets(year: DateYear, distance: TrackDistance? = null): Map<StatName, Int> {
        val primary = getStatTargetsByDistance(distance)

        val multiplier: Double =
            when (year) {
                DateYear.JUNIOR -> classicMilestonePct / 100.0
                DateYear.CLASSIC -> seniorMilestonePct / 100.0
                DateYear.SENIOR -> 1.0
            }

        // Senior (multiplier == 1.0) returns the primary map untouched.
        return if (multiplier == 1.0) {
            primary
        } else {
            primary.mapValues { (_, target) -> (target * multiplier).toInt().coerceAtLeast(1) }
        }
    }

    /**
     * Updates the trainee's stats with the provided values.
     *
     * Values are only updated if they are not null. This is useful for partial updates from OCR results.
     *
     * @param speed New Speed value, or null to skip.
     * @param stamina New Stamina value, or null to skip.
     * @param power New Power value, or null to skip.
     * @param guts New Guts value, or null to skip.
     * @param wit New Wit value, or null to skip.
     */
    fun setTraineeStats(speed: Int? = null, stamina: Int? = null, power: Int? = null, guts: Int? = null, wit: Int? = null) {
        if (speed != null) {
            stats.speed = speed
        }
        if (stamina != null) {
            stats.stamina = stamina
        }
        if (power != null) {
            stats.power = power
        }
        if (guts != null) {
            stats.guts = guts
        }
        if (wit != null) {
            stats.wit = wit
        }
    }

    /**
     * Sets the trainee's aptitude for a specific running style.
     *
     * @param runningStyle The running style to set the aptitude for.
     * @param aptitude The aptitude value to assign.
     */
    fun setRunningStyleAptitude(runningStyle: RunningStyle, aptitude: Aptitude) {
        runningStyleAptitudes[runningStyle] = aptitude
    }

    /**
     * Returns the trainee's aptitude for a specified [TrackSurface].
     *
     * @param trackSurface The track surface to check the aptitude for.
     * @return The aptitude value for the specified track surface.
     */
    fun checkTrackSurfaceAptitude(trackSurface: TrackSurface): Aptitude {
        return trackSurfaceAptitudes[trackSurface] ?: Aptitude.G
    }

    /**
     * Returns the trainee's aptitude for a specified [TrackDistance].
     *
     * @param trackDistance The track distance to check the aptitude for.
     * @return The aptitude value for the specified track distance.
     */
    fun checkTrackDistanceAptitude(trackDistance: TrackDistance): Aptitude {
        return trackDistanceAptitudes[trackDistance] ?: Aptitude.G
    }

    /**
     * Returns the trainee's aptitude for a specified [RunningStyle].
     *
     * @param runningStyle The running style to check the aptitude for.
     * @return The aptitude value for the specified running style.
     */
    fun checkRunningStyleAptitude(runningStyle: RunningStyle): Aptitude {
        return runningStyleAptitudes[runningStyle] ?: Aptitude.G
    }

    /**
     * Detects the trainee's aptitudes for a specific category by scanning the screen.
     *
     * This logic performs a template check across a horizontal row in the Details dialog. The row is identified using the provided [label] coordinate.
     *
     * @param imageUtils Reference to the image processing utility.
     * @param label The [ComponentInterface] representing the row label (e.g., [LabelStatDistance]).
     * @return A map linking the enum type [T] to its detected [Aptitude], or null if the label couldn't be found.
     */
    inline fun <reified T : Enum<T>> findAptitudesInBitmap(imageUtils: CustomImageUtils, label: ComponentInterface): Map<T, Aptitude>? {
        val result = mutableMapOf<T, Aptitude>()

        val bitmap: Bitmap = imageUtils.getSourceBitmap()
        val point: Point? = label.find(imageUtils = imageUtils).first
        if (point == null) {
            MessageLog.e(TAG, "[ERROR] findAptitudesInBitmap:: point is null.")
            return null
        }

        enumEntries<T>().forEachIndexed { index, option ->
            // Calculate the horizontal offset for each potential choice in the row.
            // Choices are spaced 190 pixels apart relative to the starting point.
            val croppedBitmap: Bitmap? =
                imageUtils.createSafeBitmap(
                    bitmap,
                    imageUtils.relX(point.x, 108 + (index * 190)),
                    imageUtils.relY(point.y, -25),
                    imageUtils.relWidth(APTITUDE_CELL_REF_WIDTH),
                    imageUtils.relHeight(APTITUDE_CELL_REF_HEIGHT),
                    "findAptitudesInBitmap:: crop bitmap.",
                )
            if (croppedBitmap == null) {
                MessageLog.e(TAG, "[ERROR] findAptitudesInBitmap:: Failed to create cropped bitmap: $option.")
                return@forEachIndexed
            }

            // Score every letter template and take the best match rather than the first to clear the floor, so a lower-ranked letter (e.g. B) can't shadow the true grade (e.g. D or E).
            val best: Aptitude? = imageUtils.findBestTemplateMatch(croppedBitmap, aptitudeComponentMap, APTITUDE_CELL_REF_WIDTH, APTITUDE_CELL_REF_HEIGHT)
            if (best != null) {
                result[option] = best
            }
        }

        return result.toMap()
    }

    /**
     * Updates the trainee's track surface aptitudes from the current screen.
     *
     * @param imageUtils A reference to a [CustomImageUtils] instance.
     */
    private fun updateTrackSurfaceAptitudes(imageUtils: CustomImageUtils) {
        val aptitudes = findAptitudesInBitmap<TrackSurface>(imageUtils = imageUtils, label = LabelStatTrackSurface)

        // Cache the location of the label for use with readName().
        if (statTrackLocation == null) {
            statTrackLocation = LabelStatTrackSurface.find(imageUtils = imageUtils).first
        }

        if (aptitudes == null) {
            return
        }

        for ((key, value) in aptitudes) {
            trackSurfaceAptitudes[key] = value
        }
    }

    /**
     * Updates the trainee's track distance aptitudes from the current screen.
     *
     * @param imageUtils A reference to a [CustomImageUtils] instance.
     */
    private fun updateTrackDistanceAptitudes(imageUtils: CustomImageUtils) {
        val aptitudes = findAptitudesInBitmap<TrackDistance>(imageUtils = imageUtils, label = LabelStatDistance) ?: return
        for ((key, value) in aptitudes) {
            trackDistanceAptitudes[key] = value
        }
    }

    /**
     * Updates the trainee's running style aptitudes from the current screen.
     *
     * @param imageUtils A reference to a [CustomImageUtils] instance.
     */
    private fun updateRunningStyleAptitudes(imageUtils: CustomImageUtils) {
        val aptitudes = findAptitudesInBitmap<RunningStyle>(imageUtils = imageUtils, label = LabelStatStyle) ?: return
        for ((key, value) in aptitudes) {
            runningStyleAptitudes[key] = value
        }
    }

    /**
     * Updates all aptitudes for trainee.
     *
     * Requires the Umamusume Details dialog to be opened.
     *
     * @param imageUtils A reference to a [CustomImageUtils] instance.
     */
    fun updateAptitudes(imageUtils: CustomImageUtils) {
        updateTrackSurfaceAptitudes(imageUtils = imageUtils)
        updateTrackDistanceAptitudes(imageUtils = imageUtils)
        updateRunningStyleAptitudes(imageUtils = imageUtils)

        bHasUpdatedAptitudes = true
    }

    /**
     * Replaces the trainee's active conditions with a freshly-read set and logs them.
     *
     * @param positive The positive condition names read from the Conditions sublist.
     * @param negative The negative condition names read from the Conditions sublist.
     */
    fun setConditions(positive: List<String>, negative: List<String>) {
        currentPositiveStatuses.clear()
        currentPositiveStatuses.addAll(positive)
        currentNegativeStatuses.clear()
        currentNegativeStatuses.addAll(negative)
        if (currentPositiveStatuses.isNotEmpty()) MessageLog.v(TAG, "[TRAINEE] Positive Statuses: ${currentPositiveStatuses.joinToString(", ")}")
        if (currentNegativeStatuses.isNotEmpty()) MessageLog.v(TAG, "[TRAINEE] Negative Statuses: ${currentNegativeStatuses.joinToString(", ")}")
    }

    /**
     * Returns true when [name] fuzzy-matches any active positive condition. Tolerant of OCR noise since reads are stored raw.
     *
     * @param name The canonical positive condition name to look for.
     * @return True when a raw positive read matches at the query threshold.
     */
    fun hasPositiveStatus(name: String): Boolean = fuzzyMatchesAny(name, currentPositiveStatuses, STATUS_QUERY_THRESHOLD)

    /**
     * Returns true when [name] fuzzy-matches any active negative condition. Tolerant of OCR noise since reads are stored raw.
     *
     * @param name The canonical negative condition name to look for.
     * @return True when a raw negative read matches at the query threshold.
     */
    fun hasNegativeStatus(name: String): Boolean = fuzzyMatchesAny(name, currentNegativeStatuses, STATUS_QUERY_THRESHOLD)

    /**
     * Reads the trainee's name from the Umamusume Details dialog using color-filtered OCR.
     *
     * The name text uses a uniform color #794016 (Brown-ish). This method uses [LabelStatTrackSurface] as a dynamic reference point to calculate the name's position on the screen.
     *
     * If successful, it also updates the [MessageLog]'s file name prefix so logs can be categorized by trainee.
     *
     * @param imageUtils Reference to a [CustomImageUtils] instance.
     */
    fun readName(imageUtils: CustomImageUtils) {
        val sourceBitmap = imageUtils.getSourceBitmap()

        // Extract reference point coordinates from cached location or find it if not available.
        val refPoint = statTrackLocation ?: LabelStatTrackSurface.find(imageUtils = imageUtils).first
        if (refPoint == null) {
            name = "null"
            return
        }

        // Extract the coordinates from the reference point and cache the location.
        val refX = refPoint.x.toDouble()
        val refY = refPoint.y.toDouble()
        if (statTrackLocation == null && refPoint != null) {
            statTrackLocation = refPoint
        }

        // Calculate name position relative to the reference point.
        val nameX = refX + imageUtils.relWidth(385)
        val nameY = refY - imageUtils.relHeight(370)
        val nameWidth = imageUtils.relWidth(335)
        val nameHeight = imageUtils.relHeight(50)

        // Use color-filtered OCR with the target text color #794016.
        val detectedName =
            imageUtils.findTextByColor(
                sourceBitmap = sourceBitmap,
                x = nameX.toInt(),
                y = nameY.toInt(),
                width = nameWidth,
                height = nameHeight,
                targetR = 121,
                targetG = 64,
                targetB = 22,
                debugName = "trainee_name",
            )

        if (detectedName.isNotEmpty()) {
            name = detectedName
            MessageLog.i(TAG, "[TRAINEE] Name: $name")

            // Set the log file name prefix to the trainee name with spaces replaced by underscores.
            // This is done to differentiate which logs belong to which trainee.
            MessageLog.logFileNamePrefix = name.replace(" ", "_")
        } else {
            MessageLog.w(TAG, "[WARN] readName:: Could not detect Trainee name from the aptitude dialog.")
        }
    }

    /**
     * Updates the trainee's skill points from the current screen.
     *
     * @param imageUtils A reference to a [CustomImageUtils] instance.
     * @param sourceBitmap Optional pre-captured bitmap to analyze.
     * @param skillPointsLocation Optional pre-determined location of skill points on screen.
     */
    fun updateSkillPoints(imageUtils: CustomImageUtils, sourceBitmap: Bitmap? = null, skillPointsLocation: Point? = null) {
        val res = imageUtils.determineSkillPoints(sourceBitmap, skillPointsLocation)
        if (res != -1) {
            skillPoints = res
        }

        bHasUpdatedSkillPoints = skillPoints != -1
    }

    /**
     * Applies one OCR-read stat value through the consistency-verification guard shared by the parallel and sequential update paths.
     *
     * @param statName The stat being updated.
     * @param newValue The newly OCR-read value for the stat.
     * @param viaSequential True when called from the sequential fallback, which annotates the log messages accordingly.
     */
    private fun applyStatUpdate(statName: StatName, newValue: Int, viaSequential: Boolean) {
        val via = if (viaSequential) " via sequential processing" else ""

        val oldValue = getStat(statName)
        val diff = abs(newValue - oldValue)

        // Reject updates that vary too wildly unless the previous value was unset (<= 0).
        if (oldValue <= 0 || diff < 150) {
            stats.setStat(statName, newValue)
            bHasUpdatedStats = true

            // Reset mismatch tracking for this stat.
            mismatchCounts[statName] = 0
            lastMismatchedValues[statName] = -1
        } else {
            // Start or continue a verification count if the OCR result is consistent but different.
            val lastMismatchedValue = lastMismatchedValues[statName] ?: -1
            val mismatchDiff = abs(newValue - lastMismatchedValue)
            val currentCount = mismatchCounts[statName] ?: 0

            if (mismatchDiff < 50) {
                val newCount = currentCount + 1
                mismatchCounts[statName] = newCount

                // If the "incorrect" value is detected multiple times, assume the previous
                // recorded value was the actual misread and update to the new one.
                if (newCount >= 2) {
                    MessageLog.d(TAG, "[DEBUG] updateStats:: New $statName stat value has been consistent for $newCount updates$via. Trusting the new value: $newValue (was $oldValue)")
                    stats.setStat(statName, newValue)
                    bHasUpdatedStats = true
                    mismatchCounts[statName] = 0
                    lastMismatchedValues[statName] = -1
                } else {
                    MessageLog.w(
                        TAG,
                        "[WARN] updateStats:: New $statName stat value has changed too much since last update (old=$oldValue, new=$newValue)$via. Consecutive mismatch count: $newCount",
                    )
                }
            } else {
                // The mismatch itself is inconsistent, so reset the counter.
                mismatchCounts[statName] = 1
                lastMismatchedValues[statName] = newValue
                MessageLog.w(TAG, "[WARN] updateStats:: New $statName stat value has changed too much since last update (old=$oldValue, new=$newValue)$via. Resetting mismatch count.")
            }
        }
    }

    /**
     * Updates the trainee's stats by performing OCR on the screen.
     *
     * To prevent "jumping" to incorrect values due to OCR misreads, this method implements a verification process:
     * 1. If a new value differs from the old one by >150, it is flagged as a potential error and rejected initially.
     * 2. The bot tracks consecutive "mismatches" for that stat.
     * 3. If the "mismatched" value remains consistent across multiple updates, the bot recovers by trusting the new value (assuming the previous one was the actual error).
     *
     * @param imageUtils Reference to a [CustomImageUtils] instance.
     * @param sourceBitmap Optional bitmap to use (enables parallel processing).
     * @param skillPointsLocation Reference coordinate for parallel stat sub-crops.
     * @param externalLatch Latch for thread synchronization.
     * @param isAptitudeDialog True if reading from the Umamusume Details dialog instead of the Training screen.
     */
    fun updateStats(imageUtils: CustomImageUtils, sourceBitmap: Bitmap? = null, skillPointsLocation: Point? = null, externalLatch: CountDownLatch? = null, isAptitudeDialog: Boolean = false) {
        // If sourceBitmap and skillPointsLocation are provided, use threading for parallel processing.
        if (sourceBitmap != null && skillPointsLocation != null) {
            val statLatch = externalLatch ?: CountDownLatch(5)
            val waitLatch = CountDownLatch(5) // Internal latch for waiting, regardless of external latch.
            val threadSafeResults = ConcurrentHashMap<StatName, Int>()

            // Create 5 threads, one for each stat.
            for (statName in StatName.entries) {
                Thread {
                    try {
                        if (!BotService.isRunning) {
                            return@Thread
                        }
                        val statValue = imageUtils.determineSingleStatValue(statName, sourceBitmap, skillPointsLocation, isAptitudeDialog)
                        threadSafeResults[statName] = statValue
                    } catch (e: Exception) {
                        Log.e(TAG, "[ERROR] updateStats:: Error processing stat $statName: ${e.stackTraceToString()}")
                        threadSafeResults[statName] = -1
                    } finally {
                        statLatch.countDown()
                        waitLatch.countDown()
                    }
                }.apply { isDaemon = true }.start()
            }

            // Wait for all threads to complete using the internal wait latch.
            try {
                waitLatch.await(10, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                MessageLog.e(TAG, "[ERROR] updateStats:: Stat processing timed out.")
            }

            // Update stats with thread-safe results.
            val statMapping = threadSafeResults.toMap()
            for ((statName, newValue) in statMapping) {
                applyStatUpdate(statName, newValue, viaSequential = false)
            }
        } else {
            // Sequential processing (fallback).
            val statMapping: Map<StatName, Int> =
                imageUtils.determineStatValues(
                    sourceBitmap = null,
                    skillPointsLocation = skillPointsLocation,
                    isAptitudeDialog = isAptitudeDialog,
                )

            for ((statName, newValue) in statMapping) {
                applyStatUpdate(statName, newValue, viaSequential = true)
            }
        }
    }

    /**
     * Detects the trainee's current mood from the screen.
     *
     * @param imageUtils Reference to a [CustomImageUtils] instance.
     * @param sourceBitmap Optional pre-captured bitmap to analyze (enables thread-safety).
     * @return The detected [Mood], or null if no mood could be determined.
     */
    fun checkMood(imageUtils: CustomImageUtils, sourceBitmap: Bitmap? = null): Mood? {
        return if (sourceBitmap != null) {
            // Use findImageWithBitmap for thread-safe operations on the provided bitmap.
            when {
                imageUtils.findImageWithBitmap(IconMoodAwful.template.path, sourceBitmap, region = IconMoodAwful.template.region, suppressError = true) != null -> Mood.AWFUL
                imageUtils.findImageWithBitmap(IconMoodBad.template.path, sourceBitmap, region = IconMoodBad.template.region, suppressError = true) != null -> Mood.BAD
                imageUtils.findImageWithBitmap(IconMoodNormal.template.path, sourceBitmap, region = IconMoodNormal.template.region, suppressError = true) != null -> Mood.NORMAL
                imageUtils.findImageWithBitmap(IconMoodGood.template.path, sourceBitmap, region = IconMoodGood.template.region, suppressError = true) != null -> Mood.GOOD
                imageUtils.findImageWithBitmap(IconMoodGreat.template.path, sourceBitmap, region = IconMoodGreat.template.region, suppressError = true) != null -> Mood.GREAT
                else -> null
            }
        } else {
            // Use the sequential check method (non-thread-safe fallback).
            when {
                IconMoodAwful.check(imageUtils = imageUtils) -> Mood.AWFUL
                IconMoodBad.check(imageUtils = imageUtils) -> Mood.BAD
                IconMoodNormal.check(imageUtils = imageUtils) -> Mood.NORMAL
                IconMoodGood.check(imageUtils = imageUtils) -> Mood.GOOD
                IconMoodGreat.check(imageUtils = imageUtils) -> Mood.GREAT
                else -> null
            }
        }
    }

    /**
     * Read the live per-stat caps from the "/NNNN" denominators on the main / training screen and store the plausible ones in [statCaps]. A failed read keeps the previous cap for
     * that stat, so a transient OCR miss does not drop a good value. Caps are not shown on the aptitude dialog, so this is a main-screen read. Runs sequentially (five small reads)
     * as one parallel task within `performTurnStartUpdates`.
     *
     * @param imageUtils Reference to a [CustomImageUtils] instance.
     * @param sourceBitmap The shared main-screen bitmap.
     * @param skillPointsLocation The pre-found skill-points label location used as the OCR anchor.
     */
    fun updateStatCaps(imageUtils: CustomImageUtils, sourceBitmap: Bitmap? = null, skillPointsLocation: Point? = null) {
        if (sourceBitmap == null || skillPointsLocation == null) return
        for (statName in StatName.entries) {
            if (!BotService.isRunning) return
            val cap = imageUtils.determineSingleStatCap(statName, sourceBitmap, skillPointsLocation)
            if (cap > 0) statCaps[statName] = cap
        }
    }

    /**
     * Updates the trainee's [mood] state from the current screen.
     *
     * If no mood can be detected, the current state remains unchanged.
     *
     * @param imageUtils Reference to a [CustomImageUtils] instance.
     * @param sourceBitmap Optional pre-captured bitmap to analyze.
     */
    fun updateMood(imageUtils: CustomImageUtils, sourceBitmap: Bitmap? = null) {
        // If checkMood returns null, then make no change to the mood state.
        mood = checkMood(imageUtils, sourceBitmap) ?: mood
    }

    /**
     * Updates the trainee's approximate [energy] level from the current screen.
     *
     * @param imageUtils Reference to a [CustomImageUtils] instance.
     */
    fun updateEnergy(imageUtils: CustomImageUtils) {
        val res = imageUtils.analyzeEnergyBar()
        if (res != null) {
            energy = res
        }
    }

    /**
     * Sets up stat targets for different race distances by reading values from SQLite settings.
     *
     * These targets are used to determine training priorities based on the expected race distance of the current campaign goal.
     */
    fun setStatTargetsByDistances() {
        for (trackDistance in TrackDistance.entries) {
            val newStats = Stats()
            for (statName in StatName.entries) {
                val statNameString = statName.name.lowercase()
                val trackDistanceString = trackDistance.name.lowercase().replaceFirstChar { it.uppercase() }
                val target: Int = SettingsHelper.getIntSetting("trainingStatTarget", "training${trackDistanceString}StatTarget_${statNameString}StatTarget")
                newStats.setStat(statName, target)
            }
            statTargetsByDistance[trackDistance] = newStats
        }
    }

    /** Logs the trainee's current state in a structured format for the Remote Log Viewer dashboard. */
    fun logInfo() {
        if (name.isNotEmpty()) {
            MessageLog.v(TAG, "[TRAINEE] Name: $name")
        }
        MessageLog.v(TAG, "[TRAINEE] Stats: $stats")
        MessageLog.v(TAG, "[TRAINEE] Energy: $energy%")
        MessageLog.v(TAG, "[TRAINEE] Mood: ${mood.name}")
        MessageLog.v(TAG, "[TRAINEE] Fans: $fans")
        MessageLog.v(TAG, "[TRAINEE] Skill Points: $skillPoints")
        estimatedRank?.let { MessageLog.v(TAG, "[TRAINEE] Estimated Rank: ${it.rankLabel} (${it.totalScore})") }
        val trackString = "Turf=${trackSurfaceAptitudes[TrackSurface.TURF]}, Dirt=${trackSurfaceAptitudes[TrackSurface.DIRT]}"
        val distanceString =
            "Sprint=${trackDistanceAptitudes[TrackDistance.SPRINT]}, Mile=${trackDistanceAptitudes[TrackDistance.MILE]}, Medium=${trackDistanceAptitudes[TrackDistance.MEDIUM]}, Long=${trackDistanceAptitudes[TrackDistance.LONG]}"
        val styleString =
            "Front=${runningStyleAptitudes[RunningStyle.FRONT_RUNNER]}, Pace=${runningStyleAptitudes[RunningStyle.PACE_CHASER]}, Late=${runningStyleAptitudes[RunningStyle.LATE_SURGER]}, End=${runningStyleAptitudes[RunningStyle.END_CLOSER]}"
        MessageLog.v(TAG, "[TRAINEE] Track: $trackString")
        MessageLog.v(TAG, "[TRAINEE] Distance: $distanceString")
        MessageLog.v(TAG, "[TRAINEE] Style: $styleString")
        if (currentPositiveStatuses.isNotEmpty()) {
            MessageLog.v(TAG, "[TRAINEE] Positive Statuses: ${currentPositiveStatuses.joinToString(", ")}")
        }
        if (currentNegativeStatuses.isNotEmpty()) {
            MessageLog.v(TAG, "[TRAINEE] Negative Statuses: ${currentNegativeStatuses.joinToString(", ")}")
        }
    }

    override fun toString(): String {
        val aptitudesString = "TrackSurface: $trackSurface\nTrackDistance: $trackDistance\nRunningStyle: $runningStyle"
        val statsString = stats.toString()
        return "Aptitudes: $aptitudesString" +
            "\nStats: $statsString" +
            "\nSkill Points: $skillPoints" +
            "\nMood: $mood" +
            "\nEnergy: $energy" +
            "\nFans: $fans" +
            "\nFanCountClass: $fanCountClass" +
            "\nMegaphone turns remaining: $megaphoneTurnCounter"
    }
}
