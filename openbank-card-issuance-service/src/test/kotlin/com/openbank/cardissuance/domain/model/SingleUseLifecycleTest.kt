// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class SingleUseLifecycleTest {
    private val now = Instant.parse("2026-08-07T10:00:00Z")

    private fun card(
        type: CardType = CardType.SINGLE_USE,
        status: CardStatus = CardStatus.ACTIVE,
        expiresAt: Instant? = null,
    ) = Card(
        id = UUID.randomUUID(),
        idempotencyKey = "k",
        partyId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        productCode = "P",
        cardType = type,
        network = CardNetwork.VISA,
        maskedPan = "**** 1234",
        cardholderName = "J R",
        embossedName = "J R",
        expiryDate = LocalDate.of(2030, 1, 1),
        status = status,
        dailyLimitMinorUnits = 500_000,
        monthlyLimitMinorUnits = 5_000_000,
        currency = "CZK",
        deliveryAddress = null,
        activatedAt = null,
        blockedAt = null,
        blockedReason = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        expiresAt = expiresAt,
    )

    @Test
    fun `a used single-use card becomes CONSUMED with the reason that says so`() {
        val c = card().consume(now)
        assertThat(c.status).isEqualTo(CardStatus.CONSUMED)
        assertThat(c.closedReason).isEqualTo(CardClosedReason.SINGLE_USE_CONSUMED)
        assertThat(c.updatedAt).isEqualTo(now)
    }

    @Test
    fun `CONSUMED is terminal`() {
        // The whole point: a consumed card must never authorise again, and no transition may
        // resurrect it.
        assertThat(Card.TERMINAL_STATUSES).contains(CardStatus.CONSUMED)
        val consumed = card().consume(now)
        assertThatThrownBy { consumed.consume(now) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { consumed.cancel("x", now) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `only a single-use card can be consumed`() {
        // Marking an ordinary debit card CONSUMED would tell the customer a false story about why
        // it stopped working.
        for (t in CardType.entries.filter { it != CardType.SINGLE_USE }) {
            assertThatThrownBy { card(type = t).consume(now) }
                .describedAs("type %s", t)
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `only an active card can be consumed`() {
        for (s in CardStatus.entries.filter { it != CardStatus.ACTIVE }) {
            assertThatThrownBy { card(status = s).consume(now) }
                .describedAs("status %s", s)
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `an unused card that times out is EXPIRED, not CONSUMED`() {
        // Nothing was spent. The distinction is exactly what the customer is told, so it must not
        // collapse into one status.
        val c = card(expiresAt = now).expireUnused(now)
        assertThat(c.status).isEqualTo(CardStatus.EXPIRED)
        assertThat(c.closedReason).isEqualTo(CardClosedReason.VALIDITY_EXPIRED)
    }

    @Test
    fun `expiry applies to a card that was never activated`() {
        // A disposable card issued and never presented is the ordinary case for timing out.
        val c = card(status = CardStatus.PENDING, expiresAt = now).expireUnused(now)
        assertThat(c.status).isEqualTo(CardStatus.EXPIRED)
    }

    @Test
    fun `a dead card cannot be expired again`() {
        val consumed = card().consume(now)
        assertThatThrownBy { consumed.expireUnused(now) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a live card carries no closed reason`() {
        assertThat(card().closedReason).isNull()
    }

    @Test
    fun `expiresAt is only meaningful for single-use cards`() {
        // Nothing enforces this in the type system, so it is stated here: the column stays null for
        // every other type, and a non-null value on a debit card would be a bug in whatever set it.
        assertThat(card(type = CardType.DEBIT).expiresAt).isNull()
    }
}
