// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.application.port.out

import com.openbank.libs.domain.money.Money
import io.smallrye.mutiny.Uni
import java.time.LocalDate
import java.util.UUID

/**
 * The customer credit leg of a capitalization (ADR-0033 §D) — the accounting fact that interest was
 * credited, expressed in the interest domain's own terms and left for the adapter to turn into a
 * balanced journal.
 *
 * This is an **accounting event, not a payment**: nothing is instructed, cleared or settled, so it
 * follows `openbank-lending-service`'s `LedgerPostingPort` shape (hand the ledger a journal) rather
 * than the withholding *remittance* path's `TransactionServiceClient.initiateTransaction` (instruct
 * a real payment to the finanční úřad). The two are easy to confuse because both touch tax money;
 * the remittance moves the bank's cash out, this only records that the customer is owed the net and
 * the state is owed the tax.
 *
 * ### Why the amounts are [Money] and not `BigDecimal`
 *
 * `LedgerService` re-wraps every incoming line as `Money.of(amount, currencyCode)`, and `Money`
 * refuses an amount whose scale exceeds the currency's minor units (CZK/EUR/USD/GBP = 2). A posting
 * carrying a raw `BigDecimal` therefore pushes that invariant across a network boundary, where it
 * surfaces as an opaque HTTP 400 on **every** capitalization instead of a compile-time obligation —
 * which is exactly what happened when this port was first written with three bare `BigDecimal`s at
 * scale 4. lending's `LendingJournalFactory` and transaction-service's `PaymentJournalFactory` are
 * immune by type (both read `.amount` off a `Money`); this port now is too. Rounding to the
 * currency's scale happens ONCE, at the source in `InterestService`, before this type is built.
 *
 * [gross] must equal [net] + [tax] and all three must share one currency — enforced here, in the
 * constructor, so an unbalanced or mixed-currency split cannot exist as a value at all.
 */
data class CapitalizationPosting(
    /** The customer account whose pocket is credited — the sub-ledger dimension of the deposit leg. */
    val accountId: UUID,
    val productId: String,
    /** Period end = the §38d credit date; part of the business identity of this capitalization. */
    val periodTo: LocalDate,
    /** Gross interest earned — the bank's expense. */
    val gross: Money,
    /** Withholding tax retained at source; zero for NOT_WITHHELD / EXEMPT / DEFERRED_FX. */
    val tax: Money,
    /** What the customer actually receives (`gross − tax`). */
    val net: Money,
) {
    init {
        require(gross.currency == net.currency && gross.currency == tax.currency) {
            "Refusing a mixed-currency interest capitalization for account=$accountId " +
                "product=$productId period=$periodTo: gross=$gross net=$net tax=$tax"
        }
        require(gross.amount.compareTo(net.amount.add(tax.amount)) == 0) {
            "Refusing to post an unbalanced interest capitalization for account=$accountId " +
                "product=$productId period=$periodTo: gross=${gross.amount} != " +
                "net=${net.amount} + tax=${tax.amount}"
        }
    }

    /** The single ISO-4217 code all three legs are denominated in. */
    val currency: String get() = gross.currency.code
}

/**
 * Posts the capitalization split to `openbank-ledger-service`.
 *
 * Implementations MUST be idempotent on the posting's **business identity**
 * (`account, product, period end`) so that a retry after a crash collapses onto the already-booked
 * journal instead of crediting the customer twice — see
 * [CapitalizationJournalFactory][com.openbank.interest.infrastructure.client.CapitalizationJournalFactory].
 */
interface LedgerPostingPort {
    fun post(posting: CapitalizationPosting): Uni<Unit>
}
