// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class NameTokenScoreTest {

    private val potentialHit = 0.65
    private val hit = 0.85

    // The case that motivated the score: pg_trgm word_similarity gave all three 0.636 against
    // "Jiří Raška" (shared "jiri" + the "ra" of the surname) and the screen raised a HIT on a PEP.
    @ParameterizedTest
    @CsvSource(
        "Jiří Raška, Jiří Rathouský",
        "Jiří Raška, Jiří Zlatuška",
        "Jiří Raška, Jüri Ratas",
        "Jiří Raška, Petr Raška",
    )
    fun `a shared given name or surname alone is not a match`(query: String, entry: String) {
        assertThat(NameTokenScore.best(listOf(query), listOf(entry))).isLessThan(potentialHit)
    }

    @ParameterizedTest
    @CsvSource(
        "Jiří Raška, Jiri Raska",
        "Raška Jiří, Jiří Raška",
        "'Raška, Jiří', Jiří Raška",
        "Vladimir Putin, Vladimir Vladimirovich Putin",
        "Wladimir Putin, Vladimir Putin",
        "Muammar Gaddafi, Muammar Qadhafi",
        "Mohammed Hussein, Muhammad Husain",
        "Jan Nowak, Jan Novák",
        // Real alias pairs of one listed person each, from the OFAC/EU/UN/HMT lists:
        "Moneer Adanov, Moneer Adnuf",
        "Hazwan al Wez, Hazwan al-Wazz",
        "Abu Hammam al-Sa'idi, Abu Humam al-Sa'idi",
        "Othman al-Ghamdi, Othman bin Ahmed bin Othman Alghamdi",
        "Abu-Rayhanah, Rayhanah",
        "J. Raška, Jiří Raška",
        // Review findings on #16258: Russian given names that start like a fused Arabic article,
        // a run of initials without dots, and a name made only of particles.
        "Alexei Navalny, Aleksey Navalnyy",
        "Alexey Miller, Aleksei Miller",
        "Alexei Anatolievich Navalny, Aleksey Anatolyevich Navalny",
        "Ilham Aliev, Ilham Aliyev",
        "VV Putin, Vladimir Vladimirovich Putin",
        "Abu Abd, Abu Abd",
        "Othman Alghamdi, Othman Ghamdi",
    )
    fun `spelling, transliteration, order and omitted middle names still reach a hit`(query: String, entry: String) {
        assertThat(NameTokenScore.best(listOf(query), listOf(entry))).isGreaterThanOrEqualTo(hit)
    }

    @Test
    fun `the best of the entry's aliases decides`() {
        val score = NameTokenScore.best(listOf("Jiří Raška"), listOf("Jiří Rathouský", "Jiri Raska"))
        assertThat(score).isEqualTo(1.0)
    }

    @Test
    fun `decide removes a clearly different name and never raises the trigram score`() {
        val raska = listOf("Jiří Raška")
        assertThat(NameTokenScore.decide(0.64, raska, listOf("Jiří Rathouský"), 0.65, 0.65, 0.85)).isNull()
        assertThat(
            NameTokenScore.decide(0.7, listOf("Jiri Raska"), listOf("Jiri Raska"), 0.65, 0.65, 0.85),
        ).isEqualTo(0.7)
    }

    @Test
    fun `on a HIT-floor profile an undecided structure score leaves the trigram HIT standing`() {
        // Structure 0.75 with a payment floor of 0.85 neither confirms nor refutes the match: lowering
        // it to 0.75 would turn a blocking HIT into a REVIEW (or CLEAR on an internal transfer).
        val query = listOf("Ahmad Karimi Abadi")
        val entry = listOf("Ahmad Karimi Tehrani")
        val structure = NameTokenScore.best(query, entry)
        assertThat(structure).isBetween(0.65, 0.85)
        assertThat(NameTokenScore.decide(0.9, query, entry, 0.85, 0.65, 0.85)).isEqualTo(0.9)
        // On CUSTOMER the parts may lower it, but never below the HIT threshold the trigram reached.
        assertThat(NameTokenScore.decide(0.9, query, entry, 0.65, 0.65, 0.85)).isEqualTo(0.85)
    }

    @Test
    fun `a name with no letters scores zero`() {
        assertThat(NameTokenScore.best(listOf(" - "), listOf("Jiri Raska"))).isZero()
        assertThat(NameTokenScore.best(listOf("Jiri"), listOf(""))).isZero()
    }

    @Test
    fun `a particle written fused on one side and separate on the other still matches`() {
        // Second review of #16258: dropping the particle from one side only left the other side's
        // fused part with no counterpart, and these real list spellings scored 0.45-0.50.
        val cases = listOf(
            "Osama Binladen" to "Usama bin Laden",
            "Usama Bin Laden" to "Usama Binladen",
            "Abdelaziz Bouteflika" to "Abd el Aziz Bouteflika",
            "Abdulrahman Ali" to "Abd al-Rahman Ali",
            "Benali Zine" to "Ben Ali Zine",
            "Vandersloot Joran" to "Joran van der Sloot",
            "Delacruz Juan" to "Juan de la Cruz",
        )
        for ((query, entry) in cases) {
            assertThat(NameTokenScore.best(listOf(query), listOf(entry)))
                .describedAs("%s vs %s", query, entry)
                .isGreaterThanOrEqualTo(0.85)
        }
    }

    @Test
    fun `letters NFD does not decompose are folded`() {
        assertThat(
            NameTokenScore.best(listOf("Djordje Djordjevic"), listOf("Đorđe Đorđević")),
        ).isGreaterThanOrEqualTo(0.85)
        assertThat(
            NameTokenScore.best(listOf("Soren Ostergaard"), listOf("Søren Østergaard")),
        ).isGreaterThanOrEqualTo(0.85)
        assertThat(NameTokenScore.best(listOf("Strasse"), listOf("Straße"))).isEqualTo(1.0)
    }

    @Test
    fun `Ng is a surname, not a run of initials`() {
        assertThat(NameTokenScore.parts("Ng Wai")).containsExactly("ng", "wai")
        assertThat(NameTokenScore.parts("VV Putin")).containsExactly("v", "v", "putin")
    }

    @Test
    fun `a strong trigram score is never removed, only held at the HIT threshold`() {
        // Structure under the review floor, but the whole-string evidence is a HIT.
        val query = listOf("Jiří Raška")
        val entry = listOf("Jiří Rathouský")
        assertThat(NameTokenScore.best(query, entry)).isLessThan(0.65)
        assertThat(NameTokenScore.decide(0.9, query, entry, 0.85, 0.65, 0.85)).isEqualTo(0.85)
        assertThat(NameTokenScore.decide(0.9, query, entry, 0.65, 0.65, 0.85)).isEqualTo(0.85)
        assertThat(NameTokenScore.decide(0.84, query, entry, 0.65, 0.65, 0.85)).isNull()
    }

    @Test
    fun `a whole-name score at the HIT threshold is never demoted below HIT on a review-floor profile`() {
        // word_similarity measured on pg_trgm: a one-letter variant of a listed DPRK official, and
        // a dropped letter that splits a given name, both rate a HIT as whole strings while one
        // short part falls under the part floor ("he"/"hae", "on"/"jon"). On CUSTOMER (search floor
        // 0.65) these were lowered to the structure score, i.e. a blocking HIT became a review item.
        val cases = listOf(
            Triple("Choe Ryong He", "Choe Ryong Hae", 0.857),
            Triple("On Inaki Perez Aramburu", "Jon Inaki Perez Aramburu", 0.917),
        )
        for ((query, entry, trigram) in cases) {
            assertThat(NameTokenScore.best(listOf(query), listOf(entry))).isLessThan(hit)
            for (floor in listOf(potentialHit, hit)) {
                assertThat(NameTokenScore.decide(trigram, listOf(query), listOf(entry), floor, potentialHit, hit))
                    .describedAs("%s vs %s at search floor %s", query, entry, floor)
                    .isGreaterThanOrEqualTo(hit)
            }
        }
    }
}
