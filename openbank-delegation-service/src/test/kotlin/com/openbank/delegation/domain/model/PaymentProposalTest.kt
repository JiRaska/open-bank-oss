// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class PaymentProposalTest {

    private val now = OffsetDateTime.of(2026, 11, 19, 10, 0, 0, 0, ZoneOffset.UTC)

    @Test
    fun `canonical hash matches the vector customer-edge pins (CustomerProposalHashTest)`() {
        assertThat(PaymentProposal.canonicalSha256(VECTOR_ID, VECTOR_PARTY, vectorPayload()))
            .isEqualTo(VECTOR_SHA256)
    }

    @Test
    fun `the hash binds the proposal id and party, not only the payment`() {
        val other = PaymentProposal.canonicalSha256(UUID.randomUUID(), VECTOR_PARTY, vectorPayload())
        val otherParty = PaymentProposal.canonicalSha256(VECTOR_ID, UUID.randomUUID(), vectorPayload())
        assertThat(other).isNotEqualTo(VECTOR_SHA256)
        assertThat(otherParty).isNotEqualTo(VECTOR_SHA256)
    }

    @Test
    fun `a row whose stored hash does not describe its payload cannot be loaded`() {
        assertThatThrownBy { proposal().copy(payloadSha256 = "0".repeat(64)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `approve with the stored hash moves PENDING to APPROVED once`() {
        val approved = proposal().approve(VECTOR_SHA256, now)
        assertThat(approved.status).isEqualTo(PaymentProposalStatus.APPROVED)
        assertThat(approved.revision).isEqualTo(1)
        assertThat(approved.decidedAt).isEqualTo(now)
    }

    @Test
    fun `approval with a mismatched hash is refused and changes nothing`() {
        val pending = proposal()
        val tampered = PaymentProposal.canonicalSha256(VECTOR_ID, VECTOR_PARTY, vectorPayload(amount = "1250.51"))
        assertThatThrownBy { pending.approve(tampered, now) }
            .isInstanceOf(PaymentProposalHashMismatchException::class.java)
        assertThat(pending.status).isEqualTo(PaymentProposalStatus.PENDING)
    }

    @Test
    fun `a replayed approval is refused`() {
        val approved = proposal().approve(VECTOR_SHA256, now)
        assertThatThrownBy { approved.approve(VECTOR_SHA256, now) }
            .isInstanceOf(PaymentProposalStateException::class.java)
    }

    @Test
    fun `an expired proposal cannot be approved and reads as EXPIRED`() {
        val pending = proposal()
        val late = pending.expiresAt
        assertThat(pending.effectiveStatus(late)).isEqualTo(PaymentProposalStatus.EXPIRED)
        assertThatThrownBy { pending.approve(VECTOR_SHA256, late) }
            .isInstanceOf(PaymentProposalStateException::class.java)
        assertThatThrownBy { pending.reject(late) }.isInstanceOf(PaymentProposalStateException::class.java)
    }

    @Test
    fun `execution is recorded only after approval and only against the same hash`() {
        val pending = proposal()
        val paymentId = UUID.randomUUID()
        assertThatThrownBy { pending.recordExecuted(VECTOR_SHA256, paymentId, now) }
            .isInstanceOf(PaymentProposalStateException::class.java)
        val approved = pending.approve(VECTOR_SHA256, now)
        assertThatThrownBy { approved.recordExecuted("f".repeat(64), paymentId, now) }
            .isInstanceOf(PaymentProposalHashMismatchException::class.java)
        val executed = approved.recordExecuted(VECTOR_SHA256, paymentId, now)
        assertThat(executed.status).isEqualTo(PaymentProposalStatus.EXECUTED)
        assertThat(executed.executedPaymentId).isEqualTo(paymentId)
        assertThatThrownBy { executed.recordExecuted(VECTOR_SHA256, paymentId, now) }
            .isInstanceOf(PaymentProposalStateException::class.java)
    }

    @Test
    fun `a rejected proposal can never be approved`() {
        val rejected = proposal().reject(now)
        assertThatThrownBy { rejected.approve(VECTOR_SHA256, now) }
            .isInstanceOf(PaymentProposalStateException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["line\nbreak", "see https://x.example", "![img](x)", "<b>", "tab\there"])
    fun `model-composed free text with control characters or markup is refused`(reference: String) {
        assertThatThrownBy { vectorPayload(reference = reference) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["Pay\u202Eeman", "Elek\u200Btrarna", "Name\u2028Other", "Name\u2029x", "a\uFEFFb"])
    fun `Unicode format, bidi and separator characters in a payee name are refused`(name: String) {
        assertThatThrownBy {
            vectorPayload().copy(creditorName = name)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a non-NFC payee name is refused, its NFC form accepted`() {
        val decomposed = "Elektra\u0301rna"
        assertThatThrownBy { vectorPayload().copy(creditorName = decomposed) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(vectorPayload().copy(creditorName = PaymentProposalPayload.normalize(decomposed)).creditorName)
            .isEqualTo("Elektrárna")
    }

    @Test
    fun `the rail fixes the currency`() {
        assertThatThrownBy { vectorPayload(currency = "EUR") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            vectorPayload().copy(operationType = PaymentProposalOperationType.SEPA_PAYMENT)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `dedupe compares payee amount currency and debtor, not the free text`() {
        assertThat(vectorPayload().sameInstructionAs(vectorPayload(amount = "1250.5", reference = "other"))).isTrue()
        assertThat(vectorPayload().sameInstructionAs(vectorPayload(amount = "1250.51"))).isFalse()
    }

    private fun proposal() = PaymentProposal(
        id = VECTOR_ID,
        partyId = VECTOR_PARTY,
        grantId = UUID.randomUUID(),
        mcpClientId = "mcp-hosted-reference",
        payload = vectorPayload(),
        payloadSha256 = VECTOR_SHA256,
        status = PaymentProposalStatus.PENDING,
        createdAt = now,
        expiresAt = now.plusHours(24),
    )

    companion object {
        val VECTOR_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val VECTOR_PARTY: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        const val VECTOR_SHA256 = "a6dc945bf55dbb394354af807887bb5e71ff75f3743bd9ee5f2d31d161c46ff5"

        fun vectorPayload(
            amount: String = "1250.50",
            currency: String = "CZK",
            reference: String? = "Faktura 2026-11",
        ) = PaymentProposalPayload(
            operationType = PaymentProposalOperationType.DOMESTIC_PAYMENT,
            debtorAccountId = UUID.fromString("33333333-3333-4333-8333-333333333333"),
            creditorIban = "CZ6508000000192000145399",
            creditorName = "Elektrárna Praha a.s.",
            amount = BigDecimal(amount),
            currency = currency,
            reference = reference,
        )
    }
}
