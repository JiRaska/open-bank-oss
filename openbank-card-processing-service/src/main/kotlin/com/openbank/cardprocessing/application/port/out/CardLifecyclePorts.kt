// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

@file:Suppress("ktlint:standard:filename")

package com.openbank.cardprocessing.application.port.out

import com.openbank.cardprocessing.domain.model.CardDisputeCase
import com.openbank.cardprocessing.domain.model.CardTokenRegistration
import com.openbank.cardprocessing.domain.model.DisputeEvidenceRecord
import com.openbank.libs.persistence.outbox.OutboxMessage
import java.util.UUID

/**
 * Persistence for the token mirror.
 *
 * Every write takes its event: the row and the outbox row commit together or neither does
 * (ADR-0050). A token whose provisioning was recorded but never announced is the same defect class
 * as an authorisation that never reached the ledger, which is what ADR-0283 exists to fix.
 */
interface CardTokenRegistrationRepository {
    /**
     * Writes [registration] with its [event] and, when [claim] is given, completes that idempotency
     * reservation in the SAME transaction — so a replay can never find a completed reservation
     * pointing at a row that was rolled back, nor a row whose reservation still reads PENDING.
     */
    suspend fun save(
        registration: CardTokenRegistration,
        event: OutboxMessage,
        idempotencyKey: String,
        claim: IdempotencyClaim?,
    ): CardTokenRegistration

    /**
     * Upserts tokens the NETWORK reported that the mirror did not hold, keyed on the network's own
     * token reference, and returns the stored rows. An existing row is left untouched — only a
     * reference the mirror has never seen is inserted — so two concurrent reads cannot both insert,
     * and a re-read returns the same id every time.
     */
    suspend fun adoptNetworkSeen(registrations: List<CardTokenRegistration>): List<CardTokenRegistration>

    suspend fun findById(id: UUID): CardTokenRegistration?

    suspend fun findByTokenReference(tokenReference: String): CardTokenRegistration?

    /** The mirror for one card, newest first. This is what a degraded read falls back to. */
    suspend fun findByCardId(cardId: UUID): List<CardTokenRegistration>
}

interface CardDisputeCaseRepository {
    /**
     * Writes the case with its event and, when [claim] is given, completes that reservation in the
     * same transaction — see [CardTokenRegistrationRepository.save].
     */
    suspend fun save(
        case: CardDisputeCase,
        event: OutboxMessage,
        idempotencyKey: String,
        claim: IdempotencyClaim?,
    ): CardDisputeCase

    /**
     * Appends one evidence submission to the case's history, updates the case, writes the event and
     * completes [claim] — all in one transaction. History is APPEND-ONLY: a second submission adds a
     * row, it never overwrites the first, because the representment file a scheme ruled on has to be
     * reconstructible afterwards.
     */
    suspend fun recordEvidence(
        case: CardDisputeCase,
        evidence: DisputeEvidenceRecord,
        event: OutboxMessage,
        claim: IdempotencyClaim,
    ): CardDisputeCase

    /** Every evidence submission against [disputeId], oldest first. */
    suspend fun findEvidence(disputeId: UUID): List<DisputeEvidenceRecord>

    suspend fun findById(id: UUID): CardDisputeCase?

    suspend fun findByCardId(cardId: UUID, limit: Int): List<CardDisputeCase>

    /**
     * A live case against this authorisation, if one exists.
     *
     * "Live" means not terminal. The database enforces the same rule with a partial UNIQUE index, so
     * two concurrent requests cannot both pass this read and then both insert — a check in
     * application code alone is a race, not a constraint.
     */
    suspend fun findLiveByAuthorization(authorizationId: UUID): CardDisputeCase?
}

/**
 * What the token and dispute paths count.
 *
 * Separate from [CardProcessingMetricsPort] because the outcomes are different: a token refusal is
 * not a decline and a dispute is not a presentment. Folding them in would give the money-path
 * dashboards a counter that moves for a reason none of their panels can explain.
 *
 * Every counter carries its OUTCOME, including the refusals. A path that only counts successes
 * cannot answer "is the scheme binding failing?" — which for a capability whose only binding today
 * is a simulator is the single most useful question about it.
 */
interface CardLifecycleMetricsPort {
    fun tokenProvisioned(scheme: String, refusal: String?)

    fun tokenStatusChanged(scheme: String, status: String, refusal: String?)

    fun tokenListServed(source: String)

    fun disputeOpened(scheme: String, refusal: String?)

    fun disputeEvidenceSubmitted(refusal: String?)

    /**
     * A refresh of a CLOSED case found the network reporting a different outcome. The stored outcome
     * is kept — a closed case is terminal — so this counter is the only place the disagreement shows.
     */
    fun disputeTerminalMismatch(scheme: String, stored: String, reported: String)
}

/**
 * The scheme label for a refusal decided BEFORE any network was asked — unknown card, card not
 * active, card-issuance unreachable, nothing cleared. One value for both the token and the dispute
 * paths, so a dashboard filter `scheme!="NONE"` means "the network was actually called" everywhere.
 */
const val UNATTRIBUTED_SCHEME = "NONE"

/** Which mutating operation an `Idempotency-Key` was presented to. Keys are scoped per operation. */
enum class LifecycleOperation { TOKEN_PROVISION, DISPUTE_OPEN, DISPUTE_EVIDENCE }

/** A reservation this request holds and must complete (in the result's transaction) or release. */
data class IdempotencyClaim(val operation: LifecycleOperation, val key: String)

/** What [LifecycleIdempotencyPort.reserve] found. */
sealed interface Reservation {
    /** The key was free and is now held PENDING by this request: call the network. */
    data object Claimed : Reservation

    /** The same request already completed; [resultId] is the row it produced. Replay it. */
    data class Completed(val resultId: UUID) : Reservation

    /** The same request is still running (or died after calling the network). Never call again. */
    data object InProgress : Reservation

    /** The key is bound to a DIFFERENT request. */
    data object Mismatch : Reservation
}

/**
 * Reserves an `Idempotency-Key` in the database BEFORE the network is called.
 *
 * A read-then-call-then-insert lets two concurrent first requests both miss the read and both reach
 * the network — two wallet credentials, two chargebacks — and the loser then fails its insert with a
 * 500. The reservation is an INSERT under a primary key, so exactly one request can win it; the loser
 * is answered from the winner's row, or told the winner is still running. It never calls the network.
 *
 * A PENDING reservation does NOT expire on a timer. A request that died after the network answered
 * may have minted a token or opened a case, and letting a retry through would mint a second one — so
 * a stuck reservation stays `InProgress` and is an operator matter (logged at ERROR where it is left).
 */
interface LifecycleIdempotencyPort {
    suspend fun reserve(operation: LifecycleOperation, key: String, fingerprint: String): Reservation

    /** Frees a PENDING reservation — only when the network was provably not reached, or refused. */
    suspend fun release(claim: IdempotencyClaim)
}
