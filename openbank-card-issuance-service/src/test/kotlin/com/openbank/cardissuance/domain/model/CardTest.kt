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

class CardTest {
    @Test fun `activate changes pending card to active`() {
        val now = Instant.parse("2026-01-01T10:15:30Z")
        val card = card(status = CardStatus.PENDING)

        val updated = card.activate(now)

        assertThat(updated.status).isEqualTo(CardStatus.ACTIVE)
        assertThat(updated.activatedAt).isEqualTo(now)
        assertThat(updated.updatedAt).isEqualTo(now)
    }

    @Test fun `activate rejects non pending card`() {
        val card = card(status = CardStatus.ACTIVE)

        assertThatThrownBy { card.activate(Instant.parse("2026-01-01T10:15:30Z")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Only PENDING cards can be activated")
    }

    @Test fun `block changes active card to blocked`() {
        val now = Instant.parse("2026-01-01T10:15:30Z")
        val card = card(status = CardStatus.ACTIVE)

        val updated = card.block("Fraud suspected", now)

        assertThat(updated.status).isEqualTo(CardStatus.BLOCKED)
        assertThat(updated.blockedAt).isEqualTo(now)
        assertThat(updated.blockedReason).isEqualTo("Fraud suspected")
        assertThat(updated.updatedAt).isEqualTo(now)
    }

    @Test fun `block rejects blank reason`() {
        val card = card(status = CardStatus.ACTIVE)

        assertThatThrownBy { card.block("   ", Instant.parse("2026-01-01T10:15:30Z")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Block reason required")
    }

    @Test fun `block rejects inactive card`() {
        val card = card(status = CardStatus.PENDING)

        assertThatThrownBy { card.block("Fraud suspected", Instant.parse("2026-01-01T10:15:30Z")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Cannot block card in status PENDING")
    }

    @Test fun `suspend changes active card to suspended`() {
        val now = Instant.parse("2026-01-01T10:15:30Z")
        val card = card(status = CardStatus.ACTIVE)

        val updated = card.suspend(now)

        assertThat(updated.status).isEqualTo(CardStatus.SUSPENDED)
        assertThat(updated.updatedAt).isEqualTo(now)
    }

    @Test fun `suspend rejects non active card`() {
        val card = card(status = CardStatus.PENDING)

        assertThatThrownBy { card.suspend(Instant.parse("2026-01-01T10:15:30Z")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Only ACTIVE cards can be suspended")
    }

    @Test fun `resume changes suspended card to active`() {
        val now = Instant.parse("2026-01-01T10:15:30Z")
        val card = card(status = CardStatus.SUSPENDED)

        val updated = card.resume(now)

        assertThat(updated.status).isEqualTo(CardStatus.ACTIVE)
        assertThat(updated.updatedAt).isEqualTo(now)
    }

    @Test fun `resume rejects non suspended card`() {
        val card = card(status = CardStatus.ACTIVE)

        assertThatThrownBy { card.resume(Instant.parse("2026-01-01T10:15:30Z")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Only SUSPENDED cards can be resumed")
    }

    @Test fun `withControls flips all four channel flags and stamps updatedAt`() {
        val now = Instant.parse("2026-02-02T09:00:00Z")
        val card = card(status = CardStatus.ACTIVE)

        val updated = card.withControls(contactless = false, online = true, atm = false, abroad = true, now = now)

        assertThat(updated.contactlessEnabled).isFalse()
        assertThat(updated.onlineEnabled).isTrue()
        assertThat(updated.atmEnabled).isFalse()
        assertThat(updated.abroadEnabled).isTrue()
        assertThat(updated.updatedAt).isEqualTo(now)
    }

    @Test fun `withControls is allowed on a suspended card`() {
        val updated = card(status = CardStatus.SUSPENDED)
            .withControls(
                contactless = true,
                online = false,
                atm = true,
                abroad = false,
                now = Instant.parse("2026-01-01T10:15:30Z"),
            )
        assertThat(updated.onlineEnabled).isFalse()
    }

    @Test fun `withControls rejects a terminal card`() {
        assertThatThrownBy {
            card(
                status = CardStatus.BLOCKED,
            ).withControls(true, true, true, true, Instant.parse("2026-01-01T10:15:30Z"))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Cannot change controls")
    }

    // ── cancel (#2): the transition matrix, including CANCELLED-is-terminal ────────────

    @Test fun `cancel closes a card from every non-terminal status`() {
        val now = Instant.parse("2026-03-03T08:00:00Z")

        Card.CANCELLABLE_STATUSES.forEach { status ->
            val updated = card(status = status).cancel("Customer closed the card", now)

            assertThat(updated.status)
                .describedAs("cancel from $status")
                .isEqualTo(CardStatus.CANCELLED)
            assertThat(updated.blockedReason).isEqualTo("Customer closed the card")
            assertThat(updated.updatedAt).isEqualTo(now)
        }
    }

    @Test fun `cancel is allowed from BLOCKED - a lost card the customer then closes`() {
        val blocked = card(status = CardStatus.BLOCKED)

        assertThat(blocked.cancel(null, Instant.parse("2026-01-01T10:15:30Z")).status).isEqualTo(CardStatus.CANCELLED)
    }

    @Test fun `cancel without a reason keeps the original block reason`() {
        val lost = card(
            status = CardStatus.ACTIVE,
        ).block(
            "Reported lost",
            Instant.parse("2026-01-01T10:15:30Z"),
        ).cancel(null, Instant.parse("2026-01-01T10:15:30Z"))

        assertThat(lost.status).isEqualTo(CardStatus.CANCELLED)
        assertThat(lost.blockedReason).isEqualTo("Reported lost")
    }

    @Test fun `cancel is terminal - a cancelled card can never transition again`() {
        val cancelled = card(status = CardStatus.CANCELLED)

        assertThatThrownBy { cancelled.cancel("again", Instant.parse("2026-01-01T10:15:30Z")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Cannot cancel card in status CANCELLED")
        assertThatThrownBy {
            cancelled.activate(Instant.parse("2026-01-01T10:15:30Z"))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            cancelled.block("fraud", Instant.parse("2026-01-01T10:15:30Z"))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            cancelled.suspend(Instant.parse("2026-01-01T10:15:30Z"))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            cancelled.resume(Instant.parse("2026-01-01T10:15:30Z"))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            cancelled.withLimits(1, 2, Instant.parse("2026-01-01T10:15:30Z"))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { cancelled.withControls(true, true, true, true, Instant.parse("2026-01-01T10:15:30Z")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `cancel rejects an expired card`() {
        assertThatThrownBy {
            card(status = CardStatus.EXPIRED)
                .cancel("closing", Instant.parse("2026-01-01T10:15:30Z"))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Cannot cancel card in status EXPIRED")
    }

    @Test fun `a cancelled card never consumes product quota`() {
        assertThat(Card.LIVE_STATUSES).doesNotContain(CardStatus.CANCELLED, CardStatus.BLOCKED, CardStatus.EXPIRED)
        assertThat(Card.LIVE_STATUSES)
            .containsExactlyInAnyOrder(CardStatus.PENDING, CardStatus.ACTIVE, CardStatus.SUSPENDED)
    }

    @Test fun `only virtual form factors expose their PAN digitally`() {
        assertThat(card(status = CardStatus.ACTIVE).copy(cardType = CardType.VIRTUAL).isVirtualForm).isTrue()
        assertThat(card(status = CardStatus.ACTIVE).copy(cardType = CardType.SINGLE_USE).isVirtualForm).isTrue()
        assertThat(card(status = CardStatus.ACTIVE).copy(cardType = CardType.DEBIT).isVirtualForm).isFalse()
        assertThat(card(status = CardStatus.ACTIVE).copy(cardType = CardType.CREDIT).isVirtualForm).isFalse()
        assertThat(card(status = CardStatus.ACTIVE).copy(cardType = CardType.PREPAID).isVirtualForm).isFalse()
    }

    private fun card(status: CardStatus) = Card(
        id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        idempotencyKey = "idem-key",
        partyId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
        accountId = UUID.fromString("33333333-3333-3333-3333-333333333333"),
        productCode = "CARD-001",
        cardType = CardType.DEBIT,
        network = CardNetwork.VISA,
        maskedPan = "**** **** **** 1234",
        cardholderName = "Jane Doe",
        embossedName = "JANE DOE",
        expiryDate = LocalDate.of(2030, 12, 31),
        status = status,
        dailyLimitMinorUnits = 100_00,
        monthlyLimitMinorUnits = 1_000_00,
        currency = "EUR",
        deliveryAddress = "123 Main St",
        activatedAt = null,
        blockedAt = null,
        blockedReason = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )
}
