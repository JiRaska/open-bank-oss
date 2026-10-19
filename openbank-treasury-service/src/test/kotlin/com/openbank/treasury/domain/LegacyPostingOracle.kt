// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain

import com.openbank.libs.domain.money.RoundingPolicy
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.FxSide
import com.openbank.treasury.domain.model.PostingEvent
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.domain.model.Side
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * ORACLE for ADR-0341 D3: a verbatim copy of the treasury posting rules and chart as they were on
 * `origin/main` before postings named an accounting class instead of a GL code. It exists only so
 * [PostingJournalsUnchangedTest] can prove the new rules, resolved through the ledger map, put
 * every existing deal's journal on exactly the same GL accounts, sides and amounts. Never edit it
 * to make a test pass: if the rules legitimately change, retire this file with the change.
 */
data class LegacyLine(val glCode: String, val side: Side, val amount: BigDecimal, val currency: String)

data class LegacyJournal(val event: PostingEvent, val lines: List<LegacyLine>, val suffix: String? = null)

object LegacyTreasuryChart {
    private val byCurrency: Map<String, Map<String, String>> = mapOf(
        Deal.CZK to mapOf(
            "nostro" to "1001",
            "placement" to "1500",
            "cnb" to "1510",
            "borrowing" to "2300",
            "cnb-borrowing" to "2320",
            "income" to "4200",
            "expense" to "5200",
            "accrued-receivable" to "1520",
            "accrued-payable" to "2310",
            "fx-position" to "1990",
        ),
        Deal.EUR to mapOf(
            "nostro" to "1002",
            "placement" to "1501",
            "borrowing" to "2301",
            "income" to "4201",
            "expense" to "5201",
            "accrued-receivable" to "1521",
            "accrued-payable" to "2311",
            "fx-position" to "1991",
        ),
    )

    fun code(currency: String, purpose: String): String =
        byCurrency[currency]?.get(purpose) ?: error("no treasury GL account for $purpose in $currency")

    /** Fixed ids seeded by the ledger migration: `a0000000-0000-0000-0000-00000000<code>`. */
    fun glAccountId(code: String): UUID = UUID.fromString("a0000000-0000-0000-0000-00000000$code")

    /** The nostro GL of every currency — the only accounts a correspondent statement may reconcile. */
    val nostroCodes: Set<String> get() = byCurrency.values.mapNotNull { it["nostro"] }.toSet()

    /** Every code the treasury posts to — the ledger migration must seed each one. */
    val postedCodes: Set<String> get() = byCurrency.values.flatMap { it.values }.toSet()
}

object LegacyPostingRules {

    fun settlement(deal: Deal): LegacyJournal {
        val p = deal.principal
        val ccy = deal.currency
        val nostro = LegacyTreasuryChart.code(ccy, "nostro")
        val lines = when (deal.product) {
            ProductType.MM_PLACEMENT -> listOf(
                LegacyLine(LegacyTreasuryChart.code(ccy, "placement"), Side.DEBIT, p, ccy),
                LegacyLine(nostro, Side.CREDIT, p, ccy),
            )
            ProductType.CNB_DEPOSIT_FACILITY -> listOf(
                LegacyLine(LegacyTreasuryChart.code(ccy, "cnb"), Side.DEBIT, p, ccy),
                LegacyLine(nostro, Side.CREDIT, p, ccy),
            )
            ProductType.MM_BORROWING, ProductType.CNB_LOMBARD -> listOf(
                LegacyLine(nostro, Side.DEBIT, p, ccy),
                LegacyLine(liabilityCode(deal), Side.CREDIT, p, ccy),
            )
            ProductType.FX_SPOT -> fxSpotSettlement(deal)
            else -> error("${deal.product} is not a legacy product")
        }
        return LegacyJournal(PostingEvent.SETTLED, lines)
    }

    private fun fxSpotSettlement(deal: Deal): List<LegacyLine> {
        val fx = checkNotNull(deal.fx) { "FX_SPOT deal ${deal.id} without FX terms" }
        val ccy = deal.currency
        val czk = Deal.CZK
        val buy = listOf(
            LegacyLine(LegacyTreasuryChart.code(ccy, "nostro"), Side.DEBIT, deal.principal, ccy),
            LegacyLine(LegacyTreasuryChart.code(ccy, "fx-position"), Side.CREDIT, deal.principal, ccy),
            LegacyLine(LegacyTreasuryChart.code(czk, "fx-position"), Side.DEBIT, fx.counterAmount, czk),
            LegacyLine(LegacyTreasuryChart.code(czk, "nostro"), Side.CREDIT, fx.counterAmount, czk),
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
    fun accrual(deal: Deal, date: LocalDate): LegacyJournal? {
        if (deal.product == ProductType.FX_SPOT) return null
        val amount = accruedThrough(deal, date) - accruedThrough(deal, date.minusDays(1))
        if (amount.signum() <= 0) return null
        val ccy = deal.currency
        val lines = if (deal.product.isAsset) {
            listOf(
                LegacyLine(LegacyTreasuryChart.code(ccy, "accrued-receivable"), Side.DEBIT, amount, ccy),
                LegacyLine(LegacyTreasuryChart.code(ccy, "income"), Side.CREDIT, amount, ccy),
            )
        } else {
            listOf(
                LegacyLine(LegacyTreasuryChart.code(ccy, "expense"), Side.DEBIT, amount, ccy),
                LegacyLine(LegacyTreasuryChart.code(ccy, "accrued-payable"), Side.CREDIT, amount, ccy),
            )
        }
        return LegacyJournal(PostingEvent.ACCRUED, lines, suffix = date.toString())
    }

    fun maturity(deal: Deal, accrued: BigDecimal = BigDecimal.ZERO): LegacyJournal {
        require(deal.product != ProductType.FX_SPOT) { "an FX spot deal has no maturity journal" }
        val p = deal.principal
        val i = deal.interest
        require(accrued.signum() >= 0 && accrued <= i) { "accrued $accrued outside 0..$i for ${deal.id}" }
        val rest = i - accrued
        val ccy = deal.currency
        val nostro = LegacyTreasuryChart.code(ccy, "nostro")
        val lines = when (deal.product) {
            ProductType.MM_PLACEMENT, ProductType.CNB_DEPOSIT_FACILITY -> {
                val asset = LegacyTreasuryChart.code(
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
                    LegacyLine(nostro, Side.DEBIT, p + i, ccy),
                    LegacyLine(asset, Side.CREDIT, p, ccy),
                    line(LegacyTreasuryChart.code(ccy, "accrued-receivable"), Side.CREDIT, accrued, ccy),
                    line(LegacyTreasuryChart.code(ccy, "income"), Side.CREDIT, rest, ccy),
                )
            }
            ProductType.FX_SPOT -> error("unreachable: FX spot has no maturity")
            ProductType.MM_BORROWING, ProductType.CNB_LOMBARD -> listOfNotNull(
                LegacyLine(liabilityCode(deal), Side.DEBIT, p, ccy),
                line(LegacyTreasuryChart.code(ccy, "accrued-payable"), Side.DEBIT, accrued, ccy),
                line(LegacyTreasuryChart.code(ccy, "expense"), Side.DEBIT, rest, ccy),
                LegacyLine(nostro, Side.CREDIT, p + i, ccy),
            )
            else -> error("${deal.product} is not a legacy product")
        }
        return LegacyJournal(PostingEvent.MATURED, lines)
    }

    /** The offsetting journal for a SETTLED deal's reversal, unwinding [accrued]; null when nothing had posted. */
    fun reversal(deal: Deal, stateBeforeReversal: DealState, accrued: BigDecimal = BigDecimal.ZERO): LegacyJournal? {
        if (stateBeforeReversal != DealState.SETTLED) return null
        val flipped = settlement(deal).lines.map { it.copy(side = it.side.flipped()) }
        val ccy = deal.currency
        val unwind = if (deal.product.isAsset) {
            listOfNotNull(
                line(LegacyTreasuryChart.code(ccy, "income"), Side.DEBIT, accrued, ccy),
                line(LegacyTreasuryChart.code(ccy, "accrued-receivable"), Side.CREDIT, accrued, ccy),
            )
        } else {
            listOfNotNull(
                line(LegacyTreasuryChart.code(ccy, "accrued-payable"), Side.DEBIT, accrued, ccy),
                line(LegacyTreasuryChart.code(ccy, "expense"), Side.CREDIT, accrued, ccy),
            )
        }
        return LegacyJournal(PostingEvent.REVERSED, flipped + unwind)
    }

    /** The principal's liability account: interbank borrowings, or the ČNB lombard account. */
    private fun liabilityCode(deal: Deal): String = LegacyTreasuryChart.code(
        deal.currency,
        if (deal.product ==
            ProductType.CNB_LOMBARD
        ) {
            "cnb-borrowing"
        } else {
            "borrowing"
        },
    )

    private fun line(code: String, side: Side, amount: BigDecimal, ccy: String): LegacyLine? =
        if (amount.signum() > 0) LegacyLine(code, side, amount, ccy) else null
}
