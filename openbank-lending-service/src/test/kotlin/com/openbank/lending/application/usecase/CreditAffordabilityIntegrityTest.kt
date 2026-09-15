// SPDX-License-Identifier: Apache-2.0
package com.openbank.lending.application.usecase

import com.openbank.lending.application.port.out.CreditAssessment
import com.openbank.lending.application.port.out.CreditBureauPort
import com.openbank.lending.application.port.out.StarterCreditPolicy
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.infrastructure.adapter.NoOpCreditBureauPort
import com.openbank.lending.infrastructure.compliance.CompliancePackGuard
import com.openbank.libs.decision.PolicyDecision
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.AmortizationMethod
import com.openbank.libs.lending.compliance.CompliancePackRegistry
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class CreditAffordabilityIntegrityTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC)
    private fun money(value: String) = Money.of(value, "CZK")
    private fun application() = LoanApplication(
        partyId = UUID.randomUUID(), requestedAmount = money("120000"), nominalAnnualRate = BigDecimal.ZERO,
        termPeriods = 12, firstDueDate = LocalDate.of(2026, 10, 9), proposedBy = "test-maker",
        createdAt = OffsetDateTime.now(clock), verifiedIncomeMonthly = money("50000"),
        existingDebtServiceMonthly = money("20000"), existingDebtOutstanding = money("4800000"),
        ageYears = 35, residency = "CZ",
    )

    private fun engine(bureau: CreditBureauPort) = OriginationDecisionService(
        bureau,
        StarterCreditPolicy(),
        CompliancePackGuard(CompliancePackRegistry(), clock, enforced = false),
        clock,
    )

    @Test
    fun `existing debt breaches total affordability and persists evaluated ratios`() {
        val bureau = mockk<CreditBureauPort>()
        every { bureau.assess(any(), any()) } returns
            Uni.createFrom().item(CreditAssessment(null, false, "test-bureau", true))
        val outcome = engine(bureau).evaluate(application()).await().indefinitely()
        assertThat(outcome.decision).isInstanceOf(PolicyDecision.Decline::class.java)
        assertThat(outcome.recorded.decisionDsti).isEqualByComparingTo("0.6")
        assertThat(outcome.recorded.decisionDti).isEqualByComparingTo("8.2")
    }

    @Test
    fun `missing debt is unknown rather than zero`() {
        assertThat(
            OriginationDecisionService.affordabilityRatios(application().copy(existingDebtOutstanding = null)),
        ).isNull()
        assertThat(
            OriginationDecisionService.affordabilityRatios(application().copy(existingDebtServiceMonthly = null)),
        ).isNull()
        assertThat(
            OriginationDecisionService.affordabilityRatios(
                application().copy(existingDebtServiceMonthly = Money.of("1", "EUR")),
            ),
        ).isNull()
        assertThat(
            OriginationDecisionService.affordabilityRatios(application().copy(existingDebtOutstanding = money("-1"))),
        ).isNull()
    }

    @Test
    fun `quarterly repayments are normalized to monthly income`() {
        val ratios = requireNotNull(
            OriginationDecisionService.affordabilityRatios(
                application().copy(
                    periodsPerYear = 4,
                    termPeriods = 4,
                    existingDebtServiceMonthly = money("0"),
                    existingDebtOutstanding = money("0"),
                ),
            ),
        )
        assertThat(ratios.dsti).isEqualByComparingTo("0.2")
        assertThat(ratios.dti).isEqualByComparingTo("0.2")
    }

    @Test
    fun `declining-principal schedule uses its largest payment`() {
        val app = application().copy(
            method = AmortizationMethod.EQUAL_PRINCIPAL,
            nominalAnnualRate = BigDecimal("0.12"),
        )
        val ratios = requireNotNull(OriginationDecisionService.affordabilityRatios(app))
        assertThat(ratios.dsti).isEqualByComparingTo("0.624")
    }

    @Test
    fun `unavailable bureau cannot become standard customer`() {
        val outcome = engine(NoOpCreditBureauPort()).evaluate(application()).await().indefinitely()
        assertThat(outcome.decision).isInstanceOf(PolicyDecision.Refer::class.java)
        assertThat(outcome.recorded.decisionReasons).contains("INPUT_MISSING:starter-ex-adverse")
    }
}
