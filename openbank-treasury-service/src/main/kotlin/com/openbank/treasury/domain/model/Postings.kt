// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.model

import com.openbank.libs.domain.money.RoundingPolicy
import java.math.BigDecimal
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

/**
 * Which of a product's ledger accounts a leg belongs to. Mirrors the ledger's `AccountingRole`
 * (ADR-0341 D3) by name: the ledger, not the treasury, decides which GL account that is.
 */
enum class LedgerRole { PRINCIPAL, ACCRUED_INTEREST, INTEREST_INCOME, INTEREST_EXPENSE }

/** The accounting classes treasury posts under (ADR-0341 D3); the ledger maps each to GL accounts. */
object AccountingClasses {
    const val NOSTRO = "NOSTRO_ACCOUNT"
}

/**
 * One leg of a journal. It names its account by [accountingClass] + [role] (+ [currency]) and carries
 * no GL code: the ledger resolves the account from its own map (ADR-0341 D3).
 */
data class PostingLine(
    val accountingClass: String,
    val role: LedgerRole,
    val side: Side,
    val amount: BigDecimal,
    val currency: String,
    /**
     * Set ONLY on a reversal leg, to the account the ORIGINAL posting of this class/role/currency
     * actually landed on ([ResolvedAccount]). The ledger map may be re-pointed between a settlement
     * and its reversal by a reviewed migration; a reversal that re-resolved the class would then
     * offset a different account than the one it is reversing. Null on every other leg.
     */
    val glAccountId: UUID? = null,
)

/**
 * What the ledger resolved one leg of a journal to when it was posted. Recorded with the deal's
 * journal so a later reversal can offset exactly those accounts, whatever the map says by then.
 */
data class ResolvedAccount(
    val accountingClass: String,
    val role: LedgerRole,
    val currency: String,
    val glAccountId: UUID,
)

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
 * The posting table (ADR-0315 D5). Pure: a deal and an event in, a balanced journal out. I is the
 * deal's interest, A the part of it already accrued (Σ of the daily ACCRUED journals).
 *
 * | product              | SETTLED                       | ACCRUED (per day, amount d)         | MATURED                                                        |
 * |----------------------|-------------------------------|-------------------------------------|----------------------------------------------------------------|
 * | MM_PLACEMENT         | Dr placement / Cr nostro (P)  | Dr accrued receivable / Cr income   | Dr nostro (P+I) / Cr placement (P) / Cr accrued (A) / Cr income (I−A) |
 * | CNB_DEPOSIT_FACILITY | Dr ČNB 1510 / Cr nostro (P)   | Dr accrued receivable / Cr income   | Dr nostro (P+I) / Cr 1510 (P) / Cr accrued (A) / Cr income (I−A)      |
 * | MM_BORROWING         | Dr nostro / Cr borrowing (P)  | Dr expense / Cr accrued payable     | Dr borrowing (P) / Dr accrued (A) / Dr expense (I−A) / Cr nostro (P+I) |
 * | CNB_LOMBARD          | Dr nostro / Cr ČNB 2320 (P)   | Dr expense / Cr accrued payable     | Dr 2320 (P) / Dr accrued (A) / Dr expense (I−A) / Cr nostro (P+I)      |
 *
 * The daily amount is `cumulative(d) − cumulative(d−1)` with `cumulative(n) = I · n / days`, rounded
 * as I itself is, so the dailies sum to exactly I over the life of the deal and a maturity after a
 * complete accrual run books nothing to income (A = I). REVERSED (from SETTLED) is the settlement
 * journal with every side flipped, plus the accrued A unwound out of income or expense, posted as
 * one new offsetting journal under its own key; from BOOKED nothing had posted. A zero amount
 * posts no line.
 *
 * FX_SPOT (F = foreign amount, C = its CZK leg) SETTLED, routed through the ledger's per-currency
 * FX position accounts (V5, credited when the bank acquires a currency) so each currency balances:
 * - BUY:  Dr nostro(F) / Cr FX position(F) (F); Dr FX position CZK / Cr nostro CZK (C)
 * - SELL: the same four lines with every side flipped.
 * It never accrues or matures; its reversal is the flipped settlement. Revaluation of the open
 * position is the ledger's daily FX revaluation, not the treasury's.
 */
object PostingRules {

    fun settlement(deal: Deal): JournalSpec {
        val p = deal.principal
        val ccy = deal.currency
        val lines = when (deal.product) {
            ProductType.MM_PLACEMENT, ProductType.CNB_DEPOSIT_FACILITY -> listOf(
                leg(deal, LedgerRole.PRINCIPAL, Side.DEBIT, p),
                nostro(ccy, Side.CREDIT, p),
            )
            ProductType.MM_BORROWING, ProductType.CNB_LOMBARD -> listOf(
                nostro(ccy, Side.DEBIT, p),
                leg(deal, LedgerRole.PRINCIPAL, Side.CREDIT, p),
            )
            ProductType.FX_SPOT -> fxSpotSettlement(deal)
        }
        return JournalSpec(deal.id, PostingEvent.SETTLED, lines)
    }

    private fun fxSpotSettlement(deal: Deal): List<PostingLine> {
        val fx = checkNotNull(deal.fx) { "FX_SPOT deal ${deal.id} without FX terms" }
        val ccy = deal.currency
        val czk = Deal.CZK
        val position = ProductType.FX_SPOT.accountingClass
        val buy = listOf(
            nostro(ccy, Side.DEBIT, deal.principal),
            PostingLine(position, LedgerRole.PRINCIPAL, Side.CREDIT, deal.principal, ccy),
            PostingLine(position, LedgerRole.PRINCIPAL, Side.DEBIT, fx.counterAmount, czk),
            nostro(czk, Side.CREDIT, fx.counterAmount),
        )
        return if (fx.side == FxSide.BUY) buy else buy.map { it.copy(side = it.side.flipped()) }
    }

    private fun Side.flipped() = if (this == Side.DEBIT) Side.CREDIT else Side.DEBIT

    /** Interest accrued from the value date up to and including [date], capped at maturity. */
    fun accruedThrough(deal: Deal, date: LocalDate): BigDecimal {
        val total = deal.days
        if (total <= 0L) return BigDecimal.ZERO
        val elapsed = ChronoUnit.DAYS.between(deal.valueDate, date).coerceIn(0L, total)
        return RoundingPolicy.TREASURY_AMOUNT.divide(
            deal.interest.multiply(BigDecimal.valueOf(elapsed)),
            BigDecimal.valueOf(total),
        )
    }

    /** The journal accruing [date]'s interest, or null when that day adds nothing. */
    fun accrual(deal: Deal, date: LocalDate): JournalSpec? {
        if (deal.product == ProductType.FX_SPOT) return null
        val amount = accruedThrough(deal, date) - accruedThrough(deal, date.minusDays(1))
        if (amount.signum() <= 0) return null
        val lines = if (deal.product.isAsset) {
            listOf(
                leg(deal, LedgerRole.ACCRUED_INTEREST, Side.DEBIT, amount),
                leg(deal, LedgerRole.INTEREST_INCOME, Side.CREDIT, amount),
            )
        } else {
            listOf(
                leg(deal, LedgerRole.INTEREST_EXPENSE, Side.DEBIT, amount),
                leg(deal, LedgerRole.ACCRUED_INTEREST, Side.CREDIT, amount),
            )
        }
        return JournalSpec(deal.id, PostingEvent.ACCRUED, lines, suffix = date.toString())
    }

    fun maturity(deal: Deal, accrued: BigDecimal = BigDecimal.ZERO): JournalSpec {
        require(deal.product != ProductType.FX_SPOT) { "an FX spot deal has no maturity journal" }
        val p = deal.principal
        val i = deal.interest
        require(accrued.signum() >= 0 && accrued <= i) { "accrued $accrued outside 0..$i for ${deal.id}" }
        val rest = i - accrued
        val ccy = deal.currency
        val lines = when (deal.product) {
            ProductType.MM_PLACEMENT, ProductType.CNB_DEPOSIT_FACILITY -> listOfNotNull(
                nostro(ccy, Side.DEBIT, p + i),
                leg(deal, LedgerRole.PRINCIPAL, Side.CREDIT, p),
                optional(deal, LedgerRole.ACCRUED_INTEREST, Side.CREDIT, accrued),
                optional(deal, LedgerRole.INTEREST_INCOME, Side.CREDIT, rest),
            )
            ProductType.FX_SPOT -> error("unreachable: FX spot has no maturity")
            ProductType.MM_BORROWING, ProductType.CNB_LOMBARD -> listOfNotNull(
                leg(deal, LedgerRole.PRINCIPAL, Side.DEBIT, p),
                optional(deal, LedgerRole.ACCRUED_INTEREST, Side.DEBIT, accrued),
                optional(deal, LedgerRole.INTEREST_EXPENSE, Side.DEBIT, rest),
                nostro(ccy, Side.CREDIT, p + i),
            )
        }
        return JournalSpec(deal.id, PostingEvent.MATURED, lines)
    }

    /** The offsetting journal for a SETTLED deal's reversal, unwinding [accrued]; null when nothing had posted. */
    fun reversal(deal: Deal, stateBeforeReversal: DealState, accrued: BigDecimal = BigDecimal.ZERO): JournalSpec? {
        if (stateBeforeReversal != DealState.SETTLED) return null
        val flipped = settlement(deal).lines.map { it.copy(side = it.side.flipped()) }
        val unwind = if (deal.product.isAsset) {
            listOfNotNull(
                optional(deal, LedgerRole.INTEREST_INCOME, Side.DEBIT, accrued),
                optional(deal, LedgerRole.ACCRUED_INTEREST, Side.CREDIT, accrued),
            )
        } else {
            listOfNotNull(
                optional(deal, LedgerRole.ACCRUED_INTEREST, Side.DEBIT, accrued),
                optional(deal, LedgerRole.INTEREST_EXPENSE, Side.CREDIT, accrued),
            )
        }
        return JournalSpec(deal.id, PostingEvent.REVERSED, flipped + unwind)
    }

    private fun leg(deal: Deal, role: LedgerRole, side: Side, amount: BigDecimal) =
        PostingLine(deal.product.accountingClass, role, side, amount, deal.currency)

    private fun nostro(currency: String, side: Side, amount: BigDecimal) =
        PostingLine(AccountingClasses.NOSTRO, LedgerRole.PRINCIPAL, side, amount, currency)

    private fun optional(deal: Deal, role: LedgerRole, side: Side, amount: BigDecimal): PostingLine? =
        if (amount.signum() > 0) leg(deal, role, side, amount) else null
}
