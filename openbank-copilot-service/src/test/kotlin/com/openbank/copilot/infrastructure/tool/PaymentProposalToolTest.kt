// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.infrastructure.tool

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.copilot.domain.ActionKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PaymentProposalToolTest {

    private val tool = PaymentProposalTool()
    private val mapper = ObjectMapper()

    private fun args(vararg pairs: Pair<String, String>): JsonNode = mapper.valueToTree(mapOf(*pairs))

    @Test
    fun `valid input produces a PAYMENT proposal with normalised fields`() {
        val result = tool.propose(
            args(
                "fromAccountId" to "11111111-1111-1111-1111-111111111111",
                "payeeIban" to "cz6508000000192000145399",
                "amount" to "1500,50",
                "currency" to "czk",
            ),
        )

        assertThat(result.error).isNull()
        val p = result.proposal!!
        assertThat(p.kind).isEqualTo(ActionKind.PAYMENT)
        assertThat(p.fields["payeeIban"]).isEqualTo("CZ6508000000192000145399")
        assertThat(p.fields["amount"]).isEqualTo("1500.50")
        assertThat(p.fields["currency"]).isEqualTo("CZK")
    }

    @Test
    fun `defaults currency to CZK when absent`() {
        val result = tool.propose(
            args(
                "fromAccountId" to "11111111-1111-1111-1111-111111111111",
                "payeeIban" to "CZ6508000000192000145399",
                "amount" to "10",
            ),
        )

        assertThat(result.proposal!!.fields["currency"]).isEqualTo("CZK")
    }

    @Test
    fun `rejects an invalid IBAN`() {
        val result = tool.propose(
            args(
                "fromAccountId" to "11111111-1111-1111-1111-111111111111",
                "payeeIban" to "not-an-iban",
                "amount" to "10",
            ),
        )

        assertThat(result.proposal).isNull()
        assertThat(result.error).contains("IBAN")
    }

    @Test
    fun `rejects a non-positive amount`() {
        val result = tool.propose(
            args(
                "fromAccountId" to "11111111-1111-1111-1111-111111111111",
                "payeeIban" to "CZ6508000000192000145399",
                "amount" to "0",
            ),
        )

        assertThat(result.proposal).isNull()
        assertThat(result.error).contains("kladná")
    }

    @Test
    fun `rejects a missing account id`() {
        val result = tool.propose(args("payeeIban" to "CZ6508000000192000145399", "amount" to "10"))

        assertThat(result.proposal).isNull()
        assertThat(result.error).contains("účtu")
    }
}
