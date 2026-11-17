package org.jrs82.fsclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.OffsetDateTime

/** Varalähteen (spot-hinta.fi, TodayAndDayForward) vastauksen jäsennys sähkön sisäiseen malliin. */
class ElectricityFallbackClientTest {

    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private val start = ms("2026-11-16T00:00:00+02:00")
    private val end = ms("2026-11-18T00:00:00+02:00")

    private val body = """
        [
          {"Rank":2,"DateTime":"2026-11-16T00:00:00+02:00","PriceNoTax":0.01105,"PriceWithTax":0.01387},
          {"Rank":5,"DateTime":"2026-11-16T00:15:00+02:00","PriceNoTax":-0.00012,"PriceWithTax":-0.00012},
          {"Rank":1,"DateTime":"2026-11-17T23:45:00+02:00","PriceNoTax":0.0301,"PriceWithTax":0.03778},
          {"Rank":3,"DateTime":"2026-11-18T00:00:00+02:00","PriceNoTax":0.03154,"PriceWithTax":0.03958}
        ]
    """.trimIndent()

    @Test
    fun `veroton hinta muunnetaan yksikkoon snt per kWh`() {
        val data = ElectricityFallbackClient.parse(body, start, end)
        assertEquals(1.105, data.quarters[0].sntPerKwh, 1e-9)
        assertEquals(3.01, data.quarters[2].sntPerKwh, 1e-9)
    }

    @Test
    fun `negatiivinen hinta sailyy negatiivisena`() {
        val data = ElectricityFallbackClient.parse(body, start, end)
        assertEquals(-0.012, data.quarters[1].sntPerKwh, 1e-9)
    }

    @Test
    fun `vartin kentat ovat Suomen aikaa`() {
        val q = ElectricityFallbackClient.parse(body, start, end).quarters[2]
        assertEquals(ms("2026-11-17T23:45:00+02:00"), q.timestamp)
        assertEquals(listOf(2026, 11, 17, 23, 45), listOf(q.year, q.month, q.dayOfMonth, q.hour, q.minute))
    }

    @Test
    fun `kesaajan aikaleima tulkitaan oikein`() {
        val summer = """[{"DateTime":"2026-07-01T12:30:00+03:00","PriceNoTax":0.05}]"""
        val q = ElectricityFallbackClient.parse(summer, 0L, Long.MAX_VALUE).quarters.single()
        assertEquals(ms("2026-07-01T09:30:00Z"), q.timestamp)
        assertEquals(listOf(7, 1, 12, 30), listOf(q.month, q.dayOfMonth, q.hour, q.minute))
    }

    @Test
    fun `pyydetyn aikavalin ulkopuoliset vartit jatetaan pois`() {
        val data = ElectricityFallbackClient.parse(body, start, end)
        assertEquals(3, data.quarters.size)
        assertEquals(ms("2026-11-17T23:45:00+02:00"), data.quarters.last().timestamp)
    }

    @Test
    fun `vartit palautetaan aikajarjestyksessa`() {
        val reversed = """
            [
              {"DateTime":"2026-11-16T00:15:00+02:00","PriceNoTax":0.02},
              {"DateTime":"2026-11-16T00:00:00+02:00","PriceNoTax":0.01}
            ]
        """.trimIndent()
        val data = ElectricityFallbackClient.parse(reversed, start, end)
        assertEquals(1.0, data.quarters[0].sntPerKwh, 1e-9)
        assertEquals(2.0, data.quarters[1].sntPerKwh, 1e-9)
    }

    @Test
    fun `puutteellinen rivi ohitetaan kaatumatta`() {
        val broken = """
            [
              {"DateTime":"2026-11-16T00:00:00+02:00"},
              {"DateTime":"ei-aika","PriceNoTax":0.01},
              {"DateTime":"2026-11-16T00:30:00+02:00","PriceNoTax":null},
              {"DateTime":"2026-11-16T00:45:00+02:00","PriceNoTax":0.02}
            ]
        """.trimIndent()
        val data = ElectricityFallbackClient.parse(broken, start, end)
        assertEquals(1, data.quarters.size)
        assertEquals(2.0, data.quarters[0].sntPerKwh, 1e-9)
    }

    @Test
    fun `data merkitaan varalahteen dataksi`() {
        assertEquals(ElectricityData.SOURCE_SPOT_HINTA, ElectricityFallbackClient.parse(body, start, end).source)
    }

    @Test
    fun `muu kuin hintalista on virhe`() {
        assertThrows(Exception::class.java) {
            ElectricityFallbackClient.parse("""{"error":"Too many requests"}""", start, end)
        }
    }
}
