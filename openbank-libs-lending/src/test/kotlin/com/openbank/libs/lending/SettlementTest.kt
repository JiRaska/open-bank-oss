// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending

import com.openbank.libs.domain.money.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Covers ADR-0215 D2/D4: deterministic settlement quotes and statutory withdrawal interest. */
class SettlementTest {

    private fun eur(v: String) = Money.of(v, "EUR")

    private val schedule = Amortization.schedule(
        principal = eur("12000.00"),
        nominalAnnualRate = BigDecimal("0.12"),
        termPeriods = 12,
        firstDueDate = LocalDate.parse("2026-06-30"),
    )

    @Test
    fun `quote after one paid installment reconciles principal, day interest and compensation`() {
        val quote = Settlement.quote(
            schedule = schedule,
            paidThroughInstallment = 1,
            asOf = LocalDate.parse("2026-07-15"),
            contractualCompensationRate = BigDecimal("0.01"),
            legalCompensationCap = BigDecimal("0.01"),
        )

        assertThat(quote.outstandingPrincipal).isEqualTo(eur("11053.81"))
        assertThat(quote.accruedInterest).isEqualTo(eur("54.51"))
        assertThat(quote.compensation).isEqualTo(eur("110.54"))
        assertThat(quote.total).isEqualTo(eur("11218.86"))
        assertThat(quote.compensationCapped).isFalse()
        assertThat(quote.validUntil).isEqualTo(LocalDate.parse("2026-08-14"))
    }

    @Test
    fun `quote before any repayment starts from the full principal`() {
        val quote = Settlement.quote(
            schedule = schedule,
            paidThroughInstallment = 0,
            asOf = LocalDate.parse("2026-06-14"),
            contractualCompensationRate = BigDecimal("0.01"),
            legalCompensationCap = BigDecimal("0.01"),
        )

        assertThat(quote.outstandingPrincipal).isEqualTo(eur("12000.00"))
        assertThat(quote.accruedInterest).isEqualTo(eur("59.18"))
    }

    @Test
    fun `quote on a due date carries zero accrued interest`() {
        val quote = Settlement.quote(
            schedule = schedule,
            paidThroughInstallment = 1,
            asOf = LocalDate.parse("2026-06-30"),
            contractualCompensationRate = BigDecimal("0.01"),
            legalCompensationCap = BigDecimal("0.01"),
        )

        assertThat(quote.accruedInterest.isZero()).isTrue()
    }

    @Test
    fun `compensation above the pack cap is cut to the cap and marked capped`() {
        val quote = Settlement.quote(
            schedule = schedule,
            paidThroughInstallment = 1,
            asOf = LocalDate.parse("2026-06-30"),
            contractualCompensationRate = BigDecimal("0.015"),
            legalCompensationCap = BigDecimal("0.01"),
        )

        assertThat(quote.compensation).isEqualTo(eur("110.54"))
        assertThat(quote.compensationCapped).isTrue()
    }

    @Test
    fun `null pack cap means no compensation at all`() {
        val quote = Settlement.quote(
            schedule = schedule,
            paidThroughInstallment = 1,
            asOf = LocalDate.parse("2026-06-30"),
            contractualCompensationRate = BigDecimal("0.01"),
            legalCompensationCap = null,
        )

        assertThat(quote.compensation.isZero()).isTrue()
    }

    @Test
    fun `unapplied overpayment reduces the settlement total`() {
        val quote = Settlement.quote(
            schedule = schedule,
            paidThroughInstallment = 1,
            asOf = LocalDate.parse("2026-06-30"),
            contractualCompensationRate = BigDecimal.ZERO,
            legalCompensationCap = null,
            unappliedCredit = eur("500.00"),
        )

        assertThat(quote.total).isEqualTo(eur("10553.81"))
    }

    @Test
    fun `withdrawal interest is the statutory day interest for the drawn period`() {
        val interest = Settlement.withdrawalInterest(
            principal = eur("12000.00"),
            nominalAnnualRate = BigDecimal("0.12"),
            disbursedAt = LocalDate.parse("2026-06-01"),
            withdrawnAt = LocalDate.parse("2026-06-15"),
        )

        assertThat(interest).isEqualTo(eur("55.23"))
    }

    @Test
    fun `same inputs always produce the same quote — the number is reproducible`() {
        val first = Settlement.quote(schedule, 1, LocalDate.parse("2026-07-15"), BigDecimal("0.01"), BigDecimal("0.01"))
        val second = Settlement.quote(
            schedule,
            1,
            LocalDate.parse("2026-07-15"),
            BigDecimal("0.01"),
            BigDecimal("0.01"),
        )

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `mixed currencies are refused, never silently converted`() {
        assertThatThrownBy {
            Settlement.quote(
                schedule = schedule,
                paidThroughInstallment = 1,
                asOf = LocalDate.parse("2026-06-30"),
                contractualCompensationRate = BigDecimal.ZERO,
                legalCompensationCap = null,
                unappliedCredit = Money.of("10.00", "CZK"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
