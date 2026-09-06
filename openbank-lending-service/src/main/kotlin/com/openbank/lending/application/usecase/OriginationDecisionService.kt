// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.openbank.lending.application.port.out.CreditAssessment
import com.openbank.lending.application.port.out.CreditBureauPort
import com.openbank.lending.application.port.out.CreditPolicyPort
import com.openbank.lending.application.port.out.LendingOutboxMessage
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.infrastructure.compliance.CompliancePackGuard
import com.openbank.libs.decision.PolicyApplication
import com.openbank.libs.decision.PolicyAttribute
import com.openbank.libs.decision.PolicyBundle
import com.openbank.libs.decision.PolicyDecision
import com.openbank.libs.decision.PolicyEvaluator
import com.openbank.libs.decision.PolicyTableKind
import com.openbank.libs.decision.PolicyValue
import com.openbank.libs.lending.Amortization
import com.openbank.libs.lending.compliance.CompliancePackEvaluator
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.math.MathContext
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime

private const val MONTHS_IN_YEAR = 12L

/** The engine outcome the origination flow acts on: the recorded application, the decision and its evidence. */
data class DecisionOutcome(
    val recorded: LoanApplication,
    val decision: PolicyDecision,
    val evidence: LendingOutboxMessage,
    val declined: Boolean,
)

/**
 * The ADR-0213 deterministic credit evaluation, wired at the ASSESSMENT step: builds
 * the typed attribute set (application facts, computed DSTI/DTI from the amortization
 * schedule, the bureau port's adverse flag), merges the pinned pack's mandatoryChecks
 * into ELIGIBILITY, and runs the pure evaluator. The full output contract — outcome,
 * price band, reason codes, matched rules, versions, input snapshot hash — rides back
 * for persistence and `credit.decision.evaluated` evidence (ADR-0214).
 *
 * Input completeness is part of the contract, not a convenience: any attribute the
 * pinned policy needs that the application does not carry (income, residency, age)
 * makes the evaluator fail closed to REFER (ADR-0213 D2) — never to a silent approve.
 * The DSTI/DTI pair is computed from the regenerated amortization schedule, so the
 * affordability number the floor reads is identical to the one the offer discloses.
 */
@ApplicationScoped
class OriginationDecisionService(
    private val bureau: CreditBureauPort,
    private val creditPolicy: CreditPolicyPort,
    private val complianceGuard: CompliancePackGuard,
    private val clock: Clock,
) {

    fun evaluate(application: LoanApplication): Uni<DecisionOutcome> =
        bureau.assess(application.partyId, application.requestedAmount).flatMap { assessment ->
            creditPolicy.activeBundle(LocalDate.now(clock)).map { bundle ->
                val withPackChecks = mergePackChecks(application, bundle)
                val decision = PolicyEvaluator.evaluate(
                    PolicyApplication(attributes(application, assessment), LocalDate.now(clock)),
                    withPackChecks,
                )
                val recorded = application.copy(
                    decisionOutcome = outcomeName(decision),
                    decisionPriceBand = (decision as? PolicyDecision.Approve)?.priceBand,
                    decisionReasons = decision.evaluation.reasons.joinToString(",") {
                        "${it.code}:${it.ruleId ?: "-"}"
                    },
                    decisionMatchedRules = decision.evaluation.matchedRuleIds.joinToString(","),
                    policyVersions = decision.evaluation.policyVersions.entries.joinToString(",") {
                        "${it.key}=${it.value}"
                    },
                    decisionInputHash = decision.evaluation.inputSnapshotHash,
                    decidedEngineAt = OffsetDateTime.now(clock),
                )
                DecisionOutcome(
                    recorded = recorded,
                    decision = decision,
                    evidence = evidence(recorded, decision),
                    declined = decision is PolicyDecision.Decline,
                )
            }
        }

    private fun mergePackChecks(application: LoanApplication, bundle: PolicyBundle): PolicyBundle {
        val pack = complianceGuard.resolveOriginationPack(application.jurisdiction, application.productType)
            ?: return bundle
        val checks = CompliancePackEvaluator.mandatoryEligibilityRules(pack)
        if (checks.isEmpty()) return bundle
        return PolicyBundle(
            bundle.tables.map { table ->
                if (table.kind == PolicyTableKind.ELIGIBILITY) table.copy(rules = table.rules + checks) else table
            },
        )
    }

    private fun attributes(
        application: LoanApplication,
        assessment: CreditAssessment,
    ): Map<PolicyAttribute, PolicyValue> {
        val attributes = mutableMapOf<PolicyAttribute, PolicyValue>()
        application.verifiedIncomeMonthly?.let {
            attributes[PolicyAttribute.VERIFIED_INCOME_MONTHLY] = PolicyValue.Numeric(it.amount)
        }
        application.existingDebtServiceMonthly?.let {
            attributes[PolicyAttribute.EXISTING_DEBT_SERVICE_MONTHLY] = PolicyValue.Numeric(it.amount)
        }
        application.ageYears?.let {
            attributes[PolicyAttribute.AGE_YEARS] = PolicyValue.Numeric(BigDecimal(it))
        }
        application.residency?.let {
            attributes[PolicyAttribute.RESIDENCY] = PolicyValue.Text(it)
        }
        application.employmentTenureMonths?.let {
            attributes[PolicyAttribute.EMPLOYMENT_TENURE_MONTHS] = PolicyValue.Numeric(BigDecimal(it))
        }
        attributes[PolicyAttribute.CUSTOMER_TYPE] =
            PolicyValue.Text(if (assessment.hasAdverseData) "ADVERSE_BUREAU" else "STANDARD")
        application.jurisdiction?.let {
            attributes[PolicyAttribute.JURISDICTION] = PolicyValue.Text(it)
        }
        application.productType?.let {
            attributes[PolicyAttribute.PRODUCT_TYPE] = PolicyValue.Text(it)
        }
        attributes[PolicyAttribute.REQUESTED_AMOUNT] = PolicyValue.Numeric(application.requestedAmount.amount)
        application.verifiedIncomeMonthly?.takeIf { it.isPositive() }?.let { income ->
            val schedule = Amortization.schedule(
                principal = application.requestedAmount,
                nominalAnnualRate = application.nominalAnnualRate,
                termPeriods = application.termPeriods,
                periodsPerYear = application.periodsPerYear,
                firstDueDate = application.firstDueDate,
            )
            val monthlyPayment = schedule.installments.first().payment.amount
            attributes[PolicyAttribute.DSTI] =
                PolicyValue.Numeric(monthlyPayment.divide(income.amount, MathContext.DECIMAL128))
            attributes[PolicyAttribute.DTI] = PolicyValue.Numeric(
                application.requestedAmount.amount.divide(
                    income.amount.multiply(BigDecimal(MONTHS_IN_YEAR)),
                    MathContext.DECIMAL128,
                ),
            )
        }
        return attributes
    }

    private fun outcomeName(decision: PolicyDecision): String = when (decision) {
        is PolicyDecision.Approve -> "APPROVE"
        is PolicyDecision.Refer -> "REFER"
        is PolicyDecision.Decline -> "DECLINE"
    }

    private fun evidence(application: LoanApplication, decision: PolicyDecision): LendingOutboxMessage {
        val id = application.id.value
        val evaluation = decision.evaluation
        val payload = buildString {
            append("""{"eventType":"credit.decision.evaluated",""")
            append(""""aggregateType":"LOAN_APPLICATION",""")
            append(""""aggregateId":"$id",""")
            append(""""loanApplicationId":"$id",""")
            append(""""partyId":"${application.partyId}",""")
            append(""""outcome":"${outcomeName(decision)}",""")
            append(""""priceBand":${application.decisionPriceBand?.let { band -> "\"$band\"" } ?: "null"},""")
            append(""""policyVersions":"${application.policyVersions}",""")
            append(""""matchedRuleIds":"${application.decisionMatchedRules}",""")
            append(""""reasons":"${application.decisionReasons}",""")
            append(""""inputSnapshotHash":"${evaluation.inputSnapshotHash}",""")
            append(""""packVersion":${application.packVersion ?: "null"},""")
            append(""""occurredAt":"${clock.instant()}",""")
            append(""""correlationId":"$id",""")
            append(""""sourceService":"lending"}""")
        }
        return LendingOutboxMessage(aggregateId = id, eventType = "credit.decision.evaluated", payload = payload)
    }
}
