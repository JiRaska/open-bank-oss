// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.domain.model

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/**
 * One position a NAV was struck on, kept with the NAV so the portfolio at that date can be re-read.
 * [instrumentClass] is the class it carries NOW — recorded, or set by an approved correction.
 */
data class NavPosition(
    val id: UUID,
    val navId: UUID,
    val instrumentId: String,
    val quantity: BigDecimal,
    val price: BigDecimal,
    val instrumentClass: InstrumentClass = InstrumentClass.UNCLASSIFIED,
) {
    val marketValue: BigDecimal get() = Precision.money(quantity.multiply(price))
}

/** No published NAV backs the requested period, so there is nothing to report — never zeroes. */
class PeriodNotReportableException(message: String) : RuntimeException(message)

data class FundBalanceSheet(val totalAssets: BigDecimal, val totalLiabilities: BigDecimal, val totalEquity: BigDecimal)

/**
 * Year-to-date P&L as separately accumulated lines (#12425), never one figure derived from another.
 *
 * Each line is summed NAV interval by NAV interval:
 * - [revaluationGains] / [revaluationLosses]: per instrument, the units held across an interval
 *   times the change in its price — a rise is a gain and a fall a loss, each accumulated on its OWN
 *   line, so a loss year is two non-negative numbers and never a negative "income".
 * - [managementFees]: the fee each NAV accrued.
 * - [otherInvestmentResult]: signed — interest, the result on disposals and cash. This service has
 *   no cash or trade ledger, so those three cannot be told apart here; the line is what each
 *   interval's result leaves once revaluation and fees are accounted for, and says so.
 * - [profitLoss]: measured independently from net assets and capital flows.
 *
 * The revaluation lines need the positions of BOTH NAVs of every interval in the year. A NAV struck
 * before positions were recorded makes them unknown: [linesUnavailableReason] says why, and the
 * lines are null rather than zero. [profitLoss] and [managementFees] are always known.
 */
data class FundProfitAndLoss(
    val revaluationGains: BigDecimal?,
    val revaluationLosses: BigDecimal?,
    val otherInvestmentResult: BigDecimal?,
    val managementFees: BigDecimal,
    val profitLoss: BigDecimal,
    val linesUnavailableReason: String? = null,
)

data class UnitRollForward(
    val opening: BigDecimal,
    val issued: BigDecimal,
    val cancelled: BigDecimal,
    val closing: BigDecimal,
    val unitValue: BigDecimal,
    val unitValuePeriodMax: BigDecimal,
)

/**
 * Null figures mean the closing NAV was struck before positions were recorded — unknown, not zero.
 * [loansOutstanding] is additionally null while any closing position is [InstrumentClass.UNCLASSIFIED]
 * ([unclassifiedCount] > 0): such a position may be a loan.
 */
data class PortfolioSummary(
    val carryingValue: BigDecimal?,
    val holdingsCount: Int?,
    val cash: BigDecimal?,
    val carryingValueByClass: Map<InstrumentClass, BigDecimal>? = null,
    val unclassifiedCount: Int? = null,
    val loansOutstanding: BigDecimal? = null,
)

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
        positionsByNav: Map<UUID, List<NavPosition>>,
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
        val closing = inPeriod.last()
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
            profitAndLossYtd = profitAndLossYtd(ctx, positionsByNav),
            units = unitRollForward(ctx, inPeriod),
            portfolio = portfolio(closing, positionsByNav[closing.id]),
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

    private fun profitAndLossYtd(w: Window, positionsByNav: Map<UUID, List<NavPosition>>): FundProfitAndLoss {
        val yearStart = LocalDate.of(w.periodEnd.year, 1, 1)
        val opening = w.navs.lastOrNull { it.valuationDate.isBefore(yearStart) }
        val flows = netCapitalFlowsBetween(w, opening?.valuationDate ?: yearStart, w.closing.valuationDate)
        val fees = feesAccrued(w.navs, opening?.valuationDate, yearStart, w.closing.valuationDate)
        val profit = Precision.money(
            w.closing.figures.netAssets - (opening?.figures?.netAssets ?: ZERO_MONEY) - flows.net,
        )
        val chain = w.navs.filter {
            (opening == null || it.valuationDate.isAfter(opening.valuationDate)) &&
                !it.valuationDate.isBefore(yearStart) &&
                !it.valuationDate.isAfter(w.closing.valuationDate)
        }
        val missing = (listOfNotNull(opening) + chain).filter { it.id !in positionsByNav }
        if (missing.isNotEmpty()) {
            return FundProfitAndLoss(
                revaluationGains = null,
                revaluationLosses = null,
                otherInvestmentResult = null,
                managementFees = fees,
                profitLoss = profit,
                linesUnavailableReason = "NAV(s) ${missing.joinToString { it.valuationDate.toString() }} were " +
                    "struck before positions were recorded, so the revaluation in the year is unknown",
            )
        }
        var gains = ZERO_MONEY
        var losses = ZERO_MONEY
        var other = ZERO_MONEY
        var previous = opening
        chain.forEach { nav ->
            val (g, l) = revaluation(
                previous?.let {
                    positionsByNav.getValue(it.id)
                }.orEmpty(),
                positionsByNav.getValue(nav.id),
            )
            val intervalFlows = netCapitalFlowsBetween(w, previous?.valuationDate ?: yearStart, nav.valuationDate)
            val intervalResult =
                nav.figures.netAssets - (previous?.figures?.netAssets ?: ZERO_MONEY) - intervalFlows.net
            // What the interval earned before its fee, less what revaluation explains.
            other += intervalResult + nav.figures.accruedManagementFee - g + l
            gains += g
            losses += l
            previous = nav
        }
        return FundProfitAndLoss(
            revaluationGains = Precision.money(gains),
            revaluationLosses = Precision.money(losses),
            otherInvestmentResult = Precision.money(other),
            managementFees = fees,
            profitLoss = profit,
        )
    }

    private fun entitlements(w: Window, periodOpening: NavRecord?): EntitlementRollForward {
        val flows = netCapitalFlowsBetween(w, periodOpening?.valuationDate ?: w.periodStart, w.closing.valuationDate)
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
        val byClass = positions.groupBy { it.instrumentClass }
            .mapValues { (_, ps) -> Precision.money(ps.fold(ZERO_MONEY) { a, p -> a + p.marketValue }) }
            .toSortedMap()
        val unclassified = positions.count { it.instrumentClass == InstrumentClass.UNCLASSIFIED }
        return PortfolioSummary(
            carryingValue = carrying,
            holdingsCount = positions.count { it.quantity.signum() > 0 },
            cash = Precision.money(closing.figures.grossAssets - carrying),
            carryingValueByClass = byClass,
            unclassifiedCount = unclassified,
            loansOutstanding = if (unclassified > 0) null else byClass[InstrumentClass.LOAN] ?: ZERO_MONEY,
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

    /**
     * Flows priced at NAVs dated in [from, to). With `to` = the closing NAV's date these are the
     * flows the closing NAV's cash contains.
     */
    private fun netCapitalFlowsBetween(w: Window, from: LocalDate, to: LocalDate): CapitalFlows {
        val window = w.txs.filter {
            val d = w.dateOf(it)
            !d.isBefore(from) && d.isBefore(to)
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

/**
 * Price movement on the units of each instrument held at BOTH ends of an interval, split into
 * (gains, losses), each non-negative. Units bought or sold in between are not revalued here:
 * their price is not known to this service, so their result lands in the other line.
 */
private fun revaluation(before: List<NavPosition>, after: List<NavPosition>): Pair<BigDecimal, BigDecimal> {
    val prior = before.groupBy { it.instrumentId }
    var gains = BigDecimal.ZERO.setScale(Precision.MONEY_SCALE)
    var losses = BigDecimal.ZERO.setScale(Precision.MONEY_SCALE)
    after.groupBy { it.instrumentId }.forEach { (instrument, now) ->
        val then = prior[instrument] ?: return@forEach
        val carried = now.sumOf { it.quantity }.min(then.sumOf { it.quantity })
        val move = Precision.money(carried.multiply(now.first().price - then.first().price))
        if (move.signum() > 0) gains += move else losses += move.negate()
    }
    return gains to losses
}
