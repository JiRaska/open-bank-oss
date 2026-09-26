// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.openbank.lending.application.port.out.PostingKind
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.lending.domain.model.LoanProvisioningRecord
import com.openbank.lending.domain.model.LoanStatus
import com.openbank.lending.infrastructure.rest.glTotals
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.AmortizationMethod
import com.openbank.libs.lending.DelinquencyBucket
import com.openbank.libs.lending.Ifrs9Stage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/** #10746: the backfill must reproduce the live flow's legs, references and amounts exactly. */
class LedgerBackfillPlannerTest {

    private val zone = ZoneId.of("Europe/Prague")
    private val cutover = LocalDate.parse("2026-09-24")
    private val loanId = LoanId(UUID.fromString("00000000-0000-0000-0000-00000000a001"))

    private fun loan(status: LoanStatus = LoanStatus.ACTIVE, principal: String = "300.00") = Loan(
        id = loanId,
        applicationId = LoanApplicationId.random(),
        partyId = UUID.fromString("00000000-0000-0000-0000-00000000b001"),
        principal = Money.of(principal, "CZK"),
        nominalAnnualRate = BigDecimal("0.12"),
        termPeriods = 3,
        method = AmortizationMethod.ANNUITY,
        firstDueDate = LocalDate.parse("2026-06-20"),
        status = status,
        // 23:30 UTC is already the NEXT day in Prague: the business date must be Prague's.
        disbursedAt = OffsetDateTime.parse("2026-05-19T23:30:00Z"),
        createdAt = OffsetDateTime.parse("2026-05-19T23:30:00Z"),
    )

    private fun row(n: Int, principal: String, interest: String, paid: Boolean, accrued: Boolean) = LoanInstallment(
        loanId = loanId,
        number = n,
        dueDate = LocalDate.parse("2026-0${5 + n}-20"),
        openingBalance = Money.of("0", "CZK"),
        principal = Money.of(principal, "CZK"),
        interest = Money.of(interest, "CZK"),
        payment = Money.of("0", "CZK"),
        closingBalance = Money.of("0", "CZK"),
        paid = paid,
        paidAt = if (paid) OffsetDateTime.parse("2026-0${5 + n}-20T10:00:00Z") else null,
        interestAccrued = accrued,
        accruedAt = if (accrued) OffsetDateTime.parse("2026-0${5 + n}-20T08:00:00Z") else null,
    )

    private fun provisioning(period: String, ecl: String, asOf: String) = LoanProvisioningRecord(
        loanId = loanId,
        period = period,
        asOf = LocalDate.parse(asOf),
        outstandingBalance = Money.of("300", "CZK"),
        daysPastDue = 0,
        bucket = DelinquencyBucket.CURRENT,
        stage = Ifrs9Stage.STAGE_1,
        expectedCreditLoss = Money.of(ecl, "CZK"),
        createdAt = OffsetDateTime.parse("2026-07-19T00:00:00Z"),
        modelVersion = "test",
    )

    private val schedule = listOf(
        row(1, "100.00", "3.00", paid = true, accrued = true),
        row(2, "100.00", "2.00", paid = true, accrued = false),
        row(3, "100.00", "1.00", paid = false, accrued = false),
    )
    private val history = listOf(
        provisioning("2026-07", "6.00", "2026-07-19"),
        provisioning("2026-08", "6.00", "2026-08-01"),
        provisioning("2026-09", "4.50", "2026-09-01"),
    )

    private fun plan(l: Loan = loan(), rows: List<LoanInstallment> = schedule) =
        LedgerBackfillPlanner.plan(cutover, listOf(Triple(l, rows, history)), zone)

    @Test
    fun `every leg carries the live reference, kind, amount and original business date`() {
        val id = loanId.value
        val legs = plan().legs.map { listOf(it.reference, it.kind, it.amount.toPlainString(), it.valueDate.toString()) }
        assertThat(legs).containsExactly(
            listOf("loan:$id:disbursement", PostingKind.DISBURSEMENT, "300.00", "2026-05-20"),
            listOf("loan:$id:inst:1:accrual", PostingKind.INTEREST_ACCRUAL, "3.00", "2026-06-20"),
            listOf("loan:$id:inst:1:principal", PostingKind.PRINCIPAL_REPAYMENT, "100.00", "2026-06-20"),
            listOf("loan:$id:inst:1:interest", PostingKind.INTEREST_SETTLEMENT, "3.00", "2026-06-20"),
            listOf("loan:$id:inst:2:principal", PostingKind.PRINCIPAL_REPAYMENT, "100.00", "2026-07-20"),
            // Paid before it was accrued: the live flow recognises income directly (INTEREST).
            listOf("loan:$id:inst:2:interest", PostingKind.INTEREST, "2.00", "2026-07-20"),
            // Provisioning books DELTAS: +6.00, nothing for the unchanged period, then -1.50.
            listOf("loan:$id:provisioning:2026-07", PostingKind.PROVISIONING, "6.00", "2026-07-19"),
            listOf("loan:$id:provisioning:2026-09", PostingKind.PROVISIONING, "-1.50", "2026-09-01"),
        )
    }

    @Test
    fun `the plan ties out Loans Receivable to lending's unpaid principal`() {
        val tie = plan().tieOut.single()
        assertThat(tie.currency).isEqualTo("CZK")
        assertThat(tie.loansReceivableAfter).isEqualByComparingTo("100.00")
        assertThat(tie.lendingUnpaidPrincipal).isEqualByComparingTo("100.00")
        assertThat(plan().executable).isTrue()
    }

    @Test
    fun `dry-run GL totals are balanced and land on the lending chart`() {
        val totals = plan().glTotals().associateBy { it.code }
        assertThat(totals.getValue("1200").net).isEqualByComparingTo("100.00")
        assertThat(totals.getValue("1300").net).isEqualByComparingTo("0.00")
        assertThat(totals.getValue("4100").net).isEqualByComparingTo("-5.00")
        assertThat(totals.getValue("1400").net).isEqualByComparingTo("-4.50")
        assertThat(totals.getValue("5100").net).isEqualByComparingTo("4.50")
        // Clearing: -300 out, +200 principal, +5 interest cash in.
        assertThat(totals.getValue("1100").net).isEqualByComparingTo("-95.00")
        assertThat(totals.values.sumOf { it.net }).isEqualByComparingTo("0")
    }

    @Test
    fun `a restructured, written-off or non-active loan is refused, never approximated`() {
        assertThat(plan(loan(status = LoanStatus.WRITTEN_OFF)).executable).isFalse()
        assertThat(plan(loan(principal = "350.00")).unsupported.single().unsupportedReason).contains("restructured")
        assertThat(plan(rows = schedule.filter { it.number != 2 }).unsupported.single().unsupportedReason)
            .contains("contiguous")
    }

    @Test
    fun `the plan hash is stable for the same book and changes when the book moves`() {
        assertThat(plan().planHash).isEqualTo(plan().planHash)
        val repaid = schedule.map { if (it.number == 3) row(3, "100.00", "1.00", paid = true, accrued = false) else it }
        assertThat(plan(rows = repaid).planHash).isNotEqualTo(plan().planHash)
        assertThat(
            LedgerBackfillPlanner.plan(cutover.plusDays(1), listOf(Triple(loan(), schedule, history)), zone).planHash,
        )
            .isNotEqualTo(plan().planHash)
    }

    @Test
    fun `postings book on the cut-over date and carry the original date as value date`() {
        val leg = plan().legs.first()
        val posting = LedgerBackfillPlanner.toPosting(leg, UUID.randomUUID(), cutover)
        assertThat(posting.accountingDate).isEqualTo(cutover)
        assertThat(posting.valueDate).isEqualTo(LocalDate.parse("2026-05-20"))
        assertThat(posting.reference).isEqualTo(leg.reference)
    }
}
