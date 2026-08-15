// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.CampaignContentExperimentRepository
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.ContentVariantMetrics
import com.openbank.campaign.domain.model.ContentVariant
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID
import kotlin.math.sqrt

/** Outcome of one stable content arm. A null rate means no party has been assigned yet. */
data class ContentVariantOutcome(val assigned: Long, val converted: Long, val conversionRate: Double?)

enum class ContentExperimentDecisionState { COLLECTING_DATA, INCONCLUSIVE, A_OUTPERFORMS_B, B_OUTPERFORMS_A }

/** Readiness guidance, never an instruction to alter an active campaign automatically. */
data class ContentExperimentDecision(
    val state: ContentExperimentDecisionState,
    val minimumAssignedPerVariant: Int,
    val aConfidenceInterval: RateConfidenceInterval?,
    val bConfidenceInterval: RateConfidenceInterval?,
)

data class CampaignContentExperimentSummary(
    val campaignId: UUID,
    val a: ContentVariantOutcome,
    val b: ContentVariantOutcome,
    val observedLiftPercentagePoints: Double?,
    val decision: ContentExperimentDecision,
)

/**
 * A measured two-arm content experiment. It does not infer an open or click — the only outcome is
 * the campaign's explicitly declared product conversion rule, and assignment is the durable value
 * on the enrolment. Holdout enrolments are intentionally absent from this comparison.
 */
@ApplicationScoped
class CampaignContentExperimentQuery(
    private val campaigns: CampaignRepository,
    private val experiments: CampaignContentExperimentRepository,
) {
    suspend fun summary(campaignId: UUID): CampaignContentExperimentSummary? {
        val campaign = campaigns.findById(campaignId) ?: return null
        check(campaign.hasContentExperiment) { "campaign $campaignId has no content experiment" }
        check(campaign.conversionRule != null) { "a content experiment requires a conversionRule" }
        val metrics = experiments.metrics(campaignId).associateBy { it.variant }
        val a = metrics.outcomeFor(ContentVariant.A)
        val b = metrics.outcomeFor(ContentVariant.B)
        val lift = b.conversionRate?.let { bRate -> a.conversionRate?.let { aRate -> (bRate - aRate) * PERCENT } }
        return CampaignContentExperimentSummary(
            campaignId = campaignId,
            a = a,
            b = b,
            observedLiftPercentagePoints = lift,
            decision = ContentExperimentStatistics.evaluate(a, b),
        )
    }

    private fun Map<ContentVariant, ContentVariantMetrics>.outcomeFor(variant: ContentVariant): ContentVariantOutcome {
        val metrics = get(variant) ?: ContentVariantMetrics(variant, assigned = 0, converted = 0)
        return ContentVariantOutcome(
            assigned = metrics.assigned,
            converted = metrics.converted,
            conversionRate = metrics.converted.toDouble().div(metrics.assigned).takeIf { metrics.assigned > 0 },
        )
    }

    private companion object {
        const val PERCENT = 100.0
    }
}

private object ContentExperimentStatistics {
    private const val MINIMUM_ASSIGNED_PER_VARIANT = 100
    private const val Z_95 = 1.959_963_984_540_054
    private const val HALF = 2.0
    private const val QUARTER = 4.0

    fun evaluate(a: ContentVariantOutcome, b: ContentVariantOutcome): ContentExperimentDecision {
        val aInterval = a.wilsonInterval()
        val bInterval = b.wilsonInterval()
        val state = when {
            a.assigned < MINIMUM_ASSIGNED_PER_VARIANT || b.assigned < MINIMUM_ASSIGNED_PER_VARIANT ->
                ContentExperimentDecisionState.COLLECTING_DATA
            aInterval == null || bInterval == null -> ContentExperimentDecisionState.COLLECTING_DATA
            aInterval.lower > bInterval.upper -> ContentExperimentDecisionState.A_OUTPERFORMS_B
            bInterval.lower > aInterval.upper -> ContentExperimentDecisionState.B_OUTPERFORMS_A
            else -> ContentExperimentDecisionState.INCONCLUSIVE
        }
        return ContentExperimentDecision(state, MINIMUM_ASSIGNED_PER_VARIANT, aInterval, bInterval)
    }

    private fun ContentVariantOutcome.wilsonInterval(): RateConfidenceInterval? {
        val rate = conversionRate ?: return null
        val n = assigned.toDouble()
        val zSquared = Z_95 * Z_95
        val denominator = 1 + zSquared / n
        val centre = (rate + zSquared / (HALF * n)) / denominator
        val margin = Z_95 * sqrt((rate * (1 - rate) + zSquared / (QUARTER * n)) / n) / denominator
        return RateConfidenceInterval((centre - margin).coerceAtLeast(0.0), (centre + margin).coerceAtMost(1.0))
    }
}
