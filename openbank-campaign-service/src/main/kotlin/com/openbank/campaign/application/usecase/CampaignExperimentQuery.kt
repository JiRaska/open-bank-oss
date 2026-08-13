// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.CampaignExperimentRepository
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.ExperimentCohortMetrics
import com.openbank.campaign.domain.model.ExperimentCohort
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID
import kotlin.math.sqrt

/** A cohort outcome, with a null rate when no one has yet been assigned to that cohort. */
data class CohortOutcome(val assigned: Long, val converted: Long, val conversionRate: Double?)

/** A 95% Wilson confidence interval for one cohort's observed conversion rate. */
data class RateConfidenceInterval(val lower: Double, val upper: Double)

/**
 * A conservative readiness state for an operator, never an automatic campaign action.
 *
 * A separation is reported only after each cohort has enough assignments and their individual 95%
 * Wilson intervals do not overlap. That is deliberately more conservative than treating a raw
 * rate difference as a winner declaration; overlapping intervals stay [INCONCLUSIVE].
 */
enum class ExperimentDecisionState {
    COLLECTING_DATA,
    INCONCLUSIVE,
    TREATMENT_OUTPERFORMS_HOLDOUT,
    HOLDOUT_OUTPERFORMS_TREATMENT,
}

data class ExperimentDecision(
    val state: ExperimentDecisionState,
    val minimumAssignedPerCohort: Int,
    val treatmentConfidenceInterval: RateConfidenceInterval?,
    val holdoutConfidenceInterval: RateConfidenceInterval?,
)

/**
 * A deliberately cautious experiment result. [observedLiftPercentagePoints] remains descriptive;
 * [decision] is a conservative readiness gate, not proof of causality and never an instruction to
 * change a running campaign automatically.
 */
data class CampaignExperimentSummary(
    val campaignId: UUID,
    val holdoutPercent: Int,
    val treatment: CohortOutcome,
    val holdout: CohortOutcome,
    val observedLiftPercentagePoints: Double?,
    val decision: ExperimentDecision,
)

@ApplicationScoped
class CampaignExperimentQuery(
    private val campaigns: CampaignRepository,
    private val experiments: CampaignExperimentRepository,
) {
    /** Null means no campaign exists; a configured experiment is required for a result. */
    suspend fun summary(campaignId: UUID): CampaignExperimentSummary? {
        val campaign = campaigns.findById(campaignId) ?: return null
        check(campaign.holdoutPercent > 0) { "campaign $campaignId has no holdout experiment" }
        val byCohort = experiments.metrics(campaignId).associateBy { it.cohort }
        val treatment = byCohort.outcomeFor(ExperimentCohort.TREATMENT)
        val holdout = byCohort.outcomeFor(ExperimentCohort.HOLDOUT)
        val observedLift = treatment.conversionRate?.let { treatmentRate ->
            holdout.conversionRate?.let { holdoutRate -> (treatmentRate - holdoutRate) * PERCENT }
        }
        return CampaignExperimentSummary(
            campaignId = campaignId,
            holdoutPercent = campaign.holdoutPercent,
            treatment = treatment,
            holdout = holdout,
            observedLiftPercentagePoints = observedLift,
            decision = ExperimentStatistics.evaluate(treatment, holdout),
        )
    }

    private fun Map<ExperimentCohort, ExperimentCohortMetrics>.outcomeFor(cohort: ExperimentCohort): CohortOutcome {
        val metrics = get(cohort) ?: ExperimentCohortMetrics(cohort, assigned = 0, converted = 0)
        return CohortOutcome(
            assigned = metrics.assigned,
            converted = metrics.converted,
            conversionRate = metrics.converted.toDouble().div(metrics.assigned).takeIf { metrics.assigned > 0 },
        )
    }

    private companion object {
        const val PERCENT = 100.0
    }
}

private object ExperimentStatistics {
    private const val MINIMUM_ASSIGNED_PER_COHORT = 100
    private const val Z_95 = 1.959_963_984_540_054
    private const val HALF = 2.0
    private const val QUARTER = 4.0

    fun evaluate(treatment: CohortOutcome, holdout: CohortOutcome): ExperimentDecision {
        val treatmentInterval = treatment.wilsonInterval()
        val holdoutInterval = holdout.wilsonInterval()
        val state = when {
            treatment.assigned < MINIMUM_ASSIGNED_PER_COHORT || holdout.assigned < MINIMUM_ASSIGNED_PER_COHORT ->
                ExperimentDecisionState.COLLECTING_DATA
            treatmentInterval == null || holdoutInterval == null -> ExperimentDecisionState.COLLECTING_DATA
            treatmentInterval.lower > holdoutInterval.upper -> ExperimentDecisionState.TREATMENT_OUTPERFORMS_HOLDOUT
            holdoutInterval.lower > treatmentInterval.upper -> ExperimentDecisionState.HOLDOUT_OUTPERFORMS_TREATMENT
            else -> ExperimentDecisionState.INCONCLUSIVE
        }
        return ExperimentDecision(state, MINIMUM_ASSIGNED_PER_COHORT, treatmentInterval, holdoutInterval)
    }

    private fun CohortOutcome.wilsonInterval(): RateConfidenceInterval? {
        val rate = conversionRate ?: return null
        val n = assigned.toDouble()
        val zSquared = Z_95 * Z_95
        val denominator = 1 + zSquared / n
        val centre = (rate + zSquared / (HALF * n)) / denominator
        val margin = Z_95 * sqrt((rate * (1 - rate) + zSquared / (QUARTER * n)) / n) / denominator
        return RateConfidenceInterval((centre - margin).coerceAtLeast(0.0), (centre + margin).coerceAtMost(1.0))
    }
}
