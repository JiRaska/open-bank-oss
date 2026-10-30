// SPDX-License-Identifier: Apache-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.\n// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.\n
package com.openbank.sepa.domain.model

import com.openbank.libs.domain.money.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SepaPaymentTest {

    @Test
    fun `pending return refuses a fresh conflicting terminal transition`() {
        val pending = payment(status = SepaPaymentStatus.PROCESSING).copy(
            revision = 17,
            returnIntent = SepaReturnIntent(
                UUID.randomUUID(),
                Instant.parse("2026-01-02T00:00:00Z"),
                "AM09",
                "actor",
                "ROLE_API",
                null,
            ),
        )
        for (target in listOf(SepaPaymentStatus.REJECTED, SepaPaymentStatus.CANCELLED, SepaPaymentStatus.RETURNED)) {
            assertThat(pending.canTransitionTo(target)).isFalse()
            assertThatThrownBy {
                pending.transitionTo(target, SepaRejectReason.TECHNICAL_ERROR, clock = Clock.systemUTC())
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(pending.canTransitionTo(SepaPaymentStatus.COMPLETED)).isFalse()
        val identified = pending.copy(transactionId = pending.returnIntent?.originalTransactionId)
        assertThat(identified.canTransitionTo(SepaPaymentStatus.COMPLETED)).isTrue()
    }

    @Test
    fun `returned requires proven reversal even through generic status transition`() {
        val now = Instant.parse("2026-01-02T00:00:00Z")
        for (status in listOf(SepaPaymentStatus.PROCESSING, SepaPaymentStatus.COMPLETED)) {
            val original = payment(status = status)
            assertThatThrownBy { original.transitionTo(SepaPaymentStatus.RETURNED, clock = Clock.systemUTC()) }
                .isInstanceOf(IllegalArgumentException::class.java)
            val intent = SepaReturnIntent(
                UUID.randomUUID(),
                now.minusSeconds(30),
                "AM09",
                "actor",
                "ROLE_API",
                null,
                resolvedAt = now,
                reversalTransactionId = UUID.randomUUID(),
            )
            val returned = original.copy(returnIntent = intent)
                .transitionTo(SepaPaymentStatus.RETURNED, clock = Clock.fixed(now, ZoneOffset.UTC))
            assertThat(returned.status).isEqualTo(SepaPaymentStatus.RETURNED)
            assertThat(returned.returnIntent?.firstReceivedAt).isEqualTo(now.minusSeconds(30))
        }
    }

    @Test
    fun `reject transition requires reject reason`() {
        assertThatThrownBy {
            payment().transitionTo(SepaPaymentStatus.REJECTED, clock = Clock.systemUTC())
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Reject reason is required")
    }

    @Test
    fun `completed transition sets submitted and completed timestamps`() {
        val now = Instant.parse("2026-01-02T00:00:00Z")
        val transactionId = UUID.randomUUID()

        val transitioned = payment(status = SepaPaymentStatus.PROCESSING).copy(
            transactionId = transactionId,
        ).transitionTo(
            targetStatus = SepaPaymentStatus.COMPLETED,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        assertThat(transitioned.status).isEqualTo(SepaPaymentStatus.COMPLETED)
        assertThat(transitioned.submittedAt).isEqualTo(now)
        assertThat(transitioned.completedAt).isEqualTo(now)
        assertThat(transitioned.revision).isEqualTo(1)
        assertThat(transitioned.transactionId).isEqualTo(transactionId)
    }

    @Test
    fun `reject transition records reason detail and terminal timestamps`() {
        val now = Instant.parse("2026-01-02T00:00:00Z")

        val rejected = payment().transitionTo(
            targetStatus = SepaPaymentStatus.REJECTED,
            reason = SepaRejectReason.INSUFFICIENT_FUNDS,
            detail = "debit account below threshold",
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        assertThat(rejected.status).isEqualTo(SepaPaymentStatus.REJECTED)
        // Mutant tripwire: a negated `targetStatus == REJECTED` conditional in transitionTo
        // would drop exactly these two fields while every other assertion still passes.
        assertThat(rejected.rejectReason).isEqualTo(SepaRejectReason.INSUFFICIENT_FUNDS)
        assertThat(rejected.rejectDetail).isEqualTo("debit account below threshold")
        assertThat(rejected.submittedAt).isEqualTo(now)
        // REJECTED is deliberately NOT in the completedAt branch set — a rejected payment
        // has no completion; asserting null here is a tripwire against that set widening.
        assertThat(rejected.completedAt).isNull()
        assertThat(rejected.revision).isEqualTo(1)
    }

    @Test
    fun `non-reject transition clears any stale reject context`() {
        val withStaleReject = payment().copy(
            rejectReason = SepaRejectReason.TECHNICAL_ERROR,
            rejectDetail = "stale",
        )

        val validated = withStaleReject.transitionTo(
            targetStatus = SepaPaymentStatus.VALIDATED,
            // A reason supplied for a non-reject target must NOT leak into the aggregate:
            // the conditional in transitionTo must select the else branch here.
            reason = SepaRejectReason.INSUFFICIENT_FUNDS,
            clock = Clock.systemUTC(),
        )

        assertThat(validated.status).isEqualTo(SepaPaymentStatus.VALIDATED)
        assertThat(validated.rejectReason).isNull()
        assertThat(validated.rejectDetail).isNull()
    }

    @Test
    fun `cancelled transition is terminal and completes with a timestamp`() {
        val now = Instant.parse("2026-01-02T00:00:00Z")
        val submittedAt = Instant.parse("2026-01-01T12:00:00Z")

        val cancelled = payment(status = SepaPaymentStatus.VALIDATED).copy(
            submittedAt = submittedAt,
        ).transitionTo(
            targetStatus = SepaPaymentStatus.CANCELLED,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        assertThat(cancelled.status).isEqualTo(SepaPaymentStatus.CANCELLED)
        assertThat(cancelled.submittedAt).isEqualTo(submittedAt)
        assertThat(cancelled.completedAt).isEqualTo(now)
    }

    @Test
    fun `currency derives from the kernel money amount`() {
        assertThat(payment().currency).isEqualTo("EUR")
    }

    @Test
    fun `request hash fingerprint is preserved on the aggregate`() {
        // Issue #10916: the fingerprint a duplicate POST is matched against must survive
        // round-tripping the aggregate; an empty-string mutant on the getter would break
        // idempotent-replay detection while every structural assertion stayed green.
        val fingerprinted = payment().copy(requestHash = "sha256:deadbeef")

        assertThat(fingerprinted.requestHash).isEqualTo("sha256:deadbeef")
        assertThat(payment().requestHash).isNull()
    }

    @Test
    fun `bic and debtor name are exposed on the aggregate`() {
        val exposed = payment()

        assertThat(exposed.creditorBic).isEqualTo("COBADEFFXXX")
        assertThat(exposed.debtorName).isEqualTo("Debtor")
    }

    private fun payment(status: SepaPaymentStatus = SepaPaymentStatus.RECEIVED) = SepaPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "idem",
        type = SepaPaymentType.SCT,
        status = status,
        debtorAccountId = UUID.randomUUID(),
        debtorIban = "CZ6500000000000000000001",
        debtorName = "Debtor",
        creditorIban = "DE89370400440532013000",
        creditorName = "Creditor",
        creditorBic = "COBADEFFXXX",
        amount = Money.of(BigDecimal("10.00"), "EUR"),
        remittanceInfo = null,
        endToEndId = "E2E1",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = null,
        completedAt = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )
}
