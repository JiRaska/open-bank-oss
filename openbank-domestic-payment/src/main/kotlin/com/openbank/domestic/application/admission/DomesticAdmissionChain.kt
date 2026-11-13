// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.admission

import com.openbank.domestic.application.port.out.AccountStandingLookup
import com.openbank.domestic.application.port.out.AccountStandingPort
import com.openbank.domestic.application.port.out.FraudEnforcementMetricsPort
import com.openbank.domestic.application.port.out.FraudScoreCommand
import com.openbank.domestic.application.port.out.FraudScoringPort
import com.openbank.domestic.application.port.out.SanctionsScreeningPort
import com.openbank.domestic.application.port.out.ScreeningUnavailableException
import com.openbank.domestic.application.workflow.FraudGateAction
import com.openbank.domestic.application.workflow.FraudGatePolicy
import com.openbank.domestic.application.workflow.FraudHoldBasis
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticTransferScope
import com.openbank.libs.fincrime.FraudVerdict
import com.openbank.libs.fincrime.ScreeningDecision
import com.openbank.libs.fincrime.ScreeningMatchStatus
import com.openbank.libs.fincrime.ScreeningPolicy
import com.openbank.libs.fincrime.ScreeningResult
import com.openbank.libs.fincrime.ScreeningRole
import com.openbank.libs.fincrime.admission.AdmissionDecision
import com.openbank.libs.fincrime.admission.AdmissionEvidence
import com.openbank.libs.fincrime.admission.AdmissionReasonCode
import com.openbank.libs.fincrime.admission.AdmissionResult
import com.openbank.libs.fincrime.admission.AdmissionStep
import com.openbank.libs.fincrime.admission.PaymentAdmissionChain
import com.openbank.libs.fincrime.admission.StepOutcome
import com.openbank.libs.fincrime.admission.UnavailableCause
import com.openbank.libs.fincrime.admission.UnavailablePolicy
import com.openbank.libs.observability.DomainMetrics
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration

/**
 * The domestic rail's binding of the ADR-0374 admission chain.
 *
 * The rail supplies adapters and nothing else: the order, the severity algebra and the rule that a
 * control which cannot answer never admits belong to [PaymentAdmissionChain]. Every step is either
 * bound here or declared unbound with the reason it is; the kernel refuses to build a chain that
 * forgets one.
 *
 *  - `AUTHORITY` unbound: the rail holds no grant facts. Delegated authority is decided by the
 *    customer edge until the shared facts source (ADR-0375 D1/D2) is wired into the rail.
 *  - `STANDING` bound: account-service's answer, read with the account (ADR-0374 D4).
 *  - `LIMITS` unbound: owner limits do not exist yet (ADR-0374 delivery step B).
 *  - `SANCTIONS` bound: the ADR-0032 screen, with the same due-diligence scope rules as before.
 *  - `AML_DESTINATION_RISK` unbound: ADR-0374 delivery step E.
 *  - `FRAUD` bound: the ADR-0084 score. While `openbank.domestic.fraud.enforcement-enabled` is
 *    off the score is computed and counted but the step reports itself not applicable. It never
 *    reports a shadow score as an ALLOW.
 *  - `PAYEE_VERIFICATION` unbound: the domestic scheme does not require it (ADR-0171).
 *
 * A domestic payment has no scheme deadline, so an unavailable control holds
 * ([UnavailablePolicy.HOLD]); the budget only bounds how long one admission may take.
 */
@ApplicationScoped
class DomesticAdmissionChain(
    private val standingPort: AccountStandingPort,
    private val screeningPort: SanctionsScreeningPort,
    private val fraudScoringPort: FraudScoringPort,
    private val fraudMetrics: FraudEnforcementMetricsPort,
    private val metrics: DomainMetrics,
    @ConfigProperty(name = "openbank.domestic.fraud.enforcement-enabled", defaultValue = "false")
    private val fraudEnforcementEnabled: Boolean,
    @ConfigProperty(name = "openbank.domestic.admission.budget", defaultValue = "PT30S")
    private val budget: Duration,
) {
    private val chain: PaymentAdmissionChain<DomesticPayment> =
        PaymentAdmissionChain.Builder<DomesticPayment>(budget, UnavailablePolicy.HOLD)
            .unbound(AdmissionStep.AUTHORITY, AUTHORITY_DECIDED_AT_EDGE)
            .check(AdmissionStep.STANDING) { payment, remaining -> standingWithin(payment, remaining) }
            .unbound(AdmissionStep.LIMITS, LIMITS_NOT_DELIVERED)
            .check(AdmissionStep.SANCTIONS) { payment, remaining -> sanctions(payment, remaining) }
            .unbound(AdmissionStep.AML_DESTINATION_RISK, AML_DESTINATION_RISK_NOT_DELIVERED)
            .check(AdmissionStep.FRAUD) { payment, remaining -> fraud(payment, remaining) }
            .unbound(AdmissionStep.PAYEE_VERIFICATION, PAYEE_VERIFICATION_NOT_REQUIRED)
            .build()

    suspend fun admit(payment: DomesticPayment): AdmissionResult = chain.admit(payment)

    /**
     * The standing step alone, for the re-run a release makes before it lets a payment go.
     *
     * Not a default argument on [standingWithin]: this bean is called through a client proxy, and a
     * default that reads a field is evaluated on the PROXY, whose fields are null.
     */
    suspend fun standing(payment: DomesticPayment): StepOutcome = standingWithin(payment, budget)

    private suspend fun standingWithin(payment: DomesticPayment, remaining: Duration): StepOutcome {
        val lookup = withTimeoutOrNull(remaining.toMillis()) { standingPort.debtorStanding(payment.debtorAccountId) }
            ?: return StepOutcome.Unavailable(UnavailableCause.BUDGET_EXCEEDED)
        return when (lookup) {
            is AccountStandingLookup.Unavailable -> StepOutcome.Unavailable(lookup.cause)
            is AccountStandingLookup.Known -> {
                val evidence = AdmissionEvidence.of(
                    buildMap {
                        put(EVIDENCE_DEBTOR_PARTY, lookup.ownerPartyId.toString())
                        lookup.reason?.let { put("standing_reason", it.take(AdmissionEvidence.MAX_VALUE_LENGTH)) }
                    },
                )
                if (lookup.mayDebit) {
                    StepOutcome.Decided(AdmissionDecision.ALLOW, STANDING_GOOD, evidence)
                } else {
                    StepOutcome.Decided(AdmissionDecision.REJECT, STANDING_MAY_NOT_DEBIT, evidence)
                }
            }
        }
    }

    private suspend fun sanctions(payment: DomesticPayment, remaining: Duration): StepOutcome {
        if (!DomesticScreeningScope.isScreened(payment.transferScope)) {
            return StepOutcome.NotApplicable(SANCTIONS_OWN_OR_TECHNICAL_TRANSFER)
        }
        val results = try {
            withTimeoutOrNull(remaining.toMillis()) {
                listOf(
                    screeningPort.screen(payment.debtorName, ScreeningRole.DEBTOR, "${payment.id}:debtor"),
                    screeningPort.screen(payment.creditorName, ScreeningRole.CREDITOR, "${payment.id}:creditor"),
                )
            } ?: return StepOutcome.Unavailable(UnavailableCause.BUDGET_EXCEEDED)
        } catch (_: ScreeningUnavailableException) {
            return StepOutcome.Unavailable(UnavailableCause.DEPENDENCY_DOWN)
        }
        results.forEach(::meter)
        val evidence = sanctionsEvidence(results)
        return when (DomesticScreeningScope.apply(payment.transferScope, ScreeningPolicy.decide(results))) {
            ScreeningDecision.CLEAR -> StepOutcome.Decided(AdmissionDecision.ALLOW, SANCTIONS_CLEAR, evidence)
            ScreeningDecision.REVIEW -> StepOutcome.Decided(AdmissionDecision.HOLD, SANCTIONS_POTENTIAL_MATCH, evidence)
            ScreeningDecision.BLOCK -> StepOutcome.Decided(AdmissionDecision.REJECT, SANCTIONS_HIT, evidence)
        }
    }

    private fun meter(result: ScreeningResult) {
        val role = result.role.name.lowercase()
        metrics.sanctionsScreening(role)
        val severity = when (result.status) {
            ScreeningMatchStatus.HIT, ScreeningMatchStatus.ESCALATED -> "block"
            ScreeningMatchStatus.POTENTIAL_HIT -> "review"
            ScreeningMatchStatus.CLEAR, ScreeningMatchStatus.WHITELISTED -> null
        }
        if (severity != null) metrics.sanctionsHit(role, severity)
    }

    /**
     * Status and score per role, and the list entry that matched. Never the screened name itself:
     * the evidence travels in an event several services read, and the reviewer has the payment.
     */
    private fun sanctionsEvidence(results: List<ScreeningResult>): AdmissionEvidence = AdmissionEvidence.of(
        buildMap {
            results.forEach { result ->
                val role = result.role.name.lowercase()
                put("${role}_status", result.status.name)
                put("${role}_score", result.score.toString())
                val matched = result.matchedEntity
                if (matched != null && result.status != ScreeningMatchStatus.CLEAR) {
                    put("${role}_matched_entity", matched.take(AdmissionEvidence.MAX_VALUE_LENGTH))
                }
            }
        },
    )

    private suspend fun fraud(payment: DomesticPayment, remaining: Duration): StepOutcome {
        val outcome = withTimeoutOrNull(remaining.toMillis()) {
            fraudScoringPort.score(
                FraudScoreCommand(
                    amount = payment.amount.amount,
                    currency = payment.currency,
                    rail = "DOMESTIC",
                    accountId = payment.debtorAccountId,
                    counterpartyId = null,
                ),
            )
        } ?: return StepOutcome.Unavailable(UnavailableCause.BUDGET_EXCEEDED)
        val wouldDo = FraudGatePolicy.wouldDo(outcome)
        fraudMetrics.recordDecision(wouldDo, enforced = fraudEnforcementEnabled)
        if (!fraudEnforcementEnabled) return StepOutcome.NotApplicable(FRAUD_ENFORCEMENT_OFF)
        if (outcome.synthetic) {
            // The adapter's stand-in for an unreachable fraud-service: no verdict at all.
            fraudMetrics.recordHold(FraudHoldBasis.SCORING_UNAVAILABLE)
            return StepOutcome.Unavailable(UnavailableCause.DEPENDENCY_DOWN)
        }
        val evidence = AdmissionEvidence.of(
            "verdict" to outcome.verdict.name,
            "score" to outcome.score.toString(),
            "rule_version" to outcome.ruleVersion.take(AdmissionEvidence.MAX_VALUE_LENGTH),
        )
        return when (wouldDo) {
            FraudGateAction.PROCEED -> StepOutcome.Decided(AdmissionDecision.ALLOW, FRAUD_ALLOW, evidence)
            FraudGateAction.REJECT -> StepOutcome.Decided(AdmissionDecision.REJECT, FRAUD_DECLINED, evidence)
            FraudGateAction.HOLD -> {
                fraudMetrics.recordHold(FraudHoldBasis.VERDICT)
                // A CHALLENGE keeps its own decision; this rail has no step-up inside an accepted
                // payment, so the activity parks it for review like any other hold.
                val decision =
                    if (outcome.verdict ==
                        FraudVerdict.CHALLENGE
                    ) {
                        AdmissionDecision.CHALLENGE
                    } else {
                        AdmissionDecision.HOLD
                    }
                StepOutcome.Decided(decision, FRAUD_REVIEW, evidence)
            }
        }
    }

    companion object {
        /** Evidence key under which the standing step records the account's owning party. */
        const val EVIDENCE_DEBTOR_PARTY = "debtor_party_id"

        val AUTHORITY_DECIDED_AT_EDGE = AdmissionReasonCode("AUTHORITY_DECIDED_AT_EDGE")
        val LIMITS_NOT_DELIVERED = AdmissionReasonCode("LIMITS_NOT_DELIVERED")
        val AML_DESTINATION_RISK_NOT_DELIVERED = AdmissionReasonCode("AML_DESTINATION_RISK_NOT_DELIVERED")
        val PAYEE_VERIFICATION_NOT_REQUIRED = AdmissionReasonCode("PAYEE_VERIFICATION_NOT_REQUIRED")
        val STANDING_GOOD = AdmissionReasonCode("STANDING_GOOD")
        val STANDING_MAY_NOT_DEBIT = AdmissionReasonCode("STANDING_MAY_NOT_DEBIT")
        val SANCTIONS_OWN_OR_TECHNICAL_TRANSFER = AdmissionReasonCode("SANCTIONS_OWN_OR_TECHNICAL_TRANSFER")
        val SANCTIONS_CLEAR = AdmissionReasonCode("SANCTIONS_CLEAR")
        val SANCTIONS_POTENTIAL_MATCH = AdmissionReasonCode("SANCTIONS_POTENTIAL_MATCH")
        val SANCTIONS_HIT = AdmissionReasonCode("SANCTIONS_HIT")
        val FRAUD_ENFORCEMENT_OFF = AdmissionReasonCode("FRAUD_ENFORCEMENT_OFF")
        val FRAUD_ALLOW = AdmissionReasonCode("FRAUD_ALLOW")
        val FRAUD_REVIEW = AdmissionReasonCode("FRAUD_REVIEW")
        val FRAUD_DECLINED = AdmissionReasonCode("FRAUD_DECLINED")
    }
}

/**
 * Which transfers the sanctions screen applies to, and the one downgrade it allows (ADR-0032,
 * simplified due diligence). One copy, read by the legacy screening activity and by the chain, so
 * the two paths cannot disagree about a transfer between the bank's own customers.
 */
object DomesticScreeningScope {
    /** A transfer between the customer's own accounts, or to a technical account, is not screened. */
    fun isScreened(scope: DomesticTransferScope): Boolean =
        scope != DomesticTransferScope.OWN_ACCOUNTS && scope != DomesticTransferScope.TECHNICAL_ACCOUNT

    /**
     * Between two customers the bank holds full KYC on, a potential match is not actionable and
     * does not hold the payment. A BLOCK is never downgraded.
     */
    fun apply(scope: DomesticTransferScope, raw: ScreeningDecision): ScreeningDecision =
        if (scope == DomesticTransferScope.INTERNAL_CLIENT && raw == ScreeningDecision.REVIEW) {
            ScreeningDecision.CLEAR
        } else {
            raw
        }
}
