// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.persistence.mapper

import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticRejectReason
import com.openbank.domestic.domain.model.DomesticTransferScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class DomesticPaymentMapperTest {

    private val initiatedByPartyId = UUID.randomUUID()
    private val requestFingerprint = "a".repeat(64)
    private val delegationId = UUID.randomUUID()
    private val reservationId = UUID.randomUUID()

    private fun fullyPopulated() = DomesticPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "idem-key-1",
        status = DomesticPaymentStatus.REJECTED,
        debtorAccountId = UUID.randomUUID(),
        debtorAccountNumber = "19-2000145399",
        debtorBankCode = "0800",
        debtorName = "Jan Novak",
        creditorAccountNumber = "123456789",
        creditorBankCode = "0100",
        creditorName = "Brno Utility",
        amount = BigDecimal("1234.560000"),
        currency = "CZK",
        variableSymbol = "2026001",
        specificSymbol = "55",
        constantSymbol = "0308",
        messageForPayee = "Utility bill",
        priority = DomesticPaymentPriority.URGENT,
        transferScope = DomesticTransferScope.TECHNICAL_ACCOUNT,
        technicalAccountCode = "TECH-1",
        statementLabel = "Monthly settlement",
        endToEndId = "DOMU123456",
        rejectReason = DomesticRejectReason.SANCTIONS_HIT,
        rejectDetail = "creditor on list",
        submittedAt = Instant.parse("2026-06-01T10:00:00Z"),
        settledAt = Instant.parse("2026-06-01T11:00:00Z"),
        createdAt = Instant.parse("2026-06-01T09:00:00Z"),
        updatedAt = Instant.parse("2026-06-01T12:00:00Z"),
        initiatedByPartyId = initiatedByPartyId,
        requestFingerprint = requestFingerprint,
        delegationId = delegationId,
        reservationId = reservationId,
    )

    private fun nullOptionals() = DomesticPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "idem-key-2",
        status = DomesticPaymentStatus.RECEIVED,
        debtorAccountId = UUID.randomUUID(),
        debtorAccountNumber = "1000",
        debtorBankCode = "0800",
        debtorName = "Payer",
        creditorAccountNumber = "2000",
        creditorBankCode = "0100",
        creditorName = "Payee",
        amount = BigDecimal("10.000000"),
        currency = "CZK",
        variableSymbol = null,
        specificSymbol = null,
        constantSymbol = null,
        messageForPayee = null,
        priority = DomesticPaymentPriority.STANDARD,
        transferScope = DomesticTransferScope.INTERNAL_CLIENT,
        technicalAccountCode = null,
        statementLabel = null,
        endToEndId = "DOMS999",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = null,
        settledAt = null,
        createdAt = Instant.parse("2026-06-02T09:00:00Z"),
        updatedAt = Instant.parse("2026-06-02T09:00:00Z"),
    )

    @Test
    fun `fully-populated payment survives an entity round-trip`() {
        val original = fullyPopulated()

        val roundTripped = original.toEntity().toDomain()

        assertThat(roundTripped).isEqualTo(original)
    }

    @Test
    fun `null-optionals payment survives an entity round-trip`() {
        val original = nullOptionals()

        val roundTripped = original.toEntity().toDomain()

        assertThat(roundTripped).isEqualTo(original)
    }

    @Test
    fun `toEntity projects enum and optional fields onto entity columns`() {
        val original = fullyPopulated()

        val entity = original.toEntity()

        assertThat(entity.paymentId).isEqualTo(original.id)
        assertThat(entity.status).isEqualTo("REJECTED")
        assertThat(entity.priority).isEqualTo("URGENT")
        assertThat(entity.transferScope).isEqualTo("TECHNICAL_ACCOUNT")
        assertThat(entity.rejectReason).isEqualTo("SANCTIONS_HIT")
        assertThat(entity.amount).isEqualByComparingTo(original.amount)
        assertThat(entity.initiatedByPartyId).isEqualTo(initiatedByPartyId)
        assertThat(entity.requestFingerprint).isEqualTo(requestFingerprint)
        assertThat(entity.delegationId).isEqualTo(delegationId)
        assertThat(entity.reservationId).isEqualTo(reservationId)
    }

    @Test
    fun `toEntity leaves rejectReason null when domain reject reason is absent`() {
        val entity = nullOptionals().toEntity()

        assertThat(entity.rejectReason).isNull()
        assertThat(entity.submittedAt).isNull()
        assertThat(entity.settledAt).isNull()
        assertThat(entity.initiatedByPartyId).isNull()
        assertThat(entity.requestFingerprint).isNull()
        assertThat(entity.delegationId).isNull()
        assertThat(entity.reservationId).isNull()
    }
}
