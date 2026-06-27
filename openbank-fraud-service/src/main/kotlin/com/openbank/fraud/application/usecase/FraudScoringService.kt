// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.application.usecase

import com.openbank.fraud.application.port.`in`.ScoreFraudUseCase
import com.openbank.fraud.application.port.out.FraudMetricsPort
import com.openbank.fraud.application.port.out.FraudScoreRepository
import com.openbank.fraud.application.port.out.VelocityAggregateRepository
import com.openbank.fraud.domain.model.FraudScore
import com.openbank.fraud.domain.model.ScoreRequest
import com.openbank.fraud.domain.model.VelocityWindow
import com.openbank.fraud.domain.rules.FraudRuleEngine
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Real-time scoring use case (ADR-0084 §1/§2). Enriches the payment context with per-account
 * rolling velocity counters from the async signal plane (ADR-0084 §2), evaluates the deterministic
 * rule set, and persists the decision as an immutable audit row. The latency budget at maturity is
 * p99 ≤ 150 ms.
 *
 * Velocity lookups degrade gracefully — a missing aggregate (null from the repository, e.g. before
 * the first Kafka signal arrives) leaves the counter at zero so no velocity rule fires.
 *
 * Single CDI constructor — all dependencies are [ApplicationScoped] beans, so a plain [@Inject]
 * constructor satisfies ArC. The pure [FraudRuleEngine] is a stateless object (not a bean) and is
 * referenced directly, not injected.
 *
 * Every decision is both persisted (immutable audit row) and counted by verdict via
 * [FraudMetricsPort] (`openbank_fraud_scores_total`) — the series that proves the
 * shadow → challenge → enforce rollout is safe before any surface honours a verdict.
 */
@ApplicationScoped
class FraudScoringService @Inject constructor(
    private val repository: FraudScoreRepository,
    private val metrics: FraudMetricsPort,
    private val velocityRepo: VelocityAggregateRepository,
) : ScoreFraudUseCase {

    override suspend fun score(request: ScoreRequest): FraudScore {
        val enriched = enrichWithVelocity(request)
        val result = FraudRuleEngine.score(enriched)
        repository.save(enriched, result)
        metrics.recordVerdict(result.verdict, enriched.rail)
        return result
    }

    private suspend fun enrichWithVelocity(request: ScoreRequest): ScoreRequest {
        val accountId = request.accountId ?: return request
        val currency = request.currency
        val h1 = velocityRepo.findAggregate(accountId, VelocityWindow.H1, currency)?.transactionCount ?: 0L
        val h24 = velocityRepo.findAggregate(accountId, VelocityWindow.H24, currency)?.transactionCount ?: 0L
        return request.copy(velocityH1Count = h1, velocityH24Count = h24)
    }
}
