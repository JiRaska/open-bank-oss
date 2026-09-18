// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.domestic.domain.model

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.domestic.infrastructure.persistence.entity.DomesticPaymentProposalDraftEntity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class DomesticPaymentProposalDraftTest {
    private val mapper = ObjectMapper().registerKotlinModule()
    private val accountId = UUID.randomUUID()
    private val ownerId = UUID.randomUUID()
    private val makerId = UUID.randomUUID()

    private fun instruction(amount: String = "100.00") = PaymentProposalInstruction(
        debtorAccountId = accountId,
        debtorAccountNumber = "123456789",
        debtorBankCode = "0800",
        debtorName = "Owner",
        creditorAccountNumber = "987654321",
        creditorBankCode = "0800",
        creditorName = "Recipient",
        amount = BigDecimal(amount),
        currency = "CZK",
        variableSymbol = "123",
        specificSymbol = null,
        constantSymbol = null,
        messageForPayee = "Invoice",
        priority = DomesticPaymentPriority.STANDARD,
        statementLabel = null,
        endToEndId = null,
    )

    private fun draft() = DomesticPaymentProposalDraft(
        id = UUID.randomUUID(),
        makerPartyId = makerId,
        ownerPartyId = ownerId,
        delegationId = UUID.randomUUID(),
        idempotencyKey = "idem-1",
        requestFingerprint = "a".repeat(64),
        instruction = instruction(),
        createdAt = Instant.parse("2026-09-18T12:00:00Z"),
        expiresAt = Instant.parse("2026-09-25T12:00:00Z"),
    )

    @Test
    fun `draft instruction round trips without acquiring execution state`() {
        val expected = draft()
        val entity = DomesticPaymentProposalDraftEntity.fromDomain(expected, mapper)

        assertThat(entity.status).isEqualTo("DRAFT")
        assertThat(entity.toDomain(mapper)).isEqualTo(expected)
        assertThat(expected.isExpiredAt(expected.expiresAt)).isTrue()
        assertThat(expected.isExpiredAt(expected.createdAt)).isFalse()
    }

    @Test
    fun `maker cannot be owner and monetary amount must be positive`() {
        assertThatThrownBy { draft().copy(ownerPartyId = makerId) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { instruction("0.00") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { instruction("-1.00") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `unknown status cannot be interpreted as an executable draft`() {
        val entity = DomesticPaymentProposalDraftEntity.fromDomain(draft(), mapper)
        entity.status = "APPROVED"

        assertThatThrownBy { entity.toDomain(mapper) }
            .isInstanceOf(IllegalStateException::class.java)
    }
}
