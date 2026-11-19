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

    private val now = java.time.OffsetDateTime.parse("2026-11-19T10:00:00Z")

    private fun proposal(
        status: String = "PENDING",
        amount: String = "1250.50",
        hash: String = VECTOR,
        expiresAt: java.time.OffsetDateTime = now.plusHours(1),
    ) = CustomerMcpProposal(
        id, party, status, "mcp-hosted-reference", "DOMESTIC_PAYMENT",
        UUID.fromString("33333333-3333-4333-8333-333333333333"), "CZ6508000000192000145399",
        "Elektrárna Praha a.s.", amount, "CZK", "Faktura 2026-11", hash, "mcp-proposal:$id",
        expiresAt,
    )

    @Test
    fun `canonical hash equals delegation-service's pinned vector (PaymentProposalTest)`() {
        assertThat(proposal().canonicalSha256()).isEqualTo(VECTOR)
    }

    @Test
    fun `a pending proposal with the matching hash may be approved`() {
        assertThat(proposal().refusalFor(party, VECTOR, now)).isNull()
    }

    @Test
    fun `mismatched supplied hash is refused before SCA`() {
        assertThat(proposal().refusalFor(party, "a".repeat(64), now)).isEqualTo("PROPOSAL_HASH_MISMATCH")
    }

    @Test
    fun `a store response whose instruction does not match its hash is refused`() {
        assertThat(proposal(amount = "9999.00").refusalFor(party, VECTOR, now)).isEqualTo("PROPOSAL_HASH_INVALID")
    }

    @Test
    fun `another party's proposal and a non-pending one are refused`() {
        assertThat(proposal().refusalFor(UUID.randomUUID(), VECTOR, now)).isEqualTo("PROPOSAL_NOT_FOUND")
        // APPROVED with no payment yet is the retry of a payment that failed after approval.
        assertThat(proposal(status = "APPROVED").refusalFor(party, VECTOR, now)).isNull()
        assertThat(proposal(status = "APPROVED").copy(executedPaymentId = "p").refusalFor(party, VECTOR, now))
            .isEqualTo("PROPOSAL_NOT_PENDING")
        assertThat(proposal(status = "EXECUTED").refusalFor(party, VECTOR, now)).isEqualTo("PROPOSAL_NOT_PENDING")
    }

    @Test
    fun `a PENDING proposal at or past expiresAt is refused by the edge clock`() {
        assertThat(proposal(expiresAt = now).refusalFor(party, VECTOR, now)).isEqualTo("PROPOSAL_EXPIRED")
        assertThat(
            proposal(expiresAt = now.minusSeconds(1)).refusalFor(party, VECTOR, now),
        ).isEqualTo("PROPOSAL_EXPIRED")
    }

    @Test
    fun `a malformed amount is PROPOSAL_HASH_INVALID, not an exception`() {
        assertThat(proposal(amount = "1.234").refusalFor(party, VECTOR, now)).isEqualTo("PROPOSAL_HASH_INVALID")
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
