// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.onboarding

import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.pack.LegalReview

/**
 * The onboarding and transfer part of a jurisdiction pack (ADR-0334 §3/§4, slice S2).
 *
 * An ADDITIVE extension: it lives in its own file per pack version
 * (`jurisdiction-packs/onboarding/<pack>.json`) and is keyed by the core pack's
 * `(jurisdiction, productLine, version)`, so the core [com.openbank.pension.domain.pack.JurisdictionPack]
 * schema is untouched. The loader refuses to boot when a core pack has no extension, so a
 * jurisdiction can never be onboarded under rules the domain had to guess.
 *
 * Every value is statutory or policy DATA and carries [legalReview]; no country literal appears in
 * Kotlin.
 */
data class OnboardingRules(
    val jurisdiction: String,
    val productLine: ProductLine,
    val packVersion: Int,
    val legalReview: LegalReview,
    /** Days after signature during which the participant may withdraw without giving a reason. */
    val coolingOffDays: Int,
    val activation: ActivationRule,
    /** An unsigned application lapses after this many days of inactivity. */
    val applicationExpiryDays: Int,
    val questionnaire: QuestionnaireRules,
    val keyInformationDocument: KeyInformationDocumentRule,
    val strategies: List<StrategyOption>,
    /** The lifecycle strategy recommended by default when it is suitable (ADR-0334 §1). */
    val lifecycleDefault: String? = null,
    /** Investment-horizon caps on the risk class, ascending by [HorizonCap.maxYears]. */
    val horizonCaps: List<HorizonCap> = emptyList(),
    /** A participant without full legal capacity needs a guardian, as a minor does. */
    val guardianRequiredForLimitedCapacity: Boolean = true,
    val transferIn: TransferInRules,
) {
    init {
        require(jurisdiction.isNotBlank()) { "onboarding rules jurisdiction must not be blank" }
        require(packVersion >= 1) { "onboarding rules packVersion must be >= 1" }
        require(coolingOffDays >= 0) { "coolingOffDays must be >= 0" }
        require(applicationExpiryDays > 0) { "applicationExpiryDays must be > 0" }
        require(strategies.isNotEmpty()) { "onboarding rules must offer at least one strategy" }
        require(strategies.map { it.code }.toSet().size == strategies.size) { "strategy codes must be unique" }
        require(lifecycleDefault == null || strategies.any { it.code == lifecycleDefault && it.lifecycle }) {
            "lifecycleDefault must name a lifecycle strategy of this pack"
        }
        require(horizonCaps.zipWithNext().all { (a, b) -> a.maxYears < b.maxYears }) {
            "horizonCaps must be strictly ascending by maxYears"
        }
    }

    fun strategy(code: String): StrategyOption? = strategies.firstOrNull { it.code == code }
}

/** What turns a signed, PENDING_ACTIVATION contract ACTIVE. */
enum class ActivationTrigger { FIRST_CONTRIBUTION, SIGNATURE }

data class ActivationRule(
    val trigger: ActivationTrigger,
    /** For [ActivationTrigger.FIRST_CONTRIBUTION]: a contract never funded lapses after this many days. */
    val deadlineDays: Int,
) {
    init {
        require(deadlineDays > 0) { "activation deadlineDays must be > 0" }
    }
}

/**
 * Which assessment the product line requires. `MIFID_SUITABILITY` is the full investment-advice
 * regime (knowledge, experience, financial situation, objectives, risk and sustainability
 * preferences); `RISK_PROFILE_ONLY` is the lighter profile a pension-fund product line uses.
 */
enum class QuestionnaireRegime { MIFID_SUITABILITY, RISK_PROFILE_ONLY }

data class QuestionnaireRules(
    val regime: QuestionnaireRegime,
    /** Whether an appropriateness test (knowledge + experience) is run. */
    val appropriatenessTest: Boolean,
    /** Minimum knowledge + experience score (each 0..3) below which the product is not appropriate. */
    val appropriatenessMinScore: Int = 0,
    /** Whether the sustainability preference must be answered. */
    val esgPreferenceRequired: Boolean,
    /** Whether a strategy above the suitable risk class may be chosen with an acknowledged warning. */
    val allowUnsuitableWithWarning: Boolean,
    /** An assessment is reusable for this many days before it must be re-answered. */
    val validityDays: Int,
) {
    init {
        require(appropriatenessMinScore in 0..MAX_COMBINED_SCORE) { "appropriatenessMinScore must be in 0..6" }
        require(validityDays > 0) { "questionnaire validityDays must be > 0" }
    }

    private companion object {
        const val MAX_COMBINED_SCORE = 6
    }
}

enum class KeyInformationDocumentType { PRIIPS_KID, PEPP_KID, PRE_CONTRACTUAL_INFORMATION }

data class KeyInformationDocumentRule(val type: KeyInformationDocumentType, val templateCode: String) {
    init {
        require(templateCode.isNotBlank()) { "key-information templateCode must not be blank" }
    }
}

/**
 * One strategy offered under the pack. [riskClass] is the 1..7 summary risk indicator; for a
 * [lifecycle] strategy it is the class at the START of the glide path, which is what a long horizon
 * is matched against.
 */
data class StrategyOption(
    val code: String,
    val riskClass: Int,
    val lifecycle: Boolean = false,
    val sustainable: Boolean = false,
) {
    init {
        require(code.isNotBlank()) { "strategy code must not be blank" }
        require(riskClass in MIN_RISK_CLASS..MAX_RISK_CLASS) { "strategy $code riskClass must be in 1..7" }
    }

    companion object {
        const val MIN_RISK_CLASS = 1
        const val MAX_RISK_CLASS = 7
    }
}

/** Up to [maxYears] to retirement, no strategy above [maxRiskClass] is suitable. */
data class HorizonCap(val maxYears: Int, val maxRiskClass: Int) {
    init {
        require(maxYears >= 0) { "horizon cap maxYears must be >= 0" }
        require(maxRiskClass in StrategyOption.MIN_RISK_CLASS..StrategyOption.MAX_RISK_CLASS) {
            "horizon cap maxRiskClass must be in 1..7"
        }
    }
}

data class TransferInRules(
    /** After the ceding provider accepted, how long the funds may take to arrive. */
    val fundsGraceDays: Int,
) {
    init {
        require(fundsGraceDays > 0) { "transferIn fundsGraceDays must be > 0" }
    }
}
