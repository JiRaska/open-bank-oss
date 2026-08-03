// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.domain.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
    /**
     * The id of the stored quote this one was derived from, or `null` when this IS the stored
     * quote. Set only by [inverted]; nothing that reads a row out of `fx_rates` ever sets it,
     * which is what makes "is this a derived quote?" answerable rather than inferred.
     */
    val derivedFrom: UUID? = null,
) {
    val pair: String get() = "$baseCurrency/$quoteCurrency"
    val midRate: BigDecimal get() = (bidRate + askRate).divide(BigDecimal.TWO)
    val spread: BigDecimal get() = askRate - bidRate
    fun isValid(at: Instant = Instant.EPOCH) = at.isAfter(validFrom) && at.isBefore(validTo)

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
     * ## Identity (#3374)
     *
     * The first version of this method carried [id] over unchanged, so `EUR/CZK` and `CZK/EUR`
     * answered under ONE identifier with different pairs and inverted numbers — an id that no
     * longer identified what it named. A conversion audit record citing that id cannot be replayed
     * to a direction.
     *
     * So the derived quote gets its own [id], [derivedId] over `(sourceId, "inverse")`: stable
     * across calls (a client may cache by id) and distinct per direction. The link back to the row
     * is not lost, it is made EXPLICIT in [derivedFrom] — which is the half the caller could not
     * see before.
     *
     * **`FxConversion.rateId` must still reference the STORED row**, and not merely as bookkeeping:
     * `fx_conversions.rate_id` is `NOT NULL REFERENCES fx_rates(id)`, and a derived id has no row,
     * so writing it would fail every CZK→foreign conversion on a foreign-key violation. `FxService`
     * therefore persists `rate.derivedFrom ?: rate.id`. Nulling the id instead — the shape #3374
     * first proposed — would have hit the same constraint from the other side.
     */
    fun inverted(): FxRate = copy(
        id = derivedId(id),
        derivedFrom = id,
        baseCurrency = quoteCurrency,
        quoteCurrency = baseCurrency,
        bidRate = BigDecimal.ONE.divide(askRate, INVERSE_SCALE, RoundingMode.HALF_UP),
        askRate = BigDecimal.ONE.divide(bidRate, INVERSE_SCALE, RoundingMode.HALF_UP),
    )

    private companion object {
        /** Matches the numeric(18,8) the rates are stored at, so a round trip does not drift. */
        const val INVERSE_SCALE = 8

        /** Namespace separator; part of the hashed input, so changing it changes every derived id. */
        const val INVERSE_NAMESPACE = "openbank.fx.rate.inverse"

        private const val UUID_BYTES = 16
        private const val VERSION_BYTE = 6
        private const val VARIANT_BYTE = 8
        private const val LOW_NIBBLE = 0x0f
        private const val UUID_VERSION_8 = 0x80
        private const val VARIANT_MASK = 0x3f
        private const val VARIANT_RFC = 0x80
        private const val BITS_PER_BYTE = 8
        private const val BYTE_MASK = 0xffL

        /**
         * A name-based UUID for the inverse of [source], RFC 9562 version 8 (vendor-defined) over
         * SHA-256 of `"<namespace>:<source>"`.
         *
         * Version 8 rather than the version 5 #3374 suggested because v5 is *defined* as SHA-1, and
         * a SHA-1 call in a money-path service is a finding every scanner in this repo will raise —
         * for a construction that is not a security control at all. v8 is exactly the escape hatch
         * RFC 9562 provides for a name-based scheme with a different digest, and the property that
         * matters here is identical: same input, same id, forever.
         */
        fun derivedId(source: UUID): UUID {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$INVERSE_NAMESPACE:$source".toByteArray(StandardCharsets.UTF_8))
                .copyOf(UUID_BYTES)
            digest[VERSION_BYTE] = ((digest[VERSION_BYTE].toInt() and LOW_NIBBLE) or UUID_VERSION_8).toByte()
            digest[VARIANT_BYTE] = ((digest[VARIANT_BYTE].toInt() and VARIANT_MASK) or VARIANT_RFC).toByte()
            var hi = 0L
            var lo = 0L
            for (i in 0 until BITS_PER_BYTE) hi = (hi shl BITS_PER_BYTE) or (digest[i].toLong() and BYTE_MASK)
            for (i in BITS_PER_BYTE until UUID_BYTES) lo = (lo shl BITS_PER_BYTE) or (digest[i].toLong() and BYTE_MASK)
            return UUID(hi, lo)
        }
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
