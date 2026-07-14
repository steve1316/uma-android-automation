package com.steve1316.uma_android_automation.bot

import com.steve1316.uma_android_automation.bot.SkillPlan.Companion.SkillCandidate
import com.steve1316.uma_android_automation.bot.SkillPlan.Companion.calculateCommonPurchases
import com.steve1316.uma_android_automation.bot.SkillPlan.Companion.calculateLeftoverDrainPurchases
import com.steve1316.uma_android_automation.bot.SkillPlan.Companion.calculateOptimizeRankPurchases
import com.steve1316.uma_android_automation.bot.SkillPlan.Companion.calculateSkillPurchases
import com.steve1316.uma_android_automation.bot.SkillPlan.Companion.isRecoverySkill
import com.steve1316.uma_android_automation.bot.SkillPlan.Companion.isStaminaHeavyDistance
import com.steve1316.uma_android_automation.bot.SkillPlan.Companion.matchesPreference
import com.steve1316.uma_android_automation.bot.SkillPlan.Companion.recoveryBoostedRatio
import com.steve1316.uma_android_automation.bot.SkillPlan.SkillPlanSettings
import com.steve1316.uma_android_automation.bot.SkillPlan.SpendingStrategy
import com.steve1316.uma_android_automation.types.RunningStyle
import com.steve1316.uma_android_automation.types.TrackDistance
import com.steve1316.uma_android_automation.types.TrackSurface
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Unit tests for the skill plan purchasing algorithms.
 *
 * Tests the pure companion functions on [SkillPlan] that determine which skills to buy:
 * - [SkillPlan.calculateOptimizeRankPurchases]: greedy rank-maximizing strategy.
 * - [SkillPlan.calculateCommonPurchases]: phased buying (negative, inherited unique, user-planned).
 * - [SkillPlan.calculateSkillPurchases]: full orchestrator combining common + strategy-specific logic.
 * - [SkillPlan.calculateLeftoverDrainPurchases]: exact subset-sum that spends the points left over at career end.
 *
 * Includes randomized stress tests that generate dummy skill lists (10-100 skills) with
 * random prices, eval points, and flags, verifying budget and uniqueness invariants
 * across all three spending strategies (DEFAULT, OPTIMIZE_RANK, OPTIMIZE_SKILLS).
 */
@DisplayName("Skill Plan Purchasing Tests")
class SkillPlanPurchasingTest {
    // =========================================================================
    // SkillCandidate
    // =========================================================================

    @Nested
    @DisplayName("SkillCandidate")
    inner class SkillCandidateTests {
        @Test
        fun `evaluationPointRatio calculated correctly`() {
            val skill = SkillCandidate(name = "Test", price = 100, evaluationPoints = 50)
            assertEquals(0.5, skill.evaluationPointRatio, 0.001)
        }

        @Test
        fun `evaluationPointRatio is zero when price is zero`() {
            val skill = SkillCandidate(name = "Test", price = 0, evaluationPoints = 50)
            assertEquals(0.0, skill.evaluationPointRatio)
        }

        @Test
        fun `high eval points with low price gives high ratio`() {
            val skill = SkillCandidate(name = "Bargain", price = 50, evaluationPoints = 200)
            assertEquals(4.0, skill.evaluationPointRatio, 0.001)
        }
    }

    // =========================================================================
    // calculateOptimizeRankPurchases()
    // =========================================================================

    @Nested
    @DisplayName("calculateOptimizeRankPurchases()")
    inner class OptimizeRankTests {
        @Test
        fun `empty candidates returns empty`() {
            val result = calculateOptimizeRankPurchases(emptyList(), 1000)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `buys skill with best ratio first`() {
            val candidates =
                listOf(
                    SkillCandidate("Low Ratio", price = 100, evaluationPoints = 50), // 0.5
                    SkillCandidate("High Ratio", price = 50, evaluationPoints = 100), // 2.0
                    SkillCandidate("Mid Ratio", price = 80, evaluationPoints = 80), // 1.0
                )
            val result = calculateOptimizeRankPurchases(candidates, 1000)
            assertEquals(3, result.size)
            assertEquals("High Ratio", result[0].first) // Best ratio first
            assertEquals("Mid Ratio", result[1].first)
            assertEquals("Low Ratio", result[2].first)
        }

        @Test
        fun `respects budget constraint`() {
            val candidates =
                listOf(
                    SkillCandidate("Expensive", price = 200, evaluationPoints = 400), // 2.0
                    SkillCandidate("Cheap", price = 50, evaluationPoints = 80), // 1.6
                )
            val result = calculateOptimizeRankPurchases(candidates, 100)
            assertEquals(1, result.size)
            assertEquals("Cheap", result[0].first)
        }

        @Test
        fun `skips already planned skills`() {
            val candidates =
                listOf(
                    SkillCandidate("Skill A", price = 50, evaluationPoints = 100),
                    SkillCandidate("Skill B", price = 50, evaluationPoints = 80),
                )
            val result = calculateOptimizeRankPurchases(candidates, 1000, alreadyPlanned = listOf("Skill A"))
            assertEquals(1, result.size)
            assertEquals("Skill B", result[0].first)
        }

        @Test
        fun `skips zero-price skills`() {
            val candidates =
                listOf(
                    SkillCandidate("Free", price = 0, evaluationPoints = 100),
                    SkillCandidate("Paid", price = 50, evaluationPoints = 50),
                )
            val result = calculateOptimizeRankPurchases(candidates, 1000)
            assertEquals(1, result.size)
            assertEquals("Paid", result[0].first)
        }

        @Test
        fun `total spent does not exceed budget`() {
            val candidates =
                (1..20).map {
                    SkillCandidate("Skill_$it", price = 30 + it * 5, evaluationPoints = 40 + it * 10)
                }
            val budget = 200
            val result = calculateOptimizeRankPurchases(candidates, budget)
            val totalSpent = result.sumOf { it.second }
            assertTrue(totalSpent <= budget, "Spent $totalSpent > budget $budget")
        }

        @Test
        fun `maximizes eval points within budget`() {
            // Given equal prices, should prefer higher eval points
            val candidates =
                listOf(
                    SkillCandidate("Low EP", price = 100, evaluationPoints = 50),
                    SkillCandidate("High EP", price = 100, evaluationPoints = 150),
                )
            val result = calculateOptimizeRankPurchases(candidates, 100)
            assertEquals(1, result.size)
            assertEquals("High EP", result[0].first) // Better ratio
        }
    }

    // =========================================================================
    // calculateCommonPurchases()
    // =========================================================================

    @Nested
    @DisplayName("calculateCommonPurchases()")
    inner class CommonPurchasesTests {
        private val defaultSettings =
            SkillPlanSettings(
                bIsEnabled = true,
                strategy = SpendingStrategy.DEFAULT,
                bEnableBuyNegativeSkills = true,
                skillNames = listOf("User Skill A", "User Skill B"),
            )

        @Test
        fun `buys negative skills first`() {
            val candidates =
                listOf(
                    SkillCandidate("Negative A", price = 50, evaluationPoints = 30, isNegative = true),
                    SkillCandidate("Regular", price = 50, evaluationPoints = 100),
                )
            val result = calculateCommonPurchases(candidates, 100, defaultSettings)
            assertEquals(1, result.size)
            assertEquals("Negative A", result[0].first)
        }

        @Test
        fun `skips negative skills when disabled`() {
            val settings = defaultSettings.copy(bEnableBuyNegativeSkills = false)
            val candidates =
                listOf(
                    SkillCandidate("Negative A", price = 50, evaluationPoints = 30, isNegative = true),
                )
            val result = calculateCommonPurchases(candidates, 1000, settings)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `buys user-planned skills after negative`() {
            val candidates =
                listOf(
                    SkillCandidate("Negative", price = 30, evaluationPoints = 20, isNegative = true),
                    SkillCandidate("User Skill A", price = 100, evaluationPoints = 80, isUserPlanned = true),
                    SkillCandidate("User Skill B", price = 120, evaluationPoints = 90, isUserPlanned = true),
                )
            val result = calculateCommonPurchases(candidates, 500, defaultSettings)
            assertEquals(3, result.size)
            assertEquals("Negative", result[0].first)
            assertEquals("User Skill A", result[1].first)
            assertEquals("User Skill B", result[2].first)
        }

        @Test
        fun `respects budget across all phases`() {
            val candidates =
                listOf(
                    SkillCandidate("Negative", price = 80, evaluationPoints = 20, isNegative = true),
                    SkillCandidate("User Skill A", price = 80, evaluationPoints = 80, isUserPlanned = true),
                )
            val result = calculateCommonPurchases(candidates, 150, defaultSettings)
            // Can afford Negative(80) + User Skill A(80) = 160 > 150
            // So only Negative(80) then can't afford User Skill A(80) at 70 remaining
            assertEquals(1, result.size)
            assertEquals("Negative", result[0].first)
        }

        @Test
        fun `does not buy duplicates`() {
            val candidates =
                listOf(
                    SkillCandidate("Dual Role", price = 50, evaluationPoints = 30, isNegative = true, isUserPlanned = true),
                )
            val result = calculateCommonPurchases(candidates, 1000, defaultSettings)
            // Should only appear once even though it matches both negative and user-planned phases.
            assertEquals(1, result.size)
        }

        @Test
        fun `bExcludeUniqueSkills filters planned inherited unique skills`() {
            // Production-layer behavior: when bExcludeUniqueSkills is on, isBlacklisted is precomputed true for any inherited-unique entry.
            // This test simulates that pre-computation and verifies the downstream candidate pipeline respects the flag.
            val settings = defaultSettings.copy(bExcludeUniqueSkills = true)
            val candidates =
                listOf(
                    SkillCandidate("Planned Inherited", price = 80, evaluationPoints = 120, isInheritedUnique = true, isUserPlanned = true, isBlacklisted = true),
                    SkillCandidate("Planned Regular", price = 50, evaluationPoints = 80, isUserPlanned = true),
                )
            val result = calculateCommonPurchases(candidates, 1000, settings)
            assertTrue(result.none { it.first == "Planned Inherited" }, "bExcludeUniqueSkills must filter out inherited unique skill from plan")
            assertTrue(result.any { it.first == "Planned Regular" }, "Non-unique planned skill should still be purchased")
        }
    }

    // =========================================================================
    // calculateSkillPurchases() - full orchestration
    // =========================================================================

    @Nested
    @DisplayName("calculateSkillPurchases()")
    inner class FullPurchaseTests {
        @Test
        fun `disabled plan returns empty`() {
            val settings =
                SkillPlanSettings(
                    bIsEnabled = false,
                    strategy = SpendingStrategy.DEFAULT,
                    bEnableBuyNegativeSkills = true,
                    skillNames = emptyList(),
                )
            val result =
                calculateSkillPurchases(
                    listOf(SkillCandidate("Skill", price = 50, evaluationPoints = 100)),
                    1000,
                    settings,
                )
            assertTrue(result.isEmpty())
        }

        @Test
        fun `DEFAULT strategy buys common then rank-optimized`() {
            val settings =
                SkillPlanSettings(
                    bIsEnabled = true,
                    strategy = SpendingStrategy.DEFAULT,
                    bEnableBuyNegativeSkills = true,
                    skillNames = emptyList(),
                )
            val candidates =
                listOf(
                    SkillCandidate("Negative", price = 30, evaluationPoints = 20, isNegative = true),
                    SkillCandidate("Best Ratio", price = 50, evaluationPoints = 200),
                    SkillCandidate("Worst Ratio", price = 100, evaluationPoints = 50),
                )
            val result = calculateSkillPurchases(candidates, 200, settings)
            // Negative first (common), then Best Ratio (rank strategy), then Worst Ratio
            assertTrue(result.isNotEmpty())
            assertEquals("Negative", result[0].first)
            assertEquals("Best Ratio", result[1].first)
        }

        @Test
        fun `OPTIMIZE_RANK buys by highest ratio`() {
            val settings =
                SkillPlanSettings(
                    bIsEnabled = true,
                    strategy = SpendingStrategy.OPTIMIZE_RANK,
                    bEnableBuyNegativeSkills = false,
                    skillNames = emptyList(),
                )
            val candidates =
                listOf(
                    SkillCandidate("A", price = 100, evaluationPoints = 100), // 1.0
                    SkillCandidate("B", price = 50, evaluationPoints = 150), // 3.0
                    SkillCandidate("C", price = 80, evaluationPoints = 160), // 2.0
                )
            val result = calculateSkillPurchases(candidates, 300, settings)
            assertEquals("B", result[0].first) // 3.0 ratio
            assertEquals("C", result[1].first) // 2.0 ratio
            assertEquals("A", result[2].first) // 1.0 ratio
        }

        @Test
        fun `OPTIMIZE_SKILLS prefers tiered skills then falls back to rank`() {
            val settings =
                SkillPlanSettings(
                    bIsEnabled = true,
                    strategy = SpendingStrategy.OPTIMIZE_SKILLS,
                    bEnableBuyNegativeSkills = false,
                    skillNames = emptyList(),
                )
            val candidates =
                listOf(
                    SkillCandidate("Tier SS", price = 150, evaluationPoints = 100, communityTier = 0), // SS
                    SkillCandidate("Tier B", price = 50, evaluationPoints = 80, communityTier = 3), // B
                    SkillCandidate("No Tier High Ratio", price = 30, evaluationPoints = 120), // No tier
                )
            val result = calculateSkillPurchases(candidates, 500, settings)
            // Tiered skills first (sorted by tier then ratio): SS then B
            // Then untiered via rank fallback
            assertTrue(result.any { it.first == "Tier SS" })
            assertTrue(result.any { it.first == "Tier B" })
            assertTrue(result.any { it.first == "No Tier High Ratio" })
        }

        // -----------------------------------------------------------------------
        // Random / stress tests with generated skill lists
        // -----------------------------------------------------------------------

        @Test
        fun `random skill list with OPTIMIZE_RANK respects budget invariant`() {
            val rng = Random(42)

            repeat(20) { iteration ->
                val budget = rng.nextInt(100, 2000)
                val numSkills = rng.nextInt(10, 50)

                val candidates =
                    (1..numSkills).map { i ->
                        SkillCandidate(
                            name = "Skill_$i",
                            price = rng.nextInt(20, 300),
                            evaluationPoints = rng.nextInt(10, 200),
                            isNegative = rng.nextDouble() < 0.1,
                            isInheritedUnique = rng.nextDouble() < 0.05,
                            isUserPlanned = rng.nextDouble() < 0.15,
                            communityTier = if (rng.nextBoolean()) rng.nextInt(0, 4) else null,
                        )
                    }

                val settings =
                    SkillPlanSettings(
                        bIsEnabled = true,
                        strategy = SpendingStrategy.OPTIMIZE_RANK,
                        bEnableBuyNegativeSkills = rng.nextBoolean(),
                        skillNames = candidates.filter { it.isUserPlanned }.map { it.name },
                    )

                val result = calculateSkillPurchases(candidates, budget, settings)
                val totalSpent = result.sumOf { it.second }

                assertTrue(totalSpent <= budget, "Iteration $iteration: spent $totalSpent > budget $budget")

                // No duplicates
                val names = result.map { it.first }
                assertEquals(names.size, names.distinct().size, "Iteration $iteration: duplicates found")
            }
        }

        @Test
        fun `random skill list with OPTIMIZE_SKILLS respects budget invariant`() {
            val rng = Random(99)

            repeat(20) { iteration ->
                val budget = rng.nextInt(200, 1500)
                val numSkills = rng.nextInt(15, 40)

                val candidates =
                    (1..numSkills).map { i ->
                        SkillCandidate(
                            name = "Skill_$i",
                            price = rng.nextInt(30, 250),
                            evaluationPoints = rng.nextInt(20, 180),
                            isNegative = rng.nextDouble() < 0.08,
                            isInheritedUnique = rng.nextDouble() < 0.05,
                            isUserPlanned = rng.nextDouble() < 0.1,
                            communityTier = if (rng.nextDouble() < 0.6) rng.nextInt(0, 4) else null,
                        )
                    }

                val settings =
                    SkillPlanSettings(
                        bIsEnabled = true,
                        strategy = SpendingStrategy.OPTIMIZE_SKILLS,
                        bEnableBuyNegativeSkills = true,
                        skillNames = candidates.filter { it.isUserPlanned }.map { it.name },
                    )

                val result = calculateSkillPurchases(candidates, budget, settings)
                val totalSpent = result.sumOf { it.second }

                assertTrue(totalSpent <= budget, "Iteration $iteration: spent $totalSpent > budget $budget")

                val names = result.map { it.first }
                assertEquals(names.size, names.distinct().size, "Iteration $iteration: duplicates found")
            }
        }

        @Test
        fun `stress test with 100 skills and tight budget`() {
            val rng = Random(777)
            val candidates =
                (1..100).map { i ->
                    SkillCandidate(
                        name = "Skill_$i",
                        price = rng.nextInt(10, 500),
                        evaluationPoints = rng.nextInt(5, 300),
                        isNegative = i <= 5,
                        isInheritedUnique = i in 6..8,
                        isUserPlanned = i in 9..15,
                        communityTier = if (i <= 60) i % 4 else null,
                    )
                }

            val settings =
                SkillPlanSettings(
                    bIsEnabled = true,
                    strategy = SpendingStrategy.OPTIMIZE_SKILLS,
                    bEnableBuyNegativeSkills = true,
                    skillNames = candidates.filter { it.isUserPlanned }.map { it.name },
                )

            val result = calculateSkillPurchases(candidates, 500, settings)
            val totalSpent = result.sumOf { it.second }

            assertTrue(totalSpent <= 500)
            assertTrue(result.isNotEmpty(), "Should buy at least some skills with 500 SP")

            // Negative skills should come first if bought
            val negativeIndices =
                result.mapIndexedNotNull { idx, (name, _) ->
                    if (candidates.find { it.name == name }?.isNegative == true) idx else null
                }
            val nonNegativeIndices =
                result.mapIndexedNotNull { idx, (name, _) ->
                    if (candidates.find { it.name == name }?.isNegative != true) idx else null
                }
            if (negativeIndices.isNotEmpty() && nonNegativeIndices.isNotEmpty()) {
                assertTrue(
                    negativeIndices.max() < nonNegativeIndices.min(),
                    "Negative skills should be purchased before other skills",
                )
            }
        }

        @Test
        fun `OPTIMIZE_RANK skips double-circle skills when toggle on`() {
            val settings =
                SkillPlanSettings(
                    bIsEnabled = true,
                    strategy = SpendingStrategy.OPTIMIZE_RANK,
                    bEnableBuyNegativeSkills = false,
                    skillNames = emptyList(),
                    bExcludeDoubleCircleSkills = true,
                )
            // ASCII names; detection here is via the isDoubleCircle flag (mirrors real Winter Runner single/double variants).
            val candidates =
                listOf(
                    SkillCandidate("Winter Runner (double)", price = 110, evaluationPoints = 174, isDoubleCircle = true),
                    SkillCandidate("Winter Runner (single)", price = 90, evaluationPoints = 129),
                )
            val result = calculateSkillPurchases(candidates, 1000, settings)
            assertTrue(result.none { it.first == "Winter Runner (double)" })
            assertTrue(result.any { it.first == "Winter Runner (single)" })
        }

        @Test
        fun `planned double-circle skill is still bought when toggle on`() {
            val settings =
                SkillPlanSettings(
                    bIsEnabled = true,
                    strategy = SpendingStrategy.OPTIMIZE_RANK,
                    bEnableBuyNegativeSkills = false,
                    skillNames = listOf("Nakayama Racecourse (double)"),
                    bExcludeDoubleCircleSkills = true,
                )
            val candidates =
                listOf(
                    SkillCandidate("Nakayama Racecourse (double)", price = 110, evaluationPoints = 174, isDoubleCircle = true, isUserPlanned = true),
                )
            val result = calculateSkillPurchases(candidates, 1000, settings)
            assertTrue(result.any { it.first == "Nakayama Racecourse (double)" })
        }

        @Test
        fun `double-circle skill is bought when toggle off`() {
            val settings =
                SkillPlanSettings(
                    bIsEnabled = true,
                    strategy = SpendingStrategy.OPTIMIZE_RANK,
                    bEnableBuyNegativeSkills = false,
                    skillNames = emptyList(),
                    bExcludeDoubleCircleSkills = false,
                )
            val candidates = listOf(SkillCandidate("Winter Runner (double)", price = 110, evaluationPoints = 174, isDoubleCircle = true))
            val result = calculateSkillPurchases(candidates, 1000, settings)
            assertTrue(result.any { it.first == "Winter Runner (double)" })
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Blacklist behavior

    @Nested
    @DisplayName("Skill blacklist (per-skill and category)")
    inner class BlacklistTests {
        @Test
        fun `blacklisted skill never appears in OPTIMIZE_RANK output even with the best ratio`() {
            // The blacklisted skill has the highest ratio (10.0). Without the filter it would always be picked first.
            val candidates =
                listOf(
                    SkillCandidate("Top Pick", price = 10, evaluationPoints = 100, isBlacklisted = true),
                    SkillCandidate("Runner Up", price = 50, evaluationPoints = 100),
                    SkillCandidate("Third", price = 100, evaluationPoints = 50),
                )

            val result = calculateOptimizeRankPurchases(candidates, budget = 200)

            assertFalse(result.any { it.first == "Top Pick" }, "Blacklisted skill must not be purchased")
            assertTrue(result.any { it.first == "Runner Up" }, "Non-blacklisted skill should still be purchased")
        }

        @Test
        fun `blacklisted skill on user plan is skipped even when the plan would otherwise buy it`() {
            // Blacklist takes precedence over the user's planned-skill list to keep behavior predictable.
            val candidates =
                listOf(
                    SkillCandidate("Planned & Blacklisted", price = 50, evaluationPoints = 80, isUserPlanned = true, isBlacklisted = true),
                    SkillCandidate("Planned", price = 50, evaluationPoints = 80, isUserPlanned = true),
                )
            val settings =
                SkillPlanSettings(
                    bIsEnabled = true,
                    strategy = SpendingStrategy.DEFAULT,
                    bEnableBuyNegativeSkills = false,
                    skillNames = listOf("Planned & Blacklisted", "Planned"),
                )

            val result = calculateCommonPurchases(candidates, budget = 200, settings = settings)

            assertFalse(result.any { it.first == "Planned & Blacklisted" }, "Blacklist must override user plan")
            assertTrue(result.any { it.first == "Planned" }, "Non-blacklisted planned skill should still be purchased")
        }

        @Test
        fun `category exclusion drops every matching skill regardless of strategy`() {
            // Simulates the production layer setting isBlacklisted = true on every skill in an excluded color category.
            val candidates =
                (1..5).map { i ->
                    SkillCandidate(
                        name = "Green_$i",
                        price = 30,
                        evaluationPoints = 80,
                        isBlacklisted = true,
                    )
                } +
                    listOf(
                        SkillCandidate("Yellow_1", price = 50, evaluationPoints = 60),
                        SkillCandidate("Yellow_2", price = 60, evaluationPoints = 50),
                    )
            val settings =
                SkillPlanSettings(
                    bIsEnabled = true,
                    strategy = SpendingStrategy.OPTIMIZE_RANK,
                    bEnableBuyNegativeSkills = false,
                    skillNames = emptyList(),
                )

            val result = calculateSkillPurchases(candidates, budget = 500, settings = settings)

            assertTrue(result.none { it.first.startsWith("Green_") }, "No green skills should be purchased")
            assertTrue(result.any { it.first.startsWith("Yellow_") }, "Yellow skills are still eligible")
        }

        @Test
        fun `empty plan with both flags off causes common phase to return empty - the no skills bought scenario`() {
            // Pins the behavior reported by users as "bot doesn't buy any skills even though I created a planner".
            // The production strategy DEFAULT (UI label "Do Not Spend Remaining Points") delegates to getSkillsToBuyDefaultStrategy
            // which returns emptyMap, so the only purchases come from the common phase. With an empty plan list and both
            // auto-buy flags off, the common phase also returns empty - meaning the bot buys nothing.
            // (Note: the pure calculateSkillPurchases treats DEFAULT == OPTIMIZE_RANK, so we assert the common phase directly.)
            val candidates =
                listOf(
                    SkillCandidate("A", price = 50, evaluationPoints = 100),
                    SkillCandidate("B", price = 80, evaluationPoints = 200),
                    SkillCandidate("Negative", price = 30, evaluationPoints = 20, isNegative = true),
                    SkillCandidate("Inherited", price = 60, evaluationPoints = 90, isInheritedUnique = true),
                )
            val settings =
                SkillPlanSettings(
                    bIsEnabled = true,
                    strategy = SpendingStrategy.DEFAULT,
                    bEnableBuyNegativeSkills = false,
                    skillNames = emptyList(),
                )

            val commonResult = calculateCommonPurchases(candidates, budget = 1000, settings = settings)

            assertTrue(commonResult.isEmpty(), "Common phase with no plan and both flags off should buy nothing")
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // matchesPreference()

    @Nested
    @DisplayName("matchesPreference()")
    inner class MatchesPreferenceTests {
        @Test
        fun `no preference set means everything matches`() {
            assertTrue(matchesPreference(TrackDistance.SPRINT, RunningStyle.LATE_SURGER, emptyList(), TrackSurface.DIRT, null, null, null))
        }

        @Test
        fun `matching distance is kept and off-distance is excluded`() {
            assertTrue(matchesPreference(TrackDistance.MEDIUM, null, emptyList(), null, TrackDistance.MEDIUM, null, null))
            assertFalse(matchesPreference(TrackDistance.SPRINT, null, emptyList(), null, TrackDistance.MEDIUM, null, null))
        }

        @Test
        fun `generic skill with null axis is always kept`() {
            assertTrue(matchesPreference(null, null, emptyList(), null, TrackDistance.MEDIUM, RunningStyle.FRONT_RUNNER, TrackSurface.TURF))
        }

        @Test
        fun `explicit running style match is kept`() {
            assertTrue(matchesPreference(null, RunningStyle.FRONT_RUNNER, emptyList(), null, null, RunningStyle.FRONT_RUNNER, null))
        }

        @Test
        fun `inferred running style match is kept`() {
            assertTrue(matchesPreference(null, null, listOf(RunningStyle.FRONT_RUNNER), null, null, RunningStyle.FRONT_RUNNER, null))
        }

        @Test
        fun `committed running style with no matching inferred is excluded`() {
            assertFalse(matchesPreference(null, RunningStyle.LATE_SURGER, listOf(RunningStyle.LATE_SURGER), null, null, RunningStyle.FRONT_RUNNER, null))
        }

        @Test
        fun `matching surface kept and off-surface excluded`() {
            assertTrue(matchesPreference(null, null, emptyList(), TrackSurface.TURF, null, null, TrackSurface.TURF))
            assertFalse(matchesPreference(null, null, emptyList(), TrackSurface.DIRT, null, null, TrackSurface.TURF))
        }

        @Test
        fun `one off-axis fails the whole match`() {
            // distance matches MEDIUM but surface DIRT != preferred TURF -> excluded
            assertFalse(matchesPreference(TrackDistance.MEDIUM, null, emptyList(), TrackSurface.DIRT, TrackDistance.MEDIUM, null, TrackSurface.TURF))
        }

        @Test
        fun `explicit style match passes despite non-matching inferred styles`() {
            assertTrue(matchesPreference(null, RunningStyle.FRONT_RUNNER, listOf(RunningStyle.LATE_SURGER), null, null, RunningStyle.FRONT_RUNNER, null))
        }
    }

    @Nested
    @DisplayName("Recovery-skill priority")
    inner class RecoveryPriorityTests {
        @Test
        fun `recovery skills are detected from their description`() {
            assertTrue(isRecoverySkill("Slightly recovers endurance on the final corner."), "A description mentioning recovery is a recovery skill")
            assertTrue(isRecoverySkill("RECOVER stamina when overtaken."), "Detection is case-insensitive")
            assertFalse(isRecoverySkill("Increases velocity slightly on a straight."), "A pure speed skill is not a recovery skill")
        }

        @Test
        fun `only Medium and Long builds are stamina-heavy`() {
            assertTrue(isStaminaHeavyDistance(TrackDistance.MEDIUM), "Medium leans on stamina")
            assertTrue(isStaminaHeavyDistance(TrackDistance.LONG), "Long leans on stamina the most")
            assertFalse(isStaminaHeavyDistance(TrackDistance.SPRINT), "Sprint does not lean on recovery")
            assertFalse(isStaminaHeavyDistance(TrackDistance.MILE), "Mile does not lean on recovery")
            assertFalse(isStaminaHeavyDistance(null), "No distance preference is not stamina-heavy")
        }

        @Test
        fun `recovery ratio is boosted only for recovery skills on stamina-heavy builds`() {
            assertEquals(3.0, recoveryBoostedRatio(baseRatio = 2.0, isRecovery = true, staminaHeavy = true, boost = 1.5), 0.001, "Recovery skill on a stamina build is boosted")
            assertEquals(2.0, recoveryBoostedRatio(baseRatio = 2.0, isRecovery = false, staminaHeavy = true, boost = 1.5), 0.001, "A non-recovery skill is never boosted")
            assertEquals(2.0, recoveryBoostedRatio(baseRatio = 2.0, isRecovery = true, staminaHeavy = false, boost = 1.5), 0.001, "Recovery skill on a non-stamina build is not boosted")
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Leftover point drain

    @Nested
    @DisplayName("calculateLeftoverDrainPurchases()")
    inner class LeftoverDrainTests {
        /** The total price of a set of picks. */
        private fun spend(picks: List<Pair<String, Int>>): Int = picks.sumOf { it.second }

        @Test
        fun `spends leftover points that the normal strategies would have stranded`() {
            // The reported case: a career ended with 67 points unspent while a 66-point skill sat available in the list.
            val candidates =
                listOf(
                    SkillCandidate(name = "Dodging Danger", price = 66, evaluationPoints = 103),
                    SkillCandidate(name = "Long Corners", price = 70, evaluationPoints = 195),
                    SkillCandidate(name = "Competitive Spirit", price = 72, evaluationPoints = 129),
                )

            val picks = calculateLeftoverDrainPurchases(candidates, budget = 67)

            assertEquals(listOf("Dodging Danger" to 66), picks, "The only affordable skill is bought, leaving 1 point behind instead of 67")
        }

        @Test
        fun `finds an exact-spend combination that a greedy pass would miss`() {
            // A greedy "buy the biggest that fits" pass would take 99 and strand 1 point. The exact search takes 60 + 40 and strands nothing.
            val candidates =
                listOf(
                    SkillCandidate(name = "Expensive", price = 99, evaluationPoints = 300),
                    SkillCandidate(name = "Medium", price = 60, evaluationPoints = 100),
                    SkillCandidate(name = "Cheap", price = 40, evaluationPoints = 80),
                )

            val picks = calculateLeftoverDrainPurchases(candidates, budget = 100)

            assertEquals(100, spend(picks), "The budget is spent down to exactly zero")
            assertEquals(listOf("Cheap", "Medium"), picks.map { it.first }, "The two cheaper skills are bought instead of the single expensive one")
        }

        @Test
        fun `breaks ties on total evaluation points`() {
            // Both "Rich" alone and "PoorA" + "PoorB" spend the full 100, so the more valuable subset wins.
            val candidates =
                listOf(
                    SkillCandidate(name = "PoorA", price = 50, evaluationPoints = 10),
                    SkillCandidate(name = "PoorB", price = 50, evaluationPoints = 10),
                    SkillCandidate(name = "Rich", price = 100, evaluationPoints = 500),
                )

            val picks = calculateLeftoverDrainPurchases(candidates, budget = 100)

            assertEquals(100, spend(picks), "Both subsets spend the whole budget")
            assertEquals(listOf("Rich" to 100), picks, "The subset worth more evaluation points wins the tie")
        }

        @Test
        fun `never buys a blacklisted skill even when it fits perfectly`() {
            val candidates =
                listOf(
                    SkillCandidate(name = "Banned", price = 100, evaluationPoints = 500),
                    SkillCandidate(name = "Allowed", price = 90, evaluationPoints = 50),
                )

            val picks = calculateLeftoverDrainPurchases(candidates, budget = 100, blacklist = listOf("Banned"))

            assertEquals(listOf("Allowed" to 90), picks, "The blacklisted skill is skipped even though it would spend the budget exactly")
        }

        @Test
        fun `does not re-buy a skill the plan already covers`() {
            val candidates =
                listOf(
                    SkillCandidate(name = "Planned", price = 100, evaluationPoints = 500),
                    SkillCandidate(name = "Extra", price = 90, evaluationPoints = 50),
                )

            val picks = calculateLeftoverDrainPurchases(candidates, budget = 100, alreadyPlanned = listOf("Planned"))

            assertEquals(listOf("Extra" to 90), picks, "An already-planned skill is not bought a second time")
        }

        @Test
        fun `never exceeds the budget`() {
            val random = Random(1316)
            repeat(200) {
                val budget = random.nextInt(0, 2000)
                val candidates =
                    List(random.nextInt(1, 40)) { index ->
                        SkillCandidate(name = "Skill$index", price = random.nextInt(1, 500), evaluationPoints = random.nextInt(0, 900))
                    }

                val picks = calculateLeftoverDrainPurchases(candidates, budget)

                assertTrue(spend(picks) <= budget, "Spend stays within the budget")
                assertEquals(picks.size, picks.map { it.first }.distinct().size, "No skill is bought twice")

                // Nothing affordable may be left behind, otherwise the drain failed at its one job.
                val remaining = budget - spend(picks)
                val bought = picks.map { it.first }.toSet()
                assertTrue(candidates.none { it.name !in bought && it.price <= remaining }, "No affordable skill is left unbought")
            }
        }

        @Test
        fun `returns nothing for degenerate inputs`() {
            val candidates = listOf(SkillCandidate(name = "Skill", price = 100, evaluationPoints = 50))

            assertTrue(calculateLeftoverDrainPurchases(candidates, budget = 0).isEmpty(), "A zero budget buys nothing")
            assertTrue(calculateLeftoverDrainPurchases(candidates, budget = -50).isEmpty(), "A negative budget buys nothing")
            assertTrue(calculateLeftoverDrainPurchases(emptyList(), budget = 500).isEmpty(), "An empty skill list buys nothing")
            assertTrue(calculateLeftoverDrainPurchases(candidates, budget = 99).isEmpty(), "Nothing is bought when everything is too expensive")
            assertTrue(calculateLeftoverDrainPurchases(candidates, budget = 999999).isEmpty(), "An implausible budget from a bad OCR read is rejected")
        }
    }
}
