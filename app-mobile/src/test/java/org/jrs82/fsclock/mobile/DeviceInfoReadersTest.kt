package org.jrs82.fsclock.mobile

import android.os.BatteryManager
import org.jrs82.fsclock.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceInfoReadersTest {

    @Test fun tavutSovelluksenKielenDesimaalierottimella() {
        val gb = (1.5 * 1024 * 1024 * 1024).toLong()
        assertEquals("1,5 GB", DeviceInfoReaders.fmtBytes(gb, AppLocale.FINNISH))
        assertEquals("1,5 GB", DeviceInfoReaders.fmtBytes(gb, AppLocale.SWEDISH))
        assertEquals("1.5 GB", DeviceInfoReaders.fmtBytes(gb, AppLocale.ENGLISH))
        assertEquals("512 MB", DeviceInfoReaders.fmtBytes(512L * 1024 * 1024, AppLocale.ENGLISH))
        assertEquals("0", DeviceInfoReaders.fmtBytes(0L, AppLocale.ENGLISH))
    }

    @Test fun wifiKaistaJaKanava() {
        assertEquals("2,4 GHz", DeviceInfoReaders.band(2412, AppLocale.FINNISH))
        assertEquals("2.4 GHz", DeviceInfoReaders.band(2412, AppLocale.ENGLISH))
        assertEquals("5 GHz", DeviceInfoReaders.band(5180, AppLocale.ENGLISH))
        assertEquals(1, DeviceInfoReaders.channel(2412))
        assertEquals(36, DeviceInfoReaders.channel(5180))
    }

    @Test fun tilatResursseiksiJaTuntematonYhteisesta() {
        assertEquals(R.string.device_battery_charging, DeviceInfoReaders.batteryStatusRes(BatteryManager.BATTERY_STATUS_CHARGING))
        assertEquals(R.string.device_battery_full, DeviceInfoReaders.batteryStatusRes(BatteryManager.BATTERY_STATUS_FULL))
        assertEquals(R.string.common_unknown, DeviceInfoReaders.batteryStatusRes(-1))
        assertEquals(R.string.device_health_good, DeviceInfoReaders.healthRes(BatteryManager.BATTERY_HEALTH_GOOD))
        assertEquals(R.string.common_unknown, DeviceInfoReaders.healthRes(-1))
        assertEquals("Wi-Fi 6 (ax)", DeviceInfoReaders.wifiStandard(6))
        assertNull(DeviceInfoReaders.wifiStandard(0))
    }
}
