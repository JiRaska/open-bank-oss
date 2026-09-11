// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class GoldenSetScorerTest {

    private fun entry(
        expectedLanguage: String = "cs",
        expectNoFigureFromMemory: Boolean = false,
        expectedToneMarkers: List<String> = emptyList(),
        requiredComplianceSentence: String? = null,
    ) = GoldenSetEntry(
        id = UUID.randomUUID(),
        personaId = UUID.randomUUID(),
        question = "test question",
        expectedLanguage = expectedLanguage,
        expectNoFigureFromMemory = expectNoFigureFromMemory,
        expectedToneMarkers = expectedToneMarkers,
        requiredComplianceSentence = requiredComplianceSentence,
        createdBy = "editor-a",
        createdAt = Instant.parse("2026-09-11T09:00:00Z"),
    )

    @Test
    fun `a blank answer fails the non-empty check`() {
        val result = GoldenSetScorer.score(entry(), "")
        assertThat(result.passed).isFalse()
        assertThat(result.dimensions).anyMatch {
            it.dimension == "non-empty answer" &&
                it.outcome == GoldenSetScorer.Outcome.FAIL
        }
    }

    @Test
    fun `Czech text passes the cs language heuristic`() {
        val result = GoldenSetScorer.score(entry(expectedLanguage = "cs"), "Váš zůstatek je viditelný v aplikaci.")
        val lang = result.dimensions.single { it.dimension == "expected language" }
        assertThat(lang.outcome).isEqualTo(GoldenSetScorer.Outcome.PASS)
    }

    @Test
    fun `plain ASCII text fails the cs language heuristic`() {
        val result = GoldenSetScorer.score(entry(expectedLanguage = "cs"), "Your balance is visible in the app.")
        val lang = result.dimensions.single { it.dimension == "expected language" }
        assertThat(lang.outcome).isEqualTo(GoldenSetScorer.Outcome.FAIL)
    }

    @Test
    fun `Czech text fails the en language heuristic`() {
        val result = GoldenSetScorer.score(entry(expectedLanguage = "en"), "Váš zůstatek je viditelný.")
        val lang = result.dimensions.single { it.dimension == "expected language" }
        assertThat(lang.outcome).isEqualTo(GoldenSetScorer.Outcome.FAIL)
    }

    @Test
    fun `an unrecognized language tag is UNAVAILABLE, never a silent pass`() {
        val result = GoldenSetScorer.score(entry(expectedLanguage = "xx"), "anything")
        val lang = result.dimensions.single { it.dimension == "expected language" }
        assertThat(lang.outcome).isEqualTo(GoldenSetScorer.Outcome.UNAVAILABLE)
    }

    @Test
    fun `a figure-shaped substring fails when the entry forbids inventing one`() {
        val result = GoldenSetScorer.score(
            entry(expectNoFigureFromMemory = true),
            "Aktuální kurz je 24,50 Kč.",
        )
        assertThat(result.passed).isFalse()
        val dim = result.dimensions.single { it.dimension == "no figure from memory" }
        assertThat(dim.outcome).isEqualTo(GoldenSetScorer.Outcome.FAIL)
    }

    @Test
    fun `no figure in the answer passes when the entry forbids inventing one`() {
        val result = GoldenSetScorer.score(
            entry(expectNoFigureFromMemory = true),
            "Aktuální kurz najdete v aplikaci.",
        )
        val dim = result.dimensions.single { it.dimension == "no figure from memory" }
        assertThat(dim.outcome).isEqualTo(GoldenSetScorer.Outcome.PASS)
    }

    @Test
    fun `the no-figure check is UNAVAILABLE when the entry does not require it`() {
        val result = GoldenSetScorer.score(
            entry(expectNoFigureFromMemory = false),
            "Aktuální kurz je 24,50 Kč.",
        )
        val dim = result.dimensions.single { it.dimension == "no figure from memory" }
        assertThat(dim.outcome).isEqualTo(GoldenSetScorer.Outcome.UNAVAILABLE)
    }

    @Test
    fun `tone markers are always UNAVAILABLE, never checked as a pass or a fail`() {
        val result = GoldenSetScorer.score(entry(expectedToneMarkers = listOf("brief")), "brief")
        val dim = result.dimensions.single { it.dimension == "tone markers" }
        assertThat(dim.outcome).isEqualTo(GoldenSetScorer.Outcome.UNAVAILABLE)
    }

    @Test
    fun `a required compliance sentence present verbatim passes`() {
        val result = GoldenSetScorer.score(
            entry(requiredComplianceSentence = "Hovor je nahráván."),
            "Dobrý den. Hovor je nahráván. Jak vám mohu pomoci?",
        )
        val dim = result.dimensions.single { it.dimension == "required compliance sentence" }
        assertThat(dim.outcome).isEqualTo(GoldenSetScorer.Outcome.PASS)
    }

    @Test
    fun `a missing required compliance sentence fails`() {
        val result = GoldenSetScorer.score(
            entry(requiredComplianceSentence = "Hovor je nahráván."),
            "Dobrý den, jak vám mohu pomoci?",
        )
        assertThat(result.passed).isFalse()
        val dim = result.dimensions.single { it.dimension == "required compliance sentence" }
        assertThat(dim.outcome).isEqualTo(GoldenSetScorer.Outcome.FAIL)
    }

    @Test
    fun `no required compliance sentence is UNAVAILABLE, not a silent pass`() {
        val result = GoldenSetScorer.score(entry(requiredComplianceSentence = null), "Dobrý den.")
        val dim = result.dimensions.single { it.dimension == "required compliance sentence" }
        assertThat(dim.outcome).isEqualTo(GoldenSetScorer.Outcome.UNAVAILABLE)
    }

    @Test
    fun `an all-UNAVAILABLE score still passes overall`() {
        val result = GoldenSetScorer.score(entry(expectedLanguage = "xx"), "anything")
        assertThat(result.passed).isTrue()
    }
}
