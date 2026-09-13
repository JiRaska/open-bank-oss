// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.lending.application.port.out

import com.openbank.libs.domain.money.Money
import io.smallrye.mutiny.Uni
import java.util.UUID

/**
 * Resolves the borrower's own CURRENT account for a disbursement — a party may hold several
 * accounts (checking, savings), and a loan is disbursed to the one the customer actually spends
 * from, in the loan's own currency.
 */
interface BorrowerAccountLookupPort {
    /** The party's CURRENT account in [currency], or null if the party has none. */
    fun findCurrentAccount(partyId: UUID, currency: String): Uni<UUID?>
}

/**
 * Credits the borrower's account with disbursed loan cash, or debits it to unwind a disbursement
 * (CCD2 cooling-off withdrawal). The loan book's own ledger journal
 * ([com.openbank.lending.infrastructure.client.LendingJournalFactory]) only ever touches internal
 * GL accounts — Loans Receivable and Funding Clearing — neither of which is the customer's own
 * account. Crediting the customer is a second, separate booking against transaction-service, the
 * same shape `account-service` uses for the welcome bonus.
 */
interface BorrowerCreditPort {
    /** Idempotent per [reference] — a retried disbursement call is a no-op, not a second credit. */
    fun credit(reference: String, borrowerAccountId: UUID, amount: Money): Uni<Unit>

    /** Idempotent per [reference]. */
    fun debit(reference: String, borrowerAccountId: UUID, amount: Money): Uni<Unit>
}
