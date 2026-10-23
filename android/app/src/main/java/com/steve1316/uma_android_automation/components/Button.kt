/**
 * Defines button components.
 *
 * Buttons are any element on screen that can be clicked to perform an action.
 *
 * Do not add checkboxes or radio buttons to this file. Those have their own files.
 *
 * Some buttons may have multiple different states. These should use the MultiStateButtonInterface interface instead of ButtonInterface.
 */

package com.steve1316.uma_android_automation.components

/**
 * Builds a clickable button component backed by the `components/button/[name]` template.
 *
 * @param name Leaf filename under `components/button/` identifying the template image.
 * @param region Screen region to constrain matching to. Defaults to the whole screen.
 * @param confidence Match threshold (0.0, 1.0]. Defaults to 0.0, which uses the global default.
 * @return A [ButtonInterface] wrapping the resolved template.
 */
private fun button(name: String, region: IntArray = intArrayOf(0, 0, 0, 0), confidence: Double = 0.0): ButtonInterface =
    object : ButtonInterface {
        override val template = Template("components/button/$name", region = region, confidence = confidence)
    }

val ButtonAgenda = button("agenda", Region.bottomHalf)
val ButtonAutoSelect = button("auto_select")
val ButtonBack = button("back", Region.bottomHalf)
val ButtonBackGreen = button("back_green", Region.bottomHalf)
val ButtonBeginShowdown = button("begin_showdown")
val ButtonBorrowSupportCard = button("borrow_support_card")
val ButtonBurger = button("burger", Region.bottomHalf)
val ButtonCancel = button("cancel", Region.bottomHalf)
val ButtonCareer = button("career")
val ButtonChangeRunningStyle = button("change")
val ButtonClose = button("close", Region.bottomHalf)
val ButtonCollectAll = button("collect_all", Region.bottomHalf)
val ButtonConfirm = button("confirm", Region.bottomHalf)
val ButtonConfirmExclamation = button("confirm_exclamation", Region.bottomHalf)
val ButtonDailyRaces = button("daily_races")
val ButtonDailyRacesDisabled = button("daily_races_disabled")
val ButtonDailyRacesJupiterCup = button("daily_races_jupiter_cup_logo")
val ButtonDailyRacesMoonlightSho = button("daily_races_moonlight_sho_logo")
val ButtonEditTeam = button("edit_team")
val ButtonFollow = button("follow")
val ButtonFinish = button("finish")
val ButtonGiveUp = button("give_up")
val ButtonToHome = button("to_home")
val ButtonHomeSpecialMissions = button("home_special_missions")
val ButtonHomePresents = button("home_presents")
val ButtonSpecialMissionsTabDaily = button("special_missions_tab_daily")
val ButtonSpecialMissionsTabMain = button("special_missions_tab_main")
val ButtonSpecialMissionsTabTitles = button("special_missions_tab_titles")
val ButtonSpecialMissionsTabSpecial = button("special_missions_tab_special")
val ButtonLater = button("later")
val ButtonLegendRace = button("legend_race")
val ButtonLegendRaceDisabled = button("legend_race_disabled")
val ButtonRaceHardInactive = button("race_hard_inactive")
val ButtonRaceHardActive = button("race_hard_active")
val ButtonLegendRaceHomeSpecialMissions = button("legend_race_special_missions")
val ButtonLog = button("log", Region.bottomHalf)
val ButtonNext = button("next", Region.bottomHalf)
val ButtonNextWithImage = button("next_with_image", Region.bottomHalf)
val ButtonNextRaceEnd = button("next_race_end", Region.bottomHalf)
val ButtonNo = button("no", Region.bottomHalf)
val ButtonOk = button("ok", Region.bottomHalf)
val ButtonOptions = button("options", Region.bottomHalf)
val ButtonLearn = button("learn")
val ButtonReset = button("reset", Region.bottomHalf)
val ButtonRace = button("race", Region.bottomHalf)
val ButtonRaceDayRace = button("race_day_race", Region.bottomHalf)

// Grand Live's race-day bottom row is Skills / Race / Lessons, so its Race button renders smaller than the standard race-day template.
val ButtonRaceDayMini = button("race_day_race_mini", Region.bottomHalf)
val ButtonRaceAgain = button("race_again", Region.bottomHalf)
val ButtonRaceDetails = button("race_details", Region.bottomHalf)
val ButtonRaceEvents = button("race_events")
val ButtonRaceExclamation = button("race_exclamation", Region.bottomHalf)
val ButtonRaceExclamationShiftedUp = button("race_exclamation_shifted_up", Region.middle)
val ButtonRaceManual = button("race_manual", Region.bottomHalf)
val ButtonRaceRecommendationsCenterStage = button("race_recommendations_center_stage")
val ButtonRaceRecommendationsPathToFame = button("race_recommendations_path_to_fame")
val ButtonRaceRecommendationsForgeYourOwnPath = button("race_recommendations_forge_your_own_path")
val ButtonRaceResults = button("race_results")
val ButtonRestore = button("restore")
val ButtonRetry = button("retry")
val ButtonResume = button("resume")
val ButtonSave = button("save", Region.bottomHalf)
val ButtonSaveSchedule = button("save_schedule", Region.bottomHalf)
val ButtonSaveAndExit = button("save_and_exit", Region.bottomHalf)
val ButtonSeeResults = button("see_results", Region.bottomHalf)
val ButtonSelectOpponent = button("select_opponent", Region.bottomHalf)
val ButtonSelectLegacy = button("select_legacy")
val ButtonShop = button("shop")
val ButtonSkip = button("skip", Region.bottomHalf)
val ButtonSkills = button("skills", Region.bottomHalf)
val ButtonStartCareer = button("start_career", Region.bottomHalf)
val ButtonStartCareerOffset = button("start_career_offset", Region.bottomHalf)
val ButtonTeamRace = button("team_race")
val ButtonTeamTrials = button("team_trials")
val ButtonTitleScreen = button("title_screen")
val ButtonTryAgain = button("try_again", Region.bottomHalf)
val ButtonTryAgainAlt = button("try_again_alt", Region.bottomHalf)
val ButtonViewResults = button("view_results", Region.bottomHalf)
val ButtonWatchConcert = button("watch_concert", Region.bottomHalf)
val ButtonRaceStrategyFront = button("strategy_front_select", Region.middle)
val ButtonRaceStrategyPace = button("strategy_pace_select", Region.middle)
val ButtonRaceStrategyLate = button("strategy_late_select", Region.middle)
val ButtonRaceStrategyEnd = button("strategy_end_select", Region.middle)

// More complex buttons

val ButtonMenuBarHomeSelected = button("menu_bar_home_selected")
val ButtonMenuBarHomeUnselected = button("menu_bar_home_unselected")

object ButtonMenuBarHome : MultiStateButtonInterface {
    override val templates: List<Template> =
        listOf(
            Template("components/button/menu_bar_home_unselected"),
            Template("components/button/menu_bar_home_selected"),
        )
}

val ButtonMenuBarRaceSelected = button("menu_bar_race_selected")
val ButtonMenuBarRaceUnselected = button("menu_bar_race_unselected")

object ButtonMenuBarRace : MultiStateButtonInterface {
    override val templates: List<Template> =
        listOf(
            Template("components/button/menu_bar_race_unselected"),
            Template("components/button/menu_bar_race_selected"),
        )
}

val ButtonCompleteCareer = button("complete_career", Region.bottomHalf)
val ButtonCareerEndSkills = button("career_end_skills")

// Grand Live's career-end screen has a three-button bottom row (Skills / Complete Career / Lessons), so the Skills button renders smaller than the standard template.
val ButtonCareerEndSkillsMini = button("career_end_skills_mini")
val ButtonClawMachine = button("claw_machine", Region.bottomHalf)
val ButtonClawMachineOk = button("claw_machine_ok", Region.bottomHalf)
val ButtonInheritance = button("inheritance", Region.bottomHalf)
val ButtonPredictions = button("predictions", Region.bottomHalf)
val ButtonRunners = button("runners", Region.middle)
val ButtonUnityCupRace = button("unitycup_race", Region.bottomHalf)
val ButtonUnityCupRaceFinal = button("unitycup_race_final", Region.bottomHalf)
val ButtonUnityCupSeeAllRaceResults = button("unitycup_see_all_race_results", Region.bottomHalf)
val ButtonUnityCupTeam = button("unitycup_team", Region.bottomHalf)
val ButtonUnityCupWatchMainRace = button("unitycup_watch_main_race", Region.bottomHalf)
val ButtonGrandLiveLessons = button("grandlive_lessons", Region.bottomHalf)
val ButtonGrandLiveLessonsBig = button("grandlive_lessons_big", Region.bottomHalf)
val ButtonGrandLiveConcert = button("grandlive_concert", Region.bottomHalf)
val ButtonGrandLiveGrandConcert = button("grandlive_grand_concert", Region.bottomHalf)
val ButtonGrandLiveStart = button("grandlive_start", Region.bottomHalf)
val ButtonRest = button("rest", Region.bottomHalf)
val ButtonRestAndRecreation = button("rest_and_recreation", Region.bottomHalf)
val ButtonInfirmary = button("infirmary", Region.bottomHalf)
val ButtonRecreation = button("recreation", Region.bottomHalf)

// Grand Live shrinks the bottom action row to four buttons (Lessons is added), so Infirmary / Recreation / Races render smaller than the standard templates.
val ButtonInfirmaryMini = button("infirmary_mini", Region.bottomHalf)
val ButtonRecreationMini = button("recreation_mini", Region.bottomHalf)
val ButtonRacesMini = button("races_mini", Region.bottomHalf)
val ButtonEndCareer = button("end_career", Region.bottomHalf)
val ButtonRaceListFullStats = button("race_list_full_stats", Region.middle)
val ButtonSkillListFullStats = button("skill_list_full_stats", Region.topHalf)
val ButtonHomeFullStats = button("home_full_stats", Region.middle)
val ButtonTrainingSpeed = button("training_speed", Region.bottomHalf)
val ButtonTrainingStamina = button("training_stamina", Region.bottomHalf)
val ButtonTrainingPower = button("training_power", Region.bottomHalf)
val ButtonTrainingGuts = button("training_guts", Region.bottomHalf)
val ButtonTrainingWit = button("training_wit", Region.bottomHalf)

// Grand Live's facility buttons do not match the standard templates above, so it uses this alternative crop.
val ButtonTrainingSpeedAlt = button("training_speed_alt", Region.bottomHalf)
val ButtonTrainingStaminaAlt = button("training_stamina_alt", Region.bottomHalf)
val ButtonTrainingPowerAlt = button("training_power_alt", Region.bottomHalf)
val ButtonTrainingGutsAlt = button("training_guts_alt", Region.bottomHalf)
val ButtonTrainingWitAlt = button("training_wit_alt", Region.bottomHalf)
val ButtonTraining = button("training", Region.bottomHalf)
val ButtonRaces = button("races", Region.bottomHalf)
val ButtonHomeFansInfo = button("home_fans_info", Region.leftHalf)
val ButtonSkillUp = button("skill_up", Region.rightHalf)
val ButtonSkillDown = button("skill_down", Region.rightHalf)
val ButtonOverwrite = button("overwrite", Region.bottomHalf)
val ButtonMyAgendas = button("my_agendas", Region.bottomHalf)
val ButtonRaceAgendaLoadList = button("race_agenda_load_list", Region.rightHalf)
val ButtonDetails = button("details", Region.middle)
val ButtonShopTrackblazer = button("shop_trackblazer", Region.bottomHalf)
val ButtonTrainingItems = button("training_items")
val ButtonExchange = button("exchange", Region.bottomHalf)
val ButtonConfirmUse = button("confirm_use", Region.bottomHalf)
val ButtonUseTrainingItems = button("use_training_items", Region.bottomHalf)
val ButtonConditions = button("conditions", Region.middle)
val ButtonEventProgressChevron = button("event_progress_chevron")

// Dailies
val ButtonClubItemRequest = button("club_item_request", Region.bottomHalf)
val ButtonClubRequestList = button("club_request_list", Region.middle)
val ButtonComplete = button("complete", Region.bottomHalf)
val ButtonDailyLegendRacesCard = button("daily_legend_races_card", Region.bottomHalf)
val ButtonDailyProgram = button("daily_program", Region.bottomHalf)
val ButtonDailyRacesCard = button("daily_races_card", Region.bottomHalf)
val ButtonDonateToAll = button("donate_to_all", Region.bottomHalf)
val ButtonDonateToAllConfirm = button("donate_to_all_confirm", Region.bottomHalf)
val ButtonHomeClub = button("home_club", Region.bottomHalf)
val ButtonHomeMissionsIcon = button("home_missions_icon", Region.rightHalf)
val ButtonHomePresentsIcon = button("home_presents_icon", Region.rightHalf)
val ButtonItemRequestSashTab = button("item_request_sash_tab", Region.topQuarter, confidence = 0.9)
val ButtonItemRequestShoesTab = button("item_request_shoes_tab", Region.topQuarter, confidence = 0.9)
val ButtonMultiRaceOff = button("multi_race_off", Region.bottomHalf)
val ButtonPleasingParfait = button("pleasing_parfait", Region.middle)
val ButtonQuickModeOff = button("quick_mode_off", Region.bottomHalf)
val ButtonRadioRating = button("radio_rating", Region.topHalf, confidence = 0.9)
val ButtonSeeAllRaceResults = button("see_all_race_results", Region.bottomHalf)
val ButtonSortAsc = button("sort_asc", Region.bottomHalf, confidence = 0.9)
val ButtonSortRating = button("sort_rating", Region.bottomHalf, confidence = 0.9)
val ButtonSortToggle = button("sort_toggle", Region.bottomHalf, confidence = 0.9)

// Career Setup
/** The Career button on Home. A career in progress draws the button larger under its date bubble, so it needs its own template. */
object ButtonCareerStartHomeCareer : MultiStateButtonInterface {
    override val templates: List<Template> =
        listOf(
            Template("components/button/career_start_home_career", region = Region.bottomHalf),
            Template("components/button/career_start_home_career_in_progress", region = Region.bottomHalf),
        )
}

val ButtonCareerStartArrowRight = button("career_start_arrow_right", Region.rightHalf, confidence = 0.9)
val ButtonCareerStartDeckArrowLeft = button("career_start_deck_arrow_left", Region.leftHalf, confidence = 0.9)
val ButtonCareerStartDeckArrowRight = button("career_start_deck_arrow_right", Region.rightHalf, confidence = 0.9)
val ButtonCareerStartFriendRefresh = button("career_start_friend_refresh", Region.bottomHalf)

// Also matches the Support Formation Reset, so only use it once the Legacy Select header is confirmed.
val ButtonCareerStartLegacyReset = button("career_start_legacy_reset", Region.bottomHalf)

// "Start!" shares its prefix with "Start Career!" and scores 0.87 against it, so it needs a stricter threshold.
val ButtonCareerStartIndependentStart = button("career_start_independent_start", Region.bottomHalf, confidence = 0.95)

/** The green Career button on the Independent Training dialog, which claims a finished one. */
val ButtonCareerStartIndependentCareer = button("career_start_independent_career", Region.bottomHalf)

// The three story skip states differ only by their triangles and score 0.82 against each other.
val ButtonCareerStartStorySkipOff = button("career_start_story_skip_off", Region.bottomHalf, confidence = 0.95)
val ButtonCareerStartStorySkipOne = button("career_start_story_skip_one", Region.bottomHalf, confidence = 0.95)
val ButtonCareerStartStorySkipTwo = button("career_start_story_skip_two", Region.bottomHalf, confidence = 0.95)
