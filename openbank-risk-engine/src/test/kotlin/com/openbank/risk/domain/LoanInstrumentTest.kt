// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain

import com.openbank.libs.lending.AmortizationMethod
import com.openbank.risk.domain.Fixtures.AS_OF
import com.openbank.risk.domain.Fixtures.LOAN_A
import com.openbank.risk.domain.Fixtures.LOAN_B
import com.openbank.risk.domain.Fixtures.lendingLoan
import com.openbank.risk.domain.Fixtures.tb
import com.openbank.risk.domain.Fixtures.tiedOutWithLoans
import com.openbank.risk.domain.curve.CurveIndex
import com.openbank.risk.domain.model.InputHash
import com.openbank.risk.domain.model.InstrumentKind
import com.openbank.risk.domain.model.InvalidLoanContractException
import com.openbank.risk.domain.model.LoanContract
import com.openbank.risk.domain.model.LoanExtension
import com.openbank.risk.domain.model.LoanInstrumentMapper
import com.openbank.risk.domain.model.PositionBuilder
import com.openbank.risk.domain.model.PositionKind
import com.openbank.risk.domain.model.RateType
import com.openbank.risk.domain.model.TieOut
import com.openbank.risk.domain.model.TieOutStatus
import com.openbank.risk.infrastructure.client.LendingAdapter
import com.openbank.risk.infrastructure.client.LoanBookEntryResponse
import com.openbank.risk.infrastructure.client.RateTermsResponse
import com.openbank.risk.infrastructure.client.RemainingInstallmentResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** ADR-0314 D4: loans as contract-level instruments — mapping, tie-out and input hash. */
class LoanInstrumentTest {

    private fun instruments(vararg loans: LoanContract) = loans.map(LoanInstrumentMapper::toInstrument)

    private fun tieOut(ledgerLoanTotal: String, vararg loans: LoanContract) =
        tiedOutWithLoans(BigDecimal(ledgerLoanTotal))
            .let { inputs -> TieOut.check(inputs.trialBalance, PositionBuilder.build(inputs, instruments(*loans))) }

    // --- mapping from lending's DTO -------------------------------------------------------------

    @Test
    fun `a lending loan-book entry maps to an AMORTISING_LOAN instrument with its schedule`() {
        val dto = LoanBookEntryResponse(
            loanId = LOAN_A,
            counterpartyRef = Fixtures.ALICE,
            status = "ACTIVE",
            currency = "EUR",
            glAccountCode = "1201",
            outstandingPrincipal = BigDecimal("300.00"),
            nominalAnnualRate = BigDecimal("0.045"),
            rateTerms = RateTermsResponse(
                "FLOATING",
                "EURIBOR_3M",
                BigDecimal("0.015"),
                3,
                LocalDate.parse("2026-12-15"),
            ),
            method = "EQUAL_PRINCIPAL",
            periodsPerYear = 12,
            disbursedOn = LocalDate.parse("2026-01-10"),
            maturityDate = LocalDate.parse("2026-11-15"),
            ifrs9Stage = "STAGE_2",
            remainingInstallments = listOf(
                RemainingInstallmentResponse(
                    10,
                    LocalDate.parse("2026-10-15"),
                    BigDecimal("150.00"),
                    BigDecimal("1.13"),
                ),
                RemainingInstallmentResponse(
                    11,
                    LocalDate.parse("2026-11-15"),
                    BigDecimal("150.00"),
                    BigDecimal("0.56"),
                ),
            ),
        )

        val instrument = LoanInstrumentMapper.toInstrument(LendingAdapter.toContract(dto))

        assertThat(instrument.id).isEqualTo(LOAN_A.toString())
        assertThat(instrument.kind).isEqualTo(InstrumentKind.AMORTISING_LOAN)
        assertThat(instrument.glAccountCode).isEqualTo("1201")
        assertThat(instrument.outstanding).isEqualByComparingTo("300.00") // an asset: positive
        assertThat(instrument.valueDate).isEqualTo(LocalDate.parse("2026-01-10"))
        assertThat(instrument.maturityDate).isEqualTo(LocalDate.parse("2026-11-15"))
        assertThat(instrument.counterpartyRef).isEqualTo(Fixtures.ALICE.toString())
        assertThat(instrument.ifrs9Stage).isEqualTo("STAGE_2")
        val rate = instrument.rateTerms!!
        assertThat(rate.rateType).isEqualTo(RateType.FLOATING)
        assertThat(rate.index).isEqualTo(CurveIndex.EURIBOR_3M)
        assertThat(rate.currentAnnualRate).isEqualByComparingTo("0.045")
        assertThat(rate.resetFrequencyMonths).isEqualTo(3)
        val ext = instrument.extension as LoanExtension
        assertThat(ext.method).isEqualTo(AmortizationMethod.EQUAL_PRINCIPAL)
        assertThat(ext.remainingPeriods).isEqualTo(2)
        assertThat(ext.nextDueDate).isEqualTo(LocalDate.parse("2026-10-15"))
    }

    @Test
    fun `a BULLET loan is a BULLET instrument`() {
        val bullet = lendingLoan(method = AmortizationMethod.BULLET)
        assertThat(LoanInstrumentMapper.toInstrument(bullet).kind).isEqualTo(InstrumentKind.BULLET)
    }

    @Test
    fun `an outstanding that disagrees with the remaining schedule is a broken contract, not repaired`() {
        val loan = lendingLoan().let { it.copy(outstandingPrincipal = it.outstandingPrincipal.add(BigDecimal("0.01"))) }
        assertThatThrownBy { LoanInstrumentMapper.toInstrument(loan) }
            .isInstanceOf(InvalidLoanContractException::class.java)
            .hasMessageContaining("sum of remaining principal")
    }

    @Test
    fun `a floating loan on an index the engine has no curve type for is refused`() {
        val loan = lendingLoan(floating = Triple("LIBOR_3M", "0.01", 3), nextResetDate = AS_OF.plusMonths(1))
        assertThatThrownBy { LoanInstrumentMapper.toInstrument(loan) }
            .isInstanceOf(InvalidLoanContractException::class.java)
            .hasMessageContaining("LIBOR_3M")
    }

    // --- positions and tie-out ------------------------------------------------------------------

    @Test
    fun `with loans read, Loans Receivable is carried by LOAN positions, never also at GL level`() {
        val a = lendingLoan(id = LOAN_A)
        val b = lendingLoan(id = LOAN_B, principal = "6000.00")
        val total = a.outstandingPrincipal.add(b.outstandingPrincipal)

        val positions = PositionBuilder.build(tiedOutWithLoans(total), instruments(a, b))

        assertThat(positions.filter { it.glAccountCode == "1200" }.map { it.kind })
            .containsOnly(PositionKind.LOAN)
            .hasSize(2)
        assertThat(positions.filter { it.kind == PositionKind.LOAN }.map { it.instrumentId })
            .containsExactlyInAnyOrder(LOAN_A.toString(), LOAN_B.toString())
    }

    @Test
    fun `without the loan-book read, Loans Receivable stays a GL position as before D4`() {
        val positions = PositionBuilder.build(tiedOutWithLoans(BigDecimal("100.00")))
        assertThat(positions.single { it.glAccountCode == "1200" }.kind).isEqualTo(PositionKind.GL_ACCOUNT)
    }

    @Test
    fun `loans that sum to the ledger tie out`() {
        val a = lendingLoan(id = LOAN_A)
        val b = lendingLoan(id = LOAN_B, principal = "6000.00")
        val total = a.outstandingPrincipal.add(b.outstandingPrincipal)

        assertThat(tieOut(total.toPlainString(), a, b).status).isEqualTo(TieOutStatus.TIED_OUT)
    }

    @Test
    fun `lending short of the ledger is UNTIED on 1200 - the GL figure never stands in for a missing loan`() {
        val a = lendingLoan()
        val ledger = a.outstandingPrincipal.add(BigDecimal("500.00"))

        val result = tieOut(ledger.toPlainString(), a)

        assertThat(result.status).isEqualTo(TieOutStatus.UNTIED)
        val m = result.mismatches.single()
        assertThat(m.glAccountCode).isEqualTo("1200")
        assertThat(m.difference).isEqualByComparingTo("-500.00")
    }

    @Test
    fun `lending above the ledger is UNTIED on 1200`() {
        val a = lendingLoan()
        val ledger = a.outstandingPrincipal.subtract(BigDecimal("0.01"))

        val m = tieOut(ledger.toPlainString(), a).mismatches.single()

        assertThat(m.glAccountCode).isEqualTo("1200")
        assertThat(m.difference).isEqualByComparingTo("0.01")
    }

    @Test
    fun `an empty loan book against a ledger holding loans is UNTIED`() {
        val result = tiedOutWithLoans(BigDecimal("100.00"))
            .let { TieOut.check(it.trialBalance, PositionBuilder.build(it, emptyList())) }
        assertThat(result.mismatches.single().glAccountCode).isEqualTo("1200")
    }

    @Test
    fun `a loan in a currency the ledger holds no Loans Receivable for is UNTIED`() {
        val gbp = lendingLoan(currency = "GBP", glAccountCode = "1203")
        val result = tieOut("0", gbp)
        assertThat(result.mismatches.map { it.glAccountCode to it.currency }).containsExactly("1203" to "GBP")
    }

    @Test
    fun `a loan lending names no GL account for is UNMAPPED and UNTIED`() {
        val chf = lendingLoan(currency = "CHF", glAccountCode = null)
        val result = tieOut("0", chf)
        assertThat(result.mismatches.single().glAccountCode).isNull()
        assertThat(result.mismatches.single().currency).isEqualTo("CHF")
    }

    @Test
    fun `accrued interest and allowance stay GL positions next to the loans`() {
        val a = lendingLoan()
        val inputs = tiedOutWithLoans(a.outstandingPrincipal).let {
            it.copy(
                trialBalance =
                it.trialBalance + tb("1300", "ASSET", "CZK", "30.00", "0") + tb("1400", "ASSET", "CZK", "0", "30.00"),
            )
        }
        val positions = PositionBuilder.build(inputs, instruments(a))
        assertThat(positions.filter { it.glAccountCode in setOf("1300", "1400") }.map { it.kind })
            .containsOnly(PositionKind.GL_ACCOUNT)
        assertThat(TieOut.check(inputs.trialBalance, positions).status).isEqualTo(TieOutStatus.TIED_OUT)
    }

    // --- input hash -----------------------------------------------------------------------------

    @Test
    fun `the hash changes when a loan changes - one installment's interest by a cent`() {
        val inputs = tiedOutWithLoans(BigDecimal("1"))
        val a = lendingLoan()
        val changed = a.copy(
            remainingInstallments = a.remainingInstallments.mapIndexed { i, inst ->
                if (i == 0) inst.copy(interest = inst.interest.add(BigDecimal("0.01"))) else inst
            },
        )
        assertThat(InputHash.of(inputs, listOf(changed))).isNotEqualTo(InputHash.of(inputs, listOf(a)))
        assertThat(InputHash.of(inputs, listOf(a.copy(ifrs9Stage = "STAGE_2"))))
            .isNotEqualTo(InputHash.of(inputs, listOf(a)))
    }

    @Test
    fun `loan order and amount scale do not change the hash`() {
        val inputs = tiedOutWithLoans(BigDecimal("1"))
        val a = lendingLoan(id = LOAN_A)
        val b = lendingLoan(id = LOAN_B, principal = "6000.00")
        val rescaled = a.copy(outstandingPrincipal = a.outstandingPrincipal.setScale(4))
        assertThat(InputHash.of(inputs, listOf(b, rescaled))).isEqualTo(InputHash.of(inputs, listOf(a, b)))
    }

    @Test
    fun `not read, read-and-empty and read-with-loans are three different inputs, and not read is the old hash`() {
        val inputs = tiedOutWithLoans(BigDecimal("1"))
        val notRead = InputHash.of(inputs, null)
        val empty = InputHash.of(inputs, emptyList())
        val withLoan = InputHash.of(inputs, listOf(lendingLoan()))
        assertThat(setOf(notRead, empty, withLoan)).hasSize(3)
        assertThat(notRead).isEqualTo(InputHash.of(inputs))
    }
}
