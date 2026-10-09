// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.onboarding

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.questionnaire.QuestionnaireProfile
import com.openbank.pension.domain.questionnaire.QuestionnaireRecord
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.util.UUID

/** Sustainability preference (MiFID Art. 2(7) delegated regulation shape, generic). */
enum class EsgPreference { NONE, CONSIDER, REQUIRED }

/**
 * Questionnaire answers on closed 0..3 scales, so scoring never interprets free text.
 * Knowledge and experience are only REQUIRED under the MiFID regime; the light regime may omit them.
 */
data class QuestionnaireAnswers(
    val knowledgeLevel: Int? = null,
    val experienceLevel: Int? = null,
    val riskAppetite: Int,
    val lossTolerance: Int,
    val financialSituationStable: Boolean,
    val esgPreference: EsgPreference? = null,
) {
    init {
        listOfNotNull(knowledgeLevel, experienceLevel, riskAppetite, lossTolerance).forEach {
            require(it in SCALE) { "questionnaire answers must be on the 0..3 scale" }
        }
    }

    companion object {
        val SCALE = 0..3
    }
}

/** The risk profile derived from answers: the highest risk class the participant can bear. */
data class RiskProfile(val maxRiskClass: Int, val label: RiskLabel)

enum class RiskLabel { CONSERVATIVE, BALANCED, DYNAMIC }

enum class AssessmentStatus {
    CURRENT,
    SUPERSEDED,
    ;

    fun canMoveTo(target: AssessmentStatus): Boolean = this == CURRENT && target == SUPERSEDED
}

/**
 * One answered suitability / appropriateness / ESG questionnaire (ADR-0334 §4 step 2).
 *
 * Immutable and append-only: answering again SUPERSEDES the previous assessment rather than editing
 * it, because the assessment in force at signature is evidence of what the participant was told.
 */
data class SuitabilityAssessment(
    val id: UUID,
    val partyId: UUID,
    val applicationId: UUID,
    val productLine: ProductLine,
    val regime: QuestionnaireRegime,
    val answers: QuestionnaireAnswers,
    val riskProfile: RiskProfile,
    /** Null when the regime runs no appropriateness test. */
    val appropriate: Boolean?,
    val status: AssessmentStatus,
    val assessedAt: Instant,
    val validUntil: LocalDate,
    /** Set when the assessment came from the data-driven question set (issue #12384). */
    val questionnaire: QuestionnaireRecord? = null,
) {
    val esgPreference: EsgPreference get() = answers.esgPreference ?: EsgPreference.NONE

    fun supersede(): SuitabilityAssessment {
        check(status.canMoveTo(AssessmentStatus.SUPERSEDED)) { "assessment $id is already $status" }
        return copy(status = AssessmentStatus.SUPERSEDED)
    }

    fun isValidOn(date: LocalDate): Boolean = status == AssessmentStatus.CURRENT && !date.isAfter(validUntil)

    companion object {
        private const val SCORE_TO_CLASS_STEP = 2
        private const val UNSTABLE_FINANCES_CAP = 3
        private const val BALANCED_FROM = 3
        private const val DYNAMIC_FROM = 5

        /** Scores [answers] under [rules]; refuses answers the regime requires but did not get. */
        @Suppress("LongParameterList")
        fun assess(
            partyId: UUID,
            applicationId: UUID,
            productLine: ProductLine,
            rules: QuestionnaireRules,
            answers: QuestionnaireAnswers,
            today: LocalDate,
            now: Instant,
        ): SuitabilityAssessment {
            if (rules.regime == QuestionnaireRegime.MIFID_SUITABILITY || rules.appropriatenessTest) {
                require(answers.knowledgeLevel != null && answers.experienceLevel != null) {
                    "knowledgeLevel and experienceLevel are required under ${rules.regime}"
                }
            }
            require(!rules.esgPreferenceRequired || answers.esgPreference != null) {
                "esgPreference is required for this product"
            }
            return SuitabilityAssessment(
                id = Ids.newId(),
                partyId = partyId,
                applicationId = applicationId,
                productLine = productLine,
                regime = rules.regime,
                answers = answers,
                riskProfile = riskProfile(answers),
                appropriate = if (rules.appropriatenessTest) {
                    (answers.knowledgeLevel ?: 0) + (answers.experienceLevel ?: 0) >= rules.appropriatenessMinScore
                } else {
                    null
                },
                status = AssessmentStatus.CURRENT,
                assessedAt = now,
                validUntil = today.plusDays(rules.validityDays.toLong()),
            )
        }

        /**
         * Scores a data-driven [profile] (issue #12384): the class comes from the question set's
         * per-answer ceilings (1..7), the regime checks and appropriateness from [assess].
         */
        @Suppress("LongParameterList")
        fun fromQuestionnaire(
            partyId: UUID,
            applicationId: UUID,
            productLine: ProductLine,
            rules: QuestionnaireRules,
            profile: QuestionnaireProfile,
            record: QuestionnaireRecord,
            today: LocalDate,
            now: Instant,
        ): SuitabilityAssessment = assess(partyId, applicationId, productLine, rules, profile.legacyAnswers, today, now)
            .copy(riskProfile = profile.riskProfile, questionnaire = record)

        /**
         * The weaker of willingness (appetite) and ability (loss tolerance) bounds the class:
         * 0 → 1, 1 → 3, 2 → 5, 3 → 7. Unstable finances cap it at 3 whatever the appetite.
         */
        fun riskProfile(answers: QuestionnaireAnswers): RiskProfile {
            val score = minOf(answers.riskAppetite, answers.lossTolerance)
            var max = StrategyOption.MIN_RISK_CLASS + score * SCORE_TO_CLASS_STEP
            if (!answers.financialSituationStable) max = minOf(max, UNSTABLE_FINANCES_CAP)
            val label = when {
                max >= DYNAMIC_FROM -> RiskLabel.DYNAMIC
                max >= BALANCED_FROM -> RiskLabel.BALANCED
                else -> RiskLabel.CONSERVATIVE
            }
            return RiskProfile(max, label)
        }
    }
}

/** Why a strategy was or was not recommended, in machine-readable form for the client UI. */
enum class RecommendationReason {
    LIFECYCLE_DEFAULT,
    HIGHEST_SUITABLE_RISK,
    SUSTAINABLE_PREFERRED,
    NO_SUITABLE_STRATEGY_FALLBACK_MOST_CONSERVATIVE,
    NO_SUSTAINABLE_STRATEGY_AVAILABLE,
}

data class StrategyRecommendation(
    val recommended: String,
    val suitable: List<String>,
    val maxRiskClass: Int,
    val yearsToRetirement: Int,
    val reasons: List<RecommendationReason>,
)

/**
 * Strategy recommendation from the risk profile and the years to retirement (ADR-0334 §4 step 2).
 *
 * The cap is the lower of the profile's class and the pack's horizon cap. Among strategies at or
 * under it (and sustainable ones only, when the participant REQUIRES it), the pack's lifecycle
 * default wins when suitable; otherwise the highest-risk suitable strategy, preferring a
 * sustainable one when the participant asked to CONSIDER sustainability.
 */
object StrategyRecommender {

    fun recommend(
        rules: OnboardingRules,
        assessment: SuitabilityAssessment,
        birthDate: LocalDate,
        retirementAge: Int,
        today: LocalDate,
    ): StrategyRecommendation {
        val age = Period.between(birthDate, today).years
        val years = maxOf(0, retirementAge - age)
        val horizonCap = rules.horizonCaps.firstOrNull { years <= it.maxYears }?.maxRiskClass
            ?: StrategyOption.MAX_RISK_CLASS
        val cap = minOf(assessment.riskProfile.maxRiskClass, horizonCap)
        val reasons = mutableListOf<RecommendationReason>()
        val esg = assessment.esgPreference
        val byRisk = rules.strategies.filter { it.riskClass <= cap }
        val suitable = if (esg == EsgPreference.REQUIRED) {
            byRisk.filter { it.sustainable }.ifEmpty {
                reasons += RecommendationReason.NO_SUSTAINABLE_STRATEGY_AVAILABLE
                byRisk
            }
        } else {
            byRisk
        }
        val lifecycle = suitable.firstOrNull { it.code == rules.lifecycleDefault }
        val chosen = when {
            suitable.isEmpty() -> {
                reasons += RecommendationReason.NO_SUITABLE_STRATEGY_FALLBACK_MOST_CONSERVATIVE
                rules.strategies.minBy { it.riskClass }
            }
            lifecycle != null && (esg == EsgPreference.NONE || lifecycle.sustainable) -> {
                reasons += RecommendationReason.LIFECYCLE_DEFAULT
                lifecycle
            }
            esg == EsgPreference.CONSIDER && suitable.any { it.sustainable } -> {
                reasons += RecommendationReason.SUSTAINABLE_PREFERRED
                suitable.filter { it.sustainable }.maxBy { it.riskClass }
            }
            else -> {
                reasons += RecommendationReason.HIGHEST_SUITABLE_RISK
                suitable.maxBy { it.riskClass }
            }
        }
        return StrategyRecommendation(chosen.code, suitable.map { it.code }, cap, years, reasons)
    }
}
