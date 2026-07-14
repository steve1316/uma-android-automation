package com.steve1316.uma_android_automation.bot

import android.graphics.Bitmap
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Campaign
import com.steve1316.uma_android_automation.types.RunningStyle
import com.steve1316.uma_android_automation.types.SkillList
import com.steve1316.uma_android_automation.types.SkillListEntry
import com.steve1316.uma_android_automation.types.SkillType
import com.steve1316.uma_android_automation.types.TrackDistance
import com.steve1316.uma_android_automation.types.TrackSurface
import org.json.JSONObject
import org.opencv.core.Point

private const val USE_MOCK_DATA: Boolean = false
private const val MOCK_SKILL_POINTS: Int = 1495

/**
 * Handle operations based on the user's Skill Plan Settings.
 *
 * @property game The [Game] instance used for bot interaction.
 * @property campaign The [Campaign] instance currently being automated.
 */
class SkillPlan(private val game: Game, private val campaign: Campaign) {
    /** The preferred running style from settings. */
    val skillSettingRunningStyleString = SettingsHelper.getStringSetting("skills", "preferredRunningStyle")

    /** The preferred track distance from settings. */
    val skillSettingTrackDistanceString = SettingsHelper.getStringSetting("skills", "preferredTrackDistance")

    /** The preferred track surface from settings. */
    val skillSettingTrackSurfaceString = SettingsHelper.getStringSetting("skills", "preferredTrackSurface")

    /** Whether to nudge recovery skills up the ranking on stamina-heavy (Medium/Long) builds. Default on. */
    private val prioritizeRecoveryForStamina: Boolean = SettingsHelper.getBooleanSetting("skills", "prioritizeRecoveryForStamina", true)

    /** The preferred track distance override for training. */
    private val trainingSettingTrackDistanceString = SettingsHelper.getStringSetting("training", "preferredDistanceOverride")

    /** The original race strategy from settings. */
    private val racingSettingRunningStyleString = SettingsHelper.getStringSetting("racing", "originalRaceStrategy")

    /** Map of skill plan names to their corresponding settings. */
    val skillPlans: Map<String, SkillPlanSettings> =
        try {
            val plansString = SettingsHelper.getStringSetting("skills", "plans")
            if (plansString.isNotEmpty()) {
                val jsonObject = JSONObject(plansString)
                val plansMap = mutableMapOf<String, SkillPlanSettings>()
                jsonObject.keys().forEach { planName ->
                    val planData = jsonObject.getJSONObject(planName)
                    val strategyString: String = planData.getString("strategy")
                    val skillIds: List<Int> =
                        planData
                            .getString("plan")
                            .split(",")
                            .map { it.trim() }
                            .mapNotNull { it.toIntOrNull() }
                    val skillNames: List<String> = skillIds.mapNotNull { game.skillDatabase.getSkillName(it) }
                    val blacklistIds: List<Int> =
                        planData
                            .optString("blacklist", "")
                            .split(",")
                            .map { it.trim() }
                            .mapNotNull { it.toIntOrNull() }
                    val skillBlacklist: List<String> = blacklistIds.mapNotNull { game.skillDatabase.getSkillName(it) }
                    val excludedTypes: Set<SkillType> =
                        buildSet {
                            if (planData.optBoolean("excludeGreenSkills", false)) add(SkillType.GREEN)
                            if (planData.optBoolean("excludeRedSkills", false)) add(SkillType.RED)
                        }
                    plansMap[planName] =
                        SkillPlanSettings(
                            bIsEnabled = planData.getBoolean("enabled"),
                            strategy = SpendingStrategy.fromName(strategyString) ?: SpendingStrategy.DEFAULT,
                            bEnableBuyNegativeSkills = planData.getBoolean("enableBuyNegativeSkills"),
                            skillNames = skillNames,
                            skillBlacklist = skillBlacklist,
                            excludedTypes = excludedTypes,
                            bExcludeUniqueSkills = planData.optBoolean("excludeUniqueSkills", false),
                            bExcludeDoubleCircleSkills = planData.optBoolean("excludeDoubleCircleSkills", false),
                        )
                }
                plansMap
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            MessageLog.w(TAG, "[WARN] skillPlans:: Could not parse skill plan settings: ${e.message}")
            emptyMap()
        }

    /** The strategy used for spending skill points. */
    enum class SpendingStrategy {
        /**
         * Default spending strategy. Buys nothing beyond the common checks, so points left after the user's planned skills are kept.
         *
         * This is overridden on the final purchase of a career, where the leftover drain spends the remainder regardless. See [getLeftoverDrainSkills].
         */
        DEFAULT,

        /** Prioritize skills that match the trainee's aptitudes and community-tier rankings. */
        OPTIMIZE_SKILLS,

        /** Prioritize skills that offer the best rank increase per point spent. */
        OPTIMIZE_RANK,

        ;

        companion object {
            private val nameMap = entries.associateBy { it.name }
            private val ordinalMap = entries.associateBy { it.ordinal }

            /** Retrieve the [SpendingStrategy] by its name. */
            fun fromName(value: String): SpendingStrategy? = nameMap[value.uppercase()]

            /** Retrieve the [SpendingStrategy] by its ordinal value. */
            fun fromOrdinal(ordinal: Int): SpendingStrategy? = ordinalMap[ordinal]
        }
    }

    /**
     * Encapsulates the configuration for a specific skill plan.
     *
     * @property bIsEnabled Whether the skill plan is active.
     * @property strategy The [SpendingStrategy] to follow.
     * @property bEnableBuyNegativeSkills Whether to purchase negative (blue) skills.
     * @property skillNames The list of specific skill names to purchase as part of this plan.
     * @property skillBlacklist Names of skills to exclude from purchase regardless of strategy.
     * @property excludedTypes Skill type categories (GREEN / YELLOW / BLUE / RED) to exclude wholesale.
     * @property bExcludeUniqueSkills Whether to exclude all inherited unique skills from purchase, even if listed in the plan.
     * @property bExcludeDoubleCircleSkills Whether to skip double-circle (double-O) skills in the auto-strategy. Planned ones are still bought.
     */
    data class SkillPlanSettings(
        val bIsEnabled: Boolean,
        val strategy: SpendingStrategy,
        val bEnableBuyNegativeSkills: Boolean,
        val skillNames: List<String>,
        val skillBlacklist: List<String> = emptyList(),
        val excludedTypes: Set<SkillType> = emptySet(),
        val bExcludeUniqueSkills: Boolean = false,
        val bExcludeDoubleCircleSkills: Boolean = false,
    )

    companion object {
        private val TAG: String = "[${MainActivity.loggerTag}]SkillPlan"

        /** The double-circle (double-O) marker found in double-circle skill names. */
        private const val DOUBLE_CIRCLE_CHAR: Char = '\u25CE'

        /** The name of the skill plan that runs on the final purchase of a career, once the run is over. */
        private const val PLAN_CAREER_COMPLETE: String = "careerComplete"

        /** Upper bound on the budget the leftover drain will run its knapsack over. Real skill point totals never come close, so a larger value means OCR returned garbage. */
        private const val MAX_DRAIN_BUDGET: Int = 10000

        /** How many times the leftover drain re-runs. Buying a skill can lower an upgrade's price or reveal a new row, which frees up budget for another pass. */
        private const val MAX_DRAIN_ITERATIONS: Int = 5

        /**
         * Whether a skill is a recovery skill. Recovery skills describe restoring stamina/endurance, so their description contains "recover". Data-driven off the skill description, not a
         * fixed name list, so new recovery skills are caught automatically. Pure and unit-testable.
         *
         * @param description The skill's description text.
         * @return True when the skill recovers stamina/endurance.
         */
        fun isRecoverySkill(description: String): Boolean = description.contains("recover", ignoreCase = true)

        /**
         * Whether the build is stamina-heavy, meaning recovery skills carry more value. Medium and Long distances lean on stamina and recovery far more than Sprint or Mile. Pure and testable.
         *
         * @param distance The resolved preferred track distance, or null for no preference.
         * @return True for Medium or Long builds.
         */
        fun isStaminaHeavyDistance(distance: TrackDistance?): Boolean = distance == TrackDistance.MEDIUM || distance == TrackDistance.LONG

        /**
         * The ranking ratio a skill sorts by, boosted for recovery skills on stamina-heavy builds. A modest multiplier that nudges recovery skills up without overriding a clearly stronger
         * stat skill. Returns the base ratio unchanged when the boost does not apply. Pure and testable.
         *
         * @param baseRatio The skill's normal evaluation-point-to-price ratio.
         * @param isRecovery Whether the skill is a recovery skill.
         * @param staminaHeavy Whether the boost is active this run (setting on and a Medium/Long build).
         * @param boost The multiplier to apply when the boost is active. Defaults to 1.5 - a nudge on stamina-heavy (Medium/Long) builds so strong stat skills still compete, not a hard override.
         * @return The effective ratio to sort by.
         */
        fun recoveryBoostedRatio(baseRatio: Double, isRecovery: Boolean, staminaHeavy: Boolean, boost: Double = 1.5): Double =
            if (isRecovery && staminaHeavy) baseRatio * boost else baseRatio

        /**
         * The ranking ratio a skill sorts by: its evaluation-point ratio, boosted for recovery skills on stamina-heavy builds. Shared by the auto-strategy sort sites. The description scan
         * is skipped entirely when the boost is inactive, so it costs nothing on the common (feature-off or Sprint/Mile) path.
         *
         * @param entry The skill being ranked.
         * @param staminaHeavy Whether the recovery boost is active this run (setting on and a Medium/Long build).
         * @return The effective ratio to sort by, highest first.
         */
        fun rankingRatio(entry: SkillListEntry, staminaHeavy: Boolean): Double =
            recoveryBoostedRatio(entry.evaluationPointRatio, staminaHeavy && isRecoverySkill(entry.skillData.description), staminaHeavy)

        /**
         * Whether a skill is compatible with the resolved Style preference on every axis. A skill passes when, for each axis with a
         * preference, it either has no commitment on that axis (generic / aptitude-independent) or its value matches. Running style
         * matches on the explicit style or any inferred style, mirroring the Optimize Skills include-pass.
         *
         * @param skillDistance The skill's track distance, or null.
         * @param skillStyle The skill's explicit running style, or null.
         * @param skillInferredStyles The skill's inferred running styles (may be empty).
         * @param skillSurface The skill's track surface, or null.
         * @param prefDistance The preferred track distance, or null for no restriction.
         * @param prefStyle The preferred running style, or null for no restriction.
         * @param prefSurface The preferred track surface, or null for no restriction.
         * @return True if the skill is buyable under the preference.
         */
        fun matchesPreference(
            skillDistance: TrackDistance?,
            skillStyle: RunningStyle?,
            skillInferredStyles: List<RunningStyle>,
            skillSurface: TrackSurface?,
            prefDistance: TrackDistance?,
            prefStyle: RunningStyle?,
            prefSurface: TrackSurface?,
        ): Boolean {
            val distanceOk = prefDistance == null || skillDistance == null || skillDistance == prefDistance
            val surfaceOk = prefSurface == null || skillSurface == null || skillSurface == prefSurface
            val styleOk =
                prefStyle == null ||
                    (skillStyle == null && skillInferredStyles.isEmpty()) ||
                    skillStyle == prefStyle ||
                    prefStyle in skillInferredStyles
            return distanceOk && surfaceOk && styleOk
        }

        /**
         * Represents a skill available for purchase in a pure calculation context.
         *
         * @property name The skill name.
         * @property price The skill's price in skill points.
         * @property evaluationPoints The rank points gained upon purchase.
         * @property isNegative Whether this is a negative (purple) skill.
         * @property isInheritedUnique Whether this is an inherited unique skill.
         * @property isUserPlanned Whether this skill is in the user's plan.
         * @property communityTier The community tier ranking (lower is better, null = unranked).
         * @property isBlacklisted Whether this skill is blacklisted by the user's plan settings (per-skill or category-level).
         * @property isDoubleCircle Whether this is a double-circle (double-O) variant of the skill.
         */
        data class SkillCandidate(
            val name: String,
            val price: Int,
            val evaluationPoints: Int,
            val isNegative: Boolean = false,
            val isInheritedUnique: Boolean = false,
            val isUserPlanned: Boolean = false,
            val communityTier: Int? = null,
            val isBlacklisted: Boolean = false,
            val isDoubleCircle: Boolean = false,
        ) {
            /** The ratio of rank gained to price. Higher is better. */
            val evaluationPointRatio: Double
                get() = if (price > 0) evaluationPoints.toDouble() / price.toDouble() else 0.0
        }

        /**
         * Pure calculation function that determines which skills to buy using the Optimize Rank strategy.
         *
         * Greedily selects skills with the highest evaluation-point-to-price ratio within
         * the available budget.
         *
         * @param candidates List of available skills for purchase.
         * @param budget Available skill points to spend.
         * @param alreadyPlanned Skills already planned for purchase (to avoid duplicates).
         * @return Ordered list of (name, price) pairs representing skills to buy.
         */
        fun calculateOptimizeRankPurchases(
            candidates: List<SkillCandidate>,
            budget: Int,
            alreadyPlanned: List<String> = emptyList(),
        ): List<Pair<String, Int>> {
            val result = mutableListOf<Pair<String, Int>>()
            var remaining = budget

            val sorted =
                candidates
                    .filter { it.name !in alreadyPlanned && it.price > 0 && !it.isBlacklisted }
                    .sortedByDescending { it.evaluationPointRatio }

            for (skill in sorted) {
                if (skill.price <= remaining) {
                    result.add(skill.name to skill.price)
                    remaining -= skill.price
                }
            }

            return result
        }

        /**
         * Pure calculation function that picks the subset of skills that spends as much of the budget as possible.
         *
         * This is used to drain skill points that would otherwise be lost at the end of a career. Since leftover points are worth nothing once the run is scored, the objective is to minimize the
         * remainder rather than to maximize value per point. Ties on the amount spent are broken in favor of the subset worth the most evaluation points.
         *
         * Only the user's per-skill blacklist is honored here. Category exclusions and aptitude preferences are deliberately ignored because they exist to steer normal purchasing and would
         * otherwise strand the very points this is meant to spend.
         *
         * @param candidates List of available skills for purchase.
         * @param budget Available skill points to spend.
         * @param alreadyPlanned Skills already planned for purchase (to avoid duplicates).
         * @param blacklist Names of skills the user has explicitly blacklisted.
         * @return Ordered list of (name, price) pairs representing skills to buy, cheapest first.
         */
        fun calculateLeftoverDrainPurchases(
            candidates: List<SkillCandidate>,
            budget: Int,
            alreadyPlanned: List<String> = emptyList(),
            blacklist: List<String> = emptyList(),
        ): List<Pair<String, Int>> {
            if (budget <= 0 || budget > MAX_DRAIN_BUDGET) {
                return emptyList()
            }

            val plannedNames: Set<String> = alreadyPlanned.toSet()
            val blacklistedNames: Set<String> = blacklist.toSet()
            val affordable: List<SkillCandidate> =
                candidates.filter { it.name !in plannedNames && it.name !in blacklistedNames && it.price in 1..budget }
            if (affordable.isEmpty()) {
                return emptyList()
            }

            // When everything on offer fits, the best subset is simply all of it, so skip the search.
            if (affordable.sumOf { it.price } <= budget) {
                return affordable.map { it.name to it.price }.sortedBy { it.second }
            }

            // 0/1 knapsack over the budget where a skill's weight and value are both its price, so the best subset is the one that spends the most without going over.
            val bestSpend = IntArray(budget + 1)
            val bestEvaluationPoints = IntArray(budget + 1)
            val keep: Array<BooleanArray> = Array(affordable.size) { BooleanArray(budget + 1) }

            for ((index, skill) in affordable.withIndex()) {
                for (remaining in budget downTo skill.price) {
                    val spend: Int = bestSpend[remaining - skill.price] + skill.price
                    val evaluationPoints: Int = bestEvaluationPoints[remaining - skill.price] + skill.evaluationPoints
                    if (spend > bestSpend[remaining] || (spend == bestSpend[remaining] && evaluationPoints > bestEvaluationPoints[remaining])) {
                        bestSpend[remaining] = spend
                        bestEvaluationPoints[remaining] = evaluationPoints
                        keep[index][remaining] = true
                    }
                }
            }

            // Walk the keep table backwards to recover which skills made up the winning subset.
            val result = mutableListOf<Pair<String, Int>>()
            var remaining: Int = budget
            for (index in affordable.indices.reversed()) {
                if (!keep[index][remaining]) {
                    continue
                }
                val skill: SkillCandidate = affordable[index]
                result.add(skill.name to skill.price)
                remaining -= skill.price
            }

            return result.sortedBy { it.second }
        }

        /**
         * Pure calculation function that determines which skills to buy using the common strategy.
         *
         * Buys in order: negative skills, inherited unique skills, then user-planned skills,
         * respecting the budget and enabled flags.
         *
         * @param candidates All available skill candidates.
         * @param budget Available skill points to spend.
         * @param settings Configuration for which skill types to buy.
         * @return Ordered list of (name, price) pairs representing skills to buy.
         */
        fun calculateCommonPurchases(
            candidates: List<SkillCandidate>,
            budget: Int,
            settings: SkillPlanSettings,
        ): List<Pair<String, Int>> {
            val result = mutableListOf<Pair<String, Int>>()
            var remaining = budget
            val bought = mutableSetOf<String>()

            // Phase 1: Negative skills
            if (settings.bEnableBuyNegativeSkills) {
                for (skill in candidates.filter { it.isNegative && !it.isBlacklisted }) {
                    if (skill.name in bought) continue
                    if (skill.price <= remaining) {
                        result.add(skill.name to skill.price)
                        remaining -= skill.price
                        bought.add(skill.name)
                    }
                }
            }

            // Phase 2: User-planned skills (in the order specified by plan). Blacklist takes precedence over plan.
            for (skill in candidates.filter { it.isUserPlanned && !it.isBlacklisted }) {
                if (skill.name in bought) continue
                if (skill.price <= remaining) {
                    result.add(skill.name to skill.price)
                    remaining -= skill.price
                    bought.add(skill.name)
                }
            }

            return result
        }

        /**
         * Pure calculation function that combines common and strategy-specific purchases.
         *
         * @param candidates All available skill candidates.
         * @param budget Available skill points to spend.
         * @param settings Configuration for the skill plan.
         * @return Ordered list of (name, price) pairs representing all skills to buy.
         */
        fun calculateSkillPurchases(
            candidates: List<SkillCandidate>,
            budget: Int,
            settings: SkillPlanSettings,
        ): List<Pair<String, Int>> {
            if (!settings.bIsEnabled) return emptyList()

            val result = mutableListOf<Pair<String, Int>>()

            // Common purchases first
            val common = calculateCommonPurchases(candidates, budget, settings)
            result.addAll(common)
            val spent = common.sumOf { it.second }
            val alreadyBought = common.map { it.first }

            // Strategy-specific purchases
            val remainingCandidates = candidates.filter { it.name !in alreadyBought && !(settings.bExcludeDoubleCircleSkills && it.isDoubleCircle) }
            val strategyPurchases =
                when (settings.strategy) {
                    SpendingStrategy.DEFAULT, SpendingStrategy.OPTIMIZE_RANK -> {
                        calculateOptimizeRankPurchases(remainingCandidates, budget - spent, alreadyBought)
                    }
                    SpendingStrategy.OPTIMIZE_SKILLS -> {
                        // For OPTIMIZE_SKILLS, filter by community tier first, then fall back to rank
                        val tiered =
                            remainingCandidates
                                .filter { it.communityTier != null && !it.isBlacklisted }
                                .sortedWith(compareBy<SkillCandidate> { it.communityTier }.thenByDescending { it.evaluationPointRatio })
                        val tieredResult = mutableListOf<Pair<String, Int>>()
                        var tieredRemaining = budget - spent
                        val tieredBought = alreadyBought.toMutableList()
                        for (skill in tiered) {
                            if (skill.name in tieredBought) continue
                            if (skill.price <= tieredRemaining) {
                                tieredResult.add(skill.name to skill.price)
                                tieredRemaining -= skill.price
                                tieredBought.add(skill.name)
                            }
                        }
                        // Fall back to optimize rank for remaining budget
                        val rankFallback =
                            calculateOptimizeRankPurchases(
                                remainingCandidates.filter { it.name !in tieredBought },
                                tieredRemaining,
                                tieredBought,
                            )
                        tieredResult + rankFallback
                    }
                }
            result.addAll(strategyPurchases)

            return result
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Debug Tests

    /**
     * Perform a test run of the skill list OCR and purchasing logic using mock skill points.
     *
     * This method allows for testing the skill identification and selection logic without performing actual transactions in the game.
     */
    fun startSkillListBuyTest() {
        MessageLog.i(TAG, "\n[TEST] Now beginning Skill List Buy test.")

        val skillList = SkillList(game, campaign)

        // Verify that the bot is currently at the skill list screen.
        if (!skillList.checkSkillListScreen()) {
            MessageLog.e(TAG, "[ERROR] startSkillListBuyTest:: Not on the Skill List screen. Ending test.")
            return
        }

        // Detect the current skill points.
        val currentPoints: Int? = skillList.detectSkillPoints()
        if (currentPoints == null) {
            MessageLog.e(TAG, "[ERROR] startSkillListBuyTest:: Failed to detect skill points. Ending test.")
            return
        }
        MessageLog.i(TAG, "[TEST] Current Skill Points: $currentPoints")

        // Scan the skill list and parse all available entries.
        // Use mock data if enabled for logic testing without a game instance.
        MessageLog.i(TAG, "[TEST] Scanning skill list...")
        val allSkills: Map<String, SkillListEntry> = skillList.parseSkillListEntries(bUseMockData = USE_MOCK_DATA)

        val availableSkills: Map<String, SkillListEntry> = allSkills.filter { !it.value.bIsObtained && !it.value.bIsVirtual }

        // Log a summary of all identified available skills.
        MessageLog.i(TAG, "[TEST] Summary of available skills:")
        availableSkills.forEach { (name, entry) ->
            MessageLog.i(TAG, "\t- $name: ${entry.screenPrice} SP")
        }

        // Preview the drain by running the exact code path the career-complete purchase uses. Purchases here are simulated in memory since no button location is passed along, so nothing is bought.
        val skillPlanSettings: SkillPlanSettings? = skillPlans[PLAN_CAREER_COMPLETE]
        if (skillPlanSettings == null) {
            MessageLog.e(TAG, "[ERROR] startSkillListBuyTest:: No CareerComplete skill plan is configured. Ending test.")
            return
        }

        val skillsToBuy: Map<String, Int> =
            getLeftoverDrainSkills(
                skillPlanSettings = skillPlanSettings,
                skillList = skillList,
                skillsToBuy = emptyList(),
                availableSkillPoints = currentPoints,
            )
        val remainingPoints: Int = currentPoints - skillsToBuy.values.sum()

        // Log a summary of the skills that would be purchased.
        MessageLog.i(TAG, "[TEST] Identified skills that would be bought to bring SP close to zero:")
        if (skillsToBuy.isEmpty()) {
            MessageLog.i(TAG, "\t- No skills can be purchased with current SP.")
        } else {
            skillsToBuy.forEach { (name, price) ->
                MessageLog.i(TAG, "\t- $name: $price SP")
            }
        }
        MessageLog.i(TAG, "[TEST] Expected remaining Skill Points: $remainingPoints")
        MessageLog.i(TAG, "[TEST] Skill List Buy test complete.")
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /** The resolved Style preference for each axis (null = no restriction). */
    private data class PreferredAxes(
        /** The resolved preferred running style, or null for no restriction. */
        val runningStyle: RunningStyle?,
        /** The resolved preferred track distance, or null for no restriction. */
        val trackDistance: TrackDistance?,
        /** The resolved preferred track surface, or null for no restriction. */
        val trackSurface: TrackSurface?,
    )

    /**
     * Resolve the global Style preference settings into concrete enum values, applying the no_preference / inherit rules.
     *
     * @return The resolved running style, track distance, and track surface (any of which may be null for no restriction).
     */
    private fun resolvePreferredAxes(): PreferredAxes {
        val runningStyle: RunningStyle? =
            when (skillSettingRunningStyleString.lowercase()) {
                "no_preference" -> null
                "inherit" -> RunningStyle.fromShortName(racingSettingRunningStyleString) ?: campaign.trainee.runningStyle
                else -> RunningStyle.fromName(skillSettingRunningStyleString)
            }
        val trackDistance: TrackDistance? =
            when (skillSettingTrackDistanceString.lowercase()) {
                "no_preference" -> null
                "inherit" -> TrackDistance.fromName(trainingSettingTrackDistanceString) ?: campaign.trainee.trackDistance
                else -> TrackDistance.fromName(skillSettingTrackDistanceString)
            }
        val trackSurface: TrackSurface? =
            when (skillSettingTrackSurfaceString.lowercase()) {
                "no_preference" -> null
                else -> TrackSurface.fromName(skillSettingTrackSurfaceString)
            }
        return PreferredAxes(runningStyle, trackDistance, trackSurface)
    }

    /**
     * Whether the given skill entry should be excluded from purchase due to the user's blacklist settings.
     *
     * Returns true if the entry's name is in the per-skill blacklist OR its color category is in the excluded types set.
     *
     * @param entry The [SkillListEntry] under consideration.
     * @param settings The [SkillPlanSettings] holding the blacklist and excluded categories.
     * @return True if the entry should be skipped, false otherwise.
     */
    private fun isBlacklisted(entry: SkillListEntry, settings: SkillPlanSettings): Boolean =
        entry.name in settings.skillBlacklist ||
            entry.skillData.type in settings.excludedTypes ||
            (settings.bExcludeUniqueSkills && entry.bIsInheritedUnique)

    /**
     * Retrieve all available negative skills from the skill list.
     *
     * @param skillPlanSettings The [SkillPlanSettings] to follow.
     * @param skillList The [SkillList] to analyze.
     * @param skillsToBuy The list of skills already planned for purchase.
     * @param availableSkillPoints The current amount of available skill points.
     * @return A map of skill names to their prices for the identified negative skills.
     */
    private fun getNegativeSkills(skillPlanSettings: SkillPlanSettings, skillList: SkillList, skillsToBuy: List<String>, availableSkillPoints: Int): Map<String, Int> {
        if (!skillPlanSettings.bEnableBuyNegativeSkills) {
            return emptyMap()
        }

        val result: MutableMap<String, Int> = mutableMapOf()
        var remainingSkillPoints = availableSkillPoints

        val entries: Map<String, SkillListEntry> = skillList.getNegativeSkills()
        for ((name, entry) in entries) {
            // Don't add any duplicate entries.
            if (name in skillsToBuy) {
                continue
            }

            // Skip skills the user has explicitly blacklisted (per-skill or by color category).
            if (isBlacklisted(entry, skillPlanSettings)) {
                continue
            }

            if (entry.bIsAvailable && entry.screenPrice <= remainingSkillPoints) {
                result[name] = entry.screenPrice
                remainingSkillPoints -= entry.screenPrice
                entry.buy()
            }
        }

        return result.toMap()
    }

    /**
     * Retrieve all available skills from the user's skill plan that are present in the skill list.
     *
     * @param skillPlanSettings The [SkillPlanSettings] to follow.
     * @param skillList The [SkillList] to analyze.
     * @param skillsToBuy The list of skills already planned for purchase.
     * @param availableSkillPoints The current amount of available skill points.
     * @return A map of skill names to their prices for the identified user-planned skills.
     */
    private fun getUserPlannedSkills(skillPlanSettings: SkillPlanSettings, skillList: SkillList, skillsToBuy: List<String>, availableSkillPoints: Int): Map<String, Int> {
        if (skillPlanSettings.skillNames.isEmpty()) {
            return emptyMap()
        }

        val result: MutableMap<String, Int> = mutableMapOf()
        var remainingSkillPoints = availableSkillPoints

        // If two versions of the same skill are in the skill list and plan, prioritize the higher level version.
        // For example, if "Corner Recovery O" and "Swinging Maestro" are both in the plan and list,
        // prioritize "Swinging Maestro". If points are insufficient, attempt to buy "Corner Recovery O" instead.
        for (name in skillPlanSettings.skillNames) {
            // Don't add duplicate entries.
            if (name in skillsToBuy || name in result) {
                continue
            }

            val entry: SkillListEntry? = skillList.getEntry(name)
            if (entry == null) {
                MessageLog.e(TAG, "[ERROR] getUserPlannedSkills:: Failed to find entry for \"$name\".")
                continue
            }

            // Skip skills the user has both planned AND blacklisted. Blacklist takes precedence to keep behavior predictable.
            if (isBlacklisted(entry, skillPlanSettings)) {
                continue
            }

            // Handle exact matches.
            if (entry.bIsAvailable) {
                result[name] = entry.screenPrice
                remainingSkillPoints -= entry.screenPrice
                entry.buy()
                continue
            }

            // If no exact match exists, check for in-place upgrade chains.
            // Obtaining a skill hint for an in-place chain skill allows upgrading to any higher versions.
            // Higher versions of non-in-place chains require their own skill hints to unlock.

            // Skip the entry if no downgraded versions exist in the skill list.
            val availableEntry: SkillListEntry = entry.getFirstAvailableDowngrade() ?: continue

            // If a downgraded version exists, calculate the sequence of upgrades required to reach the planned skill.
            val upgrades: List<SkillListEntry> = availableEntry.getUpgradesUntil(name)

            // Handle in-place upgrade skill chains.
            if (upgrades.all { it.bIsInPlace }) {
                // Only add entries that haven't already been planned or purchased.
                val unacquired: List<SkillListEntry> =
                    upgrades
                        .filter { it.name !in skillsToBuy && it.name !in result }

                val totalPrice: Int = unacquired.sumOf { it.price }
                if (totalPrice <= remainingSkillPoints) {
                    unacquired.forEach { it.buy() }
                    val toAdd: Map<String, Int> = unacquired.associate { it.name to it.price }
                    result.putAll(toAdd)
                    remainingSkillPoints -= totalPrice
                }
                continue
            }
        }

        return result.toMap()
    }

    /**
     * Retrieve all available negative, inherited unique, and user-planned skills.
     *
     * These common skill checks are performed across all spending strategies.
     *
     * @param skillPlanSettings The [SkillPlanSettings] to follow.
     * @param skillList The [SkillList] to analyze.
     * @param skillsToBuy The list of skills already planned for purchase.
     * @param availableSkillPoints The current amount of available skill points.
     * @return A map of skill names to their prices for all identified common skills.
     */
    private fun getSkillsToBuyCommon(skillPlanSettings: SkillPlanSettings, skillList: SkillList, skillsToBuy: List<String>, availableSkillPoints: Int): Map<String, Int> {
        val result: MutableMap<String, Int> = mutableMapOf()

        result +=
            getNegativeSkills(
                skillPlanSettings = skillPlanSettings,
                skillList = skillList,
                skillsToBuy = skillsToBuy + result.keys.toList(),
                availableSkillPoints = availableSkillPoints - result.values.sum(),
            )

        result +=
            getUserPlannedSkills(
                skillPlanSettings = skillPlanSettings,
                skillList = skillList,
                skillsToBuy = skillsToBuy + result.keys.toList(),
                availableSkillPoints = availableSkillPoints - result.values.sum(),
            )

        return result.toMap()
    }

    /**
     * Retrieve all available skills following the default spending strategy.
     *
     * Currently, this strategy is synonymous with OPTIMIZE_RANK.
     *
     * @param skillPlanSettings The [SkillPlanSettings] to follow.
     * @param skillList The [SkillList] to analyze.
     * @param skillsToBuy The list of skills already planned for purchase.
     * @param availableSkillPoints The current amount of available skill points.
     * @return A map of skill names to their prices for the default strategy.
     */
    private fun getSkillsToBuyDefaultStrategy(skillPlanSettings: SkillPlanSettings, skillList: SkillList, skillsToBuy: List<String>, availableSkillPoints: Int): Map<String, Int> {
        // Currently does not implement additional logic beyond common skills.
        return emptyMap()
    }

    /**
     * Retrieve all available skills following the OptimizeSkills strategy.
     *
     * This strategy calculates optimal skills based on a community tier list and evaluates them based on their rank-to-price ratio. It filters skills to match user-specified aptitudes for running
     * style, track distance, and track surface.
     *
     * @param skillPlanSettings The [SkillPlanSettings] to follow.
     * @param skillList The [SkillList] to analyze.
     * @param skillsToBuy The list of skills already planned for purchase.
     * @param availableSkillPoints The current amount of available skill points.
     * @return A map of skill names to their prices for the OptimizeSkills strategy.
     */
    private fun getSkillsToBuyOptimizeSkillsStrategy(skillPlanSettings: SkillPlanSettings, skillList: SkillList, skillsToBuy: List<String>, availableSkillPoints: Int): Map<String, Int> {
        val result: MutableMap<String, Int> = mutableMapOf()
        var remainingSkillPoints = availableSkillPoints

        val (preferredRunningStyle, preferredTrackDistance, preferredTrackSurface) = resolvePreferredAxes()

        MessageLog.d(TAG, "[DEBUG] getSkillsToBuyOptimizeSkillsStrategy:: Using preferred running style: $preferredRunningStyle")
        MessageLog.d(TAG, "[DEBUG] getSkillsToBuyOptimizeSkillsStrategy:: Using preferred track distance: $preferredTrackDistance")
        MessageLog.d(TAG, "[DEBUG] getSkillsToBuyOptimizeSkillsStrategy:: Using preferred track surface: $preferredTrackSurface")
        val staminaHeavy = prioritizeRecoveryForStamina && isStaminaHeavyDistance(preferredTrackDistance)

        // Retrieve skills that match the specified aptitudes or are style-agnostic.
        fun getFilteredSkills(remainingSkillPoints: Int): Map<String, SkillListEntry> {
            val result: MutableMap<String, SkillListEntry> = mutableMapOf()

            result.putAll(skillList.getAptitudeIndependentSkills(preferredRunningStyle))

            if (preferredRunningStyle != null) {
                result.putAll(skillList.getRunningStyleSkills(preferredRunningStyle))
                result.putAll(skillList.getInferredRunningStyleSkills(preferredRunningStyle))
            }
            if (preferredTrackDistance != null) {
                result.putAll(skillList.getTrackDistanceSkills(preferredTrackDistance))
            }
            if (preferredTrackSurface != null) {
                result.putAll(skillList.getTrackSurfaceSkills(preferredTrackSurface))
            }

            result.values.removeAll { it.price > remainingSkillPoints }

            return result.toMap()
        }

        // Iterate until no more affordable skills are found, as purchasing can unlock new options.
        val maxIterations = 10
        var i = 0
        var remainingSkills: Map<String, SkillListEntry> = getFilteredSkills(remainingSkillPoints)
        while (remainingSkills.any { it.value.screenPrice <= remainingSkillPoints }) {
            // Group entries by community tier, with higher tiers prioritized.
            val groupedByCommunityTier: Map<Int?, List<SkillListEntry>> =
                remainingSkills.values
                    .groupBy { it.communityTier }
                    .toSortedMap(compareBy { it })

            // Iterate from the highest tier to lowest, ignoring unranked (null) entries.
            for ((communityTier, group) in groupedByCommunityTier) {
                if (communityTier == null) {
                    continue
                }

                // Sort within the tier by evaluation point ratio, nudging recovery skills up on stamina-heavy builds.
                val sortedByPointRatio: List<SkillListEntry> = group.sortedByDescending { rankingRatio(it, staminaHeavy) }
                for (entry in sortedByPointRatio) {
                    // Don't add duplicate entries.
                    if (entry.name in result || entry.name in skillsToBuy) {
                        continue
                    }

                    if (isBlacklisted(entry, skillPlanSettings)) {
                        continue
                    }

                    if (skillPlanSettings.bExcludeDoubleCircleSkills && entry.skillData.name.contains(DOUBLE_CIRCLE_CHAR)) {
                        continue
                    }

                    if (!entry.bIsAvailable || entry.screenPrice > remainingSkillPoints) {
                        continue
                    }

                    result[entry.name] = entry.screenPrice
                    remainingSkillPoints -= entry.screenPrice
                    entry.buy()
                }
            }

            remainingSkills = getFilteredSkills(remainingSkillPoints)
            if (i++ > maxIterations) {
                break
            }
        }

        // Spend remaining skill points using the Optimize Rank strategy.
        result +=
            getSkillsToBuyOptimizeRankStrategy(
                skillPlanSettings = skillPlanSettings,
                skillList = skillList,
                skillsToBuy = skillsToBuy + result.keys.toList(),
                availableSkillPoints = remainingSkillPoints,
            )

        return result.toMap()
    }

    /**
     * Retrieve all available skills following the Optimize Rank strategy.
     *
     * This strategy maximizes total rank by purchasing skills with the highest rank-to-price ratio. User-specified skill aptitudes are ignored in this strategy.
     *
     * @param skillPlanSettings The [SkillPlanSettings] to follow.
     * @param skillList The [SkillList] to analyze.
     * @param skillsToBuy The list of skills already planned for purchase.
     * @param availableSkillPoints The current amount of available skill points.
     * @return A map of skill names to their prices for the Optimize Rank strategy.
     */
    private fun getSkillsToBuyOptimizeRankStrategy(skillPlanSettings: SkillPlanSettings, skillList: SkillList, skillsToBuy: List<String>, availableSkillPoints: Int): Map<String, Int> {
        val result: MutableMap<String, Int> = mutableMapOf()
        var remainingSkillPoints = availableSkillPoints
        val (preferredRunningStyle, preferredTrackDistance, preferredTrackSurface) = resolvePreferredAxes()
        val staminaHeavy = prioritizeRecoveryForStamina && isStaminaHeavyDistance(preferredTrackDistance)

        // Iterate until no more affordable skills are found, as purchasing can unlock new options.
        val maxIterations = 10
        var i = 0
        var remainingSkills: Map<String, SkillListEntry> = skillList.getAvailableSkills()
        while (remainingSkills.any { it.value.screenPrice <= remainingSkillPoints }) {
            val sortedByPointRatio: List<SkillListEntry> =
                remainingSkills.values
                    .sortedByDescending { rankingRatio(it, staminaHeavy) }

            for (entry in sortedByPointRatio) {
                // Don't add duplicate entries.
                if (entry.name in result || entry.name in skillsToBuy) {
                    continue
                }

                if (isBlacklisted(entry, skillPlanSettings)) {
                    continue
                }

                if (skillPlanSettings.bExcludeDoubleCircleSkills && entry.skillData.name.contains(DOUBLE_CIRCLE_CHAR)) {
                    continue
                }

                if (!matchesPreference(
                        entry.trackDistance,
                        entry.runningStyle,
                        entry.inferredRunningStyles,
                        entry.trackSurface,
                        preferredTrackDistance,
                        preferredRunningStyle,
                        preferredTrackSurface,
                    )
                ) {
                    continue
                }

                if (!entry.bIsAvailable || entry.screenPrice > remainingSkillPoints) {
                    continue
                }

                result[entry.name] = entry.screenPrice
                remainingSkillPoints -= entry.screenPrice
                entry.buy()
            }

            remainingSkills = skillList.getAvailableSkills()

            if (i++ > maxIterations) {
                break
            }
        }

        return result.toMap()
    }

    /**
     * Retrieve additional skills to buy purely to spend skill points that would otherwise go to waste.
     *
     * This runs as the final pass of the career-complete purchase. Leftover points are lost once the run is scored, so anything bought here is free rank, even skills the bot would never pick
     * during the career. Only the user's per-skill blacklist still applies. See [calculateLeftoverDrainPurchases] for the selection itself.
     *
     * @param skillPlanSettings The [SkillPlanSettings] to follow.
     * @param skillList The [SkillList] to analyze.
     * @param skillsToBuy The list of skills already planned for purchase.
     * @param availableSkillPoints The current amount of available skill points.
     * @return A map of skill names to their prices for the identified leftover skills.
     */
    private fun getLeftoverDrainSkills(skillPlanSettings: SkillPlanSettings, skillList: SkillList, skillsToBuy: List<String>, availableSkillPoints: Int): Map<String, Int> {
        val result: MutableMap<String, Int> = mutableMapOf()
        val plannedNames: MutableSet<String> = skillsToBuy.toMutableSet()
        var remainingSkillPoints: Int = availableSkillPoints

        // A skill that belongs to an upgrade chain drops the price of its upgrade when bought, which frees up budget the previous pass could not see. Re-run only when that actually happened.
        for (iteration in 0 until MAX_DRAIN_ITERATIONS) {
            val entries: Map<String, SkillListEntry> = skillList.getAvailableSkills()
            val candidates: List<SkillCandidate> =
                entries.values.map { entry -> SkillCandidate(name = entry.name, price = entry.screenPrice, evaluationPoints = entry.evaluationPoints) }

            val picks: List<Pair<String, Int>> =
                calculateLeftoverDrainPurchases(
                    candidates = candidates,
                    budget = remainingSkillPoints,
                    alreadyPlanned = plannedNames.toList(),
                    blacklist = skillPlanSettings.skillBlacklist,
                )
            if (picks.isEmpty()) {
                break
            }

            var bPricesMayHaveShifted = false
            for ((name, _) in picks) {
                // Re-read the price off the entry since an earlier pick in this same batch may have shifted it.
                val entry: SkillListEntry = entries[name] ?: continue
                if (!entry.bIsAvailable || entry.screenPrice > remainingSkillPoints) {
                    continue
                }

                result[name] = entry.screenPrice
                plannedNames.add(name)
                remainingSkillPoints -= entry.screenPrice
                if (entry.next != null) {
                    bPricesMayHaveShifted = true
                }
                entry.buy()
            }

            // Nothing bought in this pass can have shifted another skill's price, so another pass would just recompute the same empty answer.
            if (!bPricesMayHaveShifted) {
                break
            }
        }

        if (result.isNotEmpty()) {
            MessageLog.i(TAG, "[SKILLS] Spending ${availableSkillPoints - remainingSkillPoints} leftover skill points that would be lost at career end.")
            for ((name, price) in result) {
                MessageLog.i(TAG, "\t- $name: $price pts")
            }
        }

        return result.toMap()
    }

    /**
     * Retrieve all available skills to purchase based on the specified spending strategy.
     *
     * @param skillPlanSettings The [SkillPlanSettings] to follow.
     * @param skillList The [SkillList] to analyze.
     * @param availableSkillPoints The current amount of available skill points.
     * @param bSpendLeftoverPoints Whether to spend any remaining points on extra skills afterwards. Set from the career-complete screen check, since that is the final purchase of the career and
     *   any points left behind are lost.
     * @return A map of skill names to their prices for all skills to be purchased.
     */
    fun getSkillsToBuy(skillPlanSettings: SkillPlanSettings, skillList: SkillList, availableSkillPoints: Int, bSpendLeftoverPoints: Boolean = false): Map<String, Int> {
        MessageLog.i(TAG, "[SKILLS] Beginning process of calculating skills to purchase...")

        if (!skillPlanSettings.bIsEnabled) {
            MessageLog.i(TAG, "[SKILLS] Skill plan is disabled. No skills will be purchased.")
            return emptyMap()
        }

        val result: MutableMap<String, Int> = mutableMapOf()

        // Execute common skill checks first.
        result +=
            getSkillsToBuyCommon(
                skillPlanSettings = skillPlanSettings,
                skillList = skillList,
                skillsToBuy = result.keys.toList(),
                availableSkillPoints = availableSkillPoints - result.values.sum(),
            )

        // Execute strategy-specific checks.
        result +=
            when (skillPlanSettings.strategy) {
                SpendingStrategy.DEFAULT -> {
                    getSkillsToBuyDefaultStrategy(
                        skillPlanSettings = skillPlanSettings,
                        skillList = skillList,
                        skillsToBuy = result.keys.toList(),
                        availableSkillPoints = availableSkillPoints - result.values.sum(),
                    )
                }

                SpendingStrategy.OPTIMIZE_SKILLS -> {
                    getSkillsToBuyOptimizeSkillsStrategy(
                        skillPlanSettings = skillPlanSettings,
                        skillList = skillList,
                        skillsToBuy = result.keys.toList(),
                        availableSkillPoints = availableSkillPoints - result.values.sum(),
                    )
                }

                SpendingStrategy.OPTIMIZE_RANK -> {
                    getSkillsToBuyOptimizeRankStrategy(
                        skillPlanSettings = skillPlanSettings,
                        skillList = skillList,
                        skillsToBuy = result.keys.toList(),
                        availableSkillPoints = availableSkillPoints - result.values.sum(),
                    )
                }
            }

        // Drain whatever is left so the career does not end with unspent points.
        if (bSpendLeftoverPoints) {
            result +=
                getLeftoverDrainSkills(
                    skillPlanSettings = skillPlanSettings,
                    skillList = skillList,
                    skillsToBuy = result.keys.toList(),
                    availableSkillPoints = availableSkillPoints - result.values.sum(),
                )
        }

        MessageLog.v(TAG, "================ Skills To Buy =================")
        for ((name, price) in result) {
            MessageLog.v(TAG, "\t$name: $price")
        }
        MessageLog.v(
            TAG,
            "\n\tTOTAL: ${result.values.sum()} / ${if (USE_MOCK_DATA) MOCK_SKILL_POINTS else skillList.skillPoints} pts with ${if (USE_MOCK_DATA) MOCK_SKILL_POINTS else skillList.skillPoints - result.values.sum()} left over pts",
        )
        MessageLog.v(TAG, "================================================")

        return result.toMap()
    }

    /**
     * Buys a single skill, logs the purchase, and records it in the trainee's owned-skill set
     * so the Remote Log Viewer's Skills panel can update without waiting for a Details read.
     *
     * @param name The canonical name of the skill to buy.
     * @param point The screen location of the skill's purchase button.
     * @param skillList The [SkillList] managing the current scan.
     * @return True if the skill was purchased, false otherwise.
     */
    private fun buyAndTrackSkill(name: String, point: Point, skillList: SkillList): Boolean {
        val purchaseResult: SkillListEntry = skillList.buySkill(name, point) ?: return false
        MessageLog.i(TAG, "[INFO] Buying \"${purchaseResult.name}\" for ${purchaseResult.price} pts")
        campaign.trainee.ownedSkillNames.add(purchaseResult.name)
        return true
    }

    /**
     * Log the details of a detected skill list entry and handle its purchase if planned.
     *
     * @param entry The detected [SkillListEntry].
     * @param point The screen location of the skill's purchase button.
     * @param skillsToBuy The list of skill names planned for purchase.
     * @param skillList The [SkillList] managing the current scan.
     * @return True if all planned skills have been purchased, triggering an early exit; false otherwise.
     */
    private fun onSkillListEntryDetected(entry: SkillListEntry, point: Point, skillsToBuy: List<String>, skillList: SkillList): Boolean {
        if (entry.bIsObtained || entry.bIsVirtual) {
            return false
        }

        if (entry.name !in skillsToBuy) {
            return false
        }

        // Buy the base skill plus any in-place upgrade versions that are also planned.
        val namesToBuy: List<String> = if (entry.bIsInPlace) listOf(entry.name) + entry.getUpgradeNames().filter { it in skillsToBuy } else listOf(entry.name)

        var boughtAny = false
        for (name in namesToBuy) {
            if (buyAndTrackSkill(name, point, skillList)) boughtAny = true
        }

        // Push the freshly-owned skills to the Remote Log Viewer's Skills panel now instead of waiting for the next Details tab read.
        if (boughtAny) campaign.broadcastOwnedSkills()

        // Check if all planned skills have been purchased to allow for an early exit.
        val obtained: Map<String, SkillListEntry> = skillList.getObtainedSkills()
        if (skillsToBuy.all { it in obtained }) {
            MessageLog.i(TAG, "[SKILLS] All skills purchased. Exiting loop early...")
            return true
        }

        return false
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Start the skill purchasing process.
     *
     * This method orchestrates the full flow: identifying affordable skills based on the user's settings and then interacting with the game UI to buy them.
     *
     * @param skillPlanName Optional name of the skill plan to execute. If null, defaults based on career status.
     * @return True if the process completed successfully, false otherwise.
     */
    fun start(skillPlanName: String? = null): Boolean {
        val bitmap: Bitmap = game.imageUtils.getSourceBitmap()

        val skillList = SkillList(game, campaign)

        // Verify that the bot is currently at the skill list screen.
        val bIsCareerComplete: Boolean = skillList.checkCareerCompleteSkillListScreen(bitmap)
        if (!bIsCareerComplete && !skillList.checkSkillListScreen(bitmap)) {
            MessageLog.e(TAG, "[ERROR] start:: Not at skill list screen. Aborting...")
            return false
        }

        // Determine which skill plan to execute based on the current context.
        val skillPlanSettings: SkillPlanSettings =
            if (skillPlanName == null) {
                if (bIsCareerComplete) {
                    skillPlans[PLAN_CAREER_COMPLETE]!!
                } else {
                    skillPlans["preFinals"]!!
                }
            } else {
                val tmpPlan: SkillPlanSettings? = skillPlans[skillPlanName]
                if (tmpPlan == null) {
                    MessageLog.e(TAG, "[ERROR] start:: Invalid skill plan name: $skillPlanName")
                    return false
                }
                tmpPlan
            }

        // If no purchasing options are enabled, exit early to avoid unnecessary scanning. The career-complete purchase always has work to do as it spends the leftover points regardless.
        if (
            !bIsCareerComplete &&
            skillPlanSettings.skillNames.isEmpty() &&
            skillPlanSettings.strategy == SpendingStrategy.DEFAULT &&
            !skillPlanSettings.bEnableBuyNegativeSkills
        ) {
            MessageLog.w(TAG, "[WARN] start:: Skill Plan is empty and no options to purchase any skills are enabled. Aborting...")
            skillList.cancelAndExit()
            return true
        }

        // Ensure that the trainee's aptitudes are up-to-date before calculating purchases.
        if (!USE_MOCK_DATA && !campaign.trainee.bHasUpdatedAptitudes) {
            skillList.checkStats()
        }

        val skillPoints: Int =
            if (USE_MOCK_DATA) {
                MOCK_SKILL_POINTS
            } else {
                skillList.detectSkillPoints(bitmap) ?: 0
            }

        // Exit if the current skill points are below the minimum possible skill cost.
        if (skillPoints < 42) {
            MessageLog.i(TAG, "[SKILLS] Skill Points < 42. Cannot afford any skills. Aborting...")
            skillList.cancelAndExit()
            return true
        }

        // Gather and parse all skill entries from the screen.
        skillList.parseSkillListEntries(bUseMockData = USE_MOCK_DATA)
        if (skillList.getAllSkills().isEmpty()) {
            MessageLog.e(TAG, "[ERROR] start:: Failed to detect skills.")
            skillList.cancelAndExit()
            return false
        }

        skillList.printSkillListEntries(verbose = true)

        // Calculate the list of skills to purchase based on settings and points.
        val skillsToPurchase: Map<String, Int> =
            getSkillsToBuy(
                skillPlanSettings = skillPlanSettings,
                skillList = skillList,
                availableSkillPoints = skillPoints,
                bSpendLeftoverPoints = bIsCareerComplete,
            )

        // Exit if no skills were identified for purchase.
        if (skillsToPurchase.isEmpty()) {
            skillList.cancelAndExit()
            campaign.trainee.skillPoints = skillList.skillPoints
            return true
        }

        // Reset the internal purchase state before starting the actual buying process.
        skillList.sellAllSkills()

        // Iterate through the list again and perform the confirmed purchases.
        skillList.parseSkillListEntries { currentList: SkillList, entry: SkillListEntry, point: Point ->
            onSkillListEntryDetected(
                entry = entry,
                point = point,
                skillsToBuy = skillsToPurchase.keys.toList(),
                skillList = currentList,
            )
        }

        skillList.confirmAndExit()
        campaign.trainee.skillPoints = skillList.skillPoints
        return true
    }
}
