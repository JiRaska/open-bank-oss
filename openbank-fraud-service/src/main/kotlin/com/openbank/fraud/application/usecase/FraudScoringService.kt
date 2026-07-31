// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.application.usecase

import com.openbank.fraud.application.port.`in`.ScoreFraudUseCase
import com.openbank.fraud.application.port.out.FraudMetricsPort
import com.openbank.fraud.application.port.out.FraudScoreRepository
import com.openbank.fraud.application.port.out.MlModelPort
import com.openbank.fraud.application.port.out.PayeeHistoryRepository
import com.openbank.fraud.application.port.out.ScoredRecord
import com.openbank.fraud.application.port.out.VelocityAggregateRepository
import com.openbank.fraud.domain.model.FraudScore
import com.openbank.fraud.domain.model.ScoreRequest
import com.openbank.fraud.domain.model.VelocityWindow
import com.openbank.fraud.domain.rules.FraudRuleEngine
import com.openbank.libs.domain.feature.FeatureValue
import com.openbank.libs.domain.feature.OnlineFeatureStore
import com.openbank.libs.domain.feature.PHASE1_FEATURES
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant

/**
 * Real-time scoring use case (ADR-0084 §1/§2). Enriches the payment context with per-account
 * rolling velocity counters from the async signal plane (ADR-0084 §2), evaluates the deterministic
 * rule set, and persists the decision as an immutable audit row. The latency budget at maturity is
 * p99 ≤ 150 ms.
 *
 * Velocity lookups degrade gracefully — a missing aggregate (null from the repository, e.g. before
 * the first Kafka signal arrives) leaves the counter at zero so no velocity rule fires. The
 * ADR-0084 §3 v4 payee-history lookup degrades the same direction: a missing accountId/
 * counterpartyId pair leaves [ScoreRequest.isNewPayee] at its default `false`, so
 * [com.openbank.fraud.domain.rules.NewPayeeHighAmountReviewRule] stays silent rather than firing on
 * a pair the service could not actually evaluate — the *absence* of history data never manufactures
 * a REVIEW by itself. A genuinely new payee (no [com.openbank.fraud.domain.model.PayeeHistory] row
 * for the pair) is the one case that *does* set it true — see [enrichWithPayeeHistory].
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
    private val payeeHistoryRepo: PayeeHistoryRepository,
    private val featureStore: OnlineFeatureStore,
    private val mlModel: MlModelPort,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.ml.shadow.enabled", defaultValue = "true")
    private val shadowEnabled: Boolean,
) : ScoreFraudUseCase {

    private val log = Logger.getLogger(FraudScoringService::class.java)

    override suspend fun score(request: ScoreRequest): FraudScore {
        val enriched = enrichWithPayeeHistory(enrichWithVelocity(request))
        val result = FraudRuleEngine.score(enriched) // the ONLY thing that determines the verdict
        repository.save(enriched, result)
        metrics.recordVerdict(result.verdict, enriched.rail)
        runShadow(enriched, result) // logs + metrics only; its outcome is discarded
        return result // byte-identical to rules-only
    }

    override suspend fun reviewQueue(verdict: String, limit: Int): List<ScoredRecord> =
        repository.findRecentByVerdict(verdict, limit)

    /**
     * ADR-0139 phase-1 shadow plane: compute the ML score from the online feature store and log it
     * alongside the rule verdict. It changes nothing — the returned [FraudScore] is the pure rule
     * engine's output. Stale/missing features are omitted (never a confident stale value, ADR-0140),
     * and any failure here is swallowed so the rule verdict always returns (fail-open for shadow).
     */
    private suspend fun runShadow(enriched: ScoreRequest, ruleResult: FraudScore) {
        if (!shadowEnabled) return
        val accountId = enriched.accountId ?: return
        @Suppress("TooGenericExceptionCaught") // feature-store / model failures have no common base
        try {
            val now = Instant.now(clock)
            val features = PHASE1_FEATURES.mapNotNull { feature ->
                when (val value = featureStore.read(feature, accountId.toString(), now)) {
                    is FeatureValue.Fresh -> feature.name to value.value
                    else -> null // Stale / Missing -> omit (treated as missing, ADR-0140)
                }
            }.toMap()
            val mlScore = mlModel.scoreShadow(features) ?: return // model unavailable -> rules-only
            metrics.recordShadowScore(mlScore)
            log.infof(
                "fraud-ml-shadow account=%s features=%s mlScore=%.4f ruleVerdict=%s ruleScore=%d",
                accountId,
                features,
                mlScore,
                ruleResult.verdict,
                ruleResult.score,
            )
        } catch (ex: Exception) {
            log.debugf(ex, "shadow scoring failed for account %s (rules verdict unaffected)", accountId)
        }
    }

    private suspend fun enrichWithVelocity(request: ScoreRequest): ScoreRequest {
        val accountId = request.accountId ?: return request
        val currency = request.currency
        val h1Aggregate = velocityRepo.findAggregate(accountId, VelocityWindow.H1, currency)
        val h24 = velocityRepo.findAggregate(accountId, VelocityWindow.H24, currency)?.transactionCount ?: 0L
        return request.copy(
            velocityH1Count = h1Aggregate?.transactionCount ?: 0L,
            velocityH24Count = h24,
            velocityH1TotalAmount = h1Aggregate?.totalAmount ?: BigDecimal.ZERO,
        )
    }

    /**
     * ADR-0084 §3 v4: sets [ScoreRequest.isNewPayee] from the payee_history signal plane. Silent
     * (leaves the default `false`) when either half of the (account, payee) pair is missing —
     * mirrors [enrichWithVelocity]'s "nothing to look up -> no signal" contract. A pair IS new when
     * [PayeeHistoryRepository.findHistory] returns null (no prior payment ever recorded), which is
     * the normal, expected state for a brand-new payee, not an error condition.
     */
    private suspend fun enrichWithPayeeHistory(request: ScoreRequest): ScoreRequest {
        val accountId = request.accountId ?: return request
        val counterpartyId = request.counterpartyId ?: return request
        val history = payeeHistoryRepo.findHistory(accountId, counterpartyId.toString())
        return request.copy(isNewPayee = history == null)
    }
}
