// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.application.port.out

import io.smallrye.mutiny.Uni
import java.math.BigDecimal
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
 * [grossAmount] must equal [netAmount] + [taxAmount] — [WithholdingTaxPolicy][com.openbank.interest
 * .domain.tax.WithholdingTaxPolicy] guarantees it by construction (`net = gross − tax`), and it is
 * what makes the resulting three-leg entry balance within [currency].
 */
data class CapitalizationPosting(
    /** The customer account whose pocket is credited — the sub-ledger dimension of the deposit leg. */
    val accountId: UUID,
    val productId: String,
    /** Period end = the §38d credit date; part of the business identity of this capitalization. */
    val periodTo: LocalDate,
    val currency: String,
    /** Gross interest earned — the bank's expense. */
    val grossAmount: BigDecimal,
    /** Withholding tax retained at source; zero for NOT_WITHHELD / EXEMPT / DEFERRED_FX. */
    val taxAmount: BigDecimal,
    /** What the customer actually receives (`gross − tax`). */
    val netAmount: BigDecimal,
)

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
