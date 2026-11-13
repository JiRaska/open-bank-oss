// SPDX-License-Identifier: Apache-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.\n// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.\n
package com.openbank.domestic.domain.model

import com.openbank.libs.domain.money.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class DomesticPaymentTest {

    @Test
    fun `rejected transition requires reason`() {
        assertThatThrownBy {
            payment().transitionTo(DomesticPaymentStatus.REJECTED, clock = Clock.systemUTC())
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Reject reason is required")
    }

    @Test
    fun `settled transition stamps submitted and settled timestamps`() {
        val now = Instant.parse("2026-01-02T00:00:00Z")

        val settled = payment(status = DomesticPaymentStatus.SENT_TO_CLEARING).transitionTo(
            DomesticPaymentStatus.SETTLED,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        assertThat(settled.submittedAt).isEqualTo(now)
        assertThat(settled.settledAt).isEqualTo(now)
        assertThat(settled.aggregateRevision).isEqualTo(2)
    }

    @Test
    fun `every valid lifecycle transition increments the durable aggregate revision once`() {
        val clock = Clock.fixed(Instant.parse("2026-01-02T00:00:00Z"), ZoneOffset.UTC)
        val received = payment()

        val validated = received.transitionTo(DomesticPaymentStatus.VALIDATED, clock = clock)
        val submitted = validated.transitionTo(DomesticPaymentStatus.SENT_TO_CLEARING, clock = clock)

        assertThat(received.aggregateRevision).isEqualTo(1)
        assertThat(validated.aggregateRevision).isEqualTo(2)
        assertThat(submitted.aggregateRevision).isEqualTo(3)
    }

    @Test
    fun `a payment is held only from RECEIVED and a hold ends admitted or rejected, nothing else`() {
        val clock = java.time.Clock.systemUTC()
        assertThat(DomesticPaymentStatus.entries.filter { payment(it).canTransitionTo(DomesticPaymentStatus.HELD) })
            .containsExactly(DomesticPaymentStatus.RECEIVED)
        assertThat(DomesticPaymentStatus.entries.filter { payment(DomesticPaymentStatus.HELD).canTransitionTo(it) })
            .containsExactly(DomesticPaymentStatus.VALIDATED, DomesticPaymentStatus.REJECTED)

        val held = payment().transitionTo(DomesticPaymentStatus.HELD, clock = clock)
        // Nothing was submitted: a held payment has not passed the controls yet.
        assertThat(held.submittedAt).isNull()
        assertThat(held.aggregateRevision).isEqualTo(2)
        assertThat(held.transitionTo(DomesticPaymentStatus.VALIDATED, clock = clock).submittedAt).isNotNull()
    }

    @Test
    fun `a customer channel is shown a held payment as received and every other status as it is`() {
        assertThat(DomesticPaymentStatus.HELD.customerFacing).isEqualTo(CustomerFacingPaymentStatus.RECEIVED)
        assertThat(DomesticPaymentStatus.entries.filter { it.customerFacing.asRailStatus != it })
            .containsExactly(DomesticPaymentStatus.HELD)
        assertThat(CustomerFacingPaymentStatus.entries.map { it.name }).doesNotContain("HELD")
    }

    @Test
    fun `a customer channel is told a reason only when it is theirs to act on, never one that names a control`() {
        val shown = DomesticRejectReason.entries.associateWith { it.customerFacing.asRailReason }

        // Named one by one: a reason added later must be placed here on purpose.
        assertThat(shown.filterValues { it == null }.keys).containsExactlyInAnyOrder(
            DomesticRejectReason.AML_HOLD,
            DomesticRejectReason.SANCTIONS_HIT,
            DomesticRejectReason.FRAUD_SUSPECTED,
            DomesticRejectReason.DEBTOR_ACCOUNT_BLOCKED,
        )
        shown.filterValues { it != null }.forEach { (reason, seen) -> assertThat(seen).isEqualTo(reason) }
    }

    @Test
    fun `a hold records when it began and keeps that after it ends, and its case is requested once`() {
        val clock = Clock.fixed(Instant.parse("2026-11-13T10:00:00Z"), ZoneOffset.UTC)
        val later = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC)
        val held = payment(DomesticPaymentStatus.RECEIVED).transitionTo(DomesticPaymentStatus.HELD, clock = clock)
        assertThat(held.heldAt).isEqualTo(clock.instant())
        assertThat(held.holdCaseRequestedAt).isNull()

        val requested = held.withHoldCaseRequested(clock.instant(), setOf("SANCTIONS:SANCTIONS_POTENTIAL_MATCH"))
        val again = requested.withHoldCaseRequested(later.instant(), setOf("FRAUD:FRAUD_REVIEW"))
        assertThat(again.holdCaseRequestedAt).isEqualTo(clock.instant())
        assertThat(again.holdCaseReasons).containsExactly("SANCTIONS:SANCTIONS_POTENTIAL_MATCH")

        val released = requested.transitionTo(DomesticPaymentStatus.VALIDATED, clock = later)
        assertThat(released.heldAt).isEqualTo(clock.instant())
        assertThatThrownBy { released.withHoldCaseRequested(later.instant(), emptySet()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `delegation and reservation form an indivisible pair`() {
        assertThatThrownBy {
            payment().copy(delegationId = UUID.randomUUID())
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must either both be present or both be absent")

        assertThatThrownBy {
            payment().copy(reservationId = UUID.randomUUID())
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must either both be present or both be absent")
    }

    private fun payment(status: DomesticPaymentStatus = DomesticPaymentStatus.RECEIVED) = DomesticPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "idem",
        status = status,
        debtorAccountId = UUID.randomUUID(),
        debtorAccountNumber = "1234567890",
        debtorBankCode = "0800",
        debtorName = "Debtor",
        creditorAccountNumber = "0987654321",
        creditorBankCode = "2010",
        creditorName = "Creditor",
        amount = Money.of(BigDecimal("20.00"), "CZK"),
        variableSymbol = null,
        specificSymbol = null,
        constantSymbol = null,
        messageForPayee = null,
        priority = DomesticPaymentPriority.STANDARD,
        transferScope = DomesticTransferScope.INTERNAL_CLIENT,
        technicalAccountCode = null,
        statementLabel = null,
        endToEndId = "DOM1",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = null,
        settledAt = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )
}
