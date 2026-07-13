/**
 * Defines icon components.
 *
 * These are images which are typically not clickable, however they DO have click functionality; it just isn't their primary purpose. This is why we classify them as Icons instead of Buttons.
 */

package com.steve1316.uma_android_automation.components

/**
 * Builds an icon component backed by the `components/icon/[name]` template.
 *
 * @param name Leaf filename under `components/icon/` identifying the template image.
 * @param region Screen region to constrain matching to. Defaults to the whole screen.
 * @param confidence Match-confidence override for this template. Defaults to 0.0 (use the global default).
 * @return A [ComponentInterface] wrapping the resolved template.
 */
private fun icon(name: String, region: IntArray = intArrayOf(0, 0, 0, 0), confidence: Double = 0.0): ComponentInterface =
    object : ComponentInterface {
        override val template = Template("components/icon/$name", region = region, confidence = confidence)
    }

val IconMoodGreat = icon("mood_great", Region.topHalf)
val IconMoodGood = icon("mood_good", Region.topHalf)
val IconMoodNormal = icon("mood_normal", Region.topHalf)
val IconMoodBad = icon("mood_bad", Region.topHalf)
val IconMoodAwful = icon("mood_awful", Region.topHalf)
val IconTrainingHeaderSpeed = icon("training_header_speed", Region.topHalf)
val IconTrainingHeaderStamina = icon("training_header_stamina", Region.topHalf)
val IconTrainingHeaderPower = icon("training_header_power", Region.topHalf)
val IconTrainingHeaderGuts = icon("training_header_guts", Region.topHalf)
val IconTrainingHeaderWit = icon("training_header_wit", Region.topHalf)
val IconHorseshoe = icon("horseshoe")
val IconDoubleCircle = icon("double_circle")
val IconUnityCupRaceEndLogo = icon("unity_cup_race_end_logo", Region.topHalf)
val IconTazuna = icon("tazuna", Region.topHalf)
val IconRaceDayRibbon = icon("race_day_ribbon", Region.bottomHalf)
val IconRaceHistory1st = icon("race_history_1st")
val IconGoalRibbon = icon("goal_ribbon", Region.leftHalf)
val IconRaceListPredictionDoubleStar = icon("race_list_prediction_double_star", Region.rightHalf)
val IconRaceListSelectionBracketBottomRight = icon("race_list_selection_bracket_bottom_right", Region.rightHalf)
val IconRaceListMaidenPill = icon("race_list_maiden_pill", Region.bottomHalf)
val IconScrollListTopLeft = icon("scroll_list_top_left", Region.leftHalf)
val IconScrollListBottomRight = icon("scroll_list_bottom_right", Region.rightHalf)
val IconObtainedPill = icon("obtained_pill", Region.rightHalf)
val IconSkillTitleDoubleCircle = icon("skill_title_double_circle")
val IconSkillTitleCircle = icon("skill_title_circle")
val IconSkillTitleX = icon("skill_title_x")
val IconOneFreePerDayTooltip = icon("one_free_per_day_tooltip", Region.middle)
val IconEnergyBarLeftPart = icon("energy_bar_left_part", Region.topHalf)
val IconEnergyBarRightPart0 = icon("energy_bar_right_part_0", Region.topHalf)
val IconEnergyBarRightPart1 = icon("energy_bar_right_part_1", Region.topHalf)
val IconRaceNotEnoughFans = icon("race_not_enough_fans", Region.middle)
val IconStatBlockSpeed = icon("stat_block_speed")
val IconStatBlockStamina = icon("stat_block_stamina")
val IconStatBlockPower = icon("stat_block_power")
val IconStatBlockGuts = icon("stat_block_guts")
val IconStatBlockWit = icon("stat_block_wit")
val IconStatBlockTrainer = icon("stat_block_trainer")
val IconStatBlockGroup = icon("stat_block_group")
val IconStatSupportEtsukoOtonashi = icon("stat_support_etsuko_otonashi")
val IconStatSupportRikoKashimoto = icon("stat_support_riko_kashimoto")
val IconStatSupportYayoiAkikawa = icon("stat_support_yayoi_akikawa")
val IconStatSupportHappyMeek = icon("stat_support_happy_meek")
val IconDuelGreat = icon("happy_meek_duel_great", Region.middle)
val IconDuelGood = icon("happy_meek_duel_good", Region.middle)
val IconDuelBad = icon("happy_meek_duel_bad", Region.middle)
val IconStatSkillHint = icon("stat_skill_hint", confidence = 0.9)
val IconRecreationDate = icon("recreation_date", Region.bottomHalf)
val IconRecreationDateOpen = icon("recreation_date_open", Region.middle)
val IconTrainingEventHorseshoe = icon("training_event_horseshoe", Region.leftHalf)
val IconEventTitleSpacer = icon("event_title_spacer")
val IconUnityCupSpiritExplosion = icon("unitycup_spirit_explosion", Region.topRightThird)
val IconUnityCupExtremeSpiritExplosion = icon("unitycup_extreme_spirit_explosion", Region.topRightThird)
val IconUnityCupSpiritTraining = icon("unitycup_spirit_training", Region.topRightThird)
val IconUnityCupTutorialHeader = icon("unitycup_tutorial_header", Region.topHalf)
val IconInfirmaryEventHeader = icon("infirmary_event_header", Region.topHalf)
val IconRaceAgendaEmpty = icon("race_agenda_empty", Region.topHalf)
val IconDialogScrollListTopLeft = icon("dialog_scroll_list_top_left", Region.leftHalf)
val IconDialogScrollListBottomRight = icon("dialog_scroll_list_bottom_right", Region.rightHalf)
