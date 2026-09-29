// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.lending.domain

import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanBookAssembler
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.lending.domain.model.LoanStatus
import com.openbank.lending.infrastructure.client.LendingGlChart
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.Amortization
import com.openbank.libs.lending.AmortizationMethod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** ADR-0314 D4: the loan book the risk engine ties out against the ledger. */
class LoanBookAssemblerTest {

    private val asOf = LocalDate.parse("2026-09-30")
    private val at = OffsetDateTime.parse("2026-01-10T10:00:00Z")

    private fun loan(
        currency: String = "CZK",
        status: LoanStatus = LoanStatus.ACTIVE,
        disbursedAt: OffsetDateTime = at,
    ) = Loan(
        id = LoanId(UUID.randomUUID()),
        applicationId = LoanApplicationId(UUID.randomUUID()),
        partyId = UUID.randomUUID(),
        principal = Money.of("12000.00", currency),
        nominalAnnualRate = BigDecimal("0.06"),
        termPeriods = 12,
        method = AmortizationMethod.ANNUITY,
        firstDueDate = LocalDate.parse("2026-02-15"),
        status = status,
        disbursedAt = disbursedAt,
        createdAt = disbursedAt,
    )

    /** The loan's schedule as lending persists it, with the first [paid] installments paid on their due date. */
    private fun schedule(loan: Loan, paid: Int, paidOn: (LocalDate) -> LocalDate = { it }) =
        Amortization.schedule(loan.principal, loan.nominalAnnualRate, 12, loan.firstDueDate).installments.map {
            val isPaid = it.number <= paid
            LoanInstallment(
                loanId = loan.id,
                number = it.number,
                dueDate = it.dueDate,
                openingBalance = it.openingBalance,
                principal = it.principal,
                interest = it.interest,
                payment = it.payment,
                closingBalance = it.closingBalance,
                paid = isPaid,
                paidAt = if (isPaid) paidOn(it.dueDate).atStartOfDay().atOffset(ZoneOffset.UTC).plusHours(9) else null,
            )
        }

    private fun assemble(
        loans: List<Loan>,
        installments: List<LoanInstallment>,
        stages: Map<LoanId, String> = emptyMap(),
    ) = LoanBookAssembler.assemble(asOf, loans, installments, stages, LendingGlChart::loansReceivableCode)

    @Test
    fun `outstanding is the principal of the installments unpaid at asOf, on the currency's Loans Receivable code`() {
        val czk = loan()
        val eur = loan(currency = "EUR")
        val rows = schedule(czk, paid = 7) + schedule(eur, paid = 3)

        val book = assemble(listOf(czk, eur), rows, mapOf(czk.id to "STAGE_2"))

        val c = book.loans.single { it.loanId == czk.id.value }
        assertThat(c.glAccountCode).isEqualTo("1200")
        assertThat(c.remainingInstallments.map { it.number }).containsExactly(8, 9, 10, 11, 12)
        assertThat(c.outstandingPrincipal).isEqualByComparingTo(
            rows.filter { it.loanId == czk.id && !it.paid }.fold(BigDecimal.ZERO) { a, i -> a.add(i.principal.amount) },
        )
        assertThat(c.outstandingPrincipal).isEqualByComparingTo(
            rows.single {
                it.loanId == czk.id && it.number == 7
            }.closingBalance.amount,
        )
        assertThat(c.ifrs9Stage).isEqualTo("STAGE_2")
        assertThat(c.maturityDate).isEqualTo(LocalDate.parse("2027-01-15"))
        assertThat(book.loans.single { it.loanId == eur.id.value }.glAccountCode).isEqualTo("1201")
    }

    @Test
    fun `an installment paid after asOf is still remaining at asOf`() {
        val l = loan()
        val rows = schedule(l, paid = 9) // each paid on its due date; the 9th is due 2026-10-15
        val book = assemble(listOf(l), rows)
        // Installment 9 (due 2026-10-15) was paid on 2026-10-15 — after asOf — so it is remaining.
        assertThat(book.loans.single().remainingInstallments.first().number).isEqualTo(9)
    }

    @Test
    fun `off-book statuses and loans disbursed after asOf are not on the book`() {
        val live = loan()
        val gone = LoanBookAssembler.OFF_BOOK.map { loan(status = it) }
        val future = loan(disbursedAt = OffsetDateTime.parse("2026-10-01T08:00:00Z"))
        val delinquent = loan(status = LoanStatus.DELINQUENT)

        val book = assemble(listOf(live, future, delinquent) + gone, emptyList())

        assertThat(book.loans.map { it.loanId }).containsExactlyInAnyOrder(live.id.value, delinquent.id.value)
    }

    @Test
    fun `a fully repaid loan still on the book has zero outstanding and no installments`() {
        val l = loan()
        val book = assemble(listOf(l), schedule(l, paid = 12) { LocalDate.parse("2026-09-01") })
        assertThat(book.loans.single().outstandingPrincipal).isEqualByComparingTo("0")
        assertThat(book.loans.single().remainingInstallments).isEmpty()
    }
}
