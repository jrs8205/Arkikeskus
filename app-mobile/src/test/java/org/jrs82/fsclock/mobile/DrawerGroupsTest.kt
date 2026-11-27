package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.R
import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerGroupsTest {
    @Test fun drawerGroupsPlaceSpeedometerOnlyUnderDriving() {
        assertEquals(listOf(R.string.main_drawer_weather, R.string.main_drawer_traffic, R.string.main_drawer_driving,
            R.string.main_drawer_transit, R.string.main_drawer_tv, R.string.main_drawer_news, R.string.main_drawer_fitness,
            R.string.main_drawer_other), DRAWER_GROUPS.map { it.titleRes })
        val driving = DRAWER_GROUPS.single { it.titleRes == R.string.main_drawer_driving }
        assertEquals(R.drawable.mobile_ic_speed_24, driving.iconRes)
        assertEquals(listOf(HomeSection.SPEEDOMETER), driving.sections)
        assertEquals(listOf(HomeSection.WORKOUT, HomeSection.STEPS),
            DRAWER_GROUPS.single { it.titleRes == R.string.main_drawer_fitness }.sections)
        val sections = DRAWER_GROUPS.flatMap { it.sections }
        assertEquals(HomeSection.entries.filter { it != HomeSection.HOME }.associateWith { 1 },
            sections.groupingBy { it }.eachCount())
    }
}
