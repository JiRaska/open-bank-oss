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
