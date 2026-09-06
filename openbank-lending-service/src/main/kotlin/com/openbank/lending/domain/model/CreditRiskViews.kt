// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.domain.model

import com.openbank.libs.decision.PolicyTable
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Read models for the credit-risk console (ADR-0230 D1 read view over the ADR-0213 D4 decision
 * evidence and the ADR-0028 Phase 3 provisioning history).
 *
 * WHY A SEPARATE SHAPE AND NOT THE AGGREGATES
 * `LoanApplication` persists the engine's output as the CSV strings the evidence event carries
 * (`decisionReasons = "CODE:ruleId,..."`, `policyVersions = "KIND=1,..."`). That is the right
 * shape for a hash-bound evidence record and the wrong one for a chart: a risk analyst wants the
 * reason CODE and the RULE as two columns, and the affordability ratios the engine actually read.
 * These views decode the evidence once, server-side, so every consumer (console, notebook export)
 * sees one decoding rather than each re-implementing the format.
 *
 * WHAT THEY DELIBERATELY DO NOT DO
 * No score, no probability, no ranking. The ADR-0213 output contract is outcome + reasons + rule
 * ids + versions + input hash, and a read model must not invent a number the engine never
 * produced. The affordability ratios are the exact figures the ASSESSMENT leg computed
 * (`OriginationDecisionService.affordabilityRatios`), never a re-estimate.
 */
data class DecisionReasonView(val code: String, val ruleId: String?)

/**
 * DSTI/DTI as the engine reads them plus the total-debt-service DSTI the engine does NOT read.
 *
 * `dsti` is `new installment / verified income` — the figure `PolicyAttribute.DSTI` carries into
 * the affordability table today. `dstiIncludingExistingDebt` adds `existingDebtServiceMonthly`,
 * the CNB/EBA definition of debt-service-to-income. Both are exposed so the console can show the
 * gap rather than hide it; changing which one the ENGINE uses is a credit-policy decision
 * (ADR-0213 D4), not a read-model change.
 */
data class AffordabilityRatios(val dsti: BigDecimal, val dti: BigDecimal, val dstiIncludingExistingDebt: BigDecimal)

/** One engine-evaluated application, decoded from its pinned evidence fields. */
data class CreditDecisionView(
    val applicationId: UUID,
    val partyId: UUID,
    val status: String,
    val createdAt: OffsetDateTime,
    val requestedAmount: BigDecimal,
    val currency: String,
    val termPeriods: Int,
    val nominalAnnualRate: BigDecimal,
    val jurisdiction: String?,
    val productType: String?,
    val productKind: String,
    val packVersion: Int?,
    /** `APPROVE` / `REFER` / `DECLINE` (ADR-0213 D1). */
    val engineOutcome: String,
    val priceBand: String?,
    val reasons: List<DecisionReasonView>,
    val matchedRuleIds: List<String>,
    /** Pinned table version per `PolicyTableKind` name. */
    val policyVersions: Map<String, Int>,
    val inputSnapshotHash: String?,
    val decidedEngineAt: OffsetDateTime?,
    val affordability: AffordabilityRatios?,
    val verifiedIncomeMonthly: BigDecimal?,
    val existingDebtServiceMonthly: BigDecimal?,
    val ageYears: Int?,
    val residency: String?,
    val employmentTenureMonths: Int?,
    /** The four-eyes checker's disposition, when one has been recorded (ADR-0028 D5). */
    val humanDecidedBy: String?,
    val humanDecisionReason: String?,
    val humanDecidedAt: OffsetDateTime?,
)

/** Book-wide engine-outcome totals, grouped in the database so the console never sums a capped list. */
data class DecisionOutcomeSummary(val engineOutcome: String, val priceBand: String?, val count: Long)

/** The latest persisted IFRS 9 record for one loan (`loan_provisioning`, one row per loan-period). */
data class LoanRiskAssessmentView(
    val period: String,
    val asOf: LocalDate,
    val outstandingBalance: BigDecimal,
    val daysPastDue: Int,
    val bucket: String,
    val stage: String,
    val expectedCreditLoss: BigDecimal,
    val modelVersion: String,
)

/**
 * One loan with its latest provisioning record. `assessment` is null for a loan the scheduled
 * cycle has never assessed — a real state the console must show as "not yet assessed", never as
 * Stage 1 with zero ECL.
 */
data class LoanRiskView(
    val loanId: UUID,
    val applicationId: UUID,
    val partyId: UUID,
    val status: String,
    val principal: BigDecimal,
    val currency: String,
    val nominalAnnualRate: BigDecimal,
    val termPeriods: Int,
    val disbursedAt: OffsetDateTime,
    val assessment: LoanRiskAssessmentView?,
)

/**
 * The policy bundle the engine would evaluate as of [asOf]. [codeSeeded] is true while the bundle
 * comes from `StarterCreditPolicy` (ADR-0213 D3 phase 1) rather than the four-eyes-governed table
 * store D4 describes — the console says so, because a risk committee reading a decision table
 * must know whether anyone can change it and how.
 */
data class CreditPolicyView(
    val asOf: LocalDate,
    val source: String,
    val codeSeeded: Boolean,
    val tables: List<PolicyTable>,
)

/**
 * Decodes the CSV evidence fields exactly as `OriginationDecisionService` writes them. Pure and
 * total: a malformed fragment yields a best-effort entry rather than a throw, because a read model
 * must never make a persisted decision unreadable.
 */
object DecisionEvidenceCodec {
    private const val NO_RULE = "-"

    fun reasons(csv: String?): List<DecisionReasonView> = fragments(csv).map { fragment ->
        val split = fragment.indexOf(':')
        if (split < 0) {
            DecisionReasonView(code = fragment, ruleId = null)
        } else {
            val rule = fragment.substring(split + 1)
            DecisionReasonView(
                code = fragment.substring(0, split),
                ruleId = rule.takeUnless { it == NO_RULE || it.isBlank() },
            )
        }
    }

    fun matchedRuleIds(csv: String?): List<String> = fragments(csv)

    fun policyVersions(csv: String?): Map<String, Int> = fragments(csv).mapNotNull { fragment ->
        val split = fragment.indexOf('=')
        if (split < 0) {
            null
        } else {
            val kind = fragment.substring(0, split).trim()
            val version = fragment.substring(split + 1).trim().toIntOrNull()
            if (kind.isEmpty() || version == null) null else kind to version
        }
    }.toMap()

    private fun fragments(csv: String?): List<String> =
        csv.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
