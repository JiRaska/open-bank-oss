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
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@ApplicationScoped
@Suppress("TooManyFunctions") // one use-case method per card operation (hexagonal)
class CardService(
    private val repo: CardRepository,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val cipher: CardSecretCipher,
    private val productCatalog: CardProductCatalogPort,
) : CardUseCase {

    override suspend fun issueCard(cmd: IssueCardCommand): Card {
        repo.findByIdempotencyKey(cmd.idempotencyKey)?.let { return it }
        enforceEntitlements(cmd)
        val now = Instant.now(clock)
        // Synthetic, Luhn-valid test PAN — see SyntheticPanGenerator. maskedPan is DERIVED from it,
        // so the displayed last-4 finally matches a number that exists (it used to be random).
        val credential = SyntheticPanGenerator.generate(cmd.network)
        val card = Card(
            id = UUID.randomUUID(), idempotencyKey = cmd.idempotencyKey,
            partyId = cmd.partyId, accountId = cmd.accountId,
            productCode = cmd.productCode, cardType = cmd.cardType, network = cmd.network,
            maskedPan = credential.maskedPan,
            cardholderName = cmd.cardholderName, embossedName = cmd.embossedName,
            expiryDate = LocalDate.now(clock).plusYears(EXPIRY_YEARS),
            status = CardStatus.PENDING,
            dailyLimitMinorUnits = cmd.dailyLimitMinorUnits,
            monthlyLimitMinorUnits = cmd.monthlyLimitMinorUnits,
            currency = cmd.currency, deliveryAddress = cmd.deliveryAddress,
            activatedAt = null, blockedAt = null, blockedReason = null,
            createdAt = now, updatedAt = now,
            panEncrypted = cipher.encrypt(credential.pan),
            cvvEncrypted = cipher.encrypt(credential.cvv),
        )
        // ADR-0050: the card and its CardIssued event commit atomically via the outbox. The event
        // carries the MASKED pan only — an outbox payload lands on Kafka and in the DB in the clear.
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

    /** Terminal close-out. Mirrors [blockCard] exactly, including the outbox emission (ADR-0050). */
    override suspend fun cancelCard(cmd: CardStatusCommand): Card =
        changeStatus(cmd) { it.cancel(cmd.reason, Instant.now(clock)) }

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
     * Decrypt and return a virtual card's synthetic PAN/CVV (#3).
     *
     * Three refusals, each with its own machine-readable code: a card with plastic (its PAN is
     * embossed — this service must not become a PAN oracle for it), a card that is no longer live,
     * and a card issued before the vault existed. The returned values are NEVER logged; only the
     * *access* is audited (who, which card, when).
     */
    // ThrowsCount: three refusals with three distinct machine-readable codes IS the contract here —
    // collapsing them into one exception would erase the reason the caller has to branch on.
    @Suppress("ThrowsCount")
    override suspend fun readSecureDetails(query: ReadSecureDetailsQuery): CardSecureDetails {
        val card = repo.findById(query.cardId) ?: throw NoSuchElementException("Card not found: ${query.cardId}")
        if (!card.isVirtualForm) {
            audit(query, RESULT_DENIED, card.cardType.name)
            throw SecureDetailsForbiddenException(
                CardErrorCode.CARD_SECURE_DETAILS_NOT_SUPPORTED,
                "Secure details are only available for VIRTUAL and SINGLE_USE cards, not ${card.cardType}",
            )
        }
        if (card.status !in SECURE_DETAILS_STATUSES) {
            audit(query, RESULT_DENIED, card.status.name)
            throw SecureDetailsForbiddenException(
                CardErrorCode.CARD_SECURE_DETAILS_CARD_NOT_LIVE,
                "Secure details are not available for a card in status ${card.status}",
            )
        }
        val pan = card.panEncrypted
        val cvv = card.cvvEncrypted
        if (pan == null || cvv == null) {
            audit(query, RESULT_DENIED, "NO_STORED_PAN")
            throw SecureDetailsNotStoredException(
                CardErrorCode.CARD_SECURE_DETAILS_NOT_STORED,
                "No stored PAN for card ${card.id} — it was issued before the synthetic-PAN vault",
            )
        }
        audit(query, RESULT_SUCCESS, card.cardType.name)
        return CardSecureDetails(
            pan = cipher.decrypt(pan),
            cvv = cipher.decrypt(cvv),
            expiryDate = card.expiryDate,
            cardholderName = card.cardholderName,
            network = card.network,
        )
    }

    override suspend fun getEntitlements(partyId: UUID, productCode: String): CardEntitlements {
        val issued = countLiveCards(partyId, productCode)
        return when (val lookup = productCatalog.findCardConfig(productCode)) {
            is CardConfigLookup.Found -> CardEntitlements(
                productCode = productCode,
                maxCards = lookup.config.maxCards,
                issued = issued,
                remaining = (lookup.config.maxCards - issued).coerceAtLeast(0),
                virtualCardAllowed = lookup.config.virtualCardAllowed,
                // SINGLE_USE is a virtual card with a one-purchase intent, so it rides the same
                // product switch — the catalog has no separate flag for it.
                singleUseAllowed = lookup.config.virtualCardAllowed,
                networks = lookup.config.networks,
                tiers = lookup.config.tiers,
                monthlyFeePerCard = lookup.config.monthlyFeePerCard,
                enabled = lookup.config.enabled,
                source = EntitlementSource.CATALOG,
            )
            // Permissive default — the catalog is unreachable or does not know this code. maxCards
            // and remaining are UNLIMITED (-1), never 0: "unknown cap", not "nothing left".
            CardConfigLookup.Unavailable -> CardEntitlements(
                productCode = productCode,
                maxCards = CardEntitlements.UNLIMITED,
                issued = issued,
                remaining = CardEntitlements.UNLIMITED,
                virtualCardAllowed = true,
                singleUseAllowed = true,
                networks = CardNetwork.entries,
                tiers = emptyList(),
                monthlyFeePerCard = 0.0,
                enabled = true,
                source = EntitlementSource.FALLBACK,
            )
        }
    }

    /**
     * Product-catalog entitlement gate (#4). Every rejection carries its own [CardErrorCode] so the
     * caller can tell "this product has no cards" from "you already have three".
     *
     * A [CardConfigLookup.Unavailable] lookup — unknown product code, catalog down, catalog slow —
     * skips the gate entirely and lets the issue through; the adapter has already logged a warning
     * naming the code and the failure. See [CardProductCatalogPort] for the full rationale.
     */
    @Suppress("ThrowsCount") // one distinct CardErrorCode per product rule — see the KDoc
    private suspend fun enforceEntitlements(cmd: IssueCardCommand) {
        val config = (productCatalog.findCardConfig(cmd.productCode) as? CardConfigLookup.Found)?.config ?: return

        if (!config.enabled) {
            throw CardEntitlementException(
                CardErrorCode.CARD_PRODUCT_DISABLED,
                "Product ${cmd.productCode} does not carry cards",
            )
        }
        if (cmd.cardType in Card.VIRTUAL_FORM_TYPES && !config.virtualCardAllowed) {
            throw CardEntitlementException(
                CardErrorCode.CARD_VIRTUAL_NOT_ALLOWED,
                "Product ${cmd.productCode} does not allow ${cmd.cardType} cards",
            )
        }
        if (cmd.network !in config.networks) {
            throw CardEntitlementException(
                CardErrorCode.CARD_NETWORK_NOT_ALLOWED,
                "Product ${cmd.productCode} does not allow network ${cmd.network}",
            )
        }
        val live = countLiveCards(cmd.partyId, cmd.productCode)
        if (live >= config.maxCards) {
            throw CardEntitlementException(
                CardErrorCode.CARD_QUOTA_EXCEEDED,
                "Party ${cmd.partyId} already holds $live of ${config.maxCards} cards on ${cmd.productCode}",
            )
        }
    }

    /**
     * Cards of [partyId] on [productCode] that consume quota. Only PENDING/ACTIVE/SUSPENDED count —
     * a BLOCKED/CANCELLED/EXPIRED card must not hold a slot the customer can never use again.
     */
    private suspend fun countLiveCards(partyId: UUID, productCode: String): Int =
        repo.findByPartyId(partyId).count { it.productCode == productCode && it.status in Card.LIVE_STATUSES }

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

    /**
     * Audit trail for a secure-details *access*. This module has no audit publisher wired, so the
     * trail is one structured line on a dedicated category — cardId, operator, outcome. **The PAN
     * and CVV are never arguments here, or anywhere else that logs.** Upgrading this to libs'
     * `AuditEventPublisher` is a drop-in follow-up if card-issuance ever needs a durable trail.
     */
    private fun audit(query: ReadSecureDetailsQuery, result: String, detail: String) {
        auditLog.infof(
            "card secure-details access cardId=%s operator=%s result=%s detail=%s at=%s",
            query.cardId,
            query.requestedBy,
            result,
            detail,
            Instant.now(clock),
        )
    }

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

        private const val EXPIRY_YEARS = 4L
        private const val RESULT_SUCCESS = "SUCCESS"
        private const val RESULT_DENIED = "DENIED"

        /** A card must still be usable for its PAN to be worth serving. */
        private val SECURE_DETAILS_STATUSES = setOf(CardStatus.PENDING, CardStatus.ACTIVE, CardStatus.SUSPENDED)

        private val auditLog: Logger = Logger.getLogger("openbank.audit.cards")
    }
}
