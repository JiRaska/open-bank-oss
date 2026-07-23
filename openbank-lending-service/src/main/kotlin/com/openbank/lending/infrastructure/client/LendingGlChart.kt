// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.client

import java.util.UUID

/**
 * The per-currency leaf GL accounts ledger-service seeds for the loan book (issue #1275).
 *
 * A loan's currency is **client-supplied** (`Loan.principal: Money`, positivity-checked only — never
 * coerced to CZK; EUR is the canonical test/fixture currency), and ledger-service **422s a journal
 * line whose currency does not match its GL account's `currency_code`** (`LedgerService` line/account
 * currency-match check, confirmed live). So a disbursement/repayment/interest/write-off/provisioning
 * posting must land on the account variant matching the loan's own currency — exactly as
 * `openbank-transaction-service`'s `PaymentJournalFactory` selects `CASH_CLEARING` / `DEPOSIT_CONTROL`
 * by the transaction currency. A single fixed (CZK) account set — the shape before this change
 * (V19 + `LendingLedgerConfig.Gl` @WithDefault UUIDs) — 422s the first non-CZK disbursement, uncaught
 * because unit tests use the pure factory, the outbox IT runs dispatch-disabled, and the pact fixed CZK.
 *
 * Codes follow the chart's per-currency convention: `XX00` CZK / `XX01` EUR / `XX02` USD / `XX03` GBP.
 * CZK leaves were seeded in `V19__lending_book_accounts.sql`; EUR/USD/GBP in
 * `V20__lending_book_accounts_per_currency.sql`. These are platform-fixed seeded accounts (not
 * per-deployment), so — like `PaymentJournalFactory` — they are held here, not in configuration:
 * a `@WithDefault` placeholder is the exact footgun that let #1275/#1720 boot green and 422 at first
 * posting instead of failing loud. `LendingLedgerConfig` keeps only the system actor id.
 */
object LendingGlChart {

    private val LOANS_RECEIVABLE = leafSet(czk = "1200", eur = "1201", usd = "1202", gbp = "1203")
    private val INTEREST_RECEIVABLE = leafSet(czk = "1300", eur = "1301", usd = "1302", gbp = "1303")
    private val LOAN_LOSS_ALLOWANCE = leafSet(czk = "1400", eur = "1401", usd = "1402", gbp = "1403")
    private val INTEREST_INCOME = leafSet(czk = "4100", eur = "4101", usd = "4102", gbp = "4103")
    private val LOAN_LOSS_EXPENSE = leafSet(czk = "5100", eur = "5101", usd = "5102", gbp = "5103")

    /**
     * Funding clearing IS the shared "Customer Cash Clearing" account transaction-service posts
     * against — a loan's cash leg lands in the same clearing account as customer payments. These
     * UUIDs MUST stay identical to `PaymentJournalFactory.CASH_CLEARING`: CZK was seeded
     * pre-convention in V3 as `…-000001` (not `…-001100`); EUR/USD/GBP are `1101`/`1102`/`1103`
     * (V14). Kept in sync by `LendingGlChartTest`; extracting a shared `openbank-libs` constant so the
     * two services cannot drift is a tracked follow-up (#1275).
     */
    private val FUNDING_CLEARING = mapOf(
        "CZK" to UUID.fromString("a0000000-0000-0000-0000-000000000001"),
        "EUR" to UUID.fromString("a0000000-0000-0000-0000-000000001101"),
        "USD" to UUID.fromString("a0000000-0000-0000-0000-000000001102"),
        "GBP" to UUID.fromString("a0000000-0000-0000-0000-000000001103"),
    )

    /** The currencies with a seeded lending GL account set. Loans in any other currency fail loud. */
    val supportedCurrencies: Set<String> = FUNDING_CLEARING.keys

    /**
     * The leaf GL account set for [currency]. Fails loud on an unseeded currency rather than resolving
     * to a plausible-but-wrong account — a missing seed must surface as a boot/posting-time error, not
     * a silent mis-post (the #1275 lesson).
     */
    fun accountsFor(currency: String): LendingGlAccounts {
        check(currency in supportedCurrencies) {
            "No lending GL accounts seeded for currency $currency " +
                "(supported: ${supportedCurrencies.sorted()}) — add the per-currency leaves to a ledger migration"
        }
        return LendingGlAccounts(
            loansReceivable = LOANS_RECEIVABLE.getValue(currency),
            fundingClearing = FUNDING_CLEARING.getValue(currency),
            interestIncome = INTEREST_INCOME.getValue(currency),
            interestReceivable = INTEREST_RECEIVABLE.getValue(currency),
            loanLossExpense = LOAN_LOSS_EXPENSE.getValue(currency),
            loanLossAllowance = LOAN_LOSS_ALLOWANCE.getValue(currency),
        )
    }

    private fun leafSet(czk: String, eur: String, usd: String, gbp: String): Map<String, UUID> =
        mapOf("CZK" to leaf(czk), "EUR" to leaf(eur), "USD" to leaf(usd), "GBP" to leaf(gbp))

    /** `a0000000-…-<code>` with the numeric account code zero-padded into the last UUID segment. */
    private fun leaf(code: String): UUID = UUID.fromString("a0000000-0000-0000-0000-%012d".format(code.toLong()))
}
