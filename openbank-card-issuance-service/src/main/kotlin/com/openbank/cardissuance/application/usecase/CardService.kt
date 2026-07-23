// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.cardissuance.application.port.`in`.*
import com.openbank.cardissuance.application.port.out.*
import com.openbank.cardissuance.domain.event.*
import com.openbank.cardissuance.domain.model.*
import com.openbank.libs.persistence.outbox.OutboxMessage
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@ApplicationScoped
class CardService(private val repo: CardRepository, private val mapper: ObjectMapper, private val clock: Clock) :
    CardUseCase {

    override suspend fun issueCard(cmd: IssueCardCommand): Card {
        repo.findByIdempotencyKey(cmd.idempotencyKey)?.let { return it }
        val now = Instant.now(clock)
        val card = Card(
            id = UUID.randomUUID(), idempotencyKey = cmd.idempotencyKey,
            partyId = cmd.partyId, accountId = cmd.accountId,
            productCode = cmd.productCode, cardType = cmd.cardType, network = cmd.network,
            maskedPan = "**** **** **** ${(1000..9999).random()}",
            cardholderName = cmd.cardholderName, embossedName = cmd.embossedName,
            expiryDate = LocalDate.now(clock).plusYears(4),
            status = CardStatus.PENDING,
            dailyLimitMinorUnits = cmd.dailyLimitMinorUnits,
            monthlyLimitMinorUnits = cmd.monthlyLimitMinorUnits,
            currency = cmd.currency, deliveryAddress = cmd.deliveryAddress,
            activatedAt = null, blockedAt = null, blockedReason = null,
            createdAt = now, updatedAt = now,
        )
        // ADR-0050: the card and its CardIssued event commit atomically via the outbox.
        return repo.save(
            card,
            outboxMessage(
                card.id,
                EVENT_CARD_ISSUED,
                CardIssued(
                    card.id,
                    card.partyId,
                    card.accountId,
                    card.cardType,
                    card.network,
                    card.maskedPan,
                    now,
                ),
            ),
        )
    }

    override suspend fun activateCard(cmd: CardStatusCommand): Card =
        changeStatus(cmd) { it.activate(Instant.now(clock)) }

    override suspend fun blockCard(cmd: CardStatusCommand): Card =
        changeStatus(cmd) { it.block(cmd.reason ?: "Blocked by operator", Instant.now(clock)) }

    override suspend fun suspendCard(cmd: CardStatusCommand): Card =
        changeStatus(cmd) { it.suspend(Instant.now(clock)) }

    override suspend fun resumeCard(cmd: CardStatusCommand): Card = changeStatus(cmd) { it.resume(Instant.now(clock)) }

    override suspend fun updateLimits(cmd: UpdateLimitsCommand): Card {
        val card = repo.findById(cmd.cardId) ?: error("Card not found: ${cmd.cardId}")
        val updated = card.withLimits(cmd.dailyLimitMinorUnits, cmd.monthlyLimitMinorUnits, Instant.now(clock))
        val event = CardLimitsChanged(
            updated.id,
            updated.dailyLimitMinorUnits,
            updated.monthlyLimitMinorUnits,
            cmd.changedBy,
            updated.updatedAt,
        )
        return repo.save(updated, outboxMessage(updated.id, EVENT_CARD_LIMITS_CHANGED, event))
    }

    override suspend fun updateControls(cmd: UpdateControlsCommand): Card {
        val card = repo.findById(cmd.cardId) ?: error("Card not found: ${cmd.cardId}")
        val updated = card.withControls(
            cmd.contactlessEnabled,
            cmd.onlineEnabled,
            cmd.atmEnabled,
            cmd.abroadEnabled,
            Instant.now(clock),
        )
        val event = CardControlsChanged(
            updated.id,
            updated.contactlessEnabled,
            updated.onlineEnabled,
            updated.atmEnabled,
            updated.abroadEnabled,
            cmd.changedBy,
            updated.updatedAt,
        )
        return repo.save(updated, outboxMessage(updated.id, EVENT_CARD_CONTROLS_CHANGED, event))
    }

    /**
     * Shared status-transition path: load the card, apply [transition], then persist the updated
     * aggregate and its CardStatusChanged event in one transaction (ADR-0050).
     */
    private suspend fun changeStatus(cmd: CardStatusCommand, transition: (Card) -> Card): Card {
        val card = repo.findById(cmd.cardId) ?: error("Card not found: ${cmd.cardId}")
        val updated = transition(card)
        val event = CardStatusChanged(
            updated.id,
            card.status,
            updated.status,
            cmd.reason,
            cmd.changedBy,
            updated.updatedAt,
        )
        return repo.save(updated, outboxMessage(updated.id, EVENT_CARD_STATUS_CHANGED, event))
    }

    override suspend fun getCard(id: UUID) = repo.findById(id)
    override suspend fun listAll() = repo.listAllCards()
    override suspend fun listByAccount(accountId: UUID) = repo.findByAccountId(accountId)
    override suspend fun listByParty(partyId: UUID) = repo.findByPartyId(partyId)

    private fun outboxMessage(aggregateId: UUID, eventType: String, event: CardEvent): OutboxMessage = OutboxMessage(
        eventId = UUID.randomUUID(),
        aggregateId = aggregateId,
        eventType = eventType,
        payload = mapper.writeValueAsString(event),
    )

    companion object {
        const val EVENT_CARD_ISSUED = "card.issued.v1"
        const val EVENT_CARD_STATUS_CHANGED = "card.status_changed.v1"
        const val EVENT_CARD_LIMITS_CHANGED = "card.limits_changed.v1"
        const val EVENT_CARD_CONTROLS_CHANGED = "card.controls_changed.v1"
    }
}
