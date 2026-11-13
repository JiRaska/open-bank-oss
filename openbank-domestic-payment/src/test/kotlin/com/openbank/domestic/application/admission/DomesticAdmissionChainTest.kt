// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.admission

import com.openbank.domestic.application.port.out.AccountStandingLookup
import com.openbank.domestic.application.port.out.AccountStandingPort
import com.openbank.domestic.application.port.out.FraudEnforcementMetricsPort
import com.openbank.domestic.application.port.out.FraudScoreCommand
import com.openbank.domestic.application.port.out.FraudScoreOutcome
import com.openbank.domestic.application.port.out.FraudScoringPort
import com.openbank.domestic.application.port.out.SanctionsScreeningPort
import com.openbank.domestic.application.port.out.ScreeningUnavailableException
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticTransferScope
import com.openbank.libs.domain.money.Money
import com.openbank.libs.fincrime.FraudVerdict
import com.openbank.libs.fincrime.ScreeningMatchStatus
import com.openbank.libs.fincrime.ScreeningResult
import com.openbank.libs.fincrime.ScreeningRole
import com.openbank.libs.fincrime.admission.AdmissionDecision
import com.openbank.libs.fincrime.admission.AdmissionStep
import com.openbank.libs.fincrime.admission.StepOutcome
import com.openbank.libs.fincrime.admission.UnavailableCause
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The domestic binding of the ADR-0374 chain, against hand-written ports that record their calls.
 * The order and the severity algebra are the kernel's and are tested there; this class pins what
 * the RAIL contributes: which steps are bound, what each answer maps to, and that a control the
 * rail could not ask never reads as a pass.
 */
class DomesticAdmissionChainTest {

    private val owner = UUID.randomUUID()
    private var standing: AccountStandingLookup = AccountStandingLookup.Known(owner, mayDebit = true, reason = null)
    private var creditorStatus = ScreeningMatchStatus.CLEAR
    private var screeningDown = false
    private var fraud = FraudScoreOutcome(FraudVerdict.ALLOW, 3, "rules-1", emptyList())
    private val calls = mutableListOf<String>()

    private val standingPort = object : AccountStandingPort {
        override suspend fun debtorStanding(accountId: UUID): AccountStandingLookup {
            calls += "standing"
            return standing
        }
    }
    private val screeningPort = object : SanctionsScreeningPort {
        override suspend fun screen(name: String, role: ScreeningRole, idempotencyKey: String): ScreeningResult {
            calls += "sanctions:$role"
            if (screeningDown) throw ScreeningUnavailableException(IllegalStateException("down"))
            val status = if (role == ScreeningRole.CREDITOR) creditorStatus else ScreeningMatchStatus.CLEAR
            val matched = if (status == ScreeningMatchStatus.CLEAR) null else "Listed Entity 7"
            // Below the policy's block threshold, so a POTENTIAL_HIT is a review and not a block.
            return ScreeningResult(name, role, status, if (matched == null) 0.0 else 0.8, matched)
        }
    }
    private val fraudPort = object : FraudScoringPort {
        override suspend fun score(command: FraudScoreCommand): FraudScoreOutcome {
            calls += "fraud"
            return fraud
        }
    }

    private fun chain(fraudEnforced: Boolean = true) = DomesticAdmissionChain(
        standingPort,
        screeningPort,
        fraudPort,
        mockk<FraudEnforcementMetricsPort>(relaxed = true),
        mockk(relaxed = true),
        fraudEnforced,
        Duration.ofSeconds(30),
    )

    private fun admit(payment: DomesticPayment = payment(), fraudEnforced: Boolean = true) =
        runBlocking { chain(fraudEnforced).admit(payment) }

    @Test
    fun `good standing, a clear screen and a real fraud ALLOW admit the payment`() {
        val result = admit()

        assertThat(result.decision).isEqualTo(AdmissionDecision.ALLOW)
        assertThat(calls).containsExactly("standing", "sanctions:DEBTOR", "sanctions:CREDITOR", "fraud")
        val evidence = (result[AdmissionStep.STANDING].outcome as StepOutcome.Decided).evidence.attributes
        assertThat(evidence).containsEntry(DomesticAdmissionChain.EVIDENCE_DEBTOR_PARTY, owner.toString())
    }

    @Test
    fun `an account that may not be debited is rejected before any remote control hears of the payment`() {
        standing = AccountStandingLookup.Known(owner, mayDebit = false, reason = "ACCOUNT_FROZEN")

        val result = admit()

        assertThat(result.decision).isEqualTo(AdmissionDecision.REJECT)
        assertThat(result.reasons).containsExactly(DomesticAdmissionChain.STANDING_MAY_NOT_DEBIT)
        assertThat(calls).containsExactly("standing")
        assertThat(result[AdmissionStep.SANCTIONS].outcome).isEqualTo(StepOutcome.NotReached)
        assertThat(result[AdmissionStep.FRAUD].outcome).isEqualTo(StepOutcome.NotReached)
    }

    @Test
    fun `standing that could not be read holds the payment and is never a pass`() {
        standing = AccountStandingLookup.Unavailable(UnavailableCause.DEPENDENCY_DOWN)

        val result = admit()

        assertThat(result.decision).isEqualTo(AdmissionDecision.HOLD)
        assertThat(result.degraded).isTrue()
        assertThat(result[AdmissionStep.STANDING].outcome)
            .isEqualTo(StepOutcome.Unavailable(UnavailableCause.DEPENDENCY_DOWN))
    }

    @Test
    fun `a potential sanctions match holds, a hit rejects and neither leaks the screened name`() {
        creditorStatus = ScreeningMatchStatus.POTENTIAL_HIT
        val review = admit()
        assertThat(review.decision).isEqualTo(AdmissionDecision.HOLD)
        assertThat(review.reasons).containsExactly(DomesticAdmissionChain.SANCTIONS_POTENTIAL_MATCH)
        val evidence = (review[AdmissionStep.SANCTIONS].outcome as StepOutcome.Decided).evidence.attributes
        assertThat(evidence).containsEntry("creditor_status", "POTENTIAL_HIT")
            .containsEntry("creditor_matched_entity", "Listed Entity 7")
        assertThat(evidence.values).noneMatch { it.contains("Creditor Name") || it.contains("Debtor Name") }

        creditorStatus = ScreeningMatchStatus.HIT
        calls.clear()
        val hit = admit()
        assertThat(hit.decision).isEqualTo(AdmissionDecision.REJECT)
        assertThat(hit.reasons).containsExactly(DomesticAdmissionChain.SANCTIONS_HIT)
        assertThat(calls).doesNotContain("fraud")
    }

    @Test
    fun `an unreachable sanctions screen holds the payment`() {
        screeningDown = true

        val result = admit()

        assertThat(result.decision).isEqualTo(AdmissionDecision.HOLD)
        assertThat(result[AdmissionStep.SANCTIONS].outcome)
            .isEqualTo(StepOutcome.Unavailable(UnavailableCause.DEPENDENCY_DOWN))
    }

    @Test
    fun `a transfer between own accounts is not screened and a potential match between two customers does not hold`() {
        val own = admit(payment(scope = DomesticTransferScope.OWN_ACCOUNTS))
        assertThat(own.decision).isEqualTo(AdmissionDecision.ALLOW)
        assertThat(own[AdmissionStep.SANCTIONS].outcome)
            .isEqualTo(StepOutcome.NotApplicable(DomesticAdmissionChain.SANCTIONS_OWN_OR_TECHNICAL_TRANSFER))
        assertThat(calls).doesNotContain("sanctions:DEBTOR", "sanctions:CREDITOR")

        creditorStatus = ScreeningMatchStatus.POTENTIAL_HIT
        assertThat(admit(payment(scope = DomesticTransferScope.INTERNAL_CLIENT)).decision)
            .isEqualTo(AdmissionDecision.ALLOW)
        creditorStatus = ScreeningMatchStatus.HIT
        assertThat(admit(payment(scope = DomesticTransferScope.INTERNAL_CLIENT)).decision)
            .isEqualTo(AdmissionDecision.REJECT)
    }

    @Test
    fun `an enforced fraud verdict maps DECLINE to REJECT, REVIEW to HOLD and CHALLENGE to CHALLENGE`() {
        fraud = fraud.copy(verdict = FraudVerdict.DECLINE)
        assertThat(admit().decision).isEqualTo(AdmissionDecision.REJECT)
        fraud = fraud.copy(verdict = FraudVerdict.REVIEW)
        assertThat(admit().decision).isEqualTo(AdmissionDecision.HOLD)
        fraud = fraud.copy(verdict = FraudVerdict.CHALLENGE)
        assertThat(admit().decision).isEqualTo(AdmissionDecision.CHALLENGE)
    }

    @Test
    fun `a synthetic fraud verdict is an unavailable control, whatever it says`() {
        fraud = FraudScoreOutcome(FraudVerdict.ALLOW, 0, "unavailable", emptyList(), synthetic = true)

        val result = admit()

        assertThat(result.decision).isEqualTo(AdmissionDecision.HOLD)
        assertThat(result[AdmissionStep.FRAUD].outcome)
            .isEqualTo(StepOutcome.Unavailable(UnavailableCause.DEPENDENCY_DOWN))
    }

    @Test
    fun `with fraud enforcement off the score is taken and the step reports itself not applicable, not ALLOW`() {
        fraud = fraud.copy(verdict = FraudVerdict.DECLINE)

        val result = admit(fraudEnforced = false)

        assertThat(calls).contains("fraud")
        assertThat(result.decision).isEqualTo(AdmissionDecision.ALLOW)
        assertThat(result[AdmissionStep.FRAUD].outcome)
            .isEqualTo(StepOutcome.NotApplicable(DomesticAdmissionChain.FRAUD_ENFORCEMENT_OFF))
        assertThat(result[AdmissionStep.FRAUD].effective).isNull()
    }

    @Test
    fun `every step the rail does not run says so with its reason`() {
        val result = admit()

        assertThat(
            AdmissionStep.entries.filter { result[it].outcome is StepOutcome.NotApplicable }
                .associateWith { (result[it].outcome as StepOutcome.NotApplicable).reason },
        ).isEqualTo(
            mapOf(
                AdmissionStep.AUTHORITY to DomesticAdmissionChain.AUTHORITY_DECIDED_AT_EDGE,
                AdmissionStep.LIMITS to DomesticAdmissionChain.LIMITS_NOT_DELIVERED,
                AdmissionStep.AML_DESTINATION_RISK to DomesticAdmissionChain.AML_DESTINATION_RISK_NOT_DELIVERED,
                AdmissionStep.PAYEE_VERIFICATION to DomesticAdmissionChain.PAYEE_VERIFICATION_NOT_REQUIRED,
            ),
        )
    }

    private fun payment(scope: DomesticTransferScope = DomesticTransferScope.EXTERNAL) = DomesticPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "idem",
        status = DomesticPaymentStatus.RECEIVED,
        debtorAccountId = UUID.randomUUID(),
        debtorAccountNumber = "1234567890",
        debtorBankCode = "0800",
        debtorName = "Debtor Name",
        creditorAccountNumber = "0987654321",
        creditorBankCode = "2010",
        creditorName = "Creditor Name",
        amount = Money.of(BigDecimal("20.00"), "CZK"),
        variableSymbol = null,
        specificSymbol = null,
        constantSymbol = null,
        messageForPayee = null,
        priority = DomesticPaymentPriority.STANDARD,
        transferScope = scope,
        technicalAccountCode = null,
        statementLabel = null,
        endToEndId = "DOM1",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = null,
        settledAt = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )
}
