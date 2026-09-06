// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.openbank.lending.application.port.out.CreditDecisionQueryRepository
import com.openbank.lending.application.port.out.LoanRepository
import com.openbank.lending.application.port.out.ProvisioningRepository
import com.openbank.lending.application.port.out.StarterCreditPolicy
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.domain.model.LoanProvisioningRecord
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.AmortizationMethod
import com.openbank.libs.lending.DelinquencyBucket
import com.openbank.libs.lending.Ifrs9Stage
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class CreditRiskInsightServiceTest {

    private val applications = mockk<CreditDecisionQueryRepository>()
    private val loans = mockk<LoanRepository>()
    private val provisioning = mockk<ProvisioningRepository>()
    private val service = CreditRiskInsightService(applications, loans, provisioning, StarterCreditPolicy())

    private fun application(income: String?, existingDebt: String? = null) = LoanApplication(
        partyId = UUID.randomUUID(),
        requestedAmount = Money.of(BigDecimal("120000.00"), "CZK"),
        nominalAnnualRate = BigDecimal("0.08"),
        termPeriods = 12,
        firstDueDate = LocalDate.of(2026, 10, 1),
        proposedBy = "officer",
        createdAt = OffsetDateTime.parse("2026-09-01T10:00:00Z"),
        verifiedIncomeMonthly = income?.let { Money.of(BigDecimal(it), "CZK") },
        existingDebtServiceMonthly = existingDebt?.let { Money.of(BigDecimal(it), "CZK") },
        decisionOutcome = "APPROVE",
        decisionPriceBand = "PRIME",
        decisionReasons = "",
        decisionMatchedRules = "starter-el-age,starter-el-residency,starter-af-dsti,starter-af-dti,starter-pr-prime",
        policyVersions = "EXCLUSION=1,ELIGIBILITY=1,AFFORDABILITY=1,PRICING_BAND=1",
        decisionInputHash = "abc",
        decidedEngineAt = OffsetDateTime.parse("2026-09-01T10:00:05Z"),
    )

    @Test
    fun `decisions decode the pinned evidence and carry the engine's own affordability ratios`() {
        val app = application(income = "50000.00", existingDebt = "5000.00")
        every { applications.findEvaluated(any()) } returns Uni.createFrom().item(listOf(app))

        val view = service.decisions(10).await().indefinitely().single()

        assertThat(view.engineOutcome).isEqualTo("APPROVE")
        assertThat(view.priceBand).isEqualTo("PRIME")
        assertThat(view.matchedRuleIds).hasSize(5).contains("starter-pr-prime")
        assertThat(view.policyVersions).containsEntry("AFFORDABILITY", 1)
        val ratios = requireNotNull(view.affordability)
        // The same number the ASSESSMENT leg read — one definition, not a console re-estimate.
        val engine = requireNotNull(OriginationDecisionService.affordabilityRatios(app))
        assertThat(ratios.dsti).isEqualByComparingTo(engine.dsti)
        assertThat(ratios.dti).isEqualByComparingTo(BigDecimal("120000").divide(BigDecimal("600000")))
        // Existing debt service widens the total-DSTI figure by exactly debt/income.
        assertThat(ratios.dstiIncludingExistingDebt.subtract(ratios.dsti)).isEqualByComparingTo(BigDecimal("0.1"))
    }

    @Test
    fun `no verified income means no ratios, never a zero`() {
        every { applications.findEvaluated(any()) } returns Uni.createFrom().item(listOf(application(income = null)))
        assertThat(service.decisions(10).await().indefinitely().single().affordability).isNull()
    }

    @Test
    fun `the read limit is clamped to the server maximum`() {
        every { applications.findEvaluated(CreditRiskInsightService.MAX_LIMIT) } returns
            Uni.createFrom().item(emptyList())
        every { applications.findEvaluated(1) } returns Uni.createFrom().item(emptyList())
        service.decisions(999_999).await().indefinitely()
        service.decisions(-5).await().indefinitely()
    }

    @Test
    fun `portfolio joins the latest assessment and leaves an unassessed loan null`() {
        val assessed = loan()
        val fresh = loan()
        every { loans.findRecent(any()) } returns Uni.createFrom().item(listOf(assessed, fresh))
        every { provisioning.findLatestPerLoan() } returns Uni.createFrom().item(
            listOf(
                LoanProvisioningRecord(
                    loanId = assessed.id, period = "2026-08", asOf = LocalDate.of(2026, 8, 31),
                    outstandingBalance = Money.of(BigDecimal("90000.00"), "CZK"), daysPastDue = 45,
                    bucket = DelinquencyBucket.DPD_31_60, stage = Ifrs9Stage.STAGE_2,
                    expectedCreditLoss = Money.of(BigDecimal("8100.00"), "CZK"),
                    createdAt = OffsetDateTime.now(), modelVersion = "noop-flat-v1",
                ),
            ),
        )

        val views = service.portfolio(100).await().indefinitely().associateBy { it.loanId }

        val a = requireNotNull(views[assessed.id.value].let { requireNotNull(it).assessment })
        assertThat(a.stage).isEqualTo("STAGE_2")
        assertThat(a.bucket).isEqualTo("DPD_31_60")
        assertThat(a.expectedCreditLoss).isEqualByComparingTo("8100.00")
        assertThat(a.modelVersion).isEqualTo("noop-flat-v1")
        assertThat(requireNotNull(views[fresh.id.value]).assessment).isNull()
    }

    @Test
    fun `the starter policy is reported as code-seeded with all four table kinds`() {
        val view = service.activePolicy(LocalDate.of(2026, 9, 5)).await().indefinitely()
        assertThat(view.codeSeeded).isTrue()
        assertThat(view.source).isEqualTo("StarterCreditPolicy")
        assertThat(view.tables.map { it.kind.name }).containsExactlyInAnyOrder(
            "EXCLUSION",
            "ELIGIBILITY",
            "AFFORDABILITY",
            "PRICING_BAND",
        )
    }

    private fun loan() = Loan(
        id = LoanId(UUID.randomUUID()),
        applicationId = LoanApplicationId(UUID.randomUUID()),
        partyId = UUID.randomUUID(),
        principal = Money.of(BigDecimal("100000.00"), "CZK"),
        nominalAnnualRate = BigDecimal("0.08"),
        termPeriods = 12,
        method = AmortizationMethod.ANNUITY,
        firstDueDate = LocalDate.of(2026, 10, 1),
        disbursedAt = OffsetDateTime.parse("2026-09-01T10:00:00Z"),
        createdAt = OffsetDateTime.parse("2026-09-01T10:00:00Z"),
    )
}
