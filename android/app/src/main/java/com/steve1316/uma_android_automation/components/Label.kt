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
 * @param confidence Match threshold (0.0, 1.0]. Defaults to 0.0, which uses the global default.
 * @return A [ComponentInterface] wrapping the resolved template.
 */
private fun label(name: String, region: IntArray = intArrayOf(0, 0, 0, 0), confidence: Double = 0.0): ComponentInterface =
    object : ComponentInterface {
        override val template = Template("components/label/$name", region = region, confidence = confidence)
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

// The Elite Team's S badge sits on a pink card, so a normal (white-card) row only scores ~0.75 against it. Held at 0.9 to keep that clear.
val LabelUnityCupEliteTeamBadge = label("unitycup_elite_team_badge", Region.leftHalf, confidence = 0.9)
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
val LabelConnecting = label("connecting", Region.topRightSixth)
val LabelNowLoading = label("now_loading", Region.bottomRightQuarter)
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
val LabelGrandLiveLearnable = label("grandlive_learnable", Region.rightHalf)
val LabelGrandLiveMaxHype = label("grandlive_max_hype")
val LabelGrandLivePerformancePoints = label("grandlive_performance_points", Region.leftHalf)
val LabelGrandLiveLessonCost = label("grandlive_performance_point_cost", Region.leftHalf)

// Dailies
val LabelClubMenu = label("club_menu", Region.topQuarter)

/** Name logos on the Daily Legend Races cards, keyed by the legend's name as stored in settings. Pass the grid's region when finding one. */
val LabelDailyLegendLogos: Map<String, ComponentInterface> =
    mapOf(
        "Agnes Digital" to label("daily_legend_agnes_digital", confidence = 0.8),
        "Air Groove" to label("daily_legend_air_groove", confidence = 0.8),
        "Biwa Hayahide" to label("daily_legend_biwa_hayahide", confidence = 0.8),
        "Curren Chan" to label("daily_legend_curren_chan", confidence = 0.8),
        "Eishin Flash" to label("daily_legend_eishin_flash", confidence = 0.8),
        "El Condor Pasa" to label("daily_legend_el_condor_pasa", confidence = 0.8),
        "Fuji Kiseki" to label("daily_legend_fuji_kiseki", confidence = 0.8),
        "Gold Ship" to label("daily_legend_gold_ship", confidence = 0.8),
        "Grass Wonder" to label("daily_legend_grass_wonder", confidence = 0.8),
        "King Halo" to label("daily_legend_king_halo", confidence = 0.8),
        "Maruzensky" to label("daily_legend_maruzensky", confidence = 0.8),
        "Matikanefukukitaru" to label("daily_legend_matikanefukukitaru", confidence = 0.8),
        "Mayano Top Gun" to label("daily_legend_mayano_top_gun", confidence = 0.8),
        "Mejiro McQueen" to label("daily_legend_mejiro_mcqueen", confidence = 0.8),
        "Mejiro Ryan" to label("daily_legend_mejiro_ryan", confidence = 0.8),
        "Mihono Bourbon" to label("daily_legend_mihono_bourbon", confidence = 0.8),
        "Nice Nature" to label("daily_legend_nice_nature", confidence = 0.8),
        "Oguri Cap" to label("daily_legend_oguri_cap", confidence = 0.8),
        "Sakura Bakushin O" to label("daily_legend_sakura_bakushin_o", confidence = 0.8),
        "Seiun Sky" to label("daily_legend_seiun_sky", confidence = 0.8),
        "Silence Suzuka" to label("daily_legend_silence_suzuka", confidence = 0.8),
        "Special Week" to label("daily_legend_special_week", confidence = 0.8),
        "Super Creek" to label("daily_legend_super_creek", confidence = 0.8),
        "Symboli Rudolf" to label("daily_legend_symboli_rudolf", confidence = 0.8),
        "T.M. Opera O" to label("daily_legend_t_m_opera_o", confidence = 0.8),
        "Taiki Shuttle" to label("daily_legend_taiki_shuttle", confidence = 0.8),
        "Tokai Teio" to label("daily_legend_tokai_teio", confidence = 0.8),
        "Vodka" to label("daily_legend_vodka", confidence = 0.8),
        "Winning Ticket" to label("daily_legend_winning_ticket", confidence = 0.8),
    )
val LabelDailyLegendRacesHeader = label("daily_legend_races_header", Region.topQuarter, confidence = 0.9)
val LabelDailyPrograms = label("daily_programs", Region.middle)
val LabelDailyRacesHeader = label("daily_races_header", Region.topQuarter, confidence = 0.9)
val LabelDoneForToday = label("done_for_today", Region.bottomHalf)
val LabelItemRequestExpired = label("item_request_expired", Region.bottomHalf)
val LabelItemRequestHeld = label("item_request_held")
val LabelItemRequestSelectPrompt = label("item_request_select_prompt", Region.bottomHalf)
val LabelMainRewards = label("main_rewards", Region.middle)
val LabelMissionsHeader = label("missions_header", Region.topQuarter)
val LabelRunnerSelection = label("runner_selection", Region.topQuarter)
val LabelRunnerSpeedHeader = label("runner_speed_header", Region.middle)
val LabelSelectOpponent = label("select_opponent", Region.topQuarter)
val LabelStarUnlockText = label("star_unlock_text", confidence = 0.85)
val LabelTeamRankBadge = label("team_rank_badge", Region.leftHalf)
val LabelTopBarRp = label("top_bar_rp", Region.topQuarter)

// Career Setup
val LabelCareerStartScenarioSelectHeader = label("career_start_scenario_select_header", Region.topQuarter, confidence = 0.9)
val LabelCareerStartTraineeSelectHeader = label("career_start_trainee_select_header", Region.topQuarter, confidence = 0.9)
val LabelCareerStartLegacySelectHeader = label("career_start_legacy_select_header", Region.topQuarter, confidence = 0.9)
val LabelCareerStartSupportFormationHeader = label("career_start_support_formation_header", Region.topQuarter, confidence = 0.9)

// The label sits on each trainee's card art, which drags some matches down to 0.84, so 0.85 missed up to 4 of 10 cards.
val LabelCareerStartPotentialLvl = label("career_start_potential_lvl", confidence = 0.75)
val LabelCareerStartLegacyDuplicate = label("career_start_legacy_duplicate", confidence = 0.85)
val LabelCareerStartLegacy1Tag = label("career_start_legacy_1_tag", confidence = 0.85)

/** The pink "Free once per day!" banner above a guest's Monies Required, shown only while the daily free borrow is unused. */
val LabelCareerStartLegacyFreeBorrow = label("career_start_legacy_free_borrow", Region.topHalf, confidence = 0.85)
val LabelCareerStartDuplicateSupport = label("career_start_duplicate_support", confidence = 0.85)
val LabelCareerStartLastLogin = label("career_start_last_login", confidence = 0.9)

/** The "TRAINING COMPLETE!" label on the Independent Training dialog. While training it shows "Training..." instead. */
val LabelCareerStartIndependentTrainingComplete = label("career_start_independent_training_complete")

/** Spark badge labels keyed by the label text, grouped by badge color. */
val LabelCareerStartSparkStat: Map<String, ComponentInterface> = listOf("SPD", "STA", "PWR", "GUTS", "WIT").associateWith { label("career_start_spark_${it.lowercase()}") }
val LabelCareerStartSparkAptitude: Map<String, ComponentInterface> =
    listOf("TURF", "DIRT", "SPRNT", "MILE", "MED", "LONG", "FRONT", "PACE", "LATE", "END").associateWith { label("career_start_spark_${it.lowercase()}") }
val LabelCareerStartSparkUnique: Map<String, ComponentInterface> = mapOf("UNIQ" to label("career_start_spark_uniq"))
