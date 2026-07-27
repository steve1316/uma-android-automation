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
 * @return A [ButtonInterface] wrapping the resolved template.
 */
private fun button(name: String, region: IntArray = intArrayOf(0, 0, 0, 0)): ButtonInterface =
    object : ButtonInterface {
        override val template = Template("components/button/$name", region = region)
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
