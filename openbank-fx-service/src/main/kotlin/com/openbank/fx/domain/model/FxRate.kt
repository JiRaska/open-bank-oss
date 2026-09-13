// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.domain.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

enum class RateType { SPOT, FORWARD, INDICATIVE, INTERBANK }
enum class RateSource { ECB, REUTERS, BLOOMBERG, INTERNAL, CNB }

data class FxRate(
    val id: UUID,
    val baseCurrency: String,
    val quoteCurrency: String,
    val bidRate: BigDecimal,
    val askRate: BigDecimal,
    val rateType: RateType,
    val source: RateSource,
    val validFrom: Instant,
    val validTo: Instant,
    val createdAt: Instant,
) {
    val pair: String get() = "$baseCurrency/$quoteCurrency"
    val midRate: BigDecimal get() = (bidRate + askRate).divide(BigDecimal.TWO)
    val spread: BigDecimal get() = askRate - bidRate
    fun isValid(at: Instant) = at.isAfter(validFrom) && at.isBefore(validTo)

    /**
     * The same quote read from the other side: CZK/EUR out of EUR/CZK.
     *
     * Needed because the ČNB fixing — the only live source this platform ingests — publishes
     * FOREIGN→CZK exclusively. Every stored pair is therefore `X/CZK`, and a customer selling
     * CZK to buy EUR asks for a pair that has never existed and never will. There is no amount
     * of retrying that fixes that.
     *
     * **The sides swap, and that is the whole point.** The bank BUYS the base at [bidRate] and
     * SELLS it at [askRate]; in the inverted pair those roles trade places, so
     * `inverted().bidRate = 1 / askRate` and `inverted().askRate = 1 / bidRate`. Taking a naive
     * `1 / bidRate` for both would quote the customer the wrong side of the spread on every
     * CZK→foreign exchange — a systematic loss, in the customer's favour on one leg and the
     * bank's on the other, which is exactly the kind of error that does not announce itself.
     *
     * [id] is carried over unchanged: this is a view of the SAME quote, not a new one, and
     * FxConversion.rateId must keep pointing at the row the price came from.
     */
    fun inverted(): FxRate = copy(
        baseCurrency = quoteCurrency,
        quoteCurrency = baseCurrency,
        bidRate = BigDecimal.ONE.divide(askRate, INVERSE_SCALE, RoundingMode.HALF_UP),
        askRate = BigDecimal.ONE.divide(bidRate, INVERSE_SCALE, RoundingMode.HALF_UP),
    )

    private companion object {
        /** Matches the numeric(18,8) the rates are stored at, so a round trip does not drift. */
        const val INVERSE_SCALE = 8
    }
}

data class FxConversion(
    val id: UUID,
    val idempotencyKey: String,
    val partyId: UUID,
    val accountId: UUID?,
    val fromCurrency: String,
    val toCurrency: String,
    val fromAmountMinorUnits: Long,
    val toAmountMinorUnits: Long,
    val appliedRate: BigDecimal,
    val feeMinorUnits: Long,
    val rateId: UUID,
    val status: FxConversionStatus,
    val createdAt: Instant,
    val settledAt: Instant?,
)

enum class FxConversionStatus { PENDING, SETTLED, FAILED, REVERSED }

/**
 * Pure conversion arithmetic (issue #469 item 3 — ADR-0011 property testing). Extracted out of
 * [FxService][com.openbank.fx.application.usecase.FxService].convert() so the margin math is
 * callable from a property test without instantiating the use case and its 8 mocked ports.
 */
object FxConversionMath {
    private val FEE_RATE = BigDecimal("0.005")

    /** `fromAmount * appliedRate`, rounded HALF_UP to whole minor units. */
    fun convertedAmountMinorUnits(fromAmountMinorUnits: Long, appliedRate: BigDecimal): Long =
        BigDecimal(fromAmountMinorUnits).multiply(appliedRate).setScale(0, RoundingMode.HALF_UP).toLong()

    /** The bank's 0.5% margin on the source amount, rounded HALF_UP to whole minor units. */
    fun feeMinorUnits(fromAmountMinorUnits: Long): Long =
        BigDecimal(fromAmountMinorUnits).multiply(FEE_RATE).setScale(0, RoundingMode.HALF_UP).toLong()
}
