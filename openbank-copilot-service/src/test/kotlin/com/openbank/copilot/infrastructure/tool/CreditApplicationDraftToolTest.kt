// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.infrastructure.tool

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.copilot.domain.ActionKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0269 rule 5, L2. The agent may prepare and never commit, so these tests are mostly about what
 * the draft must NOT contain.
 */
class CreditApplicationDraftToolTest {

    private val tool = CreditApplicationDraftTool()
    private val json = ObjectMapper()

    private fun propose(amount: String?, term: Int?) = tool.propose(
        json.createObjectNode().apply {
            amount?.let { put("amount", it) }
            term?.let { put("termMonths", it) }
        },
    )

    @Test
    fun `a valid request produces a credit application draft`() {
        val result = propose("250000", 48)
        assertThat(result.proposal).isNotNull
        assertThat(result.proposal!!.kind).isEqualTo(ActionKind.CREDIT_APPLICATION)
        assertThat(result.proposal!!.fields).containsEntry("amount", "250000").containsEntry("termMonths", "48")
    }

    @Test
    fun `the draft carries no price of any kind`() {
        val fields = propose("250000", 48).proposal!!.fields
        // A draft that claimed a rate or an instalment would be a quote the bank never made. The
        // price comes from the server, at the moment the customer asks for it.
        assertThat(fields.keys).containsExactlyInAnyOrder("amount", "termMonths")
        assertThat(fields.keys).noneMatch { it.contains("rate") || it.contains("apr") || it.contains("instal") }
    }

    @Test
    fun `the summary says it is a draft, so no rendering of it can read as an approval`() {
        assertThat(propose("250000", 48).proposal!!.summary).contains("návrh")
    }

    @Test
    fun `a missing or unparseable amount is refused rather than guessed`() {
        assertThat(propose(null, 48).error).isNotNull
        assertThat(propose("abc", 48).error).isNotNull
        assertThat(propose("0", 48).error).isNotNull
        assertThat(propose("-100", 48).error).isNotNull
    }

    @Test
    fun `a missing or non-positive term is refused`() {
        assertThat(propose("250000", null).error).isNotNull
        assertThat(propose("250000", 0).error).isNotNull
    }

    @Test
    fun `the tool describes itself as preparing, never submitting`() {
        assertThat(tool.description).contains("do NOT submit")
        assertThat(tool.name).isEqualTo("credit_prepare_application")
    }
}
