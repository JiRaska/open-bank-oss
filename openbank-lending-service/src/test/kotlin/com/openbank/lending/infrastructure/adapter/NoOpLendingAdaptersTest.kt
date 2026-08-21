// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.adapter

import com.openbank.lending.application.port.out.LedgerPosting
import com.openbank.lending.application.port.out.LendingOutboxMessage
import com.openbank.lending.application.port.out.PostingKind
import com.openbank.lending.application.port.out.RiskParameterSource
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanStatus
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.AmortizationMethod
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The `@Default` no-op port bindings keep the service offline-buildable (ADR-0028 D3/D4); each must be
 * benign and deterministic — completing successfully, never mutating or inventing data — because they
 * are what runs when no real integration is wired.
 */
class NoOpLendingAdaptersTest {

    private val partyId = UUID.fromString("44444444-4444-4444-4444-444444444444")

    private fun eur(v: String) = Money.of(v, "EUR")

    @Test
    fun `no-op ledger posting refuses rather than reporting a posting it did not make`() {
        val posting = LedgerPosting(
            reference = "loan:1:disbursement",
            partyId = partyId,
            amount = eur("12000.00"),
            kind = PostingKind.DISBURSEMENT,
        )

        // #6057: this test previously asserted `isEqualTo(Unit)` — it pinned the defect in place.
        // A no-op that returns the real adapter's success value is indistinguishable from a posted
        // journal, which is how 44 live loans accrued against a ledger holding zero lending lines.
        assertThatThrownBy { NoOpLedgerPostingPort().post(posting).await().indefinitely() }
            .isInstanceOf(LedgerBackendNotConfiguredException::class.java)
    }

    @Test
    fun `no-op credit bureau reports no score and no adverse data`() {
        val assessment = NoOpCreditBureauPort().assess(partyId, eur("12000.00")).await().indefinitely()

        // No bureau wired: the assessment must be explicitly empty, never a fabricated score.
        assertThat(assessment.score).isNull()
        assertThat(assessment.hasAdverseData).isFalse()
        assertThat(assessment.source).isEqualTo("no-op")
    }

    @Test
    fun `no-op collateral valuation accepts the declared value unchanged`() {
        val declared = eur("250000.00")

        val valued = NoOpCollateralValuationPort().revalue("REAL_ESTATE", declared).await().indefinitely()

        assertThat(valued).isEqualTo(declared)
    }

    @Test
    fun `conservative risk source returns the documented flat PD and LGD defaults`() {
        val loan = Loan(
            id = LoanId.random(),
            applicationId = LoanApplicationId.random(),
            partyId = partyId,
            principal = eur("12000.00"),
            nominalAnnualRate = BigDecimal("0.12"),
            termPeriods = 12,
            method = AmortizationMethod.ANNUITY,
            firstDueDate = LocalDate.parse("2026-06-30"),
            status = LoanStatus.ACTIVE,
            disbursedAt = OffsetDateTime.parse("2026-01-15T10:00:00Z"),
            createdAt = OffsetDateTime.parse("2026-01-15T10:00:00Z"),
        )
        val ead = eur("10000.00")

        val inputs = ConservativeRiskParameterSource().parametersFor(loan, ead).await().indefinitely()

        assertThat(inputs.pd12Month).isEqualTo(RiskParameterSource.DEFAULT_PD_12M)
        assertThat(inputs.pdLifetime).isEqualTo(RiskParameterSource.DEFAULT_PD_LIFETIME)
        assertThat(inputs.lgd).isEqualTo(RiskParameterSource.DEFAULT_LGD)
        // EAD is passed through, not recomputed.
        assertThat(inputs.exposureAtDefault).isEqualTo(ead)
    }

    @Test
    fun `logging event emitter completes without publishing anywhere`() {
        val message = LendingOutboxMessage(
            aggregateId = UUID.randomUUID(),
            eventType = "loan.disbursed",
            payload = """{"loanId":"x"}""",
        )

        val result = LoggingLoanEventEmitter().emit(message).await().indefinitely()

        assertThat(result).isEqualTo(Unit)
    }

    @Test
    fun `the no-op workflow port reports NOT_ARMED, not the real adapter's success value`() {
        // #6085. The whole defect was that this returned Uni<Unit> — byte for byte what
        // TemporalOriginationWorkflowAdapter returns after starting and signalling the timers
        // workflow. Asserting merely that the Uni completes would restate the bug; the assertion
        // has to be on the OUTCOME, which is the thing that now differs.
        val outcome = com.openbank.lending.infrastructure.adapter.NoOpOriginationWorkflowPort()
            .stateEntered(
                com.openbank.libs.domain.identifiers.LoanApplicationId(java.util.UUID.randomUUID()),
                com.openbank.libs.lending.origination.OriginationState.OFFERED,
                14,
            )
            .await().indefinitely()

        assertThat(outcome)
            .describedAs(
                "a no-op must never share a success signal with the real implementation: with " +
                    "ARMED returned here, no document-SLA, offer-expiry or reflection-period timer " +
                    "is armed and nothing anywhere disagrees.",
            )
            .isEqualTo(com.openbank.lending.application.port.out.TimerArmingOutcome.NOT_ARMED_NO_WORKFLOW_BACKEND)
    }
}
