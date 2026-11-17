package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/** Uutiskeskus-API:n vastauksen jäsennys: otsikko näytetään aina alkuperäiskielellä. */
class ForeignNewsParseTest {

    @Test
    fun `otsikko on alkuperainen vaikka vastauksessa olisi kaannos`() {
        val json = """[{"title":"Harbor reopens after storm","title_fi":"Satama aukeaa myrskyn jälkeen","url":"https://n/1"}]"""
        assertEquals("Harbor reopens after storm", ForeignNewsClient.parse(json).single().title)
    }

    @Test
    fun `otsikko luetaan vastauksesta jossa kaannoskenttaa ei ole`() {
        val json = """[{"title":"Harbor reopens after storm","url":"https://n/1","source":"Wire","related_count":2}]"""
        val a = ForeignNewsClient.parse(json).single()
        assertEquals("Harbor reopens after storm", a.title)
        assertEquals("Wire", a.source)
        assertEquals(2, a.relatedCount)
    }

    @Test
    fun `puuttuva alkuperainen otsikko korvataan kaannoksella`() {
        val json = """[{"title":null,"title_fi":"Satama aukeaa","url":"https://n/1"}]"""
        assertEquals("Satama aukeaa", ForeignNewsClient.parse(json).single().title)
    }

    @Test
    fun `osoitteeton rivi ohitetaan`() {
        val json = """[{"title":"A","url":""},{"title":"B","url":"https://n/2"}]"""
        assertEquals(listOf("B"), ForeignNewsClient.parse(json).map { it.title })
    }
}
