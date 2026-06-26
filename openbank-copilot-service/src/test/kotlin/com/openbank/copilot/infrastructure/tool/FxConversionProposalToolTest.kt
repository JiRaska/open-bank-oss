// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
package com.openbank.copilot.infrastructure.tool

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.copilot.domain.ActionKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FxConversionProposalToolTest {

    private val tool = FxConversionProposalTool()
    private val mapper = ObjectMapper()

    private fun args(vararg pairs: Pair<String, String>): JsonNode = mapper.valueToTree(mapOf(*pairs))

    private val validAccountId = "11111111-1111-1111-1111-111111111111"

    @Test
    fun `valid input produces FX_CONVERSION proposal`() {
        val result = tool.propose(
            args(
                "accountId" to validAccountId,
                "fromCurrency" to "eur",
                "toCurrency" to "czk",
                "amount" to "500,00",
            ),
        )

        assertThat(result.error).isNull()
        val p = result.proposal!!
        assertThat(p.kind).isEqualTo(ActionKind.FX_CONVERSION)
        assertThat(p.fields["fromCurrency"]).isEqualTo("EUR")
        assertThat(p.fields["toCurrency"]).isEqualTo("CZK")
        assertThat(p.fields["amount"]).isEqualTo("500.00")
        assertThat(p.fields["accountId"]).isEqualTo(validAccountId)
        assertThat(p.summary).contains("EUR").contains("CZK")
    }

    @Test
    fun `rejects same from and to currency`() {
        val result = tool.propose(
            args("accountId" to validAccountId, "fromCurrency" to "EUR", "toCurrency" to "EUR", "amount" to "100"),
        )

        assertThat(result.proposal).isNull()
        assertThat(result.error).isNotBlank()
    }

    @Test
    fun `rejects invalid accountId`() {
        val result = tool.propose(
            args("accountId" to "not-a-uuid", "fromCurrency" to "EUR", "toCurrency" to "CZK", "amount" to "100"),
        )

        assertThat(result.proposal).isNull()
        assertThat(result.error).contains("accountId")
    }

    @Test
    fun `rejects missing accountId`() {
        val result = tool.propose(args("fromCurrency" to "EUR", "toCurrency" to "CZK", "amount" to "100"))

        assertThat(result.proposal).isNull()
        assertThat(result.error).isNotBlank()
    }

    @Test
    fun `rejects non-positive amount`() {
        val result = tool.propose(
            args("accountId" to validAccountId, "fromCurrency" to "EUR", "toCurrency" to "CZK", "amount" to "-1"),
        )

        assertThat(result.proposal).isNull()
        assertThat(result.error).isNotBlank()
    }

    @Test
    fun `rejects invalid currency code`() {
        val result = tool.propose(
            args("accountId" to validAccountId, "fromCurrency" to "EURO", "toCurrency" to "CZK", "amount" to "100"),
        )

        assertThat(result.proposal).isNull()
        assertThat(result.error).isNotBlank()
    }
}
