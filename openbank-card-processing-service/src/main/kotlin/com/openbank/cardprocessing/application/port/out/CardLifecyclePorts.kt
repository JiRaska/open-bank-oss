// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

@file:Suppress("ktlint:standard:filename")

package com.openbank.cardprocessing.application.port.out

import com.openbank.cardprocessing.domain.model.CardDisputeCase
import com.openbank.cardprocessing.domain.model.CardTokenRegistration
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
    suspend fun save(
        registration: CardTokenRegistration,
        event: OutboxMessage,
        idempotencyKey: String,
    ): CardTokenRegistration

    suspend fun findByTokenReference(tokenReference: String): CardTokenRegistration?

    suspend fun findByIdempotencyKey(key: String): CardTokenRegistration?

    /** The mirror for one card, newest first. This is what a degraded read falls back to. */
    suspend fun findByCardId(cardId: UUID): List<CardTokenRegistration>
}

interface CardDisputeCaseRepository {
    suspend fun save(case: CardDisputeCase, event: OutboxMessage, idempotencyKey: String): CardDisputeCase

    suspend fun findById(id: UUID): CardDisputeCase?

    suspend fun findByIdempotencyKey(key: String): CardDisputeCase?

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
}
