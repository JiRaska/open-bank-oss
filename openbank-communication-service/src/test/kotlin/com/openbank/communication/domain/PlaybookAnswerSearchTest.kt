// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PlaybookAnswerSearchTest {

    private val answers = listOf(
        ApprovedAnswer("card lost or stolen", "Block the card immediately in the app, under Cards > Block."),
        ApprovedAnswer("forgot PIN", "The customer can reset their PIN via the app without calling in."),
        ApprovedAnswer("dispute a transaction", "Open a dispute case; refer to the dispute-service runbook."),
    )

    @Test
    fun `an empty or blank query returns no hits`() {
        assertThat(PlaybookAnswerSearch.search("", answers)).isEmpty()
        assertThat(PlaybookAnswerSearch.search("   ", answers)).isEmpty()
    }

    @Test
    fun `a query matching every term in a situation ranks it first`() {
        val hits = PlaybookAnswerSearch.search("card lost", answers)
        assertThat(hits).isNotEmpty()
        assertThat(hits.first().answer.situation).isEqualTo("card lost or stolen")
        assertThat(hits.first().score).isEqualTo(1.0)
    }

    @Test
    fun `a query with no term overlap at all returns no hits`() {
        assertThat(PlaybookAnswerSearch.search("interest rate mortgage", answers)).isEmpty()
    }

    @Test
    fun `matching is case-insensitive`() {
        val hits = PlaybookAnswerSearch.search("CARD LOST", answers)
        assertThat(hits).isNotEmpty()
        assertThat(hits.first().answer.situation).isEqualTo("card lost or stolen")
    }

    @Test
    fun `results are capped at limit`() {
        val manyAnswers = (1..10).map { ApprovedAnswer("pin situation $it", "reset the pin") }
        val hits = PlaybookAnswerSearch.search("pin", manyAnswers, limit = 3)
        assertThat(hits).hasSize(3)
    }

    @Test
    fun `a partial term match scores between zero and one`() {
        val hits = PlaybookAnswerSearch.search("card lost mortgage rate", answers)
        // "card" and "lost" match (2 of 4 query terms) against the card-lost answer.
        assertThat(hits.first().score).isEqualTo(0.5)
    }
}
