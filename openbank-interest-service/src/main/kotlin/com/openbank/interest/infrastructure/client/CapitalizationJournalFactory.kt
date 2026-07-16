// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.client

import com.openbank.interest.application.port.out.CapitalizationPosting
import java.math.BigDecimal
import java.util.UUID

/**
 * Turns a [CapitalizationPosting] into a balanced double-entry [PostJournalRequest] for
 * ledger-service (ADR-0033 §D). Pure and side-effect-free so the accounting is fully unit-tested
 * without any HTTP — the same shape as lending's `LendingJournalFactory`.
 *
 * The split, per capitalization:
 * ```
 *   DEBIT  4010/4011/4012/4013  Interest Expense <ccy>          gross
 *   CREDIT 2100/2101/2102/2103  Customer Deposit Control <ccy>  net    (subAccountId = accountId)
 *   CREDIT 2200                 Withholding Tax Payable          tax
 * ```
 * `gross = net + tax` (guaranteed by `WithholdingTaxPolicy`), so the entry balances within the
 * single currency it is denominated in — every leg carries the same `baseCurrencyCode`, which is
 * also each account's declared currency, as `LedgerService` requires.
 *
 * **Zero-amount legs are omitted, never posted.** A zero tax is the common, legitimate case: the
 * statutory whole-CZK DOWN rounding (daňový řád) means any gross below 7.00 CZK yields
 * `floor(gross) × 0.15 = 0` while the interest is still *withheld* in treatment, and
 * NOT_WITHHELD / EXEMPT / DEFERRED_FX are zero by definition. The ledger both requires ≥2 lines and
 * enforces `CHECK (amount > 0)` on every line, so emitting the tax leg at zero would fail the whole
 * entry — the customer's credit included.
 */
object CapitalizationJournalFactory {

    /**
     * Customer deposit-control leaf accounts, per currency — the customer's liability pocket
     * (2100=CZK, 2101=EUR, 2102=USD, 2103=GBP). These are the *same* stable ids
     * `openbank-transaction-service`'s `PaymentJournalFactory` posts against, deliberately: a
     * customer's booked balance is the sum of their payments AND their interest over one
     * sub-ledger, so interest must not land on a control account of its own. They are also the only
     * account class the ledger accepts a `subAccountId` on (ADR-0039 Phase B).
     */
    private val DEPOSIT_CONTROL: Map<String, UUID> = mapOf(
        "CZK" to UUID.fromString("a0000000-0000-0000-0000-000000000002"),
        "EUR" to UUID.fromString("a0000000-0000-0000-0000-000000002101"),
        "USD" to UUID.fromString("a0000000-0000-0000-0000-000000002102"),
        "GBP" to UUID.fromString("a0000000-0000-0000-0000-000000002103"),
    )

    /**
     * The ledger idempotency key for a capitalization, derived from its **business identity** —
     * `(account, product, period end)` — and NOT from `InterestCapitalization.id`.
     *
     * This is the difference between exactly-once and double-crediting real money. `cap.id` defaults
     * to a fresh UUID on every construction, so a crash between the ledger post and the local commit
     * would, on retry, build a new capitalization with a new id, mint a new key, and post the credit
     * a SECOND time. A business-derived key replays onto the same journal and the ledger collapses
     * it (`findByIdempotencyKey` returns the original entry before any work is done).
     *
     * The identity matches interest-service's own DB backstop — the V6 unique index on
     * `(account_id, product_id, period_to)` — on purpose: the two must agree on what "the same
     * capitalization" means, or one of them is wrong. Mirrors lending's business-derived
     * `loan:{id}:inst:{n}:accrual`.
     */
    fun idempotencyKey(posting: CapitalizationPosting): String =
        "interest-capitalization-${posting.accountId}-${posting.productId}-${posting.periodTo}"

    fun buildRequest(posting: CapitalizationPosting, config: InterestLedgerConfig): PostJournalRequest {
        val key = idempotencyKey(posting)
        return PostJournalRequest(
            idempotencyKey = key,
            // Deterministic in the same business identity as the key: a retry must not mint a new
            // transactionId, or the replayed entry would look like a different economic event.
            transactionId = UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)),
            entryDate = posting.periodTo.toString(),
            valueDate = posting.periodTo.toString(),
            description = "Interest capitalization ${posting.productId} to ${posting.periodTo}",
            lines = buildLines(posting, config),
            createdBy = config.systemActorId(),
        )
    }

    fun buildLines(posting: CapitalizationPosting, config: InterestLedgerConfig): List<JournalLineRequest> {
        val ccy = posting.currency.uppercase()
        require(posting.grossAmount.compareTo(posting.netAmount.add(posting.taxAmount)) == 0) {
            "Refusing to post an unbalanced interest capitalization for account=${posting.accountId} " +
                "product=${posting.productId} period=${posting.periodTo}: gross=${posting.grossAmount} != " +
                "net=${posting.netAmount} + tax=${posting.taxAmount}"
        }
        val expenseGl = interestExpense(ccy, config)
        val depositGl = depositControl(ccy)
        // Only money-bearing legs. See the class note: a zero leg is DB-rejected, and zero tax is
        // the normal case for sub-7-CZK gross and for every non-withheld treatment.
        return listOfNotNull(
            line(expenseGl, "DEBIT", posting.grossAmount, ccy),
            line(depositGl, "CREDIT", posting.netAmount, ccy, subAccountId = posting.accountId),
            line(config.gl().withholdingTaxPayable(), "CREDIT", posting.taxAmount, ccy),
        )
    }

    private fun line(
        glAccountId: UUID,
        side: String,
        amount: BigDecimal,
        ccy: String,
        subAccountId: UUID? = null,
    ): JournalLineRequest? {
        if (amount.signum() <= 0) return null
        return JournalLineRequest(
            glAccountId = glAccountId,
            side = side,
            amount = amount,
            currencyCode = ccy,
            // Single-currency entry: no conversion, so the base amount is the leg's own amount and
            // the ledger balances it within `ccy` (ADR-0025).
            fxRate = null,
            baseAmount = amount,
            baseCurrencyCode = ccy,
            subAccountId = subAccountId,
        )
    }

    /**
     * The interest-expense GL is per currency because `LedgerService` rejects a line whose base
     * currency differs from its GL account's declared currency (422) — the V14 cash-clearing bug.
     */
    private fun interestExpense(currency: String, config: InterestLedgerConfig): UUID = when (currency) {
        "CZK" -> config.gl().interestExpenseCzk()
        "EUR" -> config.gl().interestExpenseEur()
        "USD" -> config.gl().interestExpenseUsd()
        "GBP" -> config.gl().interestExpenseGbp()
        // Refuse rather than guess: posting a foreign credit against a CZK expense account would be
        // rejected by the ledger anyway, and picking "some" account would misstate the P&L.
        else -> error("No interest-expense GL account seeded for currency $currency")
    }

    private fun depositControl(currency: String): UUID = DEPOSIT_CONTROL[currency]
        ?: error("No deposit-control GL account seeded for currency $currency")
}
