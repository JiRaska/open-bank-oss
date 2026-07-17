// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.client

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.util.UUID

/**
 * Ledger-posting configuration for interest capitalization (ADR-0033 §D): the system actor recorded
 * as the journal author, and the leaf GL accounts the capitalization split debits/credits. Defaults
 * are the stable `a0000000-…` UUIDs the ledger seeds; operators override them per environment to the
 * real chart of accounts. Same convention as `openbank-billing-service`'s `BillingLedgerConfig`.
 *
 * Deposit-control accounts are NOT configured here: they are picked per currency by
 * [CapitalizationJournalFactory] from the same stable ids `openbank-transaction-service`'s
 * `PaymentJournalFactory` posts against, because both must land on the *same* sub-ledger for a
 * customer's booked balance to be the sum of their payments and their interest.
 */
@ConfigMapping(prefix = "interest.ledger")
interface InterestLedgerConfig {

    /** The interest service's own system-actor id, recorded as the journal's `createdBy`. */
    @WithDefault("00000000-0000-0000-0000-0000000000cc")
    fun systemActorId(): UUID

    fun gl(): Gl

    interface Gl {
        /**
         * DEBIT (gross) — the bank's interest-expense GL for CZK. Default is the seeded
         * "4010 Interest Expense CZK" row (`V17__interest_capitalization_accounts.sql`), NOT the
         * older "4000 Interest Expense" row from `V1__init_ledger.sql`: V1 seeded that one with
         * `gen_random_uuid()`, so no fixed UUID can ever reference it (issue #468's defect — every
         * posting would 422 "GL account not found"), and it is CZK-only, so it could not carry a
         * non-CZK expense leg either.
         */
        @WithDefault("a0000000-0000-0000-0000-000000004010")
        fun interestExpenseCzk(): UUID

        /** DEBIT (gross) — interest-expense GL for EUR ("4011", V17). */
        @WithDefault("a0000000-0000-0000-0000-000000004011")
        fun interestExpenseEur(): UUID

        /** DEBIT (gross) — interest-expense GL for USD ("4012", V17). */
        @WithDefault("a0000000-0000-0000-0000-000000004012")
        fun interestExpenseUsd(): UUID

        /** DEBIT (gross) — interest-expense GL for GBP ("4013", V17). */
        @WithDefault("a0000000-0000-0000-0000-000000004013")
        fun interestExpenseGbp(): UUID

        /**
         * CREDIT (tax) — the withholding-tax liability owed to the finanční úřad ("2200", V17).
         * CZK-only by construction: ADR-0033 §E withholds only CZK-denominated interest, so this
         * leg never appears on a foreign-currency entry.
         */
        @WithDefault("a0000000-0000-0000-0000-000000002200")
        fun withholdingTaxPayable(): UUID
    }
}
