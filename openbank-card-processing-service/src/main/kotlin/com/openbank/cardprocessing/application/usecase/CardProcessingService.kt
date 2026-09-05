// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.cardprocessing.application.port.`in`.AuthorizationCommand
import com.openbank.cardprocessing.application.port.`in`.CardProcessingUseCase
import com.openbank.cardprocessing.application.port.`in`.PresentmentCommand
import com.openbank.cardprocessing.application.port.out.CardAuthorizationRepository
import com.openbank.cardprocessing.application.port.out.CardIssuancePolicyPort
import com.openbank.cardprocessing.application.port.out.CardLookupPort
import com.openbank.cardprocessing.application.port.out.CardOwnership
import com.openbank.cardprocessing.application.port.out.CardProcessingMetricsPort
import com.openbank.cardprocessing.application.port.out.FraudScoringPort
import com.openbank.cardprocessing.application.port.out.IssuerDecision
import com.openbank.cardprocessing.application.port.out.LedgerPostingPort
import com.openbank.cardprocessing.application.port.out.PostingOutcome
import com.openbank.cardprocessing.domain.event.CardAuthorised
import com.openbank.cardprocessing.domain.event.CardCleared
import com.openbank.cardprocessing.domain.event.CardDeclined
import com.openbank.cardprocessing.domain.event.CardHoldReleased
import com.openbank.cardprocessing.domain.event.CardProcessingEvent
import com.openbank.cardprocessing.domain.model.AuthorizationStatus
import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.cardprocessing.domain.model.CountedSpend
import com.openbank.cardprocessing.domain.model.PresentmentOutcome
import com.openbank.cardprocessing.domain.model.PresentmentRefusal
import com.openbank.cardprocessing.domain.model.SpendWindow
import com.openbank.cardprocessing.domain.policy.AuthorizationLifecycle
import com.openbank.libs.persistence.outbox.OutboxMessage
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The card money path: decide, hold, clear, release.
 *
 * ## What this service is for
 *
 * Card-issuance has owned a complete authorisation decision since ADR-0194 D3 and **nothing had
 * ever called it** — measured on `origin/main` 2026-09-05, `POST /cards/{id}/authorizations` had no
 * caller anywhere in the fleet, and `openbank.cards.events` reached campaign-service and
 * audit-service but never the ledger. So a customer's card controls were enforced by a function
 * with no input, and card spend could not reach the books at all. This class is the caller, and the
 * path to the ledger.
 *
 * ## Where each decision lives
 *
 * - **Whether to approve** is card-issuance's, via [CardIssuancePolicyPort]. Not re-implemented
 *   here: two copies of a control diverge and the customer sees the wrong one.
 * - **How much has been spent** is this service's, via [CardAuthorizationRepository.countSpend].
 *   The issuer endpoint takes the spend figures as arguments, so before this service they were
 *   whatever the caller said — which is not a limit.
 * - **Whether money moves** is the ledger's, via [LedgerPostingPort], and only on a clearing. An
 *   authorisation holds; it does not post.
 */
@ApplicationScoped
@Suppress(
    // One collaborator per hexagonal port (ADR-0002). Collapsing them into a facade would hide
    // which ports this use case depends on, which is the thing the layering exists to show.
    "LongParameterList",
    // The count is inflated by small, named private steps (decide/ask/record/eventFor). Splitting
    // the class instead would put one flow across two files for a metric, and a reader following
    // "authorise -> hold -> clear -> release" would have to follow it across both.
    "TooManyFunctions",
)
class CardProcessingService(
    private val repository: CardAuthorizationRepository,
    private val cards: CardLookupPort,
    private val issuerPolicy: CardIssuancePolicyPort,
    private val ledger: LedgerPostingPort,
    private val fraud: FraudScoringPort,
    private val metrics: CardProcessingMetricsPort,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.card-processing.hold-expiry-days", defaultValue = "7")
    private val holdExpiryDays: Long,
) : CardProcessingUseCase {

    private val log = Logger.getLogger(CardProcessingService::class.java)

    override suspend fun authorize(command: AuthorizationCommand): CardAuthorization {
        repository.findByIdempotencyKey(command.idempotencyKey)?.let { return it }

        val ownership = cards.lookup(command.cardId) ?: throw CardNotFoundException(command.cardId)
        require(command.amountMinorUnits > 0) { "amount must be positive" }
        require(command.currencyCode.equals(ownership.currencyCode, ignoreCase = true)) {
            "authorisation currency ${command.currencyCode} does not match the card's ${ownership.currencyCode}"
        }

        val decision = decide(command)
        val authorization = record(command, ownership, decision)
        val saved = repository.save(
            authorization,
            outboxMessage(authorization.id, eventTypeFor(authorization), eventFor(authorization)),
            command.idempotencyKey,
        )
        metrics.authorizationDecided(decision.approved, authorization.declineReason)
        // Shadow only (ADR-0084): the score is recorded and changes nothing. Its failure must not
        // fail an authorisation that has already been decided and committed.
        metrics.fraudScoring(fraud.score(saved).outcome)
        return saved
    }

    /**
     * Measures the spend and asks card-issuance.
     *
     * The two-pass shape is deliberate and is about the CATEGORY. Asking for the counted spend
     * needs one, and the only honest value before the decision is the unmapped bucket — the
     * category is card-issuance's judgement of the MCC, not ours to guess. So the first pass counts
     * against `UNMAPPED`, the decision returns the real category, and a second pass counts against
     * it. Where the two counts agree (the common case, and always when the issuer judged it
     * unmapped) the second decision call is skipped, so an ordinary authorisation costs one query
     * and one call.
     */
    private suspend fun decide(command: AuthorizationCommand): IssuerDecision {
        // Resolved once, then used for every counter — a daily and a monthly figure taken a few
        // milliseconds apart can straddle a day boundary and describe two different worlds.
        val window = SpendWindow.resolve(clock)
        val provisional = repository.countSpend(command.cardId, window, UNMAPPED_CATEGORY)
        val first = ask(command, provisional)
        if (first.category == UNMAPPED_CATEGORY) return first

        val counted = repository.countSpend(command.cardId, window, first.category)
        return if (counted == provisional) first else ask(command, counted)
    }

    private suspend fun ask(command: AuthorizationCommand, counted: CountedSpend): IssuerDecision =
        issuerPolicy.decide(
            cardId = command.cardId,
            amountMinorUnits = command.amountMinorUnits,
            channel = command.channel,
            mcc = command.mcc,
            countryCode = command.merchantCountry,
            counted = counted,
        )

    private fun record(
        command: AuthorizationCommand,
        ownership: CardOwnership,
        decision: IssuerDecision,
    ): CardAuthorization {
        val now = Instant.now(clock)
        return CardAuthorization(
            id = UUID.randomUUID(),
            cardId = command.cardId,
            accountId = ownership.accountId,
            partyId = ownership.partyId,
            amountMinorUnits = command.amountMinorUnits,
            currencyCode = command.currencyCode.uppercase(),
            channel = command.channel,
            mcc = command.mcc,
            merchantName = command.merchantName,
            merchantCountry = command.merchantCountry,
            status = if (decision.approved) AuthorizationStatus.APPROVED else AuthorizationStatus.DECLINED,
            category = decision.category,
            declineReason = decision.reason.takeUnless { decision.approved },
            clearedAmountMinorUnits = 0,
            networkReference = command.networkReference,
            authorizedAt = now,
            expiresAt = now.plus(Duration.ofDays(holdExpiryDays)),
            updatedAt = now,
        )
    }

    private fun eventTypeFor(a: CardAuthorization): String =
        if (a.status == AuthorizationStatus.APPROVED) CardAuthorised.EVENT_TYPE else CardDeclined.EVENT_TYPE

    private fun eventFor(a: CardAuthorization): CardProcessingEvent =
        if (a.status == AuthorizationStatus.APPROVED) {
            CardAuthorised(
                authorizationId = a.id,
                cardId = a.cardId,
                accountId = a.accountId,
                partyId = a.partyId,
                amountMinorUnits = a.amountMinorUnits,
                currencyCode = a.currencyCode,
                channel = a.channel,
                mcc = a.mcc,
                category = a.category,
                merchantName = a.merchantName,
                merchantCountry = a.merchantCountry,
                expiresAt = a.expiresAt,
                occurredAt = a.authorizedAt,
            )
        } else {
            CardDeclined(
                authorizationId = a.id,
                cardId = a.cardId,
                accountId = a.accountId,
                partyId = a.partyId,
                amountMinorUnits = a.amountMinorUnits,
                currencyCode = a.currencyCode,
                channel = a.channel,
                reason = a.declineReason ?: DECLINE_REASON_UNSTATED,
                category = a.category,
                occurredAt = a.authorizedAt,
            )
        }

    override suspend fun clear(command: PresentmentCommand): PresentmentOutcome {
        val existing = repository.findById(command.authorizationId)
            ?: return PresentmentOutcome.Refused(PresentmentRefusal.NOT_HOLDING_FUNDS)
        val outcome = AuthorizationLifecycle.clear(existing, command.amountMinorUnits, command.currencyCode, clock)
        if (outcome !is PresentmentOutcome.Accepted) return outcome

        val cleared = outcome.authorization
        val fullyCleared = cleared.status == AuthorizationStatus.CLEARED
        val event = CardCleared(
            authorizationId = cleared.id,
            cardId = cleared.cardId,
            accountId = cleared.accountId,
            clearedAmountMinorUnits = command.amountMinorUnits,
            cumulativeClearedMinorUnits = cleared.clearedAmountMinorUnits,
            currencyCode = cleared.currencyCode,
            fullyCleared = fullyCleared,
            category = cleared.category,
            occurredAt = Instant.now(clock),
        )
        val saved = repository.save(
            cleared,
            outboxMessage(cleared.id, CardCleared.EVENT_TYPE, event),
            command.idempotencyKey,
        )
        metrics.presentmentApplied(fullyCleared)

        // Deliberately AFTER the commit, and not rolled back on failure: the clearing is a fact the
        // acquirer has already asserted, and refusing to record it because the ledger is briefly
        // unreachable would lose it. A FAILED posting is a finding, not a silent success — which is
        // what the three-valued outcome is for.
        val posting = ledger.postClearedSpend(saved, command.amountMinorUnits, command.idempotencyKey)
        metrics.ledgerPosting(posting.outcome)
        if (posting.outcome == PostingOutcome.FAILED) {
            log.errorf(
                "ledger posting FAILED for authorization %s (%s) — the clearing is recorded, the books are not: %s",
                saved.id,
                command.idempotencyKey,
                posting.detail,
            )
        }
        return PresentmentOutcome.Accepted(saved)
    }

    override suspend fun reverse(authorizationId: UUID): PresentmentOutcome {
        val existing = repository.findById(authorizationId)
            ?: return PresentmentOutcome.Refused(PresentmentRefusal.NOT_HOLDING_FUNDS)
        return releaseHold(existing, RELEASE_KIND_REVERSAL) { AuthorizationLifecycle.reverse(it, clock) }
    }

    override suspend fun findById(id: UUID): CardAuthorization? = repository.findById(id)

    override suspend fun findByCard(cardId: UUID, limit: Int): List<CardAuthorization> =
        repository.findByCardId(cardId, limit)

    override suspend fun releaseExpiredHolds(limit: Int): Int {
        val due = repository.findExpiredHolds(Instant.now(clock), limit)
        var released = 0
        for (authorization in due) {
            val outcome = releaseHold(authorization, RELEASE_KIND_EXPIRY) {
                AuthorizationLifecycle.expire(it, clock)
            }
            if (outcome is PresentmentOutcome.Accepted) released++
        }
        return released
    }

    private suspend fun releaseHold(
        authorization: CardAuthorization,
        kind: String,
        transition: (CardAuthorization) -> PresentmentOutcome,
    ): PresentmentOutcome {
        val releasedAmount = authorization.heldAmountMinorUnits
        val outcome = transition(authorization)
        if (outcome !is PresentmentOutcome.Accepted) return outcome
        val released = outcome.authorization
        val event = CardHoldReleased(
            authorizationId = released.id,
            cardId = released.cardId,
            accountId = released.accountId,
            releasedAmountMinorUnits = releasedAmount,
            currencyCode = released.currencyCode,
            releaseKind = kind,
            occurredAt = Instant.now(clock),
        )
        val saved = repository.save(
            released,
            outboxMessage(released.id, CardHoldReleased.EVENT_TYPE, event),
            "$kind:${released.id}",
        )
        metrics.holdReleased(kind)
        return PresentmentOutcome.Accepted(saved)
    }

    /**
     * `createdAt` is passed from the injected clock, never left to the default. The default is
     * `Instant.now()`, correct in production and wrong under a fixed test clock — and the dispatcher
     * claims rows in `created_at` order, so a row stamped from a different clock than its neighbours
     * sorts unpredictably (#3272 is the same field, one severity worse).
     */
    private fun outboxMessage(
        aggregateId: UUID,
        eventType: String,
        event: CardProcessingEvent,
    ): OutboxMessage = OutboxMessage(
        eventId = UUID.randomUUID(),
        aggregateId = aggregateId,
        eventType = eventType,
        payload = mapper.writeValueAsString(event),
        createdAt = Instant.now(clock),
    )

    companion object {
        const val RELEASE_KIND_REVERSAL = "REVERSAL"
        const val RELEASE_KIND_EXPIRY = "EXPIRY"

        /** Card-issuance's own name for an MCC it has no category for. */
        const val UNMAPPED_CATEGORY = "UNMAPPED"

        /**
         * A decline the issuer refused without naming a reason. It should not happen; recording it
         * as an explicit value beats writing `null` into a field the customer's screen renders.
         */
        const val DECLINE_REASON_UNSTATED = "UNSTATED"
    }
}

class CardNotFoundException(val cardId: UUID) : RuntimeException("card $cardId is not known to card-issuance")
