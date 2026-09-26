// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class Side { DEBIT, CREDIT }

/** The ledger events that move value. `BOOKED` posts nothing (no off-balance commitment in the MVP). */
enum class PostingEvent(val key: String) {
    SETTLED("settled"),

    /** One calendar day of interest recognised on an accrual basis (ADR-0315 D5); keyed per day. */
    ACCRUED("accrued"),
    MATURED("matured"),
    REVERSED("reversed"),
}

data class PostingLine(val glCode: String, val side: Side, val amount: BigDecimal, val currency: String)

/**
 * A balanced journal for one deal event. [idempotencyKey] is `treasury:<dealId>:<event>` (ADR-0315
 * D5), or `treasury:<dealId>:accrued:<date>` for a daily accrual ([suffix] = the ISO date): a retried
 * post returns the ledger's original entry and never double-posts.
 */
data class JournalSpec(
    val dealId: UUID,
    val event: PostingEvent,
    val lines: List<PostingLine>,
    val suffix: String? = null,
) {
    val idempotencyKey: String get() = "treasury:$dealId:${event.key}" + (suffix?.let { ":$it" } ?: "")

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
 * Interest is recognised daily into the accrued-interest accounts (ADR-0315 D5); maturity then
 * clears them rather than booking the whole interest to income in one amount.
 */
object TreasuryChart {
    private val byCurrency: Map<String, Map<String, String>> = mapOf(
        Deal.CZK to mapOf(
            "nostro" to "1001",
            "placement" to "1500",
            "cnb" to "1510",
            "borrowing" to "2300",
            "income" to "4200",
            "expense" to "5200",
            "accrued-receivable" to "1520",
            "accrued-payable" to "2310",
        ),
        Deal.EUR to mapOf(
            "nostro" to "1002",
            "placement" to "1501",
            "borrowing" to "2301",
            "income" to "4201",
            "expense" to "5201",
            "accrued-receivable" to "1521",
            "accrued-payable" to "2311",
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
 * The posting table (ADR-0315 D5). Pure: a deal and an event in, a balanced journal out. I is the
 * deal's interest, A the part of it already accrued (Σ of the daily ACCRUED journals).
 *
 * | product              | SETTLED                       | ACCRUED (per day, amount d)         | MATURED                                                        |
 * |----------------------|-------------------------------|-------------------------------------|----------------------------------------------------------------|
 * | MM_PLACEMENT         | Dr placement / Cr nostro (P)  | Dr accrued receivable / Cr income   | Dr nostro (P+I) / Cr placement (P) / Cr accrued (A) / Cr income (I−A) |
 * | CNB_DEPOSIT_FACILITY | Dr ČNB 1510 / Cr nostro (P)   | Dr accrued receivable / Cr income   | Dr nostro (P+I) / Cr 1510 (P) / Cr accrued (A) / Cr income (I−A)      |
 * | MM_BORROWING         | Dr nostro / Cr borrowing (P)  | Dr expense / Cr accrued payable     | Dr borrowing (P) / Dr accrued (A) / Dr expense (I−A) / Cr nostro (P+I) |
 *
 * The daily amount is `cumulative(d) − cumulative(d−1)` with `cumulative(n) = I · n / days`, rounded
 * as I itself is, so the dailies sum to exactly I over the life of the deal and a maturity after a
 * complete accrual run books nothing to income (A = I). REVERSED (from SETTLED) is the settlement
 * journal with every side flipped, plus the accrued A unwound out of income or expense, posted as
 * one new offsetting journal under its own key; from BOOKED nothing had posted. A zero amount
 * posts no line.
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

    /** Interest accrued from the value date up to and including [date], capped at maturity. */
    fun accruedThrough(deal: Deal, date: LocalDate): BigDecimal {
        val total = deal.days
        if (total <= 0L) return BigDecimal.ZERO
        val elapsed = ChronoUnit.DAYS.between(deal.valueDate, date).coerceIn(0L, total)
        return deal.interest.multiply(BigDecimal.valueOf(elapsed))
            .divide(BigDecimal.valueOf(total), MONEY_SCALE, RoundingMode.HALF_UP)
    }

    /** The journal accruing [date]'s interest, or null when that day adds nothing. */
    fun accrual(deal: Deal, date: LocalDate): JournalSpec? {
        val amount = accruedThrough(deal, date) - accruedThrough(deal, date.minusDays(1))
        if (amount.signum() <= 0) return null
        val ccy = deal.currency
        val lines = if (deal.product.isAsset) {
            listOf(
                PostingLine(TreasuryChart.code(ccy, "accrued-receivable"), Side.DEBIT, amount, ccy),
                PostingLine(TreasuryChart.code(ccy, "income"), Side.CREDIT, amount, ccy),
            )
        } else {
            listOf(
                PostingLine(TreasuryChart.code(ccy, "expense"), Side.DEBIT, amount, ccy),
                PostingLine(TreasuryChart.code(ccy, "accrued-payable"), Side.CREDIT, amount, ccy),
            )
        }
        return JournalSpec(deal.id, PostingEvent.ACCRUED, lines, suffix = date.toString())
    }

    fun maturity(deal: Deal, accrued: BigDecimal = BigDecimal.ZERO): JournalSpec {
        val p = deal.principal
        val i = deal.interest
        require(accrued.signum() >= 0 && accrued <= i) { "accrued $accrued outside 0..$i for ${deal.id}" }
        val rest = i - accrued
        val ccy = deal.currency
        val nostro = TreasuryChart.code(ccy, "nostro")
        val lines = when (deal.product) {
            ProductType.MM_PLACEMENT, ProductType.CNB_DEPOSIT_FACILITY -> {
                val asset = TreasuryChart.code(
                    ccy,
                    if (deal.product ==
                        ProductType.MM_PLACEMENT
                    ) {
                        "placement"
                    } else {
                        "cnb"
                    },
                )
                listOfNotNull(
                    PostingLine(nostro, Side.DEBIT, p + i, ccy),
                    PostingLine(asset, Side.CREDIT, p, ccy),
                    line(TreasuryChart.code(ccy, "accrued-receivable"), Side.CREDIT, accrued, ccy),
                    line(TreasuryChart.code(ccy, "income"), Side.CREDIT, rest, ccy),
                )
            }
            ProductType.MM_BORROWING -> listOfNotNull(
                PostingLine(TreasuryChart.code(ccy, "borrowing"), Side.DEBIT, p, ccy),
                line(TreasuryChart.code(ccy, "accrued-payable"), Side.DEBIT, accrued, ccy),
                line(TreasuryChart.code(ccy, "expense"), Side.DEBIT, rest, ccy),
                PostingLine(nostro, Side.CREDIT, p + i, ccy),
            )
        }
        return JournalSpec(deal.id, PostingEvent.MATURED, lines)
    }

    /** The offsetting journal for a SETTLED deal's reversal, unwinding [accrued]; null when nothing had posted. */
    fun reversal(deal: Deal, stateBeforeReversal: DealState, accrued: BigDecimal = BigDecimal.ZERO): JournalSpec? {
        if (stateBeforeReversal != DealState.SETTLED) return null
        val flipped = settlement(deal).lines.map {
            it.copy(side = if (it.side == Side.DEBIT) Side.CREDIT else Side.DEBIT)
        }
        val ccy = deal.currency
        val unwind = if (deal.product.isAsset) {
            listOfNotNull(
                line(TreasuryChart.code(ccy, "income"), Side.DEBIT, accrued, ccy),
                line(TreasuryChart.code(ccy, "accrued-receivable"), Side.CREDIT, accrued, ccy),
            )
        } else {
            listOfNotNull(
                line(TreasuryChart.code(ccy, "accrued-payable"), Side.DEBIT, accrued, ccy),
                line(TreasuryChart.code(ccy, "expense"), Side.CREDIT, accrued, ccy),
            )
        }
        return JournalSpec(deal.id, PostingEvent.REVERSED, flipped + unwind)
    }

    private fun line(code: String, side: Side, amount: BigDecimal, ccy: String): PostingLine? =
        if (amount.signum() > 0) PostingLine(code, side, amount, ccy) else null

    private const val MONEY_SCALE = 2
}
