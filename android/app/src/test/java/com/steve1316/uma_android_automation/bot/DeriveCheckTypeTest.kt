package com.steve1316.uma_android_automation.bot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Verifies that [SkillDatabase.deriveCheckType] maps a skill's activation condition to the correct aptitude affinity role(s) used by the estimated-rank skill scoring.
 */
class DeriveCheckTypeTest {
    @Test
    @DisplayName("A skill with no affinity token has an empty checkType")
    fun emptyWhenNoAffinityToken() {
        assertEquals("", SkillDatabase.deriveCheckType("", ""))
        assertEquals("", SkillDatabase.deriveCheckType("is_lastspurt==1", ""))
        // track_id and rotation are not affinity roles and must be ignored.
        assertEquals("", SkillDatabase.deriveCheckType("track_id==10001&rotation==1", ""))
    }

    @Test
    @DisplayName("A single running_style / distance_type / ground_type token maps to one role")
    fun singleRole() {
        assertEquals("Late", SkillDatabase.deriveCheckType("running_style==3&up_slope_random==1", ""))
        assertEquals("Medium", SkillDatabase.deriveCheckType("distance_type==3", ""))
        assertEquals("Dirt", SkillDatabase.deriveCheckType("ground_type==2", ""))
        assertEquals("Front", SkillDatabase.deriveCheckType("running_style==1", ""))
        assertEquals("Long", SkillDatabase.deriveCheckType("distance_type==4", ""))
    }

    @Test
    @DisplayName("The precondition contributes affinity tokens too")
    fun preconditionContributes() {
        assertEquals("End", SkillDatabase.deriveCheckType("", "running_style==4"))
    }

    @Test
    @DisplayName("Multiple tokens combine, ordered surface then distance then style")
    fun multiRoleOrdering() {
        assertEquals("Long/Front", SkillDatabase.deriveCheckType("distance_type==4&running_style==1", ""))
        assertEquals("Dirt/Mile", SkillDatabase.deriveCheckType("ground_type==2&distance_type==2", ""))
        // Two styles collapse into a single ordered pair.
        assertEquals("Pace/Late", SkillDatabase.deriveCheckType("running_style==2|running_style==3", ""))
    }
}
