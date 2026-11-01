// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.fx.domain.model.FxConversion
import com.openbank.fx.domain.model.FxConversionMath
import com.openbank.fx.domain.model.FxConversionStatus
import com.openbank.fx.domain.model.FxRate
import com.openbank.fx.domain.model.RateSource
import com.openbank.fx.domain.model.RateType
import com.openbank.libs.domain.money.RoundingPolicy
import com.openbank.simulation.engine.SimulatedWriteFailure
import com.openbank.simulation.runner.World
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Issue #14366 (Test Intelligence simulation gap, money path): one step of the FX conversion
 * path, modelled directly against the **real `fx-service` domain** — the `FxRate` fixing with
 * its ČNB-origin `inverted()` side-swap (the stored fixing is FOREIGN→CZK only, so a customer
 * selling CZK to buy foreign currency is quoted off `1/askRate`, never a naive `1/bidRate`) and
 * the `FxConversionMath` margin/target-amount arithmetic — not a re-model of either, the same
 * way [DomesticClearingScenario] builds on the real `DomesticPayment` aggregate and scheme rule
 * (ADR-0100). The domain package is framework-free (ADR-0002).
 *
 * A step mirrors the production ladder: store a fresh ČNB-style FOREIGN→CZK indicative fixing,
 * open a conversion for a customer selling the world currency (CZK) into a randomly drawn
 * foreign currency — priced through the REAL [FxRate.inverted] side-swap, at the bank's bid —
 * compute the target amount and the 0.5% margin with the REAL [FxConversionMath], and settle.
 * An injected write fault at the booking step marks the conversion FAILED before anything is
 * committed; an injected post-commit conflict marks it REVERSED. Every step leaves the
 * conversion in a terminal status, which `MoneyPathInvariants.fxConversionCompleteness` asserts
 * every sweep.
 */
object FxConversionScenario {

    private const val MAX_FROM_MINOR_UNITS = 500_000L // up to 5000.00 CZK per conversion

    /**
     * ČNB-style indicative fixings, FOREIGN→CZK per one unit. [spreadCzk] is the full
     * bid/ask spread around the mid; JPY (0 fraction digits) is included deliberately — the
     * fraction-digit scaling inside [FxConversionMath.convertedAmountMinorUnits] is only
     * exercised when the two currencies disagree on minor-unit exponents.
     */
    private data class FixingQuote(val currency: String, val midCzkPerUnit: String, val spreadCzk: String)

    private val FIXINGS = listOf(
        FixingQuote("EUR", "25.10", "0.20"),
        FixingQuote("USD", "21.40", "0.30"),
        FixingQuote("GBP", "29.80", "0.40"),
        FixingQuote("CHF", "26.60", "0.40"),
        FixingQuote("JPY", "14.20", "0.20"),
    )

    fun step(world: World) {
        val random = world.context.random
        val now = world.context.clock.instant()
        val quote = FIXINGS[random.nextInt(FIXINGS.size)]

        // 1. Store a fresh ČNB-style fixing FOREIGN→CZK — the only direction the live source
        //    publishes, which is exactly why the inverted-side pricing below is real logic and
        //    not a convenience.
        val rate = newFixing(world, quote, now)
        world.fxRates.add(rate)
        world.audit.append("fx rate ${rate.id} stored: ${rate.pair}")

        // 2. The customer sells CZK to buy the foreign currency. The CZK/FOREIGN pair has never
        //    existed and never will: the REAL FxRate.inverted() swaps the sides (the bank's bid
        //    on the inverted pair is 1/ask of the stored one), and the customer — the party
        //    SELLING CZK to the bank — is quoted that bid.
        val applied = rate.inverted()
        val fromMinorUnits = random.nextLong(1L, MAX_FROM_MINOR_UNITS)
        val priced = PricedConversion(
            toCurrency = quote.currency,
            fromAmountMinorUnits = fromMinorUnits,
            toAmountMinorUnits = FxConversionMath.convertedAmountMinorUnits(
                fromMinorUnits,
                world.currency,
                quote.currency,
                applied.bidRate,
            ),
            feeMinorUnits = FxConversionMath.feeMinorUnits(fromMinorUnits),
            appliedRate = applied.bidRate,
        )

        // 3. Book the conversion. An injected write fault here means the booking never
        //    committed: the conversion is FAILED and nothing else moves.
        val conversion = bookConversion(world, rate, priced, now) ?: return

        // 4. A post-commit conflict forces the compensation branch: the conversion reverses and
        //    the settlement stamp is cleared.
        if (world.context.faults.shouldConflict()) {
            val reversed = conversion.copy(
                status = FxConversionStatus.REVERSED,
                settledAt = null,
            )
            world.fxConversions[world.fxConversions.lastIndex] = reversed
            world.audit.append("fx ${reversed.id} reversed: post-commit conflict")
            return
        }

        // 5. Happy path: settle the conversion, stamping settledAt.
        val settled = conversion.copy(
            status = FxConversionStatus.SETTLED,
            settledAt = world.context.clock.instant(),
        )
        world.fxConversions[world.fxConversions.lastIndex] = settled
        world.audit.append("fx ${settled.id} settled")
    }

    private fun newFixing(world: World, quote: FixingQuote, now: Instant): FxRate {
        val mid = BigDecimal(quote.midCzkPerUnit)
        val halfSpread = BigDecimal(quote.spreadCzk).divide(BigDecimal.TWO)
        return FxRate(
            id = world.context.random.nextUuid(),
            baseCurrency = quote.currency,
            quoteCurrency = world.currency,
            bidRate = mid.subtract(halfSpread),
            askRate = mid.add(halfSpread),
            rateType = RateType.INDICATIVE,
            source = RateSource.CNB,
            validFrom = now.minus(1, ChronoUnit.HOURS),
            validTo = now.plus(1, ChronoUnit.HOURS),
            createdAt = now,
        )
    }

    private fun bookConversion(world: World, rate: FxRate, priced: PricedConversion, now: Instant): FxConversion? {
        val booked: FxConversion? = try {
            if (world.context.faults.shouldFailWrite()) {
                throw SimulatedWriteFailure("fx booking failed for ${rate.id}")
            }
            FxConversion(
                id = world.context.random.nextUuid(),
                idempotencyKey = "fx-${world.context.random.nextUuid()}",
                partyId = world.customerAccounts.first(),
                accountId = null,
                fromCurrency = world.currency,
                toCurrency = priced.toCurrency,
                fromAmountMinorUnits = priced.fromAmountMinorUnits,
                toAmountMinorUnits = priced.toAmountMinorUnits,
                appliedRate = priced.appliedRate,
                feeMinorUnits = priced.feeMinorUnits,
                rateId = rate.id,
                status = FxConversionStatus.PENDING,
                createdAt = now,
                settledAt = null,
            ).also { world.fxConversions.add(it) }
        } catch (e: SimulatedWriteFailure) {
            val failed = FxConversion(
                id = world.context.random.nextUuid(),
                idempotencyKey = "fx-${world.context.random.nextUuid()}",
                partyId = world.customerAccounts.first(),
                accountId = null,
                fromCurrency = world.currency,
                toCurrency = priced.toCurrency,
                fromAmountMinorUnits = priced.fromAmountMinorUnits,
                toAmountMinorUnits = priced.toAmountMinorUnits,
                appliedRate = priced.appliedRate,
                feeMinorUnits = priced.feeMinorUnits,
                rateId = rate.id,
                status = FxConversionStatus.FAILED,
                createdAt = now,
                settledAt = null,
            )
            world.fxConversions.add(failed)
            world.audit.append("fx ${failed.id} failed: ${e.message}")
            null
        }
        return booked
    }

    /** The conversion amounts priced by [step] before the booking write is attempted. */
    private data class PricedConversion(
        val toCurrency: String,
        val fromAmountMinorUnits: Long,
        val toAmountMinorUnits: Long,
        val feeMinorUnits: Long,
        val appliedRate: BigDecimal,
    )

    /** The REAL inverted-side bid the scenario prices at: `1 / ask` of the stored fixing. */
    fun expectedCustomerBid(askRate: BigDecimal): BigDecimal = RoundingPolicy.FX_RATE.divide(BigDecimal.ONE, askRate)
}
