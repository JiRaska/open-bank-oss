// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.application.port.out

import com.openbank.fraud.domain.model.FraudScore
import com.openbank.fraud.domain.model.ScoreRequest
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Outbound persistence port (ADR-0002). Each scoring decision is persisted as an immutable audit
 * row — the reference fraud-rate dataset RTS Art. 18 needs and the evidence trail for every verdict.
 * Implemented by [com.openbank.fraud.infrastructure.persistence.FraudScoreRepositoryImpl].
 */
interface FraudScoreRepository {

    /** Persist one scoring decision (request context + verdict) and return its audit-row id. */
    suspend fun save(request: ScoreRequest, result: FraudScore): UUID

    /** REVIEW-queue read (ADR-0230 D1): newest scoring rows with the given verdict. */
    suspend fun findRecentByVerdict(verdict: String, limit: Int): List<ScoredRecord>
}

/** One persisted scoring row as the review queue renders it (the reasons payload stays server-side). */
data class ScoredRecord(
    val scoreId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val rail: String,
    val accountId: UUID?,
    val counterpartyId: UUID?,
    val verdict: String,
    val score: Int,
    val ruleVersion: String,
    val createdAt: Instant,
)
