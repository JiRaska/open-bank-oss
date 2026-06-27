// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.application.port.out

import com.openbank.cardissuance.domain.model.Card
import com.openbank.libs.persistence.outbox.OutboxMessage
import java.util.UUID

/**
 * Outbound persistence port for the card aggregate.
 *
 * [save] persists the card **and** its domain event ([event]) in a single transaction (the
 * transactional-outbox pattern, ADR-0050): either both the card row and the outbox row commit, or
 * neither does. The dispatcher then drains the outbox to Kafka asynchronously, so a crash between
 * the DB commit and the Kafka publish can never lose — or double-emit — an event.
 */
interface CardRepository {

    suspend fun save(card: Card, event: OutboxMessage): Card

    suspend fun findById(id: UUID): Card?

    suspend fun findByIdempotencyKey(key: String): Card?

    suspend fun listAllCards(): List<Card>

    suspend fun findByAccountId(accountId: UUID): List<Card>

    suspend fun findByPartyId(partyId: UUID): List<Card>

    suspend fun anonymizeByPartyId(partyId: UUID)
}
