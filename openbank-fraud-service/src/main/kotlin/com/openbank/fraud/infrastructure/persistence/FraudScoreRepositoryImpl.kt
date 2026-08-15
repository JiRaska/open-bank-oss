// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.fraud.application.port.out.FraudScoreRepository
import com.openbank.fraud.application.port.out.ScoredRecord
import com.openbank.fraud.domain.model.FraudScore
import com.openbank.fraud.domain.model.ScoreRequest
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "fraud_scores")
class FraudScoreEntity : PanacheEntity() {
    @Column(name = "score_id", nullable = false, unique = true)
    lateinit var scoreId: UUID

    @Column(name = "amount", nullable = false)
    lateinit var amount: BigDecimal

    @Column(name = "currency", nullable = false)
    lateinit var currency: String

    @Column(name = "rail", nullable = false)
    lateinit var rail: String

    @Column(name = "account_id")
    var accountId: UUID? = null

    @Column(name = "counterparty_id")
    var counterpartyId: UUID? = null

    @Column(name = "verdict", nullable = false)
    lateinit var verdict: String

    @Column(name = "score", nullable = false)
    var score: Int = 0

    @Column(name = "reasons_json", columnDefinition = "TEXT")
    lateinit var reasonsJson: String

    @Column(name = "rule_version", nullable = false)
    lateinit var ruleVersion: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant
}

/**
 * Reactive Panache persistence adapter (ADR-0009 Postgres-per-service). Each scoring decision is
 * written as an immutable audit row — the reference fraud-rate dataset (RTS Art. 18) and the
 * per-verdict evidence trail.
 */
@ApplicationScoped
class FraudScoreRepositoryImpl(private val clock: Clock) :
    FraudScoreRepository,
    PanacheRepository<FraudScoreEntity> {

    @Inject lateinit var objectMapper: ObjectMapper

    override suspend fun save(request: ScoreRequest, result: FraudScore): UUID {
        val entity = toEntity(request, result, objectMapper, clock)
        Panache.withTransaction { persist(entity) }.awaitSuspending()
        return entity.scoreId
    }

    override suspend fun findRecentByVerdict(verdict: String, limit: Int): List<ScoredRecord> = Panache.withSession {
        find("verdict = ?1 ORDER BY createdAt DESC", verdict).page(0, limit.coerceIn(1, MAX_QUEUE_LIMIT)).list()
    }.awaitSuspending().map { it.toScoredRecord() }

    override suspend fun countRecentByAccountAndVerdict(accountId: UUID, verdict: String, since: Instant): Long =
        Panache.withSession {
            count("accountId = ?1 and verdict = ?2 and createdAt >= ?3", accountId, verdict, since)
        }.awaitSuspending()

    private companion object {
        const val MAX_QUEUE_LIMIT = 200
    }
}

// Mapper kept at file scope (pure function over an injected ObjectMapper) so the repository class
// stays within detekt's per-class function budget — mirrors the kyc/audit pattern.
private fun toEntity(request: ScoreRequest, result: FraudScore, objectMapper: ObjectMapper, clock: Clock) =
    FraudScoreEntity().also {
        it.scoreId = Ids.newId()
        it.amount = request.amount
        it.currency = request.currency
        it.rail = request.rail
        it.accountId = request.accountId
        it.counterpartyId = request.counterpartyId
        it.verdict = result.verdict.name
        it.score = result.score
        it.reasonsJson = objectMapper.writeValueAsString(result.reasons)
        it.ruleVersion = result.ruleVersion
        it.createdAt = Instant.now(clock)
    }

private fun FraudScoreEntity.toScoredRecord() = ScoredRecord(
    scoreId = scoreId,
    amount = amount,
    currency = currency,
    rail = rail,
    accountId = accountId,
    counterpartyId = counterpartyId,
    verdict = verdict,
    score = score,
    ruleVersion = ruleVersion,
    createdAt = createdAt,
)
