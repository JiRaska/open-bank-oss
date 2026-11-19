// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class CustomerMcpProposalTest {
    private val id = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val party = UUID.fromString("22222222-2222-4222-8222-222222222222")

    private fun proposal(status: String = "PENDING", amount: String = "1250.50", hash: String = VECTOR) =
        CustomerMcpProposal(
            id, party, status, "mcp-hosted-reference", "DOMESTIC_PAYMENT",
            UUID.fromString("33333333-3333-4333-8333-333333333333"), "CZ6508000000192000145399",
            "Elektrárna Praha a.s.", amount, "CZK", "Faktura 2026-11", hash, "mcp-proposal:$id",
        )

    @Test
    fun `canonical hash equals delegation-service's pinned vector (PaymentProposalTest)`() {
        assertThat(proposal().canonicalSha256()).isEqualTo(VECTOR)
    }

    @Test
    fun `a pending proposal with the matching hash may be approved`() {
        assertThat(proposal().refusalFor(party, VECTOR)).isNull()
    }

    @Test
    fun `mismatched supplied hash is refused before SCA`() {
        assertThat(proposal().refusalFor(party, "a".repeat(64))).isEqualTo("PROPOSAL_HASH_MISMATCH")
    }

    @Test
    fun `a store response whose instruction does not match its hash is refused`() {
        assertThat(proposal(amount = "9999.00").refusalFor(party, VECTOR)).isEqualTo("PROPOSAL_HASH_INVALID")
    }

    @Test
    fun `another party's proposal and a non-pending one are refused`() {
        assertThat(proposal().refusalFor(UUID.randomUUID(), VECTOR)).isEqualTo("PROPOSAL_NOT_FOUND")
        assertThat(proposal(status = "APPROVED").refusalFor(party, VECTOR)).isEqualTo("PROPOSAL_NOT_PENDING")
        assertThat(proposal(status = "EXPIRED").refusalFor(party, VECTOR)).isEqualTo("PROPOSAL_NOT_PENDING")
    }

    @Test
    fun `the payment body is built only from the stored instruction`() {
        val body = ObjectMapper().readTree(proposal().domesticBody(ObjectMapper()))
        assertThat(body.path("amount").asText()).isEqualTo("1250.50")
        assertThat(body.path("creditorAccountNumber").asText()).isEqualTo("CZ6508000000192000145399")
        assertThat(body.has("scaExemption")).isFalse()
    }

    private companion object {
        const val VECTOR = "a6dc945bf55dbb394354af807887bb5e71ff75f3743bd9ee5f2d31d161c46ff5"
    }
}
