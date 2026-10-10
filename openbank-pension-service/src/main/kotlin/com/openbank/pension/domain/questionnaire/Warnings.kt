// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.questionnaire

import com.openbank.pension.domain.onboarding.AssessmentStatus
import com.openbank.pension.domain.onboarding.EsgPreference
import com.openbank.pension.domain.onboarding.OnboardingRules
import com.openbank.pension.domain.onboarding.RecommendationReason
import com.openbank.pension.domain.onboarding.StrategyRecommendation
import com.openbank.pension.domain.onboarding.SuitabilityAssessment
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Warnings the participant must read and acknowledge before signing (issue #12384).
 *
 * The wording is DATA in the question set; the code names the duty, not a country:
 *  - [STRATEGY_ABOVE_PROFILE]: the chosen strategy is riskier than the profile supports — the
 *    pension-savings warning-and-recommend duty (CZ: ZDPS § 136(3)). The choice stays the
 *    participant's where the pack allows it; it is never blocked silently.
 *  - [PRODUCT_NOT_APPROPRIATE]: knowledge/experience below the product's threshold (MiFID II
 *    Art. 25(3) appropriateness warning).
 *  - [SUSTAINABILITY_PREFERENCE_NOT_MET]: no offered strategy meets the stated preference, so the
 *    recommendation ignores it (Delegated Reg. 2017/565 Art. 54(10) as amended — the client may
 *    adapt the preference; that adaptation is what the acknowledgement records).
 */
enum class WarningCode { STRATEGY_ABOVE_PROFILE, PRODUCT_NOT_APPROPRIATE, SUSTAINABILITY_PREFERENCE_NOT_MET }

/**
 * Evidence that the participant saw THIS wording for THIS strategy under THIS assessment. The
 * text hash binds the acknowledgement to the words shown — a later wording change does not
 * retroactively turn it into an acknowledgement of something else.
 */
data class WarningAcknowledgement(
    val code: WarningCode,
    val assessmentId: UUID,
    val strategyCode: String,
    val textSha256: String,
    val language: String,
    val acknowledgedAt: Instant,
)

object WarningPolicy {

    /** The warnings a choice of [strategyCode] requires under [assessment]. */
    fun required(
        strategyCode: String,
        assessment: SuitabilityAssessment,
        recommendation: StrategyRecommendation,
    ): Set<WarningCode> = buildSet {
        if (strategyCode !in recommendation.suitable && strategyCode != recommendation.recommended) {
            add(WarningCode.STRATEGY_ABOVE_PROFILE)
        }
        if (assessment.appropriate == false) add(WarningCode.PRODUCT_NOT_APPROPRIATE)
        if (assessment.esgPreference == EsgPreference.REQUIRED &&
            RecommendationReason.NO_SUSTAINABLE_STRATEGY_AVAILABLE in recommendation.reasons
        ) {
            add(WarningCode.SUSTAINABILITY_PREFERENCE_NOT_MET)
        }
    }

    /**
     * Whether the warning may be overridden by acknowledgement at all. A riskier strategy under a
     * regime that forbids it (MiFID advice: an unsuitable product is not recommended) is refused.
     */
    fun overridable(code: WarningCode, rules: OnboardingRules): Boolean = when (code) {
        WarningCode.STRATEGY_ABOVE_PROFILE -> rules.questionnaire.allowUnsuitableWithWarning
        WarningCode.PRODUCT_NOT_APPROPRIATE, WarningCode.SUSTAINABILITY_PREFERENCE_NOT_MET -> true
    }

    fun missing(
        required: Set<WarningCode>,
        acknowledgements: List<WarningAcknowledgement>,
        assessmentId: UUID,
        strategyCode: String,
        textFor: (WarningCode, String) -> String,
        language: String? = null,
    ): Set<WarningCode> = required - acknowledgements
        .filter {
            it.assessmentId == assessmentId &&
                it.strategyCode == strategyCode &&
                it.language in setOf("cs", "en") &&
                (language == null || it.language == language) &&
                it.textSha256 == sha256(textFor(it.code, it.language))
        }
        .map { it.code }
        .toSet()

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Why an assessment must be answered again. */
enum class RefreshReason { EXPIRED, SUPERSEDED, STRATEGY_CHANGE_ABOVE_PROFILE, LIFE_EVENT, NO_ASSESSMENT }

/** Events the participant reports that invalidate the profile (ESMA: keep information up to date). */
enum class LifeEvent {
    JOB_LOSS,
    INCOME_CHANGE,
    MARRIAGE_OR_DIVORCE,
    CHILD,
    INHERITANCE,
    HEALTH,
    RETIREMENT_PLAN_CHANGE,
}

/**
 * Re-assessment policy. An assessment is valid for the pack's validity period; a strategy change
 * above its class, or a reported life event, makes it stale earlier. A stale assessment is never
 * edited: answering again SUPERSEDES it and both stay on record for audit.
 */
object ReassessmentPolicy {

    fun refreshReason(
        assessment: SuitabilityAssessment,
        today: LocalDate,
        requestedStrategyRiskClass: Int? = null,
        lifeEvent: LifeEvent? = null,
    ): RefreshReason? = when {
        assessment.status != AssessmentStatus.CURRENT -> RefreshReason.SUPERSEDED
        today.isAfter(assessment.validUntil) -> RefreshReason.EXPIRED
        lifeEvent != null -> RefreshReason.LIFE_EVENT
        requestedStrategyRiskClass != null && requestedStrategyRiskClass > assessment.riskProfile.maxRiskClass ->
            RefreshReason.STRATEGY_CHANGE_ABOVE_PROFILE
        else -> null
    }
}
