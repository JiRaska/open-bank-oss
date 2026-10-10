// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.domain.model

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/** One position a NAV was struck on, kept with the NAV so the portfolio at that date can be re-read. */
data class NavPosition(val navId: UUID, val instrumentId: String, val quantity: BigDecimal, val price: BigDecimal) {
    val marketValue: BigDecimal get() = Precision.money(quantity.multiply(price))
}

/** No published NAV backs the exact period close, so there is nothing to report — never zeroes. */
class PeriodNotReportableException(message: String) : RuntimeException(message)

data class FundBalanceSheet(val totalAssets: BigDecimal, val totalLiabilities: BigDecimal, val totalEquity: BigDecimal)

data class FundProfitAndLoss(val income: BigDecimal, val expenses: BigDecimal, val profitLoss: BigDecimal)

data class UnitRollForward(
    val opening: BigDecimal,
    val issued: BigDecimal,
    val cancelled: BigDecimal,
    val closing: BigDecimal,
    val unitValue: BigDecimal,
    val unitValuePeriodMax: BigDecimal,
)

/** Null figures mean the closing NAV was struck before positions were recorded — unknown, not zero. */
data class PortfolioSummary(val carryingValue: BigDecimal?, val holdingsCount: Int?, val cash: BigDecimal?)

data class FlowFigures(
    val subscriptions: BigDecimal,
    val redemptions: BigDecimal,
    val switchesIn: BigDecimal,
    val switchesOut: BigDecimal,
    val unitFees: BigDecimal,
)

data class EntitlementRollForward(
    val opening: BigDecimal,
    val increase: BigDecimal,
    val decrease: BigDecimal,
    val closing: BigDecimal,
)

data class ParticipantCounts(val holders: Int, val subscribing: Int)

/**
 * A fund's figures for one reporting period (#12425), aggregate only: no contract id, no
 * participant attribute leaves this type — only counts and sums.
 *
 * [basisNavIds] and [fingerprint] make the answer reproducible: the same published NAVs and the
 * same register always yield the same fingerprint, and a NAV correction (which re-prices the
 * register) changes it, so a consumer can tell a restatement from a repeat.
 */
data class FundPeriodReport(
    val fundId: UUID,
    val currency: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val closingNavId: UUID,
    val closingValuationDate: LocalDate,
    val balanceSheet: FundBalanceSheet,
    val profitAndLossYtd: FundProfitAndLoss,
    val units: UnitRollForward,
    val portfolio: PortfolioSummary,
    val flows: FlowFigures,
    val managementFeesAccrued: BigDecimal,
    val entitlements: EntitlementRollForward,
    val participants: ParticipantCounts,
    val basisNavIds: List<UUID>,
    val fingerprint: String,
)

/**
 * Period figures derived ONLY from published NAVs and the unit transactions priced at them.
 *
 * Bucketing is by the NAV's valuation DATE, never by a timestamp, so the answer for a closed
 * period does not depend on the time of day it is asked. A transaction priced at NAV `d` moved
 * units on `d`; its cash reaches the next NAV — which is why capital flows for a P&L window run
 * from the opening NAV (inclusive) to the closing NAV (exclusive): those are exactly the flows the
 * closing net assets contain and the opening net assets do not.
 *
 * Every roll-forward closes by construction (opening + in - out == closing): the closing figure is
 * computed independently from the whole register, so a lost or double-counted transaction would
 * break the equality rather than be absorbed into it.
 */
object FundPeriodCalculator {
    @Suppress("LongParameterList")
    fun calculate(
        fund: Fund,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        publishedNavs: List<NavRecord>,
        transactions: List<UnitTransaction>,
        closingPositions: List<NavPosition>?,
    ): FundPeriodReport {
        require(!periodEnd.isBefore(periodStart)) { "periodEnd must not be before periodStart" }
        val navs = publishedNavs
            .filter { it.fundId == fund.id && it.status == NavStatus.PUBLISHED && !it.valuationDate.isAfter(periodEnd) }
            .sortedBy { it.valuationDate }
        val inPeriod = navs.filter { !it.valuationDate.isBefore(periodStart) }
        if (inPeriod.isEmpty()) {
            throw PeriodNotReportableException(
                "fund ${fund.id} has no published NAV between $periodStart and $periodEnd",
            )
        }
        // A statutory close cannot silently use an earlier valuation. Any permitted stale-NAV
        // window needs an explicit, approved rule; until then only the exact as-of date is valid.
        val closing = inPeriod.lastOrNull { it.valuationDate == periodEnd }
            ?: throw PeriodNotReportableException(
                "fund ${fund.id} has no published NAV at period close $periodEnd",
            )
        val navDate = navs.associate { it.id to it.valuationDate }
        val ctx = Window(
            navs = navs,
            txs = transactions.filter { it.fundId == fund.id && it.navId in navDate },
            navDate = navDate,
            closing = closing,
            periodStart = periodStart,
            periodEnd = periodEnd,
        )
        val periodOpening = navs.lastOrNull { it.valuationDate.isBefore(periodStart) }
        val report = FundPeriodReport(
            fundId = fund.id,
            currency = fund.currency,
            periodStart = periodStart,
            periodEnd = periodEnd,
            closingNavId = closing.id,
            closingValuationDate = closing.valuationDate,
            balanceSheet = FundBalanceSheet(
                totalAssets = closing.figures.grossAssets,
                totalLiabilities = Precision.money(
                    closing.figures.accruedManagementFee + closing.figures.otherLiabilities,
                ),
                totalEquity = closing.figures.netAssets,
            ),
            profitAndLossYtd = profitAndLossYtd(ctx),
            units = unitRollForward(ctx, inPeriod),
            portfolio = portfolio(closing, closingPositions),
            flows = flows(ctx),
            managementFeesAccrued = feesAccrued(navs, periodOpening?.valuationDate, periodStart, closing.valuationDate),
            entitlements = entitlements(ctx, periodOpening),
            participants = participants(ctx),
            basisNavIds = navs.map { it.id },
            fingerprint = "",
        )
        return report.copy(fingerprint = fingerprint(report, ctx.txs))
    }

    /** Everything one calculation reads, so each figure is a small function of it. */
    private class Window(
        val navs: List<NavRecord>,
        val txs: List<UnitTransaction>,
        val navDate: Map<java.util.UUID, LocalDate>,
        val closing: NavRecord,
        val periodStart: LocalDate,
        val periodEnd: LocalDate,
    ) {
        fun dateOf(tx: UnitTransaction): LocalDate = navDate.getValue(tx.navId)

        val within: List<UnitTransaction> by lazy {
            txs.filter {
                val d = dateOf(it)
                !d.isBefore(periodStart) && !d.isAfter(periodEnd)
            }
        }
    }

    private fun profitAndLossYtd(w: Window): FundProfitAndLoss {
        val yearStart = LocalDate.of(w.periodEnd.year, 1, 1)
        val opening = w.navs.lastOrNull { it.valuationDate.isBefore(yearStart) }
        val flows = netCapitalFlows(w, opening?.valuationDate ?: yearStart)
        val expenses = feesAccrued(w.navs, opening?.valuationDate, yearStart, w.closing.valuationDate)
        val profit = w.closing.figures.netAssets - (opening?.figures?.netAssets ?: ZERO_MONEY) - flows.net
        return FundProfitAndLoss(
            income = Precision.money(profit + expenses),
            expenses = expenses,
            profitLoss = Precision.money(profit),
        )
    }

    private fun entitlements(w: Window, periodOpening: NavRecord?): EntitlementRollForward {
        val flows = netCapitalFlows(w, periodOpening?.valuationDate ?: w.periodStart)
        val openingEquity = periodOpening?.figures?.netAssets ?: ZERO_MONEY
        val closingEquity = w.closing.figures.netAssets
        val revaluation = closingEquity - openingEquity - flows.inflow + flows.outflow
        return EntitlementRollForward(
            opening = Precision.money(openingEquity),
            increase = Precision.money(flows.inflow + revaluation.max(ZERO_MONEY)),
            decrease = Precision.money(flows.outflow + revaluation.negate().max(ZERO_MONEY)),
            closing = Precision.money(closingEquity),
        )
    }

    private fun flows(w: Window): FlowFigures {
        fun sumOf(type: UnitTransactionType) =
            Precision.money(w.within.filter { it.type == type }.fold(ZERO_MONEY) { a, t -> a + t.amount })
        return FlowFigures(
            subscriptions = sumOf(UnitTransactionType.SUBSCRIBE),
            redemptions = sumOf(UnitTransactionType.REDEEM),
            switchesIn = sumOf(UnitTransactionType.SWITCH_IN),
            switchesOut = sumOf(UnitTransactionType.SWITCH_OUT),
            unitFees = sumOf(UnitTransactionType.FEE),
        )
    }

    private fun participants(w: Window): ParticipantCounts = ParticipantCounts(
        holders = w.txs.groupBy { it.contractId }
            .count { (_, own) -> own.fold(BigDecimal.ZERO) { a, t -> a + signedUnits(t) }.signum() > 0 },
        subscribing = w.within.filter { it.type == UnitTransactionType.SUBSCRIBE }.map { it.contractId }.toSet().size,
    )

    private fun portfolio(closing: NavRecord, positions: List<NavPosition>?): PortfolioSummary {
        if (positions == null) return PortfolioSummary(null, null, null)
        val carrying = Precision.money(positions.fold(ZERO_MONEY) { a, p -> a + p.marketValue })
        return PortfolioSummary(
            carryingValue = carrying,
            holdingsCount = positions.count { it.quantity.signum() > 0 },
            cash = Precision.money(closing.figures.grossAssets - carrying),
        )
    }

    private fun unitRollForward(w: Window, inPeriod: List<NavRecord>): UnitRollForward {
        val opening = w.txs.filter { w.dateOf(it).isBefore(w.periodStart) }.fold(ZERO_UNITS) { a, t ->
            a +
                signedUnits(t)
        }
        val issued = w.within.filter { it.type in INCOMING }.fold(ZERO_UNITS) { a, t -> a + t.units }
        val cancelled = w.within.filter { it.type !in INCOMING }.fold(ZERO_UNITS) { a, t -> a + t.units }
        // Computed from the whole register, not from opening + movements, so a lost row breaks the identity.
        val closingUnits = w.txs.fold(ZERO_UNITS) { a, t -> a + signedUnits(t) }
        return UnitRollForward(
            opening = Precision.units(opening),
            issued = Precision.units(issued),
            cancelled = Precision.units(cancelled),
            closing = Precision.units(closingUnits),
            unitValue = w.closing.navPerUnit,
            unitValuePeriodMax = inPeriod.maxOf { it.navPerUnit },
        )
    }

    private data class CapitalFlows(val inflow: BigDecimal, val outflow: BigDecimal) {
        val net: BigDecimal get() = inflow - outflow
    }

    /** Flows priced at NAVs dated in [from, closing): the ones the closing NAV's cash contains. */
    private fun netCapitalFlows(w: Window, from: LocalDate): CapitalFlows {
        val window = w.txs.filter {
            val d = w.dateOf(it)
            !d.isBefore(from) && d.isBefore(w.closing.valuationDate)
        }
        val inflow = window.filter { it.type in INCOMING }.fold(ZERO_MONEY) { a, t -> a + t.amount }
        val outflow = window.filter { it.type !in INCOMING }.fold(ZERO_MONEY) { a, t -> a + t.amount }
        return CapitalFlows(inflow, outflow)
    }

    /** Management fee accrued by NAVs after the opening NAV up to and including the closing one. */
    private fun feesAccrued(
        navs: List<NavRecord>,
        openingDate: LocalDate?,
        windowStart: LocalDate,
        closingDate: LocalDate,
    ): BigDecimal = Precision.money(
        navs.filter {
            val afterOpening =
                openingDate?.let { o -> it.valuationDate.isAfter(o) } ?: !it.valuationDate.isBefore(windowStart)
            afterOpening && !it.valuationDate.isAfter(closingDate)
        }.fold(ZERO_MONEY) { a, n -> a + n.figures.accruedManagementFee },
    )

    private fun fingerprint(report: FundPeriodReport, txs: List<UnitTransaction>): String {
        val canonical = buildString {
            append(report.copy(fingerprint = "").toString())
            txs.sortedBy { it.id }.forEach { t ->
                append('|').append(t.id).append(':').append(t.navId).append(':')
                    .append(t.units.stripTrailingZeros().toPlainString()).append(':')
                    .append(t.amount.stripTrailingZeros().toPlainString())
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private val INCOMING = setOf(UnitTransactionType.SUBSCRIBE, UnitTransactionType.SWITCH_IN)
    private val ZERO_MONEY: BigDecimal = BigDecimal.ZERO.setScale(Precision.MONEY_SCALE)
    private val ZERO_UNITS: BigDecimal = BigDecimal.ZERO.setScale(Precision.UNIT_SCALE)
}

private val INCOMING_TYPES = setOf(UnitTransactionType.SUBSCRIBE, UnitTransactionType.SWITCH_IN)

private fun signedUnits(tx: UnitTransaction): BigDecimal =
    if (tx.type in INCOMING_TYPES) tx.units else tx.units.negate()
