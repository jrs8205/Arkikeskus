package org.jrs82.fsclock.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Varmuuskopion palautuksen asetusvaihe ([BackupManager.stagePrefs]): omat sähköhinnat palautetaan
 * kokonaisuutena tai ei lainkaan. Kopion virheellinen hintakenttä ei saa poistaa asennuksen nykyistä
 * sopimusta (katselmointi 2.30.0, kierros 3).
 */
class BackupRestorePrefsTest {
    private val s = ElectricityOwnSettings

    /** Asennus, jossa oma kokonaishinta on päällä ja sopimus on kiinteä 7,0 snt/kWh. */
    private fun installWithFixedContract(): FakeSharedPreferences = FakeSharedPreferences().also {
        it.edit()
            .putBoolean(s.KEY_ENABLED, true)
            .putString(s.KEY_CONTRACT, s.CONTRACT_FIXED)
            .putString(s.KEY_FIXED_PRICE, "7.0")
            .apply()
    }

    /** Ajaa palautuksen asetusvaiheen ja commitin kuten [BackupManager.restore]. Palauttaa palautettujen määrän. */
    private fun restore(prefs: FakeSharedPreferences, prefsJson: String): Int {
        val editor = prefs.edit()
        val count = BackupManager.stagePrefs(JSONObject(prefsJson), HashSet(prefs.all.keys), editor)
        assertTrue(editor.commit())
        return count
    }

    private fun assertFixedContractIntact(label: String, prefs: FakeSharedPreferences) {
        assertEquals(label, setOf(s.KEY_ENABLED, s.KEY_CONTRACT, s.KEY_FIXED_PRICE), prefs.all.keys.filter { it.startsWith(s.PREFIX) }.toSet())
        val own = s.own(prefs)
        assertTrue(label, own != null)
        assertEquals(label, OwnContract.FIXED, own!!.contract)
        assertEquals(label, 7.0, own.energySnt, 1e-9)
        assertTrue(label, own.energySet)
    }

    private fun own(field: String, entry: String): String = "\"${s.PREFIX}$field\": $entry"

    @Test fun nullOwnPriceField_leavesTheCurrentContractUntouched() {
        // Kopion ainoa omien hintojen kenttä on null: nykyistä sopimusta, hintaa ja kytkintä ei saa poistaa.
        val prefs = installWithFixedContract()
        val count = restore(prefs, "{${own("margin", "null")}}")
        assertFixedContractIntact("margin: null", prefs)
        assertEquals(0, count)
    }

    @Test fun anyInvalidOwnPriceField_leavesTheCurrentContractUntouched() {
        val invalid = listOf(
            own("margin", """{"t":"string"}"""), // arvo puuttuu
            own("margin", """{"t":"string","v":""}"""),
            own("margin", """{"t":"string","v":"abc"}"""),
            own("margin", """{"t":"string","v":"-1"}"""),
            own("margin", """{"t":"int","v":5}"""), // hinta tallennetaan merkkijonona
            own("margin", """{"t":"float","v":0.59}"""),
            own("margin", """{"t":"mystery","v":"0.59"}"""),
            own("margin", "\"0.59\""), // ei objekti
            own("fixed_price", """{"t":"string","v":"1000"}"""), // yli suurimman hinnan
            own("transfer_high", """{"t":"bool","v":true}"""),
            own("transfer_low", """{"t":"string","v":"1.2345"}"""), // neljä desimaalia
            own("tax", """{"t":"string","v":"2,9x"}"""),
            own("enabled", """{"t":"string","v":"true"}"""),
            own("enabled", """{"t":"bool","v":"yes"}"""),
            own("tax_enabled", """{"t":"int","v":1}"""),
            own("contract", """{"t":"string","v":"hybrid"}"""),
            own("transfer_product", """{"t":"string","v":"weekend"}"""),
            own("transfer_days", """{"t":"string","v":"sunday"}"""),
            own("transfer_start_hour", """{"t":"int","v":24}"""),
            own("transfer_start_hour", """{"t":"string","v":"7"}"""),
            own("transfer_end_hour", """{"t":"int","v":0}"""),
            // Tunnit kelpaavat yksinään, mutta eivät parina (alku ei ole ennen loppua).
            own("transfer_start_hour", """{"t":"int","v":22}""") + "," + own("transfer_end_hour", """{"t":"int","v":7}"""),
        )
        for (entry in invalid) {
            val prefs = installWithFixedContract()
            val count = restore(prefs, "{$entry}")
            assertFixedContractIntact(entry, prefs)
            assertEquals(entry, 0, count)
        }
    }

    @Test fun oneInvalidField_keepsTheWholeGroupOut_alsoItsValidFields() {
        // Kelvollinen marginaali ja kytkin eivät saa sekoittua asennuksen kiinteään sopimukseen, kun
        // ryhmän toinen kenttä on virheellinen.
        val prefs = installWithFixedContract()
        val count = restore(
            prefs,
            "{" + own("enabled", """{"t":"bool","v":false}""") + "," +
                own("margin", """{"t":"string","v":"0.59"}""") + "," +
                own("contract", "null") + "}",
        )
        assertFixedContractIntact("contract: null", prefs)
        assertEquals(0, count)
    }

    @Test fun invalidOwnPrices_doNotBlockOtherSettings() {
        val prefs = installWithFixedContract()
        prefs.edit().putBoolean(ElectricityVat.KEY, true).apply()
        val count = restore(
            prefs,
            "{" + own("margin", "null") + "," +
                "\"${ElectricityVat.KEY}\": {\"t\":\"bool\",\"v\":false}," +
                "\"news_onboarding_done\": {\"t\":\"bool\",\"v\":true}}",
        )
        assertFalse(ElectricityVat.enabled(prefs))
        assertTrue(prefs.getBoolean("news_onboarding_done", false))
        assertEquals(2, count)
        assertEquals(OwnContract.FIXED, s.own(prefs)!!.contract)
    }

    // --- Avain, jota tämä versio ei tunne (katselmointi 2.30.0, kierros 4) ---

    private fun assertFixedContractInForce(label: String, prefs: FakeSharedPreferences) {
        val own = s.own(prefs)
        assertTrue(label, own != null)
        assertEquals(label, OwnContract.FIXED, own!!.contract)
        assertEquals(label, 7.0, own.energySnt, 1e-9)
        assertTrue(label, own.energySet)
    }

    @Test fun unknownOwnPriceKey_doesNotRemoveTheCurrentContract() {
        // Kopion ainoa omien hintojen kenttä on avain, jota tämä versio ei tunne (esim. uudemman version
        // kopio). Siitä ei voi päätellä kopion sopimusta, joten asennuksen sopimus jää voimaan.
        for (entry in listOf("null", """{"t":"mystery","v":1}""", """{"t":"string","v":"x"}""", "7")) {
            val prefs = installWithFixedContract()
            restore(prefs, "{${own("future", entry)}}")
            assertFixedContractInForce("future: $entry", prefs)
        }
    }

    @Test fun unknownOwnPriceKey_isRestoredLikeAnyOtherSetting() {
        val prefs = installWithFixedContract()
        assertEquals(1, restore(prefs, "{${own("future", """{"t":"string","v":"x"}""")}}"))
        assertEquals("x", prefs.getString(s.PREFIX + "future", null))
        assertEquals(0, restore(prefs, "{${own("other", "null")}}"))
        assertFalse(prefs.contains(s.PREFIX + "other"))
        assertFixedContractInForce("tuntemattomat avaimet", prefs)
    }

    @Test fun unknownOwnPriceKey_doesNotBlockAValidGroup() {
        val prefs = installWithFixedContract()
        val count = restore(
            prefs,
            "{" + own("future", "null") + "," + own("enabled", """{"t":"bool","v":true}""") + "," +
                own("margin", """{"t":"string","v":"0.59"}""") + "}",
        )
        assertEquals(2, count)
        val own = s.own(prefs)!!
        assertEquals(OwnContract.SPOT, own.contract)
        assertEquals(0.59, own.energySnt, 1e-9)
        assertFalse(prefs.contains(s.KEY_FIXED_PRICE))
    }

    @Test fun unknownOwnPriceKey_doesNotRescueAnInvalidGroup() {
        val prefs = installWithFixedContract()
        restore(prefs, "{" + own("future", """{"t":"string","v":"x"}""") + "," + own("margin", "null") + "}")
        assertFixedContractInForce("margin: null + tuntematon avain", prefs)
        assertFalse(prefs.contains(s.KEY_MARGIN))
    }

    @Test fun restoreDecision_dependsOnlyOnTheKeysThisVersionKnows() {
        val future = s.PREFIX + "future"
        assertEquals(OwnRestore.ABSENT, s.restoreDecision(emptyMap()))
        assertEquals(OwnRestore.ABSENT, s.restoreDecision(mapOf(future to null)))
        assertEquals(OwnRestore.ABSENT, s.restoreDecision(mapOf(future to "x")))
        assertEquals(OwnRestore.REPLACE, s.restoreDecision(mapOf(s.KEY_MARGIN to "0.59")))
        assertEquals(OwnRestore.REPLACE, s.restoreDecision(mapOf(s.KEY_MARGIN to "0.59", future to null)))
        assertEquals(OwnRestore.INVALID, s.restoreDecision(mapOf(s.KEY_MARGIN to null)))
        assertEquals(OwnRestore.INVALID, s.restoreDecision(mapOf(s.KEY_MARGIN to "0.59", s.KEY_CONTRACT to null)))
    }

    @Test fun everyOwnPriceKey_belongsToTheGroup_andIsValidated() {
        // Uusi avain ei saa jäädä ryhmän ulkopuolelle eikä ilman tarkistusta: jokainen KEY_-vakio kuuluu
        // ryhmään, ja jokainen ryhmän avain hylkää virheellisen arvon.
        val constants = s::class.java.declaredFields
            .filter { it.name.startsWith("KEY_") && it.type == String::class.java }
            .map { it.isAccessible = true; it.get(null) as String }
            .toSet()
        assertEquals(12, constants.size)
        assertEquals(constants, s.GROUP_KEYS)
        for (key in s.GROUP_KEYS) {
            assertTrue(key, key.startsWith(s.PREFIX))
            assertEquals(key, OwnRestore.INVALID, s.restoreDecision(mapOf(key to null)))
            assertEquals(key, OwnRestore.INVALID, s.restoreDecision(mapOf(key to 1.5f)))
        }
    }

    @Test fun validSpotContract_replacesTheCurrentFixedContract() {
        // Pörssisopimus on oletus eikä sitä tallenneta: kopiossa on vain kytkin ja marginaali.
        val prefs = installWithFixedContract()
        val count = restore(
            prefs,
            "{" + own("enabled", """{"t":"bool","v":true}""") + "," + own("margin", """{"t":"string","v":"0.59"}""") + "}",
        )
        assertEquals(2, count)
        assertFalse(prefs.contains(s.KEY_CONTRACT))
        assertFalse(prefs.contains(s.KEY_FIXED_PRICE))
        val own = s.own(prefs)!!
        assertEquals(OwnContract.SPOT, own.contract)
        assertEquals(0.59, own.energySnt, 1e-9)
    }

    @Test fun everyValueTheAppStores_isRestored() {
        // Kaikki omien hintojen kentät sellaisina kuin asetukset ne tallentavat (hinta myös pilkulla).
        val prefs = installWithFixedContract()
        val count = restore(
            prefs,
            "{" + listOf(
                own("enabled", """{"t":"bool","v":true}"""),
                own("contract", """{"t":"string","v":"spot"}"""),
                own("margin", """{"t":"string","v":"0.59"}"""),
                own("fixed_price", """{"t":"string","v":"0"}"""),
                own("transfer_product", """{"t":"string","v":"season"}"""),
                own("transfer_high", """{"t":"string","v":"4,10"}"""),
                own("transfer_low", """{"t":"string","v":"2.6"}"""),
                own("transfer_start_hour", """{"t":"int","v":6}"""),
                own("transfer_end_hour", """{"t":"int","v":24}"""),
                own("transfer_days", """{"t":"string","v":"mon_fri"}"""),
                own("tax_enabled", """{"t":"bool","v":false}"""),
                own("tax", """{"t":"string","v":"2.79"}"""),
            ).joinToString(",") + "}",
        )
        assertEquals(12, count)
        val own = s.own(prefs)!!
        assertEquals(OwnContract.SPOT, own.contract)
        assertEquals(0.59, own.energySnt, 1e-9)
        assertEquals(OwnTransfer(TransferProduct.SEASON, 4.10, 2.6, 6, 24, TransferDays.MON_FRI), own.transfer)
        assertEquals(0.0, own.taxSnt, 1e-9)
        assertEquals(2.79, s.storedPrice(prefs, s.KEY_TAX), 1e-9)
    }

    @Test fun backupWithoutOwnPrices_keepsTheCurrentOnes() {
        val prefs = installWithFixedContract()
        val count = restore(prefs, "{\"${ElectricityVat.KEY}\": {\"t\":\"bool\",\"v\":false}}")
        assertEquals(1, count)
        assertFixedContractIntact("ei omia hintoja kopiossa", prefs)
    }

    @Test fun otherSettings_areStillRestoredLeniently() {
        // Muiden asetusten käsittely ei muutu: virheellinen kenttä ohitetaan, kelvolliset kirjoitetaan.
        val prefs = FakeSharedPreferences()
        val count = restore(
            prefs,
            """{"a": {"t":"string","v":"x"}, "b": {"t":"int","v":3}, "c": {"t":"long","v":5000000000},
               "d": {"t":"float","v":1.5}, "e": {"t":"set","v":["p","q"]}, "f": null, "g": {"t":"mystery","v":1},
               "auto_backup_uri": {"t":"string","v":"content://x"}}""",
        )
        assertEquals(5, count)
        assertEquals("x", prefs.getString("a", null))
        assertEquals(3, prefs.getInt("b", 0))
        assertEquals(5000000000L, prefs.getLong("c", 0))
        assertEquals(1.5f, prefs.getFloat("d", 0f), 1e-6f)
        assertEquals(setOf("p", "q"), prefs.getStringSet("e", null))
        assertFalse(prefs.contains("f"))
        assertFalse(prefs.contains("g"))
        assertFalse(prefs.contains("auto_backup_uri"))
    }
}
