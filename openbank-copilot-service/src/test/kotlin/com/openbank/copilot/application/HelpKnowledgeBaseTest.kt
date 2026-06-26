// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
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
