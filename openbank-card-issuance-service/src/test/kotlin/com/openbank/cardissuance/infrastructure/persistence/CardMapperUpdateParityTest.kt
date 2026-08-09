// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.persistence

import com.openbank.cardissuance.domain.model.Card
import com.openbank.cardissuance.domain.model.CardClosedReason
import com.openbank.cardissuance.domain.model.CardNetwork
import com.openbank.cardissuance.domain.model.CardStatus
import com.openbank.cardissuance.domain.model.CardType
import com.openbank.cardissuance.infrastructure.persistence.entity.CardEntity
import com.openbank.cardissuance.infrastructure.persistence.mapper.applyFrom
import com.openbank.cardissuance.infrastructure.persistence.mapper.toEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.memberProperties

/**
 * `CardRepositoryImpl.save` has two halves that write the same table: `toEntity()` on the INSERT
 * branch and `applyFrom()` on the UPDATE branch. A column set by only one of them is invisible —
 * the value is written at issue and then silently dropped by every later transition, or never
 * written at all. That shipped once already: `expiresAt` and `closedReason` were added to
 * `toEntity()` and not to `applyFrom()`, so `card.consume(now)` would have persisted
 * `status = CONSUMED` with `closed_reason` still NULL, defeating the whole point of distinguishing
 * CONSUMED from CANCELLED.
 *
 * Asserting those two fields by name would only close the instance. This compares the two paths
 * over EVERY mutable property by reflection, so a column added to one path and not the other fails
 * here whether or not anyone remembers this test exists. The exclusion list below is the deliberate
 * part: those fields are set once at issue and must not move on an update, so adding a column
 * forces an explicit decision about which side of the line it falls on.
 */
class CardMapperUpdateParityTest {
    /**
     * Set at issue and immutable thereafter — `applyFrom` deliberately leaves them alone.
     * `panEncrypted`/`cvvEncrypted` are vault material rotated by their own path, not by a
     * lifecycle transition.
     */
    private val notUpdatable = setOf(
        "id", "idempotencyKey", "partyId", "accountId", "productCode",
        "cardType", "network", "createdAt", "panEncrypted", "cvvEncrypted",
    )

    private fun card(
        status: CardStatus = CardStatus.CONSUMED,
        closedReason: CardClosedReason? = CardClosedReason.SINGLE_USE_CONSUMED,
        expiresAt: Instant? = Instant.parse("2026-04-05T06:07:08Z"),
        limit: Long = 123_456,
    ) = Card(
        id = UUID.randomUUID(),
        idempotencyKey = "k",
        partyId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        productCode = "P",
        cardType = CardType.SINGLE_USE,
        network = CardNetwork.VISA,
        maskedPan = "**** 4321",
        cardholderName = "J R",
        embossedName = "J R",
        expiryDate = LocalDate.of(2031, 3, 1),
        status = status,
        dailyLimitMinorUnits = limit,
        monthlyLimitMinorUnits = 1_234_567,
        currency = "EUR",
        deliveryAddress = "Somewhere 1",
        activatedAt = Instant.parse("2026-01-02T03:04:05Z"),
        blockedAt = Instant.parse("2026-02-03T04:05:06Z"),
        blockedReason = "LOST",
        expiresAt = expiresAt,
        closedReason = closedReason,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-05-06T07:08:09Z"),
        contactlessEnabled = false,
        onlineEnabled = false,
        atmEnabled = false,
        abroadEnabled = false,
        panEncrypted = "pan",
        cvvEncrypted = "cvv",
    )

    @Test
    fun `every updatable column written by toEntity is also written by applyFrom`() {
        val card = card()
        val inserted = card.toEntity()

        // A row that disagrees with `card` on every updatable column, so a field the copier forgets
        // keeps its stale value instead of coincidentally matching.
        val updated = card(
            status = CardStatus.ACTIVE,
            closedReason = null,
            expiresAt = Instant.parse("2025-11-11T11:11:11Z"),
            limit = 999,
        ).toEntity().also { it.id = card.id }
        updated.applyFrom(card)

        val drifted = CardEntity::class.memberProperties
            .filterIsInstance<KMutableProperty1<CardEntity, *>>()
            .filterNot { it.name in notUpdatable }
            .filter { it.get(updated) != it.get(inserted) }
            .map { it.name }

        assertThat(drifted)
            .withFailMessage(
                "applyFrom() does not write %s, but toEntity() does. A card issued with those " +
                    "columns keeps the issue-time value through every later transition. Either " +
                    "copy them in applyFrom(), or add them to notUpdatable with a reason.",
                drifted,
            )
            .isEmpty()
    }
}
