// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.client

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.util.UUID

/**
 * Ledger-posting configuration for the lending book: the system actor recorded as the journal author
 * and the leaf GL accounts each posting kind debits/credits. Defaults are stable placeholder UUIDs
 * (the `a0000000-…` family the ledger seeds); operators override them to the real chart of accounts.
 *
 * [Gl.fundingClearing] is the one exception to the "last UUID segment = account code" convention:
 * openbank-ledger-service's shared "Customer Cash Clearing" account (code 1100, the same account
 * openbank-transaction-service's PaymentJournalFactory posts against) was seeded in V3 with a
 * pre-convention sequential id (`…-000001`), before the code-based-suffix convention existed — so
 * this default deliberately points at `…-000001`, not `…-001100`. A prior `…-001100` default 422ed
 * every real disbursement/repayment/interest posting with "GL account not found" (caught by the
 * nightly full-fleet build, issue #1720, once #1623 started deriving the lending consumer pact body
 * from this config instead of a hand-written literal).
 */
@ConfigMapping(prefix = "lending.ledger")
interface LendingLedgerConfig {

    @WithDefault("00000000-0000-0000-0000-0000000000aa")
    fun systemActorId(): UUID

    fun gl(): Gl

    interface Gl {
        @WithDefault("a0000000-0000-0000-0000-000000001200")
        fun loansReceivable(): UUID

        @WithDefault("a0000000-0000-0000-0000-000000000001")
        fun fundingClearing(): UUID

        @WithDefault("a0000000-0000-0000-0000-000000004100")
        fun interestIncome(): UUID

        @WithDefault("a0000000-0000-0000-0000-000000001300")
        fun interestReceivable(): UUID

        @WithDefault("a0000000-0000-0000-0000-000000005100")
        fun loanLossExpense(): UUID

        /** Contra-asset carrying accumulated IFRS 9 impairment (ADR-0028 Phase 3). */
        @WithDefault("a0000000-0000-0000-0000-000000001400")
        fun loanLossAllowance(): UUID
    }

    /** Snapshot the GL accounts into the plain holder the pure factory consumes. */
    fun accounts(): LendingGlAccounts = LendingGlAccounts(
        loansReceivable = gl().loansReceivable(),
        fundingClearing = gl().fundingClearing(),
        interestIncome = gl().interestIncome(),
        interestReceivable = gl().interestReceivable(),
        loanLossExpense = gl().loanLossExpense(),
        loanLossAllowance = gl().loanLossAllowance(),
    )
}
