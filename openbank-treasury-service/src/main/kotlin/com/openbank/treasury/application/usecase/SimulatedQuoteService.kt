// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.usecase

import com.openbank.treasury.application.port.`in`.QuoteBoard
import com.openbank.treasury.application.port.`in`.TreasuryQuoteUseCase
import com.openbank.treasury.application.port.out.CounterpartyRepository
import com.openbank.treasury.application.port.out.CurveSetPort
import com.openbank.treasury.domain.model.CounterpartyKind
import com.openbank.treasury.domain.model.CurveSetView
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.domain.model.QuotePricer
import com.openbank.treasury.domain.model.QuoteUnavailableException
import com.openbank.treasury.domain.model.SimulatedQuote

/**
 * The simulated counterparty set's quotes (ADR-0315 D9): every SYNTHETIC bank counterparty with a
 * configured half-spread ([spreadsBp], `openbank.treasury.simulated-market.quotes.spread-bp`)
 * quotes the risk engine's latest curve set ± that spread. A counterparty that is not synthetic,
 * or has no spread, does not quote — a real bank's price is never invented.
 *
 * [enabled] is `simulated-market.enabled AND simulated-market.quotes.enabled`: off, the endpoint
 * answers 409 and the simulated market confirms without a quote check (the pre-quote behaviour).
 */
class SimulatedQuoteService(
    private val curves: CurveSetPort,
    private val counterparties: CounterpartyRepository,
    private val spreadsBp: Map<String, Int>,
    val enabled: Boolean,
) : TreasuryQuoteUseCase {

    override suspend fun quotes(product: ProductType, currency: String, tenorDays: Int): QuoteBoard {
        check(enabled) {
            "quotes come from the simulated market (ADR-0315 D9), which is off here " +
                "(openbank.treasury.simulated-market.enabled / .quotes.enabled)"
        }
        require(product in QuotePricer.QUOTED_PRODUCTS) { "quotes are for ${QuotePricer.QUOTED_PRODUCTS.sorted()}" }
        require(currency in Deal.SUPPORTED_CURRENCIES) { "currency must be one of ${Deal.SUPPORTED_CURRENCIES}" }
        require(tenorDays in QuotePricer.MIN_TENOR_DAYS..QuotePricer.MAX_TENOR_DAYS) {
            "tenorDays must be ${QuotePricer.MIN_TENOR_DAYS}..${QuotePricer.MAX_TENOR_DAYS}"
        }
        val set = latest()
        val quoting = counterparties.list()
            .filter { it.synthetic && it.kind == CounterpartyKind.BANK && spreadsBp.containsKey(it.id) }
            .sortedBy { it.id }
        val quotes = quoting.map { cp -> QuotePricer.quote(set, cp.id, currency, tenorDays, spreadsBp.getValue(cp.id)) }
        return QuoteBoard(product, currency, tenorDays, quotes)
    }

    /**
     * The quote the simulated counterparty of [deal] holds against it now, or null when [deal] is
     * not one a simulated counterparty quotes (not an MM product, not a synthetic bank, no spread,
     * or quotes are off) — the simulated market then confirms it as before quotes existed.
     */
    suspend fun quoteFor(deal: Deal): SimulatedQuote? {
        if (!enabled || deal.product !in QuotePricer.QUOTED_PRODUCTS) return null
        val spread = spreadsBp[deal.counterpartyId] ?: return null
        val cp = counterparties.findById(deal.counterpartyId) ?: return null
        if (!cp.synthetic || cp.kind != CounterpartyKind.BANK) return null
        return QuotePricer.quote(latest(), cp.id, deal.currency, deal.days.toInt(), spread)
    }

    private suspend fun latest(): CurveSetView =
        curves.latest() ?: throw QuoteUnavailableException("the risk engine holds no curve set yet")
}
