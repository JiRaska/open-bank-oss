// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.application.port.out

import com.openbank.cardissuance.domain.model.Card
import com.openbank.libs.persistence.outbox.OutboxMessage
import java.time.LocalDate
import java.util.UUID

/**
 * Outbound persistence port for the card aggregate.
 *
 * [save] persists the card **and** its domain event ([event]) in a single transaction (the
 * transactional-outbox pattern, ADR-0050): either both the card row and the outbox row commit, or
 * neither does. The dispatcher then drains the outbox to Kafka asynchronously, so a crash between
 * the DB commit and the Kafka publish can never lose — or double-emit — an event.
 */
@Suppress("TooManyFunctions") // one query per persistence concern of the card aggregate (hexagonal)
interface CardRepository {

    suspend fun save(card: Card, event: OutboxMessage): Card

    suspend fun findById(id: UUID): Card?

    suspend fun findByIdempotencyKey(key: String): Card?

    suspend fun listAllCards(): List<Card>

    suspend fun findByAccountId(accountId: UUID): List<Card>

    suspend fun findByPartyId(partyId: UUID): List<Card>

    /**
     * Cards issued under a delegation grant (ADR-0249 D1). Drives D2's "revocation must bite":
     * when the grant ends, every card it authorised is blocked, so this must return the card
     * whatever state it is in — the caller decides which states are still worth acting on.
     */
    suspend fun findByDelegationGrantId(grantId: UUID): List<Card>

    suspend fun anonymizeByPartyId(partyId: UUID)

    /** Anonymises PII (cardholderName, embossedName) for cards whose expiry_date is before [cutoff]. Returns the count updated. */
    suspend fun anonymizeExpiredCardPii(cutoff: LocalDate): Int

    /**
     * Cards with **no stored PAN credential** that are still worth one — i.e. `pan_encrypted IS NULL`
     * and the card is not in a terminal status. Feeds the vault backfill (ADR-0194 follow-up); a
     * CANCELLED or EXPIRED card is dead and needs no credential minted for it.
     */
    suspend fun findWithoutPanCredential(): List<Card>

    /**
     * Writes the encrypted PAN/CVV onto a card **only if it has none** (`pan_encrypted IS NULL`).
     * Returns true when the row was written. The NULL guard is what makes the backfill idempotent
     * and safe to race: a second boot, or a concurrent replica, updates nothing and cannot renumber
     * a card that already has a credential.
     */
    suspend fun storePanCredentialIfAbsent(cardId: UUID, panEncrypted: String, cvvEncrypted: String): Boolean
}
