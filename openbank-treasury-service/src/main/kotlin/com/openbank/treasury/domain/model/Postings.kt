// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.model

import java.math.BigDecimal
import java.util.UUID

enum class Side { DEBIT, CREDIT }

/** The ledger events that move value. `BOOKED` posts nothing (no off-balance commitment in the MVP). */
enum class PostingEvent(val key: String) {
    SETTLED("settled"),
    MATURED("matured"),
    REVERSED("reversed"),
}

data class PostingLine(val glCode: String, val side: Side, val amount: BigDecimal, val currency: String)

/**
 * A balanced journal for one deal event. [idempotencyKey] is `treasury:<dealId>:<event>` (ADR-0315
 * D5): a retried post returns the ledger's original entry and never double-posts.
 */
data class JournalSpec(val dealId: UUID, val event: PostingEvent, val lines: List<PostingLine>) {
    val idempotencyKey: String get() = "treasury:$dealId:${event.key}"

    init {
        require(lines.size >= 2) { "a journal needs at least two lines" }
        lines.groupBy { it.currency }.forEach { (ccy, ls) ->
            val dr = ls.filter { it.side == Side.DEBIT }.sumOf { it.amount }
            val cr = ls.filter { it.side == Side.CREDIT }.sumOf { it.amount }
            check(dr.compareTo(cr) == 0) { "journal for $dealId/${event.key} unbalanced in $ccy: Dr $dr != Cr $cr" }
        }
        require(lines.all { it.amount.signum() > 0 }) { "every journal line amount must be positive" }
    }
}

/**
 * The treasury chart (ledger migration `V29__treasury_money_market_accounts.sql`), by currency.
 *
 * | purpose                          | CZK  | EUR  | type      |
 * |----------------------------------|------|------|-----------|
 * | Nostro                           | 1001 | 1002 | ASSET     |
 * | MM placements with banks         | 1500 | 1501 | ASSET     |
 * | Deposit facility at ČNB          | 1510 | —    | ASSET     |
 * | Accrued interest receivable (MM) | 1520 | 1521 | ASSET     |
 * | MM borrowings from banks         | 2300 | 2301 | LIABILITY |
 * | Accrued interest payable (MM)    | 2310 | 2311 | LIABILITY |
 * | MM interest income               | 4200 | 4201 | INCOME    |
 * | MM interest expense              | 5200 | 5201 | EXPENSE   |
 *
 * The accrued-interest accounts are seeded for the daily-accrual follow-up; the MVP recognises
 * interest in one amount at maturity and does not post to them.
 */
object TreasuryChart {
    private val byCurrency: Map<String, Map<String, String>> = mapOf(
        Deal.CZK to mapOf(
            "nostro" to "1001", "placement" to "1500", "cnb" to "1510",
            "borrowing" to "2300", "income" to "4200", "expense" to "5200",
        ),
        Deal.EUR to mapOf(
            "nostro" to "1002", "placement" to "1501",
            "borrowing" to "2301", "income" to "4201", "expense" to "5201",
        ),
    )

    fun code(currency: String, purpose: String): String =
        byCurrency[currency]?.get(purpose) ?: error("no treasury GL account for $purpose in $currency")

    /** Fixed ids seeded by the ledger migration: `a0000000-0000-0000-0000-00000000<code>`. */
    fun glAccountId(code: String): UUID = UUID.fromString("a0000000-0000-0000-0000-00000000$code")

    /** Every code the treasury posts to — the ledger migration must seed each one. */
    val postedCodes: Set<String> get() = byCurrency.values.flatMap { it.values }.toSet()
}

/**
 * The posting table (ADR-0315 D5). Pure: a deal and an event in, a balanced journal out.
 *
 * | product              | SETTLED                        | MATURED                                   |
 * |----------------------|--------------------------------|-------------------------------------------|
 * | MM_PLACEMENT         | Dr placement / Cr nostro  (P)  | Dr nostro (P+I) / Cr placement (P) / Cr income (I) |
 * | CNB_DEPOSIT_FACILITY | Dr ČNB 1510 / Cr nostro   (P)  | Dr nostro (P+I) / Cr 1510 (P) / Cr income (I)       |
 * | MM_BORROWING         | Dr nostro / Cr borrowing  (P)  | Dr borrowing (P) / Dr expense (I) / Cr nostro (P+I) |
 *
 * REVERSED (from SETTLED) is the settlement journal with every side flipped, posted as a new
 * offsetting journal under its own key; from BOOKED nothing had posted, so nothing is reversed.
 * A zero-interest deal (rate 0) posts no interest line.
 */
object PostingRules {

    fun settlement(deal: Deal): JournalSpec {
        val p = deal.principal
        val ccy = deal.currency
        val nostro = TreasuryChart.code(ccy, "nostro")
        val lines = when (deal.product) {
            ProductType.MM_PLACEMENT -> listOf(
                PostingLine(TreasuryChart.code(ccy, "placement"), Side.DEBIT, p, ccy),
                PostingLine(nostro, Side.CREDIT, p, ccy),
            )
            ProductType.CNB_DEPOSIT_FACILITY -> listOf(
                PostingLine(TreasuryChart.code(ccy, "cnb"), Side.DEBIT, p, ccy),
                PostingLine(nostro, Side.CREDIT, p, ccy),
            )
            ProductType.MM_BORROWING -> listOf(
                PostingLine(nostro, Side.DEBIT, p, ccy),
                PostingLine(TreasuryChart.code(ccy, "borrowing"), Side.CREDIT, p, ccy),
            )
        }
        return JournalSpec(deal.id, PostingEvent.SETTLED, lines)
    }

    fun maturity(deal: Deal): JournalSpec {
        val p = deal.principal
        val i = deal.interest
        val ccy = deal.currency
        val nostro = TreasuryChart.code(ccy, "nostro")
        val hasInterest = i.signum() > 0
        val lines = when (deal.product) {
            ProductType.MM_PLACEMENT, ProductType.CNB_DEPOSIT_FACILITY -> {
                val asset = TreasuryChart.code(ccy, if (deal.product == ProductType.MM_PLACEMENT) "placement" else "cnb")
                listOfNotNull(
                    PostingLine(nostro, Side.DEBIT, p + i, ccy),
                    PostingLine(asset, Side.CREDIT, p, ccy),
                    if (hasInterest) PostingLine(TreasuryChart.code(ccy, "income"), Side.CREDIT, i, ccy) else null,
                )
            }
            ProductType.MM_BORROWING -> listOfNotNull(
                PostingLine(TreasuryChart.code(ccy, "borrowing"), Side.DEBIT, p, ccy),
                if (hasInterest) PostingLine(TreasuryChart.code(ccy, "expense"), Side.DEBIT, i, ccy) else null,
                PostingLine(nostro, Side.CREDIT, p + i, ccy),
            )
        }
        return JournalSpec(deal.id, PostingEvent.MATURED, lines)
    }

    /** The offsetting journal for a SETTLED deal's reversal; null when nothing had posted. */
    fun reversal(deal: Deal, stateBeforeReversal: DealState): JournalSpec? {
        if (stateBeforeReversal != DealState.SETTLED) return null
        val flipped = settlement(deal).lines.map {
            it.copy(side = if (it.side == Side.DEBIT) Side.CREDIT else Side.DEBIT)
        }
        return JournalSpec(deal.id, PostingEvent.REVERSED, flipped)
    }
}
