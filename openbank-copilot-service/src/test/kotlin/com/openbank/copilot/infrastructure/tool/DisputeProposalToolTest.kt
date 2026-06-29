// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.infrastructure.tool

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.copilot.domain.ActionKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DisputeProposalToolTest {

    private val tool = DisputeProposalTool()
    private val mapper = ObjectMapper()

    private fun args(vararg pairs: Pair<String, String>): JsonNode = mapper.valueToTree(mapOf(*pairs))

    @Test
    fun `valid transaction id and reason produce a DISPUTE proposal`() {
        val result = tool.propose(
            args(
                "transactionId" to "33333333-3333-3333-3333-333333333333",
                "reason" to "Tuto platbu jsem nedělal",
            ),
        )

        assertThat(result.error).isNull()
        assertThat(result.proposal!!.kind).isEqualTo(ActionKind.DISPUTE)
        assertThat(result.proposal!!.fields["transactionId"]).isEqualTo("33333333-3333-3333-3333-333333333333")
        assertThat(result.proposal!!.fields["reason"]).isEqualTo("Tuto platbu jsem nedělal")
    }

    @Test
    fun `rejects an invalid transaction id`() {
        val result = tool.propose(args("transactionId" to "nope", "reason" to "x"))

        assertThat(result.proposal).isNull()
        assertThat(result.error).contains("transakce")
    }

    @Test
    fun `requires a reason`() {
        val result = tool.propose(args("transactionId" to "33333333-3333-3333-3333-333333333333"))

        assertThat(result.proposal).isNull()
        assertThat(result.error).contains("důvod")
    }
}
