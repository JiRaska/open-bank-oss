// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.client

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The loan book posts in the loan's own currency, and ledger-service 422s a line whose currency does
 * not match its GL account's currency (issue #1275). [LendingGlChart] must therefore resolve a full
 * leaf set per supported currency and fail loud on an unseeded one.
 */
class LendingGlChartTest {

    private fun uuid(code: String) = UUID.fromString("a0000000-0000-0000-0000-%012d".format(code.toLong()))

    @Test
    fun `resolves the CZK leaves (seeded in V19)`() {
        val a = LendingGlChart.accountsFor("CZK")
        assertThat(a.loansReceivable).isEqualTo(uuid("1200"))
        assertThat(a.interestReceivable).isEqualTo(uuid("1300"))
        assertThat(a.loanLossAllowance).isEqualTo(uuid("1400"))
        assertThat(a.interestIncome).isEqualTo(uuid("4100"))
        assertThat(a.loanLossExpense).isEqualTo(uuid("5100"))
        // Funding clearing = shared Customer Cash Clearing, seeded pre-convention in V3 as …-000001.
        assertThat(a.fundingClearing).isEqualTo(UUID.fromString("a0000000-0000-0000-0000-000000000001"))
    }

    @Test
    fun `resolves the EUR leaves (seeded in V20)`() {
        val a = LendingGlChart.accountsFor("EUR")
        assertThat(a.loansReceivable).isEqualTo(uuid("1201"))
        assertThat(a.interestReceivable).isEqualTo(uuid("1301"))
        assertThat(a.loanLossAllowance).isEqualTo(uuid("1401"))
        assertThat(a.interestIncome).isEqualTo(uuid("4101"))
        assertThat(a.loanLossExpense).isEqualTo(uuid("5101"))
        assertThat(a.fundingClearing).isEqualTo(uuid("1101"))
    }

    @Test
    fun `resolves USD and GBP leaves`() {
        assertThat(LendingGlChart.accountsFor("USD").loansReceivable).isEqualTo(uuid("1202"))
        assertThat(LendingGlChart.accountsFor("USD").fundingClearing).isEqualTo(uuid("1102"))
        assertThat(LendingGlChart.accountsFor("GBP").loansReceivable).isEqualTo(uuid("1203"))
        assertThat(LendingGlChart.accountsFor("GBP").fundingClearing).isEqualTo(uuid("1103"))
    }

    @Test
    fun `supports exactly the four platform currencies`() {
        assertThat(LendingGlChart.supportedCurrencies).containsExactlyInAnyOrder("CZK", "EUR", "USD", "GBP")
    }

    @Test
    fun `fails loud on an unseeded currency rather than mis-posting`() {
        assertThatThrownBy { LendingGlChart.accountsFor("JPY") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("JPY")
    }

    /**
     * Drift guard: lending's funding-clearing UUIDs MUST equal transaction-service's
     * PaymentJournalFactory.CASH_CLEARING (a loan's cash leg and a payment's cash leg are the same
     * account). PaymentJournalFactory is in another module and cannot be imported here, so the
     * contract is pinned by asserting the exact known UUIDs — change either side and this fails.
     */
    @Test
    fun `funding-clearing stays pinned to the shared cash-clearing accounts`() {
        assertThat(LendingGlChart.accountsFor("CZK").fundingClearing)
            .isEqualTo(UUID.fromString("a0000000-0000-0000-0000-000000000001"))
        assertThat(LendingGlChart.accountsFor("EUR").fundingClearing).isEqualTo(uuid("1101"))
        assertThat(LendingGlChart.accountsFor("USD").fundingClearing).isEqualTo(uuid("1102"))
        assertThat(LendingGlChart.accountsFor("GBP").fundingClearing).isEqualTo(uuid("1103"))
    }
}
