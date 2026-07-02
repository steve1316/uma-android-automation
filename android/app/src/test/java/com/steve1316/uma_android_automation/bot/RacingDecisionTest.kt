package com.steve1316.uma_android_automation.bot

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for pure Racing decision helpers - the extra-race gates extracted out of the Android-coupled Racing class so they can be verified directly.
 */
@DisplayName("Racing decision helpers")
class RacingDecisionTest {
    @Test
    @DisplayName("Extra-racing energy gate blocks below the floor, allows at or above, and is disabled at 0")
    fun testHasEnoughEnergyForExtraRacing() {
        assertFalse(hasEnoughEnergyForExtraRacing(energy = 25, minEnergy = 30), "25% energy is below the 30% floor, so the fan-farming race should be skipped")
        assertTrue(hasEnoughEnergyForExtraRacing(energy = 30, minEnergy = 30), "Exactly the floor is enough energy to race")
        assertTrue(hasEnoughEnergyForExtraRacing(energy = 45, minEnergy = 30), "Above the floor is enough energy to race")
        assertTrue(hasEnoughEnergyForExtraRacing(energy = 0, minEnergy = 0), "A floor of 0 disables the check entirely")
    }
}
