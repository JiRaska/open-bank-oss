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
    suspend fun save(authorization: CardAuthorization, event: OutboxMessage, idempotencyKey: String): CardAuthorization

    /**
     * Applies a clearing: the updated authorisation, its event **and** the [clearing] record commit in
     * one transaction. The record's `(authorizationId, idempotencyKey)` is UNIQUE in the database, so
     * a concurrent duplicate cannot also commit — it fails with [DuplicateClearingException] and its
     * hold decrement and event roll back with it.
     *
     * [authorization] must carry the version of the row it was computed from: if the stored row has
     * moved on (a concurrent clearing under another key), nothing is written and
     * [StaleAuthorizationException] is thrown.
     */
    suspend fun saveClearing(
        authorization: CardAuthorization,
        event: OutboxMessage,
        clearing: RecordedClearing,
    ): CardAuthorization

    /** The clearing already applied under [idempotencyKey] for this authorisation, if any. */
    suspend fun findClearing(authorizationId: UUID, idempotencyKey: String): RecordedClearing?

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

/**
 * A clearing presentment that has been applied, as the idempotency record for its key.
 * [requestFingerprint] binds the key to the presented amount and currency, so the same key with a
 * different body is detectable as reuse instead of being replayed as the first one.
 */
data class RecordedClearing(
    val id: UUID,
    val authorizationId: UUID,
    val idempotencyKey: String,
    val requestFingerprint: String,
    val amountMinorUnits: Long,
    val currencyCode: String,
    val appliedAt: Instant,
)

/** A concurrent request already applied a clearing under the same key; this one was rolled back. */
class DuplicateClearingException(cause: Throwable) :
    RuntimeException("a clearing with this key was already applied to the authorisation", cause)

/**
 * The authorisation changed between the read a clearing was computed from and its write (a
 * concurrent clearing under another key). Nothing was written; re-read and re-evaluate.
 */
class StaleAuthorizationException(cause: Throwable? = null) :
    RuntimeException("the authorisation was modified concurrently", cause)

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

/**
 * Card facts card-processing needs and does not own: which account and party the card belongs to,
 * and the card's lifecycle state as card-issuance reports it (`ACTIVE`, `BLOCKED`, …), or null when
 * card-issuance did not say — which every caller that gates on it must treat as NOT active.
 */
data class CardOwnership(
    val accountId: UUID,
    val partyId: UUID,
    val currencyCode: String,
    val status: String? = null,
) {
    val active: Boolean get() = status.equals(ACTIVE, ignoreCase = true)

    private companion object {
        const val ACTIVE = "ACTIVE"
    }
}

/**
 * card-issuance could not be asked — a transport failure or a non-404 error. Distinct from "no such
 * card" (a null from [CardLookupPort.lookup]): one is the client's mistake, the other is an outage,
 * and answering an outage as a 404 tells a wallet the card does not exist.
 */
class CardIssuerUnavailableException(cause: Throwable) : RuntimeException("card-issuance could not be reached", cause)

interface CardLookupPort {
    /** Null for an unknown card; throws [CardIssuerUnavailableException] when card-issuance cannot answer. */
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

    /** A clearing lost a race to a concurrent clearing on the same authorisation and was re-evaluated. */
    fun clearingConflict()

    fun holdReleased(kind: String)

    fun ledgerPosting(outcome: PostingOutcome)

    fun fraudScoring(outcome: FraudScoringOutcome)
}
