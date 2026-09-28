// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending.compliance

import com.openbank.libs.decision.PolicyRule
import com.openbank.libs.lending.origination.OriginationState
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Jurisdictional credit compliance pack (ADR-0212 D1): the legal duties of one
 * (jurisdiction, productType) pair as versioned, effective-dated, declarative data.
 * Authored as reviewed JSON/YAML in the repo, activated at runtime four-eyes
 * (ADR-0212 D4) — adding a jurisdiction is data, not a service release.
 */
data class CompliancePack(
    val jurisdiction: String,
    val productType: PackProductType,
    val version: Int,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate? = null,
    val requiredSteps: Set<OriginationState> = emptySet(),
    val coolingOffDays: Int,
    val reflectionPeriodDays: Int? = null,
    val aprDisclosure: AprDisclosure,
    val earlyRepaymentCompensationCap: BigDecimal? = null,
    val terminationRules: TerminationRules,
    val disclosures: List<PackDisclosure> = emptyList(),
    val mandatoryChecks: List<PolicyRule> = emptyList(),
) {
    fun isEffectiveOn(asOf: LocalDate): Boolean =
        !asOf.isBefore(effectiveFrom) && (effectiveTo == null || asOf.isBefore(effectiveTo))
}

enum class PackProductType { CONSUMER_CREDIT, MORTGAGE, OVERDRAFT, SME_CREDIT }

/**
 * The pricing-disclosure label and locale, never the math: the APRC formula is the
 * single harmonised CCD2 Annex I computation in libs; RPSN / effektiver Jahreszins /
 * APR are national-language disclosures of the same number (ADR-0212 D1).
 */
data class AprDisclosure(val label: String, val locale: String)

enum class TerminationGround { DEFAULT_DPD, MATERIAL_BREACH, INSOLVENCY, FRAUD, OTHER_STATUTORY }

/**
 * Bank-initiated termination parameters (ADR-0215). [defaultDpdThreshold] defaults to
 * the ČNB 90-day election; CRR Art. 178 permits a 180-day national discretion for some
 * retail/PSE classes, so the threshold is data, not a constant.
 */
data class TerminationRules(
    val noticePeriodDays: Int,
    val permittedGrounds: Set<TerminationGround>,
    val defaultDpdThreshold: Int = 90,
)

enum class DisclosureStage { PRE_CONTRACTUAL, CONTRACTUAL, PRE_TERMINATION }

/** One mandatory document the pack requires (SECCI, contract, termination notice, …). */
data class PackDisclosure(
    val id: String,
    val templateKey: String,
    val languages: Set<String>,
    val requiresAcknowledgement: Boolean,
    val stage: DisclosureStage,
)
