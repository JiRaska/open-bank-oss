// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.domain

import com.openbank.cardprocessing.domain.model.AuthorizationStatus
import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.cardprocessing.domain.model.PresentmentChannel
import com.openbank.cardprocessing.domain.model.PresentmentOutcome
import com.openbank.cardprocessing.domain.model.PresentmentRefusal
import com.openbank.cardprocessing.domain.policy.AuthorizationLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Every branch of the transition policy, exercised without a database or an acquirer.
 *
 * The clock is fixed, so an expiry test asserts the RULE rather than waiting for wall time — and
 * the "not yet expired" case is testable at all, which it would not be against `Instant.now()`.
 */
class AuthorizationLifecycleTest {

    private val authorizedAt = Instant.parse("2026-09-05T10:00:00Z")
    private val clock = Clock.fixed(authorizedAt.plus(Duration.ofHours(1)), ZoneOffset.UTC)

    private fun authorization(
        amount: Long = 10_000,
        cleared: Long = 0,
        status: AuthorizationStatus = AuthorizationStatus.APPROVED,
    ) = CardAuthorization(
        id = UUID.randomUUID(),
        cardId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        partyId = UUID.randomUUID(),
        amountMinorUnits = amount,
        currencyCode = "CZK",
        channel = PresentmentChannel.CONTACTLESS,
        mcc = "5411",
        merchantName = "Potraviny",
        merchantCountry = "CZ",
        status = status,
        category = "GROCERIES",
        declineReason = if (status == AuthorizationStatus.DECLINED) "CARD_NOT_ACTIVE" else null,
        clearedAmountMinorUnits = cleared,
        networkReference = "acq-1",
        authorizedAt = authorizedAt,
        expiresAt = authorizedAt.plus(Duration.ofDays(7)),
        updatedAt = authorizedAt,
    )

    @Test
    fun `a full presentment clears the authorisation`() {
        val outcome = AuthorizationLifecycle.clear(authorization(), 10_000, "CZK", clock)

        val accepted = outcome as PresentmentOutcome.Accepted
        assertThat(accepted.authorization.status).isEqualTo(AuthorizationStatus.CLEARED)
        assertThat(accepted.authorization.clearedAmountMinorUnits).isEqualTo(10_000)
        assertThat(accepted.authorization.heldAmountMinorUnits).isZero()
        // The transition stamps the injected clock, not the wall clock.
        assertThat(accepted.authorization.updatedAt).isEqualTo(Instant.now(clock))
    }

    @Test
    fun `a partial presentment leaves the remainder held`() {
        val outcome = AuthorizationLifecycle.clear(authorization(), 4_000, "CZK", clock)

        val accepted = outcome as PresentmentOutcome.Accepted
        assertThat(accepted.authorization.status).isEqualTo(AuthorizationStatus.PARTIALLY_CLEARED)
        assertThat(accepted.authorization.heldAmountMinorUnits).isEqualTo(6_000)
        // Still holding, so the whole authorised amount still counts against the limit — a customer
        // must not be able to spend the unpresented remainder twice.
        assertThat(accepted.authorization.effectiveSpendMinorUnits).isEqualTo(10_000)
    }

    @Test
    fun `cumulative over-clearing is refused rather than tolerated`() {
        val partly = authorization(cleared = 8_000, status = AuthorizationStatus.PARTIALLY_CLEARED)

        val outcome = AuthorizationLifecycle.clear(partly, 3_000, "CZK", clock)

        assertThat(outcome).isEqualTo(PresentmentOutcome.Refused(PresentmentRefusal.EXCEEDS_AUTHORIZED_AMOUNT))
    }

    @Test
    fun `a presentment in another currency is refused`() {
        assertThat(AuthorizationLifecycle.clear(authorization(), 1_000, "EUR", clock))
            .isEqualTo(PresentmentOutcome.Refused(PresentmentRefusal.CURRENCY_MISMATCH))
    }

    @Test
    fun `a non-positive presentment is refused`() {
        assertThat(AuthorizationLifecycle.clear(authorization(), 0, "CZK", clock))
            .isEqualTo(PresentmentOutcome.Refused(PresentmentRefusal.AMOUNT_NOT_POSITIVE))
        assertThat(AuthorizationLifecycle.clear(authorization(), -1, "CZK", clock))
            .isEqualTo(PresentmentOutcome.Refused(PresentmentRefusal.AMOUNT_NOT_POSITIVE))
    }

    @Test
    fun `clearing a cleared authorisation is refused, not applied twice`() {
        val cleared = authorization(cleared = 10_000, status = AuthorizationStatus.CLEARED)

        assertThat(AuthorizationLifecycle.clear(cleared, 1_000, "CZK", clock))
            .isEqualTo(PresentmentOutcome.Refused(PresentmentRefusal.NOT_HOLDING_FUNDS))
    }

    @Test
    fun `a reversal releases the remainder and keeps what already cleared`() {
        val partly = authorization(cleared = 4_000, status = AuthorizationStatus.PARTIALLY_CLEARED)

        val accepted = AuthorizationLifecycle.reverse(partly, clock) as PresentmentOutcome.Accepted

        assertThat(accepted.authorization.status).isEqualTo(AuthorizationStatus.REVERSED)
        // The money that cleared stays cleared: zeroing it here would un-post what the ledger has.
        assertThat(accepted.authorization.clearedAmountMinorUnits).isEqualTo(4_000)
        assertThat(accepted.authorization.heldAmountMinorUnits).isZero()
        assertThat(accepted.authorization.effectiveSpendMinorUnits).isZero()
    }

    @Test
    fun `expiry before the expiry instant is refused`() {
        // The whole point of the guard: an early expiry releases funds an acquirer may still present
        // against, and a scheduler is exactly where an off-by-one lands silently.
        assertThat(AuthorizationLifecycle.expire(authorization(), clock))
            .isEqualTo(PresentmentOutcome.Refused(PresentmentRefusal.NOT_YET_EXPIRED))
    }

    @Test
    fun `expiry at or after the expiry instant releases the hold`() {
        val subject = authorization()
        val atExpiry = Clock.fixed(subject.expiresAt, ZoneOffset.UTC)

        val accepted = AuthorizationLifecycle.expire(subject, atExpiry) as PresentmentOutcome.Accepted

        assertThat(accepted.authorization.status).isEqualTo(AuthorizationStatus.EXPIRED)
        assertThat(accepted.authorization.effectiveSpendMinorUnits).isZero()
    }

    @Test
    fun `a declined authorisation holds nothing and counts as nothing`() {
        val declined = authorization(status = AuthorizationStatus.DECLINED)

        assertThat(declined.heldAmountMinorUnits).isZero()
        assertThat(declined.effectiveSpendMinorUnits).isZero()
        assertThat(AuthorizationLifecycle.reverse(declined, clock))
            .isEqualTo(PresentmentOutcome.Refused(PresentmentRefusal.NOT_HOLDING_FUNDS))
    }
}
