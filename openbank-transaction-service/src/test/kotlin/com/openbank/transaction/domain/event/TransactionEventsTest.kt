// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.domain.event

import com.openbank.libs.domain.payment.InstructionType
import com.openbank.libs.domain.payment.PaymentRail
import com.openbank.transaction.domain.model.TransactionType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Unit coverage for the domain events emitted around the transaction saga. */
class TransactionEventsTest {

    private val aggregateId = UUID.randomUUID()
    private val now = Instant.parse("2026-07-14T12:00:00Z")

    @Test
    fun `TransactionInitiatedEvent carries the aggregate and payment fields, with default rail and instruction type`() {
        val event = TransactionInitiatedEvent(
            aggregateId = aggregateId,
            version = 1L,
            referenceNumber = "REF-1",
            type = TransactionType.TRANSFER,
            sourceAccountId = UUID.randomUUID(),
            targetAccountId = UUID.randomUUID(),
            amount = BigDecimal("100.50"),
            currencyCode = "CZK",
            occurredAt = now,
        )

        assertThat(event.aggregateId).isEqualTo(aggregateId)
        assertThat(event.aggregateType).isEqualTo("Transaction")
        assertThat(event.eventType).isEqualTo("TransactionInitiated")
        assertThat(event.rail).isEqualTo(PaymentRail.UNKNOWN)
        assertThat(event.instructionType).isEqualTo(InstructionType.UNKNOWN)
        assertThat(event.initiatedByPartyId).isNull()
        assertThat(event.eventId).isNotNull()
        // AuditConsumer attribution (#3994/#5256): read as the strongest (EVENT-sourced) claim.
        assertThat(event.sourceService).isEqualTo("transaction-service")
    }

    @Test
    fun `TransactionInitiatedEvent honours explicit SCA and rail fields, and supports copy + equality`() {
        val partyId = UUID.randomUUID()
        val scaChallengeId = UUID.randomUUID()
        val event = TransactionInitiatedEvent(
            aggregateId = aggregateId,
            version = 1L,
            referenceNumber = "REF-2",
            type = TransactionType.DEBIT,
            sourceAccountId = null,
            targetAccountId = null,
            amount = BigDecimal("42.00"),
            currencyCode = "EUR",
            initiatedByPartyId = partyId,
            scaChallengeId = scaChallengeId,
            scaExemption = "LOW_VALUE",
            rail = PaymentRail.SEPA_INST,
            instructionType = InstructionType.STANDING_ORDER,
            occurredAt = now,
        )
        val copy = event.copy(referenceNumber = "REF-2-COPY")

        assertThat(event.initiatedByPartyId).isEqualTo(partyId)
        assertThat(event.scaChallengeId).isEqualTo(scaChallengeId)
        assertThat(event.scaExemption).isEqualTo("LOW_VALUE")
        assertThat(event.rail).isEqualTo(PaymentRail.SEPA_INST)
        assertThat(event).isEqualTo(event.copy())
        assertThat(event).isNotEqualTo(copy)
        assertThat(event.toString()).contains("REF-2")
        assertThat(event.hashCode()).isEqualTo(event.copy().hashCode())
    }

    @Test
    fun `TransactionCompletedEvent carries the aggregate and reference number`() {
        val event = TransactionCompletedEvent(
            aggregateId = aggregateId,
            version = 2L,
            referenceNumber = "REF-3",
            occurredAt = now,
        )

        assertThat(event.aggregateType).isEqualTo("Transaction")
        assertThat(event.eventType).isEqualTo("TransactionCompleted")
        assertThat(event.referenceNumber).isEqualTo("REF-3")
        assertThat(event).isEqualTo(event.copy())
        assertThat(event.toString()).contains("REF-3")
        assertThat(event.sourceService).isEqualTo("transaction-service")
    }

    @Test
    fun `TransactionFailedEvent carries the failure reason`() {
        val event = TransactionFailedEvent(
            aggregateId = aggregateId,
            version = 3L,
            referenceNumber = "REF-4",
            reason = "insufficient funds",
            occurredAt = now,
        )

        assertThat(event.aggregateType).isEqualTo("Transaction")
        assertThat(event.eventType).isEqualTo("TransactionFailed")
        assertThat(event.reason).isEqualTo("insufficient funds")
        assertThat(event).isEqualTo(event.copy())
        assertThat(event.toString()).contains("insufficient funds")
        assertThat(event.sourceService).isEqualTo("transaction-service")
    }
}
