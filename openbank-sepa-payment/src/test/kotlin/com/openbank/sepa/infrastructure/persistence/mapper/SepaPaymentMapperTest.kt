// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sepa.infrastructure.persistence.mapper

import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import com.openbank.sepa.domain.model.SepaRejectReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class SepaPaymentMapperTest {

    @Test
    fun `fully populated domain round-trips through the entity unchanged`() {
        val original = SepaPayment(
            id = UUID.randomUUID(),
            idempotencyKey = "idem-full",
            type = SepaPaymentType.SCT_INST,
            status = SepaPaymentStatus.REJECTED,
            debtorAccountId = UUID.randomUUID(),
            debtorIban = "DE89370400440532013000",
            debtorName = "Alice Example",
            creditorIban = "FR7630006000011234567890189",
            creditorName = "Bob Example",
            creditorBic = "DEUTDEFF",
            amount = BigDecimal("205.450000"),
            currency = "EUR",
            remittanceInfo = "Invoice 2026-01",
            endToEndId = "E2E-123",
            rejectReason = SepaRejectReason.SANCTIONS_HIT,
            rejectDetail = "OFAC hit",
            submittedAt = Instant.parse("2026-01-02T10:15:30Z"),
            completedAt = Instant.parse("2026-01-02T10:16:00Z"),
            createdAt = Instant.parse("2026-01-02T10:00:00Z"),
            updatedAt = Instant.parse("2026-01-02T10:16:00Z"),
        )

        val roundTripped = original.toEntity().toDomain()

        assertThat(roundTripped).isEqualTo(original)
    }

    @Test
    fun `domain with null optionals round-trips through the entity unchanged`() {
        val original = SepaPayment(
            id = UUID.randomUUID(),
            idempotencyKey = "idem-nulls",
            type = SepaPaymentType.SCT,
            status = SepaPaymentStatus.RECEIVED,
            debtorAccountId = UUID.randomUUID(),
            debtorIban = "DE89370400440532013000",
            debtorName = "Alice Example",
            creditorIban = "FR7630006000011234567890189",
            creditorName = "Bob Example",
            creditorBic = null,
            amount = BigDecimal("10.000000"),
            currency = "EUR",
            remittanceInfo = null,
            endToEndId = "E2E-nulls",
            rejectReason = null,
            rejectDetail = null,
            submittedAt = null,
            completedAt = null,
            createdAt = Instant.parse("2026-01-02T10:00:00Z"),
            updatedAt = Instant.parse("2026-01-02T10:00:00Z"),
        )

        val roundTripped = original.toEntity().toDomain()

        assertThat(roundTripped).isEqualTo(original)
        assertThat(roundTripped.creditorBic).isNull()
        assertThat(roundTripped.rejectReason).isNull()
        assertThat(roundTripped.submittedAt).isNull()
    }

    @Test
    fun `toEntity projects every field onto the JPA entity`() {
        val payment = SepaPayment(
            id = UUID.randomUUID(),
            idempotencyKey = "idem-entity",
            type = SepaPaymentType.SCT,
            status = SepaPaymentStatus.VALIDATED,
            debtorAccountId = UUID.randomUUID(),
            debtorIban = "DE89370400440532013000",
            debtorName = "Alice Example",
            creditorIban = "FR7630006000011234567890189",
            creditorName = "Bob Example",
            creditorBic = "DEUTDEFF",
            amount = BigDecimal("99.000000"),
            currency = "EUR",
            remittanceInfo = "ref",
            endToEndId = "E2E-entity",
            rejectReason = null,
            rejectDetail = null,
            submittedAt = Instant.parse("2026-01-02T10:15:30Z"),
            completedAt = null,
            createdAt = Instant.parse("2026-01-02T10:00:00Z"),
            updatedAt = Instant.parse("2026-01-02T10:15:30Z"),
        )

        val entity = payment.toEntity()

        assertThat(entity.paymentId).isEqualTo(payment.id)
        assertThat(entity.idempotencyKey).isEqualTo("idem-entity")
        assertThat(entity.paymentType).isEqualTo("SCT")
        assertThat(entity.status).isEqualTo("VALIDATED")
        assertThat(entity.creditorBic).isEqualTo("DEUTDEFF")
        assertThat(entity.rejectReason).isNull()
        assertThat(entity.amount).isEqualByComparingTo(BigDecimal("99.000000"))
    }
}
