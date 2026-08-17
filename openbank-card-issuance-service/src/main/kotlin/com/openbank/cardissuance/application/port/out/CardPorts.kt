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

    /**
     * Every card carrying a stored PAN credential (`pan_encrypted IS NOT NULL`), including
     * terminal ones. Feeds `CardPanKeyReencrypt` (ADR-0262 follow-up): a KEK rotation must retire
     * the OLD DEK everywhere, and a terminal card's row still needs migrating even though the
     * vault backfill above would never mint it a NEW credential — unlike that backfill, this is
     * re-encrypting an EXISTING number, not choosing a new one, so a dead card's row is not exempt.
     */
    suspend fun findWithPanCredential(): List<Card>

    /**
     * Compare-and-swap: writes the re-encrypted PAN/CVV only if the row still holds exactly
     * [expectedPanEncrypted] (the ciphertext the caller successfully decrypted under the OLD key a
     * moment earlier). Returns true when the row was written. The guard is what makes a re-encrypt
     * pass safe to race against a concurrent card-detail read/write elsewhere, or a second replica
     * running the same pass: if the row changed underneath us, this update is a no-op rather than
     * clobbering fresher data with a re-encryption of a value that is no longer current.
     */
    suspend fun updatePanCredentialIfMatches(
        cardId: UUID,
        expectedPanEncrypted: String,
        newPanEncrypted: String,
        newCvvEncrypted: String,
    ): Boolean
}
