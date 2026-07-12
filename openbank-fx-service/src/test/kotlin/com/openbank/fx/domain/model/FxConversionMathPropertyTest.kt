// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.domain.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Margin-preservation property tests (ADR-0011, issue #469 item 3). [FxServiceTest] pins specific
 * hand-picked amounts/rates for [FxConversionMath]; this suite asserts the margin can't be
 * arithmetically eliminated, inverted, or made to shrink with scale across a wide input space —
 * the "for all" guarantee example tests can't give.
 */
class FxConversionMathPropertyTest {

    // Non-negative source amounts up to ~10 billion major units — comfortably past any real
    // transfer while staying inside Long/BigDecimal.multiply headroom for a 4-decimal-place rate.
    private val amountArb = Arb.long(0L, 999_999_999_999L)

    // 1 tick = 0.0001, matching the 4-decimal-place rates used fleet-wide (e.g. bid=24.90, ask=25.10).
    private val rateTickArb = Arb.long(1L, 1_000_000L)
    private val rateArb = rateTickArb.map { BigDecimal(it).movePointLeft(4) }

    private fun orderedPairArb(arb: Arb<Long>): Arb<Pair<Long, Long>> =
        Arb.bind(arb, arb) { a, b -> minOf(a, b) to maxOf(a, b) }

    private fun bidAskArb(): Arb<Pair<BigDecimal, BigDecimal>> = Arb.bind(rateTickArb, rateTickArb) { a, b ->
        BigDecimal(minOf(a, b)).movePointLeft(4) to BigDecimal(maxOf(a, b)).movePointLeft(4)
    }

    @Test
    fun `fee is never negative and never exceeds the source amount`(): Unit = runBlocking {
        checkAll(amountArb) { fromAmount ->
            val fee = FxConversionMath.feeMinorUnits(fromAmount)
            assertThat(fee).isGreaterThanOrEqualTo(0L)
            assertThat(fee).isLessThanOrEqualTo(fromAmount)
        }
    }

    @Test
    fun `fee is always within half a minor unit of the exact 0_5 percent value`(): Unit = runBlocking {
        checkAll(amountArb) { fromAmount ->
            val fee = FxConversionMath.feeMinorUnits(fromAmount)
            val exact = BigDecimal(fromAmount).multiply(BigDecimal("0.005"))
            assertThat(BigDecimal(fee).subtract(exact).abs()).isLessThanOrEqualTo(BigDecimal("0.5"))
        }
    }

    @Test
    fun `fee never shrinks as the source amount grows`(): Unit = runBlocking {
        checkAll(orderedPairArb(amountArb)) { (smaller, larger) ->
            val feeSmaller = FxConversionMath.feeMinorUnits(smaller)
            val feeLarger = FxConversionMath.feeMinorUnits(larger)
            assertThat(feeLarger).isGreaterThanOrEqualTo(feeSmaller)
        }
    }

    @Test
    fun `converted amount never shrinks as the source amount grows, for a fixed rate`(): Unit = runBlocking {
        checkAll(orderedPairArb(amountArb), rateArb) { (smaller, larger), rate ->
            val convertedSmaller = FxConversionMath.convertedAmountMinorUnits(smaller, rate)
            val convertedLarger = FxConversionMath.convertedAmountMinorUnits(larger, rate)
            assertThat(convertedLarger).isGreaterThanOrEqualTo(convertedSmaller)
        }
    }

    @Test
    fun `converted amount is never negative for a non-negative source amount and rate`(): Unit = runBlocking {
        checkAll(amountArb, rateArb) { fromAmount, rate ->
            assertThat(FxConversionMath.convertedAmountMinorUnits(fromAmount, rate)).isGreaterThanOrEqualTo(0L)
        }
    }

    @Test
    fun `converting at the ask rate is never smaller than converting the same amount at the bid rate`(): Unit =
        runBlocking {
            checkAll(amountArb, bidAskArb()) { fromAmount, (bid, ask) ->
                val atBid = FxConversionMath.convertedAmountMinorUnits(fromAmount, bid)
                val atAsk = FxConversionMath.convertedAmountMinorUnits(fromAmount, ask)
                assertThat(atAsk).isGreaterThanOrEqualTo(atBid)
            }
        }

    @Test
    fun `FxRate mid rate always sits between bid and ask, and spread is never negative`(): Unit = runBlocking {
        checkAll(bidAskArb()) { (bid, ask) ->
            val rate = fxRate(bid, ask)
            assertThat(rate.spread).isGreaterThanOrEqualTo(BigDecimal.ZERO)
            assertThat(rate.midRate).isGreaterThanOrEqualTo(bid)
            assertThat(rate.midRate).isLessThanOrEqualTo(ask)
        }
    }

    private fun fxRate(bid: BigDecimal, ask: BigDecimal) = FxRate(
        id = java.util.UUID.randomUUID(),
        baseCurrency = "EUR",
        quoteCurrency = "CZK",
        bidRate = bid,
        askRate = ask,
        rateType = RateType.SPOT,
        source = RateSource.ECB,
        validFrom = java.time.Instant.parse("2026-01-01T00:00:00Z"),
        validTo = java.time.Instant.parse("2026-12-31T23:59:59Z"),
        createdAt = java.time.Instant.parse("2026-01-01T00:00:00Z"),
    )
}
