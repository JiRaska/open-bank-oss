// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

@file:Suppress("ktlint:standard:filename")

package com.openbank.cardprocessing.application.port.out

import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.cardprocessing.domain.model.CountedSpend
import com.openbank.cardprocessing.domain.model.PresentmentChannel
import com.openbank.cardprocessing.domain.model.SpendWindow
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository
import io.smallrye.mutiny.Uni
import java.time.Instant
import java.util.UUID

/** Persistence for the authorisation aggregate. */
interface CardAuthorizationRepository {
    /**
     * Writes the authorisation **and** its event in one transaction (transactional outbox,
     * ADR-0050): either both commit or neither does.
     *
     * [idempotencyKey] is carried on the ROW, not on the aggregate: the domain has no opinion about
     * acquirer retries, but the database's UNIQUE index on it is what makes a repeated
     * authorisation request impossible to turn into a second hold. Written once on insert; an
     * update never rewrites it.
     */
    suspend fun save(
        authorization: CardAuthorization,
        event: OutboxMessage,
        idempotencyKey: String,
    ): CardAuthorization

    suspend fun findById(id: UUID): CardAuthorization?

    /** The acquirer's own reference, which is how a reversal arrives when it carries no id of ours. */
    suspend fun findByNetworkReference(networkReference: String): CardAuthorization?

    suspend fun findByIdempotencyKey(key: String): CardAuthorization?

    suspend fun findByCardId(cardId: UUID, limit: Int): List<CardAuthorization>

    /**
     * Spend already counted against the card inside [window].
     *
     * Computed in the database over the authorisation rows themselves, not from a running-total
     * column: a stored counter is a second source of truth, and when it drifts both numbers look
     * plausible. The cost is one aggregate query per authorisation, which is the right trade for a
     * control that decides whether money moves.
     */
    suspend fun countSpend(cardId: UUID, window: SpendWindow, category: String): CountedSpend

    /** Holds past their expiry instant, oldest first. Drives the release sweep. */
    suspend fun findExpiredHolds(now: Instant, limit: Int): List<CardAuthorization>
}

/** Outbox port: the libs [OutboxRepository] plus an in-transaction write. */
interface CardProcessingOutboxRepository : OutboxRepository {
    fun persistInTransaction(message: OutboxMessage): Uni<Void>

    /** Count of rows parked in terminal `DEAD` (ADR-0050 N5); backs the dead-letter gauge (#4005). */
    suspend fun countDead(): Long
}

/** What card-issuance answered. [category] is its judgement of the MCC and is kept, never re-derived. */
data class IssuerDecision(val approved: Boolean, val reason: String?, val category: String)

/**
 * The authorisation decision itself, which belongs to card-issuance (ADR-0194 D3, ADR-0283 D2).
 *
 * Card-processing measures the spend and moves the money; it does **not** re-implement the
 * decision. Two copies of a control diverge, and the copy the customer's app shows would be the one
 * that is wrong.
 */
interface CardIssuancePolicyPort {
    suspend fun decide(
        cardId: UUID,
        amountMinorUnits: Long,
        channel: PresentmentChannel,
        mcc: String?,
        countryCode: String?,
        counted: CountedSpend,
    ): IssuerDecision
}

/** Card facts card-processing needs and does not own: which account and party the card belongs to. */
data class CardOwnership(val accountId: UUID, val partyId: UUID, val currencyCode: String)

interface CardLookupPort {
    suspend fun lookup(cardId: UUID): CardOwnership?
}

/**
 * Where a cleared presentment becomes money in the books.
 *
 * The outcome is an **enum, never a boolean**. A disabled or unbound adapter returning
 * `success = true` is how the notification fan-out counted undelivered pushes as delivered
 * (ADR-0252 phase 0, #4348): a skipped no-op and a real success sharing one signal cannot be told
 * apart afterwards, by anyone, at any effort.
 */
enum class PostingOutcome { POSTED, SKIPPED_DISABLED, FAILED }

data class PostingResult(val outcome: PostingOutcome, val transactionId: UUID?, val detail: String?)

interface LedgerPostingPort {
    suspend fun postClearedSpend(
        authorization: CardAuthorization,
        clearedAmountMinorUnits: Long,
        idempotencyKey: String,
    ): PostingResult
}

/** Same rule as [PostingOutcome]: a shadow score that did not run must not read as a clean score. */
enum class FraudScoringOutcome { SCORED, SKIPPED_DISABLED, FAILED }

data class FraudScore(val outcome: FraudScoringOutcome, val score: Double?, val decision: String?)

/**
 * Fraud scoring, in **shadow** — the verdict changes no outcome here, exactly as on the four wired
 * payment rails (ADR-0084; the domestic-payment enforcement gate was merged and then deleted,
 * #4403). Wiring it as shadow now means the model sees card traffic from the first authorisation;
 * promoting it to enforcing is a separate, deliberate decision with its own ADR.
 */
interface FraudScoringPort {
    suspend fun score(authorization: CardAuthorization): FraudScore
}

/** Metrics port, so the use case never touches a MeterRegistry (hexagonal, ADR-0002). */
interface CardProcessingMetricsPort {
    fun authorizationDecided(approved: Boolean, reason: String?)

    fun presentmentApplied(fullyCleared: Boolean)

    fun holdReleased(kind: String)

    fun ledgerPosting(outcome: PostingOutcome)

    fun fraudScoring(outcome: FraudScoringOutcome)
}
