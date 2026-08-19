/**
 * Defines label components.
 *
 * These are non-clickable regions of text on screen.
 */

package com.steve1316.uma_android_automation.components

/**
 * Builds a non-clickable label component backed by the `components/label/[name]` template.
 *
 * @param name Leaf filename under `components/label/` identifying the template image.
 * @param region Screen region to constrain matching to. Defaults to the whole screen.
 * @return A [ComponentInterface] wrapping the resolved template.
 */
private fun label(name: String, region: IntArray = intArrayOf(0, 0, 0, 0)): ComponentInterface =
    object : ComponentInterface {
        override val template = Template("components/label/$name", region = region)
    }

val LabelCongratulations = label("congratulations", Region.topHalf)
val LabelStatDistance = label("stat_distance", Region.topHalf)
val LabelStatTrackSurface = label("stat_track_surface", Region.topHalf)
val LabelStatStyle = label("stat_style", Region.topHalf)
val LabelUmamusumeClassFans = label("umamusume_class_fans", Region.middle)
val LabelStatTableHeaderSkillPoints = label("stat_table_header_skill_points", Region.bottomHalf)
val LabelTrainingFailureChance = label("training_failure_chance", Region.bottomHalf)
val LabelWinToBecomeRank = label("win_to_become_rank")
val LabelUnityCupOpponentSelectionLaurel = label("unitycup_opponent_selection_laurel", Region.leftHalf)
val LabelDuel = label("duel", Region.bottomHalf)
val LabelDuelSmall = label("duel_small", Region.bottomHalf)
val LabelEnergy = label("energy")
val LabelEnergyBarLeftPart = label("energy_bar_left_part")
val LabelEnergyBarRightPart = label("energy_bar_right_part_0")
val LabelEnergyBarExtendedRightPart = label("energy_bar_right_part_1")
val LabelSkillListScreenSkillPoints = label("skill_list_screen_skill_points", Region.topHalf)
val LabelScheduledRace = label("scheduled_race", Region.bottomHalf)
val LabelStrategy = label("strategy")
val LabelTrainingCannotPerform = label("training_cannot_perform", Region.middle)
val LabelTrophyWonDialogTitle = label("trophy_won")
val LabelConnecting = label("connecting", Region.topHalf)
val LabelNowLoading = label("now_loading", Region.bottomHalf)
val LabelOrdinaryCuties = label("ordinary_cuties", Region.middle)
val LabelStatMaxed = label("stat_maxed")
val LabelStatAptitudeA = label("stat_aptitude_A")
val LabelStatAptitudeB = label("stat_aptitude_B")
val LabelStatAptitudeC = label("stat_aptitude_C")
val LabelStatAptitudeD = label("stat_aptitude_D")
val LabelStatAptitudeE = label("stat_aptitude_E")
val LabelStatAptitudeF = label("stat_aptitude_F")
val LabelStatAptitudeG = label("stat_aptitude_G")
val LabelStatAptitudeS = label("stat_aptitude_S")
val LabelRecreationDateComplete = label("recreation_date_complete", Region.middle)
val LabelRaceSelectionFans = label("race_selection_fans", Region.bottomHalf)
val LabelRaceCriteriaFans = label("race_criteria_fans", Region.topHalf)
val LabelRaceCriteriaG1 = label("race_criteria_g1", Region.topHalf)
val LabelRaceCriteriaG3OrAbove = label("race_criteria_g3_or_above", Region.topHalf)
val LabelRaceCriteriaMaiden = label("race_criteria_maiden", Region.topHalf)
val LabelRaceCriteriaPreOpOrAbove = label("race_criteria_pre_op_or_above", Region.topHalf)
val LabelRaceCriteriaTrophies = label("race_criteria_trophies", Region.topHalf)
val LabelThereAreNoRacesToCompeteIn = label("there_are_no_races_to_compete_in", Region.middle)
val LabelEventProgress = label("event_progress", Region.middle)
val LabelRecreationUmamusume = label("recreation_umamusume", Region.middle)
val LabelOnSale = label("on_sale", Region.topHalf)
val LabelPurchased = label("purchased")
val LabelRivalRacer = label("rival_racer", Region.rightHalf)
val LabelGrandLiveLearnableTechnique = label("grandlive_learnable_technique")
val LabelGrandLiveLearnableSong = label("grandlive_learnable_song")
val LabelGrandLiveMaxHype = label("grandlive_max_hype")
val LabelGrandLivePerformancePoints = label("grandlive_performance_points", Region.leftHalf)
val LabelGrandLiveLessonCost = label("grandlive_performance_point_cost", Region.leftHalf)
