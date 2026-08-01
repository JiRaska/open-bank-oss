// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.application.usecase

import com.openbank.cardissuance.application.port.out.CardDelegationProjectionRepository
import com.openbank.cardissuance.application.port.out.CardRepository
import com.openbank.cardissuance.domain.model.CardDelegationIntent
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The card-side delegation guard (ADR-0232 D3): holder OR an ACTIVE in-window
 * delegation grant. Deliberately a separate bean from CardService — the guard is
 * the only code path that knows the projection exists, so the blast radius of a
 * delegation bug stays one class wide.
 */
@ApplicationScoped
class CardDelegationGuard(
    private val cardRepository: CardRepository,
    private val projectionRepository: CardDelegationProjectionRepository,
    private val clock: Clock,
) {

    suspend fun isAuthorized(cardId: UUID, partyId: UUID, intent: CardDelegationIntent): Boolean {
        val card = cardRepository.findById(cardId) ?: return false
        if (card.partyId == partyId) return true
        val now = OffsetDateTime.now(clock)
        return projectionRepository.findActiveByCardAndParty(cardId, partyId)
            .any { it.isActiveOn(now) && it.satisfies(intent) }
    }
}
