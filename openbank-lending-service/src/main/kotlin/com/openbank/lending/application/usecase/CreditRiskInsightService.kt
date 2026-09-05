// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.openbank.lending.application.port.`in`.CreditRiskInsightUseCase
import com.openbank.lending.application.port.out.CreditPolicyPort
import com.openbank.lending.application.port.out.LoanApplicationRepository
import com.openbank.lending.application.port.out.LoanRepository
import com.openbank.lending.application.port.out.ProvisioningRepository
import com.openbank.lending.application.port.out.StarterCreditPolicy
import com.openbank.lending.domain.model.CreditDecisionView
import com.openbank.lending.domain.model.CreditPolicyView
import com.openbank.lending.domain.model.DecisionEvidenceCodec
import com.openbank.lending.domain.model.DecisionOutcomeSummary
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.domain.model.LoanProvisioningRecord
import com.openbank.lending.domain.model.LoanRiskAssessmentView
import com.openbank.lending.domain.model.LoanRiskView
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.time.LocalDate

/**
 * The credit-risk read side (ADR-0230 D1). Composes what the origination and servicing paths
 * already persist — the pinned ADR-0213 evidence on `loan_application`, the ADR-0028 Phase 3
 * rows in `loan_provisioning` — into the views the console and a notebook consume. Nothing here
 * computes a decision, an ECL or a stage: those numbers are read back from the records the
 * governed paths wrote, so the console can never disagree with the evidence chain.
 */
@ApplicationScoped
class CreditRiskInsightService(
    private val applications: LoanApplicationRepository,
    private val loans: LoanRepository,
    private val provisioning: ProvisioningRepository,
    private val creditPolicy: CreditPolicyPort,
) : CreditRiskInsightUseCase {

    override fun decisions(limit: Int): Uni<List<CreditDecisionView>> =
        applications.findEvaluated(limit.coerceIn(1, MAX_LIMIT)).map { rows -> rows.map(::toView) }

    override fun summariseDecisions(): Uni<List<DecisionOutcomeSummary>> = applications.summariseDecisions()

    override fun portfolio(limit: Int): Uni<List<LoanRiskView>> =
        loans.findRecent(limit.coerceIn(1, MAX_LIMIT)).flatMap { book ->
            provisioning.findLatestPerLoan().map { latest ->
                val byLoan = latest.associateBy { it.loanId }
                book.map { loan -> toView(loan, byLoan[loan.id]) }
            }
        }

    override fun activePolicy(asOf: LocalDate): Uni<CreditPolicyView> = creditPolicy.activeBundle(asOf).map { bundle ->
        CreditPolicyView(
            asOf = asOf,
            source = policySource(),
            codeSeeded = creditPolicy is StarterCreditPolicy,
            tables = bundle.tables,
        )
    }

    /** The binding's own class name, with the CDI proxy/subclass suffixes stripped. */
    private fun policySource(): String =
        creditPolicy::class.java.simpleName.removeSuffix("_ClientProxy").removeSuffix("_Subclass")

    private fun toView(a: LoanApplication): CreditDecisionView = CreditDecisionView(
        applicationId = a.id.value,
        partyId = a.partyId,
        status = a.status.name,
        createdAt = a.createdAt,
        requestedAmount = a.requestedAmount.amount,
        currency = a.requestedAmount.currency.code,
        termPeriods = a.termPeriods,
        nominalAnnualRate = a.nominalAnnualRate,
        jurisdiction = a.jurisdiction,
        productType = a.productType,
        productKind = a.productKind.name,
        packVersion = a.packVersion,
        engineOutcome = a.decisionOutcome ?: UNEVALUATED,
        priceBand = a.decisionPriceBand,
        reasons = DecisionEvidenceCodec.reasons(a.decisionReasons),
        matchedRuleIds = DecisionEvidenceCodec.matchedRuleIds(a.decisionMatchedRules),
        policyVersions = DecisionEvidenceCodec.policyVersions(a.policyVersions),
        inputSnapshotHash = a.decisionInputHash,
        decidedEngineAt = a.decidedEngineAt,
        affordability = OriginationDecisionService.affordabilityRatios(a),
        verifiedIncomeMonthly = a.verifiedIncomeMonthly?.amount,
        existingDebtServiceMonthly = a.existingDebtServiceMonthly?.amount,
        ageYears = a.ageYears,
        residency = a.residency,
        employmentTenureMonths = a.employmentTenureMonths,
        humanDecidedBy = a.decidedBy,
        humanDecisionReason = a.decisionReason,
        humanDecidedAt = a.decidedAt,
    )

    private fun toView(loan: Loan, latest: LoanProvisioningRecord?): LoanRiskView = LoanRiskView(
        loanId = loan.id.value,
        applicationId = loan.applicationId.value,
        partyId = loan.partyId,
        status = loan.status.name,
        principal = loan.principal.amount,
        currency = loan.principal.currency.code,
        nominalAnnualRate = loan.nominalAnnualRate,
        termPeriods = loan.termPeriods,
        disbursedAt = loan.disbursedAt,
        assessment = latest?.let { r ->
            LoanRiskAssessmentView(
                period = r.period,
                asOf = r.asOf,
                outstandingBalance = r.outstandingBalance.amount,
                daysPastDue = r.daysPastDue,
                bucket = r.bucket.name,
                stage = r.stage.name,
                expectedCreditLoss = r.expectedCreditLoss.amount,
                modelVersion = r.modelVersion,
            )
        },
    )

    companion object {
        /** Upper bound on one read; a notebook wanting more pages by `limit` is the wrong tool — use the warehouse. */
        const val MAX_LIMIT = 1000

        /** Never expected from `findEvaluated` (it filters on the engine timestamp); kept total, not a `!!`. */
        const val UNEVALUATED = "UNEVALUATED"
    }
}
