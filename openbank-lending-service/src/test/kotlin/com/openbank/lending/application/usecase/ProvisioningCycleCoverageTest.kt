// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.openbank.lending.application.port.out.BorrowerAccountLookupPort
import com.openbank.lending.application.port.out.BorrowerCreditPort
import com.openbank.lending.application.port.out.CatalogLoanProfilePort
import com.openbank.lending.application.port.out.CollateralRepository
import com.openbank.lending.application.port.out.InstallmentRepository
import com.openbank.lending.application.port.out.LedgerPosting
import com.openbank.lending.application.port.out.LedgerPostingPort
import com.openbank.lending.application.port.out.LendingOutboxMessage
import com.openbank.lending.application.port.out.LoanApplicationRepository
import com.openbank.lending.application.port.out.LoanEventEmitter
import com.openbank.lending.application.port.out.LoanRepository
import com.openbank.lending.application.port.out.ProvisioningRepository
import com.openbank.lending.application.port.out.RiskParameterSource
import com.openbank.lending.application.port.out.StarterCreditPolicy
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.lending.domain.model.LoanProvisioningRecord
import com.openbank.lending.infrastructure.adapter.NoOpCreditBureauPort
import com.openbank.lending.infrastructure.adapter.NoOpOriginationWorkflowPort
import com.openbank.lending.infrastructure.compliance.CompliancePackGuard
import com.openbank.lending.infrastructure.compliance.OriginationConfig
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.AmortizationMethod
import com.openbank.libs.lending.EclInputs
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

/**
 * The IFRS 9 provisioning cycle must eventually assess EVERY active loan for a period, including a
 * book larger than one batch (issue #9901).
 *
 * ### Why the rest of the suite cannot see this
 *
 * Every other provisioning test stubs the scan with a list of ONE loan, so the batch bound is never
 * reached and the question this test asks — what happens on the second tick — is never put. The
 * defect it covers was live: the cycle read `findActive(limit)`, a fixed window
 * (`ORDER BY disbursedAt, id`, `setMaxResults`, no cursor), so every tick returned the same first
 * `limit` loans. The head was skipped as already-provisioned but still filled the window, so past
 * that position a loan was never assessed at all — and the scheduler's warning said the tail
 * "self-heals eventually", which is precisely what it did not do.
 *
 * ### What makes it a real test rather than a restatement
 *
 * The fake repository below implements BOTH scans with their real semantics — `findActive` as the
 * fixed window, `findActiveWithoutProvisioning` as the sliding one — over a shared book and a
 * shared provisioning store. So pointing `runProvisioningCycle` back at `findActive` reproduces the
 * defect here rather than merely failing a stub expectation: the assertion below goes red with
 * three of seven loans provisioned and no amount of extra ticks improving it.
 */
class ProvisioningCycleCoverageTest {

    private val applications = mockk<LoanApplicationRepository>()
    private val loans = mockk<LoanRepository>()
    private val installments = mockk<InstallmentRepository>()
    private val collateral = mockk<CollateralRepository>()
    private val ledger = mockk<LedgerPostingPort>()
    private val valuation = mockk<com.openbank.lending.application.port.out.CollateralValuationPort>()
    private val riskParameters = mockk<RiskParameterSource>()
    private val events = mockk<LoanEventEmitter>()
    private val provisioning = mockk<ProvisioningRepository>()
    private val borrowerAccounts = mockk<BorrowerAccountLookupPort>()
    private val borrowerCredit = mockk<BorrowerCreditPort>()
    private val catalogLoanProfiles = mockk<CatalogLoanProfilePort>()

    private val clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val fixedNow = OffsetDateTime.ofInstant(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val partyId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val firstDue = LocalDate.parse("2026-06-30")

    private val service = LendingService(
        applications,
        loans,
        installments,
        collateral,
        ledger,
        valuation,
        riskParameters,
        events,
        clock,
        provisioning,
        CompliancePackGuard(CompliancePackRegistry(), clock, enforced = false),
        OriginationConfig(false),
        NoOpOriginationWorkflowPort(),
        OriginationDecisionService(
            NoOpCreditBureauPort(),
            StarterCreditPolicy(),
            CompliancePackGuard(CompliancePackRegistry(), clock, enforced = false),
            clock,
        ),
        borrowerAccounts,
        borrowerCredit,
        catalogLoanProfiles,
    )

    private fun eur(v: String) = Money.of(v, "EUR")

    private fun loan(index: Int): Loan = Loan(
        id = LoanId(UUID.fromString("00000000-0000-4000-8000-00000000000$index")),
        applicationId = LoanApplicationId.random(),
        partyId = partyId,
        principal = eur("12000.00"),
        nominalAnnualRate = BigDecimal("0.12"),
        termPeriods = 12,
        method = AmortizationMethod.ANNUITY,
        firstDueDate = firstDue,
        disbursedAt = fixedNow.plusDays(index.toLong()),
        createdAt = fixedNow,
    )

    /** Per-loan stubs: schedule, risk parameters, no collateral, and the provisioning store reads. */
    private fun stubLoan(l: Loan, period: String, stored: MutableMap<LoanId, LoanProvisioningRecord>) {
        every { installments.findByLoan(l.id) } returns Uni.createFrom().item(
            listOf(
                LoanInstallment(
                    loanId = l.id,
                    number = 1,
                    dueDate = firstDue,
                    openingBalance = eur("12000.00"),
                    principal = eur("946.19"),
                    interest = eur("120.00"),
                    payment = eur("1066.19"),
                    closingBalance = eur("11053.81"),
                ),
            ),
        )
        every { riskParameters.parametersFor(l, eur("12000.00")) } returns Uni.createFrom().item(
            EclInputs(
                pd12Month = BigDecimal("0.02"),
                pdLifetime = BigDecimal("0.20"),
                lgd = BigDecimal("0.45"),
                exposureAtDefault = eur("12000.00"),
                modelVersion = "test-model-v1",
            ),
        )
        every { collateral.findByLoan(l.id) } returns Uni.createFrom().item(emptyList())
        every { provisioning.findByLoanAndPeriod(l.id, period) } answers {
            Uni.createFrom().optional(java.util.Optional.ofNullable(stored[l.id]))
        }
        every { provisioning.findLatestBefore(l.id, period) } returns Uni.createFrom().nullItem()
    }

    @Test
    fun `a book larger than one batch is fully provisioned across successive ticks`() {
        val book = (1..7).map { loan(it) }
        val period = "2026-06"
        val asOf = LocalDate.parse("2026-06-01")
        val batch = 3
        // The provisioning store the fake scans read back — the thing that makes the window slide.
        val stored = mutableMapOf<LoanId, LoanProvisioningRecord>()

        // Real semantics of BOTH scans over the same book.
        every { loans.findActiveWithoutProvisioning(period, batch) } answers {
            Uni.createFrom().item(book.filterNot { stored.containsKey(it.id) }.take(batch))
        }
        every { loans.findActive(batch) } answers { Uni.createFrom().item(book.take(batch)) }

        book.forEach { stubLoan(it, period, stored) }

        every { provisioning.save(any()) } answers {
            val rec = firstArg<LoanProvisioningRecord>()
            stored[rec.loanId] = rec
            Uni.createFrom().item(rec)
        }
        every { ledger.post(any<LedgerPosting>()) } returns Uni.createFrom().item(Unit)
        every { events.emit(any<LendingOutboxMessage>()) } returns Uni.createFrom().item(Unit)

        // Three ticks for seven loans at a batch of three: 3 + 3 + 1.
        val assessed = (1..3).map {
            service.runProvisioningCycle(period, asOf, batch).await().indefinitely().loansAssessed
        }

        assertThat(assessed)
            .describedAs("each tick takes a full batch until the book is exhausted")
            .containsExactly(3, 3, 1)
        assertThat(stored.keys)
            .describedAs(
                "every ACTIVE loan must carry a provisioning row for the period — a loan past the " +
                    "first batch is not 'late', it was never assessed at all (#9901)",
            )
            .containsExactlyInAnyOrderElementsOf(book.map { it.id })
    }
}
