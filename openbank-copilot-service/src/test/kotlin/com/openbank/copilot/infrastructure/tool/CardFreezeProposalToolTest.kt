// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.infrastructure.tool

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.copilot.domain.ActionKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CardFreezeProposalToolTest {

    private val tool = CardFreezeProposalTool()
    private val mapper = ObjectMapper()

    private fun args(vararg pairs: Pair<String, String>): JsonNode = mapper.valueToTree(mapOf(*pairs))

    @Test
    fun `valid card id produces a CARD_FREEZE proposal`() {
        val result = tool.propose(args("cardId" to "22222222-2222-2222-2222-222222222222", "reason" to "ztracená"))

        assertThat(result.error).isNull()
        assertThat(result.proposal!!.kind).isEqualTo(ActionKind.CARD_FREEZE)
        assertThat(result.proposal!!.fields["cardId"]).isEqualTo("22222222-2222-2222-2222-222222222222")
        assertThat(result.proposal!!.fields["reason"]).isEqualTo("ztracená")
    }

    @Test
    fun `rejects an invalid card id`() {
        val result = tool.propose(args("cardId" to "nope"))

        assertThat(result.proposal).isNull()
        assertThat(result.error).contains("karty")
    }

    @Test
    fun `reason is optional`() {
        val result = tool.propose(args("cardId" to "22222222-2222-2222-2222-222222222222"))

        assertThat(result.proposal).isNotNull
        assertThat(result.proposal!!.fields).doesNotContainKey("reason")
    }
}
