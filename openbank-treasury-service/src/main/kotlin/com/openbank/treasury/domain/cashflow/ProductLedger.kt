// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.cashflow

import com.openbank.libs.domain.money.RoundingPolicy
import com.openbank.libs.finance.pricing.FraPricer
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.LegSpec.Companion.sha256
import com.openbank.treasury.domain.model.PayReceive
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.domain.model.RateType
import com.openbank.treasury.domain.model.SimpleInterest
import java.math.BigDecimal
import java.math.MathContext
import com.openbank.libs.finance.leg.PayReceive as FinancePayReceive

/**
 * The products whose cash-flow ledger is not the plain sum of their legs' flows (ADR-0344 D2/D4).
 *
 * - **FRA**: the two legs only describe the contract; the one real flow is the settlement
 *   `N·(L−K)·τ / (1+L·τ)` paid at the (adjusted) period start, unknown until the index fixes.
 * - **BOND**: the coupon leg runs from the issue/last-coupon schedule; a purchase keeps only the
 *   flows paid AFTER its value date (earlier ones belong to the seller) and adds the purchase
 *   consideration — clean price on the nominal — and the accrued interest paid to the seller.
 *
 * Deal-level flows have no leg: they sit on [DEAL_LEVEL_LEG]. Nothing here prices: the settlement
 * formula is contractual, accrued interest is the coupon's own day count, both through the kernel.
 */
object ProductLedger {
    const val DEAL_LEVEL_LEG = -1
    private const val SETTLEMENT_PERIOD = -1
    private val HUNDRED = BigDecimal("100")

    fun shape(deal: Deal, flows: List<CashFlow>, ctx: ScheduleContext): List<CashFlow> = when (deal.product) {
        ProductType.FRA -> listOfNotNull(fraSettlement(deal, flows, ctx))
        ProductType.BOND -> bondPurchase(deal, flows, ctx)
        else -> flows
    }

    private fun fraSettlement(deal: Deal, flows: List<CashFlow>, ctx: ScheduleContext): CashFlow? {
        val floatingIndex = deal.legs.indexOfFirst { it.rate.type == RateType.FLOATING }
        val fixedIndex = deal.legs.indexOfFirst { it.rate.type == RateType.FIXED }
        require(floatingIndex >= 0 && fixedIndex >= 0 && deal.legs.size == 2) {
            "an FRA has one floating and one fixed leg"
        }
        val floating = deal.legs[floatingIndex]
        val period = flows.filter { it.legIndex == floatingIndex && it.kind == CashFlowKind.INTEREST }
        require(period.size == 1) { "an FRA is a single period" }
        val p = period.single()
        val start = checkNotNull(p.accrualStart)
        val end = checkNotNull(p.accrualEnd)
        val contract = checkNotNull(deal.legs[fixedIndex].rate.rate)
        val paymentDate = ctx.calendarFor(floating).roll(start, ctx.roll)
        val tau = floating.dayCount.kernel.yearFraction(start, end)
        // The buyer pays fixed: a positive amount is received by the buyer.
        val buyer = deal.legs[fixedIndex].payReceive == PayReceive.PAY
        val raw = p.rate?.let { l ->
            FraPricer.settlementAmount(
                p.notional ?: floating.notional,
                contract.divide(HUNDRED, MathContext.DECIMAL128),
                l.divide(HUNDRED, MathContext.DECIMAL128),
                tau,
                if (buyer) FinancePayReceive.PAY else FinancePayReceive.RECEIVE,
            )
        }
        return CashFlow(
            flowId = ctx.newId(),
            dealId = deal.id,
            scheduleVersion = ctx.scheduleVersion,
            legIndex = DEAL_LEVEL_LEG,
            periodIndex = 0,
            kind = CashFlowKind.INTEREST,
            direction = if (raw == null || raw.signum() >= 0) FlowDirection.IN else FlowDirection.OUT,
            currency = floating.currency,
            accrualStart = start,
            accrualEnd = end,
            fixingDate = p.fixingDate,
            paymentDate = paymentDate,
            notional = p.notional,
            rate = p.rate,
            rateSource = p.rateSource,
            amount = raw?.abs()?.let(RoundingPolicy.TREASURY_AMOUNT::round),
            state = if (raw == null) CashFlowState.PROJECTED else CashFlowState.FIXED,
            legHash = sha256("fra|" + deal.legs.joinToString("|") { it.specHash() }),
        )
    }

    private fun bondPurchase(deal: Deal, flows: List<CashFlow>, ctx: ScheduleContext): List<CashFlow> {
        val terms = deal.header.securityTerms ?: return flows
        val leg = deal.legs.first()
        val value = deal.valueDate
        val bought = leg.payReceive == PayReceive.RECEIVE
        val direction = if (bought) FlowDirection.OUT else FlowDirection.IN
        val hash = sha256("purchase|" + leg.specHash() + "|" + terms)
        fun settlement(kind: CashFlowKind, amount: BigDecimal) = CashFlow(
            flowId = ctx.newId(),
            dealId = deal.id,
            scheduleVersion = ctx.scheduleVersion,
            legIndex = DEAL_LEVEL_LEG,
            periodIndex = SETTLEMENT_PERIOD,
            kind = kind,
            direction = direction,
            currency = leg.currency,
            accrualStart = null,
            accrualEnd = null,
            fixingDate = null,
            paymentDate = value,
            notional = terms.nominal,
            rate = null,
            rateSource = RateSource.FIXED,
            amount = amount,
            state = CashFlowState.FIXED,
            legHash = hash,
        )
        val consideration = terms.cleanPrice?.let {
            settlement(
                CashFlowKind.PRINCIPAL,
                RoundingPolicy.TREASURY_AMOUNT.round(terms.nominal.multiply(it).divide(HUNDRED)),
            )
        }
        val coupon = leg.rate.rate
        val running = flows.firstOrNull {
            it.legIndex == 0 &&
                it.kind == CashFlowKind.INTEREST &&
                it.accrualStart != null &&
                it.accrualEnd != null &&
                !value.isBefore(it.accrualStart) &&
                value.isBefore(it.accrualEnd)
        }
        val accrued = if (coupon != null && running != null) {
            SimpleInterest.of(terms.nominal, coupon, leg.dayCount, checkNotNull(running.accrualStart), value)
        } else {
            BigDecimal.ZERO
        }
        val kept = flows.filter { it.paymentDate.isAfter(value) }
        return listOfNotNull(
            consideration,
            accrued.takeIf { it.signum() > 0 }?.let { settlement(CashFlowKind.INTEREST, it) },
        ) + kept
    }
}
