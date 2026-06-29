// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HelpKnowledgeBaseTest {

    private val passages = listOf(
        HelpKnowledgeBase.Passage(
            "Jak poslat platbu",
            "help/posilani-plateb.md",
            "Platbu odešlete v sekci Platby, zadáte IBAN příjemce a částku.",
        ),
        HelpKnowledgeBase.Passage(
            "Ztracená karta",
            "help/ztracena-karta.md",
            "Ztracenou kartu okamžitě zablokujte v sekci Karty, blokace je okamžitá.",
        ),
    )

    @Test
    fun `ranks the passage matching the query terms first`() {
        val hits = HelpKnowledgeBase.rank("ztracenou kartu zablokovat", passages, 3)

        assertThat(hits).isNotEmpty
        assertThat(hits.first().passage.docTitle).isEqualTo("Ztracená karta")
    }

    @Test
    fun `empty query returns nothing`() {
        assertThat(HelpKnowledgeBase.rank("", passages, 3)).isEmpty()
    }

    @Test
    fun `unrelated query returns nothing`() {
        assertThat(HelpKnowledgeBase.rank("xyzzy naprostý nesmysl", passages, 3)).isEmpty()
    }
}
